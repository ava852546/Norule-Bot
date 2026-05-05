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

public final class MySqlPrivateRoomCacheRepository implements PrivateRoomCacheRepository {
    private static final String SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS private_room_cache (
                guild_id BIGINT NOT NULL,
                channel_id BIGINT NOT NULL,
                owner_id BIGINT NULL,
                updated_at_millis BIGINT NOT NULL,
                PRIMARY KEY (guild_id, channel_id),
                KEY idx_private_room_cache_updated (updated_at_millis)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
    private static final String SQL_FIND_ALL = """
            SELECT guild_id, channel_id, owner_id, updated_at_millis
            FROM private_room_cache
            """;
    private static final String SQL_UPSERT = """
            INSERT INTO private_room_cache (
                guild_id, channel_id, owner_id, updated_at_millis
            ) VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                owner_id = VALUES(owner_id),
                updated_at_millis = VALUES(updated_at_millis)
            """;
    private static final String SQL_DELETE = """
            DELETE FROM private_room_cache
            WHERE guild_id = ? AND channel_id = ?
            """;

    private final HikariDataSource dataSource;

    public MySqlPrivateRoomCacheRepository(String jdbcUrl, String username, String password, int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, maxPoolSize));
        config.setMinimumIdle(1);
        config.setPoolName("private-room-cache-pool");
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
            throw new IllegalStateException("Failed to initialize mysql private-room cache schema", e);
        }
    }

    @Override
    public List<PrivateRoomCacheEntry> findAll() {
        List<PrivateRoomCacheEntry> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_FIND_ALL);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                long ownerRaw = rs.getLong("owner_id");
                Long ownerId = rs.wasNull() ? null : ownerRaw;
                out.add(new PrivateRoomCacheEntry(
                        rs.getLong("guild_id"),
                        rs.getLong("channel_id"),
                        ownerId,
                        rs.getLong("updated_at_millis")
                ));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query private-room cache entries", e);
        }
    }

    @Override
    public void upsert(PrivateRoomCacheEntry entry) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_UPSERT)) {
            statement.setLong(1, entry.getGuildId());
            statement.setLong(2, entry.getChannelId());
            if (entry.getOwnerId() == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, entry.getOwnerId());
            }
            statement.setLong(4, entry.getUpdatedAtMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert private-room cache entry", e);
        }
    }

    @Override
    public void remove(long guildId, long channelId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_DELETE)) {
            statement.setLong(1, guildId);
            statement.setLong(2, channelId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete private-room cache entry", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
