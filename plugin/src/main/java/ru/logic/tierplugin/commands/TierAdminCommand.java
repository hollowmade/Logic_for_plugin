package ru.logic.tierplugin.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.PlayerProfile;
import ru.logic.tierplugin.core.Tier;
import ru.logic.tierplugin.storage.PlayerProfileRepository;

import java.util.Optional;

/**
 * /tieradmin set|reset|info <player> [tier]
 */
public class TierAdminCommand implements CommandExecutor {

    private final LogicTierPlugin plugin;
    private final PlayerProfileRepository repo;

    public TierAdminCommand(LogicTierPlugin plugin) {
        this.plugin = plugin;
        this.repo = new PlayerProfileRepository(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("tierplugin.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /tieradmin <set|reset|info> <player> [tier]");
            return true;
        }

        String sub = args[0].toLowerCase();
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found or offline.");
            return true;
        }

        Optional<PlayerProfile> opt = repo.findByUuid(target.getUniqueId());
        PlayerProfile profile = opt.orElseGet(() ->
                plugin.getTierEngine().newProfile(target.getUniqueId(), target.getName()));

        switch (sub) {
            case "set" -> {
                if (args.length < 3) { sender.sendMessage("§cProvide a tier name."); return true; }
                try {
                    Tier tier = Tier.valueOf(args[2].toUpperCase());
                    profile.setTier(tier);
                    profile.setProvisional(false);
                    repo.save(profile);
                    sender.sendMessage("§aSet " + target.getName() + "'s tier to " + tier.name());
                } catch (IllegalArgumentException e) {
                    sender.sendMessage("§cInvalid tier. Use: LT5, HT5, LT4, HT4, LT3, HT3, LT2, HT2, LT1, HT1");
                }
            }
            case "reset" -> {
                PlayerProfile fresh = plugin.getTierEngine().newProfile(target.getUniqueId(), target.getName());
                repo.save(fresh);
                sender.sendMessage("§aReset " + target.getName() + "'s profile.");
            }
            case "info" -> {
                sender.sendMessage(profile.toString());
            }
            default -> sender.sendMessage("§cUnknown sub-command.");
        }
        return true;
    }
}
