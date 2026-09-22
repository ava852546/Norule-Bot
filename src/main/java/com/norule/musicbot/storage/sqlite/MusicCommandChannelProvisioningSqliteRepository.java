package com.norule.musicbot.storage.sqlite;

import com.norule.musicbot.domain.music.MusicCommandChannelProvisioning;
import com.norule.musicbot.domain.music.MusicCommandChannelProvisioning.Status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public final class MusicCommandChannelProvisioningSqliteRepository {
    private final SqliteDatabase database;

    public MusicCommandChannelProvisioningSqliteRepository(SqliteDatabase database) {
        this.database = database;
        database.initializeSchema("""
                CREATE TABLE IF NOT EXISTS music_command_channel_provisioning (
                    guild_id INTEGER PRIMARY KEY,
                    status TEXT NOT NULL CHECK (status IN ('ATTEMPTING', 'SUCCESS', 'FAILED')),
                    attempted_at INTEGER NOT NULL,
                    failure_reason TEXT,
                    attempt_id TEXT NOT NULL
                )
                """);
    }

    public boolean tryInsert(MusicCommandChannelProvisioning attempt) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO music_command_channel_provisioning
                         (guild_id, status, attempted_at, failure_reason, attempt_id)
                     VALUES (?, 'ATTEMPTING', ?, NULL, ?)
                     ON CONFLICT(guild_id) DO NOTHING
                     """)) {
            statement.setLong(1, attempt.guildId());
            statement.setLong(2, attempt.attemptedAt().toEpochMilli());
            statement.setString(3, attempt.attemptId());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist music command channel provisioning attempt", e);
        }
    }

    public MusicCommandChannelProvisioning find(long guildId) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM music_command_channel_provisioning WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new MusicCommandChannelProvisioning(
                        guildId, Status.valueOf(result.getString("status")),
                        Instant.ofEpochMilli(result.getLong("attempted_at")),
                        result.getString("failure_reason"), result.getString("attempt_id")) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read music command channel provisioning state", e);
        }
    }

    public boolean complete(MusicCommandChannelProvisioning attempt, Status status, String failureReason) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE music_command_channel_provisioning SET status = ?, failure_reason = ?
                     WHERE guild_id = ? AND attempt_id = ? AND status = 'ATTEMPTING'
                     """)) {
            statement.setString(1, status.name());
            statement.setString(2, failureReason);
            statement.setLong(3, attempt.guildId());
            statement.setString(4, attempt.attemptId());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to complete music command channel provisioning state", e);
        }
    }

    public void delete(long guildId) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM music_command_channel_provisioning WHERE guild_id = ?")) {
            statement.setLong(1, guildId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear music command channel provisioning state", e);
        }
    }
}
