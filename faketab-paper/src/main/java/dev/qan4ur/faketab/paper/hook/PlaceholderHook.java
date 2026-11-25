package dev.qan4ur.faketab.paper.hook;

import dev.qan4ur.faketab.paper.core.FakeTabService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

/**
 * Регистрация плейсхолдеров. Этот класс не ссылается на типы PlaceholderAPI напрямую —
 * они загружаются только внутри FakeTabExpansion и только если PlaceholderAPI установлен.
 */
public final class PlaceholderHook {

    private Object expansion;

    public void register(Plugin plugin, FakeTabService service) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        try {
            expansion = FakeTabExpansion.registerFor(plugin, service);
            if (expansion != null) {
                plugin.getLogger().info("PlaceholderAPI: зарегистрированы %faketab_online/fakes/real/max/target%");
            }
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Не удалось зарегистрировать плейсхолдеры PlaceholderAPI", e);
        }
    }

    public void unregister() {
        if (expansion == null) {
            return;
        }
        try {
            FakeTabExpansion.unregisterObject(expansion);
        } catch (RuntimeException | LinkageError ignored) {
            // PlaceholderAPI уже выгружен — нечего снимать
        }
        expansion = null;
    }
}
