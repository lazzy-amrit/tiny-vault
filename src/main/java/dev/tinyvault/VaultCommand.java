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
 * - back / list / fails / passes / storage / files: operator only.
 * Times are shown in the zone configured under `timezone` (default Asia/Kolkata).
 */
final class VaultCommand implements CommandExecutor, TabCompleter {
        private static final List<String> SUBCOMMANDS =
            List.of("detail", "back", "list", "fails", "passes", "storage", "files");

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
            case "storage" -> {
                if (requireOperator(sender)) {
                    showStorage(sender);
                }
            }
            case "files" -> {
                if (requireOperator(sender)) {
                    int page = 1;
                    if (args.length > 1) {
                        try {
                            page = Math.max(1, Integer.parseInt(args[1]));
                        } catch (NumberFormatException e) {
                            sender.sendMessage("[TinyVault] Page must be a positive number.");
                            return true;
                        }
                    }
                    showFiles(sender, page);
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
        sender.sendMessage("Usage: /vault <detail|back|list|fails|passes|storage|files [page]>");
    }

    private boolean requireOperator(CommandSender sender) {
        if (sender.isOp() || sender.hasPermission("tinyvault.admin")) {
            return true;
        }
        sender.sendMessage("[TinyVault] This subcommand is operator only.");
        return false;
    }

    private String header(String title) {
        return "╭─✦ TinyVault · " + title + " ✦";
    }

    private String line() {
        return "╰───────────────────────────────────────";
    }

    private String field(String label, String value) {
        return String.format(Locale.ROOT, "│ %-16s %s", label, value);
    }

    private String plain(String text) {
        return "│ " + text;
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
                try {
                    Map<String, Long> slots = coordinator.store().backupSlots();
                    boolean first = true;
                    for (Map.Entry<String, Long> slot : slots.entrySet()) {
                        sender.sendMessage(field(first ? "latest:" : "behind:", slot.getKey()
                                .substring(slot.getKey().lastIndexOf('/') + 1)
                                .replaceAll("-\\d{13}\\.zip$", "") + "  " + sizeText(slot.getValue())));
                        first = false;
                    }
                    if (slots.isEmpty()) {
                        sender.sendMessage(plain("no backups on storage yet — run /vault back"));
                    }
                } catch (Exception e) {
                    sender.sendMessage(field("remote:", "unavailable (" + safeMessage(e) + ")"));
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
                sender.sendMessage(field("failures 48h:", recentFailedCount + "  (" + snapshot.failures().size()
                        + " recorded all-time)"));
                sender.sendMessage(line());
            }
        }.runTaskAsynchronously(plugin);
    }

