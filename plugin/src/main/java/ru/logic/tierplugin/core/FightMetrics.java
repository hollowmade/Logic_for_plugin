package ru.logic.tierplugin.core;

/** Raw combat telemetry of one player in one fight. */
public record FightMetrics(int hits, int misses, int damageDealt, int damageTaken, int maxCombo) {

    public static final FightMetrics EMPTY = new FightMetrics(0, 0, 0, 0, 0);

    /** @return hit percentage 0–100, or -1 if the player never swung */
    public double accuracy() {
        int swings = hits + misses;
        return swings == 0 ? -1 : 100.0 * hits / swings;
    }
}
