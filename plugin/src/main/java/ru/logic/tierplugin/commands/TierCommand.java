package ru.logic.tierplugin.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.ModeRating;
import ru.logic.tierplugin.storage.Storage;
import ru.logic.tierplugin.text.Text;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * /tier [player] — shows per-gamemode tier info for self or another player (online or offline).
 */
public class TierCommand implements CommandExecutor {

    private final LogicTierPlugin plugin;

    public TierCommand(LogicTierPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        CompletableFuture<Optional<Storage.PlayerRef>> target;
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                Text.error(sender, "Использование: /tier " + Text.arg("игрок"));
                return true;
            }
            target = CompletableFuture.completedFuture(Optional.of(new Storage.PlayerRef(p.getUniqueId(), p.getName())));
        } else {
            target = PlayerResolver.resolve(plugin.getStorage(), args[0]);
        }

        target.thenCompose(ref -> ref.isEmpty()
                        ? CompletableFuture.completedFuture(null)
                        : plugin.getStorage().loadRatings(ref.get().uuid())
                                .thenApply(r -> Map.entry(ref.get(), r)))
                .whenComplete((res, err) -> plugin.sync(() -> {
                    if (err != null) {
                        Text.error(sender, "Ошибка базы данных — подробности в консоли сервера.");
                    } else if (res == null) {
                        Text.error(sender, "Игрок <name> ещё не заходил на сервер.", Text.plain("name", args[0]));
                    } else {
                        show(sender, res.getKey().name(), res.getValue());
                    }
                }));
        return true;
    }

    /** Profile card: one block per gamemode the player has fought in. */
    static void show(CommandSender sender, String name, Map<Gamemode, ModeRating> ratings) {
        Text.line(sender, Text.RULE);
        Text.line(sender, " <gradient:#FFB347:#FF5E3A><bold>Профиль</bold></gradient> <white><bold><name></bold></white>",
                Text.plain("name", name));
        if (ratings.isEmpty()) {
            Text.line(sender, " <gray>Рейтинговых боёв пока нет.</gray>");
        }
        for (ModeRating r : ratings.values()) {
            Text.line(sender, " <gray>" + Text.mode(r.getGamemode()) + "</gray> <dark_gray>»</dark_gray> "
                    + Text.tier(r.getTier(), r.isProvisional()));
            Text.line(sender, "   <gray>Рейтинг</gray> <white>" + Math.round(r.getElo()) + "</white> <dark_gray>±"
                    + Math.round(r.getRatingDeviation()) + "</dark_gray>"
                    + Text.DOT + "<gray>Навык</gray> <white>" + Text.num(r.getSkillScore(), 0) + "</white><dark_gray>/100</dark_gray>"
                    + Text.DOT + "<hover:show_text:'<gray>Растёт с каждым боем. Временный тир\nфиксируется после боёв калибровки.</gray>'>"
                    + "<gray>Уверенность</gray> <white>" + Text.percent(r.getConfidence()) + "</white></hover>");
            Text.line(sender, "   <gray>Боёв</gray> <white>" + r.getFights() + "</white>"
                    + Text.DOT + "<gray>Победы</gray> <green>" + r.getWins() + "</green>"
                    + Text.DOT + "<gray>Поражения</gray> <red>" + r.getLosses() + "</red>");
        }
        Text.line(sender, Text.RULE);
    }
}
