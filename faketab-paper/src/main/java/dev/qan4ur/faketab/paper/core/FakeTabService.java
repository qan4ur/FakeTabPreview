package dev.qan4ur.faketab.paper.core;

import dev.qan4ur.faketab.paper.config.FakeConfig;
import dev.qan4ur.faketab.paper.group.GroupDef;
import dev.qan4ur.faketab.paper.group.GroupService;
import dev.qan4ur.faketab.paper.packet.PacketSender;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
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
    /** Очередь выходов по connection-period: ближайший выход — в голове. */
    private final PriorityQueue<FakeEntry> sessions =
            new PriorityQueue<>(Comparator.comparingLong(FakeEntry::leaveAtMillis));
    private final PacketSender sender;

    // volatile: читается из PingListener, который может работать не в главном потоке.
    private volatile FakeConfig cfg;
    private GroupService groups;
    private NameGenerator names;

    /** Как задаётся цель. */
    public enum Mode {
        /** Фиксированное число (set/add/remove/off). */
        FIXED,
        /** Процент слотов (on/fill). */
        PERCENT,
        /** Процент слотов из суточного расписания. */
        SCHEDULE
    }

    private int target;
    private Mode mode = Mode.FIXED;
    /** Текущий процент слотов в режимах PERCENT и SCHEDULE. */
    private double percent;
    /** Процент по графику без случайного разброса (для status). */
    private double scheduleBasePercent;
    private int batchSize;
    private int intervalTicks;
    private boolean rotationEnabled;

    private BukkitTask spawnTask;
    private BukkitTask rotationTask;
    private BukkitTask scheduleTask;
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
        if (mode == Mode.PERCENT) {
            recomputePercentTarget();
        } else if (mode == Mode.SCHEDULE) {
            restartScheduleTask();
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
        stopSchedule();
        this.mode = Mode.PERCENT;
        this.percent = pct;
        recomputePercentTarget();
    }

    public void setFixed(int count) {
        stopSchedule();
        this.mode = Mode.FIXED;
        setTarget(Math.max(0, count));
    }

    public void off(boolean instant) {
        setFixed(0);
        if (instant) {
            removeEntries(registry.view().stream().toList());
        }
    }

    /** Включает режим расписания: цель сразу пересчитывается и дальше раз в check-interval-seconds. */
    public void startSchedule() {
        this.mode = Mode.SCHEDULE;
        restartScheduleTask();
    }

    public int target() {
        return target;
    }

    public Mode mode() {
        return mode;
    }

    /** Текущий процент слотов; в режиме FIXED не имеет смысла. */
    public double percent() {
        return percent;
    }

    public double scheduleBasePercent() {
        return scheduleBasePercent;
    }

    /** Пересчёт цели в процентных режимах при входе/выходе реальных игроков. */
    public void onRealPlayersChanged() {
        if (mode != Mode.FIXED && cfg.percentTracksRealPlayers()) {
            recomputePercentTarget();
        }
    }

    // ---------------------------------------------------------------- расписание

    private void restartScheduleTask() {
        stopSchedule();
        long period = cfg.schedule().checkIntervalSeconds() * 20L;
        scheduleTick(); // цель сразу, а не через тик — чтобы команда показала актуальное значение
        scheduleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scheduleTick, period, period);
    }

    private void stopSchedule() {
        if (scheduleTask != null) {
            scheduleTask.cancel();
            scheduleTask = null;
        }
    }

    /** Раз в check-interval-seconds: процент по графику ± разброс → новая цель. */
    private void scheduleTick() {
        Schedule schedule = cfg.schedule();
        scheduleBasePercent = schedule.percentAt(ZonedDateTime.now());
        double jitter = schedule.jitterPercent();
        double noise = jitter == 0 ? 0 : ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        percent = Math.max(0, scheduleBasePercent + noise);
        recomputePercentTarget();
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

    /**
     * Один шаг: сначала «выходят» фейки с истёкшей сессией (не больше batch-size),
     * потом движение к цели — каждый из batch-size «слотов» срабатывает с шансом
     * join-chance / leave-chance, поэтому фейки появляются и уходят неравномерно.
     * Если делать нечего — выходит без аллокаций.
     */
    private void spawnStep() {
        long now = System.currentTimeMillis();
        List<FakeEntry> leaving = null;
        while (!sessions.isEmpty() && sessions.peek().leaveAtMillis() <= now
                && (leaving == null || leaving.size() < batchSize)) {
            FakeEntry e = sessions.poll();
            if (registry.contains(e.uuid())) { // удалённые другим путём пропускаем (ленивое удаление)
                if (leaving == null) {
                    leaving = new ArrayList<>();
                }
                leaving.add(e);
            }
        }
        if (leaving != null) {
            removeEntries(leaving);
        }

        int diff = target - registry.size();
        if (diff > 0) {
            int n = trials(Math.min(batchSize, diff), cfg.joinChance());
            if (n > 0) {
                addEntries(n);
            }
        } else if (diff < 0) {
            int n = trials(Math.min(batchSize, -diff), cfg.leaveChance());
            if (n > 0) {
                removeEntries(registry.pickRandom(n));
            }
        }
    }

    /** Сколько из k попыток удалось с вероятностью p. */
    private static int trials(int k, double p) {
        if (p >= 1) {
            return k;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int n = 0;
        for (int i = 0; i < k; i++) {
            if (rnd.nextDouble() < p) {
                n++;
            }
        }
        return n;
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
            register(e);
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
        long leaveAt = 0;
        if (cfg.sessionEnabled()) {
            long minMs = cfg.sessionMinMinutes() * 60_000L;
            long maxMs = cfg.sessionMaxMinutes() * 60_000L;
            leaveAt = System.currentTimeMillis() + (maxMs > minMs ? rnd.nextLong(minMs, maxMs + 1) : minMs);
        }
        return new FakeEntry(UUID.randomUUID(), name, group.name(), groups.render(group, name), group.order(),
                latency, leaveAt);
    }

    /** Добавляет запись в реестр и, если у неё есть срок сессии, — в очередь выходов. */
    private void register(FakeEntry e) {
        registry.add(e);
        if (e.leaveAtMillis() > 0) {
            sessions.add(e);
        }
    }

    private void addEntries(int count) {
        List<FakeEntry> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            FakeEntry e = createEntry();
            register(e);
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
        stopSchedule();
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        if (rotationTask != null) {
            rotationTask.cancel();
            rotationTask = null;
        }
        sessions.clear();
        List<FakeEntry> all = registry.clear();
        List<UUID> ids = new ArrayList<>(all.size());
        for (FakeEntry e : all) {
            ids.add(e.uuid());
        }
        sender.sendRemove(Bukkit.getOnlinePlayers(), ids);
    }
}
