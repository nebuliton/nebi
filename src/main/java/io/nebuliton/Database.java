package io.nebuliton;

import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class Database {
    private final SQLiteDataSource dataSource;

    public Database(String path) {
        Path dbPath = Path.of(path);
        Path parent = dbPath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to create database directory: " + parent, e);
            }
        }

        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + dbPath);
        this.dataSource = source;
        initSchema();
    }

    public Connection getConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        applyPragmas(connection);
        return connection;
    }

    private void applyPragmas(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL;");
            statement.execute("PRAGMA synchronous=NORMAL;");
            statement.execute("PRAGMA busy_timeout=5000;");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply SQLite pragmas", e);
        }
    }

    private void initSchema() {
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS user_contexts (
                        guild_id INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        context TEXT NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY (guild_id, user_id)
                    );
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        guild_id INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        confidence REAL NOT NULL DEFAULT 1.0,
                        source TEXT NOT NULL DEFAULT 'manual',
                        added_by INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    );
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ai_blacklist (
                        guild_id INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        reason TEXT,
                        added_by INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY (guild_id, user_id)
                    );
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        guild_id INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    );
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_conversation_user
                    ON conversation_messages (guild_id, user_id, id);
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS response_feedback (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        guild_id INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        rating TEXT NOT NULL,
                        reason TEXT,
                        created_at INTEGER NOT NULL
                    );
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ai_reply_audit (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        guild_id INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        model TEXT NOT NULL,
                        used_user_context INTEGER NOT NULL,
                        history_count INTEGER NOT NULL,
                        knowledge_ids TEXT,
                        knowledge_preview TEXT,
                        prompt_excerpt TEXT,
                        response_excerpt TEXT,
                        latency_ms INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    );
                    """);
            addColumnIfMissing(connection, "knowledge_entries", "confidence", "REAL NOT NULL DEFAULT 1.0");
            addColumnIfMissing(connection, "knowledge_entries", "source", "TEXT NOT NULL DEFAULT 'manual'");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize database schema", e);
        }
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String definition)
            throws SQLException {
        if (hasColumn(connection, table, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition + ";");
        }
    }

    private boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ");")) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
