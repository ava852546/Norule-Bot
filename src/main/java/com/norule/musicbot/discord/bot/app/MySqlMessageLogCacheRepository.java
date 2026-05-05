package com.norule.musicbot.discord.bot.app;

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

public final class MySqlMessageLogCacheRepository implements MessageLogCacheRepository {
    private static final String SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS message_log_cache (
                message_id BIGINT NOT NULL,
                channel_id BIGINT NOT NULL,
                author_tag VARCHAR(128) NOT NULL,
                author_id VARCHAR(32) NOT NULL,
                author_is_bot TINYINT(1) NOT NULL,
                author_role_ids TEXT NOT NULL,
                content MEDIUMTEXT NOT NULL,
                attachments MEDIUMTEXT NOT NULL,
                cached_at_millis BIGINT NOT NULL,
                PRIMARY KEY (message_id),
                KEY idx_message_log_cache_cached_at (cached_at_millis)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
    private static final String SQL_UPSERT = """
            INSERT INTO message_log_cache (
                message_id, channel_id, author_tag, author_id, author_is_bot,
                author_role_ids, content, attachments, cached_at_millis
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                channel_id = VALUES(channel_id),
                author_tag = VALUES(author_tag),
                author_id = VALUES(author_id),
                author_is_bot = VALUES(author_is_bot),
                author_role_ids = VALUES(author_role_ids),
                content = VALUES(content),
                attachments = VALUES(attachments),
                cached_at_millis = VALUES(cached_at_millis)
            """;
    private static final String SQL_FIND = """
            SELECT message_id, channel_id, author_tag, author_id, author_is_bot,
                   author_role_ids, content, attachments, cached_at_millis
            FROM message_log_cache
            WHERE message_id = ?
            """;
    private static final String SQL_DELETE = "DELETE FROM message_log_cache WHERE message_id = ?";
    private static final String SQL_PRUNE = "DELETE FROM message_log_cache WHERE cached_at_millis < ?";

    private final HikariDataSource dataSource;

    public MySqlMessageLogCacheRepository(String jdbcUrl, String username, String password, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, maxPoolSize));
        config.setMinimumIdle(1);
        config.setPoolName("message-log-cache-pool");
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
            statement.execute(SQL_CREATE);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize mysql message-log cache schema", e);
        }
    }

    @Override
    public void upsert(MessageLogCacheEntry entry) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_UPSERT)) {
            statement.setLong(1, entry.getMessageId());
            statement.setLong(2, entry.getChannelId());
            statement.setString(3, entry.getAuthorTag());
            statement.setString(4, entry.getAuthorId());
            statement.setBoolean(5, entry.isAuthorIsBot());
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
        try (Connection connection = dataSource.getConnection();
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
                        rs.getBoolean("author_is_bot"),
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
        try (Connection connection = dataSource.getConnection();
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
        try (Connection connection = dataSource.getConnection();
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

    @Override
    public void close() {
        dataSource.close();
    }
}
