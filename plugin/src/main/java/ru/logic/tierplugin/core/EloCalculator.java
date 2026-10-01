package ru.logic.tierplugin.core;

/**
 * Elo update with Glicko-style weighting by opponent reliability.
 * <ul>
 *   <li>Opponent strength enters through the expected score: beating a
 *       higher-rated player yields more than beating a weaker one.</li>
 *   <li>Opponent reliability enters through {@code g(RD)}: a result against
 *       a fresh account (high RD) carries less weight than one against an
 *       established player, which blunts boosting with new alts.</li>
 *   <li>Own K-factor scales with own RD: newcomers move fast, veterans are stable.</li>
 * </ul>
 */
public class EloCalculator {

    private static final double Q = Math.log(10) / 400.0;

    private final CoreConfig config;

    public EloCalculator(CoreConfig config) {
        this.config = config;
    }

    /** Glicko attenuation factor: 1.0 for a perfectly known opponent, lower for uncertain ones. */
    public double g(double opponentRd) {
        return 1.0 / Math.sqrt(1.0 + 3.0 * Q * Q * opponentRd * opponentRd / (Math.PI * Math.PI));
    }

    /**
     * Expected score of a player against an opponent, attenuated by the opponent's RD.
     *
     * @return value in (0, 1)
     */
    public double expectedScore(double rating, double opponentRating, double opponentRd) {
        return 1.0 / (1.0 + Math.pow(10.0, -g(opponentRd) * (rating - opponentRating) / 400.0));
    }

    /**
     * K-factor from own rating deviation.
     * RD of initial_rd (new player) → K=64; RD at min_rd (stable) → K=16.
     */
    public double kFactor(double rd) {
        CoreConfig.Elo e = config.elo();
        double t = (rd - e.minRd()) / (e.initialRd() - e.minRd());
        return 16 + 48 * Math.max(0, Math.min(1, t));
    }

    /**
     * Rating change for one side of a match.
     *
     * @param actualScore 1 = win, 0 = loss, 0.5 = draw
     * @param weight      anti-abuse × match-quality multiplier in [0, 1]
     */
    public double delta(double rating, double rd, double opponentRating, double opponentRd,
                        double actualScore, double weight) {
        double expected = expectedScore(rating, opponentRating, opponentRd);
        return kFactor(rd) * weight * g(opponentRd) * (actualScore - expected);
    }

    /** RD after a rated match: shrinks geometrically, floored at min_rd. */
    public double reduceRd(double rd) {
        CoreConfig.Elo e = config.elo();
        return Math.max(e.minRd(), rd * e.rdDecayPerMatch());
    }

    /** Confidence 0–1: 0 at initial RD, 1 at min RD. */
    public double confidence(double rd) {
        CoreConfig.Elo e = config.elo();
        double c = (e.initialRd() - rd) / (e.initialRd() - e.minRd());
        return Math.max(0, Math.min(1, c));
    }

    /**
     * Match quality multiplier from the score difference: a 1-0 thriller says
     * more about relative skill than a 10-0 stomp.
     *
     * @return value in [0.5, 1]
     */
    public double scoreQuality(int winnerScore, int loserScore) {
        int cap = Math.max(1, config.scoreDiffCap());
        int diff = Math.min(Math.abs(winnerScore - loserScore), cap);
        return 1.0 - (diff / (double) cap) * 0.5;
    }
}
