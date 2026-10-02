package ru.logic.tierplugin.tracker;

import ru.logic.tierplugin.core.FightMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw telemetry of one player in one fight, turned into {@link FightMetrics} on demand.
 * <p>
 * Combo: consecutive melee hits on the opponent. A streak ends when the player
 * takes a melee hit from the opponent (the "can't hit back" part of a combo) or
 * when the gap to the next hit exceeds {@code comboTimeoutMs}.
 * <p>
 * Not thread-safe; fed from the server main thread.
 */
final class CombatLog {

    private final TrackerSettings settings;

    private final List<Long> swingTimes = new ArrayList<>();
    private int hits;
    private double damageDealt;
    private double damageTaken;

    private final List<Integer> streaks = new ArrayList<>();
    private int streak;
    private long lastHitAt;

    CombatLog(TrackerSettings settings) {
        this.settings = settings;
    }

    void swing(long now) {
        swingTimes.add(now);
    }

    /** A melee hit on the opponent: counts for accuracy and extends the combo. */
    void meleeHit(long now, double damage) {
        if (streak > 0 && now - lastHitAt > settings.comboTimeoutMs()) closeStreak();
        streak++;
        lastHitAt = now;
        hits++;
        damageDealt += damage;
    }

    /** Damage to the opponent that is not a direct hit (sweep, projectile). */
    void indirectDamage(double damage) {
        damageDealt += damage;
    }

    /** The opponent landed a melee hit: our combo is over. */
    void hitByOpponent() {
        closeStreak();
    }

    void damageTaken(double damage) {
        damageTaken += damage;
    }

    private void closeStreak() {
        if (streak > 0) streaks.add(streak);
        streak = 0;
    }

    /** Snapshot without closing the running streak, so it is safe to call mid-fight. */
    FightMetrics toMetrics() {
        List<Integer> all = new ArrayList<>(streaks);
        if (streak > 0) all.add(streak);

        int max = all.stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Integer> combos = all.stream().filter(s -> s >= settings.comboMinLength()).sorted().toList();
        double avg = combos.stream().mapToInt(Integer::intValue).average().orElse(0);

        return new FightMetrics(swingTimes.size(), hits, damageDealt, damageTaken,
                max, avg, median(combos), combos.size(), avgCps(), maxCps());
    }

    private static double median(List<Integer> sorted) {
        int n = sorted.size();
        if (n == 0) return 0;
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /** Swings divided by the number of distinct seconds in which the player swung. */
    private double avgCps() {
        if (swingTimes.isEmpty()) return 0;
        long first = swingTimes.get(0);
        long activeSeconds = swingTimes.stream().map(t -> (t - first) / 1000).distinct().count();
        return swingTimes.size() / (double) activeSeconds;
    }

    /** Most swings inside any sliding 1000 ms window. */
    private int maxCps() {
        int best = 0;
        for (int lo = 0, hi = 0; hi < swingTimes.size(); hi++) {
            while (swingTimes.get(hi) - swingTimes.get(lo) >= 1000) lo++;
            best = Math.max(best, hi - lo + 1);
        }
        return best;
    }
}
