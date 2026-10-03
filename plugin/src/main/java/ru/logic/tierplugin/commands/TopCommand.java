package ru.logic.tierplugin.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.storage.Storage;
import ru.logic.tierplugin.storage.TopStat;
import ru.logic.tierplugin.text.Text;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * /top [stat] [page] [mode] — leaderboards by Elo, skill, accuracy, combo, CPS, win rate…
 * with the sender's own place in each.
 */
public class TopCommand implements TabExecutor {

    private static final int PAGE_SIZE = 10;
    /** Valid Minecraft names; only these are safe to put inside a click action. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /** How each stat is called in chat, typed in commands and formatted. */
    private enum View {
        ELO(TopStat.ELO, "рейтинг", "Рейтинг", "elo"),
        SKILL(TopStat.SKILL, "навык", "Навык", "skill"),
        ACCURACY(TopStat.ACCURACY, "точность", "Точность", "accuracy"),
        COMBO(TopStat.COMBO, "комбо", "Среднее комбо", "combo"),
        BEST_COMBO(TopStat.BEST_COMBO, "серия", "Лучшая серия", "bestcombo"),
        CPS(TopStat.CPS, "кпс", "КПС", "cps"),
        WINRATE(TopStat.WINRATE, "винрейт", "Винрейт", "winrate"),
        WINS(TopStat.WINS, "победы", "Победы", "wins");

        final TopStat stat;
        final String key;
        final String title;
        final String alias;

        View(TopStat stat, String key, String title, String alias) {
            this.stat = stat;
            this.key = key;
            this.title = title;
            this.alias = alias;
        }

        String format(double v) {
            return switch (this) {
                case ELO, WINS, BEST_COMBO -> String.valueOf(Math.round(v));
                case SKILL -> Text.num(v, 0);
                case ACCURACY, WINRATE -> Text.percent(v);
                case COMBO, CPS -> Text.num(v, 1);
            };
        }

        /** Win counts and Elo are fair with any number of fights; averages need a few. */
        boolean needsMinFights() {
            return this != ELO && this != WINS;
        }

        static Optional<View> parse(String s) {
            return Arrays.stream(values())
                    .filter(v -> v.key.equalsIgnoreCase(s) || v.alias.equalsIgnoreCase(s))
                    .findFirst();
        }
    }

    private final LogicTierPlugin plugin;

    public TopCommand(LogicTierPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Gamemode gm = Gamemode.SWORD;
        View view = View.ELO;
        int page = 1;
        for (String arg : args) {
            Optional<View> v = View.parse(arg);
            Optional<Gamemode> mode = TierAdminCommand.parseMode(arg);
            if (v.isPresent()) {
                view = v.get();
            } else if (mode.isPresent()) {
                gm = mode.get();
            } else {
                try {
                    page = Math.max(1, Integer.parseInt(arg));
                } catch (NumberFormatException e) {
                    Text.error(sender, "Нет такого топа. Доступно: " + String.join(", ",
                            Arrays.stream(View.values()).map(x -> x.key).toList()) + ".");
                    return true;
                }
            }
        }

        Gamemode mode = gm;
        View shown = view;
        int pageNo = page;
        int minFights = view.needsMinFights() ? plugin.getTopMinFights() : 1;
        UUID me = sender instanceof Player p ? p.getUniqueId() : null;
        plugin.getStorage().ranking(mode, view.stat, minFights)
                .whenComplete((rows, err) -> plugin.sync(() -> {
                    if (err != null) {
                        Text.error(sender, "Ошибка базы данных — подробности в консоли сервера.");
                        return;
                    }
                    show(sender, mode, shown, pageNo, rows, me, minFights);
                }));
        return true;
    }

