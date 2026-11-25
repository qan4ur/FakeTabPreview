package dev.qan4ur.faketab.paper.group;

/**
 * Группа (ранг) из конфига.
 *
 * @param name   ключ группы
 * @param format MiniMessage-формат с плейсхолдером {@code <name>}
 * @param weight вес среди groups.random-groups
 * @param order  listOrder в табе: больше — выше; отрицательный — ниже всех реальных игроков
 */
public record GroupDef(String name, String format, int weight, int order) {
}
