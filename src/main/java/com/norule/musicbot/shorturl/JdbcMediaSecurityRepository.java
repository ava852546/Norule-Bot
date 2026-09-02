package com.norule.musicbot.shorturl;

import com.norule.musicbot.domain.shorturl.MediaPasswordAttemptLock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

abstract class JdbcMediaSecurityRepository implements MediaSecurityRepository {
    @FunctionalInterface
    protected interface ConnectionProvider {
        Connection open() throws SQLException;
    }

    private record IdentityLink(String quotaGroupId, String discordUserId,
                                long linkedAt, long lastSeenAt) {
    }

    private final ConnectionProvider connections;

    protected JdbcMediaSecurityRepository(ConnectionProvider connections, boolean mysql) {
        this.connections = connections;
        initializeSchema(mysql);
    }

    @Override
    public MediaPasswordAttemptLock findPasswordAttemptLock(String shareCode, String ipHash) {
        String sql = """
                SELECT share_code, ip_hash, failed_attempts, first_failure_at, last_failure_at,
                       next_allowed_attempt_at, locked_until
                FROM media_password_attempt_locks WHERE share_code = ? AND ip_hash = ?
                """;
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, shareCode);
            statement.setString(2, ipHash);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? mapLock(rows) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query media password attempt lock", e);
        }
    }

    @Override
    public synchronized void savePasswordAttemptLock(MediaPasswordAttemptLock lock) {
        String update = """
                UPDATE media_password_attempt_locks
                SET failed_attempts = ?, first_failure_at = ?, last_failure_at = ?,
                    next_allowed_attempt_at = ?, locked_until = ?
                WHERE share_code = ? AND ip_hash = ?
                """;
        String insert = """
                INSERT INTO media_password_attempt_locks
                    (share_code, ip_hash, failed_attempts, first_failure_at, last_failure_at,
                     next_allowed_attempt_at, locked_until)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = connections.open()) {
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setInt(1, lock.failedAttempts());
                statement.setLong(2, lock.firstFailureAt());
                statement.setLong(3, lock.lastFailureAt());
                statement.setLong(4, lock.nextAllowedAttemptAt());
                statement.setLong(5, lock.lockedUntil());
                statement.setString(6, lock.shareCode());
                statement.setString(7, lock.ipHash());
                if (statement.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, lock.shareCode());
                statement.setString(2, lock.ipHash());
                statement.setInt(3, lock.failedAttempts());
                statement.setLong(4, lock.firstFailureAt());
                statement.setLong(5, lock.lastFailureAt());
                statement.setLong(6, lock.nextAllowedAttemptAt());
                statement.setLong(7, lock.lockedUntil());
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save media password attempt lock", e);
        }
    }

    @Override
    public void deletePasswordAttemptLock(String shareCode, String ipHash) {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM media_password_attempt_locks WHERE share_code = ? AND ip_hash = ?")) {
            statement.setString(1, shareCode);
            statement.setString(2, ipHash);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear media password attempt lock", e);
        }
    }

    @Override
    public synchronized String resolveOrCreateQuotaGroup(String identityType, String identityHash,
                                                         String linkedDiscordUserId, long nowMillis) {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                IdentityLink existing = findIdentity(connection, identityType, identityHash);
                if (existing != null) {
                    touchIdentity(connection, identityType, identityHash, nowMillis);
                    connection.commit();
                    return existing.quotaGroupId();
                }
                String quotaGroupId = "Q" + UUID.randomUUID().toString().replace("-", "");
                try (PreparedStatement group = connection.prepareStatement(
                        "INSERT INTO media_quota_groups (quota_group_id, created_at, updated_at) VALUES (?, ?, ?)")) {
                    group.setString(1, quotaGroupId);
                    group.setLong(2, nowMillis);
                    group.setLong(3, nowMillis);
                    group.executeUpdate();
                }
                insertIdentity(connection, identityType, identityHash, quotaGroupId,
                        linkedDiscordUserId, nowMillis);
                connection.commit();
                return quotaGroupId;
            } catch (SQLException | RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve media quota identity", e);
        }
    }

    @Override
    public synchronized IdentityMergeResult mergeAnonymousDeviceIntoDiscord(String deviceHash,
                                                                            String discordIdentityHash,
                                                                            String discordUserId,
                                                                            long nowMillis,
                                                                            long recentActivityCutoffMillis,
                                                                            long accountSwitchCooldownMillis) {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try {
                IdentityLink device = findIdentity(connection, "ANON_DEVICE", deviceHash);
                if (device == null || device.lastSeenAt() < recentActivityCutoffMillis) {
                    connection.rollback();
                    return new IdentityMergeResult(IdentityMergeStatus.NO_RECENT_DEVICE_ACTIVITY, "");
                }
                if (!device.discordUserId().isBlank() && !device.discordUserId().equals(discordUserId)
                        && nowMillis - device.linkedAt() < accountSwitchCooldownMillis) {
                    touchIdentity(connection, "ANON_DEVICE", deviceHash, nowMillis);
                    connection.commit();
                    return new IdentityMergeResult(IdentityMergeStatus.ACCOUNT_SWITCH_BLOCKED,
                            device.quotaGroupId());
                }

                IdentityLink discord = findIdentity(connection, "DISCORD", discordIdentityHash);
                if (discord != null && discord.quotaGroupId().equals(device.quotaGroupId())
                        && discordUserId.equals(device.discordUserId())) {
                    touchIdentity(connection, "ANON_DEVICE", deviceHash, nowMillis);
                    touchIdentity(connection, "DISCORD", discordIdentityHash, nowMillis);
                    connection.commit();
                    return new IdentityMergeResult(IdentityMergeStatus.ALREADY_MERGED,
                            device.quotaGroupId());
                }

                String sourceGroup = device.quotaGroupId();
                String targetGroup = discord == null ? sourceGroup : discord.quotaGroupId();
                if (!sourceGroup.equals(targetGroup)) {
                    updateGroup(connection, "media_identity_links", sourceGroup, targetGroup);
                    updateGroup(connection, "media_upload_events", sourceGroup, targetGroup);
                }
                migrateActiveOwnership(connection, sourceGroup, targetGroup, discordUserId);
                if (discord == null) {
                    insertIdentity(connection, "DISCORD", discordIdentityHash, targetGroup,
                            discordUserId, nowMillis);
                } else {
                    touchIdentity(connection, "DISCORD", discordIdentityHash, nowMillis);
                }
                updateDeviceLink(connection, deviceHash, targetGroup, discordUserId, nowMillis);
                touchQuotaGroup(connection, targetGroup, nowMillis);
                connection.commit();
                return new IdentityMergeResult(IdentityMergeStatus.MERGED, targetGroup);
            } catch (SQLException | RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to merge anonymous media identity", e);
        }
    }

    @Override
    public void recordUploadEvent(String quotaGroupId, String ipHash, long createdAt,
                                  long sizeBytes, boolean success) {
        String sql = """
                INSERT INTO media_upload_events
                    (quota_group_id, ip_hash, created_at, size_bytes, success)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, quotaGroupId);
            statement.setString(2, ipHash);
            statement.setLong(3, createdAt);
            statement.setLong(4, Math.max(0L, sizeBytes));
            statement.setBoolean(5, success);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record media upload event", e);
        }
    }

    @Override
    public long countSuccessfulUploads(String quotaGroupId, long sinceMillis) {
        String sql = "SELECT COUNT(*) FROM media_upload_events WHERE quota_group_id = ? AND success = 1 AND created_at >= ?";
        return queryLong(sql, quotaGroupId, sinceMillis);
    }

    @Override
    public long activeStorageBytes(String quotaGroupId) {
        return activeStorageBytesInternal(quotaGroupId, null);
    }

    @Override
    public long activeStorageBytes(String quotaGroupId, long nowMillis) {
        return activeStorageBytesInternal(quotaGroupId, Math.max(0L, nowMillis));
    }

    private long activeStorageBytesInternal(String quotaGroupId, Long nowMillis) {
        String sql = "SELECT COALESCE(SUM(size_bytes), 0) FROM short_url_images"
                + " WHERE quota_group_id = ? AND storage_state = 'ACTIVE'"
                + (nowMillis == null ? "" : " AND expires_at > ?");
        return queryLong(sql, quotaGroupId, nowMillis);
    }

    @Override
    public long globalManagedStorageBytes() {
        String sql = "SELECT COALESCE(SUM(size_bytes), 0) FROM media_blobs"
                + " WHERE storage_state IN ('ACTIVE', 'ARCHIVE_PENDING', 'ARCHIVED')";
        try {
            return queryGlobalStorageBytes(sql);
        } catch (IllegalStateException exception) {
            if (!isMissingTable(exception, "media_blobs")) {
                throw exception;
            }
            return queryGlobalStorageBytes("SELECT COALESCE(SUM(size_bytes), 0) FROM short_url_images"
                    + " WHERE storage_state IN ('ACTIVE', 'ARCHIVE_PENDING', 'ARCHIVED')");
        }
    }

    private long queryGlobalStorageBytes(String sql) {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query global managed media storage", e);
        }
    }

    private boolean isMissingTable(Throwable throwable, String tableName) {
        String expected = tableName == null ? "" : tableName.toLowerCase();
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String message = sqlException.getMessage() == null
                        ? ""
                        : sqlException.getMessage().toLowerCase();
                if ((sqlException.getErrorCode() == 1146
                        || "42S02".equalsIgnoreCase(sqlException.getSQLState())
                        || message.contains("no such table"))
                        && message.contains(expected)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private long queryLong(String sql, String quotaGroupId, Long sinceMillis) {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, quotaGroupId);
            if (sinceMillis != null) {
                statement.setLong(2, sinceMillis);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query media quota usage", e);
        }
    }

    private void initializeSchema(boolean mysql) {
        String idType = mysql ? "VARCHAR(64)" : "TEXT";
        String longType = mysql ? "BIGINT" : "INTEGER";
        String eventId = mysql ? "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String boolType = mysql ? "BOOLEAN" : "INTEGER";
        try (Connection connection = connections.open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS media_quota_groups (quota_group_id " + idType
                    + " PRIMARY KEY, created_at " + longType + " NOT NULL, updated_at " + longType + " NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS media_identity_links (identity_type " + idType
                    + " NOT NULL, identity_hash " + idType + " NOT NULL, quota_group_id " + idType
                    + " NOT NULL, linked_discord_user_id " + idType + " NOT NULL DEFAULT '', linked_at "
                    + longType + " NOT NULL, last_seen_at " + longType
                    + " NOT NULL, PRIMARY KEY (identity_type, identity_hash))");
            statement.execute("CREATE TABLE IF NOT EXISTS media_upload_events (event_id " + eventId
                    + ", quota_group_id " + idType + " NOT NULL, ip_hash " + idType
                    + " NOT NULL, created_at " + longType + " NOT NULL, size_bytes " + longType
                    + " NOT NULL, success " + boolType + " NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS media_password_attempt_locks (share_code " + idType
                    + " NOT NULL, ip_hash " + idType + " NOT NULL, failed_attempts INTEGER NOT NULL, first_failure_at "
                    + longType + " NOT NULL, last_failure_at " + longType + " NOT NULL, next_allowed_attempt_at "
                    + longType + " NOT NULL, locked_until " + longType
                    + " NOT NULL, PRIMARY KEY (share_code, ip_hash))");
            createIndex(statement, mysql, "idx_media_upload_events_group_time",
                    "media_upload_events(quota_group_id, created_at)");
            createIndex(statement, mysql, "idx_media_identity_links_group",
                    "media_identity_links(quota_group_id)");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize media security schema", e);
        }
    }

    private void createIndex(Statement statement, boolean mysql, String name, String target) throws SQLException {
        try {
            statement.execute("CREATE INDEX " + (mysql ? "" : "IF NOT EXISTS ") + name + " ON " + target);
        } catch (SQLException e) {
            if (!mysql || !indexAlreadyExists(e)) {
                throw e;
            }
        }
    }

    private boolean indexAlreadyExists(SQLException e) {
        return e.getErrorCode() == 1061 || "42000".equals(e.getSQLState());
    }

    private MediaPasswordAttemptLock mapLock(ResultSet rows) throws SQLException {
        return new MediaPasswordAttemptLock(
                rows.getString("share_code"), rows.getString("ip_hash"),
                rows.getInt("failed_attempts"), rows.getLong("first_failure_at"),
                rows.getLong("last_failure_at"), rows.getLong("next_allowed_attempt_at"),
                rows.getLong("locked_until"));
    }

    private IdentityLink findIdentity(Connection connection, String identityType,
                                      String identityHash) throws SQLException {
        String sql = """
                SELECT quota_group_id, linked_discord_user_id, linked_at, last_seen_at
                FROM media_identity_links WHERE identity_type = ? AND identity_hash = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identityType);
            statement.setString(2, identityHash);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new IdentityLink(rows.getString(1), rows.getString(2),
                        rows.getLong(3), rows.getLong(4)) : null;
            }
        }
    }

    private void insertIdentity(Connection connection, String identityType, String identityHash,
                                String groupId, String discordUserId, long nowMillis) throws SQLException {
        String sql = """
                INSERT INTO media_identity_links
                    (identity_type, identity_hash, quota_group_id, linked_discord_user_id, linked_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identityType);
            statement.setString(2, identityHash);
            statement.setString(3, groupId);
            statement.setString(4, safe(discordUserId));
            statement.setLong(5, nowMillis);
            statement.setLong(6, nowMillis);
            statement.executeUpdate();
        }
    }

    private void touchIdentity(Connection connection, String identityType, String identityHash,
                               long nowMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE media_identity_links SET last_seen_at = ? WHERE identity_type = ? AND identity_hash = ?")) {
            statement.setLong(1, nowMillis);
            statement.setString(2, identityType);
            statement.setString(3, identityHash);
            statement.executeUpdate();
        }
    }

    private void updateDeviceLink(Connection connection, String deviceHash, String targetGroup,
                                  String discordUserId, long nowMillis) throws SQLException {
        String sql = """
                UPDATE media_identity_links
                SET quota_group_id = ?, linked_discord_user_id = ?, linked_at = ?, last_seen_at = ?
                WHERE identity_type = 'ANON_DEVICE' AND identity_hash = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetGroup);
            statement.setString(2, discordUserId);
            statement.setLong(3, nowMillis);
            statement.setLong(4, nowMillis);
            statement.setString(5, deviceHash);
            statement.executeUpdate();
        }
    }

    private void updateGroup(Connection connection, String table, String source,
                             String target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + table + " SET quota_group_id = ? WHERE quota_group_id = ?")) {
            statement.setString(1, target);
            statement.setString(2, source);
            statement.executeUpdate();
        }
    }

    private void migrateActiveOwnership(Connection connection, String sourceGroup, String targetGroup,
                                        String discordUserId) throws SQLException {
        String sql = """
                UPDATE short_url_images
                SET quota_group_id = ?, owner_type = 'DISCORD_USER', owner_id = ?
                WHERE quota_group_id = ? AND storage_state = 'ACTIVE'
                  AND owner_type = 'ANONYMOUS_DEVICE'
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, targetGroup);
            statement.setString(2, discordUserId);
            statement.setString(3, sourceGroup);
            statement.executeUpdate();
        }
    }

    private void touchQuotaGroup(Connection connection, String groupId, long nowMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE media_quota_groups SET updated_at = ? WHERE quota_group_id = ?")) {
            statement.setLong(1, nowMillis);
            statement.setString(2, groupId);
            statement.executeUpdate();
        }
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
