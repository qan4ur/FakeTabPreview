package dev.qan4ur.faketab.paper.config;

import dev.qan4ur.faketab.paper.core.NameGenerator;
import dev.qan4ur.faketab.paper.core.Schedule;
import dev.qan4ur.faketab.paper.group.GroupDef;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Неизменяемый снимок config.yml. При /faketab reload создаётся новый.
 */
public record FakeConfig(
        AutoStart autoStart,
        double defaultPercent,
        int warnThreshold,
        int batchSize,
        int batchIntervalTicks,
        boolean percentTracksRealPlayers,
        double joinChance,
        double leaveChance,
        boolean sessionEnabled,
        int sessionMinMinutes,
        int sessionMaxMinutes,
        Schedule schedule,
        boolean rotationEnabled,
        int rotateIntervalSeconds,
        int rotateJitterSeconds,
        double rotateFraction,
        int latencyMin,
        int latencyMax,
        GameMode gameMode,
        boolean showHat,
        String defaultGroup,
        double randomGroupChance,
        List<String> randomGroups,
        boolean useLuckPerms,
        List<GroupDef> groups,
        List<String> baseNames,
        double plainNameChance,
        boolean pingEnabled,
        int pingSampleSize,
        boolean pingAddToCount
) {

    /** Что делать сразу после запуска сервера. */
    public enum AutoStart { OFF, PERCENT, SCHEDULE }

    private static final Pattern HH_MM = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    public static FakeConfig load(FileConfiguration c, Logger log) {
        GameMode gm = parseEnum(GameMode.class, c.getString("entry.gamemode", "SURVIVAL"),
                GameMode.SURVIVAL, "entry.gamemode", log);
        // YAML 1.1 читает незакавыченное off как false — считаем это тем же "off".
        String autoRaw = c.getString("auto-start", "off");
        AutoStart autoStart = autoRaw.equalsIgnoreCase("false")
                ? AutoStart.OFF
                : parseEnum(AutoStart.class, autoRaw, AutoStart.OFF, "auto-start", log);

        List<GroupDef> groups = new ArrayList<>();
        ConfigurationSection gs = c.getConfigurationSection("groups.list");
        if (gs != null) {
            for (String key : gs.getKeys(false)) {
                ConfigurationSection g = gs.getConfigurationSection(key);
                if (g == null) {
                    continue;
                }
                groups.add(new GroupDef(
                        key,
                        g.getString("format", "<name>"),
                        Math.max(0, g.getInt("weight", 1)),
                        g.getInt("order", 0)));
            }
        }
        String defaultGroup = c.getString("groups.default-group", "default");
        if (groups.stream().noneMatch(g -> g.name().equals(defaultGroup))) {
            log.warning("Группа '" + defaultGroup + "' не найдена в groups.list — добавляю её без префикса");
            groups.add(new GroupDef(defaultGroup, "<white><name>", 1, -30));
        }

        // Редкие случайные группы: список YAML или строка "silver;platina".
        List<String> randomGroups = new ArrayList<>();
        List<String> rawRandom = c.isList("groups.random-groups.list")
                ? c.getStringList("groups.random-groups.list")
                : List.of(c.getString("groups.random-groups.list", "").split("[;,]"));
        for (String raw : rawRandom) {
            String name = raw.trim();
            if (name.isEmpty() || name.equals(defaultGroup) || randomGroups.contains(name)) {
                continue;
            }
            if (groups.stream().noneMatch(g -> g.name().equals(name))) {
                log.warning("random-groups: группы '" + name + "' нет в groups.list — добавляю без префикса");
                groups.add(new GroupDef(name, "<white><name>", 1, -20));
            }
            randomGroups.add(name);
        }

        Set<String> seen = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (String n : c.getStringList("names.base")) {
            if (!NameGenerator.VALID.matcher(n).matches()) {
                log.warning("names.base: пропускаю невалидный ник '" + n + "'");
            } else if (seen.add(n.toLowerCase(Locale.ROOT))) {
                names.add(n);
            }
        }

        int sessionMin = Math.max(1, c.getInt("connection-period.min-minutes", 5));
        int sessionMax = Math.max(sessionMin, c.getInt("connection-period.max-minutes", 25));

        int latMin = Math.max(0, c.getInt("entry.latency.min", 20));
        int latMax = Math.max(latMin, c.getInt("entry.latency.max", 120));

        return new FakeConfig(
                autoStart,
                clamp(c.getDouble("default-percent", 50.0), 0, 100_000),
                Math.max(1, c.getInt("warn-threshold", 500)),
                Math.max(1, c.getInt("spawn.batch-size", 2)),
                Math.max(1, c.getInt("spawn.batch-interval-ticks", 20)),
                c.getBoolean("spawn.percent-tracks-real-players", true),
                clamp(c.getDouble("spawn.join-chance", 0.35), 0.01, 1),
                clamp(c.getDouble("spawn.leave-chance", 0.35), 0.01, 1),
                c.getBoolean("connection-period.enabled", true),
                sessionMin,
                sessionMax,
                loadSchedule(c, log),
                c.getBoolean("rotation.enabled", false),
                Math.max(1, c.getInt("rotation.interval-seconds", 90)),
                Math.max(0, c.getInt("rotation.jitter-seconds", 30)),
                clamp(c.getDouble("rotation.fraction", 0.1), 0, 1),
                latMin,
                latMax,
                gm,
                c.getBoolean("entry.show-hat", true),
                defaultGroup,
                clamp(c.getDouble("groups.random-groups.chance", 0.08), 0, 1),
                List.copyOf(randomGroups),
                c.getBoolean("groups.use-luckperms", true),
                List.copyOf(groups),
                List.copyOf(names),
                clamp(c.getDouble("names.plain-chance", 0.35), 0, 1),
                c.getBoolean("ping.enabled", true),
                Math.max(0, c.getInt("ping.sample-size", 12)),
                c.getBoolean("ping.add-to-count", false));
    }

    private static Schedule loadSchedule(FileConfiguration c, Logger log) {
        String zoneRaw = c.getString("schedule.timezone", "system");
        ZoneId zone;
        try {
            zone = zoneRaw.equalsIgnoreCase("system") ? ZoneId.systemDefault() : ZoneId.of(zoneRaw);
        } catch (DateTimeException e) {
            log.warning("schedule.timezone: неизвестный пояс '" + zoneRaw + "', использую системный");
            zone = ZoneId.systemDefault();
        }

        List<Schedule.Point> points = new ArrayList<>();
        ConfigurationSection ps = c.getConfigurationSection("schedule.points");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                int minute = parseMinute(key);
                if (minute < 0) {
                    log.warning("schedule.points: пропускаю время '" + key + "' (нужно \"ЧЧ:ММ\" в кавычках)");
                    continue;
                }
                double pct = ps.getDouble(key, -1);
                if (pct < 0) {
                    log.warning("schedule.points: у '" + key + "' нет процента — пропускаю");
                    continue;
                }
                points.add(new Schedule.Point(minute, pct));
            }
        }
        if (points.isEmpty()) {
            log.warning("schedule.points пуст — расписание держит default-percent круглые сутки");
            points.add(new Schedule.Point(0, clamp(c.getDouble("default-percent", 50.0), 0, 100_000)));
        }
        points.sort(Comparator.comparingInt(Schedule.Point::minute));

        return new Schedule(zone, List.copyOf(points),
                clamp(c.getDouble("schedule.jitter-percent", 2.0), 0, 100),
                Math.max(5, c.getInt("schedule.check-interval-seconds", 60)));
    }

    /**
     * "ЧЧ:ММ" → минута суток. YAML 1.1 читает незакавыченное 21:30 как число 1290
     * (шестидесятеричная запись) — это и есть минута суток, поэтому принимаем и его.
     */
    static int parseMinute(String key) {
        Matcher m = HH_MM.matcher(key.trim());
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            return h < 24 && min < 60 ? h * 60 + min : -1;
        }
        try {
            int v = Integer.parseInt(key.trim());
            return v >= 0 && v < Schedule.DAY_MINUTES ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E def, String path, Logger log) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning(path + ": неизвестное значение '" + raw + "', использую " + def.name().toLowerCase(Locale.ROOT));
            return def;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
