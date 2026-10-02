package ru.logic.tierplugin.storage;

import ru.logic.tierplugin.core.EndReason;
import ru.logic.tierplugin.core.FightMetrics;
import ru.logic.tierplugin.core.Gamemode;
import ru.logic.tierplugin.core.MatchInput;
import ru.logic.tierplugin.core.MatchOutcome;
import ru.logic.tierplugin.core.ModeRating;
import ru.logic.tierplugin.core.RatingService;
import ru.logic.tierplugin.core.Tier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous persistence API used by the rest of the plugin.
 * Every method returns immediately; the SQL runs on the database thread.
 * <p>
 * Tables: {@code players}, {@code ratings} (one row per player × gamemode),
 * {@code matches} and {@code metrics} (one row per player per match).
 */
public class Storage {

    /** A known player: UUID and last seen name. */
    public record PlayerRef(UUID uuid, String name) {}

    /**
     * Aggregate of a player's most recent fights in one gamemode.
     *
     * @param accuracy pooled hit percentage (all hits / all swings), -1 if no swings
     */
    public record MetricsSummary(int fights, int wins, double accuracy,
                                 double avgDamageDealt, double avgDamageTaken,
                                 int bestCombo, double avgCombo, double avgMedianCombo,
                                 double avgCps, int peakCps, double avgFightSkill) {}

    private static final List<String> PLAYER_COLUMNS = List.of("uuid", "name", "first_seen", "last_seen");
    private static final List<String> RATING_COLUMNS = List.of(
            "uuid", "gamemode", "elo", "rd", "volatility", "skill", "confidence",
            "fights", "wins", "losses", "tier", "provisional", "updated_at");
    private static final List<String> MATCH_COLUMNS = List.of(
            "gamemode", "pair_key", "winner_uuid", "loser_uuid", "winner_score", "loser_score",
            "duration_ms", "match_number", "weight",
            "winner_elo_before", "winner_elo_after", "loser_elo_before", "loser_elo_after", "played_at",
            "end_reason");
    private static final List<String> METRIC_COLUMNS = List.of(
            "match_id", "uuid", "swings", "hits", "misses", "damage_dealt", "damage_taken",
            "max_combo", "avg_combo", "median_combo", "combo_count", "avg_cps", "max_cps", "fight_skill");

    private final Database db;
    private final Clock clock;
    private final String upsertRating;

    public Storage(Database db, Clock clock) {
        this.db = db;
        this.clock = clock;
        this.upsertRating = db.dialect().upsert("ratings", RATING_COLUMNS, List.of("uuid", "gamemode"));
    }

    // ── Players ─────────────────────────────────────────────────

    /** Records a login: creates the player or refreshes name and last_seen. */
    public CompletableFuture<Void> touchPlayer(UUID uuid, String name) {
        return db.submit(conn -> {
            upsertPlayer(conn, uuid, name);
            return null;
        });
    }

