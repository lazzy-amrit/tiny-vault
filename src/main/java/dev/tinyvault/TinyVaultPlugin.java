package dev.tinyvault;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class TinyVaultPlugin extends JavaPlugin {
    private BackupCoordinator coordinator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            coordinator = BackupCoordinator.create(this);
        } catch (IllegalStateException e) {
            getLogger().severe(e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (IOException e) {
            getLogger().severe("Could not read backup state: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        coordinator.start();
    }

    @Override
    public void onDisable() {
        if (coordinator != null) {
            coordinator.shutdown();
            coordinator = null;
        }
    }
}
