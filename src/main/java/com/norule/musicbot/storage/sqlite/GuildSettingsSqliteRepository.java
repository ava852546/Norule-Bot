package com.norule.musicbot.storage.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class GuildSettingsSqliteRepository {
    private static final String SQL_CREATE = """
            CREATE TABLE IF NOT EXISTS guild_settings_snapshot (
                guild_id INTEGER PRIMARY KEY,
                payload_yaml TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """;

    private final SqliteDatabase database;

    public GuildSettingsSqliteRepository(SqliteDatabase database) {
        this.database = database;
        this.database.initializeSchema(SQL_CREATE);
    }

    public String findGuildPayload(long guildId) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT payload_yaml FROM guild_settings_snapshot WHERE guild_id = ?"
             )) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("payload_yaml");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public void saveGuildPayload(long guildId, String payloadYaml) {
        if (guildId <= 0L || payloadYaml == null) {
            return;
        }
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO guild_settings_snapshot (guild_id, payload_yaml, updated_at)
                     VALUES (?, ?, ?)
                     ON CONFLICT(guild_id) DO UPDATE SET
                         payload_yaml = excluded.payload_yaml,
                         updated_at = excluded.updated_at
                     """)) {
            statement.setLong(1, guildId);
            statement.setString(2, payloadYaml);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
    }
}
