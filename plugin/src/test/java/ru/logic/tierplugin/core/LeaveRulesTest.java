package ru.logic.tierplugin.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A player who leaves mid-fight: winner credit, escalating penalty, cooldown. */
class LeaveRulesTest {

    private final CoreConfig config = CoreConfig.defaults();
    private final RatingService service = new RatingService(config);
    private final EloCalculator elo = service.getEloCalculator();

    private ModeRating fresh() {
        return service.newRating(UUID.randomUUID(), Gamemode.SWORD);
    }

    private static MatchInput leave(int winnerHits, long durationMs) {
        return new MatchInput(Gamemode.SWORD, 0, 0,
                FightMetrics.basic(winnerHits, 2, winnerHits * 4.0, 0, winnerHits),
                FightMetrics.basic(0, 3, 0, winnerHits * 4.0, 0),
                durationMs, EndReason.LEAVE);
    }

    @Test
    void penaltyEscalatesWithEachLeave() {
        // Fresh players are in placement: Elo moves x1.5
        double base = -elo.delta(1000, 350, 1000, 350, 0, config.elo().placementKMultiplier());
        for (int n = 1; n <= 4; n++) {
            ModeRating w = fresh(), l = fresh();
            service.process(w, l, leave(2, 20_000), 1, n);
            double expected = base * new double[]{1.0, 1.5, 2.0, 2.0}[n - 1];
            assertEquals(expected, 1000 - l.getElo(), 1e-9, "leave #" + n);
        }
    }

    @Test
    void realFightCreditsWinnerLikeAKill() {
        ModeRating w = fresh(), l = fresh();
        MatchOutcome out = service.process(w, l, leave(1, 3_000), 1, 1);
        assertTrue(out.rated());
        assertTrue(out.isLeave());

        ModeRating kw = fresh(), kl = fresh();
        service.process(kw, kl, new MatchInput(Gamemode.SWORD, 1, 0,
                FightMetrics.basic(1, 0, 4, 0, 1), FightMetrics.EMPTY, 3_000), 1);
        assertEquals(kw.getElo(), w.getElo(), 1e-9, "a leave pays the winner exactly like a 1-0 kill");
        assertEquals(1, w.getWins());
    }

    @Test
    void emptyShortFightGivesWinnerNothing() {
        ModeRating w = fresh(), l = fresh();
        MatchOutcome out = service.process(w, l, leave(0, 5_000), 1, 1);
        assertFalse(out.rated());
        assertEquals(1000, w.getElo());
        assertEquals(0, w.getFights());
        assertTrue(l.getElo() < 1000, "the leaver is still penalised");
        assertEquals(1, l.getLosses());
    }

    @Test
    void longFightWithoutHitsStillCounts() {
        ModeRating w = fresh(), l = fresh();
        assertTrue(service.process(w, l, leave(0, 15_000), 1, 1).rated());
    }

    @Test
    void penaltyIgnoresRepeatMatchProtection() {
        ModeRating w = fresh(), l = fresh();
        MatchOutcome out = service.process(w, l, leave(3, 30_000), 21, 1); // past the daily pair cap
        assertFalse(out.rated(), "winner gets nothing past the cap");
        assertEquals(1.0, out.loserWeight());
        assertTrue(l.getElo() < 1000);
    }

    @Test
    void leaveDoesNotTouchSkill() {
        ModeRating w = fresh(), l = fresh();
        w.setSkillScore(40);
        w.setFights(5);
        l.setSkillScore(60);
        l.setFights(5);
        service.process(w, l, leave(5, 30_000), 1, 1);
        assertEquals(40, w.getSkillScore());
        assertEquals(60, l.getSkillScore());
        assertEquals(6, l.getFights());
    }

    @Test
    void killAtZeroZeroIsNotHeavierThanOneZero() {
        assertEquals(elo.scoreQuality(1, 0),
                service.process(fresh(), fresh(), leave(1, 20_000), 1, 1).weight(), 1e-9);
    }

    @Test
    void cooldownAfterThirdLeave() {
        CoreConfig.Leave rules = config.leave();
        long now = 10 * 3_600_000L;
        long min = 60_000;
        assertEquals(0, rules.blockedUntil(List.of(now - 5 * min, now - 2 * min), now), "2 leaves: not blocked");
        assertEquals(now + 8 * min,
                rules.blockedUntil(List.of(now - 60 * min, now - 30 * min, now - 2 * min), now),
                "blocked for 10 min after the 3rd leave");
        assertEquals(0, rules.blockedUntil(List.of(now - 60 * min, now - 30 * min, now - 11 * min), now),
                "cooldown already over");
    }
}
