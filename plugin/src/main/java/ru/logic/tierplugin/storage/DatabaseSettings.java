package ru.logic.tierplugin.storage;

import java.io.File;

/**
 * Connection settings, decoupled from Bukkit's config API so storage can be
 * exercised in plain JUnit against a temporary SQLite file.
 *
 * @param sqliteFile database file, used only when {@code dialect} is SQLITE
 */
public record DatabaseSettings(SqlDialect dialect, File sqliteFile,
                               String host, int port, String name,
                               String user, String password, int poolSize) {

    public static DatabaseSettings sqlite(File file) {
        return new DatabaseSettings(SqlDialect.SQLITE, file, null, 0, null, null, null, 1);
    }
}
