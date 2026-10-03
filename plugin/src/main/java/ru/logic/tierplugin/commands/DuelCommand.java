package ru.logic.tierplugin.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.text.Text;

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
            Text.error(sender, "Эта команда только для игроков.");
            return true;
        }
        if (args.length == 0) {
            Text.send(player, "<gray>Использование:</gray> <white>/duel request|accept|decline " + Text.arg("игрок") + "</white>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "request" -> {
                if (args.length < 2) { Text.error(player, "Укажи игрока."); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { Text.error(player, "Игрок не найден."); return true; }
                // TODO: implement duel request queue
                Text.send(player, "<gray>Вызов на дуэль отправлен игроку</gray> <white><name></white><gray>. "
                        + "Дуэли скоро появятся — пока бой запускает администратор.</gray>", Text.plain("name", target.getName()));
                Text.send(target, "<white><name></white> <gray>вызывает тебя на дуэль! Дуэли скоро появятся.</gray>",
                        Text.plain("name", player.getName()));
            }
            case "accept", "decline" -> Text.send(player, "<gray>Дуэли скоро появятся.</gray>");
            default -> Text.error(player, "Неизвестный вариант. Доступно: request, accept, decline.");
        }
        return true;
    }
}
