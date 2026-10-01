package ru.logic.tierplugin.tracker;

import ru.logic.tierplugin.LogicTierPlugin;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.PlayerProfile;
import ru.logic.tierplugin.storage.PlayerProfileRepository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active {@link FightSession}s and converts them into rating/skill
 * updates when a fight ends.
 *
 * <h3>Anti-abuse: Diminishing Returns</h3>
 * Each pair of players has a daily match counter. The rating gain multiplier
 * decreases for repeated matches against the same opponent:
 * <pre>
 *   Match 1  → 100 %
 *   Match 2  → 70 %
 *   Match 3  → 50 %
 *   Match 4  → 30 %
 *   Match 5+ → 10 %
 * </pre>
 * A hard cap ({@code max_daily_rated_matches_per_opponent}) is enforced; beyond
 * it, the match still plays out but grants 0 Elo change.
 */
public class FightTracker {

    private final LogicTierPlugin plugin;
    private final PlayerProfileRepository repo;

    /** Active sessions keyed by each participant's UUID. */
    private final Map<UUID, FightSession> activeSessions = new ConcurrentHashMap<>();

    /**
     * Daily match counter per ordered pair (lower UUID first).
     * Key: "uuid1:uuid2", Value: matches played today.
     * Cleared daily via {@link #clearDailyCounters()}.
     */
    private final Map<String, Integer> dailyMatchCounts = new ConcurrentHashMap<>();

    // Diminishing-return multipliers indexed by match number (1-based, capped at index 5)
    private static final double[] DIMINISHING = { 0, 1.00, 0.70, 0.50, 0.30, 0.10 };

    public FightTracker(LogicTierPlugin plugin) {
        this.plugin = plugin;
        this.repo   = new PlayerProfileRepository(plugin);
    }

    // ── Session lifecycle ──────────────────────────────────────

    /** Starts a new fight session between two players. */
    public void startFight(UUID p1, UUID p2, Gamemode gamemode) {
        FightSession session = new FightSession(p1, p2, gamemode);
        activeSessions.put(p1, session);
        activeSessions.put(p2, session);
    }

    /** @return the active session for the given player, or empty */
    public Optional<FightSession> getSession(UUID uuid) {
        return Optional.ofNullable(activeSessions.get(uuid));
    }

    /** Returns true if the player is currently in a fight. */
    public boolean isInFight(UUID uuid) {
        return activeSessions.containsKey(uuid);
    }

    /**
     * Ends the fight, updates Elo / Skill Score / tier, and persists.
     *
     * @param winner UUID of the winner
     */
    public void endFight(UUID winner) {
        FightSession session = activeSessions.get(winner);
        if (session == null) return;

        UUID loser = session.getOpponent(winner);
        activeSessions.remove(session.getPlayer1());
        activeSessions.remove(session.getPlayer2());

        processResult(session, winner, loser);
    }

    // ── Core processing ────────────────────────────────────────

    private void processResult(FightSession s, UUID winnerId, UUID loserId) {
        PlayerProfile winner = repo.findByUuid(winnerId)
                .orElseGet(() -> new PlayerProfile(winnerId, "Unknown"));
        PlayerProfile loser  = repo.findByUuid(loserId)
                .orElseGet(() -> new PlayerProfile(loserId, "Unknown"));

        // ── 1. Diminishing returns ──────────────────────────────
        String pairKey = pairKey(winnerId, loserId);
        int matchNum = dailyMatchCounts.merge(pairKey, 1, Integer::sum);
        int maxRated = plugin.getConfig().getInt("elo.max_daily_rated_matches_per_opponent", 20);

        double diminishing = 0.0;
        if (matchNum <= maxRated) {
            int idx = Math.min(matchNum, DIMINISHING.length - 1);
            diminishing = DIMINISHING[idx];
        }
        // If diminishing == 0 fight is unrated (no Elo change)

        // ── 2. Elo update ───────────────────────────────────────
        var eloCalc = plugin.getTierEngine().getEloCalculator();
        double wExp = eloCalc.expectedScore(winner.getEloRating(), loser.getEloRating());
        double lExp = 1.0 - wExp;

        // Match quality: closer fight = higher signal; scaled by diminishing factor
        double quality = eloCalc.matchQuality(
                winnerId.equals(s.getPlayer1()) ? s.getP1Score() : s.getP2Score(),
                winnerId.equals(s.getPlayer1()) ? s.getP2Score() : s.getP1Score(),
                diminishing);

        winner.setEloRating(eloCalc.newRating(
                winner.getEloRating(), winner.getRatingDeviation(), wExp, 1.0, quality));
        loser.setEloRating(eloCalc.newRating(
                loser.getEloRating(),  loser.getRatingDeviation(),  lExp, 0.0, quality));

        winner.setRatingDeviation(eloCalc.reduceRd(winner.getRatingDeviation()));
        loser.setRatingDeviation(eloCalc.reduceRd(loser.getRatingDeviation()));

        // ── 3. Skill Score update (winner only) ─────────────────
        boolean p1Wins = winnerId.equals(s.getPlayer1());
        int wHits   = p1Wins ? s.getP1Hits()        : s.getP2Hits();
        int wMisses = p1Wins ? s.getP1Misses()      : s.getP2Misses();
        int wDmg    = p1Wins ? s.getP1DamageDealt() : s.getP2DamageDealt();
        int wCombo  = p1Wins ? s.getP1MaxCombo()    : s.getP2MaxCombo();

        var skillCalc = plugin.getTierEngine().getSkillCalculator();
        double newSkill = skillCalc.calculate(wHits, wMisses, wDmg, wCombo, s.getGamemode());
        // Exponential moving average (alpha = 0.2) — stabilises score over time
        winner.setSkillScore(winner.getSkillScore() * 0.8 + newSkill * 0.2);

        // ── 4. Fight counts & confidence ───────────────────────
        winner.setTotalFights(winner.getTotalFights() + 1);
        loser.setTotalFights(loser.getTotalFights()   + 1);

        // Confidence grows faster for winners (more informative result)
        winner.setConfidence(Math.min(1.0, winner.getConfidence() + 0.025));
        loser.setConfidence(Math.min(1.0, loser.getConfidence()   + 0.015));

        // ── 5. Tier evaluation & persist ───────────────────────
        plugin.getTierEngine().evaluate(winner);
        plugin.getTierEngine().evaluate(loser);

        repo.save(winner);
        repo.save(loser);
    }

    // ── Utilities ──────────────────────────────────────────────

    /**
     * Canonical pair key: always puts the smaller UUID first
     * so A-vs-B and B-vs-A share the same counter.
     */
    private String pairKey(UUID a, UUID b) {
        return a.compareTo(b) < 0
                ? a + ":" + b
                : b + ":" + a;
    }

    /** Clears the daily match counters (call at midnight or server restart). */
    public void clearDailyCounters() {
        dailyMatchCounts.clear();
    }

    public void shutdown() {
        activeSessions.clear();
        dailyMatchCounts.clear();
    }
}
