package dev.tinyvault;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal FileLu S5 storage handling. The bucket holds exactly one backup plus
 * one short-lived upload slot:
 *
 * <pre>
 * backups/current.zip    latest verified backup
 * backups/uploading.zip  temp upload target, deleted after every attempt
 * </pre>
 *
 * The new archive is uploaded to the temp slot and verified before the old
 * current.zip is touched, so a failed or interrupted run always leaves the
 * existing backup intact. FileLu S5 does not document server-side copy, so
 * promotion is a fresh upload of the same local file. Large archives are sent
 * as multipart uploads (FileLu's gateway rejects long single-request PUTs),
 * and interrupted uploads are aborted so their parts never consume quota.
 */
final class S3BackupStore implements AutoCloseable {
    static final String PREFIX = "backups/";
    private static final String TEMP_KEY = PREFIX + "uploading.zip";
    private static final String CURRENT_KEY = PREFIX + "current.zip";
    private static final long PART_SIZE = 64L * 1024 * 1024;
    private static final int MAX_PARALLEL_PARTS = 3;

    private final S3Client s3;
    private final String bucket;

    S3BackupStore(String endpoint, String region, boolean pathStyle, String bucket, String accessKey, String secretKey) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED);
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        this.s3 = builder.build();
        this.bucket = bucket;
    }

    /**
     * Uploads the archive to uploading.zip and verifies it (size + checksum).
     * Only then is the old current.zip removed and the confirmed archive
     * promoted to current.zip, followed by a final verification. Throws if any
     * step fails; existing backups are only replaced after the replacement is
     * confirmed.
     */
    void rotate(Path archive, long size) throws IOException {
        deleteQuietly(TEMP_KEY);
        abortStaleUploads();
        upload(TEMP_KEY, archive);
        verifyUpload(TEMP_KEY, archive, size);

        deleteQuietly(CURRENT_KEY);
        upload(CURRENT_KEY, archive);
        verifyUpload(CURRENT_KEY, archive, size);
        deleteQuietly(TEMP_KEY);
    }

    private void upload(String key, Path file) throws IOException {
        long size = Files.size(file);
        if (size <= PART_SIZE) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(file));
            return;
        }
        CreateMultipartUploadResponse create = s3.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build());
        try {
            List<CompletedPart> parts = uploadParts(key, create.uploadId(), file, size);
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(create.uploadId())
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build());
        } catch (Exception failure) {
            try {
                s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(bucket).key(key).uploadId(create.uploadId()).build());
            } catch (Exception ignored) {
                // interrupted multipart uploads are also aborted at the start of the next rotation
            }
            throw failure;
        }
    }

    /** Uploads all parts (up to three concurrently) and returns them in order. */
    private List<CompletedPart> uploadParts(String key, String uploadId, Path file, long size) throws IOException {
        int partCount = (int) ((size + PART_SIZE - 1) / PART_SIZE);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(partCount, MAX_PARALLEL_PARTS), runnable -> {
            Thread thread = new Thread(runnable, "TinyVault-part");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<CompletableFuture<CompletedPart>> futures = new ArrayList<>(partCount);
            for (int partNumber = 1; partNumber <= partCount; partNumber++) {
                final int number = partNumber;
                futures.add(CompletableFuture.supplyAsync(
                        () -> uploadPart(key, uploadId, file, number, size), pool));
            }
            List<CompletedPart> parts = new ArrayList<>(partCount);
            try {
                for (CompletableFuture<CompletedPart> future : futures) {
                    parts.add(future.join());
                }
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof java.io.UncheckedIOException unchecked) {
                    cause = unchecked.getCause();
                }
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException(String.valueOf(cause), cause);
            }
            parts.sort(Comparator.comparingInt(CompletedPart::partNumber));
            return parts;
        } finally {
            pool.shutdownNow();
        }
    }

    private CompletedPart uploadPart(String key, String uploadId, Path file, int partNumber, long size) {
        long offset = (long) (partNumber - 1) * PART_SIZE;
        long length = Math.min(PART_SIZE, size - offset);
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(offset);
            UploadPartResponse response = s3.uploadPart(UploadPartRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .contentLength(length)
                    .build(), RequestBody.fromInputStream(in, length));
            return CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to read part " + partNumber + " of " + file, e);
        }
    }

    /** Aborts leftover multipart uploads of our keys so interrupted runs never leak quota. */
    private void abortStaleUploads() {
        try {
            ListMultipartUploadsResponse uploads = s3.listMultipartUploads(
                    ListMultipartUploadsRequest.builder().bucket(bucket).prefix(PREFIX).build());
            for (MultipartUpload upload : uploads.uploads()) {
                try {
                    s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                            .bucket(bucket).key(upload.key()).uploadId(upload.uploadId()).build());
                } catch (Exception ignored) {
                    // best effort
                }
            }
        } catch (Exception ignored) {
            // listing is best effort; rotation continues
        }
    }

    private void verifyUpload(String key, Path file, long size) throws IOException {
        HeadObjectResponse head = head(key);
        if (head.contentLength() != size) {
            throw new IOException(key + ": expected " + size + " bytes but remote object has " + head.contentLength());
        }
        String etag = head.eTag();
        if (etag != null && !etag.contains("-") && !md5Hex(file).equalsIgnoreCase(stripQuotes(etag))) {
            throw new IOException(key + ": checksum mismatch after upload");
        }
    }

    private HeadObjectResponse head(String key) {
        return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    }

    private void deleteQuietly(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException ignored) {
            // already absent
        }
    }

    private static String stripQuotes(String etag) {
        return etag.replace("\"", "");
    }

    private static String md5Hex(Path file) throws IOException {
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm unavailable", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                md5.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(md5.getDigestLength() * 2);
        for (byte b : md5.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    @Override
    public void close() {
        s3.close();
    }
}
