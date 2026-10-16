package dev.tinyvault;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /vault command family with structured, newest-first output.
 * - detail: open to everyone, as proof that backups are being made.
 * - back / list / fails / passes: operator only.
 * Times are shown in the zone configured under `timezone` (default Asia/Kolkata).
 */
final class VaultCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("detail", "back", "list", "fails", "passes");

    private final TinyVaultPlugin plugin;
    private final BackupCoordinator coordinator;
    private final ZoneId zone;
    private final DateTimeFormatter fullTime;
    private final DateTimeFormatter dayTime;

    VaultCommand(TinyVaultPlugin plugin, BackupCoordinator coordinator) {
        this.plugin = plugin;
        this.coordinator = coordinator;
        ZoneId parsed;
        try {
            parsed = ZoneId.of(plugin.getConfig().getString("timezone", "Asia/Kolkata").strip());
        } catch (Exception e) {
            parsed = ZoneId.of("Asia/Kolkata");
        }
        this.zone = parsed;
        boolean twelveHour = "12".equalsIgnoreCase(
                plugin.getConfig().getString("time-format", "24").strip());
        this.fullTime = DateTimeFormatter
                .ofPattern(twelveHour ? "dd MMM yyyy, hh:mm a z" : "dd MMM yyyy, HH:mm z", Locale.ENGLISH)
                .withZone(zone);
        this.dayTime = DateTimeFormatter
                .ofPattern(twelveHour ? "hh:mm a z" : "HH:mm z", Locale.ENGLISH)
                .withZone(zone);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "detail" -> showDetail(sender);
            case "back" -> {
                if (requireOperator(sender)) {
                    startManualBackup(sender);
                }
            }
            case "list" -> {
                if (requireOperator(sender)) {
                    showList(sender);
                }
            }
            case "fails" -> {
                if (requireOperator(sender)) {
                    showFails(sender);
                }
            }
            case "passes" -> {
                if (requireOperator(sender)) {
                    showPasses(sender);
                }
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(sub -> sub.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("Usage: /vault <detail|back|list|fails|passes> (back, list, fails, passes are operator only)");
    }

    private boolean requireOperator(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("tinyvault.admin")) {
            return true;
        }
        sender.sendMessage("[TinyVault] This subcommand is operator only.");
        return false;
    }

    private String header(String title) {
        return "----[ TinyVault · " + title + " ]----";
    }

    private String field(String label, String value) {
        return String.format(Locale.ROOT, " %-15s %s", label, value);
    }

    private String sizeText(Long size) {
        return size == null ? "missing" : String.format(Locale.ROOT, "%.1f MiB", size / 1024.0 / 1024.0);
    }

    private String markerFor(int index) {
        return index == 1 ? "  <- latest" : "";
    }

    /* ---- detail (open to everyone) ---- */

    private void showDetail(CommandSender sender) {
        BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
        new BukkitRunnable() {
            @Override
            public void run() {
                long recentFailedCount = snapshot.failures().stream()
                        .filter(entry -> parseTime(entry).isAfter(Instant.now().minus(Duration.ofHours(48))))
                        .count();
                Instant nextDue = coordinator.nextDue();

                sender.sendMessage(header("backup status"));
                sender.sendMessage(field("timezone:", zone.toString()));
                Map<String, Long> slots = coordinator.store().slots();
                boolean first = true;
                for (Map.Entry<String, Long> slot : slots.entrySet()) {
                    String label = first ? "latest:" : "one behind:" ;
                    first = false;
                    String name = slot.getKey().startsWith(S3BackupStore.PREFIX)
                            ? slot.getKey().substring(S3BackupStore.PREFIX.length()) : slot.getKey();
                    sender.sendMessage(field(label + "  ", name + "  " + sizeText(slot.getValue())));
                }
                if (slots.isEmpty()) {
                    sender.sendMessage(field("slots:", "none found"));
                }
                sender.sendMessage(field("extreme:", coordinator.isExtreme()
                        ? "ON — backup before shutdown (min session " + coordinator.minSessionMinutes() + " min)"
                        : "off"));
                sender.sendMessage(field("last success:", snapshot.lastSuccess() == null
                        ? "never" : fullTime.format(snapshot.lastSuccess())));
                sender.sendMessage(field("status:", snapshot.currentStatus()
                        + (coordinator.backupRunning() ? " · a backup is running right now" : "")));
                sender.sendMessage(field("next due:", coordinator.isExtreme()
                        ? "excused — extreme mode backs up at shutdown"
                        : (nextDue.equals(Instant.EPOCH) ? "now (overdue)" : fullTime.format(nextDue))));
                sender.sendMessage(field("failures 48h:", recentFailedCount + "  (recorded total: "
                        + snapshot.failures().size() + ")"));
            }
        }.runTaskAsynchronously(plugin);
    }

    private void startManualBackup(CommandSender sender) {
        if (!coordinator.requestManualBackup()) {
            sender.sendMessage("[TinyVault] A backup is already running.");
            return;
        }
        Instant previousLastSuccess = coordinator.state().lastSuccess();
        sender.sendMessage("----[ TinyVault · backup starting ]----");
        sender.sendMessage("  progress updates arrive every couple of minutes — hang tight.");
        new BukkitRunnable() {
            private BackupCoordinator.Phase lastAnnounced = BackupCoordinator.Phase.IDLE;
            private boolean sawRunning = false;
            private int ticksElapsed = 0;

            @Override
            public void run() {
                ticksElapsed += 40L; // 2s period
                BackupCoordinator.Phase phase = coordinator.phase();
                boolean running = coordinator.backupRunning();
                if (running || phase != BackupCoordinator.Phase.IDLE) {
                    sawRunning = true;
                }
                if (phase != lastAnnounced && phase != BackupCoordinator.Phase.IDLE) {
                    sender.sendMessage(" " + phaseText(phase));
                    lastAnnounced = phase;
                }
                if (sawRunning && !running && phase == BackupCoordinator.Phase.IDLE) {
                    finishReport(previousLastSuccess);
                    cancel();
                    return;
                }
            }

            private String phaseText(BackupCoordinator.Phase phase) {
                return switch (phase) {
                    case SAVING -> "saving worlds (autosave paused)…";
                    case ARCHIVING -> "compressing the server into one ZIP…";
                    case UPLOADING -> "uploading to storage (large archives take several minutes)…";
                    default -> "";
                };
            }

            private void finishReport(Instant baseline) {
                BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
                if (snapshot.lastSuccess() != null && !snapshot.lastSuccess().equals(baseline)) {
                    sender.sendMessage("----[ TinyVault · backup complete ]----");
                    sender.sendMessage("  done:          uploaded, verified and recorded");
                    sender.sendMessage("  recorded at:   " + fullTime.format(snapshot.lastSuccess()));
                    sender.sendMessage("  kept:          an older copy always sits one slot behind");
                    sender.sendMessage("  nothing was lost — your worlds and player data are on storage.");
                } else {
                    String raw = snapshot.failures().isEmpty() ? "" : snapshot.failures().get(0);
                    String reason = raw.contains(" | ") ? raw.substring(raw.indexOf(" | ") + 3) : raw;
                    sender.sendMessage("----[ TinyVault · backup failed ]----");
                    sender.sendMessage("  what happened:  " + friendlyReason(reason));
                    sender.sendMessage("  technical:      " + (reason.isEmpty() ? "unknown" : reason));
                    sender.sendMessage("  untouched:      your existing backups were NOT overwritten");
                    sender.sendMessage("  next:           the next run retries automatically; "
                            + "watch progress with /vault detail");
                }
            }
        }.runTaskTimerAsynchronously(plugin, 0L, 40L);
    }

    /**
     * Turns S3/gateway error strings into something a person can actually act on.
     * Package-visible so the Discord webhook gets the same friendly wording.
     */
    static String friendlyReason(String reason) {
        if (reason.isBlank()) {
            return "no reason was recorded — check the server log";
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("expected ") && lower.contains("bytes") || lower.contains("propagat")) {
            return "the storage service is still catching up with the fresh upload";
        }
        if (lower.contains("checksum")) {
            return "the uploaded archive was not readable as a whole just yet";
        }
        if (lower.contains("quota") || lower.contains("storage") && lower.contains("full")
                || lower.contains("403") && lower.contains("exceed")) {
            return "storage is full — free up space in the bucket or FileLu trash";
        }
        if (lower.contains("connection") || lower.contains("timed out") || lower.contains("timeout")
                || lower.contains("unknownhost") || lower.contains("dns")) {
            return "the server could not reach the storage service (network hiccup)";
        }
        if (lower.contains("aborted") || lower.contains("interrupt")) {
            return "the upload was interrupted midway (server busy or connection dropped)";
        }
        if (lower.contains("denied") || lower.contains("credentials")) {
            return "storage refused the request — check access-key and secret-key in config.yml";
        }
        return "storage did not accept the backup";
    }

    /* ---- list ---- */

    private void showList(CommandSender sender) {
        BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
        LocalDate today = LocalDate.now(zone);
        sender.sendMessage(header("recorded backups (latest first)"));
        int index = 1;
        int todays = 0;
        for (Instant timestamp : snapshot.successes()) { // newest first
            boolean isToday = LocalDate.ofInstant(timestamp, zone).equals(today);
            String when = isToday ? dayTime.format(timestamp) + " (today)" : fullTime.format(timestamp);
            sender.sendMessage(" " + index + ".  " + when + markerFor(index));
            if (isToday) {
                todays++;
            }
            index++;
        }
        if (index == 1) {
            sender.sendMessage(" none yet");
        }
        sender.sendMessage(" backups today: " + todays);
    }

    private void showPasses(CommandSender sender) {
        BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
        sender.sendMessage(header("passes (successful backups, latest first)"));
        if (snapshot.successes().isEmpty()) {
            sender.sendMessage(" none yet");
        }
        int index = 1;
        for (Instant timestamp : snapshot.successes()) {
            sender.sendMessage(" " + index + ".  " + fullTime.format(timestamp) + markerFor(index));
            index++;
        }
    }

    private void showFails(CommandSender sender) {
        BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
        Instant cutoff = Instant.now().minus(Duration.ofHours(48));
        sender.sendMessage(header("failures (last 48h, latest first)"));
        int index = 1;
        int recent = 0;
        for (String failure : snapshot.failures()) { // newest first
            Instant at = parseTime(failure);
            if (!at.isAfter(cutoff)) {
                continue;
            }
            recent++;
            String reason = failure.contains(" | ") ? failure.substring(failure.indexOf(" | ") + 3) : failure;
            sender.sendMessage(" " + index + ".  " + fullTime.format(at));
            sender.sendMessage("      what happened: " + friendlyReason(reason));
            index++;
        }
        if (index == 1) {
            sender.sendMessage(" none in the last 48 hours");
        }
    }

    private static Instant parseTime(String entry) {
        int separator = entry.indexOf(" | ");
        if (separator <= 0) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(entry.substring(0, separator));
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
