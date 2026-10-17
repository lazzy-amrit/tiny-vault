package dev.tinyvault;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs whole-server backups. Default mode: one backup per 24-hour deadline,
 * triggered by a cheap periodic check (every five minutes; the backup starts
 * as soon as the deadline is overdue). Extreme mode (`extreme: true`): the
 * timer is skipped entirely and a backup runs automatically right before the
 * server stops — gated to server sessions of at least the configured minimum
 * (default 30 minutes) so quick start/stop testing rounds do not trigger
 * backups. Only one backup ever runs at a time.
 */
final class BackupCoordinator {
    private static final long CHECK_PERIOD_TICKS = 20L * 60 * 5;
    private static final int DEFAULT_TIMER_HOURS = 24;
    private static final int DEFAULT_MIN_SESSION_MINUTES = 30;
    /** A success this fresh means a shutdown backup would only duplicate it. */
    private static final Duration RECENT_SUCCESS_WINDOW = Duration.ofMinutes(10);

    /** Progress phases surfaced to the /vault back command. */
    enum Phase { IDLE, SAVING, ARCHIVING, UPLOADING }

    /** What started the current backup, for logs and webhook messages. */
    enum Trigger {
        STARTUP("startup"), SCHEDULED("timer schedule"), MANUAL("/vault back command"),
        SHUTDOWN("server shutdown");

        final String label;

        Trigger(String label) {
            this.label = label;
        }
    }

