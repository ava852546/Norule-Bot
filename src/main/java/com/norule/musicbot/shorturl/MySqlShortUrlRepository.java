package com.norule.musicbot.shorturl;

import com.norule.musicbot.ShortUrlService;
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

public final class MySqlShortUrlRepository implements ShortUrlRepository, AutoCloseable {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS short_urls (
                code VARCHAR(64) NOT NULL,
                target TEXT NOT NULL,
                created_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL,
                view_count BIGINT NOT NULL DEFAULT 0,
                owner_user_id VARCHAR(64) NOT NULL DEFAULT '',
                last_accessed_at BIGINT NOT NULL DEFAULT 0,
                PRIMARY KEY (code),
                KEY idx_short_urls_target_expires (target(255), expires_at),
                KEY idx_short_urls_expires_at (expires_at),
                KEY idx_short_urls_owner_created (owner_user_id, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
    private static final String CREATE_SETTINGS_TABLE = """
            CREATE TABLE IF NOT EXISTS short_url_settings (
                setting_key VARCHAR(64) NOT NULL,
                setting_value VARCHAR(255) NOT NULL,
                PRIMARY KEY (setting_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;
    private static final String LOG_CHANNEL_SETTING = "discord_log_channel_id";
    private static final String SELECT_FIELDS = "code, target, created_at, expires_at, view_count, owner_user_id, last_accessed_at";
    private static final String SELECT_BY_CODE = "SELECT " + SELECT_FIELDS + " FROM short_urls WHERE code = ?";
    private static final String SELECT_ACTIVE_BY_TARGET = """
            SELECT %s
            FROM short_urls
            WHERE target = ? AND expires_at > ?
            ORDER BY created_at DESC
            LIMIT 1
            """.formatted(SELECT_FIELDS);
    private static final String INSERT = "INSERT INTO short_urls (code, target, created_at, expires_at, view_count, owner_user_id, last_accessed_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String DELETE_BY_CODE = "DELETE FROM short_urls WHERE code = ?";
    private static final String CLEANUP = "DELETE FROM short_urls WHERE expires_at <= ?";
    private static final String INCREMENT_VIEW_COUNT = "UPDATE short_urls SET view_count = view_count + 1 WHERE code = ?";
    private static final String INCREMENT_VIEW_METRICS = "UPDATE short_urls SET view_count = view_count + 1, last_accessed_at = ? WHERE code = ?";
    private static final String SELECT_VIEW_COUNT = "SELECT view_count FROM short_urls WHERE code = ?";
    private static final String SELECT_SETTING = "SELECT setting_value FROM short_url_settings WHERE setting_key = ?";
    private static final String UPSERT_SETTING = """
            INSERT INTO short_url_settings (setting_key, setting_value) VALUES (?, ?)
            ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)
            """;
    private static final String DELETE_SETTING = "DELETE FROM short_url_settings WHERE setting_key = ?";

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
    public List<ShortUrlService.ShortUrlEntry> findByOwnerUserId(String ownerUserId, int offset, int limit) {
        return findByOwnerUserId(ownerUserId, null, 0L, offset, limit);
    }

    @Override
    public List<ShortUrlService.ShortUrlEntry> findByOwnerUserId(String ownerUserId,
                                                                 Boolean active,
                                                                 long nowMillis,
                                                                 int offset,
                                                                 int limit) {
        List<ShortUrlService.ShortUrlEntry> entries = new ArrayList<>();
        String sql = "SELECT " + SELECT_FIELDS + " FROM short_urls WHERE owner_user_id = ?"
                + (active == null ? "" : active ? " AND expires_at > ?" : " AND expires_at <= ?")
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
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
                    entries.add(mapRow(rows));
                }
            }
            return entries;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query short urls by owner", e);
        }
    }

    @Override
    public long countByOwnerUserId(String ownerUserId) {
        return countByOwnerUserId(ownerUserId, null, 0L);
    }

    @Override
    public long countByOwnerUserId(String ownerUserId, Boolean active, long nowMillis) {
        String sql = "SELECT COUNT(*) FROM short_urls WHERE owner_user_id = ?"
                + (active == null ? "" : active ? " AND expires_at > ?" : " AND expires_at <= ?");
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
            throw new IllegalStateException("Failed to count short urls by owner", e);
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
            statement.setLong(5, entry.getViewCount());
            statement.setString(6, entry.getOwnerUserId());
            statement.setLong(7, entry.getLastAccessedAt());
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
            throw new IllegalStateException("Failed to increment short url view count", e);
        }
    }

    @Override
    public Long findLogChannelId() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SETTING)) {
            statement.setString(1, LOG_CHANNEL_SETTING);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                long channelId = Long.parseLong(resultSet.getString("setting_value"));
                return channelId > 0L ? channelId : null;
            }
        } catch (NumberFormatException ignored) {
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query short url log channel", e);
        }
    }

    @Override
    public void saveLogChannelId(Long channelId) {
        boolean remove = channelId == null || channelId <= 0L;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(remove ? DELETE_SETTING : UPSERT_SETTING)) {
            statement.setString(1, LOG_CHANNEL_SETTING);
            if (!remove) {
                statement.setString(2, String.valueOf(channelId));
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save short url log channel", e);
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
            ensureViewCountColumn(connection, statement);
            ensureColumn(connection, statement, "owner_user_id", "VARCHAR(64) NOT NULL DEFAULT ''");
            ensureColumn(connection, statement, "last_accessed_at", "BIGINT NOT NULL DEFAULT 0");
            ensureOwnerIndex(connection, statement);
            statement.execute(CREATE_SETTINGS_TABLE);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize short url mysql schema", e);
        }
    }

    private ShortUrlService.ShortUrlEntry mapRow(ResultSet rs) throws SQLException {
        return new ShortUrlService.ShortUrlEntry(
                rs.getString("code"),
                rs.getString("target"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                rs.getLong("view_count"),
                rs.getString("owner_user_id"),
                rs.getLong("last_accessed_at")
        );
    }

    private static void ensureViewCountColumn(Connection connection, Statement statement) throws SQLException {
        ensureColumn(connection, statement, "view_count", "BIGINT NOT NULL DEFAULT 0");
    }

    private static void ensureColumn(Connection connection, Statement statement, String name,
                                     String definition) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, "short_urls", name)) {
            if (columns.next()) {
                return;
            }
        }
        statement.execute("ALTER TABLE short_urls ADD COLUMN " + name + " " + definition);
    }

    private static void ensureOwnerIndex(Connection connection, Statement statement) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet indexes = metadata.getIndexInfo(
                connection.getCatalog(), null, "short_urls", false, false)) {
            while (indexes.next()) {
                if ("idx_short_urls_owner_created".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return;
                }
            }
        }
        statement.execute("CREATE INDEX idx_short_urls_owner_created ON short_urls(owner_user_id, created_at)");
    }
}
