package ru.logic.tierplugin.commands;

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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * /tieradmin set|reset|info|fight|simulate|stats
 */
public class TierAdminCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("set", "reset", "info", "fight", "simulate", "stats");
    private static final String USAGE = String.join("\n",
            "§e/tieradmin set <player> <mode> <tier>",
            "§e/tieradmin reset <player> [mode]",
            "§e/tieradmin info <player>",
            "§e/tieradmin fight <player1> <player2> [mode] §7— start a tracked fight",
            "§e/tieradmin simulate [players] [days] §7— offline formula check",
            "§e/tieradmin stats");

    private final LogicTierPlugin plugin;

    public TierAdminCommand(LogicTierPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("tierplugin.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(USAGE);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "set" -> set(sender, args);
            case "reset" -> reset(sender, args);
            case "info" -> info(sender, args);
            case "fight" -> fight(sender, args);
            case "simulate" -> simulate(sender, args);
            case "stats" -> plugin.getStorage().countMatches().whenComplete((n, err) -> plugin.sync(() ->
                    sender.sendMessage(err != null ? "§cDatabase error." : "§7Matches stored: §f" + n)));
            default -> sender.sendMessage(USAGE);
        }
        return true;
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length < 4) { sender.sendMessage("§cUsage: /tieradmin set <player> <mode> <tier>"); return; }
        Optional<Gamemode> gm = Gamemode.parse(args[2]);
        if (gm.isEmpty()) { sender.sendMessage("§cModes: " + Arrays.toString(Gamemode.values())); return; }
        Tier tier;
        try {
            tier = Tier.valueOf(args[3].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cTiers: " + Arrays.toString(Tier.values()));
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
                .whenComplete((v, err) -> plugin.sync(() -> sender.sendMessage(err != null
                        ? "§cDatabase error."
                        : "§aSet " + ref.name() + " " + gm.get() + " tier to " + tier))));
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /tieradmin reset <player> [mode]"); return; }
        Gamemode gm = null;
        if (args.length >= 3) {
            Optional<Gamemode> parsed = Gamemode.parse(args[2]);
            if (parsed.isEmpty()) { sender.sendMessage("§cModes: " + Arrays.toString(Gamemode.values())); return; }
            gm = parsed.get();
        }
        Gamemode mode = gm;
        withPlayer(sender, args[1], ref -> plugin.getStorage().deleteRatings(ref.uuid(), mode)
                .whenComplete((n, err) -> plugin.sync(() -> sender.sendMessage(err != null
                        ? "§cDatabase error."
                        : "§aReset " + ref.name() + (mode != null ? " " + mode : " (all modes)")
                          + ": " + n + " rating(s) removed."))));
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cUsage: /tieradmin info <player>"); return; }
        withPlayer(sender, args[1], ref -> plugin.getStorage().loadRatings(ref.uuid())
                .whenComplete((ratings, err) -> plugin.sync(() -> {
                    if (err != null) { sender.sendMessage("§cDatabase error."); return; }
                    sender.sendMessage("§7UUID: §f" + ref.uuid());
                    TierCommand.show(sender, ref.name(), ratings);
                })));
    }

    private void fight(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§cUsage: /tieradmin fight <player1> <player2> [mode]"); return; }
        Player a = Bukkit.getPlayerExact(args[1]);
        Player b = Bukkit.getPlayerExact(args[2]);
        if (a == null || b == null) { sender.sendMessage("§cBoth players must be online."); return; }
        Gamemode gm = args.length >= 4 ? Gamemode.parse(args[3]).orElse(null) : Gamemode.SWORD;
        if (gm == null) { sender.sendMessage("§cModes: " + Arrays.toString(Gamemode.values())); return; }

        if (!plugin.getFightTracker().startFight(a.getUniqueId(), b.getUniqueId(), gm)) {
            sender.sendMessage("§cCannot start: same player or one of them is already fighting.");
            return;
        }
        String msg = "§6Rated " + gm + " fight started: §f" + a.getName() + " §6vs §f" + b.getName()
                + "§6. Kill your opponent to win.";
        a.sendMessage(msg);
        b.sendMessage(msg);
        if (sender != a && sender != b) sender.sendMessage(msg);
    }

    private void simulate(CommandSender sender, String[] args) {
        int players = parseInt(args, 1, 200);
        int days = parseInt(args, 2, 30);
        if (players < 2 || players > 2000 || days < 1 || days > 365) {
            sender.sendMessage("§cplayers 2..2000, days 1..365");
            return;
        }
        sender.sendMessage("§7Simulating " + players + " players × " + days + " days...");
        var cfg = plugin.getRatingService().getConfig();
        // CPU-only work: run off the main thread, report back on it
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String report = Simulator.format(Simulator.run(cfg, players, days, 6, System.nanoTime()))
                          + Simulator.format(Simulator.farm(cfg, 100));
            plugin.sync(() -> report.lines().forEach(line -> sender.sendMessage("§7" + line)));
        });
    }

    /** Resolves a name (online or offline) and runs {@code action} with it, or reports not found. */
    private void withPlayer(CommandSender sender, String name, Consumer<Storage.PlayerRef> action) {
        PlayerResolver.resolve(plugin.getStorage(), name).whenComplete((ref, err) -> {
            if (err != null) {
                plugin.sync(() -> sender.sendMessage("§cDatabase error."));
            } else if (ref.isEmpty()) {
                plugin.sync(() -> sender.sendMessage("§cPlayer '" + name + "' has never joined."));
            } else {
                action.accept(ref.get());
            }
        });
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
            case "fight" -> args.length <= 3 ? filter(players, args[args.length - 1])
                          : args.length == 4 ? filter(modes, args[3]) : List.of();
            default -> List.of();
        };
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(o -> o.toLowerCase().startsWith(prefix.toLowerCase())).toList();
    }
}