    private static void show(CommandSender sender, Gamemode gm, View view, int page,
                             List<Storage.StatRow> all, UUID me, int minFights) {
        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int pageNo = Math.min(page, pages);
        List<Storage.StatRow> rows = all.subList((pageNo - 1) * PAGE_SIZE, Math.min(all.size(), pageNo * PAGE_SIZE));

        Text.line(sender, Text.RULE);
        Text.line(sender, " <gradient:#FFB347:#FF5E3A><bold>Топ: " + view.title + "</bold></gradient>" + Text.DOT + "<gray>"
                + Text.mode(gm) + "</gray>" + Text.DOT + "<dark_gray>страница " + pageNo + " из " + pages + "</dark_gray>");
        Text.line(sender, " " + tabs(view));
        if (rows.isEmpty()) {
            Text.line(sender, " <gray>Пока никого нет"
                    + (view.needsMinFights() ? " — нужно сыграть хотя бы " + fights(minFights) : "") + ".</gray>");
        }
        for (Storage.StatRow r : rows) {
            String place = switch (r.place()) {
                case 1 -> "<#FFD24D><bold>1</bold></#FFD24D>";
                case 2 -> "<#D6D6D6><bold>2</bold></#D6D6D6>";
                case 3 -> "<#CD7F32><bold>3</bold></#CD7F32>";
                default -> "<gray>" + r.place() + "</gray>";
            };
            boolean self = r.uuid().equals(me);
            String name = self ? "<" + Text.ACCENT + "><bold><name></bold></" + Text.ACCENT + ">" : "<white><name></white>";
            if (SAFE_NAME.matcher(r.name()).matches()) {
                name = "<click:run_command:'/tier " + r.name() + "'><hover:show_text:'<gray>Открыть профиль</gray>'>"
                        + name + "</hover></click>";
            }
            Text.line(sender, " " + place + "<dark_gray>.</dark_gray> " + name + Text.DOT
                    + "<white>" + view.format(r.value()) + "</white> <dark_gray>(" + fights(r.fights()) + ")</dark_gray>",
                    Text.plain("name", r.name()));
        }

        if (me != null) {
            Optional<Storage.StatRow> mine = all.stream().filter(r -> r.uuid().equals(me)).findFirst();
            if (mine.isPresent()) {
                Text.line(sender, " <gray>Твоё место:</gray> <" + Text.ACCENT + "><bold>#" + mine.get().place()
                        + "</bold></" + Text.ACCENT + "> <gray>из</gray> <white>" + all.size() + "</white>"
                        + Text.DOT + "<white>" + view.format(mine.get().value()) + "</white>");
            } else {
                Text.line(sender, " <gray>Тебя пока нет в этом топе"
                        + (view.needsMinFights() ? " — нужно " + fights(minFights) + " в этом режиме" : "") + ".</gray>");
            }
        }
        if (view.needsMinFights()) {
            Text.line(sender, " <dark_gray>Учитываются бои, закончившиеся убийством, у игроков с "
                    + minFights + "+ такими боями.</dark_gray>");
        }
        Text.line(sender, Text.RULE);
    }

    /** "1 бой", "3 боя", "7 боёв". */
    private static String fights(int n) {
        int mod100 = n % 100, mod10 = n % 10;
        String word = (mod100 >= 11 && mod100 <= 14) ? "боёв"
                : mod10 == 1 ? "бой" : (mod10 >= 2 && mod10 <= 4) ? "боя" : "боёв";
        return n + " " + word;
    }

    /** Clickable switch between leaderboards; the current one is highlighted. */
    private static String tabs(View current) {
        StringBuilder sb = new StringBuilder();
        for (View v : View.values()) {
            if (!sb.isEmpty()) sb.append(" ");
            if (v == current) {
                sb.append("<").append(Text.ACCENT).append("><bold>[").append(v.title).append("]</bold></")
                  .append(Text.ACCENT).append(">");
            } else {
                sb.append("<click:run_command:'/top ").append(v.key).append("'><hover:show_text:'<gray>Показать топ</gray>'>")
                  .append("<gray>[").append(v.title).append("]</gray></hover></click>");
            }
        }
        return sb.toString();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        String prefix = args[args.length - 1].toLowerCase();
        return Arrays.stream(View.values()).map(v -> v.key).filter(k -> k.startsWith(prefix)).toList();
    }
}
