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
 * /vault command family.
 * - detail: open to everyone, as proof that backups are being made.
 * - back / list / fails / passes: operator only.
 */
final class VaultCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("detail", "back", "list", "fails", "passes");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final TinyVaultPlugin plugin;
    private final BackupCoordinator coordinator;

    VaultCommand(TinyVaultPlugin plugin, BackupCoordinator coordinator) {
        this.plugin = plugin;
        this.coordinator = coordinator;
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
        sender.sendMessage("Usage: /vault <detail|back|list|fails|passes> (last four are operator only)");
    }

    private boolean requireOperator(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("tinyvault.admin")) {
            return true;
        }
        sender.sendMessage("This subcommand is operator only.");
        return false;
    }

    /** Proof for anyone: remote backup sizes plus the state summary. Network work runs async. */
    private void showDetail(CommandSender sender) {
        BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
        new BukkitRunnable() {
            @Override
            public void run() {
                sender.sendMessage("[TinyVault] Backup status");
                Map<String, Long> sizes = coordinator.store().backupSizes();
                sender.sendMessage(describeObject("current.zip (latest)",
                        sizes.get(S3BackupStore.PREFIX + "current.zip")));
                sender.sendMessage(describeObject("old.zip (previous)",
                        sizes.get(S3BackupStore.PREFIX + "old.zip")));
                sender.sendMessage("last success: " + (snapshot.lastSuccess() == null
                        ? "never"
                        : TIME_FORMAT.format(snapshot.lastSuccess()) + " (status " + snapshot.currentStatus() + ")"));
                Instant nextDue = coordinator.nextDue();
                sender.sendMessage("next backup due: " + (nextDue.equals(Instant.EPOCH)
                        ? "now (overdue)" : TIME_FORMAT.format(nextDue)));
                if (coordinator.backupRunning()) {
                    sender.sendMessage("a backup is running right now");
                }
                sender.sendMessage("recorded failures: " + snapshot.failures().size()
                        + " (use /vault fails for the last 48h)");
            }
        }.runTaskAsynchronously(plugin);
    }

    private String describeObject(String label, Long size) {
        return label + ": " + (size == null
                ? "missing"
                : String.format(Locale.ROOT, "%.1f MiB", size / 1024.0 / 1024.0));
    }

    private void startManualBackup(CommandSender sender) {
        if (coordinator.requestManualBackup()) {
            sender.sendMessage("[TinyVault] Backup started; it is recorded like a scheduled one and the next timer window starts from it.");
        } else {
            sender.sendMessage("[TinyVault] A backup is already running.");
        }
    }

    private void showList(CommandSender sender) {
        BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        List<String> todayLines = new java.util.ArrayList<>();
        List<String> olderLines = new java.util.ArrayList<>();
        for (Instant timestamp : snapshot.successes()) {
            if (LocalDate.ofInstant(timestamp, zone).equals(today)) {
                todayLines.add(" - " + TIME_FORMAT.format(timestamp));
            } else {
                olderLines.add(" - " + TIME_FORMAT.format(timestamp));
            }
        }
        sender.sendMessage("[TinyVault] Backups today: " + todayLines.size());
        todayLines.forEach(sender::sendMessage);
        if (todayLines.isEmpty() && olderLines.isEmpty()) {
            sender.sendMessage("[TinyVault] No successful backups recorded yet.");
        } else if (!olderLines.isEmpty()) {
            sender.sendMessage("[TinyVault] Older recorded backups (only the last 5 are kept):");
            olderLines.forEach(sender::sendMessage);
        }
    }

    private void showFails(CommandSender sender) {
        BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
        Instant cutoff = Instant.now().minus(Duration.ofHours(48));
        List<String> recent = snapshot.failures().stream()
                .filter(entry -> parseTime(entry).isAfter(cutoff))
                .limit(10)
                .toList();
        sender.sendMessage("[TinyVault] Failures in the last 48 hours: " + recent.size());
        recent.forEach(sender::sendMessage);
    }

    private void showPasses(CommandSender sender) {
        BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
        sender.sendMessage("[TinyVault] Successful backups (last 5):");
        if (snapshot.successes().isEmpty()) {
            sender.sendMessage(" - none yet");
        }
        snapshot.successes().forEach(timestamp -> sender.sendMessage(" - " + TIME_FORMAT.format(timestamp)));
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
