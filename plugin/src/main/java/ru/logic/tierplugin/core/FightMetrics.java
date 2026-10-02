package ru.logic.tierplugin.core;

/**
 * Combat telemetry of one player in one fight.
 *
 * @param swings       main-hand attack swings (hits and misses)
 * @param hits         melee hits on the opponent
 * @param damageDealt  final damage dealt to the opponent (HP, after armor)
 * @param damageTaken  final damage taken from any source during the fight
 * @param maxCombo     longest hit streak
 * @param avgCombo     mean length of streaks that count as combos
 * @param medianCombo  median length of streaks that count as combos
 * @param combos       number of streaks that count as combos
 * @param avgCps       swings per second, over seconds in which the player swung
 * @param maxCps       most swings within any 1-second window
 */
public record FightMetrics(int swings, int hits, double damageDealt, double damageTaken,
                           int maxCombo, double avgCombo, double medianCombo, int combos,
                           double avgCps, int maxCps) {

    public static final FightMetrics EMPTY = new FightMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Metrics with only the basic counters set; used by the simulator and tests. */
    public static FightMetrics basic(int hits, int misses, double damageDealt, double damageTaken, int maxCombo) {
        return new FightMetrics(hits + misses, hits, damageDealt, damageTaken,
                maxCombo, maxCombo, maxCombo, maxCombo >= 2 ? 1 : 0, 0, 0);
    }

    public int misses() {
        return Math.max(0, swings - hits);
    }

    /**
     * @return hit percentage 0–100, or -1 if the player never swung.
     *         Capped at 100: a swing packet can be lost while the hit still registers.
     */
    public double accuracy() {
        return swings == 0 ? -1 : Math.min(100.0, 100.0 * hits / swings);
    }
}
