package com.norule.musicbot.shorturl;

import com.norule.musicbot.domain.shorturl.MediaBlob;
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

public final class SqliteMediaBlobRepository implements MediaBlobRepository {
    static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS media_blobs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sha256 TEXT NOT NULL UNIQUE,
                storage_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                content_type TEXT NOT NULL,
                extension TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                storage_state TEXT NOT NULL DEFAULT 'ACTIVE',
                archive_storage_name TEXT NOT NULL DEFAULT '',
                archived_at INTEGER NOT NULL DEFAULT 0
            )
            """;
    private static final String FIELDS = "id, sha256, storage_name, size_bytes, content_type, extension, "
            + "created_at, storage_state, archive_storage_name, archived_at";
    private static final String INSERT = "INSERT INTO media_blobs (sha256, storage_name, size_bytes, "
            + "content_type, extension, created_at, storage_state, archive_storage_name, archived_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final String jdbcUrl;

    public SqliteMediaBlobRepository(Path dbFilePath) {
        try {
            Class.forName("org.sqlite.JDBC");
            Path parent = dbFilePath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare media-blob sqlite repository", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbFilePath.toAbsolutePath().normalize() + "?foreign_keys=on";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE_SQL);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize media-blob sqlite schema", e);
        }
    }

    @Override
    public MediaBlob findBySha256(String sha256) {
        return find("SELECT " + FIELDS + " FROM media_blobs WHERE sha256 = ?", sha256);
    }

    @Override
    public MediaBlob findById(long blobId) {
        return find("SELECT " + FIELDS + " FROM media_blobs WHERE id = ?", blobId);
    }

    @Override
    public MediaBlob saveIfAbsent(MediaBlob blob) {
        MediaBlob existing = findBySha256(blob.sha256());
        if (existing != null) {
            return existing;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
            bindInsert(statement, blob);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return blob.withId(keys.getLong(1));
                }
            }
        } catch (SQLException e) {
            if (e.getErrorCode() != 19 && !"23000".equals(e.getSQLState())) {
                throw new IllegalStateException("Failed to save media blob", e);
            }
        }
        MediaBlob winner = findBySha256(blob.sha256());
        if (winner == null) {
            throw new IllegalStateException("Media blob unique collision could not be reconciled");
        }
        return winner;
    }

    @Override
    public void update(MediaBlob blob) {
        String sql = "UPDATE media_blobs SET storage_name = ?, size_bytes = ?, content_type = ?, "
                + "extension = ?, created_at = ?, storage_state = ?, archive_storage_name = ?, archived_at = ? "
                + "WHERE id = ?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, blob.storageName());
            statement.setLong(2, blob.sizeBytes());
            statement.setString(3, blob.contentType());
            statement.setString(4, blob.extension());
            statement.setLong(5, blob.createdAt());
            statement.setString(6, blob.storageState().name());
            statement.setString(7, blob.archiveStorageName());
            statement.setLong(8, blob.archivedAt());
            statement.setLong(9, blob.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update media blob", e);
        }
    }

    @Override
    public void deleteById(long blobId) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement("DELETE FROM media_blobs WHERE id = ?")) {
            statement.setLong(1, blobId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete media blob", e);
        }
    }

    @Override
    public List<MediaBlob> findByStorageStates(Set<MediaStorageState> states) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(states.size(), "?"));
        List<MediaBlob> blobs = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT " + FIELDS + " FROM media_blobs WHERE storage_state IN (" + placeholders + ")")) {
            int index = 1;
            for (MediaStorageState state : states) {
                statement.setString(index++, state.name());
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    blobs.add(map(rows));
                }
            }
            return blobs;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query media blobs by storage state", e);
        }
    }

    @Override
    public List<MediaBlob> findOrphans(long createdBeforeMillis) {
        String sql = "SELECT " + FIELDS + " FROM media_blobs b WHERE NOT EXISTS "
                + "(SELECT 1 FROM short_url_images s WHERE s.blob_id = b.id) AND b.created_at <= ?";
        List<MediaBlob> blobs = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, createdBeforeMillis);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    blobs.add(map(rows));
                }
            }
            return blobs;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query orphan media blobs", e);
        }
    }

    private MediaBlob find(String sql, Object value) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (value instanceof Number number) {
                statement.setLong(1, number.longValue());
            } else {
                statement.setString(1, String.valueOf(value));
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? map(row) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query media blob", e);
        }
    }

    private void bindInsert(PreparedStatement statement, MediaBlob blob) throws SQLException {
        statement.setString(1, blob.sha256());
        statement.setString(2, blob.storageName());
        statement.setLong(3, blob.sizeBytes());
        statement.setString(4, blob.contentType());
        statement.setString(5, blob.extension());
        statement.setLong(6, blob.createdAt());
        statement.setString(7, blob.storageState().name());
        statement.setString(8, blob.archiveStorageName());
        statement.setLong(9, blob.archivedAt());
    }

    private MediaBlob map(ResultSet row) throws SQLException {
        MediaStorageState state;
        try {
            state = MediaStorageState.valueOf(row.getString("storage_state"));
        } catch (Exception ignored) {
            state = MediaStorageState.ACTIVE;
        }
        return new MediaBlob(row.getLong("id"), row.getString("sha256"),
                row.getString("storage_name"), row.getLong("size_bytes"),
                row.getString("content_type"), row.getString("extension"),
                row.getLong("created_at"), state, row.getString("archive_storage_name"),
                row.getLong("archived_at"));
    }
}
