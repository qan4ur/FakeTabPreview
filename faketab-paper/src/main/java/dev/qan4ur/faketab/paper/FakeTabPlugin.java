package dev.qan4ur.faketab.paper;

import dev.qan4ur.faketab.paper.config.FakeConfig;
import dev.qan4ur.faketab.paper.core.FakeTabService;
import dev.qan4ur.faketab.paper.listener.PlayerListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class FakeTabPlugin extends JavaPlugin {

    private FakeTabService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        service = new FakeTabService(this);
        service.apply(FakeConfig.load(getConfig(), getLogger()));
        getServer().getPluginManager().registerEvents(new PlayerListener(this, service), this);
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    /** Перечитать config.yml, не теряя текущих фейков. */
    public void reloadAndApply() {
        reloadConfig();
        service.apply(FakeConfig.load(getConfig(), getLogger()));
    }

    public FakeTabService service() {
        return service;
    }
}
