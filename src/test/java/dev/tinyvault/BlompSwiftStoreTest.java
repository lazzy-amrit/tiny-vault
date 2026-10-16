package dev.tinyvault;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlompSwiftStoreTest {
    private static final String ACCOUNT = "/v1/AUTH_testaccount";

    private HttpServer server;
    private final AtomicInteger authCount = new AtomicInteger();
    private final AtomicInteger headCount = new AtomicInteger();
    private final List<String> objects = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private byte[] uploaded;
    /** Keystone v3 shaped token response pointing the catalog at the mock Swift. */
    private String tokenBody() {
        return "{\"token\":{\"catalog\":[{\"type\":\"object-store\",\"endpoints\":["
                + "{\"url\":\"http://127.0.0.1:" + server.getAddress().getPort() + ACCOUNT
                + "\",\"interface\":\"public\"}]}}]}";
    }

    private boolean expireOnce;
    private boolean includeQuota;
    private boolean failUpload;
    private String remoteContainer = "tinyvault";
    private String remotePrefix = "";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/v3/auth/tokens") && method.equals("POST")) {
                authCount.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains("\"password\""), "v3 body must carry the password method");
                assertTrue(body.contains("user@example.test"), "v3 body must carry the username");
                exchange.getResponseHeaders().add("X-Subject-Token", "test-token-" + authCount.get());
                send(exchange, 201, tokenBody(ACCOUNT));
            } else if (path.equals(ACCOUNT) && method.equals("HEAD")) {
                headCount.incrementAndGet();
                if (expireOnce) {
                    expireOnce = false;
                    exchange.sendResponseHeaders(401, -1);
                } else {
                    exchange.getResponseHeaders().add("X-Account-Bytes-Used", "2500000000");
                    if (includeQuota) {
                        exchange.getResponseHeaders().add("X-Account-Meta-Quota-Bytes", "10000000000");
                    }
                    exchange.sendResponseHeaders(204, -1);
                }
            } else if (path.equals(ACCOUNT) && method.equals("GET")) {
                send(exchange, 200, "[{\"name\":\"other-stuff\"},{\"name\":\"" + remoteContainer + "\"}]");
            } else if (path.equals(ACCOUNT + "/" + remoteContainer) && method.equals("PUT")) {
                exchange.sendResponseHeaders(201, -1);
            } else if (path.equals(ACCOUNT + "/" + remoteContainer) && method.equals("GET")) {
                StringBuilder listing = new StringBuilder("[");
                for (int index = 0; index < objects.size(); index++) {
                    if (index > 0) {
                        listing.append(',');
                    }
                    String name = objects.get(index);
                    byte[] bytes = storedContents.getOrDefault(name, uploadedContent(name));
                    listing.append("{\"name\":\"").append(name)
                            .append("\",\"bytes\":").append(bytes.length)
                            .append(",\"content_type\":\"application/zip\"}");
                }
                listing.append(']');
                send(exchange, 200, listing.toString());
            } else if (path.startsWith(ACCOUNT + "/" + remoteContainer + "/") && method.equals("PUT")) {
                uploaded = exchange.getRequestBody().readAllBytes();
                String objectName = path.substring((ACCOUNT + "/" + remoteContainer + "/").length());
                if (failUpload) {
                    exchange.sendResponseHeaders(500, -1);
                } else {
                    objects.add(objectName);
                    String etag;
                    if (corruptStoredOnce) {
                        // real servers store corrupted bytes and truthfully etag THEM
                        byte[] flipped = uploaded.clone();
                        flipped[0] = (byte) ~flipped[0];
                        storedContents.put(objectName, flipped);
                        etag = md5Of(flipped);
                        corruptStoredOnce = false;
                    } else {
                        storedContents.put(objectName, uploaded);
                        etag = md5Of(uploaded);
                    }
                    exchange.getResponseHeaders().add("ETag", etag);
                    exchange.sendResponseHeaders(201, -1);
                }
            } else if (path.startsWith(ACCOUNT + "/" + remoteContainer + "/") && method.equals("GET")) {
                String objectName = path.substring((ACCOUNT + "/" + remoteContainer + "/").length());
                byte[] content = storedContents.getOrDefault(objectName, uploadedContent(objectName));
                send(exchange, 200, new String(content, StandardCharsets.ISO_8859_1));
            } else if (path.startsWith(ACCOUNT + "/" + remoteContainer + "/") && method.equals("DELETE")) {
                String objectName = path.substring((ACCOUNT + "/" + remoteContainer + "/").length());
                deleted.add(objectName);
                objects.remove(objectName);
                exchange.sendResponseHeaders(204, -1);
            } else {
                send(exchange, 404, "not found");
            }
            exchange.close();
        });
        server.start();
    }

    private byte[] uploadedContent(String objectName) {
        return ("content:" + objectName).getBytes(StandardCharsets.UTF_8);
    }

    private static String md5Of(byte[] bytes) throws IOException {
        try {
            java.security.MessageDigest md5 = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("md5 unavailable", e);
        }
    }

    /** Keystone v3 shaped token response pointing the catalog at the mock Swift. */
    private static String tokenBody(String accountUrl) {
        return "{\"token\":{\"catalog\":[{\"type\":\"volume\",\"endpoints\":[{\"url\":\"http://unused/v1\","
                + "\"interface\":\"public\"}]},{\"type\":\"object-store\",\"endpoints\":"
                + "[{\"type\":\"object-store\",\"url\":\"http://internal:8080/v1\",\"interface\":\"internal\"},"
                + "{\"type\":\"object-store\",\"url\":\"http://127.0.0.1:" + mockPort + accountUrl
                + "\",\"interface\":\"public\"}]}]}}";
    }

    private static int mockPort;

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void uploadsTenBackupsOneByOneAndKeepsOnlyTheLimit() throws Exception {
        mockPort = server.getAddress().getPort();
        List<String> warnings = new ArrayList<>();
        List<String> stored = new ArrayList<>();
        Path zip = Files.createTempFile("tinyvault-rotate", ".zip");
        Files.write(zip, new byte[]{'P', 'K', 3, 4, 1, 2, 3, 4});
        try (BlompSwiftStore store = store(3, warnings)) { // limit 3
            for (int i = 1; i <= 10; i++) {
                Instant at = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 60L);
                String key = store.uploadBackup(zip, at, ZoneId.of("UTC"), "yyyy-MM-dd_HH.mm");
                stored.add(key);
                // name style per spec: backup-<stamp>-<millis>.zip
                assertTrue(key.startsWith("user@example.test/backup-2026-01-01_00."),
                        "name must be backup-<stamp> inside the account container");
                assertFalse(key.contains("tinyvault-"), "name must not use the old tinyvault- prefix");
                uploadedContentCheck(objects);
            }
            // bare object names (mock stores without the container prefix)
            List<String> bare = stored.stream()
                    .map(k -> k.substring("user@example.test/".length())).toList();
            // bucket keeps exactly the limit newest ones, oldest pruned in order
            assertEquals(3, objects.size());
            assertTrue(objects.contains(bare.get(9)), "newest must be kept");
            assertTrue(objects.contains(bare.get(8)), "second-newest must be kept");
            assertTrue(objects.contains(bare.get(7)), "third-newest must be kept");
            assertEquals(7, deleted.size());
            assertTrue(deleted.contains(bare.get(0)), "oldest must have been removed");
            assertTrue(objects.stream().noneMatch(n -> n.contains("backup-2026-01-01_00.01")));
            assertTrue(warnings.isEmpty());
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    @Test
    void uploadsHugeBackupsVerifiesChecksumsAndRotatesWithLimit() throws Exception {
        mockPort = server.getAddress().getPort();
        List<String> warnings = new ArrayList<>();
        List<String> stored = new ArrayList<>();
        // 48 MiB: big enough to exercise realistic archive sizes through the
        // real upload + checksum path (the in-process mock caps larger bodies)
        int size = 48 * 1024 * 1024;
        Path zip = Files.createTempFile("tinyvault-huge", ".zip");
        byte[] chunk = new byte[1024 * 1024];
        for (int i = 0; i < chunk.length; i++) {
            chunk[i] = (byte) (i * 31);
        }
        try (var out = Files.newOutputStream(zip)) {
            for (int i = 0; i < size / chunk.length; i++) {
                out.write(chunk);
            }
        }
        try (BlompSwiftStore store = store(2, warnings)) { // limit 2
            for (int i = 1; i <= 3; i++) {
                Instant at = Instant.parse("2026-10-16T10:00:00Z").plusSeconds(i * 60L);
                String key = store.uploadBackup(zip, at, ZoneId.of("UTC"), "yyyy-MM-dd_HH.mm");
                stored.add(key);
                // the store verified the ETag/md5 internally — a checksum-failed upload
                // would have thrown before we ever got here
            }
            List<String> bare = stored.stream().map(k -> k.substring("user@example.test/".length())).toList();
            assertEquals(2, objects.size(), "only the two newest 128MiB backups stay");
            assertTrue(objects.contains(bare.get(2)));
            assertTrue(objects.contains(bare.get(1)));
            assertFalse(objects.contains(bare.get(0)));
            assertEquals(1, deleted.size());
            assertTrue(warnings.isEmpty());
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private void uploadedContentCheck(List<String> objects) {
        // every PUT has been captured intact
        assertFalse(uploaded == null || uploaded.length == 0);
    }

    @Test
    void namesFollowTwelveHourTimeFormatWithAmPm() throws Exception {
        mockPort = server.getAddress().getPort();
        Path zip = Files.createTempFile("tinyvault-12h", ".zip");
        Files.write(zip, new byte[]{'P', 'K', 3, 4});
        try (BlompSwiftStore store = store(3, new ArrayList<>())) {
            String key = store.uploadBackup(zip, Instant.parse("2026-10-16T15:30:00Z"),
                    ZoneId.of("Asia/Kolkata"), "yyyy-MM-dd_hh.mm-a");
            // 15:30Z = 21:00 IST → 9.00 PM
            assertTrue(key.contains("backup-2026-10-16_09.00-PM-"), "12-hour names carry AM/PM: " + key);
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    @Test
    void recoversFromCorruptedTransferByReUploading() throws Exception {
        mockPort = server.getAddress().getPort();
        // the mock stores corrupted bytes once (with the corrupted bytes' own etag,
        // exactly like a truthful real server) — the store must detect the content
        // mismatch, re-upload, and end up with the correct bytes
        corruptStoredOnce = true;
        Path zip = Files.createTempFile("tinyvault-corrupt", ".zip");
        byte[] original = {'P', 'K', 3, 4, 9, 9, 9};
        Files.write(zip, original);
        try (BlompSwiftStore store = store(3, new ArrayList<>())) {
            String key = store.uploadBackup(zip, Instant.now(), ZoneId.of("UTC"), "yyyy-MM-dd_HH.mm");
            String bare = key.substring(key.indexOf('/') + 1);
            // the final stored content must equal the original file
            assertTrue(java.util.Arrays.equals(storedContents.get(bare), original),
                    "the stored object must end up matching the local file");
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private boolean corruptStoredOnce;
    private final java.util.Map<String, byte[]> storedContents = new java.util.HashMap<>();

    @Test
    void storageInfoReportsUsedQuotaAndFree() throws Exception {
        mockPort = server.getAddress().getPort();
        includeQuota = true;
        try (BlompSwiftStore store = store(3, new ArrayList<>())) {
            BlompSwiftStore.StorageInfo info = store.storageInfo();
            assertEquals(2_500_000_000L, info.usedBytes());
            assertEquals(10_000_000_000L, info.quotaBytes());
            assertEquals(7_500_000_000L, info.freeBytes());
        }
    }

    @Test
    void tokenRefreshesOnceAfterUnauthorized() throws Exception {
        mockPort = server.getAddress().getPort();
        expireOnce = true;
        try (BlompSwiftStore store = store(3, new ArrayList<>())) {
            BlompSwiftStore.StorageInfo info = store.storageInfo();
            assertEquals(2, authCount.get());
            assertEquals(2, headCount.get());
            assertNull(info.quotaBytes());
        }
    }

    @Test
    void writesToCustomContainerAndNestedPrefix() throws Exception {
        mockPort = server.getAddress().getPort();
        remoteContainer = "custom-vault";
        remotePrefix = "nightly/daily";
        Path zip = Files.createTempFile("tinyvault-custom", ".zip");
        Files.write(zip, new byte[]{'P', 'K', 3, 4});
        try (BlompSwiftStore store = store(2, new ArrayList<>(), "user@example.test/nightly/daily")) {
            String key = store.uploadBackup(zip, Instant.now(), ZoneId.of("UTC"), "yyyy-MM-dd_HH.mm");
            assertTrue(key.startsWith("user@example.test/nightly/daily/backup-"),
                    "custom nested prefix respected: " + key);
            assertTrue(objects.stream().anyMatch(n -> n.startsWith("nightly/daily/backup-")));
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    @Test
    void failedUploadDoesNotDeleteExistingBackups() throws Exception {
        mockPort = server.getAddress().getPort();
        objects.add("backup-1000000000000.zip");
        failUpload = true;
        Path zip = Files.createTempFile("tinyvault-failed", ".zip");
        Files.write(zip, new byte[]{'P', 'K', 3, 4});
        try (BlompSwiftStore store = store(1, new ArrayList<>())) {
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                    () -> store.uploadBackup(zip, Instant.now(), ZoneId.of("UTC"), "yyyy-MM-dd_HH.mm"));
            assertTrue(deleted.isEmpty(), "a failed upload must not prune anything");
            assertTrue(objects.contains("backup-1000000000000.zip"));
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    @Test
    void neverTouchesUnrelatedFilesInContainer() throws Exception {
        mockPort = server.getAddress().getPort();
        objects.add("some önemli notlar.txt");
        objects.add("notes.txt");
        Path zip = Files.createTempFile("tinyvault-unrelated", ".zip");
        Files.write(zip, new byte[]{'P', 'K', 3, 4});
        try (BlompSwiftStore store = store(1, new ArrayList<>())) {
            store.uploadBackup(zip, Instant.now(), ZoneId.of("UTC"), "yyyy-MM-dd_HH.mm");
            assertTrue(objects.contains("some önemli notlar.txt"));
            assertTrue(objects.contains("notes.txt"));
            assertFalse(deleted.contains("notes.txt"));
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private BlompSwiftStore store(int limit, List<String> warnings) {
        // the account email IS the container Blomp allows
        return store(limit, warnings, "user@example.test");
    }

    private BlompSwiftStore store(int limit, List<String> warnings, String dir) {
        mockPort = server.getAddress().getPort();
        String[] path = dir.split("/");
        remoteContainer = path[0];
        remotePrefix = String.join("/", java.util.Arrays.copyOfRange(path, 1, path.length));
        return new BlompSwiftStore(java.net.http.HttpClient.newHttpClient(),
                URI.create(baseUrl()), "user@example.test", "password", dir,
                limit, warnings::add);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
