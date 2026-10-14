package dev.tinyvault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Local state file with three sections: #Timestamps (last successful backup
 * timestamps), #failures (recent failures with brief reasons) and #current
 * (timestamp and status of the current backup). Writes go through a temp file
 * and an atomic move, and a backup is only recorded as successful after its
 * upload has completed and been verified.
 */
final class BackupState {
    private static final int MAX_TIMESTAMPS = 5;
    private static final int MAX_FAILURES = 10;
    private static final int MAX_REASON_LENGTH = 200;

    private final Path file;
    private final Deque<Instant> successes = new ArrayDeque<>();
    private final Deque<String> failures = new ArrayDeque<>();
    private String currentStatus = "NONE";

    BackupState(Path file) {
        this.file = file;
    }

    synchronized void load() throws IOException {
        successes.clear();
        failures.clear();
        currentStatus = "NONE";
        if (!Files.exists(file)) {
            return;
        }
        String section = "";
        for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                section = line;
                continue;
            }
            switch (section) {
                case "#Timestamps" -> parseInstant(line).ifPresent(successes::addLast);
                case "#failures" -> failures.addLast(line);
                case "#current" -> {
                    int eq = line.indexOf('=');
                    if (eq > 0 && line.substring(0, eq).strip().equals("status")) {
                        currentStatus = line.substring(eq + 1).strip();
                    }
                }
                default -> {
                }
            }
        }
    }

    /** Most recent successful backup timestamp, or null if there never was one. */
    synchronized Instant lastSuccess() {
        return successes.peekFirst();
    }

    /** Called only after the upload to S3 completed and was verified. */
    synchronized void recordSuccess(Instant when) throws IOException {
        successes.addFirst(when);
        while (successes.size() > MAX_TIMESTAMPS) {
            successes.removeLast();
        }
        currentStatus = "OK";
        write();
    }

    synchronized void recordFailure(Instant when, String reason) throws IOException {
        String brief = reason.replace('\n', ' ').replace('\r', ' ');
        if (brief.length() > MAX_REASON_LENGTH) {
            brief = brief.substring(0, MAX_REASON_LENGTH);
        }
        failures.addFirst(when + " | " + brief);
        while (failures.size() > MAX_FAILURES) {
            failures.removeLast();
        }
        currentStatus = "FAILED";
        write();
    }

    private void write() throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("#Timestamps\n");
        for (Instant timestamp : successes) {
            out.append(timestamp).append('\n');
        }
        out.append("#failures\n");
        for (String failure : failures) {
            out.append(failure).append('\n');
        }
        out.append("#current\n");
        Instant current = successes.peekFirst();
        out.append("timestamp=").append(current == null ? "none" : current.toString()).append('\n');
        out.append("status=").append(currentStatus).append('\n');

        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, out.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Optional<Instant> parseInstant(String line) {
        try {
            return Optional.of(Instant.parse(line));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
