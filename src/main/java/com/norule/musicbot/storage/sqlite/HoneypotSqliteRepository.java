package com.norule.musicbot.storage.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HoneypotSqliteRepository {
    private static final String SQL_CREATE_CHANNELS = """
            CREATE TABLE IF NOT EXISTS honeypot_channels (
                guild_id INTEGER PRIMARY KEY,
                channel_id INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """;
    private static final String SQL_CREATE_EVENTS = """
            CREATE TABLE IF NOT EXISTS honeypot_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                guild_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                channel_id INTEGER NOT NULL,
                message_id TEXT NOT NULL DEFAULT '',
                action_result TEXT NOT NULL DEFAULT '',
                deleted_message_count INTEGER NOT NULL DEFAULT 0,
                kicked INTEGER NOT NULL DEFAULT 0,
                note TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """;
    private static final String SQL_CREATE_SUSPECTS = """
            CREATE TABLE IF NOT EXISTS honeypot_suspects (
                guild_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                trigger_count INTEGER NOT NULL,
                last_result TEXT NOT NULL DEFAULT '',
                last_triggered_at INTEGER NOT NULL,
                PRIMARY KEY (guild_id, user_id)
            )
            """;

    private final SqliteDatabase database;

    public HoneypotSqliteRepository(SqliteDatabase database) {
        this.database = database;
        this.database.initializeSchema(SQL_CREATE_CHANNELS, SQL_CREATE_EVENTS, SQL_CREATE_SUSPECTS);
    }

    public Map<Long, Long> loadChannels() {
        Map<Long, Long> result = new LinkedHashMap<>();
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT guild_id, channel_id FROM honeypot_channels"
             );
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                long guildId = rs.getLong("guild_id");
                long channelId = rs.getLong("channel_id");
                if (guildId > 0L && channelId > 0L) {
                    result.put(guildId, channelId);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public void replaceChannels(Map<Long, Long> channels) {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement clear = connection.prepareStatement("DELETE FROM honeypot_channels");
                 PreparedStatement insert = connection.prepareStatement("""
                         INSERT INTO honeypot_channels (guild_id, channel_id, updated_at)
                         VALUES (?, ?, ?)
                         """)) {
                clear.executeUpdate();
                long now = System.currentTimeMillis();
                if (channels != null) {
                    for (Map.Entry<Long, Long> entry : channels.entrySet()) {
                        if (entry.getKey() == null || entry.getKey() <= 0L || entry.getValue() == null || entry.getValue() <= 0L) {
                            continue;
                        }
                        insert.setLong(1, entry.getKey());
                        insert.setLong(2, entry.getValue());
                        insert.setLong(3, now);
                        insert.addBatch();
                    }
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception ignored) {
        }
    }

    public void appendTriggerEvent(long guildId,
                                   long userId,
                                   long channelId,
                                   String messageId,
                                   String actionResult,
                                   int deletedMessageCount,
                                   boolean kicked,
                                   String note,
                                   long createdAtMillis) {
        if (guildId <= 0L || userId <= 0L || channelId <= 0L) {
            return;
        }
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement event = connection.prepareStatement("""
                    INSERT INTO honeypot_events (
                        guild_id, user_id, channel_id, message_id, action_result, deleted_message_count, kicked, note, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
                 PreparedStatement upsertSuspect = connection.prepareStatement("""
                         INSERT INTO honeypot_suspects (guild_id, user_id, trigger_count, last_result, last_triggered_at)
                         VALUES (?, ?, 1, ?, ?)
                         ON CONFLICT(guild_id, user_id) DO UPDATE SET
                             trigger_count = honeypot_suspects.trigger_count + 1,
                             last_result = excluded.last_result,
                             last_triggered_at = excluded.last_triggered_at
                         """)) {
                event.setLong(1, guildId);
                event.setLong(2, userId);
                event.setLong(3, channelId);
                event.setString(4, messageId == null ? "" : messageId);
                event.setString(5, actionResult == null ? "" : actionResult);
                event.setInt(6, Math.max(0, deletedMessageCount));
                event.setInt(7, kicked ? 1 : 0);
                event.setString(8, note == null ? "" : note);
                event.setLong(9, Math.max(0L, createdAtMillis));
                event.executeUpdate();

                upsertSuspect.setLong(1, guildId);
                upsertSuspect.setLong(2, userId);
                upsertSuspect.setString(3, actionResult == null ? "" : actionResult);
                upsertSuspect.setLong(4, Math.max(0L, createdAtMillis));
                upsertSuspect.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception ignored) {
        }
    }
}
