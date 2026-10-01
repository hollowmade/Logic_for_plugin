package ru.logic.tierplugin.core;

/**
 * Simplified Glicko-2-inspired rating update.
 * <p>
 * Full Glicko-2 will be implemented in a later iteration;
 * this class provides a correct, abuse-resistant foundation.
 */
public class EloCalculator {

    private final CoreConfig config;

    public EloCalculator(CoreConfig config) {
        this.config = config;
    }

    /**
     * Computes the expected score for player A against player B.
     *
     * @param ratingA Elo of player A
     * @param ratingB Elo of player B
     * @return expected score in [0, 1]
     */
    public double expectedScore(double ratingA, double ratingB) {
        return 1.0 / (1.0 + Math.pow(10.0, (ratingB - ratingA) / 400.0));
    }

    /**
     * Calculates the K-factor based on rating deviation (confidence proxy).
     * Higher RD → higher K (more volatile), lower RD → lower K (stable).
     */
    public double kFactor(double rd) {
        // RD of 350 (new player) → K≈64; RD of 60 (stable) → K≈16
        return Math.max(16, Math.min(64, rd * 64.0 / 350.0));
    }

    /**
     * Calculates the rating change for a player.
     *
     * @param rating          current rating
     * @param rd              current rating deviation
     * @param expectedScore   expected outcome [0,1]
     * @param actualScore     actual outcome [0,1] (1=win, 0=loss, 0.5=draw)
     * @param matchQuality    multiplier in [0,1] accounting for opponent strength & anti-abuse
     * @return new rating
     */
    public double newRating(double rating, double rd, double expectedScore,
                            double actualScore, double matchQuality) {
        double k = kFactor(rd);
        double delta = k * matchQuality * (actualScore - expectedScore);
        return rating + delta;
    }

    /**
     * Reduces Rating Deviation after a rated match (simplified Glicko step).
     *
     * @param rd current RD
     * @return reduced RD (floored at 60)
     */
    public double reduceRd(double rd) {
        return Math.max(60.0, rd * 0.97);
    }

    /**
     * Computes match quality multiplier from the score difference.
     *
     * @param winnerScore   e.g. kills by winner
     * @param loserScore    e.g. kills by loser
     * @param diminishingFactor anti-abuse diminishing returns factor [0,1]
     * @return quality multiplier in [0,1]
     */
    public double matchQuality(int winnerScore, int loserScore, double diminishingFactor) {
        int cap = config.scoreDiffCap();
        int diff = Math.min(Math.abs(winnerScore - loserScore), cap);
        // Closer fight = higher quality signal
        double scoreFactor = 1.0 - (diff / (double) cap) * 0.5;
        return scoreFactor * diminishingFactor;
    }
}
