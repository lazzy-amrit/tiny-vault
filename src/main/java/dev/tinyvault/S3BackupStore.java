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
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
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
import java.time.Instant;
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
    /** FileLu slot keys are unique per run: no overwrites, no re-creates. */
    static final String SLOT_PREFIX = PREFIX + "backup-";
    private static final String LEGACY_TEMP_KEY = PREFIX + "uploading.zip";
    private static final String LEGACY_CURRENT = PREFIX + "current.zip";
    private static final String LEGACY_OLD = PREFIX + "old.zip";
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
    private final java.util.function.Consumer<String> warning;

    S3BackupStore(String endpoint, String region, boolean pathStyle, String bucket, String accessKey, String secretKey,
                  boolean fileLuMode, FileLuTrashPurger trashPurger, java.util.function.Consumer<String> warning) {
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
        this.warning = warning;
    }

    /**
     * Stores the new archive as the newest backup slot and keeps exactly two.
     * Returns the key the archive was stored under.
     *
     * FileLu s5 mode uses <b>unique human-readable slot keys</b>
     * ({@code backups/backup-2026-10-16_18.44.zip}). Verified live against
     * the real gateway: overwriting an existing key breaks the object
     * (HEAD/list keep showing it, GET later returns 404), and re-creating a
     * deleted key has the same ghost shape. Create-once uploads proved
     * reliable, so every run gets its own key and exactly two newest slots
     * are kept. Space is freed BEFORE each upload (the one-but-newest slot
     * and all legacy junk leave first), so quota never blocks a backup; the
     * only readable backup is never removed before a fresh verified one
     * exists.
     *
     * Standard S3 mode keeps the classic current.zip/old.zip names with
     * server-side CopyObject, since real S3 handles overwrite and copy.
     */
    String rotate(Path archive, long size, Instant startedAt) throws IOException {
        deleteIfPresent(LEGACY_TEMP_KEY);
        abortStaleUploads();
        if (!fileLuMode) {
            deleteIfPresent(LEGACY_OLD);
            if (objectExists(LEGACY_CURRENT)) {
                s3.copyObject(CopyObjectRequest.builder()
                        .bucket(bucket)
                        .copySource(bucket + "/" + LEGACY_CURRENT)
                        .destinationBucket(bucket)
                        .destinationKey(LEGACY_OLD)
                        .build());
            }
            upload(LEGACY_CURRENT, archive);
            verifyUpload(LEGACY_CURRENT, archive, size, -1);
            return LEGACY_CURRENT;
        }

        if (!trashPurger.enabled()) {
            warning.accept("api-key is empty in config.yml — FileLu trash cannot be purged automatically; "
                    + "deleted backups stay in trash consuming quota until emptied in the web UI "
                    + "(My Account -> API Key on filelu.com, NOT the S5 keys)");
        }
        trashPurger.purgeBackupNames();

        List<String> era = new ArrayList<>();
        List<String> junk = new ArrayList<>();
        for (String key : listAllUnder(PREFIX)) {
            if (key.equals(LEGACY_TEMP_KEY)) {
                continue;
            }
            if (key.startsWith(SLOT_PREFIX)) {
                era.add(key);
            } else {
                junk.add(key);
            }
        }
        era.sort(Comparator.reverseOrder());

        // make room before uploading: keep only the newest slot; everything
        // else goes once at least one verified slot exists
        List<String> victims = new ArrayList<>();
        for (int i = 1; i < era.size(); i++) {
            victims.add(era.get(i));
        }
        if (!era.isEmpty()) {
            victims.addAll(junk);
        } else {
            // no verified slots yet: keep the possibly-still-readable legacy
            // current.zip until the new upload is verified; ghosts can go
            for (String candidate : junk) {
                if (!candidate.equals(LEGACY_CURRENT)) {
                    victims.add(candidate);
                }
            }
        }
        deleteQuietly(victims);
        if (!victims.isEmpty()) {
            trashPurger.purgeBackupNames();
        }

        String newKey = freshSlotKey(startedAt);
        upload(newKey, archive);
        verifyUpload(newKey, archive, size, -1);

        if (era.isEmpty()) {
            // a verified slot now exists; the readable legacy copy may go
            deleteQuietly(List.of(LEGACY_CURRENT));
            trashPurger.purgeBackupNames();
        }
        return newKey;
    }

    /**
     * Human-readable unique slot key: backup-2026-10-16_18.44.zip; two runs
     * inside the same minute get _2, _3… suffixes (those still sort after).
     */
    private String freshSlotKey(Instant startedAt) throws IOException {
        DateTimeFormatter stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm").withZone(slotZone);
        String base = SLOT_PREFIX + stamp.format(startedAt) + ".zip";
        String key = base;
        int bump = 2;
        try {
            while (objectExists(key)) {
                key = SLOT_PREFIX + stamp.format(startedAt) + "_" + bump + ".zip";
                bump++;
            }
        } catch (Exception e) {
            // if probing fails fall back to a guaranteed-unique name
            return SLOT_PREFIX + stamp.format(startedAt) + "_" + startedAt.toEpochMilli() + ".zip";
        }
        return key;
    }

    /** All keys under the prefix; empty on listing trouble. */
    private List<String> listAllUnder(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            ListObjectsV2Response list = s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build());
            list.contents().forEach(o -> keys.add(o.key()));
            while (list.isTruncated()) {
                list = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .continuationToken(list.nextContinuationToken())
                        .build());
                list.contents().forEach(o -> keys.add(o.key()));
            }
        } catch (Exception e) {
            warning.accept("could not list bucket objects (" + e.getMessage() + "); skipping cleanup this run");
            return List.of();
        }
        return keys;
    }

    private void deleteQuietly(List<String> keys) {
        for (String key : keys) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (Exception e) {
                warning.accept("could not delete " + key + ": " + e.getMessage());
            }
        }
        if (!keys.isEmpty()) {
            try {
                deleteIfPresent(LEGACY_TEMP_KEY);
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    /** All slot keys newest-first. On listing trouble: empty list, no deletes this run. */
    private List<String> listSlots(String guaranteedNewest) {
        List<String> keys = new ArrayList<>();
        try {
            ListObjectsV2Response list = s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucket).prefix(SLOT_PREFIX).build());
            list.contents().forEach(o -> keys.add(o.key()));
            while (list.isTruncated()) {
                list = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(SLOT_PREFIX)
                        .continuationToken(list.nextContinuationToken())
                        .build());
                list.contents().forEach(o -> keys.add(o.key()));
            }
        } catch (Exception e) {
            warning.accept("could not list existing backup slots (" + e.getMessage()
                    + "); skipping slot cleanup this run");
            return List.of();
        }
        if (!keys.contains(guaranteedNewest)) {
            keys.add(guaranteedNewest);
        }
        keys.sort(Comparator.reverseOrder());
        return keys;
    }

    /** The kept backup slots, newest first, for /vault detail; size null when unreadable-now. */
    Map<String, Long> slots() {
        Map<String, Long> out = new LinkedHashMap<>();
        if (!fileLuMode) {
            for (String key : new String[]{CURRENT_KEY, OLD_KEY}) {
                out.put(key, headSizeOrNull(key));
            }
            return out;
        }
        try {
            List<String> keys = new ArrayList<>();
            ListObjectsV2Response list = s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucket).prefix(SLOT_PREFIX).build());
            list.contents().forEach(o -> keys.add(o.key()));
            keys.sort(Comparator.reverseOrder());
            for (String key : keys.subList(0, Math.min(2, keys.size()))) {
                out.put(key, headSizeOrNull(key));
            }
        } catch (Exception ignored) {
            // the detail view is best-effort
        }
        return out;
    }

    private Long headSizeOrNull(String key) {
        try {
            return head(key).contentLength();
        } catch (NoSuchKeyException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
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
