package dev.qan4ur.faketab.paper.core;

import dev.qan4ur.faketab.paper.config.FakeConfig;
import dev.qan4ur.faketab.paper.group.GroupDef;
import dev.qan4ur.faketab.paper.group.GroupService;
import dev.qan4ur.faketab.paper.packet.PacketSender;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Состояние фейков и вся логика: цель, плавное появление, ротация.
 * Все методы — только из главного потока.
 */
public final class FakeTabService {

    private final Plugin plugin;
    private final Logger log;
    private final FakeRegistry registry = new FakeRegistry();
    private final PacketSender sender;

    private FakeConfig cfg;
    private GroupService groups;
    private NameGenerator names;

    private int target;
    /** Не null — цель задана процентом от слотов и пересчитывается. */
    private Double percent;
    private int batchSize;
    private int intervalTicks;
    private boolean rotationEnabled;

    private BukkitTask spawnTask;
    private BukkitTask rotationTask;
    private long nextRotationAtMillis;

    public FakeTabService(Plugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.sender = new PacketSender(log);
    }

    // ---------------------------------------------------------------- конфиг

    /** Первичная загрузка и reload: текущие фейки сохраняются. */
    public void apply(FakeConfig newCfg) {
        this.cfg = newCfg;
        this.groups = new GroupService(newCfg, log);
        this.names = new NameGenerator(newCfg.baseNames(), newCfg.plainNameChance());
        this.sender.configure(newCfg.gameMode(), newCfg.showHat());
        this.batchSize = newCfg.batchSize();
        this.intervalTicks = newCfg.batchIntervalTicks();
        this.rotationEnabled = newCfg.rotationEnabled();
        restartSpawnTask();
        rescheduleRotation();
        if (percent != null) {
            recomputePercentTarget();
        }
    }

    public FakeConfig config() {
        return cfg;
    }

    public FakeRegistry registry() {
        return registry;
    }

    // ---------------------------------------------------------------- цель

    public void setPercent(double pct) {
        this.percent = pct;
        recomputePercentTarget();
    }

    public void setFixed(int count) {
        this.percent = null;
        setTarget(Math.max(0, count));
    }

    public void off(boolean instant) {
        this.percent = null;
        setTarget(0);
        if (instant) {
            removeEntries(registry.view().stream().toList());
        }
    }

    public int target() {
        return target;
    }

    public Double percent() {
        return percent;
    }

    /** Пересчёт цели в процентном режиме при входе/выходе реальных игроков. */
    public void onRealPlayersChanged() {
        if (percent != null && cfg.percentTracksRealPlayers()) {
            recomputePercentTarget();
        }
    }

    private void recomputePercentTarget() {
        int max = Bukkit.getMaxPlayers();
        int real = Bukkit.getOnlinePlayers().size();
        setTarget((int) Math.max(0, Math.floor(max * percent / 100.0) - real));
    }

    private void setTarget(int newTarget) {
        if (newTarget > cfg.warnThreshold() && newTarget != target) {
            log.warning("Цель " + newTarget + " фейков выше warn-threshold (" + cfg.warnThreshold()
                    + "). Выполняю, но следите за нагрузкой на клиентов.");
        }
        this.target = newTarget;
    }

    // ---------------------------------------------------------------- скорость

    public void setSpeed(int batch, int ticks) {
        this.batchSize = Math.max(1, batch);
        this.intervalTicks = Math.max(1, ticks);
        restartSpawnTask();
    }

    public int batchSize() {
        return batchSize;
    }

    public int intervalTicks() {
        return intervalTicks;
    }

