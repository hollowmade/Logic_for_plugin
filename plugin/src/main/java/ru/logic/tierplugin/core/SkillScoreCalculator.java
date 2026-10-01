package ru.logic.tierplugin.core;

import ru.logic.tierplugin.LogicTierPlugin;

/**
 * Calculates a normalised Skill Score (0–100) from three core metrics:
 * <ul>
 *   <li><b>Accuracy</b>  — hit% normalised to 0–100</li>
 *   <li><b>Damage</b>    — damage dealt normalised against a configurable cap</li>
 *   <li><b>Combo</b>     — max combo normalised against a configurable cap</li>
 * </ul>
 * Weights are read from {@code skill_weights.<gamemode>} in config.yml.
 * Falls back to defaults (0.40 / 0.35 / 0.25) if section is absent.
 */
public class SkillScoreCalculator {

    private final LogicTierPlugin plugin;

    public SkillScoreCalculator(LogicTierPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Computes the Skill Score for a player using raw fight telemetry.
     *
     * @param hits         number of successful hits
     * @param misses       number of missed attacks
     * @param damageDealt  total damage dealt (HP × 2 in vanilla)
     * @param maxCombo     maximum combo streak achieved
     * @param gamemode     PvP gamemode for per-mode weight lookup
     * @return skill score in [0, 100]
     */
    public double calculate(int hits, int misses, int damageDealt, int maxCombo, Gamemode gamemode) {
        String key = "skill_weights." + gamemode.configKey();
        var cfg = plugin.getConfig().getConfigurationSection(key);

        double wAccuracy = cfg != null ? cfg.getDouble("accuracy", 0.40) : 0.40;
        double wDamage   = cfg != null ? cfg.getDouble("damage",   0.35) : 0.35;
        double wCombo    = cfg != null ? cfg.getDouble("combo",    0.25) : 0.25;

        // Accuracy: 0–100 straight from hit %
        int totalSwings = hits + misses;
        double accuracyScore = totalSwings == 0 ? 50.0
                : 100.0 * hits / totalSwings;

        // Damage: normalised to 0–100 against configurable cap (default 200 HP worth)
        int damageCap = plugin.getConfig().getInt("skill_weights.damage_cap", 200);
        double damageScore = Math.min(100.0, 100.0 * damageDealt / damageCap);

        // Combo: normalised to 0–100 against configurable cap (default 20 hits)
        int comboCap = plugin.getConfig().getInt("skill_weights.combo_cap", 20);
        double comboScore = Math.min(100.0, 100.0 * maxCombo / comboCap);

        return wAccuracy * accuracyScore
             + wDamage   * damageScore
             + wCombo    * comboScore;
    }
}
