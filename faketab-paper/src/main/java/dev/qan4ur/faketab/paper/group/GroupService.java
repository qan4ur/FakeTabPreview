package dev.qan4ur.faketab.paper.group;

import dev.qan4ur.faketab.paper.config.FakeConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Выбор группы для нового фейка и построение displayName.
 */
public final class GroupService {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private final GroupDef defaultGroup;
    private final double randomChance;
    /** Редкие группы с весом > 0. */
    private final List<GroupDef> random;
    private final int totalWeight;
    private final Map<String, String> lpPrefixes;

    public GroupService(FakeConfig cfg, Logger log) {
        this.defaultGroup = find(cfg, cfg.defaultGroup());
        this.randomChance = cfg.randomGroupChance();
        this.random = cfg.randomGroups().stream().map(n -> find(cfg, n)).filter(g -> g.weight() > 0).toList();
        this.totalWeight = random.stream().mapToInt(GroupDef::weight).sum();
        this.lpPrefixes = loadLuckPerms(cfg, log);
    }

    private static GroupDef find(FakeConfig cfg, String name) {
        return cfg.groups().stream().filter(g -> g.name().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, String> loadLuckPerms(FakeConfig cfg, Logger log) {
        if (!cfg.useLuckPerms() || !Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            return Map.of();
        }
        try {
            Map<String, String> map = LuckPermsHook.prefixes(
                    cfg.groups().stream().map(GroupDef::name).collect(Collectors.toList()));
            log.info("LuckPerms: префиксы взяты для групп " + map.keySet());
            return Map.copyOf(map);
        } catch (RuntimeException | LinkageError e) {
            log.log(Level.WARNING, "LuckPerms недоступен, использую форматы из конфига", e);
            return Map.of();
        }
    }

    /**
     * Группа для новой записи: default-group, и только с шансом random-groups.chance —
     * одна из random-groups (по weight).
     */
    public GroupDef pickForNew() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (totalWeight <= 0 || rnd.nextDouble() >= randomChance) {
            return defaultGroup;
        }
        int r = rnd.nextInt(totalWeight);
        for (GroupDef g : random) {
            r -= g.weight();
            if (r < 0) {
                return g;
            }
        }
        return defaultGroup;
    }

    public Component render(GroupDef group, String name) {
        String lp = lpPrefixes.get(group.name());
        if (lp != null) {
            if (lp.indexOf('&') >= 0 || lp.indexOf('§') >= 0) {
                return LEGACY.deserialize(lp.replace('§', '&') + name);
            }
            return MM.deserialize(lp + "<name>", Placeholder.unparsed("name", name));
        }
        return MM.deserialize(group.format(), Placeholder.unparsed("name", name));
    }
}
