package dev.qan4ur.faketab.paper.listener;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent.ListedPlayerInfo;
import dev.qan4ur.faketab.paper.config.FakeConfig;
import dev.qan4ur.faketab.paper.core.FakeTabService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Добавляет фейков в player sample (наведение на пинг) и, по флагу, в счётчик онлайна.
 * Событие может приходить не из главного потока — читаются только volatile-снимки.
 * Фейки показываются только адресам из ping.allowed-ips.
 */
public final class PingListener implements Listener {

    private final FakeTabService service;

    public PingListener(FakeTabService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPing(PaperServerListPingEvent event) {
        FakeConfig cfg = service.config();
        if (cfg == null || !cfg.pingEnabled() || !allowed(cfg, event.getAddress())) {
            return;
        }
        ListedPlayerInfo[] snap = service.registry().pingSnapshot();
        if (snap.length == 0) {
            return;
        }
        List<ListedPlayerInfo> sample = event.getListedPlayers();
        int free = Math.min(cfg.pingSampleSize() - sample.size(), snap.length);
        if (free > 0) {
            int start = ThreadLocalRandom.current().nextInt(snap.length);
            for (int i = 0; i < free; i++) {
                sample.add(snap[(start + i) % snap.length]);
            }
        }
        if (cfg.pingAddToCount()) {
            event.setNumPlayers(event.getNumPlayers() + snap.length);
        }
    }

    private static boolean allowed(FakeConfig cfg, InetAddress address) {
        return cfg.pingAllowAll()
                || (address != null && cfg.pingAllowedIps().contains(address.getHostAddress()));
    }
}
