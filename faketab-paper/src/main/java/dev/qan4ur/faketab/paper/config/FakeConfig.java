package dev.qan4ur.faketab.paper.config;

import dev.qan4ur.faketab.paper.core.NameGenerator;
import dev.qan4ur.faketab.paper.group.GroupDef;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Неизменяемый снимок config.yml. При /faketab reload создаётся новый.
 */
public record FakeConfig(
        double defaultPercent,
        int warnThreshold,
        int batchSize,
        int batchIntervalTicks,
        boolean percentTracksRealPlayers,
        boolean rotationEnabled,
        int rotateIntervalSeconds,
        int rotateJitterSeconds,
        double rotateFraction,
        int latencyMin,
        int latencyMax,
        GameMode gameMode,
        boolean showHat,
        AssignMode assignMode,
        String defaultGroup,
        boolean useLuckPerms,
        List<GroupDef> groups,
        List<String> baseNames,
        double plainNameChance,
        boolean pingEnabled,
        int pingSampleSize,
        boolean pingAddToCount
) {

    public enum AssignMode { DEFAULT, WEIGHTED }

    public static FakeConfig load(FileConfiguration c, Logger log) {
        GameMode gm;
        String gmRaw = c.getString("entry.gamemode", "SURVIVAL");
        try {
            gm = GameMode.valueOf(gmRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("entry.gamemode: неизвестный режим '" + gmRaw + "', использую SURVIVAL");
            gm = GameMode.SURVIVAL;
        }

        AssignMode mode;
        String modeRaw = c.getString("groups.assign-mode", "default");
        try {
            mode = AssignMode.valueOf(modeRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("groups.assign-mode: неизвестное значение '" + modeRaw + "', использую default");
            mode = AssignMode.DEFAULT;
        }

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
                        Math.max(0, g.getInt("weight", 0)),
                        g.getInt("order", 0)));
            }
        }
        String defaultGroup = c.getString("groups.default-group", "default");
        if (groups.stream().noneMatch(g -> g.name().equals(defaultGroup))) {
            log.warning("Группа '" + defaultGroup + "' не найдена в groups.list — добавляю её без префикса");
            groups.add(new GroupDef(defaultGroup, "<white><name>", 1, 0));
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


        int latMin = Math.max(0, c.getInt("entry.latency.min", 20));
        int latMax = Math.max(latMin, c.getInt("entry.latency.max", 120));

        return new FakeConfig(
                clamp(c.getDouble("default-percent", 50.0), 0, 100_000),
                Math.max(1, c.getInt("warn-threshold", 500)),
                Math.max(1, c.getInt("spawn.batch-size", 3)),
                Math.max(1, c.getInt("spawn.batch-interval-ticks", 20)),
                c.getBoolean("spawn.percent-tracks-real-players", true),
                c.getBoolean("rotation.enabled", true),
                Math.max(1, c.getInt("rotation.interval-seconds", 90)),
                Math.max(0, c.getInt("rotation.jitter-seconds", 30)),
                clamp(c.getDouble("rotation.fraction", 0.1), 0, 1),
                latMin,
                latMax,
                gm,
                c.getBoolean("entry.show-hat", true),
                mode,
                defaultGroup,
                c.getBoolean("groups.use-luckperms", true),
                List.copyOf(groups),
                List.copyOf(names),
                clamp(c.getDouble("names.plain-chance", 0.35), 0, 1),
                c.getBoolean("ping.enabled", true),
                Math.max(0, c.getInt("ping.sample-size", 12)),
                c.getBoolean("ping.add-to-count", false));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
