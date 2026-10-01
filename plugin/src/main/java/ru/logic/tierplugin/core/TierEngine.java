package ru.logic.tierplugin.core;

/**
 * Resolves a {@link Tier} for a {@link ModeRating}.
 * <ol>
 *   <li><b>Placement</b> (fewer than {@code placement_fights} fights): the tier is a
 *       temporary estimate from Elo and Skill only; {@code provisional = true}.</li>
 *   <li><b>Fixed</b>: the tier needs all thresholds (Elo, Skill, confidence, fights).
 *       Promotion is immediate; demotion happens only once Elo falls more than
 *       {@code demotion_buffer_elo} below the current tier, so a player sitting on a
 *       boundary does not flip between two tiers every match.</li>
 * </ol>
 */
public class TierEngine {

    private final CoreConfig config;

    public TierEngine(CoreConfig config) {
        this.config = config;
    }

    public void evaluate(ModeRating r) {
        CoreConfig.TierRules rules = config.tierRules();

        if (r.getFights() < rules.placementFights()) {
            r.setProvisional(true);
            r.setTier(highest(r, true, 0));
            return;
        }

        Tier earned = highest(r, false, 0);
        Tier current = r.isProvisional() ? null : r.getTier();
        r.setProvisional(false);

        boolean wouldDrop = current != null && (earned == null || earned.isLowerThan(current));
        if (wouldDrop && meets(r, current, false, rules.demotionBufferElo())) {
            return; // inside the buffer: keep the current tier
        }
        r.setTier(earned);
    }

    /** @return highest tier whose thresholds are met, or null */
    private Tier highest(ModeRating r, boolean estimateOnly, double eloSlack) {
        Tier best = null;
        for (Tier tier : Tier.values()) {
            if (meets(r, tier, estimateOnly, eloSlack)) best = tier;
        }
        return best;
    }

    /**
     * @param estimateOnly check only Elo and Skill (placement estimate)
     * @param eloSlack     how far below min_elo still counts as meeting it
     */
    private boolean meets(ModeRating r, Tier tier, boolean estimateOnly, double eloSlack) {
        CoreConfig.TierThreshold t = config.tiers().get(tier);
        if (t == null) return false;

        boolean core = r.getElo() >= t.minElo() - eloSlack
                    && r.getSkillScore() >= t.minSkill();
        if (estimateOnly) return core;
        return core
            && r.getConfidence() >= t.minConfidence()
            && r.getFights() >= t.minFights();
    }
}
