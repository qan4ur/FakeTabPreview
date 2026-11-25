package dev.qan4ur.faketab.paper.command;

import dev.qan4ur.faketab.paper.FakeTabPlugin;
import dev.qan4ur.faketab.paper.core.FakeEntry;
import dev.qan4ur.faketab.paper.core.FakeTabService;
import dev.qan4ur.faketab.paper.core.Schedule;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * /faketab — только для консоли сервера ({@link ConsoleCommandSender}).
 * Игроки, командные блоки и RCON получают отказ.
 */
public final class FakeTabCommand implements TabExecutor {

    private static final List<String> SUBS =
            List.of("on", "off", "fill", "schedule", "set", "add", "remove", "rotate", "speed", "status", "list",
                    "reload");

    private static final int LIST_PAGE = 20;
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final FakeTabPlugin plugin;

    public FakeTabCommand(FakeTabPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("Только консоль.");
            return true;
        }
        FakeTabService s = plugin.service();
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "on" -> {
                    s.setPercent(s.config().defaultPercent());
                    sender.sendMessage("FakeTab: включено, цель " + s.target()
                            + " (" + fmt(s.config().defaultPercent()) + "% слотов).");
                }
                case "off" -> {
                    boolean instant = args.length > 1 && args[1].equalsIgnoreCase("instant");
                    s.off(instant);
                    sender.sendMessage(instant ? "FakeTab: все фейки убраны мгновенно."
                            : "FakeTab: фейки плавно убираются.");
                }
                case "fill" -> {
                    double pct = parsePercent(arg(args, 1, "fill <percent>"));
                    s.setPercent(pct);
                    sender.sendMessage("FakeTab: цель " + s.target() + " (" + fmt(pct) + "% от "
                            + Bukkit.getMaxPlayers() + " минус " + Bukkit.getOnlinePlayers().size() + " реальных).");
                }
                case "schedule" -> schedule(sender, s, args);
                case "list" -> list(sender, s, args);
                case "set" -> {
                    s.setFixed(parseCount(arg(args, 1, "set <count>")));
                    sender.sendMessage("FakeTab: цель " + s.target() + ".");
                }
                case "add" -> {
                    s.setFixed(s.target() + parseCount(arg(args, 1, "add <count>")));
                    sender.sendMessage("FakeTab: цель " + s.target() + ".");
                }
                case "remove" -> {
                    s.setFixed(Math.max(0, s.target() - parseCount(arg(args, 1, "remove <count>"))));
                    sender.sendMessage("FakeTab: цель " + s.target() + ".");
                }
                case "rotate" -> rotate(sender, s, args);
                case "speed" -> {
                    int batch = parseCount(arg(args, 1, "speed <batch> <ticks>"));
                    int ticks = parseCount(arg(args, 2, "speed <batch> <ticks>"));
                    if (batch < 1 || ticks < 1) {
                        throw new IllegalArgumentException("batch и ticks должны быть >= 1");
                    }
                    s.setSpeed(batch, ticks);
                    sender.sendMessage("FakeTab: скорость " + batch + " шт. / " + ticks + " тиков.");
                }
                case "status" -> status(sender, s);
                case "reload" -> {
                    plugin.reloadAndApply();
                    sender.sendMessage("FakeTab: конфиг перечитан, текущие фейки сохранены ("
                            + s.registry().size() + ").");
                }
                default -> usage(sender);
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage("FakeTab: " + e.getMessage());
        }
        return true;
    }

    private static void rotate(CommandSender sender, FakeTabService s, String[] args) {
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (mode) {
            case "now" -> sender.sendMessage("FakeTab: заменено " + s.rotateNow() + " записей.");
            case "on" -> {
                s.setRotationEnabled(true);
                sender.sendMessage("FakeTab: ротация включена, следующая через "
                        + s.secondsToNextRotation() + " с.");
            }
            case "off" -> {
                s.setRotationEnabled(false);
                sender.sendMessage("FakeTab: ротация выключена.");
            }
            default -> sender.sendMessage("FakeTab: ротация " + (s.rotationEnabled() ? "включена" : "выключена")
                    + ". Использование: faketab rotate <now|on|off>");
        }
    }

    private static void schedule(CommandSender sender, FakeTabService s, String[] args) {
        Schedule sch = s.config().schedule();
        if (args.length > 1 && args[1].equalsIgnoreCase("preview")) {
            int max = Bukkit.getMaxPlayers();
            sender.sendMessage("FakeTab расписание (" + sch.zone() + ", разброс ±" + fmt(sch.jitterPercent())
                    + "%), % слотов и фейков при " + max + " слотах без реальных игроков:");
            for (int h = 0; h < 24; h += 2) {
                StringBuilder line = new StringBuilder("  ");
                for (int k = h; k < h + 2; k++) {
                    double pct = sch.percentAtMinute(k * 60);
                    line.append(String.format(Locale.ROOT, "%02d:00  %5.1f%%  ~%-5d", k, pct,
                            (int) Math.floor(max * pct / 100.0)));
                }
                sender.sendMessage(line.toString());
            }
            return;
        }
        s.startSchedule();
        sender.sendMessage("FakeTab: режим расписания, сейчас " + fmt(s.scheduleBasePercent())
                + "% слотов по графику (" + sch.zone() + "), цель " + s.target()
                + ". Пересчёт раз в " + sch.checkIntervalSeconds() + " с.");
    }

    /** Сводка по группам и постраничный список фейков — чтобы проверить, что видит таб. */
    private static void list(CommandSender sender, FakeTabService s, String[] args) {
        List<FakeEntry> all = s.registry().view();
        if (all.isEmpty()) {
            sender.sendMessage("FakeTab: фейков нет.");
            return;
        }
        Map<String, Integer> byGroup = new TreeMap<>();
        for (FakeEntry e : all) {
            byGroup.merge(e.group(), 1, Integer::sum);
        }
        StringBuilder summary = new StringBuilder("FakeTab: фейков " + all.size() + " —");
        byGroup.forEach((g, n) -> summary.append(' ').append(g).append(' ').append(n)
                .append(String.format(Locale.ROOT, " (%.1f%%)", n * 100.0 / all.size())).append(';'));
        sender.sendMessage(summary.toString());

        List<FakeEntry> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt(FakeEntry::listOrder).reversed()
                .thenComparing(FakeEntry::name, String.CASE_INSENSITIVE_ORDER));
        int pages = (sorted.size() + LIST_PAGE - 1) / LIST_PAGE;
        int page = args.length > 1 ? Math.max(1, Math.min(pages, parseCount(args[1]))) : 1;
        sender.sendMessage("  страница " + page + "/" + pages + " (в порядке таба):");
        long now = System.currentTimeMillis();
        for (FakeEntry e : sorted.subList((page - 1) * LIST_PAGE, Math.min(sorted.size(), page * LIST_PAGE))) {
            String left = e.leaveAtMillis() > 0
                    ? ((e.leaveAtMillis() - now) / 60_000 + 1) + " мин"
                    : "без срока";
            sender.sendMessage(String.format(Locale.ROOT, "  %-16s %-9s order %4d  ping %3d  выход через %-8s | %s",
                    e.name(), e.group(), e.listOrder(), e.latency(), left, PLAIN.serialize(e.displayName())));
        }
    }

    private static void status(CommandSender sender, FakeTabService s) {
        int max = Bukkit.getMaxPlayers();
        int current = s.registry().size();
        int real = Bukkit.getOnlinePlayers().size();
        long next = s.secondsToNextRotation();
        String mode = switch (s.mode()) {
            case FIXED -> "фиксированная";
            case PERCENT -> "режим " + fmt(s.percent()) + "%";
            case SCHEDULE -> "расписание: " + fmt(s.scheduleBasePercent()) + "% по графику, с разбросом "
                    + fmt(s.percent()) + "%";
        };
        sender.sendMessage("FakeTab статус:");
        sender.sendMessage("  фейков: " + current + " / цель " + s.target() + " (" + mode + ")");
        sender.sendMessage("  реальных: " + real + ", слотов: " + max + ", заполнено: "
                + fmt(max == 0 ? 0 : (current + real) * 100.0 / max) + "%");
        sender.sendMessage("  ротация: " + (s.rotationEnabled() ? "вкл, следующая через " + next + " с" : "выкл"));
        sender.sendMessage("  скорость: " + s.batchSize() + " шт. / " + s.intervalTicks() + " тиков");
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage("faketab on | off [instant] | fill <percent> | schedule [preview] | set|add|remove <count> | "
                + "rotate [now|on|off] | speed <batch> <ticks> | status | list [page] | reload");
    }

    private static String arg(String[] args, int i, String usage) {
        if (args.length <= i) {
            throw new IllegalArgumentException("использование: faketab " + usage);
        }
        return args[i];
    }

    private static double parsePercent(String raw) {
        try {
            double v = Double.parseDouble(raw.replace(',', '.').replace("%", ""));
            if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("неверный процент: " + raw);
        }
    }

    private static int parseCount(String raw) {
        try {
            int v = Integer.parseInt(raw);
            if (v < 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("неверное число: " + raw);
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBS, args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "off" -> filter(List.of("instant"), args[1]);
                case "rotate" -> filter(List.of("now", "on", "off"), args[1]);
                case "schedule" -> filter(List.of("preview"), args[1]);
                case "fill" -> filter(List.of("25", "50", "75", "100"), args[1]);
                case "set", "add", "remove" -> filter(List.of("10", "50", "100"), args[1]);
                case "speed" -> filter(List.of("1", "3", "5", "10"), args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("speed")) {
            return filter(List.of("5", "10", "20", "40"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(p)).toList();
    }
}
