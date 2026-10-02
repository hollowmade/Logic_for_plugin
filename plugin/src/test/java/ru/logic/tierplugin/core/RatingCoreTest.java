package ru.logic.tierplugin.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RatingCoreTest {

    private final CoreConfig config = CoreConfig.defaults();
    private final RatingService service = new RatingService(config);
    private final EloCalculator elo = service.getEloCalculator();

    private ModeRating fresh() {
        return service.newRating(UUID.randomUUID(), Gamemode.SWORD);
    }

    private static MatchInput sword() {
        return new MatchInput(Gamemode.SWORD, 1, 0,
                FightMetrics.basic(20, 10, 120, 60, 6), FightMetrics.basic(10, 20, 60, 120, 3), 30_000);
    }

    // ── Elo ─────────────────────────────────────────────────────

    @Test
    void newRatingUsesConfiguredInitialValues() {
        ModeRating r = fresh();
        assertEquals(1000, r.getElo());
        assertEquals(350, r.getRatingDeviation());
        assertEquals(0, r.getConfidence());
        assertTrue(r.isProvisional());
        assertNull(r.getTier());
    }

    @Test
    void beatingStrongerOpponentGivesMore() {
        double vsStrong = elo.delta(1200, 100, 1500, 100, 1, 1);
        double vsWeak = elo.delta(1200, 100, 900, 100, 1, 1);
        assertTrue(vsStrong > vsWeak, vsStrong + " vs " + vsWeak);
    }

    @Test
    void resultAgainstUncertainOpponentCountsLess() {
        double vsVeteran = elo.delta(1200, 100, 1200, 60, 1, 1);
        double vsNewAccount = elo.delta(1200, 100, 1200, 350, 1, 1);
        assertTrue(vsVeteran > vsNewAccount, vsVeteran + " vs " + vsNewAccount);
    }

    @Test
    void newPlayersMoveFasterThanVeterans() {
        assertEquals(64, elo.kFactor(350), 1e-9);
        assertEquals(16, elo.kFactor(60), 1e-9);
    }

    @Test
    void equalPlayersSwapEqualElo() {
        ModeRating w = fresh(), l = fresh();
        MatchOutcome out = service.process(w, l, sword(), 1);
        assertTrue(out.winner().eloDelta() > 0);
        assertEquals(out.winner().eloDelta(), -out.loser().eloDelta(), 1e-9);
        assertEquals(1, w.getWins());
        assertEquals(1, l.getLosses());
        assertTrue(w.getRatingDeviation() < 350);
    }

    @Test
    void gamemodesAreSeparateLadders() {
        ModeRating sword = fresh();
        ModeRating crystal = service.newRating(sword.getUuid(), Gamemode.CRYSTAL);
        assertThrows(IllegalArgumentException.class, () -> service.process(sword, crystal, sword(), 1));
    }

    // ── Repeat-match protection ─────────────────────────────────

    @Test
    void repeatedMatchesWeighLess() {
        CoreConfig.Elo e = config.elo();
        assertEquals(1.00, e.diminishingFactor(1));
        assertEquals(0.70, e.diminishingFactor(2));
        assertEquals(0.50, e.diminishingFactor(3));
        assertEquals(0.30, e.diminishingFactor(4));
        assertEquals(0.10, e.diminishingFactor(5));
        assertEquals(0.10, e.diminishingFactor(20));
        assertEquals(0.0, e.diminishingFactor(21), "past the daily cap a match is unrated");
    }

    @Test
    void unratedMatchChangesNothing() {
        ModeRating w = fresh(), l = fresh();
        MatchOutcome out = service.process(w, l, sword(), 21);
        assertFalse(out.rated());
        assertEquals(1000, w.getElo());
        assertEquals(0, w.getFights(), "farmed fights must not count toward min_fights");
        assertEquals(0, w.getSkillScore());
    }

    @Test
    void farmingOneOpponentIsCapped() {
        Simulator.FarmReport f = Simulator.farm(config, 100);
        assertEquals(20, f.ratedFights());
        assertTrue(f.eloGained() < f.eloGainedWithoutProtection() / 3,
                "protected " + f.eloGained() + " vs unprotected " + f.eloGainedWithoutProtection());
    }

    // ── Skill ───────────────────────────────────────────────────

    @Test
    void skillScoreStaysInRange() {
        SkillScoreCalculator calc = service.getSkillCalculator();
        assertEquals(100.0, calc.calculate(FightMetrics.basic(50, 0, 1000, 0, 40), Gamemode.SWORD), 1e-9);
        assertEquals(50.0, calc.calculate(FightMetrics.basic(10, 10, 100, 0, 10), Gamemode.CRYSTAL), 1e-9);
    }

    @Test
    void firstFightSetsSkillDirectly() {
        SkillScoreCalculator calc = service.getSkillCalculator();
        assertEquals(70, calc.update(0, 0, 70), 1e-9);
        assertEquals(54, calc.update(50, 5, 70), 1e-9);
    }
}
