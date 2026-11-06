package com.norule.musicbot.storage.sqlite;

import com.norule.musicbot.domain.music.MusicDiagnostics.Event;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Additive migration, one request and one confirmed playback per guild. */
public final class MusicDiagnosticsSqliteRepository {
    private final SqliteDatabase database;

    public MusicDiagnosticsSqliteRepository(SqliteDatabase database) {
        this.database = database;
        database.initializeSchema("""
                CREATE TABLE IF NOT EXISTS music_diagnostics_history (
                    guild_id INTEGER NOT NULL, kind TEXT NOT NULL,
                    at_millis INTEGER NOT NULL, channel_id INTEGER, requester_id INTEGER,
                    source TEXT, title TEXT, public_url TEXT, outcome TEXT,
                    PRIMARY KEY (guild_id, kind)
                )
                """);
    }

    public List<Event> load() {
        try (var connection = database.open(); var statement = connection.prepareStatement("SELECT * FROM music_diagnostics_history");
             var rows = statement.executeQuery()) {
            List<Event> result = new ArrayList<>();
            while (rows.next()) result.add(new Event(rows.getLong("guild_id"), rows.getString("kind"),
                    Instant.ofEpochMilli(rows.getLong("at_millis")), nullableLong(rows, "channel_id"),
                    nullableLong(rows, "requester_id"), rows.getString("source"), rows.getString("title"),
                    rows.getString("public_url"), rows.getString("outcome")));
            return result;
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot load music diagnostics history", error);
        }
    }

    public void save(Event event) {
        try (var connection = database.open(); var statement = connection.prepareStatement("""
                INSERT INTO music_diagnostics_history VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(guild_id, kind) DO UPDATE SET at_millis=excluded.at_millis,
                    channel_id=excluded.channel_id, requester_id=excluded.requester_id,
                    source=excluded.source, title=excluded.title, public_url=excluded.public_url, outcome=excluded.outcome
                WHERE excluded.at_millis >= music_diagnostics_history.at_millis
                """)) {
            statement.setLong(1, event.guildId()); statement.setString(2, event.kind());
            statement.setLong(3, event.at().toEpochMilli()); statement.setObject(4, event.channelId());
            statement.setObject(5, event.requesterId()); statement.setString(6, event.source());
            statement.setString(7, event.title()); statement.setString(8, event.publicUrl());
            statement.setString(9, event.outcome()); statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot save music diagnostics history", error);
        }
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }
}
