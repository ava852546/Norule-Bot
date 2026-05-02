package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;

public final class StatsConfig {
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

        public static Mysql fromLegacy(BotConfig.Stats.Mysql legacy) {
            BotConfig.Stats.Mysql value = legacy == null ? BotConfig.Stats.Mysql.defaultValues() : legacy;
            return new Mysql(value.getJdbcUrl(), value.getUsername(), value.getPassword(), value.getPoolSize());
        }

        public String getJdbcUrl() { return jdbcUrl; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public int getPoolSize() { return poolSize; }
    }

    public static final class Sqlite {
        private final String path;

        public Sqlite(String path) {
            this.path = path == null ? "" : path;
        }

        public static Sqlite fromLegacy(BotConfig.Stats.Sqlite legacy) {
            BotConfig.Stats.Sqlite value = legacy == null ? BotConfig.Stats.Sqlite.defaultValues() : legacy;
            return new Sqlite(value.getPath());
        }

        public String getPath() { return path; }
    }

    private final String storage;
    private final Mysql mysql;
    private final Sqlite sqlite;

    public StatsConfig(String storage, Mysql mysql, Sqlite sqlite) {
        this.storage = storage == null ? "sqlite" : storage;
        this.mysql = mysql == null ? Mysql.fromLegacy(null) : mysql;
        this.sqlite = sqlite == null ? Sqlite.fromLegacy(null) : sqlite;
    }

    public static StatsConfig fromLegacy(BotConfig.Stats legacy) {
        BotConfig.Stats value = legacy == null ? BotConfig.Stats.defaultValues() : legacy;
        return new StatsConfig(value.getStorage(), Mysql.fromLegacy(value.getMysql()), Sqlite.fromLegacy(value.getSqlite()));
    }

    public String getStorage() { return storage; }
    public Mysql getMysql() { return mysql; }
    public Sqlite getSqlite() { return sqlite; }
}
