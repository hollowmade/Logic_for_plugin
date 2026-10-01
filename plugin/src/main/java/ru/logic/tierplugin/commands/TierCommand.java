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
                sender.sendMessage("§cUsage: /tier <player>");
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
                        sender.sendMessage("§cDatabase error, see console.");
                    } else if (res == null) {
                        sender.sendMessage("§cPlayer '" + args[0] + "' has never joined.");
                    } else {
                        show(sender, res.getKey().name(), res.getValue());
                    }
                }));
        return true;
    }

    static void show(CommandSender sender, String name, Map<Gamemode, ModeRating> ratings) {
        sender.sendMessage("§6=== §e" + name + "§6 ===");
        if (ratings.isEmpty()) {
            sender.sendMessage("§7No rated fights yet.");
            return;
        }
        for (ModeRating r : ratings.values()) {
            String tier = r.getTier() != null ? r.getTier().name() : "Unranked";
            sender.sendMessage(String.format("§e%s§7: §f%s%s §7| Elo §f%.0f §8(RD %.0f)§7 | Skill §f%.1f"
                            + " §7| Conf §f%.0f%% §7| §f%d§7 fights (§a%d§7/§c%d§7)",
                    r.getGamemode(), tier, r.isProvisional() ? " §7(provisional)" : "",
                    r.getElo(), r.getRatingDeviation(), r.getSkillScore(),
                    r.getConfidence() * 100, r.getFights(), r.getWins(), r.getLosses()));
        }
    }
}
