package dev.qan4ur.faketab.paper;

import dev.qan4ur.faketab.paper.command.FakeTabCommand;
import dev.qan4ur.faketab.paper.config.FakeConfig;
import dev.qan4ur.faketab.paper.core.FakeTabService;
import dev.qan4ur.faketab.paper.listener.PlayerListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class FakeTabPlugin extends JavaPlugin {

    private FakeTabService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        service = new FakeTabService(this);
        service.apply(FakeConfig.load(getConfig(), getLogger()));
        getServer().getPluginManager().registerEvents(new PlayerListener(this, service), this);
        FakeTabCommand command = new FakeTabCommand(this);
        PluginCommand faketab = Objects.requireNonNull(getCommand("faketab"), "faketab in plugin.yml");
        faketab.setExecutor(command);
        faketab.setTabCompleter(command);
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
