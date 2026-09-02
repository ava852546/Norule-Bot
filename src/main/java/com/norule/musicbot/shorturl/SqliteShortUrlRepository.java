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
import java.util.ArrayList;
import java.util.List;

public final class SqliteShortUrlRepository implements ShortUrlRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS short_urls (
                code TEXT PRIMARY KEY,
                target TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                view_count INTEGER NOT NULL DEFAULT 0,
                owner_user_id TEXT NOT NULL DEFAULT '',
                last_accessed_at INTEGER NOT NULL DEFAULT 0
            )
            """;
    private static final String CREATE_SETTINGS_TABLE = """
            CREATE TABLE IF NOT EXISTS short_url_settings (
                setting_key TEXT PRIMARY KEY,
                setting_value TEXT NOT NULL
            )
            """;
    private static final String LOG_CHANNEL_SETTING = "discord_log_channel_id";
    private static final String CREATE_CODE_NOCASE_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_short_urls_code_nocase ON short_urls(code COLLATE NOCASE)";
    private static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_short_urls_target_expires ON short_urls(target, expires_at)";
    private static final String CREATE_OWNER_INDEX = "CREATE INDEX IF NOT EXISTS idx_short_urls_owner_created ON short_urls(owner_user_id, created_at DESC)";
    private static final String SELECT_FIELDS = "code, target, created_at, expires_at, view_count, owner_user_id, last_accessed_at";
    private static final String SELECT_BY_CODE = "SELECT " + SELECT_FIELDS + " FROM short_urls WHERE code = ?";
    private static final String SELECT_BY_CODE_IGNORE_CASE = "SELECT " + SELECT_FIELDS
            + " FROM short_urls WHERE code = ? COLLATE NOCASE LIMIT 1";
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
            ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value
            """;
    private static final String DELETE_SETTING = "DELETE FROM short_url_settings WHERE setting_key = ?";

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
        return findByCode(code, SELECT_BY_CODE);
    }

    @Override
    public ShortUrlService.ShortUrlEntry findByCodeIgnoreCase(String code) {
        return findByCode(code, SELECT_BY_CODE_IGNORE_CASE);
    }

    private ShortUrlService.ShortUrlEntry findByCode(String code, String sql) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(sql)) {
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
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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
        if (!insert(entry, false)) {
            throw new IllegalStateException("Short URL code already exists: " + entry.getCode());
        }
    }

    @Override
    public boolean saveIfAbsent(ShortUrlService.ShortUrlEntry entry) {
        return insert(entry, true);
    }

    private boolean insert(ShortUrlService.ShortUrlEntry entry, boolean returnFalseOnDuplicate) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, entry.getCode());
            statement.setString(2, entry.getTarget());
            statement.setLong(3, entry.getCreatedAt());
            statement.setLong(4, entry.getExpiresAt());
            statement.setLong(5, entry.getViewCount());
            statement.setString(6, entry.getOwnerUserId());
            statement.setLong(7, entry.getLastAccessedAt());
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (returnFalseOnDuplicate && isDuplicateCode(e)) {
                return false;
            }
            throw new IllegalStateException("Failed to save short url", e);
        }
    }

    private boolean isDuplicateCode(SQLException exception) {
        return exception.getErrorCode() == 19
                || exception.getErrorCode() == 1555
                || exception.getErrorCode() == 2067
                || "23000".equals(exception.getSQLState());
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
            throw new IllegalStateException("Failed to increment short url view count", e);
        }
    }

    @Override
    public Long findLogChannelId() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
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

    private void initializeSchema() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
            Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            ensureViewCountColumn(connection, statement);
            ensureColumn(connection, statement, "owner_user_id", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(connection, statement, "last_accessed_at", "INTEGER NOT NULL DEFAULT 0");
            statement.execute(CREATE_CODE_NOCASE_INDEX);
            statement.execute(CREATE_INDEX);
            statement.execute(CREATE_OWNER_INDEX);
            statement.execute(CREATE_SETTINGS_TABLE);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize short url sqlite schema", e);
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

    private void ensureViewCountColumn(Connection connection, Statement statement) throws SQLException {
        ensureColumn(connection, statement, "view_count", "INTEGER NOT NULL DEFAULT 0");
    }

    private void ensureColumn(Connection connection, Statement statement, String name,
                              String definition) throws SQLException {
        try (Statement tableInfo = connection.createStatement();
             ResultSet columns = tableInfo.executeQuery("PRAGMA table_info(short_urls)")) {
            while (columns.next()) {
                if (name.equalsIgnoreCase(columns.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE short_urls ADD COLUMN " + name + " " + definition);
    }
}
