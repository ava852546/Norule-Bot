package com.norule.musicbot.storage.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModerationSqliteRepository {
    public record GuildSnapshot(
            boolean duplicateDetectionEnabled,
            boolean numberChainEnabled,
            Long numberChainChannelId,
            long numberChainNext,
            Long numberChainLastUserId,
            long numberChainHighestNumber,
            Map<Long, Long> contributors,
            Map<Long, Integer> warnings
    ) {
        public static GuildSnapshot empty() {
            return new GuildSnapshot(false, false, null, 1L, null, 0L, Map.of(), Map.of());
        }
    }

    private static final String SQL_CREATE_STATE = """
            CREATE TABLE IF NOT EXISTS moderation_guild_state (
                guild_id INTEGER PRIMARY KEY,
                duplicate_enabled INTEGER NOT NULL DEFAULT 0,
                chain_enabled INTEGER NOT NULL DEFAULT 0,
                chain_channel_id TEXT NOT NULL DEFAULT '',
                chain_next INTEGER NOT NULL DEFAULT 1,
                chain_last_user_id TEXT NOT NULL DEFAULT '',
                chain_highest INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """;
    private static final String SQL_CREATE_WARNINGS = """
            CREATE TABLE IF NOT EXISTS moderation_warnings (
                guild_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                warning_count INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (guild_id, user_id)
            )
            """;
    private static final String SQL_CREATE_CONTRIBUTORS = """
            CREATE TABLE IF NOT EXISTS moderation_chain_contributors (
                guild_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                success_count INTEGER NOT NULL,
                PRIMARY KEY (guild_id, user_id)
            )
            """;
    private static final String SQL_CREATE_ACTIONS = """
            CREATE TABLE IF NOT EXISTS moderation_actions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                guild_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                moderator_user_id TEXT NOT NULL DEFAULT '',
                action_type TEXT NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                result TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """;

    private final SqliteDatabase database;

    public ModerationSqliteRepository(SqliteDatabase database) {
        this.database = database;
        this.database.initializeSchema(
                SQL_CREATE_STATE,
                SQL_CREATE_WARNINGS,
                SQL_CREATE_CONTRIBUTORS,
                SQL_CREATE_ACTIONS
        );
    }

    public GuildSnapshot loadGuild(long guildId) {
        GuildSnapshot fallback = GuildSnapshot.empty();
        try (Connection connection = database.open()) {
            boolean duplicateEnabled = false;
            boolean chainEnabled = false;
            Long chainChannelId = null;
            long chainNext = 1L;
            Long chainLastUserId = null;
            long chainHighest = 0L;

            try (PreparedStatement state = connection.prepareStatement(
                    "SELECT duplicate_enabled, chain_enabled, chain_channel_id, chain_next, chain_last_user_id, chain_highest FROM moderation_guild_state WHERE guild_id = ?"
            )) {
                state.setLong(1, guildId);
                try (ResultSet rs = state.executeQuery()) {
                    if (rs.next()) {
                        duplicateEnabled = rs.getInt("duplicate_enabled") == 1;
                        chainEnabled = rs.getInt("chain_enabled") == 1;
                        chainChannelId = readNullableLong(rs.getString("chain_channel_id"));
                        chainNext = Math.max(1L, rs.getLong("chain_next"));
                        chainLastUserId = readNullableLong(rs.getString("chain_last_user_id"));
                        chainHighest = Math.max(0L, rs.getLong("chain_highest"));
                    }
                }
            }

            Map<Long, Long> contributors = new LinkedHashMap<>();
            try (PreparedStatement contributorsStmt = connection.prepareStatement(
                    "SELECT user_id, success_count FROM moderation_chain_contributors WHERE guild_id = ?"
            )) {
                contributorsStmt.setLong(1, guildId);
                try (ResultSet rs = contributorsStmt.executeQuery()) {
                    while (rs.next()) {
                        long userId = rs.getLong("user_id");
                        long count = rs.getLong("success_count");
                        if (userId > 0L && count > 0L) {
                            contributors.put(userId, count);
                        }
                    }
                }
            }

            Map<Long, Integer> warnings = new LinkedHashMap<>();
            try (PreparedStatement warningsStmt = connection.prepareStatement(
                    "SELECT user_id, warning_count FROM moderation_warnings WHERE guild_id = ?"
            )) {
                warningsStmt.setLong(1, guildId);
                try (ResultSet rs = warningsStmt.executeQuery()) {
                    while (rs.next()) {
                        long userId = rs.getLong("user_id");
                        int count = rs.getInt("warning_count");
                        if (userId > 0L && count > 0) {
                            warnings.put(userId, count);
                        }
                    }
                }
            }

            return new GuildSnapshot(
                    duplicateEnabled,
                    chainEnabled,
                    chainChannelId,
                    chainNext,
                    chainLastUserId,
                    chainHighest,
                    contributors,
                    warnings
            );
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public void replaceGuild(long guildId, GuildSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        long now = System.currentTimeMillis();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO moderation_guild_state (
                        guild_id, duplicate_enabled, chain_enabled, chain_channel_id, chain_next, chain_last_user_id, chain_highest, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(guild_id) DO UPDATE SET
                        duplicate_enabled = excluded.duplicate_enabled,
                        chain_enabled = excluded.chain_enabled,
                        chain_channel_id = excluded.chain_channel_id,
                        chain_next = excluded.chain_next,
                        chain_last_user_id = excluded.chain_last_user_id,
                        chain_highest = excluded.chain_highest,
                        updated_at = excluded.updated_at
                    """);
                 PreparedStatement clearWarnings = connection.prepareStatement(
                         "DELETE FROM moderation_warnings WHERE guild_id = ?"
                 );
                 PreparedStatement insertWarning = connection.prepareStatement("""
                         INSERT INTO moderation_warnings (guild_id, user_id, warning_count, updated_at)
                         VALUES (?, ?, ?, ?)
                         """);
                 PreparedStatement clearContributors = connection.prepareStatement(
                         "DELETE FROM moderation_chain_contributors WHERE guild_id = ?"
                 );
                 PreparedStatement insertContributor = connection.prepareStatement("""
                         INSERT INTO moderation_chain_contributors (guild_id, user_id, success_count)
                         VALUES (?, ?, ?)
                         """)) {
                upsert.setLong(1, guildId);
                upsert.setInt(2, snapshot.duplicateDetectionEnabled() ? 1 : 0);
                upsert.setInt(3, snapshot.numberChainEnabled() ? 1 : 0);
                upsert.setString(4, snapshot.numberChainChannelId() == null ? "" : String.valueOf(snapshot.numberChainChannelId()));
                upsert.setLong(5, Math.max(1L, snapshot.numberChainNext()));
                upsert.setString(6, snapshot.numberChainLastUserId() == null ? "" : String.valueOf(snapshot.numberChainLastUserId()));
                upsert.setLong(7, Math.max(0L, snapshot.numberChainHighestNumber()));
                upsert.setLong(8, now);
                upsert.executeUpdate();

                clearWarnings.setLong(1, guildId);
                clearWarnings.executeUpdate();
                for (Map.Entry<Long, Integer> entry : snapshot.warnings().entrySet()) {
                    if (entry.getKey() == null || entry.getKey() <= 0L || entry.getValue() == null || entry.getValue() <= 0) {
                        continue;
                    }
                    insertWarning.setLong(1, guildId);
                    insertWarning.setLong(2, entry.getKey());
                    insertWarning.setInt(3, entry.getValue());
                    insertWarning.setLong(4, now);
                    insertWarning.addBatch();
                }
                insertWarning.executeBatch();

                clearContributors.setLong(1, guildId);
                clearContributors.executeUpdate();
                for (Map.Entry<Long, Long> entry : snapshot.contributors().entrySet()) {
                    if (entry.getKey() == null || entry.getKey() <= 0L || entry.getValue() == null || entry.getValue() <= 0L) {
                        continue;
                    }
                    insertContributor.setLong(1, guildId);
                    insertContributor.setLong(2, entry.getKey());
                    insertContributor.setLong(3, entry.getValue());
                    insertContributor.addBatch();
                }
                insertContributor.executeBatch();

                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception ignored) {
        }
    }

    public void appendAction(long guildId,
                             long userId,
                             Long moderatorUserId,
                             String actionType,
                             String reason,
                             String result,
                             long createdAtMillis) {
        if (guildId <= 0L || userId <= 0L || actionType == null || actionType.isBlank()) {
            return;
        }
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO moderation_actions (
                         guild_id, user_id, moderator_user_id, action_type, reason, result, created_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.setString(3, moderatorUserId == null ? "" : String.valueOf(moderatorUserId));
            statement.setString(4, actionType.trim().toUpperCase());
            statement.setString(5, reason == null ? "" : reason);
            statement.setString(6, result == null ? "" : result);
            statement.setLong(7, Math.max(0L, createdAtMillis));
            statement.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    private Long readNullableLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}
