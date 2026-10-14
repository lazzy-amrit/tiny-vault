package dev.tinyvault;

import org.bukkit.plugin.java.JavaPlugin;

public final class TinyVaultPlugin extends JavaPlugin {
    private BackupCoordinator coordinator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            coordinator = BackupCoordinator.create(this);
            coordinator.start();
        } catch (Exception e) {
            getLogger().severe("Could not start backups: " + e.getMessage());
            coordinator = null;
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (coordinator != null) {
            coordinator.shutdown();
            coordinator = null;
        }
    }
}
