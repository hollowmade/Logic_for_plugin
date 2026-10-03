package ru.logic.tierplugin.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.storage.Storage;
import ru.logic.tierplugin.text.Text;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * /top [mode] [page] — leaderboard by Elo, with the sender's own place.
 */
public class TopCommand implements TabExecutor {

    private static final int PAGE_SIZE = 10;
    /** Valid Minecraft names; only these are safe to put inside a click action. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private record Page(List<Storage.LeaderRow> rows, int total, Storage.ProfileEntry mine) {}

    private final LogicTierPlugin plugin;

    public TopCommand(LogicTierPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Gamemode gm = Gamemode.SWORD;
        int page = 1;
        for (String arg : args) {
            Optional<Gamemode> mode = TierAdminCommand.parseMode(arg);
            if (mode.isPresent()) {
                gm = mode.get();
            } else {
                try {
                    page = Math.max(1, Integer.parseInt(arg));
                } catch (NumberFormatException e) {
                    Text.error(sender, "Использование: /top [режим] [страница]");
                    return true;
                }
            }
        }

        Gamemode mode = gm;
        int pageNo = page;
        var storage = plugin.getStorage();
        UUID me = sender instanceof Player p ? p.getUniqueId() : null;
        CompletableFuture<Map<Gamemode, Storage.ProfileEntry>> mine =
                me != null ? storage.profile(me) : CompletableFuture.completedFuture(Map.of());
        storage.leaderboard(mode, (pageNo - 1) * PAGE_SIZE, PAGE_SIZE)
                .thenCombine(storage.rankedCount(mode), (rows, total) -> new Page(rows, total, null))
                .thenCombine(mine, (p, profile) -> new Page(p.rows(), p.total(), profile.get(mode)))
                .whenComplete((data, err) -> plugin.sync(() -> {
                    if (err != null) {
                        Text.error(sender, "Ошибка базы данных — подробности в консоли сервера.");
                        return;
                    }
                    show(sender, mode, pageNo, data, me);
                }));
        return true;
    }

    private static void show(CommandSender sender, Gamemode gm, int page, Page data, UUID me) {
        List<Storage.LeaderRow> rows = data.rows();
        int total = data.total();
        Storage.ProfileEntry mine = data.mine();
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        Text.line(sender, Text.RULE);
        Text.line(sender, " <gradient:#FFB347:#FF5E3A><bold>Топ игроков</bold></gradient>" + Text.DOT + "<gray>"
                + Text.mode(gm) + "</gray>" + Text.DOT + "<dark_gray>страница " + Math.min(page, pages) + " из " + pages + "</dark_gray>");
        if (rows.isEmpty()) {
            Text.line(sender, " <gray>" + (total == 0 ? "В этом режиме ещё никто не сыграл рейтинговых боёв."
                    : "На этой странице никого нет.") + "</gray>");
        }
        for (Storage.LeaderRow r : rows) {
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
            Text.line(sender, " " + place + "<dark_gray>.</dark_gray> " + name + " " + Text.tier(r.tier(), false) + (r.provisional() ? "<gray>?</gray>" : "")
                    + Text.DOT + "<white>" + Math.round(r.elo()) + "</white>"
                    + Text.DOT + "<green>" + r.wins() + "</green><dark_gray>/</dark_gray><red>" + r.losses() + "</red>",
                    Text.plain("name", r.name()));
        }
        if (mine != null && mine.placement() != null) {
            Text.line(sender, " <gray>Твоё место:</gray> <" + Text.ACCENT + "><bold>#" + mine.placement().place()
                    + "</bold></" + Text.ACCENT + "> <gray>из</gray> <white>" + mine.placement().total() + "</white>");
        } else if (me != null) {
            Text.line(sender, " <gray>Ты ещё не в топе — сыграй рейтинговый бой.</gray>");
        }
        Text.line(sender, " <dark_gray>? — временный тир (идут бои калибровки)</dark_gray>");
        Text.line(sender, Text.RULE);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Arrays.stream(Gamemode.values()).map(Gamemode::configKey)
                    .filter(m -> m.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
