package com.norule.musicbot.config.domain;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.config.BotConfig;
import java.util.Locale;

public final class ShortUrlConfig {
    public static final class Mysql {
        private final String jdbcUrl;
        private final String username;
        private final String password;
        private final int poolSize;

        public Mysql(String jdbcUrl, String username, String password, int poolSize) {
            this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
            this.poolSize = Math.max(1, poolSize);
        }

        public String getJdbcUrl() { return jdbcUrl; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public int getPoolSize() { return poolSize; }
    }

    public static final class Sqlite {
        private final String path;

        public Sqlite(String path) {
            this.path = path == null ? "data/short-url.db" : path;
        }

        public String getPath() { return path; }
    }

    private final String storage;
    private final boolean enabled;
    private final String bindHost;
    private final int bindPort;
    private final String publicBaseUrl;
    private final boolean dedupe;
    private final int ttlDays;
    private final int cleanupIntervalMinutes;
    private final Mysql mysql;
    private final Sqlite sqlite;

    public ShortUrlConfig(boolean enabled,
                          String bindHost,
                          int bindPort,
                          String publicBaseUrl,
                          String storage,
                          boolean dedupe,
                          int ttlDays,
                          int cleanupIntervalMinutes,
                          Mysql mysql,
                          Sqlite sqlite) {
        this.enabled = enabled;
        this.bindHost = bindHost == null || bindHost.isBlank() ? "0.0.0.0" : bindHost.trim();
        this.bindPort = Math.max(1, bindPort);
        String normalizedBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank()
                ? "https://s.norule.me"
                : publicBaseUrl.trim();
        this.publicBaseUrl = normalizedBaseUrl.endsWith("/")
                ? normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1)
                : normalizedBaseUrl;
        this.storage = normalizeStorage(storage);
        this.dedupe = dedupe;
        this.ttlDays = Math.max(1, ttlDays);
        this.cleanupIntervalMinutes = Math.max(1, cleanupIntervalMinutes);
        this.mysql = mysql == null ? new Mysql("", "", "", 8) : mysql;
        this.sqlite = sqlite == null ? new Sqlite("data/short-url.db") : sqlite;
    }

    public ShortUrlConfig(BotConfig.ShortUrl config) {
        BotConfig.ShortUrl source = config == null ? BotConfig.ShortUrl.defaultValues() : config;
        this.enabled = source.isEnabled();
        this.bindHost = source.getBindHost();
        this.bindPort = source.getBindPort();
        this.publicBaseUrl = source.getPublicBaseUrl();
        this.storage = source.getStorage();
        this.dedupe = source.isDedupe();
        this.ttlDays = source.getTtlDays();
        this.cleanupIntervalMinutes = source.getCleanupIntervalMinutes();
        this.mysql = new Mysql(
                source.getMysql().getJdbcUrl(),
                source.getMysql().getUsername(),
                source.getMysql().getPassword(),
                source.getMysql().getPoolSize()
        );
        this.sqlite = new Sqlite(source.getSqlite().getPath());
    }

    public ShortUrlService.Options toOptions() {
        return new ShortUrlService.Options(
                dedupe,
                ttlDays * 24L * 60L * 60L * 1000L,
                cleanupIntervalMinutes * 60L * 1000L,
                publicBaseUrl
        );
    }

    public boolean isEnabled() { return enabled; }
    public String getBindHost() { return bindHost; }
    public int getBindPort() { return bindPort; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    @Deprecated
    public String getHost() { return getBindHost(); }
    @Deprecated
    public int getPort() { return getBindPort(); }
    public String getStorage() { return storage; }
    public boolean isDedupe() { return dedupe; }
    public int getTtlDays() { return ttlDays; }
    public int getCleanupIntervalMinutes() { return cleanupIntervalMinutes; }
    public Mysql getMysql() { return mysql; }
    public Sqlite getSqlite() { return sqlite; }

    private static String normalizeStorage(String storage) {
        return storage == null ? "sqlite" : storage.trim().toLowerCase(Locale.ROOT);
    }
}
