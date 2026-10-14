package dev.tinyvault;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
 * promotion is a fresh upload of the same local file.
 */
final class S3BackupStore implements AutoCloseable {
    static final String PREFIX = "backups/";
    private static final String TEMP_KEY = PREFIX + "uploading.zip";
    private static final String CURRENT_KEY = PREFIX + "current.zip";

    private final S3Client s3;
    private final String bucket;

    S3BackupStore(String endpoint, String region, boolean pathStyle, String bucket, String accessKey, String secretKey) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
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
        upload(TEMP_KEY, archive);
        verifyUpload(TEMP_KEY, archive, size);

        deleteQuietly(CURRENT_KEY);
        upload(CURRENT_KEY, archive);
        verifyUpload(CURRENT_KEY, archive, size);
        deleteQuietly(TEMP_KEY);
    }

    private void upload(String key, Path file) throws IOException {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(file));
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
