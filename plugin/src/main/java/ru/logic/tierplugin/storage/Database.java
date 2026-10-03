package ru.logic.tierplugin.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connection pool, schema and the single database thread.
 * <p>
 * All SQL runs on one dedicated thread, never on the server main thread, so a
 * slow disk or a remote MySQL cannot freeze the game. Running everything on one
 * thread also serialises work: a match is read, rated and written as one unit
 * without racing another match of the same player.
 */
public class Database {

    /** A unit of work executed inside one transaction. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection conn) throws SQLException;
    }

    private final Logger log;
    private final SqlDialect dialect;
    private final HikariDataSource dataSource;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LogicTier-DB");
        t.setDaemon(true);
        return t;
    });

    /**
     * Opens the pool and creates missing tables.
     *
     * @throws SQLException if the database is unreachable or the schema cannot be created
     */
    public Database(DatabaseSettings settings, Logger log) throws SQLException {
        this.log = log;
        this.dialect = settings.dialect();
        this.dataSource = new HikariDataSource(hikariConfig(settings));
        try {
            new Schema(dialect, log).create(dataSource);
        } catch (SQLException e) {
            dataSource.close();
            throw e;
        }
        log.info("База данных готова (" + dialect + ").");
    }

    public SqlDialect dialect() { return dialect; }

