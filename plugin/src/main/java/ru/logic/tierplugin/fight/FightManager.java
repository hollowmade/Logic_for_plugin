package ru.logic.tierplugin.fight;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.EndReason;
import ru.logic.tierplugin.core.FightMetrics;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.MatchInput;
import ru.logic.tierplugin.core.MatchOutcome;
import ru.logic.tierplugin.tracker.ActiveFights;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Owns the lifecycle of fights: start, end with a winner, cancel, and disconnects.
 * Implements {@link ActiveFights} so the metrics tracker can attach telemetry
 * without knowing how fights are started (admin command now, duels later).
 * <p>
 * Disconnect: the fight pauses for {@code reconnect_grace_seconds}. If the player
 * rejoins in time the fight continues; otherwise it ends as a LEAVE (see RatingService).
 * If both players are gone, the fight is cancelled unrated.
 */
public class FightManager implements ActiveFights {

    private final LogicTierPlugin plugin;
    private final FightSettings settings;

    /** Active fights keyed by each participant's UUID. */
    private final Map<UUID, Fight> active = new ConcurrentHashMap<>();

    /** Messages for players who were offline when their fight was decided. */
    private final Map<UUID, List<String>> pendingNotices = new ConcurrentHashMap<>();

    public FightManager(LogicTierPlugin plugin, FightSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public Optional<FightRef> fightOf(UUID player) {
        return getFight(player).map(Fight::ref);
    }

    public Optional<Fight> getFight(UUID player) {
        return Optional.ofNullable(active.get(player));
    }

    public boolean isInFight(UUID player) {
        return active.containsKey(player);
    }

    /**
     * Starts a fight between two players.
     *
     * @return false if it is the same player or either is already fighting
     */
    public boolean startFight(UUID a, UUID b, Gamemode gamemode) {
        if (a.equals(b) || isInFight(a) || isInFight(b)) return false;
        Fight f = new Fight(a, b, gamemode, System.currentTimeMillis());
        active.put(a, f);
        active.put(b, f);
        return true;
    }

    /** Records a kill of the opponent and ends the fight with {@code killer} as the winner. */
    public void killed(UUID killer) {
        getFight(killer).ifPresent(f -> {
            f.recordKill(killer);
            endFight(killer, EndReason.KILL);
        });
    }

    /** Drops the fight of {@code participant} without touching any rating. */
    public void cancelFight(UUID participant) {
        Fight f = remove(participant);
        if (f != null) plugin.getMetricsTracker().discard(f.ref().id());
    }

    // ── Disconnects ────────────────────────────────────────────

    /** A participant left the server: pause and give them time to come back. */
    public void playerQuit(UUID player) {
        Fight f = active.get(player);
        if (f == null) return;
        UUID opponent = f.ref().opponentOf(player);

        if (f.disconnected() != null) {
            // The opponent is already gone too: nobody to award the win to
            cancelFight(player);
            plugin.getLogger().info("Both players left a fight; cancelled unrated.");
            return;
        }

        int grace = settings.reconnectGraceSeconds();
        f.markDisconnected(player, System.currentTimeMillis(),
                Bukkit.getScheduler().runTaskLater(plugin, () -> graceExpired(f, player), grace * 20L));
        notify(opponent, "§eYour opponent disconnected. Waiting §f" + grace
                + "s§e for them to return, otherwise you win by forfeit.");
    }

    /** A player joined: resume their paused fight and deliver messages they missed. */
    public void playerJoin(Player player) {
        UUID id = player.getUniqueId();
        List<String> notices = pendingNotices.remove(id);
        if (notices != null) notices.forEach(player::sendMessage);

        Fight f = active.get(id);
        if (f != null && id.equals(f.disconnected())) {
            f.clearDisconnect();
            player.sendMessage("§eYou are back in your fight. It continues.");
            notify(f.ref().opponentOf(id), "§eYour opponent is back. The fight continues.");
        }
    }

    private void graceExpired(Fight f, UUID leaver) {
        if (active.get(leaver) != f || !leaver.equals(f.disconnected())) return; // resolved meanwhile
        UUID opponent = f.ref().opponentOf(leaver);
        if (Bukkit.getPlayer(opponent) == null) {
            cancelFight(leaver);
            return;
        }
        endFight(opponent, EndReason.LEAVE);
    }

    // ── Ending ─────────────────────────────────────────────────

    /** Ends the fight with {@code winner} as the winner and submits it for rating. */
    private void endFight(UUID winner, EndReason reason) {
        Fight f = active.get(winner);
        if (f == null) return;
        long endedAt = reason == EndReason.LEAVE ? f.disconnectedAt() : System.currentTimeMillis();
        remove(winner);

        UUID loser = f.ref().opponentOf(winner);
        Map<UUID, FightMetrics> metrics = plugin.getMetricsTracker().finish(f.ref());
        MatchInput in = new MatchInput(f.gamemode(),
                f.killsOf(winner), f.killsOf(loser),
                metrics.get(winner), metrics.get(loser),
                endedAt - f.startedAt(), reason);

        plugin.getStorage().recordMatch(winner, loser, in, plugin.getRatingService())
                .whenComplete((out, err) -> plugin.sync(() -> {
                    if (err != null) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to record match", err);
                        notify(winner, "§cMatch could not be saved, ratings unchanged.");
                        notifyOrQueue(loser, "§cMatch could not be saved, ratings unchanged.");
                        return;
                    }
                    if (out.isLeave()) {
                        reportLeaveWinner(winner, f.gamemode(), out, in.winnerMetrics());
                        reportLeaver(loser, f.gamemode(), out);
                    } else {
                        report(winner, f.gamemode(), out, out.winner(), in.winnerMetrics(), true);
                        report(loser, f.gamemode(), out, out.loser(), in.loserMetrics(), false);
                    }
                }));
    }

