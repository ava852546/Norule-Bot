package com.norule.musicbot.discord.bot.app;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class MySqlDuplicateMessageCacheRepository implements DuplicateMessageCacheRepository {
    private static final String SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS duplicate_message_cache (
                guild_id BIGINT NOT NULL,
                channel_id BIGINT NOT NULL,
                user_id BIGINT NOT NULL,
                content_hash VARCHAR(128) NOT NULL,
                duplicate_count INT NOT NULL,
                timestamp_millis BIGINT NOT NULL,
                PRIMARY KEY (guild_id, channel_id, user_id, content_hash),
                KEY idx_duplicate_message_cache_timestamp (timestamp_millis)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
    private static final String SQL_FIND = """
            SELECT guild_id, channel_id, user_id, content_hash, duplicate_count, timestamp_millis
            FROM duplicate_message_cache
            WHERE guild_id = ? AND channel_id = ? AND user_id = ? AND content_hash = ?
            """;
    private static final String SQL_UPSERT = """
            INSERT INTO duplicate_message_cache (
                guild_id, channel_id, user_id, content_hash, duplicate_count, timestamp_millis
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                duplicate_count = VALUES(duplicate_count),
                timestamp_millis = VALUES(timestamp_millis)
            """;
    private static final String SQL_PRUNE = "DELETE FROM duplicate_message_cache WHERE timestamp_millis < ?";

    private final HikariDataSource dataSource;

    public MySqlDuplicateMessageCacheRepository(String jdbcUrl, String username, String password, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, maxPoolSize));
        config.setMinimumIdle(1);
        config.setPoolName("duplicate-message-cache-pool");
        config.setConnectionTimeout(10000L);
        config.setValidationTimeout(5000L);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        this.dataSource = new HikariDataSource(config);
        initializeSchema(this.dataSource);
    }

    private static void initializeSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(SQL_CREATE);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize mysql duplicate-message cache schema", e);
        }
    }

    @Override
    public DuplicateMessageCacheEntry find(long guildId, long channelId, long userId, String contentHash) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_FIND)) {
            statement.setLong(1, guildId);
            statement.setLong(2, channelId);
            statement.setLong(3, userId);
            statement.setString(4, contentHash == null ? "" : contentHash);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new DuplicateMessageCacheEntry(
                        rs.getLong("guild_id"),
                        rs.getLong("channel_id"),
                        rs.getLong("user_id"),
                        rs.getString("content_hash"),
                        rs.getInt("duplicate_count"),
                        rs.getLong("timestamp_millis")
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query duplicate-message cache entry", e);
        }
    }

    @Override
    public void upsert(DuplicateMessageCacheEntry entry) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_UPSERT)) {
            statement.setLong(1, entry.getGuildId());
            statement.setLong(2, entry.getChannelId());
            statement.setLong(3, entry.getUserId());
            statement.setString(4, entry.getContentHash());
            statement.setInt(5, entry.getCount());
            statement.setLong(6, entry.getTimestampMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert duplicate-message cache entry", e);
        }
    }

    @Override
    public void pruneExpired(long cutoffMillis) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_PRUNE)) {
            statement.setLong(1, cutoffMillis);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to prune duplicate-message cache entries", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