    private HikariConfig hikariConfig(DatabaseSettings s) {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("LogicTierPool");

        if (dialect == SqlDialect.MYSQL) {
            // Paper ships mysql-connector-j, so it is not shaded into the plugin
            hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hc.setJdbcUrl("jdbc:mysql://" + s.host() + ":" + s.port() + "/" + s.name()
                    + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8");
            hc.setUsername(s.user());
            hc.setPassword(s.password());
            hc.setMaximumPoolSize(s.poolSize());
        } else {
            s.sqliteFile().getAbsoluteFile().getParentFile().mkdirs();
            hc.setJdbcUrl("jdbc:sqlite:" + s.sqliteFile().getAbsolutePath());
            hc.setMaximumPoolSize(1); // SQLite allows a single writer
        }
        return hc;
    }

    /**
     * Runs {@code work} on the database thread inside a transaction.
     * Commits on success, rolls back and completes exceptionally on failure.
     */
    public <T> CompletableFuture<T> submit(SqlWork<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    T result = work.run(conn);
                    conn.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                log.log(Level.SEVERE, "Ошибка запроса к базе данных", e);
                throw new StorageException(e);
            }
        }, executor);
    }

    /** Waits for queued writes to finish, then closes the pool. */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warning("Очередь записи в базу не успела завершиться за 10 сек. — часть данных могла не сохраниться.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }

    /** Unchecked wrapper so failures travel through CompletableFuture. */
    public static class StorageException extends RuntimeException {
        public StorageException(Throwable cause) { super(cause); }
    }

    /** DDL for all tables; identical for both dialects apart from the bits in {@link SqlDialect}. */
    private record Schema(SqlDialect dialect, Logger log) {

        void create(HikariDataSource ds) throws SQLException {
            try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
                dropEmptyLegacy(st, "player_profiles");
                dropEmptyLegacy(st, "fight_history");

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS players (
                        uuid       VARCHAR(36) PRIMARY KEY,
                        name       VARCHAR(16) NOT NULL,
                        first_seen BIGINT NOT NULL,
                        last_seen  BIGINT NOT NULL
                    )""");

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ratings (
                        uuid        VARCHAR(36) NOT NULL,
                        gamemode    VARCHAR(16) NOT NULL,
                        elo         DOUBLE NOT NULL,
                        rd          DOUBLE NOT NULL,
                        volatility  DOUBLE NOT NULL,
                        skill       DOUBLE NOT NULL,
                        confidence  DOUBLE NOT NULL,
                        fights      INT NOT NULL,
                        wins        INT NOT NULL,
                        losses      INT NOT NULL,
                        tier        VARCHAR(4),
                        provisional INT NOT NULL,
                        updated_at  BIGINT NOT NULL,
                        PRIMARY KEY (uuid, gamemode)
                    )""");

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS matches (
                        id                %s,
                        gamemode          VARCHAR(16) NOT NULL,
                        pair_key          VARCHAR(73) NOT NULL,
                        winner_uuid       VARCHAR(36) NOT NULL,
                        loser_uuid        VARCHAR(36) NOT NULL,
                        winner_score      INT NOT NULL,
                        loser_score       INT NOT NULL,
                        duration_ms       BIGINT NOT NULL,
                        match_number      INT NOT NULL,
                        weight            DOUBLE NOT NULL,
                        winner_elo_before DOUBLE NOT NULL,
                        winner_elo_after  DOUBLE NOT NULL,
                        loser_elo_before  DOUBLE NOT NULL,
                        loser_elo_after   DOUBLE NOT NULL,
                        played_at         BIGINT NOT NULL,
                        end_reason        VARCHAR(8) NOT NULL DEFAULT 'KILL'
                    )""".formatted(dialect.autoIdColumn()));

                // Stage-1/3 matches tables lack end_reason
                ensureColumn(conn, st, "matches", "end_reason", "VARCHAR(8) NOT NULL DEFAULT 'KILL'");

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS metrics (
                        match_id     BIGINT NOT NULL,
                        uuid         VARCHAR(36) NOT NULL,
                        swings       INT NOT NULL DEFAULT 0,
                        hits         INT NOT NULL,
                        misses       INT NOT NULL,
                        damage_dealt DOUBLE NOT NULL,
                        damage_taken DOUBLE NOT NULL,
                        max_combo    INT NOT NULL,
                        avg_combo    DOUBLE NOT NULL DEFAULT 0,
                        median_combo DOUBLE NOT NULL DEFAULT 0,
                        combo_count  INT NOT NULL DEFAULT 0,
                        avg_cps      DOUBLE NOT NULL DEFAULT 0,
                        max_cps      INT NOT NULL DEFAULT 0,
                        fight_skill  DOUBLE NOT NULL,
                        PRIMARY KEY (match_id, uuid)
                    )""");

                // Stage-1 metrics tables lack the stage-3 columns
                ensureColumn(conn, st, "metrics", "swings", "INT NOT NULL DEFAULT 0");
                ensureColumn(conn, st, "metrics", "avg_combo", "DOUBLE NOT NULL DEFAULT 0");
                ensureColumn(conn, st, "metrics", "median_combo", "DOUBLE NOT NULL DEFAULT 0");
                ensureColumn(conn, st, "metrics", "combo_count", "INT NOT NULL DEFAULT 0");
                ensureColumn(conn, st, "metrics", "avg_cps", "DOUBLE NOT NULL DEFAULT 0");
                ensureColumn(conn, st, "metrics", "max_cps", "INT NOT NULL DEFAULT 0");

                index(st, "idx_players_name", "players", "name");
                index(st, "idx_matches_pair", "matches", "pair_key, gamemode, played_at");
                index(st, "idx_matches_winner", "matches", "winner_uuid");
                index(st, "idx_matches_loser", "matches", "loser_uuid");
            }
        }

        private void ensureColumn(Connection conn, Statement st, String table, String column, String definition)
                throws SQLException {
            try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, column)) {
                if (rs.next()) return;
            }
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("Добавлена колонка " + table + "." + column + ".");
        }

        private void index(Statement st, String name, String table, String columns) throws SQLException {
            try {
                st.executeUpdate(dialect.createIndex(name, table, columns));
            } catch (SQLException e) {
                if (!dialect.isDuplicateIndex(e)) throw e;
            }
        }

        /** Stage-0 tables were never written to; drop them only if they are still empty. */
        private void dropEmptyLegacy(Statement st, String table) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                if (rs.next() && rs.getLong(1) == 0) {
                    st.executeUpdate("DROP TABLE " + table);
                    log.info("Удалена пустая старая таблица " + table + ".");
                } else {
                    log.warning("В старой таблице " + table + " есть данные — она оставлена как есть.");
                }
            } catch (SQLException ignored) {
                // table does not exist — nothing to migrate
            }
        }
    }
}