    /** Case-insensitive lookup of a player who has ever joined. */
    public CompletableFuture<Optional<PlayerRef>> findPlayer(String name) {
        return db.submit(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT uuid, name FROM players WHERE LOWER(name) = LOWER(?) ORDER BY last_seen DESC")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2)))
                            : Optional.empty();
                }
            }
        });
    }

    // ── Ratings ─────────────────────────────────────────────────

    /** All gamemode ratings a player has; gamemodes never played are absent. */
    public CompletableFuture<Map<Gamemode, ModeRating>> loadRatings(UUID uuid) {
        return db.submit(conn -> {
            Map<Gamemode, ModeRating> out = new EnumMap<>(Gamemode.class);
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM ratings WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ModeRating r = mapRating(rs);
                        if (r != null) out.put(r.getGamemode(), r);
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<Void> saveRating(ModeRating r) {
        return db.submit(conn -> {
            saveRating(conn, r);
            return null;
        });
    }

    /** Deletes ratings of one gamemode, or of all gamemodes if {@code gamemode} is null. */
    public CompletableFuture<Integer> deleteRatings(UUID uuid, Gamemode gamemode) {
        return db.submit(conn -> {
            String sql = "DELETE FROM ratings WHERE uuid = ?" + (gamemode != null ? " AND gamemode = ?" : "");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                if (gamemode != null) ps.setString(2, gamemode.name());
                return ps.executeUpdate();
            }
        });
    }

    // ── Matches ─────────────────────────────────────────────────

    /**
     * Rates and records a finished match in a single transaction:
     * load both ratings → count the pair's matches today (and the loser's recent leaves,
     * for a LEAVE) → apply the rating core
     * → store match, metrics and the new ratings.
     */
    public CompletableFuture<MatchOutcome> recordMatch(UUID winnerId, UUID loserId, MatchInput in,
                                                       RatingService rating) {
        return db.submit(conn -> {
            ModeRating winner = loadRating(conn, winnerId, in.gamemode())
                    .orElseGet(() -> rating.newRating(winnerId, in.gamemode()));
            ModeRating loser = loadRating(conn, loserId, in.gamemode())
                    .orElseGet(() -> rating.newRating(loserId, in.gamemode()));

            String pair = pairKey(winnerId, loserId);
            int matchNumber = countPairMatchesSince(conn, pair, in.gamemode(), startOfToday()) + 1;
            int leaveNumber = in.endReason() == EndReason.LEAVE
                    ? leaveTimes(conn, loserId, clock.millis() - rating.getConfig().leave().windowMillis()).size() + 1
                    : 0;

            MatchOutcome out = rating.process(winner, loser, in, matchNumber, leaveNumber);

            long matchId = insertMatch(conn, pair, winnerId, loserId, in, out);
            insertMetrics(conn, matchId, winnerId, in.winnerMetrics(), out.winner().fightSkill());
            insertMetrics(conn, matchId, loserId, in.loserMetrics(), out.loser().fightSkill());
            saveRating(conn, winner);
            saveRating(conn, loser);
            return out;
        });
    }

    /**
     * Times of the player's leaves since {@code since} (epoch ms), newest first.
     * Feeds the leave cooldown and admin info.
     */
    public CompletableFuture<List<Long>> leaveTimes(UUID uuid, long since) {
        return db.submit(conn -> leaveTimes(conn, uuid, since));
    }

    /** Aggregates the player's last {@code limit} fights in {@code gamemode} that ended by a kill; empty if none. */
    public CompletableFuture<Optional<MetricsSummary>> metricsSummary(UUID uuid, Gamemode gamemode, int limit) {
        return db.submit(conn -> {
            String sql = """
                    SELECT COUNT(*), SUM(won), SUM(hits), SUM(swings),
                           AVG(damage_dealt), AVG(damage_taken), MAX(max_combo),
                           AVG(avg_combo), AVG(median_combo), AVG(avg_cps), MAX(max_cps), AVG(fight_skill)
                    FROM (SELECT m.*, CASE WHEN x.winner_uuid = m.uuid THEN 1 ELSE 0 END AS won
                          FROM metrics m JOIN matches x ON x.id = m.match_id
                          WHERE m.uuid = ? AND x.gamemode = ? AND x.end_reason = 'KILL'
                          ORDER BY x.played_at DESC, x.id DESC
                          LIMIT ?) recent""";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, gamemode.name());
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getInt(1) == 0) return Optional.empty();
                    long hits = rs.getLong(3), swings = rs.getLong(4);
                    return Optional.of(new MetricsSummary(
                            rs.getInt(1), rs.getInt(2),
                            swings == 0 ? -1 : Math.min(100.0, 100.0 * hits / swings),
                            rs.getDouble(5), rs.getDouble(6), rs.getInt(7),
                            rs.getDouble(8), rs.getDouble(9), rs.getDouble(10), rs.getInt(11), rs.getDouble(12)));
                }
            }
        });
    }

    /** Total matches stored, for diagnostics. */
    public CompletableFuture<Long> countMatches() {
        return db.submit(conn -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM matches")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    // ── SQL helpers (database thread only) ─────────────────────

    private void upsertPlayer(Connection conn, UUID uuid, String name) throws SQLException {
        long now = clock.millis();
        // Update first so first_seen is preserved; insert only for a new player.
        // Safe without an upsert because all SQL runs on the single database thread.
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE players SET name = ?, last_seen = ? WHERE uuid = ?")) {
            ps.setString(1, name);
            ps.setLong(2, now);
            ps.setString(3, uuid.toString());
            if (ps.executeUpdate() > 0) return;
        }
        try (PreparedStatement ps = conn.prepareStatement(SqlDialect.insert("players", PLAYER_COLUMNS))) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    private Optional<ModeRating> loadRating(Connection conn, UUID uuid, Gamemode gamemode) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM ratings WHERE uuid = ? AND gamemode = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, gamemode.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(mapRating(rs)) : Optional.empty();
            }
        }
    }

    private void saveRating(Connection conn, ModeRating r) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(upsertRating)) {
            ps.setString(1, r.getUuid().toString());
            ps.setString(2, r.getGamemode().name());
            ps.setDouble(3, r.getElo());
            ps.setDouble(4, r.getRatingDeviation());
            ps.setDouble(5, r.getVolatility());
            ps.setDouble(6, r.getSkillScore());
            ps.setDouble(7, r.getConfidence());
            ps.setInt(8, r.getFights());
            ps.setInt(9, r.getWins());
            ps.setInt(10, r.getLosses());
            ps.setString(11, r.getTier() != null ? r.getTier().name() : null);
            ps.setInt(12, r.isProvisional() ? 1 : 0);
            ps.setLong(13, clock.millis());
            ps.executeUpdate();
        }
    }

    /** @return the rating, or null if the row names a gamemode this version does not know */
    private static ModeRating mapRating(ResultSet rs) throws SQLException {
        Gamemode gm;
        try {
            gm = Gamemode.valueOf(rs.getString("gamemode"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        ModeRating r = new ModeRating(UUID.fromString(rs.getString("uuid")), gm,
                rs.getDouble("elo"), rs.getDouble("rd"), rs.getDouble("volatility"));
        r.setSkillScore(rs.getDouble("skill"));
        r.setConfidence(rs.getDouble("confidence"));
        r.setFights(rs.getInt("fights"));
        r.setWins(rs.getInt("wins"));
        r.setLosses(rs.getInt("losses"));
        String tier = rs.getString("tier");
        r.setTier(tier != null ? Tier.valueOf(tier) : null);
        r.setProvisional(rs.getInt("provisional") == 1);
        return r;
    }

    private List<Long> leaveTimes(Connection conn, UUID uuid, long since) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT played_at FROM matches WHERE loser_uuid = ? AND end_reason = 'LEAVE' AND played_at >= ?"
                + " ORDER BY played_at DESC")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getLong(1));
                return out;
            }
        }
    }

    private int countPairMatchesSince(Connection conn, String pair, Gamemode gm, long since) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM matches WHERE pair_key = ? AND gamemode = ? AND played_at >= ?")) {
            ps.setString(1, pair);
            ps.setString(2, gm.name());
            ps.setLong(3, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private long insertMatch(Connection conn, String pair, UUID winner, UUID loser,
                             MatchInput in, MatchOutcome out) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                SqlDialect.insert("matches", MATCH_COLUMNS), Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, in.gamemode().name());
            ps.setString(2, pair);
            ps.setString(3, winner.toString());
            ps.setString(4, loser.toString());
            ps.setInt(5, in.winnerScore());
            ps.setInt(6, in.loserScore());
            ps.setLong(7, in.durationMillis());
            ps.setInt(8, out.matchNumberToday());
            ps.setDouble(9, out.weight());
            ps.setDouble(10, out.winner().eloBefore());
            ps.setDouble(11, out.winner().eloAfter());
            ps.setDouble(12, out.loser().eloBefore());
            ps.setDouble(13, out.loser().eloAfter());
            ps.setLong(14, clock.millis());
            ps.setString(15, in.endReason().name());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No id returned for inserted match");
                return keys.getLong(1);
            }
        }
    }

    private void insertMetrics(Connection conn, long matchId, UUID uuid, FightMetrics m, double fightSkill)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SqlDialect.insert("metrics", METRIC_COLUMNS))) {
            ps.setLong(1, matchId);
            ps.setString(2, uuid.toString());
            ps.setInt(3, m.swings());
            ps.setInt(4, m.hits());
            ps.setInt(5, m.misses());
            ps.setDouble(6, m.damageDealt());
            ps.setDouble(7, m.damageTaken());
            ps.setInt(8, m.maxCombo());
            ps.setDouble(9, m.avgCombo());
            ps.setDouble(10, m.medianCombo());
            ps.setInt(11, m.combos());
            ps.setDouble(12, m.avgCps());
            ps.setInt(13, m.maxCps());
            ps.setDouble(14, fightSkill);
            ps.executeUpdate();
        }
    }

    /** Order-independent key so A-vs-B and B-vs-A share one daily counter. */
    static String pairKey(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }

    private long startOfToday() {
        return LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant().toEpochMilli();
    }
}
