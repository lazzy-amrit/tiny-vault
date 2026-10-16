package dev.tinyvault;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Optional Discord announcements through a webhook (config key
 * `webhook-url`). Each backup start, success and failure goes out as a
 * colored embed — blue for started, green for success, red for failure.
 * Posts run on a single background thread: backup runs and server shutdown
 * never wait on Discord, and a webhook failure only logs a warning.
 */
final class DiscordWebhook {
    private static final int COLOR_STARTED = 0x3498DB;
    private static final int COLOR_SUCCESS = 0x2ECC71;
    private static final int COLOR_FAILED = 0xE74C3C;

    private final String url; // null = disabled
    private final Consumer<String> warning;

    private final HttpClient client;

    private final java.util.concurrent.ExecutorService sender =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "TinyVault-webhook");
                thread.setDaemon(true);
                return thread;
            });

    DiscordWebhook(String rawUrl, Consumer<String> warning) {
        this(rawUrl, warning, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    DiscordWebhook(String rawUrl, Consumer<String> warning, HttpClient client) {
        this.warning = warning;
        this.client = client;
        String cleaned = sanitizeWebhookUrl(rawUrl);
        if (!cleaned.isBlank() && !cleaned.startsWith("https://discord.com/api/webhooks/")
                && !cleaned.startsWith("https://discordapp.com/api/webhooks/")) {
            warning.accept("webhook-url does not look like a Discord webhook (expected "
                    + "https://discord.com/api/webhooks/...) — announcements stay disabled until it is fixed");
        }
        this.url = cleaned.isBlank() ? null : cleaned;
    }

    /**
     * Config values often arrive wrapped in quotes when pasted from chat ("…"),
     * copied between fences, or written with trailing whitespace. Strip all of
     * that; anything that is not clearly a webhook URL is rejected instead of
     * being POSTed to Discord (which would answer 400).
     */
    static String sanitizeWebhookUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String out = raw.strip();
        out = out.replace("```", "").replace("**", "").strip();
        while (out.length() > 0 && "\"'`,; ".indexOf(out.charAt(0)) >= 0) {
            out = out.substring(1);
        }
        while (out.length() > 0 && "\"'`,; ".indexOf(out.charAt(out.length() - 1)) >= 0) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** Start announcement. */
    void started(BackupCoordinator.Trigger trigger, Instant at, String interval) {
        post(embed(COLOR_STARTED, "Backup started",
                "Whole-server archive in progress — autosaves pause meanwhile.", at,
                "TinyVault · automatic server protection",
                (interval == null)
                        ? new String[][]{{"Trigger", trigger.label}}
                        : new String[][]{{"Trigger", trigger.label}, {"Every", interval}}));
    }

    /** Success announcement with the numbers a person actually cares about. */
    void success(Instant recordedAt, long archiveBytes, Duration took, String nextText) {
        post(embed(COLOR_SUCCESS, "Backup completed",
                "Uploaded successfully and in place.", recordedAt,
                "TinyVault · automatic server protection",
                new String[][]{{"Archive", sizeText(archiveBytes)}, {"Took", durationText(took)},
                        {"Next", nextText}}));
    }

    /** Failure announcement; the friendly line first, the raw error as detail. */
    void failed(Instant at, String friendlyReason, String rawReason) {
        post(embed(COLOR_FAILED, "Backup failed",
                friendlyReason, at,
                "Existing backups were not overwritten",
                new String[][]{{"Detail", rawReason == null || rawReason.isBlank() ? "unknown" : rawReason}}));
    }

    /* ---- payload building ---- */

    private String embed(int color, String title, String description, Instant timestamp,
                         String footer, String[][] fields) {
        // Discord rejects sub-millisecond-precision ISO timestamps with 400.
        String cleanTimestamp = timestamp.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
        StringBuilder out = new StringBuilder();
        out.append("{\"username\":\"TinyVault\",\"embeds\":[{\"title\":\"")
                .append(jsonEscape(fieldText(title, 256))).append('"');
        if (description != null && !description.isBlank()) {
            out.append(",\"description\":\"")
                    .append(jsonEscape(fieldText(description, 2048))).append('"');
        }
        out.append(",\"color\":").append(color);
        out.append(",\"timestamp\":\"").append(cleanTimestamp).append('"');
        out.append(",\"fields\":[");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"name\":\"").append(jsonEscape(fieldText(fields[i][0], 256)))
                    .append("\",\"value\":\"").append(jsonEscape(fieldText(fields[i][1], 1024)))
                    .append("\",\"inline\":true}");
        }
        // closes: fields array, embed object, embeds array, root object —
        // the field's own } came from the loop above
        out.append(']');
        if (footer != null && !footer.isBlank()) {
            out.append(",\"footer\":{\"text\":\"").append(jsonEscape(fieldText(footer, 2044)))
                    .append("\"}");
        }
        out.append('}');
        out.append(']');
        out.append('}');
        return out.toString();
    }

    /**
     * Discord rejects empty or over-limit embed strings with 400: names cap
     * at 256, values at 1024, descriptions at 2048. Empty values get a
     * placeholder so nothing can slip through as "".
     */
    private static String fieldText(String in, int limit) {
        if (in == null) {
            return "n/a";
        }
        String out = in.strip();
        if (out.isEmpty()) {
            return "n/a";
        }
        if (out.length() > limit) {
            out = out.substring(0, limit - 1) + "…";
        }
        return out;
    }

    private void post(String payload) {
        if (url == null) {
            return;
        }
        sender.execute(() -> {
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build();
                    HttpResponse<String> response =
                            client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() < 300) {
                        return;
                    }
                    if (response.statusCode() == 429 && attempt == 1) {
                        // rate limited — wait what Discord asked and try once more
                        double seconds;
                        try {
                            seconds = Double.parseDouble(
                                    response.headers().firstValue("Retry-After").orElse("2"));
                        } catch (NumberFormatException e) {
                            seconds = 2.0;
                        }
                        Thread.sleep((long) (seconds * 1000.0));
                        continue;
                    }
                    String body = response.body() == null ? "" : response.body().strip();
                    if (body.length() > 300) {
                        body = body.substring(0, 300) + "…";
                    }
                    warning.accept("Discord webhook replied HTTP " + response.statusCode()
                            + (body.isEmpty() ? "" : ": " + body));
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    warning.accept("Discord webhook could not post: " + e.getMessage());
                    return;
                }
            }
        });
    }

    private static String sizeText(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1024.0 / 1024.0);
    }

    private static String durationText(Duration took) {
        long seconds = took.toSeconds();
        if (seconds < 120) {
            return seconds + "s";
        }
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private static String jsonEscape(String in) {
        StringBuilder out = new StringBuilder(in.length() + 16);
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    void close() {
        sender.shutdown();
    }
}
