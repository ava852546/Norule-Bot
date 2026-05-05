package com.norule.musicbot.discord.bot.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteDuplicateMessageCacheRepository implements DuplicateMessageCacheRepository {
    private static final String SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS duplicate_message_cache (
                guild_id INTEGER NOT NULL,
                channel_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                content_hash TEXT NOT NULL,
                duplicate_count INTEGER NOT NULL,
                timestamp_millis INTEGER NOT NULL,
                PRIMARY KEY (guild_id, channel_id, user_id, content_hash)
            )
            """;
    private static final String SQL_CREATE_IDX = """
            CREATE INDEX IF NOT EXISTS idx_duplicate_message_cache_timestamp
            ON duplicate_message_cache(timestamp_millis)
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
            ON CONFLICT(guild_id, channel_id, user_id, content_hash) DO UPDATE SET
                duplicate_count = excluded.duplicate_count,
                timestamp_millis = excluded.timestamp_millis
            """;
    private static final String SQL_PRUNE = "DELETE FROM duplicate_message_cache WHERE timestamp_millis < ?";

    private final String jdbcUrl;

    public SqliteDuplicateMessageCacheRepository(Path dbFilePath) {
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
            statement.execute(SQL_CREATE);
            statement.execute(SQL_CREATE_IDX);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize sqlite duplicate-message cache schema", e);
        }
    }

    @Override
    public DuplicateMessageCacheEntry find(long guildId, long channelId, long userId, String contentHash) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SQL_PRUNE)) {
            statement.setLong(1, cutoffMillis);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to prune duplicate-message cache entries", e);
        }
    }
}
