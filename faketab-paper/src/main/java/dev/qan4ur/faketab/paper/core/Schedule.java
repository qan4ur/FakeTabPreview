package dev.qan4ur.faketab.paper.core;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Суточный график заполненности: точки «минута суток → % слотов».
 * Между точками — линейная интерполяция, после последней точки график
 * плавно переходит в первую (через полночь).
 *
 * @param zone                 часовой пояс графика
 * @param points               точки, отсортированные по минуте суток (не пустой список)
 * @param jitterPercent        случайный разброс ± к проценту при каждом пересчёте
 * @param checkIntervalSeconds как часто пересчитывать цель
 */
public record Schedule(ZoneId zone, List<Point> points, double jitterPercent, int checkIntervalSeconds) {

    public static final int DAY_MINUTES = 24 * 60;

    /** @param minute минута суток 0..1439, @param percent % слотов */
    public record Point(int minute, double percent) {
    }

    public double percentAt(ZonedDateTime time) {
        ZonedDateTime local = time.withZoneSameInstant(zone);
        double minute = local.getHour() * 60 + local.getMinute() + local.getSecond() / 60.0;
        return percentAtMinute(minute);
    }

    public double percentAtMinute(double minute) {
        if (points.size() == 1) {
            return points.get(0).percent();
        }
        Point prev = points.get(points.size() - 1);
        double prevMinute = prev.minute() - DAY_MINUTES; // последняя точка «вчера»
        for (Point p : points) {
            if (minute < p.minute()) {
                return lerp(prevMinute, prev.percent(), p.minute(), p.percent(), minute);
            }
            prev = p;
            prevMinute = p.minute();
        }
        Point first = points.get(0);
        return lerp(prevMinute, prev.percent(), first.minute() + DAY_MINUTES, first.percent(), minute);
    }

    private static double lerp(double x0, double y0, double x1, double y1, double x) {
        if (x1 == x0) {
            return y1;
        }
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0);
    }
}
