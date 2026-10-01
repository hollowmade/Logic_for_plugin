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

    /** @return skill score of a single fight in [0, 100] */
    public double calculate(FightMetrics m, Gamemode gamemode) {
        CoreConfig.Skill skill = config.skill();
        CoreConfig.Weights w = skill.weightsFor(gamemode);

        // No swings recorded → neutral accuracy rather than a penalty
        double accuracyScore = m.accuracy() < 0 ? 50.0 : m.accuracy();
        double damageScore = Math.min(100.0, 100.0 * m.damageDealt() / skill.damageCap());
        double comboScore = Math.min(100.0, 100.0 * m.maxCombo() / skill.comboCap());

        return w.accuracy() * accuracyScore
             + w.damage()   * damageScore
             + w.combo()    * comboScore;
    }

    /** Folds one fight into the running score (exponential moving average). */
    public double update(double current, int fightsBefore, double fightScore) {
        // The first fight sets the score directly instead of dragging it up from 0
        if (fightsBefore == 0) return fightScore;
        double a = config.skill().emaAlpha();
        return current * (1 - a) + fightScore * a;
    }
}
