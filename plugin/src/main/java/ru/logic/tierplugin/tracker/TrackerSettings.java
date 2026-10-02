package ru.logic.tierplugin.tracker;

/**
 * @param comboTimeoutMs a hit streak breaks if the next hit comes later than this
 * @param comboMinLength shortest streak that counts as a combo for avg/median
 */
public record TrackerSettings(long comboTimeoutMs, int comboMinLength) {

    public static final TrackerSettings DEFAULTS = new TrackerSettings(1500, 2);
}
