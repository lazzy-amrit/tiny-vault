package dev.tinyvault;

import org.bukkit.plugin.java.JavaPlugin;

public final class TinyVaultPlugin extends JavaPlugin {
    private BackupCoordinator coordinator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            coordinator = BackupCoordinator.create(this);
        } catch (Exception e) {
            getLogger().severe("Could not start backups: " + e);
            getLogger().severe("Fix the problem described above in plugins/TinyVault/config.yml, then restart the server.");
            coordinator = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        coordinator.start();
        if (getCommand("vault") != null) {
            VaultCommand vaultCommand = new VaultCommand(this, coordinator);
            getCommand("vault").setExecutor(vaultCommand);
            getCommand("vault").setTabCompleter(vaultCommand);
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
