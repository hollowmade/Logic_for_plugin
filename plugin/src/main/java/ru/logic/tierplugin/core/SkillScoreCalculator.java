package ru.logic.tierplugin.core;

/**
 * Calculates a normalised Skill Score (0–100) from three core metrics:
 * <ul>
 *   <li><b>Accuracy</b>  — hit% normalised to 0–100</li>
 *   <li><b>Damage</b>    — damage dealt normalised against a configurable cap</li>
 *   <li><b>Combo</b>     — max combo normalised against a configurable cap</li>
 * </ul>
 * Weights come from {@code skill_weights.<gamemode>} in config.yml.
 * Falls back to defaults (0.40 / 0.35 / 0.25) if a gamemode is absent.
 */
public class SkillScoreCalculator {

    private final CoreConfig config;

    public SkillScoreCalculator(CoreConfig config) {
        this.config = config;
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
        CoreConfig.Skill skill = config.skill();
        CoreConfig.Weights w = skill.weightsFor(gamemode);

        // Accuracy: 0–100 straight from hit %
        int totalSwings = hits + misses;
        double accuracyScore = totalSwings == 0 ? 50.0
                : 100.0 * hits / totalSwings;

        // Damage: normalised to 0–100 against configurable cap (default 200 HP worth)
        double damageScore = Math.min(100.0, 100.0 * damageDealt / skill.damageCap());

        // Combo: normalised to 0–100 against configurable cap (default 20 hits)
        double comboScore = Math.min(100.0, 100.0 * maxCombo / skill.comboCap());

        return w.accuracy() * accuracyScore
             + w.damage()   * damageScore
             + w.combo()    * comboScore;
    }
}
