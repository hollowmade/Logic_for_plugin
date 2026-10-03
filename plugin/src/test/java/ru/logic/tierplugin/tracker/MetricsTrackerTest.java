package ru.logic.tierplugin.tracker;

import org.junit.jupiter.api.Test;
import ru.logic.tierplugin.core.FightMetrics;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MetricsTrackerTest {

    /** Minimal fight source: the tracker only needs who fights whom. */
    private static final class FakeFights implements ActiveFights {
        private final Map<UUID, FightRef> byPlayer = new HashMap<>();

        FightRef start(UUID a, UUID b) {
            FightRef f = new FightRef(UUID.randomUUID(), a, b);
            byPlayer.put(a, f);
            byPlayer.put(b, f);
            return f;
        }

        @Override
        public Optional<FightRef> fightOf(UUID player) {
            return Optional.ofNullable(byPlayer.get(player));
        }
    }

    private final FakeFights fights = new FakeFights();
    private final MetricsTracker tracker = new MetricsTracker(fights, new TrackerSettings(1500, 2));
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), outsider = UUID.randomUUID();

    /** Server tick of a time in ms (50 ms per tick). */
    private static int tick(long t) {
        return (int) (t / 50);
    }

    private void swing(UUID player, long t) {
        tracker.swing(player, t, tick(t));
    }

    /** Swing + hit in the same tick, as an attack arrives from the client. */
    private void attack(UUID attacker, UUID victim, long t) {
        tracker.meleeHit(attacker, victim, 4.0, t, tick(t));
        tracker.swing(attacker, t, tick(t));
    }

    @Test
    void accuracyAndDamage() {
        ActiveFights.FightRef f = fights.start(a, b);
        attack(a, b, 0);
        attack(a, b, 600);
        swing(a, 1200); // miss
        swing(a, 1800); // miss
        tracker.damageTaken(b, 4.0);
        tracker.damageTaken(b, 4.0);

        FightMetrics ma = tracker.finish(f).get(a);
        assertEquals(4, ma.swings());
        assertEquals(2, ma.hits());
        assertEquals(2, ma.misses());
        assertEquals(50.0, ma.accuracy(), 1e-9);
        assertEquals(8.0, ma.damageDealt(), 1e-9);
    }

    @Test
    void damageTakenCountsAnySource() {
        ActiveFights.FightRef f = fights.start(a, b);
        tracker.damageTaken(a, 3.0); // e.g. fall damage
        tracker.damageTaken(a, 2.5);
        assertEquals(5.5, tracker.finish(f).get(a).damageTaken(), 1e-9);
    }

    @Test
    void comboBreaksWhenOpponentHitsBack() {
        ActiveFights.FightRef f = fights.start(a, b);
        attack(a, b, 0);
        attack(a, b, 500);
        attack(a, b, 1000);   // streak of 3
        attack(b, a, 1200);   // b hits back → a's combo ends
        attack(a, b, 1500);
        attack(a, b, 2000);   // streak of 2

        FightMetrics ma = tracker.finish(f).get(a);
        assertEquals(3, ma.maxCombo());
        assertEquals(2, ma.combos());
        assertEquals(2.5, ma.avgCombo(), 1e-9);
        assertEquals(2.5, ma.medianCombo(), 1e-9);
    }

    @Test
    void comboBreaksAfterTimeout() {
        ActiveFights.FightRef f = fights.start(a, b);
        attack(a, b, 0);
        attack(a, b, 1000);
        attack(a, b, 5000);   // 4 s gap > 1.5 s timeout → new streak of 1 (not a combo)

        FightMetrics ma = tracker.finish(f).get(a);
        assertEquals(2, ma.maxCombo());
        assertEquals(1, ma.combos());
    }

    @Test
    void medianOfOddCombos() {
        ActiveFights.FightRef f = fights.start(a, b);
        long t = 0;
        for (int len : new int[]{2, 5, 3}) {
            for (int i = 0; i < len; i++) attack(a, b, t += 400);
            attack(b, a, t += 100);
        }
        FightMetrics ma = tracker.finish(f).get(a);
        assertEquals(3.0, ma.medianCombo(), 1e-9);
        assertEquals(10 / 3.0, ma.avgCombo(), 1e-9);
        assertEquals(5, ma.maxCombo());
    }

    @Test
    void cpsAverageAndPeak() {
        ActiveFights.FightRef f = fights.start(a, b);
        for (int i = 0; i < 10; i++) swing(a, i * 100);      // 10 swings in second 0
        for (int i = 0; i < 4; i++) swing(a, 5000 + i * 250); // 4 swings in second 5

        FightMetrics ma = tracker.finish(f).get(a);
        assertEquals(10, ma.maxCps());
        assertEquals(7.0, ma.avgCps(), 1e-9, "14 swings over 2 active seconds");
    }

    @Test
    void ignoresPlayersOutsideTheFightAndThirdParties() {
        ActiveFights.FightRef f = fights.start(a, b);
        swing(outsider, 0);
        tracker.meleeHit(outsider, a, 4, 0, 0);
        tracker.meleeHit(a, outsider, 4, 0, 0);

        Map<UUID, FightMetrics> m = tracker.finish(f);
        assertEquals(FightMetrics.EMPTY, m.get(b));
        assertEquals(0, m.get(a).hits(), "hits on someone outside the fight do not count");
    }

    @Test
    void indirectDamageIsNotAHit() {
        ActiveFights.FightRef f = fights.start(a, b);
        tracker.indirectDamage(a, b, 6.0); // arrow or sweep
        FightMetrics ma = tracker.finish(f).get(a);
        assertEquals(0, ma.hits());
        assertEquals(6.0, ma.damageDealt(), 1e-9);
    }

    @Test
    void snapshotDoesNotEndTheFightAndFinishForgetsIt() {
        ActiveFights.FightRef f = fights.start(a, b);
        attack(a, b, 0);
        attack(a, b, 500);
        assertEquals(2, tracker.snapshot(f).get(a).maxCombo());

        attack(a, b, 1000);
        assertEquals(3, tracker.finish(f).get(a).maxCombo(), "snapshot must not close the running streak");
        assertEquals(FightMetrics.EMPTY, tracker.finish(f).get(a));
    }

    @Test
    void hitWithoutSwingPacketStillCountsAsAnAttack() {
        // Fast clicking: the client skips swing packets while the arm animation is running
        ActiveFights.FightRef f = fights.start(a, b);
        attack(a, b, 0);
        for (int i = 1; i <= 12; i++) tracker.meleeHit(a, b, 2.0, i * 100L, tick(i * 100L));

        FightMetrics ma = tracker.finish(f).get(a);
        assertEquals(13, ma.hits());
        assertEquals(13, ma.swings(), "every hit is an attack");
        assertEquals(100.0, ma.accuracy(), 1e-9);
    }

    @Test
    void swingAndHitInOneTickAreOneAttackInEitherOrder() {
        ActiveFights.FightRef f = fights.start(a, b);
        swing(a, 0);
        tracker.meleeHit(a, b, 4, 0, tick(0));   // swing first, then hit, same tick
        attack(a, b, 500);                        // hit first, then swing, same tick
        FightMetrics ma = tracker.finish(f).get(a);
        assertEquals(2, ma.swings());
        assertEquals(2, ma.hits());
    }

    @Test
    void twoClicksInOneTickWithOneHit() {
        ActiveFights.FightRef f = fights.start(a, b);
        swing(a, 0);
        swing(a, 10);
        tracker.meleeHit(a, b, 4, 10, tick(10));
        FightMetrics ma = tracker.finish(f).get(a);
        assertEquals(2, ma.swings());
        assertEquals(1, ma.hits());
        assertEquals(1, ma.misses());
    }

    @Test
    void fightsAreIsolated() {
        UUID c = UUID.randomUUID(), d = UUID.randomUUID();
        ActiveFights.FightRef f1 = fights.start(a, b);
        ActiveFights.FightRef f2 = fights.start(c, d);
        attack(a, b, 0);
        attack(c, d, 0);
        attack(c, d, 300);
        assertEquals(1, tracker.finish(f1).get(a).hits());
        assertEquals(2, tracker.finish(f2).get(c).hits());
    }
}
