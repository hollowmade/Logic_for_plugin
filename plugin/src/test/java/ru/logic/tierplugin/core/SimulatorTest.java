package ru.logic.tierplugin.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Thousands of virtual matches: the formulas must rank players sensibly. */
class SimulatorTest {

    @Test
    void ladderRecoversTrueSkillOrder() {
        Simulator.Report r = Simulator.run(CoreConfig.defaults(), 200, 30, 6, 42);
        System.out.print(Simulator.format(r));

        assertEquals(200 * 30 * 6, r.matches());
        assertTrue(r.spearman() > 0.85, "rank correlation too low: " + r.spearman());
        assertEquals(0, r.provisional(), "everyone played far more than placement_fights");
        assertTrue(r.tierCounts().size() >= 5, "tiers should spread across the ladder: " + r.tierCounts());
    }

    @Test
    void sameSeedSameResult() {
        Simulator.Report a = Simulator.run(CoreConfig.defaults(), 50, 5, 4, 7);
        Simulator.Report b = Simulator.run(CoreConfig.defaults(), 50, 5, 4, 7);
        assertEquals(a.spearman(), b.spearman());
    }
}
