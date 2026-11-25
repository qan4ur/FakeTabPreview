package dev.qan4ur.faketab.paper.command;

import dev.qan4ur.faketab.paper.FakeTabPlugin;
import dev.qan4ur.faketab.paper.core.FakeTabService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;
import java.util.Locale;

/**
 * /faketab — только для консоли сервера ({@link ConsoleCommandSender}).
 * Игроки, командные блоки и RCON получают отказ.
 */
public final class FakeTabCommand implements TabExecutor {

    private static final List<String> SUBS =
            List.of("on", "off", "fill", "set", "add", "remove", "rotate", "speed", "status", "reload");

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

    private static void status(CommandSender sender, FakeTabService s) {
        int max = Bukkit.getMaxPlayers();
        int current = s.registry().size();
        int real = Bukkit.getOnlinePlayers().size();
        long next = s.secondsToNextRotation();
        sender.sendMessage("FakeTab статус:");
        sender.sendMessage("  фейков: " + current + " / цель " + s.target()
                + (s.percent() != null ? " (режим " + fmt(s.percent()) + "%)" : " (фиксированная)"));
        sender.sendMessage("  реальных: " + real + ", слотов: " + max + ", заполнено: "
                + fmt(max == 0 ? 0 : (current + real) * 100.0 / max) + "%");
        sender.sendMessage("  ротация: " + (s.rotationEnabled() ? "вкл, следующая через " + next + " с" : "выкл"));
        sender.sendMessage("  скорость: " + s.batchSize() + " шт. / " + s.intervalTicks() + " тиков");
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage("faketab on | off [instant] | fill <percent> | set|add|remove <count> | "
                + "rotate [now|on|off] | speed <batch> <ticks> | status | reload");
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
