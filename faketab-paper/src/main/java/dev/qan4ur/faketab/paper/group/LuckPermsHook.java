package dev.qan4ur.faketab.paper.group;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Изолирует классы LuckPerms: загружается только если плагин LuckPerms включён.
 */
final class LuckPermsHook {

    private LuckPermsHook() {
    }

    /** Префиксы существующих в LuckPerms групп ("" если префикса нет). */
    static Map<String, String> prefixes(Collection<String> groupNames) {
        LuckPerms lp = LuckPermsProvider.get();
        Map<String, String> result = new HashMap<>();
        for (String name : groupNames) {
            Group group = lp.getGroupManager().getGroup(name);
            if (group != null) {
                String prefix = group.getCachedData().getMetaData().getPrefix();
                result.put(name, prefix == null ? "" : prefix);
            }
        }
        return result;
    }
}
