package ru.logic.tierplugin.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.logic.tierplugin.LogicTierPlugin;

/**
 * /duel <request|accept|decline> [player]
 * Stub — full duel arena logic will be implemented in the next phase.
 */
public class DuelCommand implements CommandExecutor {

    private final LogicTierPlugin plugin;

    public DuelCommand(LogicTierPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§eUsage: /duel <request|accept|decline> [player]");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "request" -> {
                if (args.length < 2) { player.sendMessage("§cSpecify a player."); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { player.sendMessage("§cPlayer not found."); return true; }
                // TODO: implement duel request queue
                player.sendMessage("§eDuel request sent to §f" + target.getName() + "§e. (Coming soon)");
                target.sendMessage("§f" + player.getName() + "§e challenged you to a duel! Type §f/duel accept " + player.getName());
            }
            case "accept" -> player.sendMessage("§eDuel acceptance coming soon.");
            case "decline" -> player.sendMessage("§eDuel decline coming soon.");
            default -> player.sendMessage("§cUnknown option. Use: request, accept, decline");
        }
        return true;
    }
}
