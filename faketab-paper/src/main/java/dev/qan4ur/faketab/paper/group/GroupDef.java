package dev.qan4ur.faketab.paper.group;

/**
 * Группа (ранг) из конфига.
 *
 * @param name   ключ группы
 * @param format MiniMessage-формат с плейсхолдером {@code <name>}
 * @param weight вес при случайном выборе (режим weighted)
 * @param order  listOrder в табе: больше — выше
 */
public record GroupDef(String name, String format, int weight, int order) {
}
