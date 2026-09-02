package com.norule.musicbot.shorturl;

import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.MediaBlob;
import com.norule.musicbot.domain.shorturl.MediaOwnerType;
import com.norule.musicbot.domain.shorturl.MediaStorageState;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class MySqlImageShareRepository implements ImageShareRepository, AutoCloseable {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS short_url_images (
                code VARCHAR(64) NOT NULL,
                storage_name VARCHAR(128) NOT NULL,
                content_type VARCHAR(128) NOT NULL,
                size_bytes BIGINT NOT NULL,
                created_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL,
                password_hash VARCHAR(512) NOT NULL DEFAULT '',
                content_hash CHAR(64) NOT NULL DEFAULT '',
                view_count BIGINT NOT NULL DEFAULT 0,
                storage_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                archive_storage_name VARCHAR(128) NOT NULL DEFAULT '',
                archived_at BIGINT NOT NULL DEFAULT 0,
                owner_type VARCHAR(32) NOT NULL DEFAULT 'ANONYMOUS_DEVICE',
                owner_id VARCHAR(128) NOT NULL DEFAULT '',
                quota_group_id VARCHAR(64) NOT NULL DEFAULT '',
                created_device_id_hash CHAR(64) NOT NULL DEFAULT '',
                created_ip_hash CHAR(64) NOT NULL DEFAULT '',
                last_accessed_at BIGINT NOT NULL DEFAULT 0,
                blob_id BIGINT NULL,
                active_reuse_key CHAR(64) NULL,
                PRIMARY KEY (code),
                KEY idx_short_url_images_expires (expires_at),
                KEY idx_short_url_images_content_hash_expires (content_hash, expires_at),
                KEY idx_short_url_images_owner_created (owner_type, owner_id, created_at),
                KEY idx_short_url_images_blob (blob_id),
                KEY idx_short_url_images_quota_active (quota_group_id, storage_state, expires_at),
                UNIQUE KEY uq_short_url_images_active_reuse (active_reuse_key),
                CONSTRAINT fk_short_url_images_blob FOREIGN KEY (blob_id) REFERENCES media_blobs(id)
                    ON DELETE RESTRICT ON UPDATE RESTRICT
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
    private static final String SELECT_FIELDS = "code, storage_name, content_type, size_bytes, created_at, expires_at, password_hash, content_hash, view_count, storage_state, archive_storage_name, archived_at, owner_type, owner_id, quota_group_id, created_device_id_hash, created_ip_hash, last_accessed_at, blob_id";
    private static final String SELECT_BY_CODE = "SELECT " + SELECT_FIELDS + " FROM short_url_images WHERE code = ?";
    private static final String SELECT_ACTIVE_BY_CONTENT_HASH = """
            SELECT %s
            FROM short_url_images
            WHERE content_hash = ? AND expires_at > ? AND storage_state = 'ACTIVE'
            ORDER BY created_at DESC
            """.formatted(SELECT_FIELDS);
    private static final String INSERT = "INSERT INTO short_url_images (code, storage_name, content_type, size_bytes, created_at, expires_at, password_hash, content_hash, view_count, storage_state, archive_storage_name, archived_at, owner_type, owner_id, quota_group_id, created_device_id_hash, created_ip_hash, last_accessed_at, blob_id, active_reuse_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE = "UPDATE short_url_images SET storage_name = ?, content_type = ?, size_bytes = ?, created_at = ?, expires_at = ?, password_hash = ?, content_hash = ?, view_count = ?, storage_state = ?, archive_storage_name = ?, archived_at = ?, owner_type = ?, owner_id = ?, quota_group_id = ?, created_device_id_hash = ?, created_ip_hash = ?, last_accessed_at = ?, blob_id = ? WHERE code = ?";
    private static final String DELETE_BY_CODE = "DELETE FROM short_url_images WHERE code = ?";
    private static final String SELECT_EXPIRED = "SELECT " + SELECT_FIELDS + " FROM short_url_images WHERE expires_at <= ? AND storage_state = 'ACTIVE'";
    private static final String INCREMENT_VIEW_COUNT = "UPDATE short_url_images SET view_count = view_count + 1 WHERE code = ?";
    private static final String INCREMENT_VIEW_METRICS = "UPDATE short_url_images SET view_count = view_count + 1, last_accessed_at = ? WHERE code = ?";
    private static final String SELECT_VIEW_COUNT = "SELECT view_count FROM short_url_images WHERE code = ?";

    private final HikariDataSource dataSource;

    public MySqlImageShareRepository(String jdbcUrl, String username, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, poolSize));
        config.setMinimumIdle(1);
        config.setPoolName("image-share-pool");
        config.setConnectionTimeout(10_000L);
        config.setValidationTimeout(5_000L);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        this.dataSource = new HikariDataSource(config);
        initializeSchema(dataSource);
    }

    @Override
    public ImageShare findByCode(String code) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_CODE)) {
            statement.setString(1, code);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapRow(resultSet) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query image share by code", e);
        }
    }

    @Override
    public List<ImageShare> findActiveByContentHash(String contentHash, long nowMillis) {
        if (contentHash == null || contentHash.isBlank()) {
            return List.of();
        }
        List<ImageShare> matches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ACTIVE_BY_CONTENT_HASH)) {
            statement.setString(1, contentHash);
            statement.setLong(2, nowMillis);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    matches.add(mapRow(resultSet));
                }
            }
            return matches;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query image share by content hash", e);
        }
    }

    @Override
    public ImageShare findActiveByOwnerAndBlob(MediaOwnerType ownerType, String ownerId,
                                                long blobId, long nowMillis) {
        String sql = "SELECT " + SELECT_FIELDS + " FROM short_url_images"
                + " WHERE owner_type = ? AND owner_id = ? AND blob_id = ?"
                + " AND expires_at > ? AND storage_state = 'ACTIVE' ORDER BY created_at DESC LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, (ownerType == null ? MediaOwnerType.ANONYMOUS_DEVICE : ownerType).name());
            statement.setString(2, ownerId == null ? "" : ownerId);
            statement.setLong(3, blobId);
            statement.setLong(4, nowMillis);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? mapRow(row) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query active image share by owner and blob", e);
        }
    }

    @Override
    public List<ImageShare> findByOwnerUserId(String ownerUserId, int offset, int limit) {
        return findByOwnerUserId(ownerUserId, null, 0L, offset, limit);
    }

    @Override
    public List<ImageShare> findByOwnerUserId(String ownerUserId,
                                              Boolean active,
                                              long nowMillis,
                                              int offset,
                                              int limit) {
        List<ImageShare> shares = new ArrayList<>();
        String statusClause = active == null ? ""
                : active ? " AND expires_at > ? AND storage_state = 'ACTIVE'"
                : " AND (expires_at <= ? OR storage_state <> 'ACTIVE')";
        String sql = "SELECT " + SELECT_FIELDS
                + " FROM short_url_images WHERE owner_type = 'DISCORD_USER' AND owner_id = ?"
                + statusClause + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, ownerUserId);
            if (active != null) {
                statement.setLong(index++, nowMillis);
            }
            statement.setInt(index++, Math.max(1, limit));
            statement.setInt(index, Math.max(0, offset));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    shares.add(mapRow(rows));
                }
            }
            return shares;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query image shares by owner", e);
        }
    }

    @Override
    public long countByOwnerUserId(String ownerUserId) {
        return countByOwnerUserId(ownerUserId, null, 0L);
    }

    @Override
    public long countByOwnerUserId(String ownerUserId, Boolean active, long nowMillis) {
        String statusClause = active == null ? ""
                : active ? " AND expires_at > ? AND storage_state = 'ACTIVE'"
                : " AND (expires_at <= ? OR storage_state <> 'ACTIVE')";
        String sql = "SELECT COUNT(*) FROM short_url_images"
                + " WHERE owner_type = 'DISCORD_USER' AND owner_id = ?" + statusClause;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUserId);
            if (active != null) {
                statement.setLong(2, nowMillis);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count image shares by owner", e);
        }
    }

    @Override
    public void save(ImageShare imageShare) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            bindInsert(statement, imageShare);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save image share", e);
        }
    }

    @Override
    public boolean saveIfAbsent(ImageShare imageShare) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            bindInsert(statement, imageShare);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            if ("23000".equals(e.getSQLState()) || e.getErrorCode() == 1062) {
                return false;
            }
            throw new IllegalStateException("Failed to save image share", e);
        }
    }

    @Override
    public void update(ImageShare imageShare) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPDATE)) {
            bindUpdate(statement, imageShare);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update image share", e);
        }
    }

    @Override
    public void deleteByCode(String code) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_BY_CODE)) {
            statement.setString(1, code);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete image share", e);
        }
    }

    @Override
    public List<ImageShare> findWithoutBlob(int limit) {
        List<ImageShare> shares = new ArrayList<>();
        String sql = "SELECT " + SELECT_FIELDS + " FROM short_url_images"
                + " WHERE blob_id IS NULL OR blob_id = 0"
                + " ORDER BY CASE WHEN storage_state = 'ACTIVE' THEN 0 ELSE 1 END, created_at DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    shares.add(mapRow(rows));
                }
            }
            return shares;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query legacy image shares", e);
        }
    }

    @Override
    public List<ImageShare> findLinkedStorageMismatches(int limit) {
        List<ImageShare> shares = new ArrayList<>();
        String qualifiedFields = "s." + SELECT_FIELDS.replace(", ", ", s.");
        String sql = "SELECT " + qualifiedFields + " FROM short_url_images s"
                + " JOIN media_blobs b ON b.id = s.blob_id"
                + " WHERE s.storage_name <> b.storage_name LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    shares.add(mapRow(rows));
                }
            }
            return shares;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1146) {
                return List.of();
            }
            throw new IllegalStateException("Failed to query migrated media storage mismatches", e);
        }
    }

    @Override
    public void attachBlob(String code, long blobId, String sha256) {
        String sql = "UPDATE short_url_images SET blob_id = ?, content_hash = ? WHERE code = ?"
                + " AND (blob_id IS NULL OR blob_id = 0)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, blobId);
            statement.setString(2, sha256 == null ? "" : sha256);
            statement.setString(3, code);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to attach media blob to image share", e);
        }
    }

    @Override
    public void alignStorageWithBlob(String code, MediaBlob blob) {
        String sql = "UPDATE short_url_images SET storage_name = ?, content_type = ?, size_bytes = ?,"
                + " storage_state = ?, archive_storage_name = ?, archived_at = ?"
                + " WHERE code = ? AND blob_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, blob.storageName());
            statement.setString(2, blob.contentType());
            statement.setLong(3, blob.sizeBytes());
            statement.setString(4, blob.storageState().name());
            statement.setString(5, blob.archiveStorageName());
            statement.setLong(6, blob.archivedAt());
            statement.setString(7, code);
            statement.setLong(8, blob.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to align migrated media storage metadata", e);
        }
    }

    @Override
    public boolean hasActiveShareForBlob(long blobId, long nowMillis) {
        String sql = "SELECT 1 FROM short_url_images WHERE blob_id = ?"
                + " AND expires_at > ? AND storage_state = 'ACTIVE' LIMIT 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, blobId);
            statement.setLong(2, nowMillis);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query active media blob references", e);
        }
    }

    @Override
    public long countByBlobId(long blobId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM short_url_images WHERE blob_id = ?")) {
            statement.setLong(1, blobId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count media blob references", e);
        }
    }

    @Override
    public void releaseExpiredReuseKeys(long nowMillis) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE short_url_images SET active_reuse_key = NULL"
                             + " WHERE expires_at <= ? OR storage_state <> 'ACTIVE'")) {
            statement.setLong(1, nowMillis);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to release expired media share reuse keys", e);
        }
    }

    @Override
    public void updateStorageStateForBlob(long blobId, MediaStorageState state,
                                          String archiveStorageName, long archivedAt) {
        String sql = "UPDATE short_url_images SET storage_state = ?, archive_storage_name = ?,"
                + " archived_at = ? WHERE blob_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.name());
            statement.setString(2, archiveStorageName == null ? "" : archiveStorageName);
            statement.setLong(3, archivedAt);
            statement.setLong(4, blobId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to mirror media blob storage state", e);
        }
    }

    @Override
    public List<ImageShare> findExpired(long nowMillis) {
        List<ImageShare> expired = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_EXPIRED)) {
            statement.setLong(1, nowMillis);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    expired.add(mapRow(resultSet));
                }
            }
            return expired;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find expired image shares", e);
        }
    }

    @Override
    public List<ImageShare> findByStorageStates(Set<MediaStorageState> states) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(states.size(), "?"));
        String sql = "SELECT " + SELECT_FIELDS + " FROM short_url_images WHERE storage_state IN (" + placeholders + ")";
        List<ImageShare> matches = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (MediaStorageState state : states) {
                statement.setString(index++, state.name());
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    matches.add(mapRow(rows));
                }
            }
            return matches;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query image shares by storage state", e);
        }
    }

    @Override
    public long incrementViewCount(String code) {
        return incrementViewCountInternal(code, 0L, false);
    }

    @Override
    public long incrementViewCount(String code, long lastAccessedAt) {
        return incrementViewCountInternal(code, lastAccessedAt, true);
    }

    private long incrementViewCountInternal(String code, long lastAccessedAt, boolean updateLastAccessedAt) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(
                    updateLastAccessedAt ? INCREMENT_VIEW_METRICS : INCREMENT_VIEW_COUNT)) {
                if (updateLastAccessedAt) {
                    update.setLong(1, lastAccessedAt);
                    update.setString(2, code);
                } else {
                    update.setString(1, code);
                }
                if (update.executeUpdate() == 0) {
                    return 0L;
                }
            }
            try (PreparedStatement select = connection.prepareStatement(SELECT_VIEW_COUNT)) {
                select.setString(1, code);
                try (ResultSet resultSet = select.executeQuery()) {
                    return resultSet.next() ? resultSet.getLong("view_count") : 0L;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to increment image share view count", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static void initializeSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(MySqlMediaBlobRepository.CREATE_TABLE_SQL);
            statement.execute(CREATE_TABLE);
            ensureContentHashColumn(connection, statement);
            ensureViewCountColumn(connection, statement);
            ensureLifecycleColumns(connection, statement);
            ensureColumn(connection, statement, "last_accessed_at", "BIGINT NOT NULL DEFAULT 0");
            ensureColumn(connection, statement, "blob_id", "BIGINT NULL");
            ensureColumn(connection, statement, "active_reuse_key", "CHAR(64) NULL");
            ensureContentHashIndex(connection, statement);
            ensureOwnerIndex(connection, statement);
            ensureIndex(connection, statement, "idx_short_url_images_blob",
                    "CREATE INDEX idx_short_url_images_blob ON short_url_images(blob_id)");
            ensureIndex(connection, statement, "idx_short_url_images_quota_active",
                    "CREATE INDEX idx_short_url_images_quota_active ON short_url_images(quota_group_id, storage_state, expires_at)");
            ensureIndex(connection, statement, "uq_short_url_images_active_reuse",
                    "CREATE UNIQUE INDEX uq_short_url_images_active_reuse ON short_url_images(active_reuse_key)");
            ensureBlobForeignKey(connection, statement);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize image-share mysql schema", e);
        }
    }

    private ImageShare mapRow(ResultSet resultSet) throws SQLException {
        return new ImageShare(
                resultSet.getString("code"),
                resultSet.getString("storage_name"),
                resultSet.getString("content_type"),
                resultSet.getLong("size_bytes"),
                resultSet.getLong("created_at"),
                resultSet.getLong("expires_at"),
                resultSet.getString("password_hash"),
                resultSet.getString("content_hash"),
                resultSet.getLong("view_count"),
                parseStorageState(resultSet.getString("storage_state")),
                resultSet.getString("archive_storage_name"),
                resultSet.getLong("archived_at"),
                parseOwnerType(resultSet.getString("owner_type")),
                resultSet.getString("owner_id"),
                resultSet.getString("quota_group_id"),
                resultSet.getString("created_device_id_hash"),
                resultSet.getString("created_ip_hash"),
                resultSet.getLong("last_accessed_at"),
                resultSet.getLong("blob_id")
        );
    }

    private void bindInsert(PreparedStatement statement, ImageShare imageShare) throws SQLException {
        statement.setString(1, imageShare.code());
        bindCommon(statement, imageShare, 2);
        statement.setString(20, activeReuseKey(imageShare));
    }

    private void bindUpdate(PreparedStatement statement, ImageShare imageShare) throws SQLException {
        bindCommon(statement, imageShare, 1);
        statement.setString(19, imageShare.code());
    }

    private void bindCommon(PreparedStatement statement, ImageShare imageShare, int start) throws SQLException {
        int index = start;
        statement.setString(index++, imageShare.storageName());
        statement.setString(index++, imageShare.contentType());
        statement.setLong(index++, imageShare.sizeBytes());
        statement.setLong(index++, imageShare.createdAt());
        statement.setLong(index++, imageShare.expiresAt());
        statement.setString(index++, imageShare.passwordHash());
        statement.setString(index++, imageShare.contentHash());
        statement.setLong(index++, imageShare.viewCount());
        statement.setString(index++, imageShare.storageState().name());
        statement.setString(index++, imageShare.archiveStorageName());
        statement.setLong(index++, imageShare.archivedAt());
        statement.setString(index++, imageShare.ownerType().name());
        statement.setString(index++, imageShare.ownerId());
        statement.setString(index++, imageShare.quotaGroupId());
        statement.setString(index++, imageShare.createdDeviceIdHash());
        statement.setString(index++, imageShare.createdIpHash());
        statement.setLong(index++, imageShare.lastAccessedAt());
        if (imageShare.blobId() > 0L) {
            statement.setLong(index, imageShare.blobId());
        } else {
            statement.setNull(index, java.sql.Types.BIGINT);
        }
    }

    private String activeReuseKey(ImageShare imageShare) {
        if (imageShare.blobId() <= 0L || imageShare.ownerId().isBlank()) {
            return null;
        }
        String value = imageShare.ownerType().name() + ':' + imageShare.ownerId() + ':' + imageShare.blobId();
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private MediaStorageState parseStorageState(String value) {
        try {
            return MediaStorageState.valueOf(value);
        } catch (Exception ignored) {
            return MediaStorageState.ACTIVE;
        }
    }

    private MediaOwnerType parseOwnerType(String value) {
        try {
            return MediaOwnerType.valueOf(value);
        } catch (Exception ignored) {
            return MediaOwnerType.ANONYMOUS_DEVICE;
        }
    }

    private static void ensureContentHashColumn(Connection connection, Statement statement) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, "short_url_images", "content_hash")) {
            if (columns.next()) {
                return;
            }
        }
        statement.execute("ALTER TABLE short_url_images ADD COLUMN content_hash CHAR(64) NOT NULL DEFAULT ''");
    }

    private static void ensureContentHashIndex(Connection connection, Statement statement) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet indexes = metadata.getIndexInfo(connection.getCatalog(), null, "short_url_images", false, false)) {
            while (indexes.next()) {
                if ("idx_short_url_images_content_hash_expires".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return;
                }
            }
        }
        statement.execute("CREATE INDEX idx_short_url_images_content_hash_expires ON short_url_images(content_hash, expires_at)");
    }

    private static void ensureOwnerIndex(Connection connection, Statement statement) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet indexes = metadata.getIndexInfo(
                connection.getCatalog(), null, "short_url_images", false, false)) {
            while (indexes.next()) {
                if ("idx_short_url_images_owner_created".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return;
                }
            }
        }
        statement.execute("CREATE INDEX idx_short_url_images_owner_created"
                + " ON short_url_images(owner_type, owner_id, created_at)");
    }

    private static void ensureIndex(Connection connection, Statement statement, String name,
                                    String sql) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet indexes = metadata.getIndexInfo(
                connection.getCatalog(), null, "short_url_images", false, false)) {
            while (indexes.next()) {
                if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return;
                }
            }
        }
        statement.execute(sql);
    }

    private static void ensureBlobForeignKey(Connection connection, Statement statement) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet keys = metadata.getImportedKeys(
                connection.getCatalog(), null, "short_url_images")) {
            while (keys.next()) {
                if ("blob_id".equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))
                        && "media_blobs".equalsIgnoreCase(keys.getString("PKTABLE_NAME"))) {
                    return;
                }
            }
        }
        statement.executeUpdate("""
                UPDATE short_url_images s
                LEFT JOIN media_blobs b ON b.id = s.blob_id
                SET s.blob_id = NULL, s.active_reuse_key = NULL
                WHERE s.blob_id IS NOT NULL AND b.id IS NULL
                """);
        statement.execute("""
                ALTER TABLE short_url_images
                ADD CONSTRAINT fk_short_url_images_blob
                FOREIGN KEY (blob_id) REFERENCES media_blobs(id)
                ON DELETE RESTRICT ON UPDATE RESTRICT
                """);
    }

    private static void ensureViewCountColumn(Connection connection, Statement statement) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, "short_url_images", "view_count")) {
            if (columns.next()) {
                return;
            }
        }
        statement.execute("ALTER TABLE short_url_images ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0");
    }

    private static void ensureLifecycleColumns(Connection connection, Statement statement) throws SQLException {
        ensureColumn(connection, statement, "storage_state", "VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'");
        ensureColumn(connection, statement, "archive_storage_name", "VARCHAR(128) NOT NULL DEFAULT ''");
        ensureColumn(connection, statement, "archived_at", "BIGINT NOT NULL DEFAULT 0");
        ensureColumn(connection, statement, "owner_type", "VARCHAR(32) NOT NULL DEFAULT 'ANONYMOUS_DEVICE'");
        ensureColumn(connection, statement, "owner_id", "VARCHAR(128) NOT NULL DEFAULT ''");
        ensureColumn(connection, statement, "quota_group_id", "VARCHAR(64) NOT NULL DEFAULT ''");
        ensureColumn(connection, statement, "created_device_id_hash", "CHAR(64) NOT NULL DEFAULT ''");
        ensureColumn(connection, statement, "created_ip_hash", "CHAR(64) NOT NULL DEFAULT ''");
    }

    private static void ensureColumn(Connection connection, Statement statement, String name,
                                     String definition) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, "short_url_images", name)) {
            if (columns.next()) {
                return;
            }
        }
        statement.execute("ALTER TABLE short_url_images ADD COLUMN " + name + " " + definition);
    }
}
