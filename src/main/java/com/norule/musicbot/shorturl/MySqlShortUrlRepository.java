package com.norule.musicbot.shorturl;

import com.norule.musicbot.ShortUrlService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class MySqlShortUrlRepository implements ShortUrlRepository, AutoCloseable {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS short_urls (
                code VARCHAR(64) NOT NULL,
                target TEXT NOT NULL,
                created_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL,
                PRIMARY KEY (code),
                KEY idx_short_urls_target_expires (target(255), expires_at),
                KEY idx_short_urls_expires_at (expires_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
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

    private final HikariDataSource dataSource;

    public MySqlShortUrlRepository(String jdbcUrl, String username, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(Math.max(2, poolSize));
        config.setMinimumIdle(1);
        config.setPoolName("short-url-pool");
        config.setConnectionTimeout(10000L);
        config.setValidationTimeout(5000L);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        this.dataSource = new HikariDataSource(config);
        initializeSchema(this.dataSource);
    }

    @Override
    public ShortUrlService.ShortUrlEntry findByCode(String code) {
        try (Connection connection = dataSource.getConnection();
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
        try (Connection connection = dataSource.getConnection();
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
        try (Connection connection = dataSource.getConnection();
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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_BY_CODE)) {
            statement.setString(1, code);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete short url by code", e);
        }
    }

    @Override
    public int cleanupExpired(long nowMillis) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(CLEANUP)) {
            statement.setLong(1, nowMillis);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to cleanup expired short urls", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static void initializeSchema(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize short url mysql schema", e);
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
