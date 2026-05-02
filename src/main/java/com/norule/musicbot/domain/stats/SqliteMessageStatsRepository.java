package com.norule.musicbot.domain.stats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class SqliteMessageStatsRepository implements MessageStatsRepository {
    private static final String SQL_CREATE_COUNTS = """
            CREATE TABLE IF NOT EXISTS guild_user_message_counts (
                guild_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                message_count INTEGER NOT NULL DEFAULT 0,
                voice_seconds INTEGER NOT NULL DEFAULT 0,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (guild_id, user_id)
            )
            """;

    private static final String SQL_CREATE_PROCESSED = """
            CREATE TABLE IF NOT EXISTS processed_messages (
                message_id INTEGER PRIMARY KEY,
                guild_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """;

    private static final String SQL_INSERT_PROCESSED = "INSERT OR IGNORE INTO processed_messages (message_id, guild_id, user_id) VALUES (?, ?, ?)";
    private static final String SQL_INCREMENT_MESSAGE = """
            INSERT INTO guild_user_message_counts (guild_id, user_id, message_count, voice_seconds, updated_at)
            VALUES (?, ?, 1, 0, CURRENT_TIMESTAMP)
            ON CONFLICT(guild_id, user_id)
            DO UPDATE SET message_count = guild_user_message_counts.message_count + 1, updated_at = CURRENT_TIMESTAMP
            """;
    private static final String SQL_GET_MESSAGE_COUNT = "SELECT message_count FROM guild_user_message_counts WHERE guild_id = ? AND user_id = ?";
    private static final String SQL_GET_TOP_MESSAGE = "SELECT user_id, message_count FROM guild_user_message_counts WHERE guild_id = ? ORDER BY message_count DESC LIMIT ?";

    private static final String SQL_INCREMENT_VOICE = """
            INSERT INTO guild_user_message_counts (guild_id, user_id, message_count, voice_seconds, updated_at)
            VALUES (?, ?, 0, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(guild_id, user_id)
            DO UPDATE SET voice_seconds = guild_user_message_counts.voice_seconds + excluded.voice_seconds, updated_at = CURRENT_TIMESTAMP
            """;
    private static final String SQL_GET_VOICE_SECONDS = "SELECT voice_seconds FROM guild_user_message_counts WHERE guild_id = ? AND user_id = ?";
    private static final String SQL_GET_TOP_VOICE = "SELECT user_id, voice_seconds FROM guild_user_message_counts WHERE guild_id = ? ORDER BY voice_seconds DESC LIMIT ?";

    private final String jdbcUrl;

    public SqliteMessageStatsRepository(Path dbFilePath) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not found (org.sqlite.JDBC)", e);
        }
        try {
            Path parent = dbFilePath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare sqlite directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbFilePath.toAbsolutePath().normalize();
        initializeSchema();
    }

    private void initializeSchema() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute(SQL_CREATE_COUNTS);
            statement.execute(SQL_CREATE_PROCESSED);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize sqlite stats schema (jdbc=" + jdbcUrl + ")", e);
        }
    }

    @Override
    public boolean incrementIfNewMessage(long guildId, long userId, long messageId) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            connection.setAutoCommit(false);
            try (PreparedStatement insertProcessed = connection.prepareStatement(SQL_INSERT_PROCESSED);
                 PreparedStatement incrementCount = connection.prepareStatement(SQL_INCREMENT_MESSAGE)) {
                insertProcessed.setLong(1, messageId);
                insertProcessed.setLong(2, guildId);
                insertProcessed.setLong(3, userId);
                int inserted = insertProcessed.executeUpdate();
                if (inserted == 0) {
                    connection.commit();
                    return false;
                }

                incrementCount.setLong(1, guildId);
                incrementCount.setLong(2, userId);
                incrementCount.executeUpdate();
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to track message count", e);
        }
    }

    @Override
    public long getMessageCount(long guildId, long userId) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SQL_GET_MESSAGE_COUNT)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query message count", e);
        }
    }

    @Override
    public List<UserMessageCount> getTopMessageCounts(long guildId, int limit) {
        List<UserMessageCount> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SQL_GET_TOP_MESSAGE)) {
            statement.setLong(1, guildId);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new UserMessageCount(rs.getLong("user_id"), rs.getLong("message_count")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query top message counts", e);
        }
    }

    @Override
    public void incrementVoiceSeconds(long guildId, long userId, long seconds) {
        if (seconds <= 0) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SQL_INCREMENT_VOICE)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.setLong(3, seconds);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to track voice seconds", e);
        }
    }

    @Override
    public long getVoiceSeconds(long guildId, long userId) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SQL_GET_VOICE_SECONDS)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query voice seconds", e);
        }
    }

    @Override
    public List<UserVoiceTime> getTopVoiceSeconds(long guildId, int limit) {
        List<UserVoiceTime> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SQL_GET_TOP_VOICE)) {
            statement.setLong(1, guildId);
            statement.setInt(2, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new UserVoiceTime(rs.getLong("user_id"), rs.getLong("voice_seconds")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query top voice seconds", e);
        }
    }
}

