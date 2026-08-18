package com.norule.musicbot.shorturl;

import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.MediaOwnerType;
import com.norule.musicbot.domain.shorturl.MediaStorageState;

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
import java.util.Set;

public final class SqliteImageShareRepository implements ImageShareRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS short_url_images (
                code TEXT PRIMARY KEY,
                storage_name TEXT NOT NULL,
                content_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                password_hash TEXT NOT NULL DEFAULT '',
                content_hash TEXT NOT NULL DEFAULT '',
                view_count INTEGER NOT NULL DEFAULT 0,
                storage_state TEXT NOT NULL DEFAULT 'ACTIVE',
                archive_storage_name TEXT NOT NULL DEFAULT '',
                archived_at INTEGER NOT NULL DEFAULT 0,
                owner_type TEXT NOT NULL DEFAULT 'ANONYMOUS_DEVICE',
                owner_id TEXT NOT NULL DEFAULT '',
                quota_group_id TEXT NOT NULL DEFAULT '',
                created_device_id_hash TEXT NOT NULL DEFAULT '',
                created_ip_hash TEXT NOT NULL DEFAULT '',
                last_accessed_at INTEGER NOT NULL DEFAULT 0
            )
            """;
    private static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_short_url_images_expires ON short_url_images(expires_at)";
    private static final String CREATE_CONTENT_HASH_INDEX = "CREATE INDEX IF NOT EXISTS idx_short_url_images_content_hash_expires ON short_url_images(content_hash, expires_at)";
    private static final String SELECT_FIELDS = "code, storage_name, content_type, size_bytes, created_at, expires_at, password_hash, content_hash, view_count, storage_state, archive_storage_name, archived_at, owner_type, owner_id, quota_group_id, created_device_id_hash, created_ip_hash, last_accessed_at";
    private static final String SELECT_BY_CODE = "SELECT " + SELECT_FIELDS + " FROM short_url_images WHERE code = ?";
    private static final String SELECT_ACTIVE_BY_CONTENT_HASH = """
            SELECT %s
            FROM short_url_images
            WHERE content_hash = ? AND expires_at > ? AND storage_state = 'ACTIVE'
            ORDER BY created_at DESC
            """.formatted(SELECT_FIELDS);
    private static final String INSERT = "INSERT INTO short_url_images (code, storage_name, content_type, size_bytes, created_at, expires_at, password_hash, content_hash, view_count, storage_state, archive_storage_name, archived_at, owner_type, owner_id, quota_group_id, created_device_id_hash, created_ip_hash, last_accessed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE = "UPDATE short_url_images SET storage_name = ?, content_type = ?, size_bytes = ?, created_at = ?, expires_at = ?, password_hash = ?, content_hash = ?, view_count = ?, storage_state = ?, archive_storage_name = ?, archived_at = ?, owner_type = ?, owner_id = ?, quota_group_id = ?, created_device_id_hash = ?, created_ip_hash = ?, last_accessed_at = ? WHERE code = ?";
    private static final String DELETE_BY_CODE = "DELETE FROM short_url_images WHERE code = ?";
    private static final String SELECT_EXPIRED = "SELECT " + SELECT_FIELDS + " FROM short_url_images WHERE expires_at <= ? AND storage_state = 'ACTIVE'";
    private static final String INCREMENT_VIEW_COUNT = "UPDATE short_url_images SET view_count = view_count + 1 WHERE code = ?";
    private static final String INCREMENT_VIEW_METRICS = "UPDATE short_url_images SET view_count = view_count + 1, last_accessed_at = ? WHERE code = ?";
    private static final String SELECT_VIEW_COUNT = "SELECT view_count FROM short_url_images WHERE code = ?";

    private final String jdbcUrl;

    public SqliteImageShareRepository(Path dbFilePath) {
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
            throw new IllegalStateException("Failed to prepare image-share sqlite directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbFilePath.toAbsolutePath().normalize();
        initializeSchema();
    }

    @Override
    public ImageShare findByCode(String code) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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
    public void save(ImageShare imageShare) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            bindInsert(statement, imageShare);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save image share", e);
        }
    }

    @Override
    public void update(ImageShare imageShare) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(UPDATE)) {
            bindUpdate(statement, imageShare);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update image share", e);
        }
    }

    @Override
    public void deleteByCode(String code) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(DELETE_BY_CODE)) {
            statement.setString(1, code);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete image share", e);
        }
    }

    @Override
    public List<ImageShare> findExpired(long nowMillis) {
        List<ImageShare> expired = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
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

    private void initializeSchema() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            ensureContentHashColumn(connection, statement);
            ensureViewCountColumn(connection, statement);
            ensureLifecycleColumns(connection, statement);
            ensureColumn(connection, statement, "last_accessed_at", "INTEGER NOT NULL DEFAULT 0");
            statement.execute(CREATE_INDEX);
            statement.execute(CREATE_CONTENT_HASH_INDEX);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize image-share sqlite schema", e);
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
                resultSet.getLong("last_accessed_at")
        );
    }

    private void bindInsert(PreparedStatement statement, ImageShare imageShare) throws SQLException {
        statement.setString(1, imageShare.code());
        bindCommon(statement, imageShare, 2);
    }

    private void bindUpdate(PreparedStatement statement, ImageShare imageShare) throws SQLException {
        bindCommon(statement, imageShare, 1);
        statement.setString(18, imageShare.code());
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
        statement.setLong(index, imageShare.lastAccessedAt());
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

    private void ensureContentHashColumn(Connection connection, Statement statement) throws SQLException {
        try (Statement tableInfo = connection.createStatement();
             ResultSet columns = tableInfo.executeQuery("PRAGMA table_info(short_url_images)")) {
            while (columns.next()) {
                if ("content_hash".equalsIgnoreCase(columns.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE short_url_images ADD COLUMN content_hash TEXT NOT NULL DEFAULT ''");
    }

    private void ensureViewCountColumn(Connection connection, Statement statement) throws SQLException {
        try (Statement tableInfo = connection.createStatement();
             ResultSet columns = tableInfo.executeQuery("PRAGMA table_info(short_url_images)")) {
            while (columns.next()) {
                if ("view_count".equalsIgnoreCase(columns.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE short_url_images ADD COLUMN view_count INTEGER NOT NULL DEFAULT 0");
    }

    private void ensureLifecycleColumns(Connection connection, Statement statement) throws SQLException {
        ensureColumn(connection, statement, "storage_state", "TEXT NOT NULL DEFAULT 'ACTIVE'");
        ensureColumn(connection, statement, "archive_storage_name", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(connection, statement, "archived_at", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(connection, statement, "owner_type", "TEXT NOT NULL DEFAULT 'ANONYMOUS_DEVICE'");
        ensureColumn(connection, statement, "owner_id", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(connection, statement, "quota_group_id", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(connection, statement, "created_device_id_hash", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(connection, statement, "created_ip_hash", "TEXT NOT NULL DEFAULT ''");
    }

    private void ensureColumn(Connection connection, Statement statement, String name,
                              String definition) throws SQLException {
        try (Statement tableInfo = connection.createStatement();
             ResultSet columns = tableInfo.executeQuery("PRAGMA table_info(short_url_images)")) {
            while (columns.next()) {
                if (name.equalsIgnoreCase(columns.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE short_url_images ADD COLUMN " + name + " " + definition);
    }
}