    private final Plugin plugin;
    private final BlompSwiftStore store;
    private final ServerArchiver archiver;
    private final BackupState state;
    private final DiscordWebhook webhook;
    private final Duration backupInterval;
    private final ZoneId timezone;
    private final String namePattern;
    private final boolean extreme;
    private final Duration minSession;
    private final Path tempDir;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Phase phase = Phase.IDLE;
    private Instant sessionStart = Instant.now();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TinyVault-backup");
        thread.setDaemon(true);
        return thread;
    });
    private BukkitTask checkTask;

    private BackupCoordinator(Plugin plugin, BlompSwiftStore store, ServerArchiver archiver, BackupState state,
                              Duration backupInterval, ZoneId timezone, String namePattern,
                              boolean extreme, Duration minSession, DiscordWebhook webhook) {
        this.plugin = plugin;
        this.store = store;
        this.archiver = archiver;
        this.state = state;
        this.backupInterval = backupInterval;
        this.timezone = timezone;
        this.namePattern = namePattern;
        this.extreme = extreme;
        this.minSession = minSession;
        this.webhook = webhook;
        this.tempDir = plugin.getDataFolder().toPath().resolve("tmp");
    }

    static BackupCoordinator create(Plugin plugin) throws IOException {
        var config = plugin.getConfig();
        String username = config.getString("username", "").strip();
        String password = config.getString("password", "");
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("username and password must be set in plugins/TinyVault/config.yml");
        }
        String dir = config.getString("dir", "auto").strip();
        int limit = Math.max(1, Math.min(100, config.getInt("limit", 3)));
        String timerRaw = config.getString("timer", "24");
        Duration backupInterval = parseInterval(timerRaw);
        ZoneId timezone;
        try {
            timezone = ZoneId.of(config.getString("timezone", "Asia/Kolkata").strip());
        } catch (Exception e) {
            timezone = ZoneId.of("Asia/Kolkata");
        }
        // one universal naming method: time-format decides 24h vs 12h (AM/PM)
        boolean twelveHour = "12".equalsIgnoreCase(config.getString("time-format", "24").strip());
        String namePattern = twelveHour ? "yyyy-MM-dd_hh.mm-a" : "yyyy-MM-dd_HH.mm";
        boolean extreme = config.getBoolean("extreme", false);
        long minSessionMinutes = config.getLong("extreme-min-session-minutes", DEFAULT_MIN_SESSION_MINUTES);
        Duration minSession = Duration.ofMinutes(Math.max(1, minSessionMinutes));
        DiscordWebhook webhook = new DiscordWebhook(config.getString("webhook-url", ""),
                plugin.getLogger()::warning);

        BackupState state = new BackupState(plugin.getDataFolder().toPath().resolve("state.txt"));
        state.load();
        BlompSwiftStore store = new BlompSwiftStore(username, password, dir, limit,
            plugin.getLogger()::warning);
        ServerArchiver archiver = new ServerArchiver(serverRoot(plugin), plugin.getDataFolder().toPath());
        return new BackupCoordinator(plugin, store, archiver, state, backupInterval, timezone,
            namePattern, extreme, minSession, webhook);
    }

    /**
     * Timer values are `h` for hours and `m` for minutes ("15m", "4h",
     * "24h"); a plain number without suffix stays hours (backwards
     * compatible with earlier configs). Anything invalid falls back to 24h,
     * and the smallest allowed interval is one minute.
     */
    static Duration parseInterval(String raw) {
        if (raw == null) {
            return Duration.ofHours(DEFAULT_TIMER_HOURS);
        }
        String value = raw.strip().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("m")) {
                return Duration.ofMinutes(Math.max(1, Long.parseLong(value.substring(0, value.length() - 1).strip())));
            }
            if (value.endsWith("h")) {
                value = value.substring(0, value.length() - 1);
            }
            return Duration.ofHours(Math.max(1, Long.parseLong(value)));
        } catch (NumberFormatException e) {
            return Duration.ofHours(DEFAULT_TIMER_HOURS);
        }
    }

    /** Human interval text for logs and webhook notes: "24h" or "15m" style. */
    private String formatInterval(Duration interval) {
        if (interval.getSeconds() % 3600 == 0) {
            return (interval.getSeconds() / 3600) + "h";
        }
        return (interval.getSeconds() / 60) + "m";
    }

    /**
     * The server directory is the one containing the plugins folder. The data
     * folder path may be relative, so it is resolved against the working
     * directory before walking up; with an unexpected folder layout this falls
     * back to the working directory itself.
     */
    private static Path serverRoot(Plugin plugin) {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path pluginsDir = dataFolder.getParent();
        if (pluginsDir != null && pluginsDir.getParent() != null) {
            return pluginsDir.getParent().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    void start() {
        sessionStart = Instant.now();
        Instant last = state.lastSuccess();
        plugin.getLogger().info("Storage: Blomp Swift (" + (store.containerName()) + ")"
                + " — auth via " + store.describeConnection());
        if (extreme) {
            plugin.getLogger().info("Extreme mode ON: the " + formatInterval(backupInterval) + " timer is skipped — "
                    + "backups run automatically before the server stops (sessions of at least "
                    + minSession.toMinutes() + " minutes).");
            if (last == null) {
                plugin.getLogger().info("No previous backup exists at all; one starts now so nothing is lost.");
                startBackup(Trigger.STARTUP);
            }
            return;
        }
        Instant due = nextDue(last);
        plugin.getLogger().info(last == null
                ? "No previous backup found; a backup will start now"
                : "Last successful backup " + last + "; next backup due " + due
                        + " (" + formatInterval(backupInterval) + " timer)");
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::check, 20L, CHECK_PERIOD_TICKS);
    }

    BackupState state() {
        return state;
    }

    BlompSwiftStore store() {
        return store;
    }

    Instant nextDue() {
        return nextDue(state.lastSuccess());
    }

    Duration backupInterval() {
        return backupInterval;
    }

    boolean isExtreme() {
        return extreme;
    }

    long minSessionMinutes() {
        return minSession.toMinutes();
    }

    /**
     * Runs one backup immediately, recording it like any scheduled one (the
     * next deadline is measured from this backup's success). Returns false if
     * a backup is already running.
     */
    boolean requestManualBackup() {
        return startBackup(Trigger.MANUAL);
    }

    /** Shared launcher for every single-thread backup run. */
    private boolean startBackup(Trigger trigger) {
        if (running.get() || !running.compareAndSet(false, true)) {
            return false;
        }
        try {
            worker.execute(() -> runBackup(trigger));
            return true;
        } catch (RejectedExecutionException e) {
            running.set(false);
            return false;
        }
    }

    boolean backupRunning() {
        return running.get();
    }

    private Instant nextDue(Instant lastSuccess) {
        return lastSuccess == null ? Instant.EPOCH : lastSuccess.plus(backupInterval);
    }

    private void check() {
        if (running.get() || Instant.now().isBefore(nextDue(state.lastSuccess()))) {
            return;
        }
        startBackup(Trigger.SCHEDULED);
    }

    Phase phase() {
        return phase;
    }

    private void runBackup(Trigger trigger) {
        Instant startedAt = Instant.now();
        Path archive = tempDir.resolve("backup-" + startedAt.toEpochMilli() + ".zip.part");
        webhook.started(trigger, startedAt,
                (trigger == Trigger.SCHEDULED || trigger == Trigger.MANUAL) ? formatInterval(backupInterval) : null);
        try {
            phase = Phase.SAVING;
            awaitOnMainThread(() -> {
                for (World world : Bukkit.getWorlds()) {
                    world.setAutoSave(false);
                }
                for (World world : Bukkit.getWorlds()) {
                    world.save();
                }
                Bukkit.savePlayers();
            });
            try {
                phase = Phase.ARCHIVING;
                archiver.createArchive(archive);
            } finally {
                awaitOnMainThread(() -> {
                    for (World world : Bukkit.getWorlds()) {
                        world.setAutoSave(true);
                    }
                });
            }

            phase = Phase.UPLOADING;
            String storedKey = store.uploadBackup(archive, startedAt, timezone, namePattern);
            state.recordSuccess(startedAt);
            long archiveSize = Files.size(archive);
            plugin.getLogger().info(String.format(
                    "Backup completed in %ds (%.1f MiB) — stored as %s",
                    Duration.between(startedAt, Instant.now()).toSeconds(),
                    archiveSize / 1024.0 / 1024.0,
                    // never show the account email in logs/messages
                    storedKey.substring(storedKey.indexOf('/') + 1)));
            String nextText = extreme
                    ? "at the next shutdown"
                    : "in " + formatInterval(backupInterval);
            webhook.success(startedAt, archiveSize, Duration.between(startedAt, Instant.now()), nextText);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            plugin.getLogger().warning("Backup failed: " + reason);
            webhook.failed(Instant.now(), VaultCommand.friendlyReason(reason), reason);
            try {
                state.recordFailure(Instant.now(), reason);
            } catch (IOException stateError) {
                plugin.getLogger().warning("Could not update backup state file: " + stateError.getMessage());
            }
        } finally {
            phase = Phase.IDLE;
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
                // temp files live inside TinyVault's data folder and are excluded from future archives
            }
            running.set(false);
        }
    }

    /** Runs the given action on the main server thread and waits for it to finish. */
    private void awaitOnMainThread(Runnable action) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            // Shutdown backups run inline in onDisable, on the main thread
            // itself — the scheduler is already stopped at that point.
            action.run();
            return;
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                action.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw (cause instanceof Exception exception) ? exception : e;
        }
    }

    void shutdown() {
        if (checkTask != null) {
            checkTask.cancel();
        }
        if (running.get()) {
            // A backup is in flight; stopping now would interrupt it. Wait for
            // it to finish so the freshly-made backup is actually uploaded.
            plugin.getLogger().info("A backup is running — waiting for it to finish before the server stops...");
            long deadline = System.currentTimeMillis() + 30L * 60 * 1000;
            while (running.get() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (running.get()) {
                plugin.getLogger().warning("Backup still running when the wait ended; existing remote backups were preserved");
            }
        } else if (extreme && shutdownBackupDue()) {
            runShutdownBackup();
        }
        worker.shutdown();
        webhook.close();
        for (World world : Bukkit.getWorlds()) {
            if (!world.isAutoSave()) {
                world.setAutoSave(true);
            }
        }
    }

    /**
     * The extreme-mode trigger: before the server stops, back the server up
     * unless the session was too short (quick testing start/stop rounds) or a
     * success this fresh already covers the session.
     */
    private boolean shutdownBackupDue() {
        Duration session = Duration.between(sessionStart, Instant.now());
        if (session.compareTo(minSession) < 0) {
            plugin.getLogger().info("Extreme mode: session was under " + minSession.toMinutes()
                    + " minutes, skipping the shutdown backup.");
            return false;
        }
        Instant last = state.lastSuccess();
        if (last != null && Duration.between(last, Instant.now()).compareTo(RECENT_SUCCESS_WINDOW) < 0) {
            plugin.getLogger().info("Extreme mode: " + last + " already recorded a backup minutes ago, "
                    + "skipping the shutdown backup.");
            return false;
        }
        return true;
    }

    /** Runs the shutdown backup inline (onDisable is on the main thread) and blocks until it finishes. */
    private void runShutdownBackup() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        plugin.getLogger().info("Extreme mode: backing the server up before it stops — do not close the process.");
        runBackup(Trigger.SHUTDOWN);
    }
}
