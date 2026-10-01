package ru.logic.tierplugin.core;

import org.bukkit.configuration.ConfigurationSection;
import ru.logic.tierplugin.LogicTierPlugin;

/**
 * Central tier-determination engine.
 * <p>
 * Given a player's Elo, Skill Score and Confidence,
 * resolves the appropriate {@link Tier} or marks the player as PROVISIONAL.
 */
public class TierEngine {

    private final LogicTierPlugin plugin;
    private final SkillScoreCalculator skillCalc;
    private final EloCalculator eloCalc;

    public TierEngine(LogicTierPlugin plugin) {
        this.plugin = plugin;
        this.skillCalc = new SkillScoreCalculator(plugin);
        this.eloCalc   = new EloCalculator(plugin);
    }

    public SkillScoreCalculator getSkillCalculator() { return skillCalc; }
    public EloCalculator getEloCalculator() { return eloCalc; }

    /**
     * Determines and sets the current tier for {@code profile}.
     * Iterates tiers from highest to lowest and assigns the first one
     * whose thresholds are fully satisfied.
     */
    public void evaluate(PlayerProfile profile) {
        Tier resolved = null;

        for (Tier tier : Tier.values()) {
            if (meetsThreshold(profile, tier)) {
                resolved = tier;
            }
        }

        profile.setTier(resolved);

        double provisionalThreshold = plugin.getConfig()
                .getDouble("confidence.provisional_threshold", 0.60);
        profile.setProvisional(profile.getConfidence() < provisionalThreshold || resolved == null);
    }

    private boolean meetsThreshold(PlayerProfile profile, Tier tier) {
        String key = "tiers." + tier.name();
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection(key);
        if (cfg == null) return false;

        double minElo        = cfg.getDouble("min_elo", 0);
        double minSkill      = cfg.getDouble("min_skill", 0);
        double minConfidence = cfg.getDouble("min_confidence", 0);
        int    minFights     = cfg.getInt("min_fights", 0);

        return profile.getEloRating()  >= minElo
            && profile.getSkillScore() >= minSkill
            && profile.getConfidence() >= minConfidence
            && profile.getTotalFights() >= minFights;
    }
}
