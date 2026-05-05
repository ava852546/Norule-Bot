package com.norule.musicbot.shorturl;

import com.norule.musicbot.ShortUrlService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class SqliteShortUrlRepository implements ShortUrlRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS short_urls (
                code TEXT PRIMARY KEY,
                target TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """;
    private static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_short_urls_target_expires ON short_urls(target, expires_at)";
    private static final String SELECT_BY_CODE = "SELECT code, target, created_at, expires_at FROM short_urls WHERE code = ?";
    private static final String SELECT_ACTIVE_BY_TARGET = """
            SELECT code, target, created_at, expires_at
            FROM short_urls
            WHERE target = ? AND expires_at > ?
            ORDER BY created_at DESC
            LIMIT 1
            """;
    private static final String INSERT = "INSERT INTO short_urls (code, target, created_at, expires_at) VALUES (?, ?, ?, ?)";
    private static final String DELETE_BY_CODE = "DELETE FROM short_urls WHERE code = ?";
    private static final String CLEANUP = "DELETE FROM short_urls WHERE expires_at <= ?";

    private final String jdbcUrl;

    public SqliteShortUrlRepository(Path dbFilePath) {
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
            throw new IllegalStateException("Failed to prepare short-url sqlite directory", e);
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbFilePath.toAbsolutePath().normalize();
        initializeSchema();
    }

    @Override
    public ShortUrlService.ShortUrlEntry findByCode(String code) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SELECT_BY_CODE)) {
            statement.setString(1, code);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query short url by code", e);
        }
    }

    @Override
    public ShortUrlService.ShortUrlEntry findActiveByTarget(String target, long nowMillis) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(SELECT_ACTIVE_BY_TARGET)) {
            statement.setString(1, target);
            statement.setLong(2, nowMillis);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapRow(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query short url by target", e);
        }
    }

    @Override
    public void save(ShortUrlService.ShortUrlEntry entry) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, entry.getCode());
            statement.setString(2, entry.getTarget());
            statement.setLong(3, entry.getCreatedAt());
            statement.setLong(4, entry.getExpiresAt());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save short url", e);
        }
    }

    @Override
    public void deleteByCode(String code) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(DELETE_BY_CODE)) {
            statement.setString(1, code);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete short url by code", e);
        }
    }

    @Override
    public int cleanupExpired(long nowMillis) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(CLEANUP)) {
            statement.setLong(1, nowMillis);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to cleanup expired short urls", e);
        }
    }

    private void initializeSchema() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_INDEX);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize short url sqlite schema", e);
        }
    }

    private ShortUrlService.ShortUrlEntry mapRow(ResultSet rs) throws SQLException {
        return new ShortUrlService.ShortUrlEntry(
                rs.getString("code"),
                rs.getString("target"),
                rs.getLong("created_at"),
                rs.getLong("expires_at")
        );
    }
}
