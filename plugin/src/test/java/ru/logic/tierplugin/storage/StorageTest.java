package ru.logic.tierplugin.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.logic.tierplugin.core.CoreConfig;
import ru.logic.tierplugin.core.FightMetrics;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.MatchInput;
import ru.logic.tierplugin.core.MatchOutcome;
import ru.logic.tierplugin.core.ModeRating;
import ru.logic.tierplugin.core.RatingService;
import ru.logic.tierplugin.core.Tier;

import java.io.File;
import java.nio.file.Path;
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
                new FightMetrics(20, 10, 120, 60, 6), new FightMetrics(10, 20, 60, 120, 3), 30_000);
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

    @Test
    void counterIsPerGamemode() throws Exception {
        Storage s = open(noon);
        s.recordMatch(a, b, sword(), rating).join();
        MatchInput crystal = new MatchInput(Gamemode.CRYSTAL, 1, 0, FightMetrics.EMPTY, FightMetrics.EMPTY, 1000);
        assertEquals(1, s.recordMatch(a, b, crystal, rating).join().matchNumberToday());
        assertEquals(2, s.loadRatings(a).join().size());
    }

    @Test
    void saveAndDeleteRatings() throws Exception {
        Storage s = open(noon);
        ModeRating r = rating.newRating(a, Gamemode.CART);
        r.setTier(Tier.HT2);
        r.setProvisional(false);
        s.saveRating(r).join();
        ModeRating loaded = s.loadRatings(a).join().get(Gamemode.CART);
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
