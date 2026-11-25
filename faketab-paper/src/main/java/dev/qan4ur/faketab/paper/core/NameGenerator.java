package dev.qan4ur.faketab.paper.core;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Генерирует уникальные валидные ники из пула базовых с вариациями-суффиксами.
 */
public final class NameGenerator {

    public static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private static final String[] SYLLABLES = {
            "ka", "ri", "to", "mo", "ne", "sa", "zu", "ky", "lo", "va", "dex", "ron", "lix", "max", "fer",
            "nik", "tor", "ash", "ely", "kai", "zen", "mir", "vor", "pix", "qu", "bo", "den", "gr", "sh"
    };

    private final List<String> bases;
    private final double plainChance;

    /**
     * @param bases       уже провалидированные базовые ники
     * @param plainChance шанс взять базовый ник без суффикса (если свободен)
     */
    public NameGenerator(List<String> bases, double plainChance) {
        this.bases = List.copyOf(bases);
        this.plainChance = plainChance;
    }

    public String next(Predicate<String> taken) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (!bases.isEmpty()) {
            for (int attempt = 0; attempt < 40; attempt++) {
                String base = bases.get(rnd.nextInt(bases.size()));
                String candidate = attempt == 0 && rnd.nextDouble() < plainChance ? base : decorate(base, rnd);
                if (VALID.matcher(candidate).matches() && !taken.test(candidate)) {
                    return candidate;
                }
            }
        }
        // Пул исчерпан или пуст — синтетические ники. Количество вариантов огромное, цикл конечен на практике.
        while (true) {
            String candidate = synthetic(rnd);
            if (VALID.matcher(candidate).matches() && !taken.test(candidate)) {
                return candidate;
            }
        }
    }

    private static String decorate(String base, ThreadLocalRandom rnd) {
        String result = switch (rnd.nextInt(7)) {
            case 0 -> base + rnd.nextInt(1, 100);
            case 1 -> base + rnd.nextInt(100, 10000);
            case 2 -> base + "_" + rnd.nextInt(1, 100);
            case 3 -> base + rnd.nextInt(2000, 2016);
            case 4 -> base + "_";
            case 5 -> "x" + base + "x";
            default -> rnd.nextBoolean() ? "The" + capitalize(base) : base + "YT";
        };
        return result.length() > 16 ? result.substring(0, 16) : result;
    }

    private static String synthetic(ThreadLocalRandom rnd) {
        StringBuilder sb = new StringBuilder();
        int parts = rnd.nextInt(2, 4);
        for (int i = 0; i < parts; i++) {
            String s = SYLLABLES[rnd.nextInt(SYLLABLES.length)];
            sb.append(i == 0 || rnd.nextBoolean() ? capitalize(s) : s);
        }
        if (rnd.nextBoolean()) {
            sb.append(rnd.nextInt(1, 1000));
        }
        return sb.length() > 16 ? sb.substring(0, 16) : sb.toString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
