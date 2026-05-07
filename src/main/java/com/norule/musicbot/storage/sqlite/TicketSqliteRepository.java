package com.norule.musicbot.storage.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class TicketSqliteRepository {
    public record StoredTicket(
            long channelId,
            long ownerId,
            String typeLabel,
            String summary,
            long openedAt,
            long lastInteractionAt,
            boolean closed,
            long closedAt,
            String closeReason,
            Long closedByUserId,
            String transcriptPath,
            Set<Long> participants
    ) {
    }

    private static final String SQL_CREATE_TICKETS = """
            CREATE TABLE IF NOT EXISTS ticket_records (
                guild_id INTEGER NOT NULL,
                channel_id INTEGER NOT NULL,
                owner_id INTEGER NOT NULL,
                type_label TEXT NOT NULL DEFAULT '',
                summary TEXT NOT NULL DEFAULT '',
                opened_at INTEGER NOT NULL,
                last_interaction_at INTEGER NOT NULL,
                closed INTEGER NOT NULL DEFAULT 0,
                closed_at INTEGER NOT NULL DEFAULT 0,
                close_reason TEXT NOT NULL DEFAULT '',
                closed_by_user_id TEXT NOT NULL DEFAULT '',
                transcript_path TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (guild_id, channel_id)
            )
            """;
    private static final String SQL_CREATE_PARTICIPANTS = """
            CREATE TABLE IF NOT EXISTS ticket_participants (
                guild_id INTEGER NOT NULL,
                channel_id INTEGER NOT NULL,
                user_id INTEGER NOT NULL,
                PRIMARY KEY (guild_id, channel_id, user_id)
            )
            """;

    private final SqliteDatabase database;

    public TicketSqliteRepository(SqliteDatabase database) {
        this.database = database;
        this.database.initializeSchema(SQL_CREATE_TICKETS, SQL_CREATE_PARTICIPANTS);
    }

    public Map<Long, StoredTicket> loadGuild(long guildId) {
        Map<Long, StoredTicket> result = new LinkedHashMap<>();
        Map<Long, Set<Long>> participants = new LinkedHashMap<>();
        try (Connection connection = database.open()) {
            try (PreparedStatement participantStmt = connection.prepareStatement(
                    "SELECT channel_id, user_id FROM ticket_participants WHERE guild_id = ?"
            )) {
                participantStmt.setLong(1, guildId);
                try (ResultSet rs = participantStmt.executeQuery()) {
                    while (rs.next()) {
                        long channelId = rs.getLong("channel_id");
                        long userId = rs.getLong("user_id");
                        if (channelId <= 0L || userId <= 0L) {
                            continue;
                        }
                        participants.computeIfAbsent(channelId, ignored -> new LinkedHashSet<>()).add(userId);
                    }
                }
            }

            try (PreparedStatement ticketStmt = connection.prepareStatement("""
                    SELECT channel_id, owner_id, type_label, summary, opened_at, last_interaction_at,
                           closed, closed_at, close_reason, closed_by_user_id, transcript_path
                    FROM ticket_records
                    WHERE guild_id = ?
                    """)) {
                ticketStmt.setLong(1, guildId);
                try (ResultSet rs = ticketStmt.executeQuery()) {
                    while (rs.next()) {
                        long channelId = rs.getLong("channel_id");
                        long ownerId = rs.getLong("owner_id");
                        if (channelId <= 0L || ownerId <= 0L) {
                            continue;
                        }
                        Set<Long> rowParticipants = participants.getOrDefault(channelId, Set.of(ownerId));
                        if (rowParticipants.isEmpty()) {
                            rowParticipants = Set.of(ownerId);
                        }
                        result.put(channelId, new StoredTicket(
                                channelId,
                                ownerId,
                                rs.getString("type_label"),
                                rs.getString("summary"),
                                rs.getLong("opened_at"),
                                rs.getLong("last_interaction_at"),
                                rs.getInt("closed") == 1,
                                rs.getLong("closed_at"),
                                rs.getString("close_reason"),
                                readNullableLong(rs.getString("closed_by_user_id")),
                                rs.getString("transcript_path"),
                                Set.copyOf(rowParticipants)
                        ));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public void replaceGuild(long guildId, Collection<StoredTicket> tickets) {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement clearTickets = connection.prepareStatement("DELETE FROM ticket_records WHERE guild_id = ?");
                 PreparedStatement clearParticipants = connection.prepareStatement("DELETE FROM ticket_participants WHERE guild_id = ?");
                 PreparedStatement insertTicket = connection.prepareStatement("""
                         INSERT INTO ticket_records (
                             guild_id, channel_id, owner_id, type_label, summary, opened_at, last_interaction_at,
                             closed, closed_at, close_reason, closed_by_user_id, transcript_path
                         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """);
                 PreparedStatement insertParticipant = connection.prepareStatement("""
                         INSERT INTO ticket_participants (guild_id, channel_id, user_id)
                         VALUES (?, ?, ?)
                         """)) {
                clearTickets.setLong(1, guildId);
                clearTickets.executeUpdate();
                clearParticipants.setLong(1, guildId);
                clearParticipants.executeUpdate();

                if (tickets != null) {
                    for (StoredTicket ticket : tickets) {
                        if (ticket == null || ticket.channelId() <= 0L || ticket.ownerId() <= 0L) {
                            continue;
                        }
                        insertTicket.setLong(1, guildId);
                        insertTicket.setLong(2, ticket.channelId());
                        insertTicket.setLong(3, ticket.ownerId());
                        insertTicket.setString(4, safeText(ticket.typeLabel()));
                        insertTicket.setString(5, safeText(ticket.summary()));
                        insertTicket.setLong(6, Math.max(0L, ticket.openedAt()));
                        insertTicket.setLong(7, Math.max(0L, ticket.lastInteractionAt()));
                        insertTicket.setInt(8, ticket.closed() ? 1 : 0);
                        insertTicket.setLong(9, Math.max(0L, ticket.closedAt()));
                        insertTicket.setString(10, safeText(ticket.closeReason()));
                        insertTicket.setString(11, ticket.closedByUserId() == null ? "" : String.valueOf(ticket.closedByUserId()));
                        insertTicket.setString(12, safeText(ticket.transcriptPath()));
                        insertTicket.addBatch();

                        if (ticket.participants() != null) {
                            for (Long userId : ticket.participants()) {
                                if (userId == null || userId <= 0L) {
                                    continue;
                                }
                                insertParticipant.setLong(1, guildId);
                                insertParticipant.setLong(2, ticket.channelId());
                                insertParticipant.setLong(3, userId);
                                insertParticipant.addBatch();
                            }
                        }
                    }
                }
                insertTicket.executeBatch();
                insertParticipant.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception ignored) {
        }
    }

    private String safeText(String value) {
        return value == null ? "" : value;
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
