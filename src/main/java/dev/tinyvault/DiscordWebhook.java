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

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final java.util.concurrent.ExecutorService sender =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "TinyVault-webhook");
                thread.setDaemon(true);
                return thread;
            });

    DiscordWebhook(String url, Consumer<String> warning) {
        this.url = (url == null || url.isBlank()) ? null : url.strip();
        this.warning = warning;
    }

    /**
     * Start announcement.
     *
     * @param trigger how the backup was started (scheduled, manual, shutdown…)
     * @param note    one extra line of context, e.g. the interval of the run;
     *                may be null
     */
    void started(BackupCoordinator.Trigger trigger, Instant at, String note) {
        post(embed(COLOR_STARTED, "Backup started", note, at,
                new String[][]{{"trigger", trigger.label}}));
    }

    /** Success announcement with the numbers a person actually cares about. */
    void success(Instant recordedAt, long archiveBytes, Duration took, String nextNote) {
        post(embed(COLOR_SUCCESS, "Backup completed", nextNote, recordedAt,
                new String[][]{{"size", sizeText(archiveBytes)}, {"took", durationText(took)}}));
    }

    /** Failure announcement; the friendly line first, the raw error as detail. */
    void failed(Instant at, String friendlyReason, String rawReason) {
        String detail = rawReason == null || rawReason.isBlank() ? "unknown" : rawReason;
        if (detail.length() > 500) {
            detail = detail.substring(0, 500) + "…";
        }
        post(embed(COLOR_FAILED, "Backup failed", friendlyReason, at,
                new String[][]{{"detail", detail}}));
    }

    /* ---- payload building ---- */

    private String embed(int color, String title, String description, Instant timestamp,
                         String[][] fields) {
        StringBuilder out = new StringBuilder();
        out.append("{\"username\":\"TinyVault\",\"embeds\":[{\"title\":\"")
                .append(jsonEscape(title)).append('"');
        if (description != null && !description.isBlank()) {
            out.append(",\"description\":\"").append(jsonEscape("*" + description + "*")).append('"');
        }
        out.append(",\"color\":").append(color);
        out.append(",\"timestamp\":\"").append(timestamp).append('"');
        out.append(",\"fields\":[");
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append("{\"name\":\"").append(jsonEscape(fields[i][0]))
                    .append("\",\"value\":\"").append(jsonEscape(fields[i][1]))
                    .append("\",\"inline\":true}");
        }
        out.append("}]}");
        return out.toString();
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
                    warning.accept("Discord webhook replied HTTP " + response.statusCode());
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
