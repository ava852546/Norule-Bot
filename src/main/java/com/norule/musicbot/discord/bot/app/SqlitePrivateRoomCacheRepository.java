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

public final class SqlitePrivateRoomCacheRepository implements PrivateRoomCacheRepository {
    private static final String SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS private_room_cache (
                guild_id INTEGER NOT NULL,
                channel_id INTEGER NOT NULL,
                owner_id INTEGER NULL,
                updated_at_millis INTEGER NOT NULL,
                PRIMARY KEY (guild_id, channel_id)
            )
            """;
    private static final String SQL_CREATE_IDX = """
            CREATE INDEX IF NOT EXISTS idx_private_room_cache_updated
            ON private_room_cache(updated_at_millis)
            """;
    private static final String SQL_FIND_ALL = """
            SELECT guild_id, channel_id, owner_id, updated_at_millis
            FROM private_room_cache
            """;
    private static final String SQL_UPSERT = """
            INSERT INTO private_room_cache (
                guild_id, channel_id, owner_id, updated_at_millis
            ) VALUES (?, ?, ?, ?)
            ON CONFLICT(guild_id, channel_id) DO UPDATE SET
                owner_id = excluded.owner_id,
                updated_at_millis = excluded.updated_at_millis
            """;
    private static final String SQL_DELETE = """
            DELETE FROM private_room_cache
            WHERE guild_id = ? AND channel_id = ?
            """;

    private final String jdbcUrl;

    public SqlitePrivateRoomCacheRepository(Path dbFilePath) {
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
            throw new IllegalStateException("Failed to initialize sqlite private-room cache schema", e);
        }
    }

    @Override
    public List<PrivateRoomCacheEntry> findAll() {
        List<PrivateRoomCacheEntry> out = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SQL_DELETE)) {
            statement.setLong(1, guildId);
            statement.setLong(2, channelId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete private-room cache entry", e);
        }
    }
}
