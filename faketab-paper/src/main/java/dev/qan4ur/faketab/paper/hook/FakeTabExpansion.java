package dev.qan4ur.faketab.paper.hook;

import dev.qan4ur.faketab.paper.core.FakeTabService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Плейсхолдеры PlaceholderAPI:
 * <ul>
 *   <li>%faketab_online% — реальные игроки + фейки</li>
 *   <li>%faketab_fakes%  — только фейки</li>
 *   <li>%faketab_real%   — только реальные игроки</li>
 *   <li>%faketab_max%    — слоты (max-players)</li>
 *   <li>%faketab_target% — цель по фейкам</li>
 * </ul>
 * Может вызываться не из главного потока (например, плагином TAB), поэтому число фейков
 * берётся из volatile-снимка реестра.
 */
final class FakeTabExpansion extends PlaceholderExpansion {

    private final Plugin plugin;
    private final FakeTabService service;

    /** Регистрирует расширение; возвращает его как Object, чтобы вызывающий не зависел от классов PAPI. */
    static Object registerFor(Plugin plugin, FakeTabService service) {
        FakeTabExpansion exp = new FakeTabExpansion(plugin, service);
        return exp.register() ? exp : null;
    }

    static void unregisterObject(Object expansion) {
        ((FakeTabExpansion) expansion).unregister();
    }

    FakeTabExpansion(Plugin plugin, FakeTabService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "faketab";
    }

    @Override
    public @NotNull String getAuthor() {
        return "qan4ur";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** Не выгружать при /papi reload — плагин сам управляет регистрацией. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        int fakes = service.registry().pingSnapshot().length;
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "online" -> Integer.toString(Bukkit.getOnlinePlayers().size() + fakes);
            case "fakes" -> Integer.toString(fakes);
            case "real" -> Integer.toString(Bukkit.getOnlinePlayers().size());
            case "max" -> Integer.toString(Bukkit.getMaxPlayers());
            case "target" -> Integer.toString(service.target());
            default -> null;
        };
    }
}
