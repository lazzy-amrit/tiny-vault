package dev.tinyvault;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct Swift API access. The authenticated session is shared by all callers. */
final class BlompSwiftStore implements AutoCloseable {
    static final String DEFAULT_AUTH_URL = "https://authenticate.blomp.com";
    private static final Pattern MANAGED_NAME = Pattern.compile("backup-.+-(\\d{13})\\.zip");
    private static final long GB = 1_000_000_000L;

    record StorageInfo(Long usedBytes, Long quotaBytes) {
        Long freeBytes() {
            return usedBytes == null || quotaBytes == null ? null : Math.max(0, quotaBytes - usedBytes);
        }
    }

    record StorageEntry(String path, Long sizeBytes, boolean directory) {}

    private record Session(URI storageUrl, String token) {}

    private final HttpClient http;
    private final URI authUrl;
    private final String username;
    private final String password;
    private final String container;
    private final String prefix;
    private final int limit;
    private final Consumer<String> warning;
    private volatile Session session;

    BlompSwiftStore(String username, String password, String dir, int limit, Consumer<String> warning) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create(DEFAULT_AUTH_URL), username, password, dir, limit, warning);
    }

    BlompSwiftStore(HttpClient http, URI authUrl, String username, String password,
                    String dir, int limit, Consumer<String> warning) {
        this.http = http;
        this.authUrl = authUrl;
        this.username = username;
        this.password = password;
        String[] path = dir.strip().replace('\\', '/').split("/+");
        if (path.length == 0 || path[0].isBlank() || path[0].equals(".") || path[0].equals("..")) {
            throw new IllegalArgumentException("dir must start with a Swift container name");
        }
        this.container = path[0];
        this.prefix = path.length == 1 ? "" : String.join("/", java.util.Arrays.copyOfRange(path, 1, path.length));
        this.limit = Math.max(1, limit);
        this.warning = warning;
    }

    String uploadBackup(Path archive, Instant createdAt, ZoneId zone, String filenamePattern) throws IOException {
        String timestamp;
        try {
            timestamp = DateTimeFormatter.ofPattern(filenamePattern, Locale.ROOT)
                    .withZone(zone).format(createdAt);
        } catch (IllegalArgumentException e) {
            throw new IOException("time-format does not produce a valid name: " + e.getMessage(), e);
        }
        if (timestamp.isBlank() || timestamp.contains("/") || timestamp.contains("\\")) {
            throw new IOException("the derived name is not a single filename; check timezone/time-format");
        }
        // epoch millis keep the managed-name ordering unambiguous even when two
        // backups happen within the same stamp
        String name = "backup-" + timestamp + "-" + createdAt.toEpochMilli() + ".zip";
        String object = join(prefix, name);
        ensureContainer();
        HttpResponse<byte[]> response = request("PUT", objectUri(container, object),
                HttpRequest.BodyPublishers.ofFile(archive), "application/zip");
        requireSuccess(response, "upload ZIP");

        // Swift returns the MD5 of the object as ETag; compare against the local
        // file so a corrupted transfer can never be mistaken for a backup.
        String etag = response.headers().firstValue("ETag").orElse("");
        if (!etag.isBlank() && !md5Hex(archive).equalsIgnoreCase(etag.replace("\"", ""))) {
            try {
                request("DELETE", objectUri(container, object), HttpRequest.BodyPublishers.noBody(), null);
            } catch (IOException ignored) {
                // best effort cleanup; the object staying would be re-trimmed by retention
            }
            throw new IOException("upload of " + object + " failed verification: storage checksum "
                    + "does not match the local file (ETag mismatch)");
        }

        try {
            trimManagedBackups();
        } catch (IOException e) {
            warning.accept("Backup uploaded, but retention cleanup could not finish: " + e.getMessage());
        }
        return join(container, object);
    }

    private static String md5Hex(Path file) throws IOException {
        java.security.MessageDigest md5;
        try {
            md5 = java.security.MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("MD5 unavailable", e);
        }
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) {
                md5.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(md5.getDigestLength() * 2);
        for (byte b : md5.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit((b & 0xF), 16));
        }
        return hex.toString();
    }

    StorageInfo storageInfo() throws IOException {
        HttpResponse<byte[]> response = request("HEAD", accountUri(), HttpRequest.BodyPublishers.noBody(), null);
        requireSuccess(response, "read Swift account metadata");
        Long used = headerLong(response, "X-Account-Bytes-Used");
        Long quota = firstHeaderLong(response, "X-Account-Meta-Quota-Bytes", "X-Account-Quota-Bytes");
        return new StorageInfo(used, quota);
    }

    List<StorageEntry> listContents() throws IOException {
        List<String> containers = listContainers();
        Map<String, StorageEntry> entries = new LinkedHashMap<>();
        for (String currentContainer : containers) {
            entries.put(currentContainer + "/", new StorageEntry(currentContainer + "/", null, true));
            String marker = "";
            while (true) {
                URI uri = appendQuery(containerUri(currentContainer), "format=json&limit=10000"
                        + (marker.isEmpty() ? "" : "&marker=" + encode(marker)));
                HttpResponse<byte[]> response = request("GET", uri, HttpRequest.BodyPublishers.noBody(), null);
                requireSuccess(response, "list Swift container");
                JsonArray objects = parseArray(response.body());
                if (objects.isEmpty()) {
                    break;
                }
                for (JsonElement element : objects) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    String name = stringValue(object, "name");
                    if (name.isBlank()) {
                        continue;
                    }
                    boolean directory = name.endsWith("/")
                            || stringValue(object, "content_type").equalsIgnoreCase("application/directory");
                    String displayPath = currentContainer + "/" + name;
                    addParentDirectories(entries, displayPath);
                    entries.put(displayPath, new StorageEntry(displayPath,
                            directory ? null : longValue(object, "bytes"), directory));
                    marker = name;
                }
                if (objects.size() < 10000) {
                    break;
                }
            }
        }
        return entries.values().stream().sorted(Comparator.comparing(StorageEntry::path,
                String.CASE_INSENSITIVE_ORDER)).toList();
    }

    Map<String, Long> backupSlots() throws IOException {
        Map<String, Long> slots = new LinkedHashMap<>();
        List<StorageEntry> entries = listContainerObjects(container);
        entries.stream()
                .filter(entry -> !entry.directory() && isManaged(entry.path()))
                .sorted(Comparator.comparingLong((StorageEntry entry) -> managedMillis(entry.path())).reversed())
                .limit(2)
                .forEach(entry -> slots.put(entry.path(), entry.sizeBytes()));
        return slots;
    }

    private void ensureContainer() throws IOException {
        if (listContainers().contains(container)) {
            return;
        }
        HttpResponse<byte[]> response = request("PUT", containerUri(container),
                HttpRequest.BodyPublishers.noBody(), null);
        if (response.statusCode() != 201 && response.statusCode() != 202) {
            requireSuccess(response, "create Swift container");
        }
    }

    private List<String> listContainers() throws IOException {
        HttpResponse<byte[]> response = request("GET", appendQuery(accountUri(), "format=json&limit=10000"),
                HttpRequest.BodyPublishers.noBody(), null);
        requireSuccess(response, "list Swift containers");
        JsonArray values = parseArray(response.body());
        List<String> result = new ArrayList<>();
        for (JsonElement value : values) {
            if (value.isJsonObject()) {
                String name = stringValue(value.getAsJsonObject(), "name");
                if (!name.isBlank()) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    private List<StorageEntry> listContainerObjects(String currentContainer) throws IOException {
        List<StorageEntry> entries = new ArrayList<>();
        String marker = "";
        while (true) {
            URI uri = appendQuery(containerUri(currentContainer), "format=json&limit=10000"
                    + (marker.isEmpty() ? "" : "&marker=" + encode(marker)));
            HttpResponse<byte[]> response = request("GET", uri, HttpRequest.BodyPublishers.noBody(), null);
            requireSuccess(response, "list Swift container");
            JsonArray objects = parseArray(response.body());
            if (objects.isEmpty()) {
                break;
            }
            for (JsonElement element : objects) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String name = stringValue(object, "name");
                if (!name.isBlank()) {
                    boolean directory = name.endsWith("/")
                            || stringValue(object, "content_type").equalsIgnoreCase("application/directory");
                    entries.add(new StorageEntry(name, directory ? null : longValue(object, "bytes"), directory));
                    marker = name;
                }
            }
            if (objects.size() < 10000) {
                break;
            }
        }
        return entries;
    }

    private void trimManagedBackups() throws IOException {
        List<StorageEntry> backups = listContainerObjects(container).stream()
                .filter(entry -> !entry.directory() && isManaged(entry.path()))
                .sorted(Comparator.comparingLong(entry -> managedMillis(entry.path())))
                .toList();
        int excess = backups.size() - limit;
        for (int index = 0; index < excess; index++) {
            String object = backups.get(index).path();
            HttpResponse<byte[]> response = request("DELETE", objectUri(container, object),
                    HttpRequest.BodyPublishers.noBody(), null);
            if (response.statusCode() != 204 && response.statusCode() != 404) {
                requireSuccess(response, "remove oldest managed backup");
            }
        }
    }

    private boolean isManaged(String object) {
        String name = object.substring(object.lastIndexOf('/') + 1);
        String expectedPrefix = prefix.isEmpty() ? "" : prefix + "/";
        return object.startsWith(expectedPrefix) && MANAGED_NAME.matcher(name).matches();
    }

    private static long managedMillis(String object) {
        Matcher matcher = MANAGED_NAME.matcher(object.substring(object.lastIndexOf('/') + 1));
        return matcher.matches() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    private HttpResponse<byte[]> request(String method, URI uri, HttpRequest.BodyPublisher body,
                                        String contentType) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            Session current = getSession();
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(java.time.Duration.ofMinutes(30))
                    .header("X-Auth-Token", current.token())
                    .header("Accept", "application/json")
                    .method(method, body);
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            HttpResponse<byte[]> response;
            try {
                response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Blomp Swift request was interrupted", e);
            }
            if (response.statusCode() != 401 || attempt == 1) {
                return response;
            }
            synchronized (this) {
                if (session == current) {
                    session = null;
                }
            }
        }
        throw new IOException("Blomp Swift authorization failed");
    }

    private Session getSession() throws IOException {
        Session current = session;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (session != null) {
                return session;
            }
            String passwordClean = password;
            try {
                try {
                    session = keystoneV3(passwordClean);
                } catch (IOException primaryFailure) {
                    // the v2.0 method with the "storage" tenant is the fallback Blomp documents
                    try {
                        session = keystoneV2(passwordClean);
                    } catch (IOException ignored) {
                        throw new IOException(primaryFailure.getMessage()
                                + " (v2 fallback also failed: " + ignored.getMessage() + ")", primaryFailure);
                    }
                }
            } catch (IllegalStateException malformed) {
                throw new IOException(malformed.getMessage(), malformed);
            }
            return session;
        }
    }

    /**
     * Keystone v3: POST /v3/auth/tokens with email + password. The token comes
     * from the X-Subject-Token header, the storage URL from the "swift" catalog
     * entry picked from the token body.
     */
    private Session keystoneV3(String passwordClean) throws IOException {
        JsonObject identity = new JsonObject();
        com.google.gson.JsonArray methods = new com.google.gson.JsonArray();
        methods.add("password");
        identity.add("methods", methods);
        JsonObject user = new JsonObject();
        user.addProperty("name", username);
        JsonObject domain = new JsonObject();
        domain.addProperty("name", "Default");
        user.add("domain", domain);
        user.addProperty("password", passwordClean);
        JsonObject passwordObj = new JsonObject();
        passwordObj.add("user", user);
        identity.add("password", passwordObj);
        JsonObject auth = new JsonObject();
        auth.add("identity", identity);
        JsonObject envelope = new JsonObject();
        envelope.add("auth", auth);
        HttpRequest request = HttpRequest.newBuilder(URI.create(authUrl + "/v3/auth/tokens"))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(envelope.toString()))
                .build();
        HttpResponse<byte[]> response = sendAuth(request, "v3");
        if (response.statusCode() != 201) {
            throw new IOException("Blomp v3 auth failed with HTTP " + response.statusCode()
                    + " — check username and password in config.yml");
        }
        String token = response.headers().firstValue("X-Subject-Token").orElse("");
        String storageUrl = swiftPublicUrl(new String(response.body(), StandardCharsets.UTF_8));
        if (token.isBlank() || storageUrl.isBlank()) {
            throw new IOException("Blomp v3 auth response did not include a token or Swift catalog entry");
        }
        return new Session(URI.create(storageUrl), token);
    }

    /** Keystone v2.0 fallback: passwordCredentials + tenantName "storage" (Blomp's documented rclone way). */
    private Session keystoneV2(String passwordClean) throws IOException {
        JsonObject credentials = new JsonObject();
        credentials.addProperty("username", username);
        credentials.addProperty("password", passwordClean);
        JsonObject passwordCredentials = new JsonObject();
        passwordCredentials.add("passwordCredentials", credentials);
        passwordCredentials.addProperty("tenantName", "storage");
        JsonObject auth = new JsonObject();
        auth.add("auth", passwordCredentials);
        HttpRequest request = HttpRequest.newBuilder(URI.create(authUrl + "/v2.0/tokens"))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(auth.toString()))
                .build();
        HttpResponse<byte[]> response = sendAuth(request, "v2.0");
        if (response.statusCode() != 200) {
            throw new IOException("Blomp v2.0 auth failed with HTTP " + response.statusCode()
                    + " — check username and password in config.yml");
        }
        JsonObject parsed = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject access = parsed.getAsJsonObject("access");
        if (access == null || !access.has("token") || !access.getAsJsonObject("token").has("id")) {
            throw new IOException("Blomp v2.0 auth response is missing the token");
        }
        String token = access.getAsJsonObject("token").get("id").getAsString();
        String storageUrl = null;
        for (JsonElement service : access.getAsJsonArray("serviceCatalog")) {
            JsonObject catalogEntry = service.getAsJsonObject();
            if ("object-store".equals(catalogEntry.has("type")
                    ? catalogEntry.get("type").getAsString() : "nonexistent")) {
                storageUrl = swiftPublicUrlFrom(catalogEntry.getAsJsonArray("endpoints"));
                break;
            }
        }
        if (storageUrl == null || storageUrl.isBlank()) {
            throw new IOException("Blomp v2.0 auth response has no object-store catalog entry");
        }
        return new Session(URI.create(storageUrl), token);
    }

    private HttpResponse<byte[]> sendAuth(HttpRequest request, String version) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpConnectTimeoutException e) {
            throw new IOException("the Blomp authentication endpoint (" + authUrl + ", " + version
                    + ") did not accept a connection in time — their API may be down; retry");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Blomp authentication was interrupted", e);
        } catch (IOException e) {
            throw new IOException("could not reach the Blomp authentication endpoint (" + authUrl
                    + ", " + version + "): " + e.getMessage(), e);
        }
    }

    /** Finds the object-store "public" endpoint URL in a keystone catalog JSON string. */
    private static String swiftPublicUrl(String tokenBody) throws IOException {
        try {
            JsonObject parsed = JsonParser.parseString(tokenBody).getAsJsonObject();
            JsonArray catalog = parsed.getAsJsonObject("token").getAsJsonArray("catalog");
            for (JsonElement service : catalog) {
                JsonObject entry = service.getAsJsonObject();
                if ("object-store".equals(entry.has("type") ? entry.get("type").getAsString() : "")) {
                    String url = swiftPublicUrlFrom(entry.getAsJsonArray("endpoints"));
                    if (url != null) {
                        return url;
                    }
                }
            }
            throw new IOException("the token catalog lists no object-store endpoint");
        } catch (UnsupportedOperationException | IllegalStateException | NullPointerException e) {
            throw new IOException("could not read the Swift storage URL from the Blomp token response", e);
        }
    }

    private static String swiftPublicUrlFrom(JsonArray endpoints) {
        for (JsonElement element : endpoints) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject endpoint = element.getAsJsonObject();
            if ("public".equalsIgnoreCase(stringValue(endpoint, "interface"))
                    && !stringValue(endpoint, "url").isBlank()) {
                return endpoint.get("url").getAsString();
            }
        }
        for (JsonElement element : endpoints) {
            if (element.isJsonObject() && !stringValue(element.getAsJsonObject(), "url").isBlank()) {
                return element.getAsJsonObject().get("url").getAsString();
            }
        }
        return null;
    }

    private URI accountUri() throws IOException {
        return getSession().storageUrl();
    }

    private URI containerUri(String name) throws IOException {
        return URI.create(trimSlash(accountUri().toString()) + "/" + encode(name));
    }

    private URI objectUri(String containerName, String object) throws IOException {
        return URI.create(trimSlash(containerUri(containerName).toString()) + "/" + encodePath(object));
    }

    private static URI appendQuery(URI base, String query) {
        return URI.create(base + (base.toString().contains("?") ? "&" : "?") + query);
    }

    private static JsonArray parseArray(byte[] body) throws IOException {
        if (body.length == 0) {
            return new JsonArray();
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (RuntimeException e) {
            throw new IOException("Blomp returned an invalid Swift listing", e);
        }
    }

    private static void addParentDirectories(Map<String, StorageEntry> entries, String path) {
        int slash = path.indexOf('/');
        while (slash >= 0 && slash < path.length() - 1) {
            String directory = path.substring(0, slash + 1);
            entries.putIfAbsent(directory, new StorageEntry(directory, null, true));
            slash = path.indexOf('/', slash + 1);
        }
    }

    private static Long headerLong(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).flatMap(BlompSwiftStore::parseLong).orElse(null);
    }

    private static Long firstHeaderLong(HttpResponse<?> response, String... names) {
        for (String name : names) {
            Long value = headerLong(response, name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static java.util.Optional<Long> parseLong(String value) {
        try {
            return java.util.Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static Long longValue(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            return value == null || value.isJsonNull() ? null : value.getAsLong();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String value) {
        return java.util.Arrays.stream(value.split("/", -1)).map(BlompSwiftStore::encode)
                .collect(java.util.stream.Collectors.joining("/"));
    }

    /** Container and auth details for startup logs and diagnostics. */
    String containerName() {
        return container + (prefix.isEmpty() ? "" : "/" + prefix + "/");
    }

    String describeConnection() {
        return authUrl + " (Keystone v3, v2.0 fallback)";
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String join(String left, String right) {
        return left == null || left.isEmpty() ? right : left + "/" + right;
    }

    private static void requireSuccess(HttpResponse<?> response, String operation) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Blomp Swift could not " + operation + " (HTTP " + response.statusCode() + ")");
        }
    }

    static String gbText(Long bytes) {
        return bytes == null ? "unavailable" : String.format(Locale.ROOT, "%.2f GB", bytes / (double) GB);
    }

    @Override
    public void close() {
        session = null;
    }
}