package ru.logic.tierplugin.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.PlayerProfile;
import ru.logic.tierplugin.storage.PlayerProfileRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * /tier [player] — shows tier info for self or another player.
 */
public class TierCommand implements CommandExecutor {

    private final LogicTierPlugin plugin;
    private final PlayerProfileRepository repo;

    public TierCommand(LogicTierPlugin plugin) {
        this.plugin = plugin;
        this.repo = new PlayerProfileRepository(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        UUID target;
        String targetName;

        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("§cUsage: /tier <player>");
                return true;
            }
            target = p.getUniqueId();
            targetName = p.getName();
        } else {
            Player found = Bukkit.getPlayerExact(args[0]);
            if (found == null) {
                sender.sendMessage("§cPlayer '" + args[0] + "' not found or offline.");
                return true;
            }
            target = found.getUniqueId();
            targetName = found.getName();
        }

        Optional<PlayerProfile> opt = repo.findByUuid(target);
        if (opt.isEmpty()) {
            sender.sendMessage("§eNo data found for §f" + targetName + "§e.");
            return true;
        }

        PlayerProfile p = opt.get();
        String tierDisplay = p.getTier() != null ? p.getTier().name() : "Unranked";
        String provisional = p.isProvisional() ? " §7(Provisional)" : "";

        sender.sendMessage("§6=== §e" + targetName + "§6 ===");
        sender.sendMessage("§7Tier: §f" + tierDisplay + provisional);
        sender.sendMessage("§7Elo: §f" + String.format("%.0f", p.getEloRating())
                + " §8(RD: " + String.format("%.0f", p.getRatingDeviation()) + ")");
        sender.sendMessage("§7Skill Score: §f" + String.format("%.1f", p.getSkillScore()));
        sender.sendMessage("§7Confidence: §f" + String.format("%.0f%%", p.getConfidence() * 100));
        sender.sendMessage("§7Fights: §f" + p.getTotalFights());
        return true;
    }
}
