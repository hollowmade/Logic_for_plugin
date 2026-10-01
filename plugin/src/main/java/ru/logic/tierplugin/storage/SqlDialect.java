package ru.logic.tierplugin.storage;

import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The few places where SQLite and MySQL disagree. Everything else is plain SQL
 * shared by both, so switching {@code database.type} needs no code changes.
 */
public enum SqlDialect {

    SQLITE {
        @Override
        public String autoIdColumn() { return "INTEGER PRIMARY KEY AUTOINCREMENT"; }

        @Override
        public String upsert(String table, List<String> columns, List<String> keys) {
            return insert(table, columns) + " ON CONFLICT(" + String.join(", ", keys) + ") DO UPDATE SET "
                    + updatable(columns, keys).stream()
                        .map(c -> c + " = excluded." + c)
                        .collect(Collectors.joining(", "));
        }

        @Override
        public String createIndex(String name, String table, String columns) {
            return "CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + columns + ")";
        }

        @Override
        public boolean isDuplicateIndex(SQLException e) { return false; }
    },

    MYSQL {
        @Override
        public String autoIdColumn() { return "BIGINT PRIMARY KEY AUTO_INCREMENT"; }

        @Override
        public String upsert(String table, List<String> columns, List<String> keys) {
            return insert(table, columns) + " ON DUPLICATE KEY UPDATE "
                    + updatable(columns, keys).stream()
                        .map(c -> c + " = VALUES(" + c + ")")
                        .collect(Collectors.joining(", "));
        }

        @Override
        public String createIndex(String name, String table, String columns) {
            // MySQL has no CREATE INDEX IF NOT EXISTS; a duplicate is reported and ignored
            return "CREATE INDEX " + name + " ON " + table + " (" + columns + ")";
        }

        @Override
        public boolean isDuplicateIndex(SQLException e) { return e.getErrorCode() == 1061; }
    };

    /** Column definition for an auto-increment surrogate key. */
    public abstract String autoIdColumn();

    /** INSERT that updates every non-key column when the key already exists. */
    public abstract String upsert(String table, List<String> columns, List<String> keys);

    public abstract String createIndex(String name, String table, String columns);

    /** @return true if the exception only says the index already exists */
    public abstract boolean isDuplicateIndex(SQLException e);

    public static SqlDialect fromConfig(String type) {
        return "mysql".equalsIgnoreCase(type) ? MYSQL : SQLITE;
    }

    static String insert(String table, List<String> columns) {
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
    }

    private static List<String> updatable(List<String> columns, List<String> keys) {
        return columns.stream().filter(c -> !keys.contains(c)).toList();
    }
}
