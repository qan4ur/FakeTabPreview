package dev.qan4ur.faketab.paper.core;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent.ListedPlayerInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Активные фейки. Изменяется только в главном потоке.
 * Для пинга (может вызываться не из главного потока) держит неизменяемый снимок.
 */
public final class FakeRegistry {

    private static final ListedPlayerInfo[] EMPTY = new ListedPlayerInfo[0];

    private final List<FakeEntry> entries = new ArrayList<>();
    private final Map<UUID, Integer> indexByUuid = new HashMap<>();
    private final Map<String, FakeEntry> byLowerName = new HashMap<>();
    private final Set<String> lowerNames = Collections.unmodifiableSet(byLowerName.keySet());

    private volatile ListedPlayerInfo[] pingSnapshot = EMPTY;

    public int size() {
        return entries.size();
    }

    public List<FakeEntry> view() {
        return Collections.unmodifiableList(entries);
    }

    public boolean contains(UUID uuid) {
        return indexByUuid.containsKey(uuid);
    }

    public boolean isNameTaken(String name) {
        return byLowerName.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public FakeEntry byName(String name) {
        return byLowerName.get(name.toLowerCase(Locale.ROOT));
    }

    public Set<String> lowerNames() {
        return lowerNames;
    }

    public void add(FakeEntry entry) {
        indexByUuid.put(entry.uuid(), entries.size());
        entries.add(entry);
        byLowerName.put(entry.name().toLowerCase(Locale.ROOT), entry);
    }

    /** Удаляет запись за O(1) (swap-remove). */
    public boolean remove(UUID uuid) {
        Integer idx = indexByUuid.remove(uuid);
        if (idx == null) {
            return false;
        }
        FakeEntry removed = entries.get(idx);
        int last = entries.size() - 1;
        if (idx != last) {
            FakeEntry moved = entries.get(last);
            entries.set(idx, moved);
            indexByUuid.put(moved.uuid(), idx);
        }
        entries.remove(last);
        byLowerName.remove(removed.name().toLowerCase(Locale.ROOT));
        return true;
    }

    /** Случайные различные записи, не больше count. */
    public List<FakeEntry> pickRandom(int count) {
        int n = Math.min(count, entries.size());
        List<FakeEntry> copy = new ArrayList<>(entries);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            Collections.swap(copy, i, i + rnd.nextInt(copy.size() - i));
        }
        return new ArrayList<>(copy.subList(0, n));
    }

    public List<FakeEntry> clear() {
        List<FakeEntry> all = new ArrayList<>(entries);
        entries.clear();
        indexByUuid.clear();
        byLowerName.clear();
        refreshPingSnapshot();
        return all;
    }

    /** Вызывать после каждой пачки изменений. */
    public void refreshPingSnapshot() {
        if (entries.isEmpty()) {
            pingSnapshot = EMPTY;
            return;
        }
        ListedPlayerInfo[] snap = new ListedPlayerInfo[entries.size()];
        for (int i = 0; i < snap.length; i++) {
            FakeEntry e = entries.get(i);
            snap[i] = new ListedPlayerInfo(e.name(), e.uuid());
        }
        pingSnapshot = snap;
    }

    public ListedPlayerInfo[] pingSnapshot() {
        return pingSnapshot;
    }
}
