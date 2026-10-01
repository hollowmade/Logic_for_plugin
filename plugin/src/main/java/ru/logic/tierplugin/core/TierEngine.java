package ru.logic.tierplugin.core;

import java.util.UUID;

/**
 * Central tier-determination engine.
 * <p>
 * Given a player's Elo, Skill Score and Confidence,
 * resolves the appropriate {@link Tier} or marks the player as PROVISIONAL.
 */
public class TierEngine {

    private final CoreConfig config;
    private final SkillScoreCalculator skillCalc;
    private final EloCalculator eloCalc;

    public TierEngine(CoreConfig config) {
        this.config    = config;
        this.skillCalc = new SkillScoreCalculator(config);
        this.eloCalc   = new EloCalculator(config);
    }

    public CoreConfig getConfig() { return config; }
    public SkillScoreCalculator getSkillCalculator() { return skillCalc; }
    public EloCalculator getEloCalculator() { return eloCalc; }

    /** Creates a fresh profile with the configured initial Elo / RD / volatility. */
    public PlayerProfile newProfile(UUID uuid, String name) {
        CoreConfig.Elo elo = config.elo();
        return new PlayerProfile(uuid, name, elo.initialRating(), elo.initialRd(), elo.initialVolatility());
    }

    /**
     * Determines and sets the current tier for {@code profile}.
     * Walks tiers from lowest to highest and keeps the last one
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
        profile.setProvisional(profile.getConfidence() < config.provisionalThreshold() || resolved == null);
    }

    private boolean meetsThreshold(PlayerProfile profile, Tier tier) {
        CoreConfig.TierThreshold t = config.tiers().get(tier);
        if (t == null) return false;

        return profile.getEloRating()   >= t.minElo()
            && profile.getSkillScore()  >= t.minSkill()
            && profile.getConfidence()  >= t.minConfidence()
            && profile.getTotalFights() >= t.minFights();
    }
}
