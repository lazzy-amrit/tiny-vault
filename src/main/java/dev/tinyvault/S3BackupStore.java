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
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Storage rotation keeping exactly two remote backups:
 *
 * <pre>
 * backups/current.zip   latest successful backup
 * backups/old.zip       previous successful backup
 * </pre>
 *
 * Rotation per run, in this order:
 * <ol>
 * <li>the oldest slot (old.zip) is deleted first — and, in s5 mode, its trash
 *     entry is purged immediately so the freed space is actually usable;</li>
 * <li>the previous current.zip is preserved: s3 mode uses server-side
 *     CopyObject, s5 mode (FileLu) has no working copy, so the previous
 *     current.zip is downloaded to a local temp file;</li>
 * <li>the new archive is uploaded straight to current.zip (multipart uploads
 *     are atomic: a failed upload leaves the old object in place) and
 *     verified;</li>
 * <li>the preserved copy becomes the new old.zip and is verified.</li>
 * </ol>
 * There is no remote temp object, so nothing lingers between runs. A failed
 * run leaves any surviving backup untouched.
 */
final class S3BackupStore implements AutoCloseable {
    static final String PREFIX = "backups/";
    private static final String LEGACY_TEMP_KEY = PREFIX + "uploading.zip";
    private static final String CURRENT_KEY = PREFIX + "current.zip";
    private static final String OLD_KEY = PREFIX + "old.zip";
    private static final long PART_SIZE = 64L * 1024 * 1024;
    private static final int MAX_PARALLEL_PARTS = 3;

    private final S3Client s3;
    private final String bucket;
    private final boolean fileLuMode;
    private final FileLuTrashPurger trashPurger;

    S3BackupStore(String endpoint, String region, boolean pathStyle, String bucket, String accessKey, String secretKey,
                  boolean fileLuMode, FileLuTrashPurger trashPurger) {
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
        this.fileLuMode = fileLuMode;
        this.trashPurger = trashPurger;
    }

    void rotate(Path archive, long size) throws IOException {
        deleteIfPresent(LEGACY_TEMP_KEY); // leftovers from older plugin versions
        abortStaleUploads();
        if (fileLuMode) {
            trashPurger.purgeBackupNames();
        }
        deleteIfPresent(OLD_KEY);
        if (fileLuMode) {
            trashPurger.purgeBackupNames(); // immediately free the space old.zip just moved to trash
        }

        Path previous = archive.resolveSibling("previous.zip");
        try {
            if (objectExists(CURRENT_KEY)) {
                if (fileLuMode) {
                    download(CURRENT_KEY, previous);
                } else {
                    s3.copyObject(CopyObjectRequest.builder()
                            .bucket(bucket)
                            .copySource(bucket + "/" + CURRENT_KEY)
                            .destinationBucket(bucket)
                            .destinationKey(OLD_KEY)
                            .build());
                }
            }
            upload(CURRENT_KEY, archive);
            verifyUpload(CURRENT_KEY, archive, size);
            if (Files.exists(previous)) {
                long previousSize = Files.size(previous);
                upload(OLD_KEY, previous);
                verifyUpload(OLD_KEY, previous, previousSize);
            }
        } finally {
            try {
                Files.deleteIfExists(previous);
            } catch (IOException ignored) {
                // temp file lives inside TinyVault's data folder and is excluded from archives
            }
        }
    }

    /** Sizes of the two backup objects for the /vault detail command; null when absent. */
    Map<String, Long> backupSizes() {
        Map<String, Long> sizes = new LinkedHashMap<>();
        for (String key : new String[]{CURRENT_KEY, OLD_KEY}) {
            Long size = null;
            try {
                size = head(key).contentLength();
            } catch (NoSuchKeyException ignored) {
            } catch (Exception ignored) {
            }
            sizes.put(key, size);
        }
        return sizes;
    }

    /* ---- upload ---- */

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

    /* ---- verification and small operations ---- */

    private void verifyUpload(String key, Path file, long size) throws IOException {
        // FileLu's gateway is eventually consistent: right after an upload (or
        // a delete) HEAD can still report the previous object. Re-check a few
        // times before failing.
        IOException failure = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            HeadObjectResponse head = head(key);
            if (head.contentLength() == size) {
                String etag = head.eTag();
                if (etag == null || etag.contains("-")
                        || md5Hex(file).equalsIgnoreCase(stripQuotes(etag))) {
                    return;
                }
                failure = new IOException(key + ": checksum mismatch after upload");
            } else {
                failure = new IOException(key + ": expected " + size + " bytes but remote object has "
                        + head.contentLength() + " (remote state may still be propagating)");
            }
            try {
                Thread.sleep(5000L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure;
            }
        }
        throw failure;
    }

    private void download(String key, Path destination) throws IOException {
        try (var object = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            Files.copy(object, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private HeadObjectResponse head(String key) {
        return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    }

    private boolean objectExists(String key) {
        try {
            head(key);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /**
     * Conditional delete: only removes an object that exists, so absent keys
     * never produce pointless trash entries on FileLu.
     */
    private void deleteIfPresent(String key) {
        if (objectExists(key)) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (NoSuchKeyException ignored) {
                // removed concurrently
            }
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