    private void restartSpawnTask() {
        if (spawnTask != null) {
            spawnTask.cancel();
        }
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnStep, intervalTicks, intervalTicks);
    }

    /** Один шаг к цели. Если цель достигнута — выходит без аллокаций. */
    private void spawnStep() {
        int diff = target - registry.size();
        if (diff > 0) {
            addEntries(Math.min(batchSize, diff));
        } else if (diff < 0) {
            removeEntries(registry.pickRandom(Math.min(batchSize, -diff)));
        }
    }

    // ---------------------------------------------------------------- ротация

    public void setRotationEnabled(boolean enabled) {
        this.rotationEnabled = enabled;
        rescheduleRotation();
    }

    public boolean rotationEnabled() {
        return rotationEnabled;
    }

    /** Секунд до следующей ротации, -1 если выключена. */
    public long secondsToNextRotation() {
        if (!rotationEnabled || rotationTask == null) {
            return -1;
        }
        return Math.max(0, (nextRotationAtMillis - System.currentTimeMillis()) / 1000);
    }

    private void rescheduleRotation() {
        if (rotationTask != null) {
            rotationTask.cancel();
            rotationTask = null;
        }
        if (!rotationEnabled) {
            return;
        }
        int jitter = cfg.rotateJitterSeconds();
        int seconds = cfg.rotateIntervalSeconds()
                + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextInt(-jitter, jitter + 1));
        long delayTicks = Math.max(20L, seconds * 20L);
        nextRotationAtMillis = System.currentTimeMillis() + delayTicks * 50L;
        rotationTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            rotationTask = null;
            rotateNow();
            rescheduleRotation();
        }, delayTicks);
    }

    /** Заменяет долю rotation.fraction фейков новыми. Возвращает число замен. */
    public int rotateNow() {
        int size = registry.size();
        if (size == 0) {
            return 0;
        }
        int k = Math.max(1, (int) Math.round(size * cfg.rotateFraction()));
        List<FakeEntry> old = registry.pickRandom(k);
        // Новые ники генерируются, пока старые ещё заняты, — без мгновенного повтора.
        List<FakeEntry> fresh = new ArrayList<>(old.size());
        for (int i = 0; i < old.size(); i++) {
            FakeEntry e = createEntry();
            registry.add(e);
            fresh.add(e);
        }
        List<UUID> oldIds = new ArrayList<>(old.size());
        for (FakeEntry e : old) {
            registry.remove(e.uuid());
            oldIds.add(e.uuid());
        }
        var online = Bukkit.getOnlinePlayers();
        sender.sendRemove(online, oldIds);
        sender.sendAdd(online, fresh);
        registry.refreshPingSnapshot();
        return old.size();
    }

    // ---------------------------------------------------------------- записи

    private FakeEntry createEntry() {
        String name = names.next(n -> registry.isNameTaken(n) || Bukkit.getPlayerExact(n) != null);
        GroupDef group = groups.pickForNew();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int latency = rnd.nextInt(cfg.latencyMin(), cfg.latencyMax() + 1);
        return new FakeEntry(UUID.randomUUID(), name, group.name(), groups.render(group, name), group.order(), latency);
    }

    private void addEntries(int count) {
        List<FakeEntry> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            FakeEntry e = createEntry();
            registry.add(e);
            batch.add(e);
        }
        sender.sendAdd(Bukkit.getOnlinePlayers(), batch);
        registry.refreshPingSnapshot();
    }

    private void removeEntries(List<FakeEntry> toRemove) {
        if (toRemove.isEmpty()) {
            return;
        }
        List<UUID> ids = new ArrayList<>(toRemove.size());
        for (FakeEntry e : toRemove) {
            if (registry.remove(e.uuid())) {
                ids.add(e.uuid());
            }
        }
        sender.sendRemove(Bukkit.getOnlinePlayers(), ids);
        registry.refreshPingSnapshot();
    }

    // ---------------------------------------------------------------- игроки

    /** Вошедшему игроку — все текущие фейки. Фейк с тем же ником, что у игрока, заменяется. */
    public void onJoin(Player player) {
        FakeEntry clash = registry.byName(player.getName());
        if (clash != null) {
            removeEntries(List.of(clash));
            addEntries(1);
        }
        sender.sendAdd(List.of(player), registry.view());
    }

    // ---------------------------------------------------------------- выключение

    public void shutdown() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        if (rotationTask != null) {
            rotationTask.cancel();
            rotationTask = null;
        }
        List<FakeEntry> all = registry.clear();
        List<UUID> ids = new ArrayList<>(all.size());
        for (FakeEntry e : all) {
            ids.add(e.uuid());
        }
        sender.sendRemove(Bukkit.getOnlinePlayers(), ids);
    }
}
