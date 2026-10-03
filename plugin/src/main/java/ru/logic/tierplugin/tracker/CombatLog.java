package ru.logic.tierplugin.tracker;

import ru.logic.tierplugin.core.FightMetrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw telemetry of one player in one fight, turned into {@link FightMetrics} on demand.
 * <p>
 * Attacks: a melee hit arrives as an attack packet plus a swing packet in the same
 * tick, but the client does not send a swing packet for every click when clicking
 * fast. So within one tick, swings and hits are paired: the number of attacks in a
 * tick is {@code max(swings, hits)}. A hit therefore always counts as an attack and
 * accuracy can never exceed 100 %.
 * <p>
 * Combo: consecutive melee hits on the opponent. A streak ends when the player
 * takes a melee hit from the opponent (the "can't hit back" part of a combo) or
 * when the gap to the next hit exceeds {@code comboTimeoutMs}.
 * <p>
 * Not thread-safe; fed from the server main thread.
 */
final class CombatLog {

    private final TrackerSettings settings;

    /** Time of every attack (hit or miss), for CPS. Its size is the attack count. */
    private final List<Long> swingTimes = new ArrayList<>();
    private int tick = Integer.MIN_VALUE;
    private int swingsInTick;
    private int hitsInTick;
    private int hits;
    private double damageDealt;
    private double damageTaken;

    private final List<Integer> streaks = new ArrayList<>();
    private int streak;
    private long lastHitAt;

    CombatLog(TrackerSettings settings) {
        this.settings = settings;
    }

    void swing(long now, int tick) {
        roll(tick);
        swingsInTick++;
        if (swingsInTick > hitsInTick) swingTimes.add(now); // not already counted by a hit
    }

    /** A melee hit on the opponent: counts as an attack, for accuracy and extends the combo. */
    void meleeHit(long now, int tick, double damage) {
        roll(tick);
        hitsInTick++;
        if (hitsInTick > swingsInTick) swingTimes.add(now); // its swing packet is missing or still to come
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

    private void roll(int t) {
        if (t != tick) {
            tick = t;
            swingsInTick = 0;
            hitsInTick = 0;
        }
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