    public void shutdown() {
        active.values().forEach(Fight::clearDisconnect);
        active.clear();
    }

    private Fight remove(UUID participant) {
        Fight f = active.get(participant);
        if (f == null) return null;
        f.clearDisconnect();
        active.remove(f.ref().playerA(), f);
        active.remove(f.ref().playerB(), f);
        return f;
    }

    // ── Messages ───────────────────────────────────────────────

    private void report(UUID uuid, Gamemode gm, MatchOutcome out, MatchOutcome.Side side,
                        FightMetrics m, boolean won) {
        notify(uuid, (won ? "§aVictory" : "§cDefeat") + " §7[" + gm + "]");
        notify(uuid, formatMetrics(m));
        if (!out.rated()) {
            notify(uuid, "§7Unrated match: daily limit vs this opponent reached ("
                    + out.matchNumberToday() + " today).");
            return;
        }
        notify(uuid, eloLine(side, won ? out.weight() : out.loserWeight(), "weight"));
        tierLine(side).ifPresent(l -> notify(uuid, l));
    }

    private void reportLeaveWinner(UUID uuid, Gamemode gm, MatchOutcome out, FightMetrics m) {
        notify(uuid, "§aVictory §7[" + gm + "] §7— your opponent left the fight.");
        notify(uuid, formatMetrics(m));
        if (!out.rated()) {
            notify(uuid, "§7No Elo for this win: the fight had not really started (no hits, too short).");
            return;
        }
        notify(uuid, eloLine(out.winner(), out.weight(), "weight"));
        tierLine(out.winner()).ifPresent(l -> notify(uuid, l));
    }

    /** The leaver is usually offline: these messages wait for their next join. */
    private void reportLeaver(UUID uuid, Gamemode gm, MatchOutcome out) {
        var leave = plugin.getRatingService().getConfig().leave();
        notifyOrQueue(uuid, "§cYou left a " + gm + " fight and lost by forfeit"
                + " §7(leave #" + out.leaveNumber() + " in " + leave.windowHours() + "h).");
        notifyOrQueue(uuid, eloLine(out.loser(), out.loserWeight(), "penalty"));
        tierLine(out.loser()).ifPresent(l -> notifyOrQueue(uuid, l));
        if (leave.cooldownAfterLeaves() > 0 && out.leaveNumber() >= leave.cooldownAfterLeaves()) {
            notifyOrQueue(uuid, "§cToo many leaves: you cannot start fights for "
                    + leave.cooldownMinutes() + " min.");
        }
    }

    /** @param weightLabel "weight" (shown as a percentage) or "penalty" (shown as a multiplier) */
    private static String eloLine(MatchOutcome.Side side, double weight, String weightLabel) {
        double d = side.eloDelta();
        String w = Math.abs(weight - 1) < 0.001 ? ""
                : weightLabel.equals("penalty") ? String.format(" §8penalty ×%.1f", weight)
                : String.format(" §8weight %.0f%%", weight * 100);
        return "§7Elo §f" + Math.round(side.eloBefore()) + " → " + Math.round(side.eloAfter())
                + (d >= 0 ? " §a(+" : " §c(") + Math.round(d) + ")" + w;
    }

    private static Optional<String> tierLine(MatchOutcome.Side side) {
        if (!side.tierChanged()) return Optional.empty();
        return Optional.of("§6Tier: §f" + name(side.tierBefore()) + " → " + name(side.tierAfter())
                + (side.provisional() ? " §7(provisional)" : ""));
    }

    /** One-line summary of fight metrics for chat. */
    public static String formatMetrics(FightMetrics m) {
        String acc = m.accuracy() < 0 ? "-" : String.format("%.0f%%", m.accuracy());
        return String.format("§7Acc §f%s §8(%d/%d) §7| Dmg §f%.1f§7/§f%.1f §7| CPS §f%.1f §8(max %d)"
                        + " §7| Combo max §f%d§7, avg §f%.1f§7, med §f%.1f §8(%d)",
                acc, m.hits(), m.swings(), m.damageDealt(), m.damageTaken(),
                m.avgCps(), m.maxCps(), m.maxCombo(), m.avgCombo(), m.medianCombo(), m.combos());
    }

    private static String name(Object tier) {
        return tier == null ? "Unranked" : tier.toString();
    }

    private static void notify(UUID uuid, String msg) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) p.sendMessage(msg);
    }

    /** Sends now if online, otherwise on the player's next join (until restart). */
    private void notifyOrQueue(UUID uuid, String msg) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) p.sendMessage(msg);
        else pendingNotices.computeIfAbsent(uuid, k -> new ArrayList<>()).add(msg);
    }
}
