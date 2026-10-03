package ru.logic.tierplugin.commands;

import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.ModeRating;
import ru.logic.tierplugin.core.Simulator;
import ru.logic.tierplugin.core.Tier;
import ru.logic.tierplugin.storage.Storage;
import ru.logic.tierplugin.text.Text;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * /tieradmin set|reset|info|metrics|fight|simulate|stats|debug
 */
public class TierAdminCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("fight", "metrics", "info", "set", "reset", "simulate", "stats", "debug");

    /** Help entries: command to suggest on click, its arguments, description. */
    private static final String[][] HELP = {
            {"fight", Text.arg("игрок1") + " " + Text.arg("игрок2") + " [режим]", "начать рейтинговый бой"},
            {"metrics", Text.arg("игрок") + " [режим]", "метрики текущего боя и последних боёв"},
            {"info", Text.arg("игрок"), "профиль, UUID и выходы из боёв"},
            {"set", Text.arg("игрок") + " " + Text.arg("режим") + " " + Text.arg("тир"), "выставить тир вручную"},
            {"reset", Text.arg("игрок") + " [режим]", "сбросить рейтинг"},
            {"simulate", "[игроков] [дней]", "проверить формулы на виртуальных игроках"},
            {"stats", "", "сколько матчей в базе"},
            {"debug", "", "писать каждый взмах и удар в консоль"},
    };

    private final LogicTierPlugin plugin;

    public TierAdminCommand(LogicTierPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("tierplugin.admin")) {
            Text.error(sender, "Недостаточно прав.");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "set" -> set(sender, args);
            case "reset" -> reset(sender, args);
            case "info" -> info(sender, args);
            case "metrics" -> metrics(sender, args);
            case "fight" -> fight(sender, args);
            case "simulate" -> simulate(sender, args);
            case "stats" -> plugin.getStorage().countMatches().whenComplete((n, err) -> plugin.sync(() -> {
                if (err != null) dbError(sender);
                else Text.send(sender, "<gray>Матчей в базе:</gray> <white>" + n + "</white>");
            }));
            case "debug" -> {
                if (plugin.getMetricsListener().toggleDebug()) {
                    Text.send(sender, "<yellow>Отладка метрик включена:</yellow> <gray>взмахи и удары пишутся в консоль сервера.</gray>");
                } else {
                    Text.send(sender, "<gray>Отладка метрик выключена.</gray>");
                }
            }
            default -> help(sender);
        }
        return true;
    }

    private static void help(CommandSender sender) {
        Text.line(sender, Text.RULE);
        Text.line(sender, " " + Text.PREFIX + "<gray>команды администратора</gray>");
        for (String[] h : HELP) {
            String cmd = "/tieradmin " + h[0];
            Text.line(sender, " <click:suggest_command:'" + cmd + " '><hover:show_text:'<gray>Нажми, чтобы вставить в чат</gray>'>"
                    + "<" + Text.ACCENT + ">" + cmd + "</" + Text.ACCENT + ">"
                    + (h[1].isEmpty() ? "" : " <white>" + h[1] + "</white>")
                    + "</hover></click> <dark_gray>—</dark_gray> <gray>" + h[2] + "</gray>");
        }
        Text.line(sender, " <dark_gray>Режим: меч (sword). Тиры: LT5 … HT1.</dark_gray>");
        Text.line(sender, Text.RULE);
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 4) {
            usage(sender, "set " + Text.arg("игрок") + " " + Text.arg("режим") + " " + Text.arg("тир"));
            return;
        }
        Optional<Gamemode> gm = parseMode(args[2]);
        if (gm.isEmpty()) { badMode(sender); return; }
        Tier tier;
        try {
            tier = Tier.valueOf(args[3].toUpperCase());
        } catch (IllegalArgumentException e) {
            Text.error(sender, "Нет такого тира. Доступно: "
                    + Arrays.stream(Tier.values()).map(Tier::name).collect(Collectors.joining(", ")) + ".");
            return;
        }
        withPlayer(sender, args[1], ref -> plugin.getStorage().loadRatings(ref.uuid())
                .thenCompose(ratings -> {
                    ModeRating r = ratings.getOrDefault(gm.get(),
                            plugin.getRatingService().newRating(ref.uuid(), gm.get()));
                    r.setTier(tier);
                    r.setProvisional(false);
                    return plugin.getStorage().saveRating(r);
                })
                .whenComplete((v, err) -> plugin.sync(() -> {
                    if (err != null) { dbError(sender); return; }
                    Text.send(sender, "<gray>Тир игрока</gray> <white><name></white> <gray>(" + Text.mode(gm.get())
                            + ") —</gray> " + Text.tier(tier), Text.plain("name", ref.name()));
                })));
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length < 2) { usage(sender, "reset " + Text.arg("игрок") + " [режим]"); return; }
        Gamemode gm = null;
        if (args.length >= 3) {
            Optional<Gamemode> parsed = parseMode(args[2]);
            if (parsed.isEmpty()) { badMode(sender); return; }
            gm = parsed.get();
        }
        Gamemode mode = gm;
        withPlayer(sender, args[1], ref -> plugin.getStorage().deleteRatings(ref.uuid(), mode)
                .whenComplete((n, err) -> plugin.sync(() -> {
                    if (err != null) { dbError(sender); return; }
                    Text.send(sender, "<gray>Рейтинг игрока</gray> <white><name></white> <gray>сброшен ("
                            + (mode != null ? Text.mode(mode) : "все режимы") + "), удалено записей:</gray> <white>" + n + "</white>",
                            Text.plain("name", ref.name()));
                })));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) { usage(sender, "info " + Text.arg("игрок")); return; }
        var leave = plugin.getRatingService().getConfig().leave();
        withPlayer(sender, args[1], ref -> plugin.getStorage().profile(ref.uuid())
                .thenCombine(plugin.getStorage().leaveTimes(ref.uuid(), System.currentTimeMillis() - leave.windowMillis()),
                        Map::entry)
                .whenComplete((res, err) -> plugin.sync(() -> {
                    if (err != null) { dbError(sender); return; }
                    TierCommand.show(sender, ref.name(), res.getKey(), plugin.getRatingService().getConfig());
                    long blocked = leave.blockedUntil(res.getValue(), System.currentTimeMillis());
                    Text.line(sender, " <gray>Выходов из боя за " + leave.windowHours() + " ч:</gray> <white>"
                            + res.getValue().size() + "</white>"
                            + (blocked > 0 ? " <#FF6B6B>(бои заблокированы ещё " + minutesLeft(blocked) + " мин.)</#FF6B6B>" : ""));
                    // A UUID has no tag characters, so it can go into the template directly
                    Text.line(sender, " <dark_gray>UUID: <click:copy_to_clipboard:'" + ref.uuid()
                            + "'><hover:show_text:'Нажми, чтобы скопировать'>" + ref.uuid() + "</hover></click></dark_gray>");
                })));
    }

    private void metrics(CommandSender sender, String[] args) {
        if (args.length < 2) { usage(sender, "metrics " + Text.arg("игрок") + " [режим]"); return; }
        Optional<Gamemode> parsed = args.length >= 3 ? parseMode(args[2]) : Optional.of(Gamemode.SWORD);
        if (parsed.isEmpty()) { badMode(sender); return; }
        Gamemode gm = parsed.get();
        int limit = plugin.getMetricsSummaryFights();

        withPlayer(sender, args[1], ref -> plugin.getStorage().metricsSummary(ref.uuid(), gm, limit)
                .whenComplete((summary, err) -> plugin.sync(() -> {
                    if (err != null) { dbError(sender); return; }
                    Text.line(sender, Text.RULE);
                    Text.line(sender, " <gradient:#FFB347:#FF5E3A><bold>Метрики</bold></gradient> <white><bold><name></bold></white>"
                            + Text.DOT + "<gray>" + Text.mode(gm) + "</gray>", Text.plain("name", ref.name()));
                    plugin.getFightManager().fightOf(ref.uuid()).ifPresent(f -> Text.line(sender,
                            " <yellow>Сейчас в бою:</yellow> <m>",
                            Text.comp("m", Text.metrics(plugin.getMetricsTracker().snapshot(f).get(ref.uuid())))));
                    if (summary.isEmpty()) {
                        Text.line(sender, " <gray>Боёв в этом режиме пока нет.</gray>");
                    } else {
                        Storage.MetricsSummary m = summary.get();
                        Text.line(sender, " <gray>Последние</gray> <white>" + m.fights() + "</white> <gray>боёв, побед:</gray> <green>"
                                + m.wins() + "</green>");
                        Text.line(sender, "   <gray>Точность</gray> <white>" + (m.accuracy() < 0 ? "—" : Text.num(m.accuracy(), 0) + "%")
                                + "</white>" + Text.DOT + "<gray>Урон за бой</gray> <white>" + Text.num(m.avgDamageDealt(), 1)
                                + "</white><dark_gray>/</dark_gray><white>" + Text.num(m.avgDamageTaken(), 1) + "</white>"
                                + Text.DOT + "<gray>КПС</gray> <white>" + Text.num(m.avgCps(), 1) + "</white> <dark_gray>(пик "
                                + m.peakCps() + ")</dark_gray>");
                        Text.line(sender, "   <gray>Комбо: лучшее</gray> <white>" + m.bestCombo() + "</white><gray>, среднее</gray> <white>"
                                + Text.num(m.avgCombo(), 1) + "</white><gray>, медиана</gray> <white>" + Text.num(m.avgMedianCombo(), 1)
                                + "</white>" + Text.DOT + "<gray>Навык за бой</gray> <white>" + Text.num(m.avgFightSkill(), 0) + "</white>");
                    }
                    Text.line(sender, Text.RULE);
                })));
    }

    private void fight(CommandSender sender, String[] args) {
        if (args.length < 3) { usage(sender, "fight " + Text.arg("игрок1") + " " + Text.arg("игрок2") + " [режим]"); return; }
        Player a = Bukkit.getPlayerExact(args[1]);
        Player b = Bukkit.getPlayerExact(args[2]);
        if (a == null || b == null) { Text.error(sender, "Оба игрока должны быть онлайн."); return; }
        Gamemode gm = args.length >= 4 ? parseMode(args[3]).orElse(null) : Gamemode.SWORD;
        if (gm == null) { badMode(sender); return; }

        // Players who left too many fights recently may not start new ones
        var leave = plugin.getRatingService().getConfig().leave();
        long now = System.currentTimeMillis();
        long since = now - leave.windowMillis();
        plugin.getStorage().leaveTimes(a.getUniqueId(), since)
                .thenCombine(plugin.getStorage().leaveTimes(b.getUniqueId(), since),
                        (la, lb) -> new long[]{leave.blockedUntil(la, now), leave.blockedUntil(lb, now)})
                .whenComplete((blocked, err) -> plugin.sync(() -> {
                    if (err != null) { dbError(sender); return; }
                    for (int i = 0; i < 2; i++) {
                        if (blocked[i] > 0) {
                            Text.error(sender, "<white><name></white> слишком часто выходил из боёв — сможет драться через "
                                    + minutesLeft(blocked[i]) + " мин.", Text.plain("name", (i == 0 ? a : b).getName()));
                            return;
                        }
                    }
                    if (!a.isOnline() || !b.isOnline()) { Text.error(sender, "Оба игрока должны быть онлайн."); return; }
                    if (!plugin.getFightManager().startFight(a.getUniqueId(), b.getUniqueId(), gm)) {
                        Text.error(sender, "Нельзя начать бой: это один и тот же игрок или кто-то уже в бою.");
                        return;
                    }
                    String msg = "<gold><bold>⚔</bold></gold> <gray>Рейтинговый бой</gray>" + Text.DOT + "<gray>" + Text.mode(gm)
                            + "</gray>: <white><a></white> <dark_gray>vs</dark_gray> <white><b></white>"
                            + "<gray>. Победа — убийство соперника.</gray>";
                    TagResolver[] names = {Text.plain("a", a.getName()), Text.plain("b", b.getName())};
                    Text.send(a, msg, names);
                    Text.send(b, msg, names);
                    if (sender != a && sender != b) Text.send(sender, msg, names);
                }));
    }

    private void simulate(CommandSender sender, String[] args) {
        int players = parseInt(args, 1, 200);
        int days = parseInt(args, 2, 30);
        if (players < 2 || players > 2000 || days < 1 || days > 365) {
            Text.error(sender, "Игроков: от 2 до 2000, дней: от 1 до 365.");
            return;
        }
        Text.send(sender, "<gray>Симуляция:</gray> <white>" + players + "</white> <gray>игроков ×</gray> <white>" + days
                + "</white> <gray>дней…</gray>");
        var cfg = plugin.getRatingService().getConfig();
        // CPU-only work: run off the main thread, report back on it
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String report = Simulator.format(Simulator.run(cfg, players, days, 6, System.nanoTime()))
                          + Simulator.format(Simulator.farm(cfg, 100));
            plugin.sync(() -> {
                Text.line(sender, Text.RULE);
                report.lines().forEach(l -> Text.line(sender, " <gray><l></gray>", Text.plain("l", l)));
                Text.line(sender, Text.RULE);
            });
        });
    }

    // ── Helpers ────────────────────────────────────────────────

    /** Resolves a name (online or offline) and runs {@code action} with it, or reports not found. */
    private void withPlayer(CommandSender sender, String name, Consumer<Storage.PlayerRef> action) {
        PlayerResolver.resolve(plugin.getStorage(), name).whenComplete((ref, err) -> {
            if (err != null) {
                plugin.sync(() -> dbError(sender));
            } else if (ref.isEmpty()) {
                plugin.sync(() -> Text.error(sender, "Игрок <white><name></white> ещё не заходил на сервер.",
                        Text.plain("name", name)));
            } else {
                action.accept(ref.get());
            }
        });
    }

    /** Accepts the English id ("sword") and the Russian name ("меч"). */
    static Optional<Gamemode> parseMode(String s) {
        for (Gamemode gm : Gamemode.values()) {
            if (Text.mode(gm).equalsIgnoreCase(s)) return Optional.of(gm);
        }
        return Gamemode.parse(s);
    }

    private static void badMode(CommandSender sender) {
        Text.error(sender, "Нет такого режима. Доступно: "
                + Arrays.stream(Gamemode.values()).map(g -> Text.mode(g).toLowerCase() + " (" + g.configKey() + ")")
                        .collect(Collectors.joining(", ")) + ".");
    }

    private static void usage(CommandSender sender, String args) {
        Text.error(sender, "<gray>Использование:</gray> <white>/tieradmin " + args + "</white>");
    }

    private static void dbError(CommandSender sender) {
        Text.error(sender, "Ошибка базы данных — подробности в консоли сервера.");
    }

    private static long minutesLeft(long until) {
        return Math.max(1, (until - System.currentTimeMillis() + 59_999) / 60_000);
    }

    private static int parseInt(String[] args, int idx, int def) {
        if (args.length <= idx) return def;
        try {
            return Integer.parseInt(args[idx]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return filter(SUBS, args[0]);
        String sub = args[0].toLowerCase();
        List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        List<String> modes = Arrays.stream(Gamemode.values()).map(Gamemode::configKey).toList();

        return switch (sub) {
            case "set" -> switch (args.length) {
                case 2 -> filter(players, args[1]);
                case 3 -> filter(modes, args[2]);
                case 4 -> filter(Arrays.stream(Tier.values()).map(Tier::name).toList(), args[3]);
                default -> List.of();
            };
            case "reset" -> args.length == 2 ? filter(players, args[1])
                          : args.length == 3 ? filter(modes, args[2]) : List.of();
            case "info" -> args.length == 2 ? filter(players, args[1]) : List.of();
            case "metrics" -> args.length == 2 ? filter(players, args[1])
                            : args.length == 3 ? filter(modes, args[2]) : List.of();
            case "fight" -> args.length <= 3 ? filter(players, args[args.length - 1])
                          : args.length == 4 ? filter(modes, args[3]) : List.of();
            default -> List.of();
        };
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(o -> o.toLowerCase().startsWith(prefix.toLowerCase())).toList();
    }
}
