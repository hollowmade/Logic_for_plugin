package ru.logic.tierplugin.fight;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.FightMetrics;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.MatchInput;
import ru.logic.tierplugin.core.MatchOutcome;
import ru.logic.tierplugin.tracker.ActiveFights;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Owns the lifecycle of fights: start, end with a winner, cancel.
 * Implements {@link ActiveFights} so the metrics tracker can attach telemetry
 * without knowing how fights are started (admin command now, duels later).
 */
public class FightManager implements ActiveFights {

    private final LogicTierPlugin plugin;

    /** Active fights keyed by each participant's UUID. */
    private final Map<UUID, Fight> active = new ConcurrentHashMap<>();

    public FightManager(LogicTierPlugin plugin) {
        this.plugin = plugin;
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
            endFight(killer);
        });
    }

    /** Ends the fight with {@code winner} as the winner and submits it for rating. */
    public void endFight(UUID winner) {
        Fight f = remove(winner);
        if (f == null) return;

        UUID loser = f.ref().opponentOf(winner);
        Map<UUID, FightMetrics> metrics = plugin.getMetricsTracker().finish(f.ref());
        MatchInput in = new MatchInput(f.gamemode(),
                f.killsOf(winner), f.killsOf(loser),
                metrics.get(winner), metrics.get(loser),
                System.currentTimeMillis() - f.startedAt());

        plugin.getStorage().recordMatch(winner, loser, in, plugin.getRatingService())
                .whenComplete((out, err) -> plugin.sync(() -> {
                    if (err != null) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to record match", err);
                        notify(winner, "§cMatch could not be saved, ratings unchanged.");
                        notify(loser, "§cMatch could not be saved, ratings unchanged.");
                        return;
                    }
                    report(winner, f.gamemode(), out, out.winner(), in.winnerMetrics(), true);
                    report(loser, f.gamemode(), out, out.loser(), in.loserMetrics(), false);
                }));
    }

    /** Drops the fight of {@code participant} without touching any rating. */
    public void cancelFight(UUID participant) {
        Fight f = remove(participant);
        if (f != null) plugin.getMetricsTracker().discard(f.ref().id());
    }

    public void shutdown() {
        active.clear();
    }

    // ── Internals ──────────────────────────────────────────────

    private Fight remove(UUID participant) {
        Fight f = active.get(participant);
        if (f == null) return null;
        active.remove(f.ref().playerA(), f);
        active.remove(f.ref().playerB(), f);
        return f;
    }

    private void report(UUID uuid, Gamemode gm, MatchOutcome out, MatchOutcome.Side side,
                        FightMetrics m, boolean won) {
        notify(uuid, (won ? "§aVictory" : "§cDefeat") + " §7[" + gm + "]");
        notify(uuid, formatMetrics(m));
        if (!out.rated()) {
            notify(uuid, "§7Unrated match: daily limit vs this opponent reached ("
                    + out.matchNumberToday() + " today).");
            return;
        }
        double d = side.eloDelta();
        notify(uuid, "§7Elo §f" + Math.round(side.eloBefore()) + " → " + Math.round(side.eloAfter())
                + (d >= 0 ? " §a(+" : " §c(") + Math.round(d) + ")"
                + (out.weight() < 0.999 ? String.format(" §8weight %.0f%%", out.weight() * 100) : ""));
        if (side.tierChanged()) {
            notify(uuid, "§6Tier: §f" + name(side.tierBefore()) + " → " + name(side.tierAfter())
                    + (side.provisional() ? " §7(provisional)" : ""));
        }
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
}
