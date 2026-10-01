package ru.logic.tierplugin.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TierEngineTest {

    private final TierEngine engine = new TierEngine(CoreConfig.defaults());

    private static ModeRating rating(double elo, double skill, double confidence, int fights) {
        ModeRating r = new ModeRating(UUID.randomUUID(), Gamemode.SWORD, elo, 100, 0.06);
        r.setSkillScore(skill);
        r.setConfidence(confidence);
        r.setFights(fights);
        return r;
    }

    @Test
    void placementGivesTemporaryTierFromEloAndSkill() {
        ModeRating r = rating(1400, 65, 0.1, 3);
        engine.evaluate(r);
        assertEquals(Tier.HT3, r.getTier());
        assertTrue(r.isProvisional());
    }

    @Test
    void newcomerGetsLowestTemporaryTier() {
        ModeRating r = rating(1000, 0, 0, 0);
        engine.evaluate(r);
        assertEquals(Tier.LT5, r.getTier());
        assertTrue(r.isProvisional());
    }

    @Test
    void afterPlacementAllThresholdsApply() {
        // Elo and skill say HT3, but 12 fights and 0.25 confidence only qualify for LT5
        ModeRating r = rating(1400, 65, 0.25, 12);
        engine.evaluate(r);
        assertEquals(Tier.LT5, r.getTier());
        assertFalse(r.isProvisional());
    }

    @Test
    void highestSatisfiedTierWins() {
        ModeRating r = rating(1400, 65, 0.62, 70);
        engine.evaluate(r);
        assertEquals(Tier.HT3, r.getTier());
    }

    @Test
    void fixedTierSurvivesSmallDip() {
        ModeRating r = rating(1400, 65, 0.62, 70);
        engine.evaluate(r);
        r.setElo(1380 - 20); // below HT3 min_elo, inside the 30 buffer
        engine.evaluate(r);
        assertEquals(Tier.HT3, r.getTier());
    }

    @Test
    void fixedTierDropsBeyondBuffer() {
        ModeRating r = rating(1400, 65, 0.62, 70);
        engine.evaluate(r);
        r.setElo(1380 - 31);
        engine.evaluate(r);
        assertEquals(Tier.LT3, r.getTier());
    }

    @Test
    void promotionIsImmediate() {
        ModeRating r = rating(1400, 65, 0.62, 70);
        engine.evaluate(r);
        r.setElo(1530);
        r.setSkillScore(70);
        r.setConfidence(0.66);
        r.setFights(80);
        engine.evaluate(r);
        assertEquals(Tier.LT2, r.getTier());
    }
}
