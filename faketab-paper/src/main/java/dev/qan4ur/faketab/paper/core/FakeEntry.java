package dev.qan4ur.faketab.paper.core;

import net.kyori.adventure.text.Component;

import java.util.UUID;

/**
 * Одна фейковая запись в табе. Не игрок, не сущность — только данные для пакета.
 *
 * @param uuid        случайный UUID записи
 * @param name        ник (3–16 символов, [A-Za-z0-9_])
 * @param group       имя группы из конфига
 * @param displayName префикс + ник
 * @param listOrder   приоритет в табе (больше — выше)
 * @param latency     пинг в мс
 * @param leaveAtMillis время «выхода» по connection-period (0 — без ограничения)
 */
public record FakeEntry(UUID uuid, String name, String group, Component displayName, int listOrder, int latency,
                        long leaveAtMillis) {
}
