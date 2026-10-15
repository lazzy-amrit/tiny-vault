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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one whole-server backup per 24-hour deadline. A cheap periodic check
 * (every five minutes) compares the clock against the deadline: while the
 * deadline is ahead it waits, once it is overdue the backup starts
 * immediately. Only one backup ever runs at a time.
 */
final class BackupCoordinator {
    private static final long CHECK_PERIOD_TICKS = 20L * 60 * 5;
    private static final int DEFAULT_TIMER_HOURS = 24;

    /** Progress phases surfaced to the /vault back command. */
    enum Phase { IDLE, SAVING, ARCHIVING, UPLOADING }

    private final Plugin plugin;
    private final S3BackupStore store;
    private final ServerArchiver archiver;
    private final BackupState state;
    private final Duration backupInterval;
    private final boolean fileLuMode;
    private final Path tempDir;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Phase phase = Phase.IDLE;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TinyVault-backup");
        thread.setDaemon(true);
        return thread;
    });
    private BukkitTask checkTask;

    private BackupCoordinator(Plugin plugin, S3BackupStore store, ServerArchiver archiver, BackupState state,
                              Duration backupInterval, boolean fileLuMode) {
        this.plugin = plugin;
        this.store = store;
        this.archiver = archiver;
        this.state = state;
        this.backupInterval = backupInterval;
        this.fileLuMode = fileLuMode;
        this.tempDir = plugin.getDataFolder().toPath().resolve("tmp");
    }

    static BackupCoordinator create(Plugin plugin) throws IOException {
        var config = plugin.getConfig();
        String bucket = config.getString("bucket", "").strip();
        String accessKey = config.getString("access-key", "").strip();
        String secretKey = config.getString("secret-key", "");
        if (bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("bucket, access-key and secret-key must be set in plugins/TinyVault/config.yml");
        }
        String endpoint = config.getString("endpoint", "https://ap.s5lu.com").strip();
        String region = config.getString("region", "global").strip();
        boolean pathStyle = config.getBoolean("path-style", true);
        String mode = config.getString("mode", "s3").strip();
        boolean fileLuMode = mode.equalsIgnoreCase("s5");
        String apiKey = config.getString("api-key", "");
        FileLuTrashPurger trashPurger = fileLuMode
                ? new FileLuTrashPurger(apiKey, message -> plugin.getLogger().warning(message))
                : new FileLuTrashPurger("", null);
        int timerHours = config.getInt("timer", DEFAULT_TIMER_HOURS);
        Duration backupInterval = Duration.ofHours(timerHours < 1 ? DEFAULT_TIMER_HOURS : timerHours);

        BackupState state = new BackupState(plugin.getDataFolder().toPath().resolve("state.txt"));
        state.load();
        S3BackupStore store = new S3BackupStore(endpoint, region, pathStyle, bucket, accessKey, secretKey,
                fileLuMode, trashPurger);
        ServerArchiver archiver = new ServerArchiver(serverRoot(plugin), plugin.getDataFolder().toPath());
        return new BackupCoordinator(plugin, store, archiver, state, backupInterval, fileLuMode);
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
        Instant last = state.lastSuccess();
        Instant due = nextDue(last);
        plugin.getLogger().info("Rotation mode: " + (fileLuMode ? "s5 (FileLu)" : "s3 (universal)"));
        plugin.getLogger().info(last == null
                ? "No previous backup found; a backup will start now"
                : "Last successful backup " + last + "; next backup due " + due);
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::check, 20L, CHECK_PERIOD_TICKS);
    }

    BackupState state() {
        return state;
    }

    S3BackupStore store() {
        return store;
    }

    Instant nextDue() {
        return nextDue(state.lastSuccess());
    }

    Duration backupInterval() {
        return backupInterval;
    }

    /**
     * Runs one backup immediately, recording it like any scheduled one (the
     * next deadline is measured from this backup's success). Returns false if
     * a backup is already running.
     */
    boolean requestManualBackup() {
        if (running.get() || !running.compareAndSet(false, true)) {
            return false;
        }
        try {
            worker.execute(this::runBackup);
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
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(this::runBackup);
        } catch (RejectedExecutionException e) {
            running.set(false);
        }
    }

    Phase phase() {
        return phase;
    }

    private void runBackup() {
        Instant startedAt = Instant.now();
        Path archive = tempDir.resolve("backup-" + startedAt.toEpochMilli() + ".zip.part");
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
            store.rotate(archive, Files.size(archive));
            state.recordSuccess(startedAt);
            plugin.getLogger().info(String.format(
                    "Backup completed in %ds (%.1f MiB) -> %scurrent.zip",
                    Duration.between(startedAt, Instant.now()).toSeconds(),
                    Files.size(archive) / 1024.0 / 1024.0,
                    S3BackupStore.PREFIX));
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            plugin.getLogger().warning("Backup failed: " + reason);
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
        worker.shutdown();
        if (running.get()) {
            plugin.getLogger().warning("Backup interrupted while stopping; existing remote backups were preserved");
        }
        for (World world : Bukkit.getWorlds()) {
            if (!world.isAutoSave()) {
                world.setAutoSave(true);
            }
        }
    }
}
