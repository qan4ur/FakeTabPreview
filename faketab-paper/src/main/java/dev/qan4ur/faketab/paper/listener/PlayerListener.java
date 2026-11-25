package dev.qan4ur.faketab.paper.listener;

import dev.qan4ur.faketab.paper.core.FakeTabService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public final class PlayerListener implements Listener {

    private final Plugin plugin;
    private final FakeTabService service;

    public PlayerListener(Plugin plugin, FakeTabService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.onJoin(event.getPlayer());
        service.onRealPlayersChanged();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // В момент QuitEvent игрок ещё в списке онлайна — пересчёт на следующем тике.
        Bukkit.getScheduler().runTask(plugin, service::onRealPlayersChanged);
    }
}
