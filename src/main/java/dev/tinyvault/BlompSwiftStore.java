package dev.tinyvault;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
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
    private final String prefix;
    private final int limit;
    private final Consumer<String> warning;
    /** Blomp ties accounts to a container named after the account email. */
    private String container;
    private volatile Session session;
    /** Dead-node workaround: one reachable IP per hostname, invalidated on failure. */
    private final java.util.Map<String, String> pinnedAddressesByHost = new java.util.concurrent.ConcurrentHashMap<>();

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
        String configured = dir == null || dir.isBlank() || dir.strip().equalsIgnoreCase("auto")
                ? "auto" : dir.strip();
        String[] path = configured.replace('\\', '/').split("/+");
        if (configured.equals("auto") || path[0].isBlank() || path[0].equals(".")
                || path[0].equals("..") || !path[0].equalsIgnoreCase(username)) {
            // Blomp only lets an account write containers named after itself;
            // anything else 403s at the proxy. Default: email container + prefix.
            this.container = username;
            this.prefix = path.length == 0 || configured.equals("auto") ? "tinyvault"
                    : String.join("/", java.util.Arrays.copyOfRange(path, 0, path.length));
        } else {
            this.container = path[0];
            this.prefix = path.length == 1 ? "" : String.join("/", java.util.Arrays.copyOfRange(path, 1, path.length));
        }
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
        URI objectUri = objectUri(container, object);

        String localMd5 = md5Hex(archive);
        // 1. first PUT (request() retries 5xx busy answers on its own)
        HttpResponse<byte[]> response = request("PUT", objectUri,
                HttpRequest.BodyPublishers.ofFile(archive), "application/zip");
        String etag = response.headers().firstValue("ETag").orElse("").replace("\"", "");
        if (response.statusCode() == 201 && !etag.isBlank() && etag.equalsIgnoreCase(localMd5)) {
            // ETag already proves the stored bytes are exactly the local file
            return finishUpload(object);
        }

        // 2. content is the only truth: read the object back fully and compare
        if (objectMatchesLocal(objectUri, archive, localMd5)) {
            return finishUpload(object); // e.g. 503 answered while the upload actually landed
        }

        // 3. one clean retry, then give up (retention never prunes for a failed run)
        response = request("PUT", objectUri, HttpRequest.BodyPublishers.ofFile(archive), "application/zip");
        etag = response.headers().firstValue("ETag").orElse("").replace("\"", "");
        if (!(response.statusCode() == 201 && !etag.isBlank() && etag.equalsIgnoreCase(localMd5))
                && !objectMatchesLocal(objectUri, archive, localMd5)) {
            throw new IOException("upload of " + object + " failed verification: the stored object "
                    + "does not match the local file after two upload attempts (last HTTP "
                    + response.statusCode() + ")");
        }
        return finishUpload(object);
    }

    /** Retention housekeeping after a verified upload. */
    private String finishUpload(String object) throws IOException {
        try {
            trimManagedBackups();
        } catch (IOException e) {
            warning.accept("Backup uploaded, but retention cleanup could not finish: " + e.getMessage());
        }
        return join(container, object);
    }

    /** Streams the stored object and compares its MD5 against the local file. */
    private boolean objectMatchesLocal(URI objectUri, Path archive, String localMd5) throws IOException {
        try {
            HttpResponse<byte[]> response = request("GET", objectUri,
                    HttpRequest.BodyPublishers.noBody(), null);
            if (response.statusCode() != 200) {
                return false;
            }
            java.security.MessageDigest md5 = java.security.MessageDigest.getInstance("MD5");
            md5.update(response.body());
            StringBuilder hex = new StringBuilder(md5.getDigestLength() * 2);
            for (byte b : md5.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit((b & 0xF), 16));
            }
            return hex.toString().equalsIgnoreCase(localMd5);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("MD5 unavailable", e);
        }
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
        if (response.statusCode() == 403) {
            // Blomp hides account metadata (403 by design) — report what our
            // own container holds so /vault storage still says something real
            long ours = 0;
            for (StorageEntry entry : listContainerObjects(container)) {
                if (!entry.directory() && entry.sizeBytes() != null) {
                    ours += entry.sizeBytes();
                }
            }
            return new StorageInfo(ours, null);
        }
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
        // NOTE: account listing is 403-by-design on Blomp, so existence is
        // checked against the container itself. A GET also tells us about it.
        HttpResponse<byte[]> existing = request("GET", appendQuery(containerUri(container), "format=json&limit=1"),
                HttpRequest.BodyPublishers.noBody(), null);
        if (existing.statusCode() != 404) {
            if (existing.statusCode() == 403) {
                // custom names are forbidden — switch to the account email container
                if (!container.equalsIgnoreCase(username)) {
                    warning.accept("Blomp refused container '" + container + "' — switching to your "
                            + "account container '" + username + "'");
                    container = username;
                    existing = request("GET", appendQuery(containerUri(container), "format=json&limit=1"),
                            HttpRequest.BodyPublishers.noBody(), null);
                }
            }
            if (existing.statusCode() != 404 && (existing.statusCode() < 200 || existing.statusCode() >= 300)) {
                requireSuccess(existing, "check Swift container");
            }
            return; // exists (or at least is readable)
        }

        HttpResponse<byte[]> response = request("PUT", containerUri(container),
                HttpRequest.BodyPublishers.noBody(), null);
        if (response.statusCode() == 403) {
            if (!container.equalsIgnoreCase(username)) {
                warning.accept("Blomp refused container '" + container + "' — switching to your "
                        + "account container '" + username + "'");
                container = username;
                response = request("PUT", containerUri(container),
                        HttpRequest.BodyPublishers.noBody(), null);
            }
            if (response.statusCode() == 403) {
                // some proxies refuse re-PUTing an existing container; if it is
                // readable we can proceed to use it
                HttpResponse<byte[]> check = request("GET",
                        appendQuery(containerUri(container), "format=json&limit=1"),
                        HttpRequest.BodyPublishers.noBody(), null);
                if (check.statusCode() >= 200 && check.statusCode() < 300) {
                    return;
                }
            }
        }
        if (response.statusCode() != 201 && response.statusCode() != 202) {
            requireSuccess(response, "create Swift container");
        }
    }

    private List<String> listContainers() throws IOException {
        HttpResponse<byte[]> response = request("GET", appendQuery(accountUri(), "format=json&limit=10000"),
                HttpRequest.BodyPublishers.noBody(), null);
        if (response.statusCode() == 403) {
            // by design on Blomp — the account only sees its own container
            warning.accept("Blomp hides account-level listing (403); using your own container");
            return List.of(container);
        }
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
            // pace the removals — Blomp replies 503 and briefly denies further
            // requests when hammered with bursts
            try {
                Thread.sleep(4_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("retention cleanup interrupted", e);
            }
            HttpResponse<byte[]> response = request("DELETE", objectUri(container, object),
                    HttpRequest.BodyPublishers.noBody(), null);
            if (response.statusCode() != 204 && response.statusCode() != 404) {
                requireSuccess(response, "remove oldest managed backup");
            } else {
                System.currentTimeMillis(); // avoid unused-variable style lints
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
        HttpRequest.Builder builder = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Session current = getSession();
            if (builder == null) {
                builder = HttpRequest.newBuilder(uri)
                        .timeout(java.time.Duration.ofMinutes(30))
                        .method(method, body);
                if (contentType != null) {
                    builder.header("Content-Type", contentType);
                }
            }
            HttpRequest httpRequest = builder.header("X-Auth-Token", current.token()).build();
            HttpResponse<byte[]> response;
            try {
                response = http.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            } catch (HttpConnectTimeoutException e) {
                dropPin(uri);
                if (attempt < 2) {
                    try {
                        Thread.sleep(3_000L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Blomp Swift request was interrupted", ie);
                    }
                    continue; // transient connect failure — retry
                }
                throw new IOException("Blomp Swift request could not connect: " + e.getMessage(), e);
            } catch (IOException e) {
                // dropped/reset connections and similar transport hiccups are
                // retryable; PUT is idempotent, GET/DELETE are read-only
                if (attempt < 2) {
                    try {
                        Thread.sleep(2_000L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Blomp Swift request was interrupted", ie);
                    }
                    continue;
                }
                throw new IOException("Blomp Swift request failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Blomp Swift request was interrupted", e);
            }
            if (response.statusCode() == 401) {
                // brief cool-down before re-auth — bursty requests trigger a
                // short account-level denial window on Blomp
                try {
                    Thread.sleep(25_000L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Blomp Swift request was interrupted", ie);
                }
                synchronized (this) {
                    if (session == current) {
                        session = null;
                    }
                }
                continue; // token expired or denied — re-auth and retry
            }
            // Swift answers 502/503/504 when the cluster is busy; retry patiently
            if (response.statusCode() >= 500 && attempt < 2) {
                try {
                    Thread.sleep(4_000L * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Blomp Swift request was interrupted", e);
                }
                continue;
            }
            return response;
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
            try {
                session = authenticate();
            } catch (IllegalStateException malformed) {
                throw new IOException(malformed.getMessage(), malformed);
            }
            session = new Session(connectableUri(session.storageUrl()), session.token());
            return session;
        }
    }

    /**
     * Tries the auth endpoints in the order that survives Blomp's network:
     * plain http first (Blomp's own storage API is http-only, and it keeps
     * the reachable-IP pinning honest since TLS certs do not cover IPs),
     * then https, for both Keystone v3 and the documented v2.0 fallback.
     */
    private Session authenticate() throws IOException {
        boolean baseIsHttp = authUrl.getScheme().equals("http");
        String[] attempts = baseIsHttp
                ? new String[]{"http|v3", "http|v2"}
                : new String[]{"http|v3", "https|v3", "http|v2", "https|v2"};
        StringBuilder reasons = new StringBuilder();
        for (String attempt : attempts) {
            String scheme = attempt.split("\\|")[0];
            String version = attempt.split("\\|")[1];
            int port = authUrl.getPort();
            String portPart = port == -1
                    || (scheme.equals("http") && port == 80)
                    || (scheme.equals("https") && port == 443) ? "" : ":" + port;
            String endpoint = scheme + "://" + authUrl.getHost() + portPart
                    + (version.equals("v3") ? "/v3/auth/tokens" : "/v2.0/tokens");
            try {
                if (version.equals("v3")) {
                    return keystoneV3(URI.create(endpoint));
                }
                return keystoneV2(URI.create(endpoint));
            } catch (IOException failure) {
                // surface every path so a "null" exception message can never
                // hide what actually failed
                String reason = failure.getMessage() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage();
                if (reasons.length() > 0) {
                    reasons.append("; ");
                }
                reasons.append(version).append("/").append(scheme).append(": ").append(reason);
            }
        }
        throw new IOException("could not authenticate against Blomp with any method — "
                + reasons);
    }

    /**
     * Blomp's hostnames resolve to several IPs and not all of them accept
     * connections; Java's HttpClient only dials the first resolved address
     * and dies with ConnectException (curl and Go fail over, Java does not).
     * Probe each resolved IPv4 with a 3s connect, pin the first reachable one
     * in a cache, and drop the pin whenever a request fails so the next
     * attempt re-resolves.
     */
    private URI connectableUri(URI uri) {
        // https must keep the real hostname: Blomp's TLS certificate does not
        // cover their bare IPs, and Java verifies the hostname. IP pinning is
        // therefore only applied to plain http (Blomp's storage API is
        // http-only anyway).
        if ("https".equals(uri.getScheme())) {
            return uri;
        }
        String key = uri.getHost();
        String cachedAddress = pinnedAddressesByHost.get(key);
        if (cachedAddress != null) {
            return withAddress(uri, cachedAddress);
        }
        String chosenAddress = null;
        try {
            int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(uri.getHost())) {
                try (java.net.Socket probe = new java.net.Socket()) {
                    probe.connect(new java.net.InetSocketAddress(address, port), 3000);
                    chosenAddress = address.getHostAddress();
                    break;
                } catch (IOException probeFailure) {
                    // dead node — try the next resolved address
                }
            }
        } catch (IOException | SecurityException ignored) {
            // resolution failed entirely; fall back to the original URI
        }
        if (chosenAddress == null) {
            chosenAddress = uri.getHost(); // keep DNS resolution as it is
        }
        pinnedAddressesByHost.put(key, chosenAddress);
        return withAddress(uri, chosenAddress);
    }

    /** Rebuilds the URI with the given address in place of the hostname. */
    private static URI withAddress(URI uri, String address) {
        if (uri.getHost().equals(address)) {
            return uri;
        }
        return URI.create(uri.toString().replaceFirst(java.util.regex.Pattern.quote(uri.getHost()),
                java.util.regex.Matcher.quoteReplacement(address)));
    }

    private void dropPin(URI uri) {
        pinnedAddressesByHost.remove(uri.getHost());
    }

    /**
     * Keystone v3: POST /v3/auth/tokens with email + password. The token comes
     * from the X-Subject-Token header, the storage URL from the "swift" catalog
     * entry picked from the token body.
     */
    private Session keystoneV3(URI endpoint) throws IOException {
        JsonObject identity = new JsonObject();
        com.google.gson.JsonArray methods = new com.google.gson.JsonArray();
        methods.add("password");
        identity.add("methods", methods);
        JsonObject user = new JsonObject();
        user.addProperty("name", username);
        JsonObject domain = new JsonObject();
        domain.addProperty("name", "Default");
        user.add("domain", domain);
        user.addProperty("password", password);
        JsonObject passwordObj = new JsonObject();
        passwordObj.add("user", user);
        identity.add("password", passwordObj);
        JsonObject auth = new JsonObject();
        auth.add("identity", identity);
        JsonObject envelope = new JsonObject();
        envelope.add("auth", auth);
        HttpRequest request = HttpRequest.newBuilder(connectableUri(endpoint))
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
    private Session keystoneV2(URI endpoint) throws IOException {
        JsonObject credentials = new JsonObject();
        credentials.addProperty("username", username);
        credentials.addProperty("password", password);
        JsonObject passwordCredentials = new JsonObject();
        passwordCredentials.add("passwordCredentials", credentials);
        passwordCredentials.addProperty("tenantName", "storage");
        JsonObject auth = new JsonObject();
        auth.add("auth", passwordCredentials);
        HttpRequest request = HttpRequest.newBuilder(connectableUri(endpoint))
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
            dropPin(request.uri());
            throw new IOException("the Blomp authentication endpoint (" + authUrl + ", " + version
                    + ") did not accept a connection in time — their API may be down; retry");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Blomp authentication was interrupted", e);
        } catch (IOException e) {
            dropPin(request.uri());
            String reason = e.getMessage() == null
                    ? e.getClass().getSimpleName() + " (TLS/connection-level failure)"
                    : e.getMessage();
            throw new IOException("could not reach the Blomp authentication endpoint (" + authUrl
                    + ", " + version + "): " + reason, e);
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
        // keep '@' raw: Blomp's account container is the account email, and
        // the proxy treats %40 as a different (forbidden) container name
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("%40", "@");
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