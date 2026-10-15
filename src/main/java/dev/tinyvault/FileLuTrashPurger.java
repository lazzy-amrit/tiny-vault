package dev.tinyvault;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort purging of FileLu's trash via the FileLu account API. S3 DELETE
 * moves objects into FileLu's trash, where they can keep consuming quota until
 * they are purged. This purger lists deleted files and permanently removes
 * every trash entry whose name matches one of our backup object names, so a
 * rotation always frees exactly what it needs.
 */
final class FileLuTrashPurger {
    private static final List<String> BACKUP_NAMES =
            List.of("current.zip", "old.zip", "uploading.zip");
    private static final Pattern ENTRY_PATTERN = Pattern.compile("\\{[^{}]*\\}", Pattern.DOTALL);
    private static final Pattern FILE_CODE_PATTERN =
            Pattern.compile("\"file_code\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NAME_PATTERN =
            Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final String apiKey;
    private final Consumer<String> warn;

    FileLuTrashPurger(String apiKey, Consumer<String> warn) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.warn = warn;
    }

    boolean enabled() {
        return !apiKey.isEmpty();
    }

    /** Purges trash entries for our backup names; never throws. */
    void purgeBackupNames() {
        if (!enabled()) {
            return;
        }
        try {
            String body = get(URI.create("https://filelu.com/api/files/deleted?key=" + encode(apiKey)));
            Matcher entryMatcher = ENTRY_PATTERN.matcher(body);
            while (entryMatcher.find()) {
                String entry = entryMatcher.group();
                String name = firstGroup(NAME_PATTERN, entry);
                if (name == null || !backupNameOf(name)) {
                    continue;
                }
                String fileCode = firstGroup(FILE_CODE_PATTERN, entry);
                if (fileCode != null) {
                    purge(fileCode, name);
                }
            }
        } catch (Exception e) {
            warn.accept("Could not purge FileLu trash: " + e.getMessage());
        }
    }

    private boolean backupNameOf(String name) {
        String bare = name.substring(name.lastIndexOf('/') + 1);
        return BACKUP_NAMES.contains(bare);
    }

    private void purge(String fileCode, String name) {
        try {
            String response = get(URI.create("https://filelu.com/api/file/remove?file_code="
                    + encode(fileCode) + "&remove=1&key=" + encode(apiKey)));
            if (!response.contains("\"status\":200")) {
                warn.accept("FileLu refused to purge trash entry '" + name + "'");
            }
        } catch (Exception e) {
            warn.accept("Could not purge FileLu trash entry '" + name + "': " + e.getMessage());
        }
    }

    private String get(URI uri) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String firstGroup(Pattern pattern, String input) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : null;
    }
}
