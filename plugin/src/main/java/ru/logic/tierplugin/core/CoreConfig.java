package ru.logic.tierplugin.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of every tunable used by the core logic.
 * <p>
 * Built from config.yml by the adapter layer, so nothing in {@code core}
 * depends on Bukkit and the logic can be tested in plain JUnit.
 */
public record CoreConfig(
        Map<Tier, TierThreshold> tiers,
        Elo elo,
        Skill skill,
        TierRules tierRules,
        int scoreDiffCap,
        Leave leave
) {

    public CoreConfig {
        tiers = Map.copyOf(tiers);
    }

    /** Requirements a rating must meet to hold a tier. */
    public record TierThreshold(double minElo, double minSkill, double minConfidence, int minFights) {}

    /**
     * Elo parameters and anti-abuse settings.
     *
     * @param minRd              RD never drops below this; it is the "fully confident" point
     * @param rdDecayPerMatch    RD is multiplied by this after every rated match
     * @param diminishingReturns multipliers for the 1st, 2nd, 3rd… match against
     *                           the same opponent in a day; the last value applies to all further matches
     * @param placementKMultiplier Elo moves this many times faster during placement fights,
     *                           so a new player reaches their level sooner
     */
    public record Elo(double initialRating, double initialRd, double initialVolatility,
                      double minRd, double rdDecayPerMatch,
                      double[] diminishingReturns, int maxDailyRatedMatchesPerOpponent,
                      double placementKMultiplier) {

        public Elo {
            if (diminishingReturns.length == 0) {
                throw new IllegalArgumentException("diminishing_returns must not be empty");
            }
            if (minRd <= 0 || minRd >= initialRd) {
                throw new IllegalArgumentException("min_rd must be in (0, initial_rd)");
            }
            diminishingReturns = diminishingReturns.clone();
        }

        /** @return Elo multiplier for the {@code matchNumber}-th (1-based) match of a pair today */
        public double diminishingFactor(int matchNumber) {
            if (matchNumber > maxDailyRatedMatchesPerOpponent) return 0.0;
            int idx = Math.min(matchNumber, diminishingReturns.length) - 1;
            return diminishingReturns[idx];
        }
    }

    /** Skill Score combo normalisation, per-gamemode weights and EMA smoothing. */
    public record Skill(int comboCap, double emaAlpha, Map<Gamemode, Weights> weights) {

        public Skill {
            weights = Map.copyOf(weights);
        }

        public Weights weightsFor(Gamemode gamemode) {
            return weights.getOrDefault(gamemode, Weights.DEFAULT);
        }
    }

    public record Weights(double accuracy, double damage, double combo) {
        public static final Weights DEFAULT = new Weights(0.40, 0.35, 0.25);
    }

    /**
     * @param placementFights   until this many fights the tier is a temporary estimate
     * @param demotionBufferElo a fixed tier is kept until Elo falls this far below its threshold
     */
    public record TierRules(int placementFights, double demotionBufferElo) {}

    /**
     * Rules for a player who leaves mid-fight.
     *
     * @param penaltyMultipliers  Elo loss multiplier for the 1st, 2nd, 3rd... leave in the window;
     *                            the last value applies to all further leaves
     * @param realFightMillis     the winner is credited for a leave only if a hit landed or
     *                            the fight lasted at least this long
     * @param windowHours         leaves are counted over this many hours
     * @param cooldownAfterLeaves this many leaves in the window block starting new fights...
     * @param cooldownMinutes     ...for this long after the latest leave
     */
    public record Leave(double[] penaltyMultipliers, long realFightMillis, int windowHours,
                        int cooldownAfterLeaves, int cooldownMinutes) {

        public Leave {
            if (penaltyMultipliers.length == 0) {
                throw new IllegalArgumentException("leave.penalty_multipliers must not be empty");
            }
            penaltyMultipliers = penaltyMultipliers.clone();
        }

        /** @param leaveNumber 1-based count of leaves in the window, this one included */
        public double penaltyFor(int leaveNumber) {
            int idx = Math.max(1, Math.min(leaveNumber, penaltyMultipliers.length)) - 1;
            return penaltyMultipliers[idx];
        }

        public long windowMillis() {
            return windowHours * 3_600_000L;
        }

        /**
         * @param leaveTimes times of the player's leaves within the window, any order
         * @return until when the player may not start fights, or 0 if not blocked
         */
        public long blockedUntil(List<Long> leaveTimes, long now) {
            if (cooldownAfterLeaves <= 0 || leaveTimes.size() < cooldownAfterLeaves) return 0;
            long latest = leaveTimes.stream().mapToLong(Long::longValue).max().orElse(0);
            long until = latest + cooldownMinutes * 60_000L;
            return until > now ? until : 0;
        }
    }

    /** Defaults matching the shipped config.yml; handy for tests. */
    public static CoreConfig defaults() {
        Map<Tier, TierThreshold> tiers = new EnumMap<>(Tier.class);
        tiers.put(Tier.LT5, new TierThreshold(800, 0, 0.20, 10));
        tiers.put(Tier.HT5, new TierThreshold(950, 20, 0.30, 15));
        tiers.put(Tier.LT4, new TierThreshold(1050, 30, 0.40, 20));
        tiers.put(Tier.HT4, new TierThreshold(1150, 40, 0.50, 25));
        tiers.put(Tier.LT3, new TierThreshold(1250, 50, 0.55, 30));
        tiers.put(Tier.HT3, new TierThreshold(1380, 60, 0.60, 40));
        tiers.put(Tier.LT2, new TierThreshold(1520, 68, 0.65, 50));
        tiers.put(Tier.HT2, new TierThreshold(1670, 75, 0.70, 65));
        tiers.put(Tier.LT1, new TierThreshold(1830, 82, 0.75, 80));
        tiers.put(Tier.HT1, new TierThreshold(2000, 90, 0.80, 100));

        Map<Gamemode, Weights> weights = new EnumMap<>(Gamemode.class);
        // weights.put(Gamemode.CRYSTAL, new Weights(0.40, 0.35, 0.25)); // disabled, see Gamemode
        weights.put(Gamemode.SWORD, new Weights(0.35, 0.35, 0.30));
        // weights.put(Gamemode.CART, new Weights(0.40, 0.30, 0.30));    // disabled, see Gamemode

        return new CoreConfig(
                tiers,
                new Elo(1000, 350, 0.06, 60, 0.97, new double[]{1.00, 0.70, 0.50, 0.30, 0.10}, 20, 1.5),
                new Skill(8, 0.2, weights),
                new TierRules(10, 30),
                15,
                new Leave(new double[]{1.0, 1.5, 2.0}, 15_000, 24, 3, 10));
    }
}
