package dev.qan4ur.faketab.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.proxy.server.ServerPing.SamplePlayer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Plugin(id = "faketabpreview", name = "FakeTabPreview", version = "1.0.0",
        description = "Подставляет фейковый player sample backend-сервера в пинг прокси.")
public final class FakeTabVelocity {

    /** Последний снимок с backend. */
    private record Snapshot(List<SamplePlayer> sample, int fakeCount) {
        static final Snapshot EMPTY = new Snapshot(List.of(), 0);
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private volatile VelocityConfig cfg;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile boolean failureLogged;
    private ScheduledTask syncTask;

    @Inject
    public FakeTabVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        try {
            cfg = VelocityConfig.load(dataDir);
        } catch (IOException e) {
            logger.error("Не удалось загрузить config.properties, плагин выключен", e);
            return;
        }
        if (proxy.getServer(cfg.backend()).isEmpty()) {
            logger.warn("Backend '{}' не найден в velocity.toml — фейков в пинге не будет", cfg.backend());
        }
        syncTask = proxy.getScheduler().buildTask(this, this::sync)
                .repeat(cfg.syncIntervalSeconds(), TimeUnit.SECONDS)
                .schedule();
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (syncTask != null) {
            syncTask.cancel();
        }
    }

    private void sync() {
        VelocityConfig c = cfg;
        Optional<RegisteredServer> server = proxy.getServer(c.backend());
        if (server.isEmpty()) {
            snapshot = Snapshot.EMPTY;
            return;
        }
        RegisteredServer rs = server.get();
        rs.ping().whenComplete((ping, err) -> {
            if (err != null) {
                snapshot = Snapshot.EMPTY;
                if (!failureLogged) {
                    failureLogged = true;
                    logger.warn("Пинг backend '{}' не удался: {} (повторные ошибки не логируются)",
                            c.backend(), err.toString());
                }
                return;
            }
            failureLogged = false;
            snapshot = toSnapshot(ping, rs);
        });
    }

    private static Snapshot toSnapshot(ServerPing ping, RegisteredServer rs) {
        return ping.getPlayers().map(players -> {
            Set<UUID> real = new HashSet<>();
            rs.getPlayersConnected().forEach(p -> real.add(p.getUniqueId()));
            List<SamplePlayer> fakes = new ArrayList<>();
            for (SamplePlayer sp : players.getSample()) {
                if (!real.contains(sp.getId())) {
                    fakes.add(sp);
                }
            }
            int fakeCount = Math.max(0, players.getOnline() - rs.getPlayersConnected().size());
            return new Snapshot(List.copyOf(fakes), fakeCount);
        }).orElse(Snapshot.EMPTY);
    }

    @Subscribe
    public void onPing(ProxyPingEvent event) {
        VelocityConfig c = cfg;
        Snapshot snap = snapshot;
        if (c == null || snap.sample().isEmpty() && snap.fakeCount() == 0) {
            return;
        }
        InetSocketAddress addr = event.getConnection().getRemoteAddress();
        if (!c.allowAll() && (addr == null || addr.getAddress() == null
                || !c.allowedIps().contains(addr.getAddress().getHostAddress()))) {
            return;
        }
        ServerPing.Builder b = event.getPing().asBuilder();
        List<SamplePlayer> merged = new ArrayList<>(b.getSamplePlayers());
        Set<UUID> present = new HashSet<>();
        merged.forEach(sp -> present.add(sp.getId()));
        for (SamplePlayer sp : snap.sample()) {
            if (merged.size() >= c.sampleSize()) {
                break;
            }
            if (present.add(sp.getId())) {
                merged.add(sp);
            }
        }
        b.clearSamplePlayers().samplePlayers(merged);
        if (c.addToCount()) {
            b.onlinePlayers(b.getOnlinePlayers() + snap.fakeCount());
        }
        event.setPing(b.build());
    }
}
