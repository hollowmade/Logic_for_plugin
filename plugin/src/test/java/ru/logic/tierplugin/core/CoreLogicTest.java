package ru.logic.tierplugin.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CoreLogicTest {

    private final CoreConfig config = CoreConfig.defaults();
    private final TierEngine engine = new TierEngine(config);

    @Test
    void newProfileUsesConfiguredInitialValues() {
        PlayerProfile p = engine.newProfile(UUID.randomUUID(), "Steve");
        assertEquals(1000, p.getEloRating());
        assertEquals(350, p.getRatingDeviation());
        assertTrue(p.isProvisional());
        assertNull(p.getTier());
    }

    @Test
    void freshPlayerHasNoTier() {
        PlayerProfile p = engine.newProfile(UUID.randomUUID(), "Steve");
        engine.evaluate(p);
        assertNull(p.getTier());
        assertTrue(p.isProvisional());
    }

    @Test
    void highestSatisfiedTierWins() {
        PlayerProfile p = engine.newProfile(UUID.randomUUID(), "Steve");
        p.setEloRating(1400);
        p.setSkillScore(65);
        p.setConfidence(0.62);
        p.setTotalFights(70);
        engine.evaluate(p);
        assertEquals(Tier.HT3, p.getTier());
        assertFalse(p.isProvisional());
    }

    @Test
    void lowConfidenceKeepsTierProvisional() {
        PlayerProfile p = engine.newProfile(UUID.randomUUID(), "Steve");
        p.setEloRating(900);
        p.setConfidence(0.25);
        p.setTotalFights(12);
        engine.evaluate(p);
        assertEquals(Tier.LT5, p.getTier());
        assertTrue(p.isProvisional());
    }

    @Test
    void diminishingReturnsForRepeatedOpponent() {
        CoreConfig.Elo elo = config.elo();
        assertEquals(1.00, elo.diminishingFactor(1));
        assertEquals(0.70, elo.diminishingFactor(2));
        assertEquals(0.10, elo.diminishingFactor(5));
        assertEquals(0.10, elo.diminishingFactor(19));
        assertEquals(0.0, elo.diminishingFactor(21), "past the daily cap a match is unrated");
    }

    @Test
    void winnerGainsWhatLoserLosesAtEqualRating() {
        EloCalculator elo = engine.getEloCalculator();
        double exp = elo.expectedScore(1000, 1000);
        double quality = elo.matchQuality(1, 0, 1.0);
        double gain = elo.newRating(1000, 350, exp, 1.0, quality) - 1000;
        double loss = 1000 - elo.newRating(1000, 350, 1 - exp, 0.0, quality);
        assertTrue(gain > 0);
        assertEquals(gain, loss, 1e-9);
    }

    @Test
    void skillScoreStaysInRange() {
        SkillScoreCalculator calc = engine.getSkillCalculator();
        assertEquals(100.0, calc.calculate(50, 0, 1000, 40, Gamemode.SWORD), 1e-9);
        double mid = calc.calculate(10, 10, 100, 10, Gamemode.CRYSTAL);
        assertEquals(50.0, mid, 1e-9);
    }
}
