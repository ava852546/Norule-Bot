package com.norule.musicbot.storage.sqlite;

import com.norule.musicbot.domain.music.MusicPanelBinding;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Uses the existing bot database independently of channel provisioning claims. */
public final class MusicPanelSqliteRepository {
    private final SqliteDatabase database;

    public MusicPanelSqliteRepository(SqliteDatabase database) {
        this.database = database;
        database.initializeSchema("""
                CREATE TABLE IF NOT EXISTS music_panels (
                    guild_id INTEGER PRIMARY KEY,
                    channel_id INTEGER NOT NULL,
                    message_id INTEGER NOT NULL,
                    panel_type TEXT NOT NULL CHECK (panel_type = 'MUSIC')
                )
                """);
    }

    public List<MusicPanelBinding> findAll() {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM music_panels");
             ResultSet result = statement.executeQuery()) {
            List<MusicPanelBinding> panels = new ArrayList<>();
            while (result.next()) {
                panels.add(new MusicPanelBinding(result.getLong("guild_id"),
                        result.getLong("channel_id"), result.getLong("message_id")));
            }
            return panels;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load music panel bindings", e);
        }
    }

    public void save(MusicPanelBinding panel) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO music_panels (guild_id, channel_id, message_id, panel_type)
                     VALUES (?, ?, ?, 'MUSIC')
                     ON CONFLICT(guild_id) DO UPDATE SET channel_id = excluded.channel_id,
                         message_id = excluded.message_id
                     """)) {
            statement.setLong(1, panel.guildId());
            statement.setLong(2, panel.channelId());
            statement.setLong(3, panel.messageId());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save music panel binding", e);
        }
    }

    public void delete(long guildId) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM music_panels WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete music panel binding", e);
        }
    }
}
