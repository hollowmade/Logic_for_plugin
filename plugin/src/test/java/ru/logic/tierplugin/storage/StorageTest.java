package ru.logic.tierplugin.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.logic.tierplugin.core.CoreConfig;
import ru.logic.tierplugin.core.EndReason;
import ru.logic.tierplugin.core.FightMetrics;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.MatchInput;
import ru.logic.tierplugin.core.MatchOutcome;
import ru.logic.tierplugin.core.ModeRating;
import ru.logic.tierplugin.core.RatingService;
import ru.logic.tierplugin.core.Tier;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against a real SQLite file: the same code path as on the server. */
class StorageTest {

    @TempDir Path dir;

    private final RatingService rating = new RatingService(CoreConfig.defaults());
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    private final Clock noon = Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC);
    private Database db;

    /** Opens (or re-opens, simulating a server restart) the database. */
    private Storage open(Clock clock) throws Exception {
        if (db != null) db.close();
        db = new Database(DatabaseSettings.sqlite(new File(dir.toFile(), "test.db")), Logger.getLogger("test"));
        return new Storage(db, clock);
    }

    @AfterEach
    void close() {
        if (db != null) db.close();
    }

    private static MatchInput sword() {
        return new MatchInput(Gamemode.SWORD, 1, 0,
                FightMetrics.basic(20, 10, 120, 60, 6), FightMetrics.basic(10, 20, 60, 120, 3), 30_000);
    }

    @Test
    void dataSurvivesRestart() throws Exception {
        Storage s = open(noon);
        s.touchPlayer(a, "Alice").join();
        s.touchPlayer(b, "Bob").join();
        MatchOutcome out = s.recordMatch(a, b, sword(), rating).join();

        Storage reopened = open(noon);
        ModeRating r = reopened.loadRatings(a).join().get(Gamemode.SWORD);
        assertNotNull(r);
        assertEquals(out.winner().eloAfter(), r.getElo(), 1e-9);
        assertEquals(1, r.getFights());
        assertEquals(1, r.getWins());
        assertEquals(1L, reopened.countMatches().join());
        assertEquals(a, reopened.findPlayer("alice").join().orElseThrow().uuid());
    }

    @Test
    void dailyPairCounterSurvivesRestartAndResetsNextDay() throws Exception {
        Storage s = open(noon);
        assertEquals(1, s.recordMatch(a, b, sword(), rating).join().matchNumberToday());
        assertEquals(2, s.recordMatch(b, a, sword(), rating).join().matchNumberToday(), "order-independent pair");

        Storage reopened = open(noon);
        MatchOutcome third = reopened.recordMatch(a, b, sword(), rating).join();
        assertEquals(3, third.matchNumberToday());
        // 3rd match today → 50 % anti-abuse factor, times the score-quality factor of a 1-0 fight
        assertEquals(0.5 * rating.getEloCalculator().scoreQuality(1, 0), third.weight(), 1e-9);

        Storage tomorrow = open(Clock.offset(noon, Duration.ofDays(1)));
        assertEquals(1, tomorrow.recordMatch(a, b, sword(), rating).join().matchNumberToday());
    }

    // Needs a second gamemode; restore together with CRYSTAL/CART in Gamemode.
    // @Test
    // void counterIsPerGamemode() throws Exception {
    //     Storage s = open(noon);
    //     s.recordMatch(a, b, sword(), rating).join();
    //     MatchInput crystal = new MatchInput(Gamemode.CRYSTAL, 1, 0, FightMetrics.EMPTY, FightMetrics.EMPTY, 1000);
    //     assertEquals(1, s.recordMatch(a, b, crystal, rating).join().matchNumberToday());
    //     assertEquals(2, s.loadRatings(a).join().size());
    // }

    @Test
    void saveAndDeleteRatings() throws Exception {
        Storage s = open(noon);
        ModeRating r = rating.newRating(a, Gamemode.SWORD);
        r.setTier(Tier.HT2);
        r.setProvisional(false);
        s.saveRating(r).join();
        ModeRating loaded = s.loadRatings(a).join().get(Gamemode.SWORD);
        assertEquals(Tier.HT2, loaded.getTier());
        assertFalse(loaded.isProvisional());

        assertEquals(1, s.deleteRatings(a, null).join());
        assertTrue(s.loadRatings(a).join().isEmpty());
    }

    @Test
    void renameUpdatesLookup() throws Exception {
        open(noon).touchPlayer(a, "Old").join();
        Storage s = open(Clock.offset(noon, Duration.ofHours(1)));
        s.touchPlayer(a, "New").join();
        assertTrue(s.findPlayer("Old").join().isEmpty());
        assertEquals("New", s.findPlayer("new").join().orElseThrow().name());
    }

    @Test
    void metricsAreStoredAndSummarised() throws Exception {
        Storage s = open(noon);
        FightMetrics strong = new FightMetrics(20, 15, 22.5, 8.0, 5, 3.5, 3.0, 2, 8.0, 12);
        FightMetrics weak = new FightMetrics(10, 2, 8.0, 22.5, 1, 0, 0, 0, 4.0, 6);
        s.recordMatch(a, b, new MatchInput(Gamemode.SWORD, 1, 0, strong, weak, 30_000), rating).join();
        s.recordMatch(b, a, new MatchInput(Gamemode.SWORD, 1, 0, weak, strong, 30_000), rating).join();

        Storage reopened = open(noon);
        Storage.MetricsSummary m = reopened.metricsSummary(a, Gamemode.SWORD, 20).join().orElseThrow();
        assertEquals(2, m.fights());
        assertEquals(1, m.wins());
        assertEquals(75.0, m.accuracy(), 1e-9);
        assertEquals(22.5, m.avgDamageDealt(), 1e-9);
        assertEquals(5, m.bestCombo());
        assertEquals(3.0, m.avgMedianCombo(), 1e-9);
        assertEquals(12, m.peakCps());

        assertTrue(reopened.metricsSummary(UUID.randomUUID(), Gamemode.SWORD, 20).join().isEmpty());
    }

    @Test
    void summaryUsesOnlyTheMostRecentFights() throws Exception {
        Storage s = open(noon);
        FightMetrics old = FightMetrics.basic(0, 10, 0, 0, 0);
        FightMetrics recent = FightMetrics.basic(10, 0, 20, 0, 4);
        for (int i = 0; i < 3; i++) {
            Storage day = open(Clock.offset(noon, Duration.ofDays(i)));
            day.recordMatch(a, b, new MatchInput(Gamemode.SWORD, 1, 0, i == 0 ? old : recent,
                    FightMetrics.EMPTY, 1000), rating).join();
        }
        Storage.MetricsSummary m = open(noon).metricsSummary(a, Gamemode.SWORD, 2).join().orElseThrow();
        assertEquals(2, m.fights());
        assertEquals(100.0, m.accuracy(), 1e-9, "the oldest 0 % fight is outside the window");
    }

    @Test
    void stageOneMetricsTableIsMigrated() throws Exception {
        File file = new File(dir.toFile(), "test.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE metrics (match_id BIGINT NOT NULL, uuid VARCHAR(36) NOT NULL,
                    hits INT NOT NULL, misses INT NOT NULL, damage_dealt INT NOT NULL,
                    damage_taken INT NOT NULL, max_combo INT NOT NULL, fight_skill DOUBLE NOT NULL,
                    PRIMARY KEY (match_id, uuid))""");
        }
        Storage s = open(noon);
        s.recordMatch(a, b, sword(), rating).join();
        assertEquals(1, s.metricsSummary(a, Gamemode.SWORD, 20).join().orElseThrow().fights());
    }

    private static MatchInput leave() {
        return new MatchInput(Gamemode.SWORD, 0, 0,
                FightMetrics.basic(2, 1, 8, 0, 2), FightMetrics.EMPTY, 20_000, EndReason.LEAVE);
    }

    @Test
    void leavesAreCountedWithinTheWindow() throws Exception {
        UUID c = UUID.randomUUID();
        assertEquals(1, open(noon).recordMatch(a, b, leave(), rating).join().leaveNumber());
        // A different opponent: the count is per leaver, not per pair
        assertEquals(2, open(Clock.offset(noon, Duration.ofHours(2))).recordMatch(c, b, leave(), rating).join().leaveNumber());

        Storage s = open(Clock.offset(noon, Duration.ofHours(3)));
        assertEquals(2, s.leaveTimes(b, noon.millis() - 1).join().size());
        assertTrue(s.leaveTimes(a, 0).join().isEmpty(), "the winner has no leaves");

        // 25 h after the first leave only the second one is inside the 24 h window
        MatchOutcome later = open(Clock.offset(noon, Duration.ofHours(25))).recordMatch(a, b, leave(), rating).join();
        assertEquals(2, later.leaveNumber());
    }

    @Test
    void summaryIgnoresLeaves() throws Exception {
        Storage s = open(noon);
        s.recordMatch(a, b, leave(), rating).join();
        assertTrue(s.metricsSummary(a, Gamemode.SWORD, 20).join().isEmpty());
        s.recordMatch(a, b, sword(), rating).join();
        assertEquals(1, s.metricsSummary(a, Gamemode.SWORD, 20).join().orElseThrow().fights());
    }

    @Test
    void matchesTableWithoutEndReasonIsMigrated() throws Exception {
        File file = new File(dir.toFile(), "test.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
             Statement st = c.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE matches (id INTEGER PRIMARY KEY AUTOINCREMENT, gamemode VARCHAR(16) NOT NULL,
                    pair_key VARCHAR(73) NOT NULL, winner_uuid VARCHAR(36) NOT NULL, loser_uuid VARCHAR(36) NOT NULL,
                    winner_score INT NOT NULL, loser_score INT NOT NULL, duration_ms BIGINT NOT NULL,
                    match_number INT NOT NULL, weight DOUBLE NOT NULL,
                    winner_elo_before DOUBLE NOT NULL, winner_elo_after DOUBLE NOT NULL,
                    loser_elo_before DOUBLE NOT NULL, loser_elo_after DOUBLE NOT NULL, played_at BIGINT NOT NULL)""");
            st.executeUpdate("INSERT INTO matches (gamemode, pair_key, winner_uuid, loser_uuid, winner_score, loser_score,"
                    + " duration_ms, match_number, weight, winner_elo_before, winner_elo_after, loser_elo_before,"
                    + " loser_elo_after, played_at) VALUES ('SWORD', 'x', '" + a + "', '" + b + "', 1, 0, 1000, 1, 1,"
                    + " 1000, 1020, 1000, 980, " + noon.millis() + ")");
        }
        Storage s = open(noon);
        assertTrue(s.leaveTimes(b, 0).join().isEmpty(), "old matches become KILL");
        assertEquals(1, s.recordMatch(a, b, leave(), rating).join().leaveNumber());
    }

    @Test
    void sqlDialectsDiffer() {
        List<String> cols = List.of("uuid", "gamemode", "elo");
        List<String> keys = List.of("uuid", "gamemode");
        assertEquals("INSERT INTO ratings (uuid, gamemode, elo) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid, gamemode) DO UPDATE SET elo = excluded.elo",
                SqlDialect.SQLITE.upsert("ratings", cols, keys));
        assertEquals("INSERT INTO ratings (uuid, gamemode, elo) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE elo = VALUES(elo)",
                SqlDialect.MYSQL.upsert("ratings", cols, keys));
    }
}
