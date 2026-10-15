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
        this.fullTime = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm z", Locale.ENGLISH).withZone(zone);
        this.dayTime = DateTimeFormatter.ofPattern("HH:mm z", Locale.ENGLISH).withZone(zone);
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
                Map<String, Long> sizes = coordinator.store().backupSizes();
                long recentFailedCount = snapshot.failures().stream()
                        .filter(entry -> parseTime(entry).isAfter(Instant.now().minus(Duration.ofHours(48))))
                        .count();
                Instant nextDue = coordinator.nextDue();

                sender.sendMessage(header("backup status"));
                sender.sendMessage(field("timezone:", zone.toString()));
                sender.sendMessage(field("current.zip:", sizeText(sizes.get(S3BackupStore.PREFIX + "current.zip")) + "  (latest)"));
                sender.sendMessage(field("old.zip:", sizeText(sizes.get(S3BackupStore.PREFIX + "old.zip")) + "  (previous)"));
                sender.sendMessage(field("last success:", snapshot.lastSuccess() == null
                        ? "never" : fullTime.format(snapshot.lastSuccess())));
                sender.sendMessage(field("status:", snapshot.currentStatus()
                        + (coordinator.backupRunning() ? " · a backup is running right now" : "")));
                sender.sendMessage(field("next due:", nextDue.equals(Instant.EPOCH)
                        ? "now (overdue)" : fullTime.format(nextDue)));
                sender.sendMessage(field("failures 48h:", recentFailedCount + "  (recorded total: "
                        + snapshot.failures().size() + ")"));
            }
        }.runTaskAsynchronously(plugin);
    }

    private void startManualBackup(CommandSender sender) {
        if (coordinator.requestManualBackup()) {
            sender.sendMessage("[TinyVault] Backup started. It is recorded like a scheduled one; the next time window starts from it.");
        } else {
            sender.sendMessage("[TinyVault] A backup is already running.");
        }
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
            sender.sendMessage("      reason: " + reason);
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
