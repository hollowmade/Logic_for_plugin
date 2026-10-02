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

    /** Applies a match decided by a kill. */
    public MatchOutcome process(ModeRating winner, ModeRating loser, MatchInput in, int matchNumberToday) {
        return process(winner, loser, in, matchNumberToday, 0);
    }

    /**
     * Applies a match to both ratings in place.
     * <ul>
     *   <li><b>Kill</b>: both sides move by the same weight (repeat-match factor x score quality).</li>
     *   <li><b>Leave</b>: the winner is credited like a normal win, but only for a real fight
     *       (a hit landed or it lasted {@code real_fight_seconds}); this stops alts from feeding
     *       wins by joining and leaving. The leaver loses Elo x an escalating penalty, with no
     *       repeat-match discount. Skill is left untouched: a cut-off fight says little.</li>
     * </ul>
     *
     * @param matchNumberToday 1-based: 1 for the pair's first match today in this gamemode
     * @param leaveNumber      for a LEAVE: the loser's leaves within the window, this one included
     */
    public MatchOutcome process(ModeRating winner, ModeRating loser, MatchInput in,
                                int matchNumberToday, int leaveNumber) {
        if (winner.getGamemode() != in.gamemode() || loser.getGamemode() != in.gamemode()) {
            throw new IllegalArgumentException("ratings and match must share a gamemode");
        }
        boolean leave = in.endReason() == EndReason.LEAVE;

        // A decided fight is at least 1-0, also when it ended by a leave at 0-0
        double weight = config.elo().diminishingFactor(matchNumberToday)
                      * elo.scoreQuality(Math.max(1, in.winnerScore()), in.loserScore());
        if (leave && !isRealFight(in)) weight = 0;
        double loserWeight = leave ? config.leave().penaltyFor(leaveNumber) : weight;

        double wElo = winner.getElo(), lElo = loser.getElo();
        double wRd = winner.getRatingDeviation(), lRd = loser.getRatingDeviation();
        Tier wTier = winner.getTier(), lTier = loser.getTier();
        double wFight = skill.calculate(in.winnerMetrics(), in.gamemode());
        double lFight = skill.calculate(in.loserMetrics(), in.gamemode());

        // Past the daily pair cap a kill is recorded but changes nothing,
        // so farming one opponent cannot pile up fights, Elo or skill.
        if (weight > 0) {
            winner.setElo(wElo + elo.delta(wElo, wRd, lElo, lRd, 1.0, weight));
            applyCommon(winner, leave ? null : wFight);
            winner.setWins(winner.getWins() + 1);
            tiers.evaluate(winner);
        }
        if (loserWeight > 0) {
            loser.setElo(lElo + elo.delta(lElo, lRd, wElo, wRd, 0.0, loserWeight));
            applyCommon(loser, leave ? null : lFight);
            loser.setLosses(loser.getLosses() + 1);
            tiers.evaluate(loser);
        }

        return new MatchOutcome(matchNumberToday, weight, loserWeight,
                in.endReason(), leave ? leaveNumber : 0,
                side(winner, wElo, wTier, wFight),
                side(loser, lElo, lTier, lFight));
    }

    /** A hit landed, or the fight lasted long enough to count. */
    public boolean isRealFight(MatchInput in) {
        return in.anyHits() || in.durationMillis() >= config.leave().realFightMillis();
    }

    /** @param fightSkill this fight's skill score, or null to keep the running score as is */
    private void applyCommon(ModeRating r, Double fightSkill) {
        if (fightSkill != null) r.setSkillScore(skill.update(r.getSkillScore(), r.getFights(), fightSkill));
        r.setRatingDeviation(elo.reduceRd(r.getRatingDeviation()));
        r.setConfidence(elo.confidence(r.getRatingDeviation()));
        r.setFights(r.getFights() + 1);
    }

    private static MatchOutcome.Side side(ModeRating r, double eloBefore, Tier tierBefore, double fightSkill) {
        return new MatchOutcome.Side(eloBefore, r.getElo(), tierBefore, r.getTier(), r.isProvisional(), fightSkill);
    }
}
