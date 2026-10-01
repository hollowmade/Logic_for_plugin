package ru.logic.tierplugin.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ru.logic.tierplugin.LogicTierPlugin;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Manages the database connection pool and schema initialisation.
 * Supports SQLite (default) with a path to MySQL migration in the future.
 */
public class Database {

    private final LogicTierPlugin plugin;
    private HikariDataSource dataSource;
    private final Logger log;

    public Database(LogicTierPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /**
     * Initialises the connection pool and creates tables if absent.
     *
     * @return true on success
     */
    public boolean init() {
        try {
            HikariConfig config = buildHikariConfig();
            dataSource = new HikariDataSource(config);
            createTables();
            log.info("Database initialised successfully.");
            return true;
        } catch (Exception e) {
            log.severe("Database init failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private HikariConfig buildHikariConfig() {
        HikariConfig cfg = new HikariConfig();
        String type = plugin.getConfig().getString("database.type", "sqlite");

        if ("mysql".equalsIgnoreCase(type)) {
            String host = plugin.getConfig().getString("database.host", "localhost");
            int    port = plugin.getConfig().getInt("database.port", 3306);
            String name = plugin.getConfig().getString("database.name", "tierplugin");
            cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + name
                    + "?useSSL=false&autoReconnect=true&characterEncoding=UTF-8");
            cfg.setUsername(plugin.getConfig().getString("database.user", "root"));
            cfg.setPassword(plugin.getConfig().getString("database.password", ""));
            cfg.setMaximumPoolSize(plugin.getConfig().getInt("database.pool-size", 5));
        } else {
            String file = plugin.getDataFolder().getAbsolutePath() + "/"
                    + plugin.getConfig().getString("database.file", "tierplugin.db");
            cfg.setJdbcUrl("jdbc:sqlite:" + file);
            cfg.setMaximumPoolSize(1); // SQLite is single-threaded
        }

        cfg.setPoolName("LogicTierPool");
        return cfg;
    }

    private void createTables() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS player_profiles (
                    uuid             TEXT PRIMARY KEY,
                    last_name        TEXT,
                    elo_rating       REAL DEFAULT 1000,
                    rating_deviation REAL DEFAULT 350,
                    volatility       REAL DEFAULT 0.06,
                    skill_score      REAL DEFAULT 0,
                    confidence       REAL DEFAULT 0,
                    total_fights     INTEGER DEFAULT 0,
                    tier             TEXT,
                    provisional      INTEGER DEFAULT 1,
                    last_updated     INTEGER DEFAULT 0
                )
                """);

            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS fight_history (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    winner_uuid    TEXT NOT NULL,
                    loser_uuid     TEXT NOT NULL,
                    gamemode       TEXT NOT NULL,
                    winner_score   INTEGER,
                    loser_score    INTEGER,
                    mechanics      REAL,
                    combat         REAL,
                    decision       REAL,
                    movement       REAL,
                    consistency    REAL,
                    adaptation     REAL,
                    duration_ms    INTEGER,
                    played_at      INTEGER DEFAULT 0
                )
                """);
        }
    }

    /** @return a connection from the pool */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
