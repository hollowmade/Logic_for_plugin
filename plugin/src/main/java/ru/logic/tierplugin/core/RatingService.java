package ru.logic.tierplugin.core;

import java.util.UUID;

/**
 * Entry point of the rating core: applies one finished match to both players.
 * Pure logic — the caller loads the ratings, supplies how many times the pair
 * already played today, and persists the result.
 */
public class RatingService {

    private final CoreConfig config;
    private final EloCalculator elo;
    private final SkillScoreCalculator skill;
    private final TierEngine tiers;

    public RatingService(CoreConfig config) {
        this.config = config;
        this.elo = new EloCalculator(config);
        this.skill = new SkillScoreCalculator(config);
        this.tiers = new TierEngine(config);
    }

    public CoreConfig getConfig() { return config; }
    public EloCalculator getEloCalculator() { return elo; }
    public SkillScoreCalculator getSkillCalculator() { return skill; }
    public TierEngine getTierEngine() { return tiers; }

    /** Creates a fresh rating with the configured initial Elo / RD / volatility. */
    public ModeRating newRating(UUID uuid, Gamemode gamemode) {
        CoreConfig.Elo e = config.elo();
        ModeRating r = new ModeRating(uuid, gamemode, e.initialRating(), e.initialRd(), e.initialVolatility());
        r.setConfidence(elo.confidence(r.getRatingDeviation()));
        return r;
    }

    /**
     * Applies a match to both ratings in place.
     *
     * @param matchNumberToday 1-based: 1 for the pair's first match today in this gamemode
     */
    public MatchOutcome process(ModeRating winner, ModeRating loser, MatchInput in, int matchNumberToday) {
        if (winner.getGamemode() != in.gamemode() || loser.getGamemode() != in.gamemode()) {
            throw new IllegalArgumentException("ratings and match must share a gamemode");
        }

        double weight = config.elo().diminishingFactor(matchNumberToday)
                      * elo.scoreQuality(in.winnerScore(), in.loserScore());

        double wElo = winner.getElo(), lElo = loser.getElo();
        Tier wTier = winner.getTier(), lTier = loser.getTier();
        double wFight = skill.calculate(in.winnerMetrics(), in.gamemode());
        double lFight = skill.calculate(in.loserMetrics(), in.gamemode());

        // Past the daily pair cap the match is recorded but changes nothing,
        // so farming one opponent cannot pile up fights, Elo or skill.
        if (weight > 0) {
            double wRd = winner.getRatingDeviation(), lRd = loser.getRatingDeviation();
            winner.setElo(wElo + elo.delta(wElo, wRd, lElo, lRd, 1.0, weight));
            loser.setElo(lElo + elo.delta(lElo, lRd, wElo, wRd, 0.0, weight));

            applyCommon(winner, wFight);
            applyCommon(loser, lFight);
            winner.setWins(winner.getWins() + 1);
            loser.setLosses(loser.getLosses() + 1);

            tiers.evaluate(winner);
            tiers.evaluate(loser);
        }

        return new MatchOutcome(matchNumberToday, weight,
                side(winner, wElo, wTier, wFight),
                side(loser, lElo, lTier, lFight));
    }

    private void applyCommon(ModeRating r, double fightSkill) {
        r.setSkillScore(skill.update(r.getSkillScore(), r.getFights(), fightSkill));
        r.setRatingDeviation(elo.reduceRd(r.getRatingDeviation()));
        r.setConfidence(elo.confidence(r.getRatingDeviation()));
        r.setFights(r.getFights() + 1);
    }

    private static MatchOutcome.Side side(ModeRating r, double eloBefore, Tier tierBefore, double fightSkill) {
        return new MatchOutcome.Side(eloBefore, r.getElo(), tierBefore, r.getTier(), r.isProvisional(), fightSkill);
    }
}
