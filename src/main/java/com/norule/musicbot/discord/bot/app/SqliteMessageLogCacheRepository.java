package com.norule.musicbot.discord.bot.app;

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

public final class SqliteMessageLogCacheRepository implements MessageLogCacheRepository {
    private static final String SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS message_log_cache (
                message_id INTEGER PRIMARY KEY,
                channel_id INTEGER NOT NULL,
                author_tag TEXT NOT NULL,
                author_id TEXT NOT NULL,
                author_is_bot INTEGER NOT NULL,
                author_role_ids TEXT NOT NULL,
                content TEXT NOT NULL,
                attachments TEXT NOT NULL,
                cached_at_millis INTEGER NOT NULL
            )
            """;
    private static final String SQL_CREATE_IDX = """
            CREATE INDEX IF NOT EXISTS idx_message_log_cache_cached_at
            ON message_log_cache(cached_at_millis)
            """;
    private static final String SQL_UPSERT = """
            INSERT INTO message_log_cache (
                message_id, channel_id, author_tag, author_id, author_is_bot,
                author_role_ids, content, attachments, cached_at_millis
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(message_id) DO UPDATE SET
                channel_id = excluded.channel_id,
                author_tag = excluded.author_tag,
                author_id = excluded.author_id,
                author_is_bot = excluded.author_is_bot,
                author_role_ids = excluded.author_role_ids,
                content = excluded.content,
                attachments = excluded.attachments,
                cached_at_millis = excluded.cached_at_millis
            """;
    private static final String SQL_FIND = """
            SELECT message_id, channel_id, author_tag, author_id, author_is_bot,
                   author_role_ids, content, attachments, cached_at_millis
            FROM message_log_cache
            WHERE message_id = ?
            """;
    private static final String SQL_DELETE = "DELETE FROM message_log_cache WHERE message_id = ?";
    private static final String SQL_PRUNE = "DELETE FROM message_log_cache WHERE cached_at_millis < ?";

    private final String jdbcUrl;

    public SqliteMessageLogCacheRepository(Path dbFilePath) {
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
            throw new IllegalStateException("Failed to initialize sqlite message-log cache schema", e);
        }
    }

    @Override
    public void upsert(MessageLogCacheEntry entry) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SQL_UPSERT)) {
            statement.setLong(1, entry.getMessageId());
            statement.setLong(2, entry.getChannelId());
            statement.setString(3, entry.getAuthorTag());
            statement.setString(4, entry.getAuthorId());
            statement.setInt(5, entry.isAuthorIsBot() ? 1 : 0);
            statement.setString(6, encodeRoleIds(entry.getAuthorRoleIds()));
            statement.setString(7, entry.getContent());
            statement.setString(8, entry.getAttachments());
            statement.setLong(9, entry.getCachedAtMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert message-log cache entry", e);
        }
    }

    @Override
    public MessageLogCacheEntry find(long messageId) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SQL_FIND)) {
            statement.setLong(1, messageId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new MessageLogCacheEntry(
                        rs.getLong("message_id"),
                        rs.getLong("channel_id"),
                        rs.getString("author_tag"),
                        rs.getString("author_id"),
                        rs.getInt("author_is_bot") != 0,
                        decodeRoleIds(rs.getString("author_role_ids")),
                        rs.getString("content"),
                        rs.getString("attachments"),
                        rs.getLong("cached_at_millis")
                );
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query message-log cache entry", e);
        }
    }

    @Override
    public MessageLogCacheEntry remove(long messageId) {
        MessageLogCacheEntry existing = find(messageId);
        if (existing == null) {
            return null;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SQL_DELETE)) {
            statement.setLong(1, messageId);
            statement.executeUpdate();
            return existing;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete message-log cache entry", e);
        }
    }

    @Override
    public void pruneExpired(long cutoffMillis) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SQL_PRUNE)) {
            statement.setLong(1, cutoffMillis);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to prune message-log cache entries", e);
        }
    }

    private static String encodeRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Long roleId : roleIds) {
            if (roleId == null || roleId <= 0L) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(roleId);
        }
        return builder.toString();
    }

    private static List<Long> decodeRoleIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        String[] parts = raw.split(",");
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            try {
                long value = Long.parseLong(part.trim());
                if (value > 0L) {
                    out.add(value);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out.stream().distinct().toList();
    }
}
