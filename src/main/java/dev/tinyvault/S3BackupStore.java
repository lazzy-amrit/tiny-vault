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
    /** Bytes of the stored object read back directly to bypass stale HEAD metadata. */
    private static final int TAIL_CHECK_BYTES = 4096;
    /**
     * Backoff between verification attempts. FileLu's gateway can serve the
     * metadata of a previous object for several minutes after an overwrite;
     * this gives the gateway over twenty minutes before a run is reported as
     * failed, and every attempt also reads the object itself as an escape hatch
     * (see tailMatchesRemote).
     */
    private static final long[] VERIFY_WAITS_MILLIS = {
            20_000L, 30_000L, 45_000L, 60_000L, 90_000L, 90_000L,
            120_000L, 120_000L, 180_000L, 180_000L, 240_000L, 240_000L};

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

        // Size of current.zip before this run. A HEAD that reports exactly this
        // size after the upload is provably stale metadata (the new upload
        // almost never happens to have the exact previous size), which lets the
        // verification log the situation accurately instead of guessing.
        long previousCurrentSize = -1;
        try {
            if (objectExists(CURRENT_KEY)) {
                previousCurrentSize = head(CURRENT_KEY).contentLength();
            }
        } catch (Exception ignored) {
            // best effort; verification works without it
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
            verifyUpload(CURRENT_KEY, archive, size, previousCurrentSize);
            if (Files.exists(previous)) {
                long previousSize = Files.size(previous);
                upload(OLD_KEY, previous);
                verifyUpload(OLD_KEY, previous, previousSize, -1);
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

    private void verifyUpload(String key, Path file, long size, long previousSize) throws IOException {
        // FileLu's gateway is eventually consistent: right after an upload (or
        // a delete) HEAD can report the previous object for minutes. Re-check
        // patiently before failing, and read the tail of the stored object
        // directly on every miss — actual object bytes are not subject to the
        // stale-metadata cache the way HEAD is, so a matching tail proves the
        // upload landed and ends the wait early. The next scheduled run
        // retries anyway if everything really is broken.
        IOException failure = null;
        int attempts = VERIFY_WAITS_MILLIS.length;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                HeadObjectResponse head = head(key);
                long remote = head.contentLength();
                if (remote == size) {
                    String etag = head.eTag();
                    if (etag == null || etag.contains("-")
                            || md5Hex(file).equalsIgnoreCase(stripQuotes(etag))) {
                        return;
                    }
                    failure = new IOException(key + ": checksum mismatch after upload");
                } else if (previousSize >= 0 && remote == previousSize) {
                    failure = new IOException(key + ": storage still reports the previous object (" + remote
                            + " bytes; new backup is " + size + "), waiting for its metadata to refresh");
                } else {
                    failure = new IOException(key + ": expected " + size + " bytes but remote object has "
                            + remote + " (remote state may still be propagating)");
                }
            } catch (NoSuchKeyException e) {
                failure = new IOException(key + ": temporarily not readable after upload (propagation)", e);
            }
            if (tailMatchesRemote(key, file, size)) {
                return;
            }
            try {
                Thread.sleep(VERIFY_WAITS_MILLIS[attempt]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure;
            }
        }
        throw failure;
    }

    /**
     * Reads the last few bytes of the stored object with a ranged GET and
     * compares them against the local file. A matching tail proves the upload
     * is complete and intact even while HEAD is still serving stale metadata
     * (which is exactly what the "expected ... bytes" failures were).
     */
    private boolean tailMatchesRemote(String key, Path file, long size) {
        long tail = Math.min(TAIL_CHECK_BYTES, size);
        long start = size - tail;
        byte[] local;
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(start);
            local = in.readNBytes((int) tail);
        } catch (IOException e) {
            return false;
        }
        try (var object = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .range("bytes=" + start + "-" + (size - 1))
                .build())) {
            byte[] remote = object.readAllBytes();
            return remote.length == local.length && MessageDigest.isEqual(local, remote);
        } catch (Exception e) {
            return false;
        }
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
