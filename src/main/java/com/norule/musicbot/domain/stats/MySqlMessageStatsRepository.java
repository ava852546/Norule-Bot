package com.norule.musicbot.domain.stats;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class MySqlMessageStatsRepository implements MessageStatsRepository, AutoCloseable {
    private static final String SQL_CREATE_COUNTS = """
            CREATE TABLE IF NOT EXISTS guild_user_message_counts (
                guild_id BIGINT NOT NULL,
                user_id BIGINT NOT NULL,
                message_count BIGINT NOT NULL DEFAULT 0,
                voice_seconds BIGINT NOT NULL DEFAULT 0,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (guild_id, user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String SQL_CREATE_PROCESSED = """
            CREATE TABLE IF NOT EXISTS processed_messages (
                message_id BIGINT NOT NULL,
                guild_id BIGINT NOT NULL,
                user_id BIGINT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (message_id),
                KEY idx_processed_messages_guild_user (guild_id, user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String SQL_INSERT_PROCESSED = "INSERT IGNORE INTO processed_messages (message_id, guild_id, user_id) VALUES (?, ?, ?)";
    private static final String SQL_INCREMENT_MESSAGE = """
            INSERT INTO guild_user_message_counts (guild_id, user_id, message_count, voice_seconds)
            VALUES (?, ?, 1, 0)
            ON DUPLICATE KEY UPDATE message_count = message_count + 1
            """;
    private static final String SQL_GET_MESSAGE_COUNT = "SELECT message_count FROM guild_user_message_counts WHERE guild_id = ? AND user_id = ?";
    private static final String SQL_GET_TOP_MESSAGE = "SELECT user_id, message_count FROM guild_user_message_counts WHERE guild_id = ? ORDER BY message_count DESC LIMIT ?";

    private static final String SQL_INCREMENT_VOICE = """
            INSERT INTO guild_user_message_counts (guild_id, user_id, message_count, voice_seconds)
            VALUES (?, ?, 0, ?)
            ON DUPLICATE KEY UPDATE voice_seconds = voice_seconds + VALUES(voice_seconds)
            """;
    private static final String SQL_GET_VOICE_SECONDS = "SELECT voice_seconds FROM guild_user_message_counts WHERE guild_id = ? AND user_id = ?";
    private static final String SQL_GET_TOP_VOICE = "SELECT user_id, voice_seconds FROM guild_user_message_counts WHERE guild_id = ? ORDER BY voice_seconds DESC LIMIT ?";

    private final HikariDataSource dataSource;

    public MySqlMessageStatsRepository(String jdbcUrl, String username, String password, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, maxPoolSize));
        config.setMinimumIdle(1);
        config.setPoolName("message-stats-pool");
        config.setConnectionTimeout(10000L);
        config.setValidationTimeout(5000L);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        this.dataSource = new HikariDataSource(config);
        initializeSchema(this.dataSource);
    }

    private static void initializeSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(SQL_CREATE_COUNTS);
            statement.execute(SQL_CREATE_PROCESSED);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize stats schema", e);
        }
    }

    @Override
    public boolean incrementIfNewMessage(long guildId, long userId, long messageId) {
        try (Connection connection = dataSource.getConnection()) {
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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_GET_MESSAGE_COUNT)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
            return 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query message count", e);
        }
    }

    @Override
    public List<UserMessageCount> getTopMessageCounts(long guildId, int limit) {
        List<UserMessageCount> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
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
        try (Connection connection = dataSource.getConnection();
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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_GET_VOICE_SECONDS)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
            return 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query voice seconds", e);
        }
    }

    @Override
    public List<UserVoiceTime> getTopVoiceSeconds(long guildId, int limit) {
        List<UserVoiceTime> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
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

    @Override
    public void close() {
        dataSource.close();
    }
}