    private void showStorage(CommandSender sender) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    BlompSwiftStore.StorageInfo info = coordinator.store().storageInfo();
                    sender.sendMessage(header("blomp account storage"));
                    sender.sendMessage(field("used:", BlompSwiftStore.gbText(info.usedBytes())));
                    sender.sendMessage(field("quota:", BlompSwiftStore.gbText(info.quotaBytes())));
                    sender.sendMessage(field("available:", BlompSwiftStore.gbText(info.freeBytes())));
                    sender.sendMessage(field("when full:", "oldest backups are removed beyond the limit — "
                            + "free space is only for you"));
                    sender.sendMessage(line());
                } catch (Exception e) {
                    sender.sendMessage(header("blomp account storage"));
                    sender.sendMessage(field("status:", "unavailable (" + safeMessage(e) + ")"));
                    sender.sendMessage(plain("this usually means Blomp's Swift API is having an outage "
                            + "when every method 403s — try again later"));
                    sender.sendMessage(line());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private void showFiles(CommandSender sender, int page) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    List<BlompSwiftStore.StorageEntry> entries = coordinator.store().listContents();

                    // file-manager style: numbered containers, their files listed
                    // inside, sized; the account email is never shown
                    Map<String, List<BlompSwiftStore.StorageEntry>> groups = new java.util.LinkedHashMap<>();
                    for (BlompSwiftStore.StorageEntry entry : entries) {
                        if (entry.directory()) {
                            if (entry.path().indexOf('/') == entry.path().length() - 1) {
                                groups.putIfAbsent(entry.path(), new java.util.ArrayList<>());
                            }
                            continue; // deeper prefix markers are covered by their files
                        }
                        int slash = entry.path().indexOf('/');
                        String group = slash < 0 ? "" : entry.path().substring(0, slash + 1);
                        groups.computeIfAbsent(group, k -> new java.util.ArrayList<>()).add(entry);
                    }

                    List<String> lines = new java.util.ArrayList<>();
                    int containerNumber = 1;
                    int fileCount = 0;
                    for (Map.Entry<String, List<BlompSwiftStore.StorageEntry>> group : groups.entrySet()) {
                        String name = group.getKey().replace("/", "");
                        String display = name.contains("@") ? name.substring(0, name.indexOf('@'))
                                + " … (your account)" : name;
                        List<BlompSwiftStore.StorageEntry> files = group.getValue();
                        lines.add(containerNumber + ". " + display + "   " + files.size() + " file"
                                + (files.size() == 1 ? "" : "s"));
                        containerNumber++;
                        for (BlompSwiftStore.StorageEntry file : files) {
                            String inner = file.path().substring(group.getKey().length());
                            lines.add("     " + inner + "   " + byteText(file.sizeBytes()));
                            fileCount++;
                        }
                    }

                    int pageSize = 20;
                    int pages = Math.max(1, (lines.size() + pageSize - 1) / pageSize);
                    int selectedPage = Math.min(page, pages);
                    sender.sendMessage(header("blomp file manager · page " + selectedPage + " of " + pages));
                    int from = (selectedPage - 1) * pageSize;
                    int to = Math.min(lines.size(), from + pageSize);
                    if (lines.isEmpty()) {
                        sender.sendMessage(plain("empty — nothing is stored yet"));
                    }
                    for (int index = from; index < to; index++) {
                        sender.sendMessage(plain(lines.get(index)));
                    }
                    if (pages > 1) {
                        sender.sendMessage(field("pages:", "/vault files " + Math.max(1, selectedPage - 1)
                                + "  ·  next: /vault files " + Math.min(pages, selectedPage + 1)));
                    }
                    sender.sendMessage(plain(containerNumber - 1 + " container(s) · " + fileCount
                            + " total file(s)"));
                    sender.sendMessage(line());
                } catch (Exception e) {
                    sender.sendMessage(header("blomp files"));
                    sender.sendMessage(field("status:", "unavailable (" + safeMessage(e) + ")"));
                    sender.sendMessage(line());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private String byteText(Long bytes) {
        return bytes == null ? "size unavailable"
                : String.format(Locale.ROOT, "%.2f MB", bytes / 1_000_000.0);
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private void startManualBackup(CommandSender sender) {
        if (!coordinator.requestManualBackup()) {
            sender.sendMessage("[TinyVault] A backup is already running.");
            return;
        }
        Instant previousLastSuccess = coordinator.state().lastSuccess();
        sender.sendMessage(header("backup starting"));
        sender.sendMessage(plain("progress updates arrive as the work moves — hang tight."));
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
                    sender.sendMessage(header("backup complete"));
                    sender.sendMessage(plain("saved, verified by checksum and recorded on storage"));
                    sender.sendMessage(field("recorded:", fullTime.format(snapshot.lastSuccess())));
                    sender.sendMessage(plain("the previous backup still sits one step behind — nothing lost."));
                    sender.sendMessage(line());
                } else {
                    String raw = snapshot.failures().isEmpty() ? "" : snapshot.failures().get(0);
                    String reason = raw.contains(" | ") ? raw.substring(raw.indexOf(" | ") + 3) : raw;
                    sender.sendMessage(header("backup failed"));
                    sender.sendMessage(plain("what happened: " + friendlyReason(reason)));
                    sender.sendMessage(field("technical:", reason.isEmpty() ? "unknown" : reason));
                    sender.sendMessage(plain("your existing backups were NOT touched — retry comes automatically."));
                    sender.sendMessage(line());
                }
            }
        }.runTaskTimerAsynchronously(plugin, 0L, 40L);
    }

    /**
     * Turns storage error strings into something a person can actually act on.
     * Package-visible so the Discord webhook gets the same friendly wording.
     */
    static String friendlyReason(String reason) {
        if (reason.isBlank()) {
            return "no reason was recorded — check the server log";
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("403")) {
            return "Blomp's storage proxy refused the API call — their side, not your setup "
                    + "(their Swift API has had outages; the login itself works)";
        }
        if (lower.contains("connect timed out") || lower.contains("timed out after")
                || lower.contains("authentication") && lower.contains("timed out")) {
            return "Blomp's backend did not accept a connection in time — usually their API having "
                    + "a hiccup; try again shortly";
        }
        if (lower.contains("expected ") && lower.contains("bytes") || lower.contains("propagat")) {
            return "the storage service is still catching up with the fresh upload";
        }
        if (lower.contains("etag mismatch") || lower.contains("checksum")) {
            return "the uploaded archive did not arrive intact — the corrupted copy was removed "
                    + "and the next run will retry";
        }
        if (lower.contains("quota") || lower.contains("storage") && lower.contains("full")
                || lower.contains("exceed")) {
            return "Blomp reports no space remaining for this container";
        }
        if (lower.contains("connection") || lower.contains("timed out") || lower.contains("timeout")
                || lower.contains("unknownhost") || lower.contains("dns")) {
            return "the server could not reach the storage service (network hiccup)";
        }
        if (lower.contains("aborted") || lower.contains("interrupt")) {
            return "the upload was interrupted midway (server busy or connection dropped)";
        }
        if (lower.contains("denied") || lower.contains("credentials") || lower.contains("cannot set up session")
                || lower.contains("auth failed") || lower.contains("authorization failed")) {
            return "Blomp refused the request — check username and password in config.yml";
        }
        return "storage did not accept the backup";
    }

    /* ---- list ---- */

    private void showList(CommandSender sender) {
        BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
        LocalDate today = LocalDate.now(zone);
        sender.sendMessage(header("recorded backups · latest first"));
        int index = 1;
        int todays = 0;
        for (Instant timestamp : snapshot.successes()) { // newest first
            boolean isToday = LocalDate.ofInstant(timestamp, zone).equals(today);
            String when = isToday ? dayTime.format(timestamp) + "  (today)" : fullTime.format(timestamp);
            sender.sendMessage(plain(index + ".  " + when + markerFor(index)));
            if (isToday) {
                todays++;
            }
            index++;
        }
        if (index == 1) {
            sender.sendMessage(plain("nothing recorded yet"));
        }
        sender.sendMessage(plain("today: " + todays + " backup" + (todays == 1 ? "" : "s")));
        sender.sendMessage(line());
    }

    private void showPasses(CommandSender sender) {
        BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
        sender.sendMessage(header("passes · every recorded success"));
        if (snapshot.successes().isEmpty()) {
            sender.sendMessage(plain("clean slate — no passes yet"));
        }
        int index = 1;
        for (Instant timestamp : snapshot.successes()) {
            sender.sendMessage(plain(index + ".  " + fullTime.format(timestamp) + markerFor(index)));
            index++;
        }
        sender.sendMessage(line());
    }

    private void showFails(CommandSender sender) {
        BackupState.StateSnapshot snapshot = coordinator.state().snapshot();
        Instant cutoff = Instant.now().minus(Duration.ofHours(48));
        sender.sendMessage(header("failures · last 48 hours"));
        int index = 1;
        int recent = 0;
        for (String failure : snapshot.failures()) { // newest first
            Instant at = parseTime(failure);
            if (!at.isAfter(cutoff)) {
                continue;
            }
            recent++;
            String reason = failure.contains(" | ") ? failure.substring(failure.indexOf(" | ") + 3) : failure;
            sender.sendMessage(plain(index + ".  " + fullTime.format(at)));
            sender.sendMessage(field("what:", friendlyReason(reason)));
            index++;
        }
        if (index == 1) {
            sender.sendMessage(plain("none in the last 48 hours — nice and quiet"));
        }
        sender.sendMessage(line());
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
