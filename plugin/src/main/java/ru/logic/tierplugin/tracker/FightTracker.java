package ru.logic.tierplugin.tracker;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.MatchInput;
import ru.logic.tierplugin.core.MatchOutcome;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages active {@link FightSession}s and hands finished fights to storage,
 * which rates and persists them off the main thread.
 * <p>
 * Repeat-match protection lives in the rating core and is counted from the
 * {@code matches} table, so the daily pair limit survives restarts.
 */
public class FightTracker {

    private final LogicTierPlugin plugin;

    /** Active sessions keyed by each participant's UUID. */
    private final Map<UUID, FightSession> activeSessions = new ConcurrentHashMap<>();

    public FightTracker(LogicTierPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Session lifecycle ──────────────────────────────────────

    /**
     * Starts a new fight session between two players.
     *
     * @return false if either player is already fighting
     */
    public boolean startFight(UUID p1, UUID p2, Gamemode gamemode) {
        if (p1.equals(p2) || isInFight(p1) || isInFight(p2)) return false;
        FightSession session = new FightSession(p1, p2, gamemode);
        activeSessions.put(p1, session);
        activeSessions.put(p2, session);
        return true;
    }

    /** @return the active session for the given player, or empty */
    public Optional<FightSession> getSession(UUID uuid) {
        return Optional.ofNullable(activeSessions.get(uuid));
    }

    /** Returns true if the player is currently in a fight. */
    public boolean isInFight(UUID uuid) {
        return activeSessions.containsKey(uuid);
    }

    /** Ends the fight with {@code winner} as the winner and submits it for rating. */
    public void endFight(UUID winner) {
        FightSession s = remove(winner);
        if (s == null) return;

        UUID loser = s.getOpponent(winner);
        MatchInput in = new MatchInput(s.getGamemode(),
                s.scoreOf(winner), s.scoreOf(loser),
                s.metricsOf(winner), s.metricsOf(loser),
                s.getDurationMillis());

        plugin.getStorage().recordMatch(winner, loser, in, plugin.getRatingService())
                .whenComplete((out, err) -> plugin.sync(() -> {
                    if (err != null) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to record match", err);
                        notify(winner, "§cMatch could not be saved, ratings unchanged.");
                        notify(loser, "§cMatch could not be saved, ratings unchanged.");
                        return;
                    }
                    report(winner, s.getGamemode(), out, out.winner(), true);
                    report(loser, s.getGamemode(), out, out.loser(), false);
                }));
    }

    /** Drops the fight of {@code participant} without touching any rating. */
    public void cancelFight(UUID participant) {
        remove(participant);
    }

    public void shutdown() {
        activeSessions.clear();
    }

    // ── Internals ──────────────────────────────────────────────

    private FightSession remove(UUID participant) {
        FightSession s = activeSessions.get(participant);
        if (s == null) return null;
        activeSessions.remove(s.getPlayer1(), s);
        activeSessions.remove(s.getPlayer2(), s);
        return s;
    }

    private void report(UUID uuid, Gamemode gm, MatchOutcome out, MatchOutcome.Side side, boolean won) {
        if (!out.rated()) {
            notify(uuid, "§7Unrated match: daily limit vs this opponent reached ("
                    + out.matchNumberToday() + " today).");
            return;
        }
        double d = side.eloDelta();
        notify(uuid, (won ? "§aVictory" : "§cDefeat") + " §7[" + gm + "] Elo §f"
                + Math.round(side.eloBefore()) + " → " + Math.round(side.eloAfter())
                + (d >= 0 ? " §a(+" : " §c(") + Math.round(d) + ")"
                + (out.weight() < 0.999 ? String.format(" §8weight %.0f%%", out.weight() * 100) : ""));
        if (side.tierChanged()) {
            notify(uuid, "§6Tier: §f" + name(side.tierBefore()) + " → " + name(side.tierAfter())
                    + (side.provisional() ? " §7(provisional)" : ""));
        }
    }

    private static String name(Object tier) {
        return tier == null ? "Unranked" : tier.toString();
    }

    private static void notify(UUID uuid, String msg) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) p.sendMessage(msg);
    }
}
