package com.norule.musicbot.config;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class BotConfig {
    private final String token;
    private final Discord discord;
    private final String prefix;
    private final boolean debug;
    private final Long commandGuildId;
    private final String guildSettingsDir;
    private final String languageDir;
    private final DataPaths dataPaths;
    private final String defaultLanguage;
    private final int commandCooldownSeconds;
    private final int numberChainReactionDelayMillis;
    private final BotProfile botProfile;
    private final Developers developers;
    private final Notifications notifications;
    private final Welcome welcome;
    private final MessageLogs messageLogs;
    private final Music music;
    private final PrivateRoom privateRoom;
    private final Ticket ticket;
    private final ShortUrl shortUrl;
    private final MinecraftStatus minecraftStatus;
    private final Dictionary dictionary;
    private final Web web;
    private final Stats stats;

    public BotConfig(String token,
                      Discord discord,
                      String prefix,
                      boolean debug,
                      Long commandGuildId,
                      String guildSettingsDir,
                      String languageDir,
                      DataPaths dataPaths,
                      String defaultLanguage,
                      int commandCooldownSeconds,
                      int numberChainReactionDelayMillis,
                      BotProfile botProfile,
                      Developers developers,
                      Notifications notifications,
                      Welcome welcome,
                      MessageLogs messageLogs,
                      Music music,
                      PrivateRoom privateRoom,
                      Ticket ticket,
                      ShortUrl shortUrl,
                      MinecraftStatus minecraftStatus,
                      Dictionary dictionary,
                      Web web,
                      Stats stats) {
        this.token = token;
        this.discord = discord == null ? Discord.defaultValues() : discord;
        this.prefix = prefix;
        this.debug = debug;
        this.commandGuildId = commandGuildId;
        this.guildSettingsDir = guildSettingsDir;
        this.languageDir = languageDir;
        this.dataPaths = dataPaths;
        this.defaultLanguage = defaultLanguage;
        this.commandCooldownSeconds = Math.max(0, commandCooldownSeconds);
        this.numberChainReactionDelayMillis = Math.max(0, numberChainReactionDelayMillis);
        this.botProfile = botProfile;
        this.developers = developers;
        this.notifications = notifications;
        this.welcome = welcome;
        this.messageLogs = messageLogs;
        this.music = music;
        this.privateRoom = privateRoom;
        this.ticket = ticket;
        this.shortUrl = shortUrl;
        this.minecraftStatus = minecraftStatus == null ? MinecraftStatus.defaultValues() : minecraftStatus;
        this.dictionary = dictionary == null ? Dictionary.defaultValues() : dictionary;
        this.web = web;
        this.stats = stats;
    }

    

public String getToken() {
        return token;
    }

    public Discord getDiscord() {
        return discord;
    }

    public String getPrefix() {
        return prefix;
    }

    public boolean isDebug() {
        return debug;
    }

    public Long getCommandGuildId() {
        return commandGuildId;
    }

    public String getGuildSettingsDir() {
        return guildSettingsDir;
    }

    public String getLanguageDir() {
        return languageDir;
    }

    public DataPaths getDataPaths() {
        return dataPaths;
    }

    public DataPaths.DataDatabase getDataDatabase() {
        return dataPaths.getDatabase();
    }

    public String getMusicDataDir() {
        return dataPaths.getMusicDir();
    }

    public String getModerationDataDir() {
        return dataPaths.getModerationDir();
    }

    public String getTicketDataDir() {
        return dataPaths.getTicketDir();
    }

    public String getTicketTranscriptDir() {
        return dataPaths.getTicketTranscriptDir();
    }

    public String getHoneypotDataDir() {
        return dataPaths.getHoneypotDir();
    }

    public String getLogDir() {
        return dataPaths.getLogDir();
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public int getCommandCooldownSeconds() {
        return commandCooldownSeconds;
    }

    public int getNumberChainReactionDelayMillis() {
        return numberChainReactionDelayMillis;
    }

    public BotProfile getBotProfile() {
        return botProfile;
    }

    public Developers getDevelopers() {
        return developers;
    }

    public static class DataPaths {
        public static final class DataDatabase {
            private final String type;
            private final String path;

            private DataDatabase(String type, String path) {
                this.type = type == null || type.isBlank() ? "sqlite" : type.trim().toLowerCase(Locale.ROOT);
                this.path = blankToDefault(path, "data/norule.db");
            }

            private static DataDatabase fromMap(Map<String, Object> map, DataDatabase fallback) {
                DataDatabase defaults = fallback == null ? defaultValues() : fallback;
                String sqlitePath = getString(asMap(map.get("sqlite")), "path", defaults.getPath());
                return new DataDatabase(
                        getString(map, "type", defaults.getType()),
                        sqlitePath
                );
            }

            private static DataDatabase defaultValues() {
                return new DataDatabase("sqlite", "data/norule.db");
            }

            public String getType() {
                return type;
            }

            public String getPath() {
                return path;
            }
        }

        private final String guildSettingsDir;
        private final String languageDir;
        private final String musicDir;
        private final String moderationDir;
        private final String ticketDir;
        private final String ticketTranscriptDir;
        private final String honeypotDir;
        private final String logDir;
        private final DataDatabase database;

        private DataPaths(String guildSettingsDir,
                          String languageDir,
                          String musicDir,
                          String moderationDir,
                          String ticketDir,
                          String ticketTranscriptDir,
                          String honeypotDir,
                          String logDir,
                          DataDatabase database) {
            this.guildSettingsDir = blankToDefault(guildSettingsDir, "guild/configs");
            this.languageDir = blankToDefault(languageDir, "lang");
            this.musicDir = blankToDefault(musicDir, "guild/music");
            this.moderationDir = blankToDefault(moderationDir, "guild/moderation");
            this.ticketDir = blankToDefault(ticketDir, "guild/tickets");
            this.ticketTranscriptDir = blankToDefault(ticketTranscriptDir, "ticket-transcripts");
            this.honeypotDir = blankToDefault(honeypotDir, "guild/honeypot");
            this.logDir = blankToDefault(logDir, "LOG");
            this.database = database == null ? DataDatabase.defaultValues() : database;
        }

        public static DataPaths fromConfig(Map<String, Object> root) {
            return fromConfigWithDefaults(root, Map.of());
        }

        private static DataPaths fromConfigWithDefaults(Map<String, Object> root, Map<String, Object> defaultRoot) {
            Map<String, Object> data = asMap(root.get("data"));
            Map<String, Object> defaults = asMap(defaultRoot.get("data"));
            Map<String, Object> dataDatabase = asMap(data.get("database"));
            Map<String, Object> defaultDataDatabase = asMap(defaults.get("database"));
            Map<String, Object> rootDatabase = asMap(root.get("database"));
            Map<String, Object> defaultRootDatabase = asMap(defaultRoot.get("database"));
            DataDatabase database = DataDatabase.fromMap(
                    firstNonEmptyMap(dataDatabase, defaultDataDatabase, rootDatabase, defaultRootDatabase),
                    DataDatabase.defaultValues()
            );
            if (getString(asMap(dataDatabase.get("sqlite")), "path", "").isBlank()) {
                String legacyDbPath = getString(asMap(rootDatabase.get("sqlite")), "path", "");
                if (!legacyDbPath.isBlank()) {
                    database = new DataDatabase(database.getType(), legacyDbPath);
                }
            }
            return new DataPaths(
                    configuredPath(data, root, defaults, defaultRoot, "guildSettingsDir", "guild/configs"),
                    configuredPath(data, root, defaults, defaultRoot, "languageDir", "lang"),
                    configuredPath(data, root, defaults, defaultRoot, "musicDir", "guild/music"),
                    configuredPath(data, root, defaults, defaultRoot, "moderationDir", "guild/moderation"),
                    configuredPath(data, root, defaults, defaultRoot, "ticketDir", "guild/tickets"),
                    configuredPath(data, root, defaults, defaultRoot, "ticketTranscriptDir", "ticket-transcripts"),
                    configuredPath(data, root, defaults, defaultRoot, "honeypotDir", "guild/honeypot"),
                    configuredPath(data, root, defaults, defaultRoot, "logDir", "LOG"),
                    database
            );
        }

        private static String configuredPath(Map<String, Object> data,
                                             Map<String, Object> root,
                                             Map<String, Object> defaultData,
                                             Map<String, Object> defaultRoot,
                                             String key,
                                             String fallback) {
            String value = getString(data, key, "");
            if (!value.isBlank()) {
                return value;
            }
            value = getString(root, key, "");
            if (!value.isBlank()) {
                return value;
            }
            value = getString(defaultData, key, "");
            if (!value.isBlank()) {
                return value;
            }
            value = getString(defaultRoot, key, "");
            return value.isBlank() ? fallback : value;
        }

        private static String blankToDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        public String getGuildSettingsDir() {
            return guildSettingsDir;
        }

        public String getLanguageDir() {
            return languageDir;
        }

        public String getMusicDir() {
            return musicDir;
        }

        public String getModerationDir() {
            return moderationDir;
        }

        public String getTicketDir() {
            return ticketDir;
        }

        public String getTicketTranscriptDir() {
            return ticketTranscriptDir;
        }

        public String getHoneypotDir() {
            return honeypotDir;
        }

        public String getLogDir() {
            return logDir;
        }

        public DataDatabase getDatabase() {
            return database;
        }

        @SafeVarargs
        private static Map<String, Object> firstNonEmptyMap(Map<String, Object>... maps) {
            if (maps == null) {
                return Map.of();
            }
            for (Map<String, Object> map : maps) {
                if (map != null && !map.isEmpty()) {
                    return map;
                }
            }
            return Map.of();
        }
    }

    public Notifications getNotifications() {
        return notifications;
    }

    public Welcome getWelcome() {
        return welcome;
    }

    public MessageLogs getMessageLogs() {
        return messageLogs;
    }

    public Music getMusic() {
        return music;
    }

    public PrivateRoom getPrivateRoom() {
        return privateRoom;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public ShortUrl getShortUrl() {
        return shortUrl;
    }

    public MinecraftStatus getMinecraftStatus() {
        return minecraftStatus;
    }

    public Dictionary getDictionary() {
        return dictionary;
    }

    public Web getWeb() {
        return web;
    }

    public Stats getStats() {
        return stats;
    }

    public static final class Discord {
        private final LoginRetry loginRetry;

        private Discord(LoginRetry loginRetry) {
            this.loginRetry = loginRetry == null ? LoginRetry.defaultValues() : loginRetry;
        }

        public static Discord fromMap(Map<String, Object> map, Discord fallback) {
            Discord defaults = fallback == null ? defaultValues() : fallback;
            return new Discord(LoginRetry.fromMap(
                    asMap(map.get("loginRetry")),
                    defaults.getLoginRetry()
            ));
        }

        public static Discord defaultValues() {
            return new Discord(LoginRetry.defaultValues());
        }

        public LoginRetry getLoginRetry() {
            return loginRetry;
        }

        public static final class LoginRetry {
            public static final boolean DEFAULT_ENABLED = true;
            public static final int DEFAULT_MAX_ATTEMPTS = 8;
            public static final int DEFAULT_INITIAL_DELAY_SECONDS = 5;
            public static final int DEFAULT_MAX_DELAY_SECONDS = 60;

            private final boolean enabled;
            private final int maxAttempts;
            private final int initialDelaySeconds;
            private final int maxDelaySeconds;

            private LoginRetry(boolean enabled,
                               int maxAttempts,
                               int initialDelaySeconds,
                               int maxDelaySeconds) {
                this.enabled = enabled;
                this.maxAttempts = positiveOrDefault(maxAttempts, DEFAULT_MAX_ATTEMPTS);
                this.initialDelaySeconds = nonNegativeOrDefault(
                        initialDelaySeconds,
                        DEFAULT_INITIAL_DELAY_SECONDS
                );
                this.maxDelaySeconds = Math.max(
                        this.initialDelaySeconds,
                        nonNegativeOrDefault(maxDelaySeconds, DEFAULT_MAX_DELAY_SECONDS)
                );
            }

            public static LoginRetry fromMap(Map<String, Object> map, LoginRetry fallback) {
                LoginRetry defaults = fallback == null ? defaultValues() : fallback;
                return new LoginRetry(
                        getBoolean(map, "enabled", defaults.isEnabled()),
                        getInt(map, "maxAttempts", defaults.getMaxAttempts()),
                        getInt(map, "initialDelaySeconds", defaults.getInitialDelaySeconds()),
                        getInt(map, "maxDelaySeconds", defaults.getMaxDelaySeconds())
                );
            }

            public static LoginRetry defaultValues() {
                return new LoginRetry(
                        DEFAULT_ENABLED,
                        DEFAULT_MAX_ATTEMPTS,
                        DEFAULT_INITIAL_DELAY_SECONDS,
                        DEFAULT_MAX_DELAY_SECONDS
                );
            }

            public boolean isEnabled() {
                return enabled;
            }

            public int getMaxAttempts() {
                return maxAttempts;
            }

            public int getInitialDelaySeconds() {
                return initialDelaySeconds;
            }

            public int getMaxDelaySeconds() {
                return maxDelaySeconds;
            }

            private static int positiveOrDefault(int value, int defaultValue) {
                return value > 0 ? value : defaultValue;
            }

            private static int nonNegativeOrDefault(int value, int defaultValue) {
                return value >= 0 ? value : defaultValue;
            }
        }
    }

    public static final class Dictionary {
        public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 5;
        public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 5;

        private final int connectTimeoutSeconds;
        private final int requestTimeoutSeconds;
        private final FreeDictionary freeDictionary;
        private final MerriamWebster merriamWebster;

        private Dictionary(int connectTimeoutSeconds,
                           int requestTimeoutSeconds,
                           FreeDictionary freeDictionary,
                           MerriamWebster merriamWebster) {
            this.connectTimeoutSeconds = positiveOrDefault(
                    connectTimeoutSeconds,
                    DEFAULT_CONNECT_TIMEOUT_SECONDS
            );
            this.requestTimeoutSeconds = positiveOrDefault(
                    requestTimeoutSeconds,
                    DEFAULT_REQUEST_TIMEOUT_SECONDS
            );
            this.freeDictionary = freeDictionary == null ? FreeDictionary.defaultValues() : freeDictionary;
            this.merriamWebster = merriamWebster == null ? MerriamWebster.defaultValues() : merriamWebster;
        }

        public static Dictionary fromMap(Map<String, Object> map, Dictionary fallback) {
            Dictionary defaults = fallback == null ? defaultValues() : fallback;
            return new Dictionary(
                    getInt(map, "connectTimeoutSeconds", defaults.getConnectTimeoutSeconds()),
                    getInt(map, "requestTimeoutSeconds", defaults.getRequestTimeoutSeconds()),
                    FreeDictionary.fromMap(asMap(map.get("freeDictionary")), defaults.getFreeDictionary()),
                    MerriamWebster.fromMap(asMap(map.get("merriamWebster")), defaults.getMerriamWebster())
            );
        }

        public static Dictionary defaultValues() {
            return new Dictionary(
                    DEFAULT_CONNECT_TIMEOUT_SECONDS,
                    DEFAULT_REQUEST_TIMEOUT_SECONDS,
                    FreeDictionary.defaultValues(),
                    MerriamWebster.defaultValues()
            );
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public FreeDictionary getFreeDictionary() {
            return freeDictionary;
        }

        public MerriamWebster getMerriamWebster() {
            return merriamWebster;
        }

        public static final class FreeDictionary {
            public static final String DEFAULT_ENDPOINT =
                    "https://api.dictionaryapi.dev/api/v2/entries/en/";

            private final boolean enabled;
            private final String endpoint;

            private FreeDictionary(boolean enabled, String endpoint) {
                this.enabled = enabled;
                this.endpoint = normalizeEndpoint(endpoint, DEFAULT_ENDPOINT);
            }

            public static FreeDictionary fromMap(Map<String, Object> map, FreeDictionary fallback) {
                FreeDictionary defaults = fallback == null ? defaultValues() : fallback;
                return new FreeDictionary(
                        getBoolean(map, "enabled", defaults.isEnabled()),
                        getString(map, "endpoint", defaults.getEndpoint())
                );
            }

            public static FreeDictionary defaultValues() {
                return new FreeDictionary(true, DEFAULT_ENDPOINT);
            }

            public boolean isEnabled() {
                return enabled;
            }

            public String getEndpoint() {
                return endpoint;
            }
        }

        public static final class MerriamWebster {
            public static final String DEFAULT_ENDPOINT =
                    "https://www.dictionaryapi.com/api/v3/references/collegiate/json/";

            private final boolean enabled;
            private final String endpoint;
            private final String apiKey;

            private MerriamWebster(boolean enabled, String endpoint, String apiKey) {
                this.enabled = enabled;
                this.endpoint = normalizeEndpoint(endpoint, DEFAULT_ENDPOINT);
                this.apiKey = nullToEmpty(apiKey);
            }

            public static MerriamWebster fromMap(Map<String, Object> map, MerriamWebster fallback) {
                MerriamWebster defaults = fallback == null ? defaultValues() : fallback;
                return new MerriamWebster(
                        getBoolean(map, "enabled", defaults.isEnabled()),
                        getString(map, "endpoint", defaults.getEndpoint()),
                        getString(map, "apiKey", defaults.getApiKey())
                );
            }

            public static MerriamWebster defaultValues() {
                return new MerriamWebster(true, DEFAULT_ENDPOINT, "");
            }

            public boolean isEnabled() {
                return enabled;
            }

            public boolean isAvailable() {
                return enabled && !apiKey.isBlank();
            }

            public String getEndpoint() {
                return endpoint;
            }

            public String getApiKey() {
                return apiKey;
            }
        }

        private static int positiveOrDefault(int value, int defaultValue) {
            return value > 0 ? value : defaultValue;
        }

        private static String normalizeEndpoint(String endpoint, String defaultEndpoint) {
            String normalized = nullToEmpty(endpoint);
            if (normalized.isBlank()) {
                return defaultEndpoint;
            }
            return normalized.endsWith("/") ? normalized : normalized + "/";
        }
    }

    public static class Stats {
        private final String storage;
        private final Mysql mysql;
        private final Sqlite sqlite;

        private Stats(String storage, Mysql mysql, Sqlite sqlite) {
            this.storage = (storage == null || storage.isBlank()) ? "mysql" : storage.trim().toLowerCase(Locale.ROOT);
            this.mysql = mysql == null ? Mysql.defaultValues() : mysql;
            this.sqlite = sqlite == null ? Sqlite.defaultValues() : sqlite;
        }

        public static Stats fromMap(Map<String, Object> map, Stats fallback) {
            Stats defaults = fallback == null ? defaultValues() : fallback;
            return new Stats(
                    getString(map, "storage", defaults.getStorage()),
                    Mysql.fromMap(asMap(map.get("mysql")), defaults.getMysql()),
                    Sqlite.fromMap(asMap(map.get("sqlite")), defaults.getSqlite())
            );
        }

        public static Stats defaultValues() {
            return new Stats("sqlite", Mysql.defaultValues(), Sqlite.defaultValues());
        }

        public String getStorage() {
            return storage;
        }

        public Mysql getMysql() {
            return mysql;
        }

        public Sqlite getSqlite() {
            return sqlite;
        }

        public static class Mysql {
            private final String jdbcUrl;
            private final String username;
            private final String password;
            private final int poolSize;

            private Mysql(String jdbcUrl, String username, String password, int poolSize) {
                this.jdbcUrl = jdbcUrl;
                this.username = username;
                this.password = password;
                this.poolSize = Math.max(2, poolSize);
            }

            public static Mysql fromMap(Map<String, Object> map, Mysql fallback) {
                Mysql defaults = fallback == null ? defaultValues() : fallback;
                return new Mysql(
                        getString(map, "jdbcUrl", defaults.getJdbcUrl()),
                        getString(map, "username", defaults.getUsername()),
                        getString(map, "password", defaults.getPassword()),
                        getInt(map, "poolSize", defaults.getPoolSize())
                );
            }

            public static Mysql defaultValues() {
                return new Mysql(
                        "jdbc:mysql://localhost:3306/discord_bot?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        "root",
                        "",
                        8
                );
            }

            public String getJdbcUrl() {
                return jdbcUrl;
            }

            public String getUsername() {
                return username;
            }

            public String getPassword() {
                return password;
            }

            public int getPoolSize() {
                return poolSize;
            }
        }

        public static class Sqlite {
            private final String path;

            private Sqlite(String path) {
                this.path = (path == null || path.isBlank()) ? "data/message-stats.db" : path.trim();
            }

            public static Sqlite fromMap(Map<String, Object> map, Sqlite fallback) {
                Sqlite defaults = fallback == null ? defaultValues() : fallback;
                return new Sqlite(getString(map, "path", defaults.getPath()));
            }

            public static Sqlite defaultValues() {
                return new Sqlite("data/message-stats.db");
            }

            public String getPath() {
                return path;
            }
        }
    }

    public static class Notifications {
        private final boolean enabled;
        private final boolean memberJoinEnabled;
        private final boolean memberLeaveEnabled;
        private final boolean voiceLogEnabled;
        private final Long memberChannelId;
        private final Long memberJoinChannelId;
        private final Long memberLeaveChannelId;
        private final String memberJoinTitle;
        private final String memberJoinMessage;
        private final String memberJoinThumbnailUrl;
        private final String memberJoinImageUrl;
        private final String memberLeaveMessage;
        private final int memberJoinColor;
        private final int memberLeaveColor;
        private final Long voiceChannelId;
        private final String voiceJoinMessage;
        private final String voiceLeaveMessage;
        private final String voiceMoveMessage;
        private final int voiceJoinColor;
        private final int voiceLeaveColor;
        private final int voiceMoveColor;

        private Notifications(boolean enabled,
                              boolean memberJoinEnabled,
                              boolean memberLeaveEnabled,
                              boolean voiceLogEnabled,
                              Long memberChannelId,
                              Long memberJoinChannelId,
                              Long memberLeaveChannelId,
                              String memberJoinTitle,
                              String memberJoinMessage,
                              String memberJoinThumbnailUrl,
                              String memberJoinImageUrl,
                              String memberLeaveMessage,
                              int memberJoinColor,
                              int memberLeaveColor,
                              Long voiceChannelId,
                              String voiceJoinMessage,
                              String voiceLeaveMessage,
                              String voiceMoveMessage,
                              int voiceJoinColor,
                              int voiceLeaveColor,
                              int voiceMoveColor) {
            this.enabled = enabled;
            this.memberJoinEnabled = memberJoinEnabled;
            this.memberLeaveEnabled = memberLeaveEnabled;
            this.voiceLogEnabled = voiceLogEnabled;
            this.memberChannelId = memberChannelId;
            this.memberJoinChannelId = memberJoinChannelId;
            this.memberLeaveChannelId = memberLeaveChannelId;
            this.memberJoinTitle = memberJoinTitle;
            this.memberJoinMessage = memberJoinMessage;
            this.memberJoinThumbnailUrl = memberJoinThumbnailUrl;
            this.memberJoinImageUrl = memberJoinImageUrl;
            this.memberLeaveMessage = memberLeaveMessage;
            this.memberJoinColor = memberJoinColor;
            this.memberLeaveColor = memberLeaveColor;
            this.voiceChannelId = voiceChannelId;
            this.voiceJoinMessage = voiceJoinMessage;
            this.voiceLeaveMessage = voiceLeaveMessage;
            this.voiceMoveMessage = voiceMoveMessage;
            this.voiceJoinColor = normalizeColor(voiceJoinColor);
            this.voiceLeaveColor = normalizeColor(voiceLeaveColor);
            this.voiceMoveColor = normalizeColor(voiceMoveColor);
        }

        public static Notifications fromMap(Map<String, Object> map, Notifications fallback) {
            Notifications defaults = fallback == null ? defaultValues() : fallback;
            return new Notifications(
                    getBoolean(map, "enabled", defaults.isEnabled()),
                    getBoolean(map, "memberJoinEnabled", defaults.isMemberJoinEnabled()),
                    getBoolean(map, "memberLeaveEnabled", defaults.isMemberLeaveEnabled()),
                    getBoolean(map, "voiceLogEnabled", defaults.isVoiceLogEnabled()),
                    getLong(map, "memberChannelId", defaults.getMemberChannelId()),
                    getLong(map, "memberJoinChannelId", defaults.getMemberJoinChannelId()),
                    getLong(map, "memberLeaveChannelId", defaults.getMemberLeaveChannelId()),
                    getString(map, "memberJoinTitle", defaults.getMemberJoinTitle()),
                    getString(map, "memberJoinMessage", defaults.getMemberJoinMessage()),
                    getString(map, "memberJoinThumbnailUrl", defaults.getMemberJoinThumbnailUrl()),
                    getString(map, "memberJoinImageUrl", defaults.getMemberJoinImageUrl()),
                    getString(map, "memberLeaveMessage", defaults.getMemberLeaveMessage()),
                    getColor(map, "memberJoinColor", defaults.getMemberJoinColor()),
                    getColor(map, "memberLeaveColor", defaults.getMemberLeaveColor()),
                    getLong(map, "voiceChannelId", defaults.getVoiceChannelId()),
                    getString(map, "voiceJoinMessage", defaults.getVoiceJoinMessage()),
                    getString(map, "voiceLeaveMessage", defaults.getVoiceLeaveMessage()),
                    getString(map, "voiceMoveMessage", defaults.getVoiceMoveMessage()),
                    getColor(map, "voiceJoinColor", defaults.getVoiceJoinColor()),
                    getColor(map, "voiceLeaveColor", defaults.getVoiceLeaveColor()),
                    getColor(map, "voiceMoveColor", defaults.getVoiceMoveColor())
            );
        }

        public static Notifications defaultValues() {
            return new Notifications(
                    true,
                    true,
                    true,
                    true,
                    null,
                    null,
                    null,
                    "Member Joined",
                    "{user} joined the server. Account created: {createdAt} ({accountAgeDays} days ago). ID: {id}",
                    "",
                    "",
                    "{user} left the server. Account created: {createdAt} ({accountAgeDays} days ago). ID: {id}",
                    0x2ECC71,
                    0xE74C3C,
                    null,
                    "{user} joined voice channel {channel}.",
                    "{user} left voice channel {channel}.",
                    "{user} moved voice channel from {from} to {to}.",
                    0x2ECC71,
                    0xE74C3C,
                    0x5865F2
            );
        }

        public Notifications withEnabled(boolean enabled) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberJoinEnabled(boolean memberJoinEnabled) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberLeaveEnabled(boolean memberLeaveEnabled) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withVoiceLogEnabled(boolean voiceLogEnabled) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberChannelId(Long memberChannelId) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberJoinChannelId(Long memberJoinChannelId) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberLeaveChannelId(Long memberLeaveChannelId) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withVoiceChannelId(Long voiceChannelId) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberJoinTitle(String memberJoinTitle) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberJoinMessage(String memberJoinMessage) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberJoinThumbnailUrl(String memberJoinThumbnailUrl) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberJoinImageUrl(String memberJoinImageUrl) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberLeaveMessage(String memberLeaveMessage) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withVoiceJoinMessage(String voiceJoinMessage) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withVoiceLeaveMessage(String voiceLeaveMessage) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withVoiceMoveMessage(String voiceMoveMessage) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberJoinColor(int memberJoinColor) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, normalizeColor(memberJoinColor), memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withMemberLeaveColor(int memberLeaveColor) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, normalizeColor(memberLeaveColor), voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withVoiceJoinColor(int voiceJoinColor) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, normalizeColor(voiceJoinColor), voiceLeaveColor, voiceMoveColor);
        }

        public Notifications withVoiceLeaveColor(int voiceLeaveColor) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, normalizeColor(voiceLeaveColor), voiceMoveColor);
        }

        public Notifications withVoiceMoveColor(int voiceMoveColor) {
            return new Notifications(enabled, memberJoinEnabled, memberLeaveEnabled, voiceLogEnabled, memberChannelId, memberJoinChannelId, memberLeaveChannelId, memberJoinTitle, memberJoinMessage, memberJoinThumbnailUrl, memberJoinImageUrl, memberLeaveMessage, memberJoinColor, memberLeaveColor, voiceChannelId,
                    voiceJoinMessage, voiceLeaveMessage, voiceMoveMessage, voiceJoinColor, voiceLeaveColor, normalizeColor(voiceMoveColor));
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isMemberJoinEnabled() {
            return memberJoinEnabled;
        }

        public boolean isMemberLeaveEnabled() {
            return memberLeaveEnabled;
        }

        public boolean isVoiceLogEnabled() {
            return voiceLogEnabled;
        }

        public Long getMemberChannelId() {
            return memberChannelId;
        }

        public Long getMemberJoinChannelId() {
            return memberJoinChannelId;
        }

        public Long getMemberLeaveChannelId() {
            return memberLeaveChannelId;
        }

        public String getMemberJoinTitle() {
            return memberJoinTitle;
        }

        public String getMemberJoinMessage() {
            return memberJoinMessage;
        }

        public String getMemberJoinThumbnailUrl() {
            return memberJoinThumbnailUrl;
        }

        public String getMemberJoinImageUrl() {
            return memberJoinImageUrl;
        }

        public String getMemberLeaveMessage() {
            return memberLeaveMessage;
        }

        public int getMemberJoinColor() {
            return memberJoinColor;
        }

        public int getMemberLeaveColor() {
            return memberLeaveColor;
        }

        public Long getVoiceChannelId() {
            return voiceChannelId;
        }

        public String getVoiceJoinMessage() {
            return voiceJoinMessage;
        }

        public String getVoiceLeaveMessage() {
            return voiceLeaveMessage;
        }

        public String getVoiceMoveMessage() {
            return voiceMoveMessage;
        }

        public int getVoiceJoinColor() {
            return voiceJoinColor;
        }

        public int getVoiceLeaveColor() {
            return voiceLeaveColor;
        }

        public int getVoiceMoveColor() {
            return voiceMoveColor;
        }

        private static int normalizeColor(int value) {
            return value & 0xFFFFFF;
        }
    }

    public static class Welcome {
        private final boolean enabled;
        private final Long channelId;
        private final String title;
        private final String message;
        private final String thumbnailUrl;
        private final String imageUrl;

        private Welcome(boolean enabled,
                        Long channelId,
                        String title,
                        String message,
                        String thumbnailUrl,
                        String imageUrl) {
            this.enabled = enabled;
            this.channelId = channelId;
            this.title = title == null ? "" : title.trim();
            this.message = message == null ? "" : message.trim();
            this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl.trim();
            this.imageUrl = imageUrl == null ? "" : imageUrl.trim();
        }

        public static Welcome fromMap(Map<String, Object> map, Welcome fallback) {
            Welcome defaults = fallback == null ? defaultValues() : fallback;
            return new Welcome(
                    getBoolean(map, "enabled", defaults.isEnabled()),
                    getLong(map, "channelId", defaults.getChannelId()),
                    getString(map, "title", defaults.getTitle()),
                    getString(map, "message", defaults.getMessage()),
                    getString(map, "thumbnailUrl", defaults.getThumbnailUrl()),
                    getString(map, "imageUrl", defaults.getImageUrl())
            );
        }

        public static Welcome defaultValues() {
            return new Welcome(
                    false,
                    null,
                    "",
                    "{user} joined {guild}.",
                    "",
                    ""
            );
        }

        public Welcome withEnabled(boolean enabled) {
            return new Welcome(enabled, channelId, title, message, thumbnailUrl, imageUrl);
        }

        public Welcome withChannelId(Long channelId) {
            return new Welcome(enabled, channelId, title, message, thumbnailUrl, imageUrl);
        }

        public Welcome withTitle(String title) {
            return new Welcome(enabled, channelId, title, message, thumbnailUrl, imageUrl);
        }

        public Welcome withMessage(String message) {
            return new Welcome(enabled, channelId, title, message, thumbnailUrl, imageUrl);
        }

        public Welcome withThumbnailUrl(String thumbnailUrl) {
            return new Welcome(enabled, channelId, title, message, thumbnailUrl, imageUrl);
        }

        public Welcome withImageUrl(String imageUrl) {
            return new Welcome(enabled, channelId, title, message, thumbnailUrl, imageUrl);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Long getChannelId() {
            return channelId;
        }

        public String getTitle() {
            return title;
        }

        public String getMessage() {
            return message;
        }

        public String getThumbnailUrl() {
            return thumbnailUrl;
        }

        public String getImageUrl() {
            return imageUrl;
        }
    }

    public static class MessageLogs {
        private final boolean enabled;
        private final Long channelId;
        private final Long messageLogChannelId;
        private final Long commandUsageChannelId;
        private final Long channelLifecycleChannelId;
        private final Long roleLogChannelId;
        private final Long moderationLogChannelId;
        private final boolean roleLogEnabled;
        private final boolean channelLifecycleLogEnabled;
        private final boolean moderationLogEnabled;
        private final boolean commandUsageLogEnabled;
        private final List<Long> ignoredMemberIds;
        private final List<Long> ignoredRoleIds;
        private final List<Long> ignoredChannelIds;
        private final List<String> ignoredPrefixes;

        private MessageLogs(boolean enabled,
                            Long channelId,
                            Long messageLogChannelId,
                            Long commandUsageChannelId,
                            Long channelLifecycleChannelId,
                            Long roleLogChannelId,
                            Long moderationLogChannelId,
                            boolean roleLogEnabled,
                            boolean channelLifecycleLogEnabled,
                            boolean moderationLogEnabled,
                            boolean commandUsageLogEnabled,
                            List<Long> ignoredMemberIds,
                            List<Long> ignoredRoleIds,
                            List<Long> ignoredChannelIds,
                            List<String> ignoredPrefixes) {
            this.enabled = enabled;
            this.channelId = channelId;
            this.messageLogChannelId = messageLogChannelId;
            this.commandUsageChannelId = commandUsageChannelId;
            this.channelLifecycleChannelId = channelLifecycleChannelId;
            this.roleLogChannelId = roleLogChannelId;
            this.moderationLogChannelId = moderationLogChannelId;
            this.roleLogEnabled = roleLogEnabled;
            this.channelLifecycleLogEnabled = channelLifecycleLogEnabled;
            this.moderationLogEnabled = moderationLogEnabled;
            this.commandUsageLogEnabled = commandUsageLogEnabled;
            this.ignoredMemberIds = ignoredMemberIds == null ? List.of() : ignoredMemberIds.stream()
                    .filter(value -> value != null && value > 0L)
                    .distinct()
                    .toList();
            this.ignoredRoleIds = ignoredRoleIds == null ? List.of() : ignoredRoleIds.stream()
                    .filter(value -> value != null && value > 0L)
                    .distinct()
                    .toList();
            this.ignoredChannelIds = ignoredChannelIds == null ? List.of() : ignoredChannelIds.stream()
                    .filter(value -> value != null && value > 0L)
                    .distinct()
                    .toList();
            this.ignoredPrefixes = ignoredPrefixes == null ? List.of() : ignoredPrefixes.stream()
                    .map(value -> value == null ? "" : value.trim())
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        }

        public static MessageLogs fromMap(Map<String, Object> map, MessageLogs fallback) {
            MessageLogs defaults = fallback == null ? defaultValues() : fallback;
            return new MessageLogs(
                    getBoolean(map, "enabled", defaults.isEnabled()),
                    getLong(map, "channelId", defaults.getChannelId()),
                    getLong(map, "messageLogChannelId", defaults.getMessageLogChannelId()),
                    getLong(map, "commandUsageChannelId", defaults.getCommandUsageChannelId()),
                    getLong(map, "channelLifecycleChannelId", defaults.getChannelLifecycleChannelId()),
                    getLong(map, "roleLogChannelId", defaults.getRoleLogChannelId()),
                    getLong(map, "moderationLogChannelId", defaults.getModerationLogChannelId()),
                    getBoolean(map, "roleLogEnabled", defaults.isRoleLogEnabled()),
                    getBoolean(map, "channelLifecycleLogEnabled", defaults.isChannelLifecycleLogEnabled()),
                    getBoolean(map, "moderationLogEnabled", defaults.isModerationLogEnabled()),
                    getBoolean(map, "commandUsageLogEnabled", defaults.isCommandUsageLogEnabled()),
                    getLongList(map, "ignoredMemberIds", defaults.getIgnoredMemberIds()),
                    getLongList(map, "ignoredRoleIds", defaults.getIgnoredRoleIds()),
                    getLongList(map, "ignoredChannelIds", defaults.getIgnoredChannelIds()),
                    getStringList(map, "ignoredPrefixes", defaults.getIgnoredPrefixes())
            );
        }

        public static MessageLogs defaultValues() {
            return new MessageLogs(true, null, null, null, null, null, null, true, true, true, true, List.of(), List.of(), List.of(), List.of());
        }

        public MessageLogs withEnabled(boolean enabled) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withChannelId(Long channelId) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withMessageLogChannelId(Long messageLogChannelId) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withCommandUsageChannelId(Long commandUsageChannelId) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withChannelLifecycleChannelId(Long channelLifecycleChannelId) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withRoleLogChannelId(Long roleLogChannelId) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withModerationLogChannelId(Long moderationLogChannelId) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withRoleLogEnabled(boolean value) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, value, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withChannelLifecycleLogEnabled(boolean value) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, value, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withModerationLogEnabled(boolean value) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, value, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withCommandUsageLogEnabled(boolean value) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, value, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withIgnoredMemberIds(List<Long> ignoredMemberIds) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withIgnoredRoleIds(List<Long> ignoredRoleIds) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withIgnoredChannelIds(List<Long> ignoredChannelIds) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public MessageLogs withIgnoredPrefixes(List<String> ignoredPrefixes) {
            return new MessageLogs(enabled, channelId, messageLogChannelId, commandUsageChannelId, channelLifecycleChannelId, roleLogChannelId, moderationLogChannelId, roleLogEnabled, channelLifecycleLogEnabled, moderationLogEnabled, commandUsageLogEnabled, ignoredMemberIds, ignoredRoleIds, ignoredChannelIds, ignoredPrefixes);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Long getChannelId() {
            return channelId;
        }

        public Long getMessageLogChannelId() {
            return messageLogChannelId;
        }

        public Long getCommandUsageChannelId() {
            return commandUsageChannelId;
        }

        public Long getChannelLifecycleChannelId() {
            return channelLifecycleChannelId;
        }

        public Long getRoleLogChannelId() {
            return roleLogChannelId;
        }

        public Long getModerationLogChannelId() {
            return moderationLogChannelId;
        }

        public boolean isRoleLogEnabled() {
            return roleLogEnabled;
        }

        public boolean isChannelLifecycleLogEnabled() {
            return channelLifecycleLogEnabled;
        }

        public boolean isModerationLogEnabled() {
            return moderationLogEnabled;
        }

        public boolean isCommandUsageLogEnabled() {
            return commandUsageLogEnabled;
        }

        public List<Long> getIgnoredMemberIds() {
            return ignoredMemberIds;
        }

        public List<Long> getIgnoredRoleIds() {
            return ignoredRoleIds;
        }

        public List<Long> getIgnoredChannelIds() {
            return ignoredChannelIds;
        }

        public List<String> getIgnoredPrefixes() {
            return ignoredPrefixes;
        }
    }

    public static class Music {
        public enum RepeatMode {
            OFF, SINGLE, ALL
        }

        private final boolean autoLeaveEnabled;
        private final int autoLeaveMinutes;
        private final boolean autoplayEnabled;
        private final RepeatMode defaultRepeatMode;
        private final Long commandChannelId;
        private final int historyLimit;
        private final int statsRetentionDays;
        private final int playlistTrackLimit;
        private final Youtube youtube;
        private final Oauth oauth;
        private final Cipher cipher;
        private final Spotify spotify;
        private final Audio audio;

        private Music(boolean autoLeaveEnabled,
                      int autoLeaveMinutes,
                      boolean autoplayEnabled,
                      RepeatMode defaultRepeatMode,
                      Long commandChannelId,
                      int historyLimit,
                       int statsRetentionDays,
                       int playlistTrackLimit,
                       Youtube youtube,
                       Oauth oauth,
                       Cipher cipher,
                       Spotify spotify,
                       Audio audio) {
            this.autoLeaveEnabled = autoLeaveEnabled;
            this.autoLeaveMinutes = autoLeaveMinutes;
            this.autoplayEnabled = autoplayEnabled;
            this.defaultRepeatMode = defaultRepeatMode;
            this.commandChannelId = commandChannelId;
            this.historyLimit = Math.max(1, historyLimit);
            this.statsRetentionDays = Math.max(0, statsRetentionDays);
            this.playlistTrackLimit = Math.max(1, playlistTrackLimit);
            this.youtube = youtube == null ? Youtube.defaultValues() : youtube;
            this.oauth = oauth == null ? Oauth.defaultValues() : oauth;
            this.cipher = cipher == null ? Cipher.defaultValues() : cipher;
            this.spotify = spotify == null ? Spotify.defaultValues() : spotify;
            this.audio = audio == null ? Audio.defaultValues() : audio;
        }

        public static Music fromMap(Map<String, Object> map, Music fallback) {
            Music defaults = fallback == null ? defaultValues() : fallback;
            return new Music(
                    getBoolean(map, "autoLeaveEnabled", defaults.isAutoLeaveEnabled()),
                    getInt(map, "autoLeaveMinutes", defaults.getAutoLeaveMinutes()),
                    getBoolean(map, "autoplayEnabled", defaults.isAutoplayEnabled()),
                    parseRepeatMode(getString(map, "defaultRepeatMode", defaults.getDefaultRepeatMode().name())),
                    getLong(map, "commandChannelId", defaults.getCommandChannelId()),
                    getInt(map, "historyLimit", defaults.getHistoryLimit()),
                     getInt(map, "statsRetentionDays", defaults.getStatsRetentionDays()),
                     getInt(map, "playlistTrackLimit", defaults.getPlaylistTrackLimit()),
                     Youtube.fromMap(asMap(map.get("youtube")), defaults.getYoutube()),
                     Oauth.fromMap(asMap(map.get("oauth")), defaults.getOauth()),
                     Cipher.fromMap(asMap(map.get("cipher")), defaults.getCipher()),
                     Spotify.fromMap(asMap(map.get("spotify")), defaults.getSpotify()),
                     Audio.fromMap(asMap(map.get("audio")), defaults.getAudio())
             );
        }

        public static Music defaultValues() {
            return new Music(true, 5, true, RepeatMode.OFF, null, 50, 0, 100,
                    Youtube.defaultValues(), Oauth.defaultValues(), Cipher.defaultValues(),
                    Spotify.defaultValues(), Audio.defaultValues());
        }

        public Music withAutoLeaveEnabled(boolean enabled) {
            return new Music(enabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, oauth, cipher, spotify, audio);
        }

        public Music withAutoLeaveMinutes(int minutes) {
            return new Music(autoLeaveEnabled, Math.max(1, minutes), autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, oauth, cipher, spotify, audio);
        }

        public Music withAutoplayEnabled(boolean enabled) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, enabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, oauth, cipher, spotify, audio);
        }

        public Music withDefaultRepeatMode(RepeatMode mode) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, mode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, oauth, cipher, spotify, audio);
        }

        public Music withCommandChannelId(Long commandChannelId) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, oauth, cipher, spotify, audio);
        }

        public Music withHistoryLimit(int historyLimit) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, oauth, cipher, spotify, audio);
        }

        public Music withStatsRetentionDays(int statsRetentionDays) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, oauth, cipher, spotify, audio);
        }

        public Music withPlaylistTrackLimit(int playlistTrackLimit) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, oauth, cipher, spotify, audio);
        }

        public boolean isAutoLeaveEnabled() {
            return autoLeaveEnabled;
        }

        public int getAutoLeaveMinutes() {
            return autoLeaveMinutes;
        }

        public boolean isAutoplayEnabled() {
            return autoplayEnabled;
        }

        public RepeatMode getDefaultRepeatMode() {
            return defaultRepeatMode;
        }

        public Long getCommandChannelId() {
            return commandChannelId;
        }

        public int getHistoryLimit() {
            return historyLimit;
        }

        public int getStatsRetentionDays() {
            return statsRetentionDays;
        }

        public int getPlaylistTrackLimit() {
            return playlistTrackLimit;
        }

        public Youtube getYoutube() {
            return youtube;
        }

        public Oauth getOauth() {
            return oauth;
        }

        public Cipher getCipher() {
            return cipher;
        }

        public Spotify getSpotify() {
            return spotify;
        }

        public Audio getAudio() {
            return audio;
        }

        private static RepeatMode parseRepeatMode(String value) {
            try {
                return RepeatMode.valueOf(value.trim().toUpperCase());
            } catch (Exception ignored) {
                return RepeatMode.OFF;
            }
        }

        public static class Audio {
            private final DirectHttp directHttp;
            private final Recovery recovery;

            private Audio(DirectHttp directHttp, Recovery recovery) {
                this.directHttp = directHttp == null ? DirectHttp.defaultValues() : directHttp;
                this.recovery = recovery == null ? Recovery.defaultValues() : recovery;
            }

            public static Audio fromMap(Map<String, Object> map, Audio fallback) {
                Audio defaults = fallback == null ? defaultValues() : fallback;
                Map<String, Object> directHttp = asMap(map.get("directHttp"));
                if (directHttp.isEmpty()) {
                    directHttp = asMap(map.get("direct-http"));
                }
                return new Audio(
                        DirectHttp.fromMap(directHttp, defaults.getDirectHttp()),
                        Recovery.fromMap(asMap(map.get("recovery")), defaults.getRecovery())
                );
            }

            public static Audio defaultValues() {
                return new Audio(DirectHttp.defaultValues(), Recovery.defaultValues());
            }

            public DirectHttp getDirectHttp() {
                return directHttp;
            }

            public Recovery getRecovery() {
                return recovery;
            }

            public static class DirectHttp {
                private final boolean enabled;
                private final int connectTimeoutMillis;
                private final int readTimeoutMillis;
                private final int maxRedirects;
                private final List<String> allowedHosts;

                private DirectHttp(boolean enabled,
                                   int connectTimeoutMillis,
                                   int readTimeoutMillis,
                                   int maxRedirects,
                                   List<String> allowedHosts) {
                    this.enabled = enabled;
                    this.connectTimeoutMillis = Math.max(1, connectTimeoutMillis);
                    this.readTimeoutMillis = Math.max(1, readTimeoutMillis);
                    this.maxRedirects = Math.max(0, maxRedirects);
                    this.allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
                }

                public static DirectHttp fromMap(Map<String, Object> map, DirectHttp fallback) {
                    DirectHttp defaults = fallback == null ? defaultValues() : fallback;
                    return new DirectHttp(
                            getBoolean(map, "enabled", defaults.isEnabled()),
                            getInt(map, "connectTimeoutMillis",
                                    getInt(map, "connect-timeout-ms", defaults.getConnectTimeoutMillis())),
                            getInt(map, "readTimeoutMillis",
                                    getInt(map, "read-timeout-ms", defaults.getReadTimeoutMillis())),
                            getInt(map, "maxRedirects",
                                    getInt(map, "max-redirects", defaults.getMaxRedirects())),
                            getStringList(map, "allowedHosts",
                                    getStringList(map, "allowed-hosts", defaults.getAllowedHosts()))
                    );
                }

                public static DirectHttp defaultValues() {
                    return new DirectHttp(false, 5000, 10000, 3, List.of());
                }

                public boolean isEnabled() { return enabled; }
                public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
                public int getReadTimeoutMillis() { return readTimeoutMillis; }
                public int getMaxRedirects() { return maxRedirects; }
                public List<String> getAllowedHosts() { return allowedHosts; }
            }

            public static class Recovery {
                private final boolean enabled;
                private final int maxStuckRetries;
                private final int resumeRewindMillis;
                private final int stuckThresholdMillis;

                private Recovery(boolean enabled,
                                 int maxStuckRetries,
                                 int resumeRewindMillis,
                                 int stuckThresholdMillis) {
                    this.enabled = enabled;
                    this.maxStuckRetries = Math.max(0, maxStuckRetries);
                    this.resumeRewindMillis = Math.max(0, resumeRewindMillis);
                    this.stuckThresholdMillis = Math.max(1, stuckThresholdMillis);
                }

                public static Recovery fromMap(Map<String, Object> map, Recovery fallback) {
                    Recovery defaults = fallback == null ? defaultValues() : fallback;
                    return new Recovery(
                            getBoolean(map, "enabled", defaults.isEnabled()),
                            getInt(map, "maxStuckRetries",
                                    getInt(map, "max-stuck-retries", defaults.getMaxStuckRetries())),
                            getInt(map, "resumeRewindMillis",
                                    getInt(map, "resume-rewind-ms", defaults.getResumeRewindMillis())),
                            getInt(map, "stuckThresholdMillis",
                                    getInt(map, "stuck-threshold-ms", defaults.getStuckThresholdMillis()))
                    );
                }

                public static Recovery defaultValues() {
                    return new Recovery(true, 2, 2000, 20000);
                }

                public boolean isEnabled() { return enabled; }
                public int getMaxStuckRetries() { return maxStuckRetries; }
                public int getResumeRewindMillis() { return resumeRewindMillis; }
                public int getStuckThresholdMillis() { return stuckThresholdMillis; }
            }
        }

        public static final class Oauth {
            private final boolean enabled;
            private final String refreshToken;

            private Oauth(boolean enabled, String refreshToken) {
                this.enabled = enabled;
                this.refreshToken = nullToEmpty(refreshToken);
            }

            private static Oauth fromMap(Map<String, Object> map, Oauth fallback) {
                Oauth defaults = fallback == null ? defaultValues() : fallback;
                return new Oauth(
                        getBoolean(map, "enabled", defaults.isEnabled()),
                        getString(map, "refreshToken", defaults.getRefreshToken())
                );
            }

            private static Oauth defaultValues() {
                return new Oauth(false, "");
            }

            public boolean isEnabled() { return enabled; }
            public String getRefreshToken() { return refreshToken; }
        }

        public static final class Cipher {
            private final boolean enabled;
            private final String server;
            private final String password;
            private final String userAgent;

            private Cipher(boolean enabled, String server, String password, String userAgent) {
                this.enabled = enabled;
                this.server = nullToEmpty(server);
                this.password = nullToEmpty(password);
                this.userAgent = nullToEmpty(userAgent);
            }

            private static Cipher fromMap(Map<String, Object> map, Cipher fallback) {
                Cipher defaults = fallback == null ? defaultValues() : fallback;
                return new Cipher(
                        getBoolean(map, "enabled", defaults.isEnabled()),
                        getString(map, "server", defaults.getServer()),
                        getString(map, "password", defaults.getPassword()),
                        getString(map, "userAgent", defaults.getUserAgent())
                );
            }

            private static Cipher defaultValues() {
                return new Cipher(false, "http://localhost:8001", "", "norule-music-bot");
            }

            public boolean isEnabled() { return enabled; }
            public String getServer() { return server; }
            public String getPassword() { return password; }
            public String getUserAgent() { return userAgent; }
        }

        public static class Spotify {
            private final boolean enabled;
            private final String clientId;
            private final String clientSecret;
            private final String spDc;
            private final String countryCode;
            private final boolean preferAnonymousToken;
            private final int playlistMaxTracks;
            private final int playlistLoadCooldownSeconds;

            private Spotify(boolean enabled,
                            String clientId,
                            String clientSecret,
                            String spDc,
                            String countryCode,
                            boolean preferAnonymousToken,
                            int playlistMaxTracks,
                            int playlistLoadCooldownSeconds) {
                this.enabled = enabled;
                this.clientId = nullToEmpty(clientId);
                this.clientSecret = nullToEmpty(clientSecret);
                this.spDc = nullToEmpty(spDc);
                this.countryCode = nullToEmpty(countryCode);
                this.preferAnonymousToken = preferAnonymousToken;
                this.playlistMaxTracks = Math.max(1, playlistMaxTracks);
                this.playlistLoadCooldownSeconds = Math.max(0, playlistLoadCooldownSeconds);
            }

            public static Spotify fromMap(Map<String, Object> map, Spotify fallback) {
                Spotify defaults = fallback == null ? defaultValues() : fallback;
                return new Spotify(
                        getBoolean(map, "enabled", defaults.isEnabled()),
                        getString(map, "clientId", defaults.getClientId()),
                        getString(map, "clientSecret", defaults.getClientSecret()),
                        getString(map, "spDc", defaults.getSpDc()),
                        getString(map, "countryCode", defaults.getCountryCode()),
                        getBoolean(map, "preferAnonymousToken", defaults.isPreferAnonymousToken()),
                        getInt(map, "playlistMaxTracks", defaults.getPlaylistMaxTracks()),
                        getInt(map, "playlistLoadCooldownSeconds", defaults.getPlaylistLoadCooldownSeconds())
                );
            }

        public static Spotify defaultValues() {
                return new Spotify(false, "", "", "", "TW", false, 50, 60);
            }

            public boolean isEnabled() {
                return enabled;
            }

            public String getClientId() {
                return clientId;
            }

            public String getClientSecret() {
                return clientSecret;
            }

            public String getSpDc() {
                return spDc;
            }

            public String getCountryCode() {
                return countryCode;
            }

            public boolean isPreferAnonymousToken() {
                return preferAnonymousToken;
            }

            public int getPlaylistMaxTracks() {
                return playlistMaxTracks;
            }

            public int getPlaylistLoadCooldownSeconds() {
                return playlistLoadCooldownSeconds;
            }
        }

        public static class Youtube {
            private final String playbackBackend;
            private final Companion companion;
            private final StrictPrecheck strictPrecheck;

            private Youtube(String playbackBackend,
                            Companion companion,
                            StrictPrecheck strictPrecheck) {
                this.playbackBackend = nullToEmpty(playbackBackend);
                this.companion = companion == null ? Companion.defaultValues() : companion;
                this.strictPrecheck = strictPrecheck == null ? StrictPrecheck.defaultValues() : strictPrecheck;
            }

            public static Youtube fromMap(Map<String, Object> map, Youtube fallback) {
                Youtube defaults = fallback == null ? defaultValues() : fallback;
                return new Youtube(
                        getString(map, "playbackBackend", defaults.getPlaybackBackend()),
                        Companion.fromMap(asMap(map.get("companion")), defaults.getCompanion()),
                        StrictPrecheck.fromMap(asMap(map.get("strictPrecheck")), defaults.getStrictPrecheck())
                );
            }

            public static Youtube defaultValues() {
                return new Youtube(
                        "YOUTUBE_SOURCE",
                        Companion.defaultValues(),
                        StrictPrecheck.defaultValues()
                );
            }

            public String getPlaybackBackend() {
                return playbackBackend;
            }

            public Companion getCompanion() {
                return companion;
            }

            public StrictPrecheck getStrictPrecheck() {
                return strictPrecheck;
            }

            public static class Companion {
                private final boolean enabled;
                private final String url;
                private final String secret;
                private final boolean fallbackToSource;
                private final int connectTimeoutMillis;
                private final int requestTimeoutMillis;

                private Companion(boolean enabled,
                                  String url,
                                  String secret,
                                  boolean fallbackToSource,
                                  int connectTimeoutMillis,
                                  int requestTimeoutMillis) {
                    this.enabled = enabled;
                    this.url = nullToEmpty(url);
                    this.secret = nullToEmpty(secret);
                    this.fallbackToSource = fallbackToSource;
                    this.connectTimeoutMillis = Math.max(1, connectTimeoutMillis);
                    this.requestTimeoutMillis = Math.max(1, requestTimeoutMillis);
                }

                static Companion fromMap(Map<String, Object> map, Companion fallback) {
                    Companion defaults = fallback == null ? defaultValues() : fallback;
                    return new Companion(
                            getBoolean(map, "enabled", defaults.isEnabled()),
                            getString(map, "url", defaults.getUrl()),
                            getString(map, "secret", defaults.getSecret()),
                            getBoolean(map, "fallbackToSource", defaults.isFallbackToSource()),
                            getInt(map, "connectTimeoutMillis", defaults.getConnectTimeoutMillis()),
                            getInt(map, "requestTimeoutMillis", defaults.getRequestTimeoutMillis())
                    );
                }

                static Companion defaultValues() {
                    return new Companion(false, "http://127.0.0.1:8282", "", true, 5000, 10000);
                }

                public boolean isEnabled() { return enabled; }
                public String getUrl() { return url; }
                public String getSecret() { return secret; }
                public boolean isFallbackToSource() { return fallbackToSource; }
                public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
                public int getRequestTimeoutMillis() { return requestTimeoutMillis; }
            }

            public static class StrictPrecheck {
                private final boolean enabled;
                private final int cacheTtlHours;
                private final int playableTtlHours;
                private final int temporaryFailureTtlMinutes;
                private final int permanentFailureTtlHours;
                private final int timeoutMillis;
                private final String lavalinkBaseUrl;
                private final String lavalinkPassword;

                private StrictPrecheck(boolean enabled,
                                       int cacheTtlHours,
                                       int playableTtlHours,
                                       int temporaryFailureTtlMinutes,
                                       int permanentFailureTtlHours,
                                       int timeoutMillis,
                                       String lavalinkBaseUrl,
                                       String lavalinkPassword) {
                    this.enabled = enabled;
                    this.cacheTtlHours = Math.max(1, cacheTtlHours);
                    this.playableTtlHours = Math.max(1, playableTtlHours);
                    this.temporaryFailureTtlMinutes = Math.max(1, temporaryFailureTtlMinutes);
                    this.permanentFailureTtlHours = Math.max(1, permanentFailureTtlHours);
                    this.timeoutMillis = Math.max(1, timeoutMillis);
                    this.lavalinkBaseUrl = nullToEmpty(lavalinkBaseUrl);
                    this.lavalinkPassword = nullToEmpty(lavalinkPassword);
                }

                public static StrictPrecheck fromMap(Map<String, Object> map, StrictPrecheck fallback) {
                    StrictPrecheck defaults = fallback == null ? defaultValues() : fallback;
                    Map<String, Object> cache = asMap(map.get("cache"));
                    int legacyCacheTtlHours = getInt(map, "cacheTtlHours", defaults.getCacheTtlHours());
                    String baseUrl = getString(map, "lavalinkBaseUrl", getString(map, "baseUrl", defaults.getLavalinkBaseUrl()));
                    String password = getString(map, "lavalinkPassword", getString(map, "password", defaults.getLavalinkPassword()));
                    return new StrictPrecheck(
                            getBoolean(map, "enabled", defaults.isEnabled()),
                            legacyCacheTtlHours,
                            getInt(cache, "playableTtlHours",
                                    getInt(map, "playableTtlHours", legacyCacheTtlHours)),
                            getInt(cache, "temporaryFailureTtlMinutes",
                                    getInt(map, "temporaryFailureTtlMinutes", defaults.getTemporaryFailureTtlMinutes())),
                            getInt(cache, "permanentFailureTtlHours",
                                    getInt(map, "permanentFailureTtlHours", legacyCacheTtlHours)),
                            getInt(map, "timeoutMillis", defaults.getTimeoutMillis()),
                            baseUrl,
                            password
                    );
                }

                public static StrictPrecheck defaultValues() {
                    return new StrictPrecheck(false, 24, 24, 10, 24, 5000, "", "");
                }

                public boolean isEnabled() {
                    return enabled;
                }

                public int getCacheTtlHours() {
                    return cacheTtlHours;
                }

                public int getPlayableTtlHours() { return playableTtlHours; }
                public int getTemporaryFailureTtlMinutes() { return temporaryFailureTtlMinutes; }
                public int getPermanentFailureTtlHours() { return permanentFailureTtlHours; }

                public int getTimeoutMillis() {
                    return timeoutMillis;
                }

                public String getLavalinkBaseUrl() {
                    return lavalinkBaseUrl;
                }

                public String getLavalinkPassword() {
                    return lavalinkPassword;
                }
            }
        }
    }

    public static class PrivateRoom {
        private final boolean enabled;
        private final Long triggerVoiceChannelId;
        private final int userLimit;

        private PrivateRoom(boolean enabled, Long triggerVoiceChannelId, int userLimit) {
            this.enabled = enabled;
            this.triggerVoiceChannelId = triggerVoiceChannelId;
            this.userLimit = userLimit;
        }

        public static PrivateRoom fromMap(Map<String, Object> map, PrivateRoom fallback) {
            PrivateRoom defaults = fallback == null ? defaultValues() : fallback;
            return new PrivateRoom(
                    getBoolean(map, "enabled", defaults.isEnabled()),
                    getLong(map, "triggerVoiceChannelId", defaults.getTriggerVoiceChannelId()),
                    Math.max(0, getInt(map, "userLimit", defaults.getUserLimit()))
            );
        }

        public static PrivateRoom defaultValues() {
            return new PrivateRoom(true, null, 0);
        }

        public PrivateRoom withEnabled(boolean enabled) {
            return new PrivateRoom(enabled, triggerVoiceChannelId, userLimit);
        }

        public PrivateRoom withTriggerVoiceChannelId(Long triggerVoiceChannelId) {
            return new PrivateRoom(enabled, triggerVoiceChannelId, userLimit);
        }

        public PrivateRoom withUserLimit(int userLimit) {
            return new PrivateRoom(enabled, triggerVoiceChannelId, Math.max(0, userLimit));
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Long getTriggerVoiceChannelId() {
            return triggerVoiceChannelId;
        }

        public int getUserLimit() {
            return userLimit;
        }
    }

    public static class BotProfile {
        private final String description;
        private final String presenceStatus;
        private final int activityRotationSeconds;
        private final List<String> activities;

        private BotProfile(String description,
                           String presenceStatus,
                           int activityRotationSeconds,
                           List<String> activities) {
            this.description = description;
            this.presenceStatus = presenceStatus;
            this.activityRotationSeconds = Math.max(5, activityRotationSeconds);
            this.activities = activities == null ? List.of() : List.copyOf(activities);
        }

        public static BotProfile fromMap(Map<String, Object> map, BotProfile fallback) {
            BotProfile defaults = fallback == null ? defaultValues() : fallback;
            return new BotProfile(
                    getString(map, "description", defaults.getDescription()),
                    getString(map, "presenceStatus", defaults.getPresenceStatus()),
                    Math.max(5, getInt(map, "rotationSeconds",
                            getInt(map, "activityRotationSeconds", defaults.getActivityRotationSeconds()))),
                    getStringList(map, "activities", defaults.getActivities())
            );
        }

        public static BotProfile defaultValues() {
            return new BotProfile("NoRule Bot", "ONLINE", 20, List.of("PLAYING|/help"));
        }

        public String getDescription() {
            return description;
        }

        public String getPresenceStatus() {
            return presenceStatus;
        }

        public int getActivityRotationSeconds() {
            return activityRotationSeconds;
        }

        public List<String> getActivities() {
            return activities;
        }
    }

    public static class Developers {
        private final List<Long> ids;
        private final Long developerChannelId;

        private Developers(List<Long> ids, Long developerChannelId) {
            this.ids = ids == null ? List.of() : List.copyOf(ids);
            this.developerChannelId = developerChannelId;
        }

        public static Developers fromMap(Map<String, Object> map, Developers fallback) {
            Developers defaults = fallback == null ? defaultValues() : fallback;
            return new Developers(
                    getLongList(map, "ids", defaults.getIds()),
                    getLong(map, "developerChannelId", defaults.getDeveloperChannelId())
            );
        }

        public static Developers defaultValues() {
            return new Developers(List.of(), null);
        }

        public List<Long> getIds() {
            return ids;
        }

        // Destination channel for /report submissions (bug reports and feedback).
        public Long getDeveloperChannelId() {
            return developerChannelId;
        }

        public boolean isDeveloper(long userId) {
            return ids.contains(userId);
        }
    }

    public static class Ticket {
        public enum OpenUiMode {
            SELECT,
            BUTTONS;

            public static OpenUiMode parse(String raw, OpenUiMode fallback) {
                if (raw == null || raw.isBlank()) {
                    return fallback == null ? BUTTONS : fallback;
                }
                try {
                    return OpenUiMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (Exception ignored) {
                    return fallback == null ? BUTTONS : fallback;
                }
            }
        }

        public static class TicketOption {
            private final String id;
            private final String label;
            private final String panelTitle;
            private final String panelDescription;
            private final String panelButtonStyle;
            private final String welcomeMessage;
            private final boolean preOpenFormEnabled;
            private final String preOpenFormTitle;
            private final String preOpenFormLabel;
            private final String preOpenFormPlaceholder;

            public TicketOption(String id,
                                String label,
                                String panelTitle,
                                String panelDescription,
                                String panelButtonStyle,
                                String welcomeMessage,
                                boolean preOpenFormEnabled,
                                String preOpenFormTitle,
                                String preOpenFormLabel,
                                String preOpenFormPlaceholder) {
                String normalizedId = sanitizeOptionId(id);
                String normalizedLabel = trimMax(label, 80);
                this.id = normalizedId.isBlank() ? "general" : normalizedId;
                this.label = normalizedLabel.isBlank() ? "General" : normalizedLabel;
                this.panelTitle = trimMax(panelTitle, 80);
                this.panelDescription = trimMax(panelDescription, 2000);
                this.panelButtonStyle = normalizeButtonStyle(panelButtonStyle);
                this.welcomeMessage = welcomeMessage == null ? "" : welcomeMessage.trim();
                this.preOpenFormEnabled = preOpenFormEnabled;
                this.preOpenFormTitle = trimMax(preOpenFormTitle, 45);
                this.preOpenFormLabel = trimMax(preOpenFormLabel, 45);
                this.preOpenFormPlaceholder = trimMax(preOpenFormPlaceholder, 100);
            }

            public static TicketOption fromMap(Map<String, Object> map, TicketOption fallback) {
                TicketOption defaults = fallback == null ? defaultValues() : fallback;
                return new TicketOption(
                        getString(map, "id", defaults.getId()),
                        getString(map, "label", defaults.getLabel()),
                        getString(map, "panelTitle", defaults.getPanelTitle()),
                        getString(map, "panelDescription", defaults.getPanelDescription()),
                        getString(map, "panelButtonStyle", defaults.getPanelButtonStyle()),
                        getString(map, "welcomeMessage", defaults.getWelcomeMessage()),
                        getBoolean(map, "preOpenFormEnabled", defaults.isPreOpenFormEnabled()),
                        getString(map, "preOpenFormTitle", defaults.getPreOpenFormTitle()),
                        getString(map, "preOpenFormLabel", defaults.getPreOpenFormLabel()),
                        getString(map, "preOpenFormPlaceholder", defaults.getPreOpenFormPlaceholder())
                );
            }

            public static TicketOption defaultValues() {
                return new TicketOption(
                        "general",
                        "General",
                        "",
                        "",
                        "PRIMARY",
                        "",
                        false,
                        "",
                        "",
                        ""
                );
            }

            public String getId() {
                return id;
            }

            public String getLabel() {
                return label;
            }

            public String getPanelTitle() {
                return panelTitle;
            }

            public String getPanelDescription() {
                return panelDescription;
            }

            public String getPanelButtonStyle() {
                return panelButtonStyle;
            }

            public String getWelcomeMessage() {
                return welcomeMessage;
            }

            public boolean isPreOpenFormEnabled() {
                return preOpenFormEnabled;
            }

            public String getPreOpenFormTitle() {
                return preOpenFormTitle;
            }

            public String getPreOpenFormLabel() {
                return preOpenFormLabel;
            }

            public String getPreOpenFormPlaceholder() {
                return preOpenFormPlaceholder;
            }
        }

        private final boolean enabled;
        private final Long panelChannelId;
        private final Long openCategoryId;
        private final Long closedCategoryId;
        private final int autoCloseDays;
        private final int maxOpenPerUser;
        private final OpenUiMode openUiMode;
        private final String panelTitle;
        private final String panelDescription;
        private final int panelColor;
        private final String panelButtonStyle;
        private final int panelButtonLimit;
        private final String welcomeMessage;
        private final boolean preOpenFormEnabled;
        private final String preOpenFormTitle;
        private final String preOpenFormLabel;
        private final String preOpenFormPlaceholder;
        private final List<String> optionLabels;
        private final List<TicketOption> options;
        private final List<Long> supportRoleIds;
        private final List<Long> blacklistedUserIds;

        private Ticket(boolean enabled,
                       Long panelChannelId,
                       Long openCategoryId,
                       Long closedCategoryId,
                       int autoCloseDays,
                       int maxOpenPerUser,
                       OpenUiMode openUiMode,
                       String panelTitle,
                       String panelDescription,
                       int panelColor,
                       String panelButtonStyle,
                       int panelButtonLimit,
                       String welcomeMessage,
                       boolean preOpenFormEnabled,
                       String preOpenFormTitle,
                       String preOpenFormLabel,
                       String preOpenFormPlaceholder,
                       List<String> optionLabels,
                       List<TicketOption> options,
                       List<Long> supportRoleIds,
                       List<Long> blacklistedUserIds) {
            this.enabled = enabled;
            this.panelChannelId = panelChannelId;
            this.openCategoryId = openCategoryId;
            this.closedCategoryId = closedCategoryId;
            this.autoCloseDays = Math.max(1, autoCloseDays);
            this.maxOpenPerUser = Math.max(1, Math.min(20, maxOpenPerUser));
            this.openUiMode = openUiMode == null ? OpenUiMode.BUTTONS : openUiMode;
            this.panelTitle = trimMax(panelTitle, 80);
            this.panelDescription = trimMax(panelDescription, 2000);
            this.panelColor = panelColor & 0xFFFFFF;
            this.panelButtonStyle = normalizeButtonStyle(panelButtonStyle);
            this.panelButtonLimit = Math.max(1, Math.min(25, panelButtonLimit));
            this.welcomeMessage = welcomeMessage == null ? "" : welcomeMessage.trim();
            this.preOpenFormTitle = trimMax(preOpenFormTitle, 45);
            this.preOpenFormLabel = trimMax(preOpenFormLabel, 45);
            this.preOpenFormPlaceholder = trimMax(preOpenFormPlaceholder, 100);
            List<String> labels = optionLabels == null ? List.of() : optionLabels.stream()
                    .map(v -> v == null ? "" : v.trim())
                    .filter(v -> !v.isBlank())
                    .toList();
            this.optionLabels = labels.isEmpty() ? List.of("General") : labels;
            List<TicketOption> parsedOptions = normalizeOptions(options);
            if (parsedOptions.isEmpty()) {
                List<TicketOption> migrated = new ArrayList<>();
                int index = 0;
                for (String optionLabel : this.optionLabels) {
                    migrated.add(new TicketOption(
                            "option-" + index,
                            optionLabel,
                            this.panelTitle,
                            this.panelDescription,
                            this.panelButtonStyle,
                            this.welcomeMessage,
                            preOpenFormEnabled,
                            this.preOpenFormTitle,
                            this.preOpenFormLabel,
                            this.preOpenFormPlaceholder
                    ));
                    index++;
                }
                parsedOptions = migrated;
            }
            this.options = parsedOptions;
            this.supportRoleIds = supportRoleIds == null ? List.of() : supportRoleIds.stream()
                    .filter(v -> v != null && v > 0L)
                    .distinct()
                    .toList();
            this.blacklistedUserIds = blacklistedUserIds == null ? List.of() : blacklistedUserIds.stream()
                    .filter(v -> v != null && v > 0L)
                    .distinct()
                    .toList();
            this.preOpenFormEnabled = preOpenFormEnabled;
        }

        public static Ticket fromMap(Map<String, Object> map, Ticket fallback) {
            Ticket defaults = fallback == null ? defaultValues() : fallback;
            return new Ticket(
                    getBoolean(map, "enabled", defaults.isEnabled()),
                    getLong(map, "panelChannelId", defaults.getPanelChannelId()),
                    getLong(map, "openCategoryId", defaults.getOpenCategoryId()),
                    getLong(map, "closedCategoryId", defaults.getClosedCategoryId()),
                    getInt(map, "autoCloseDays", defaults.getAutoCloseDays()),
                    getInt(map, "maxOpenPerUser", defaults.getMaxOpenPerUser()),
                    OpenUiMode.parse(getString(map, "openUiMode", defaults.getOpenUiMode().name()), defaults.getOpenUiMode()),
                    getString(map, "panelTitle", defaults.getPanelTitle()),
                    getString(map, "panelDescription", defaults.getPanelDescription()),
                    getInt(map, "panelColor", defaults.getPanelColor()),
                    getString(map, "panelButtonStyle", defaults.getPanelButtonStyle()),
                    getInt(map, "panelButtonLimit", defaults.getPanelButtonLimit()),
                    getString(map, "welcomeMessage", defaults.getWelcomeMessage()),
                    getBoolean(map, "preOpenFormEnabled", defaults.isPreOpenFormEnabled()),
                    getString(map, "preOpenFormTitle", defaults.getPreOpenFormTitle()),
                    getString(map, "preOpenFormLabel", defaults.getPreOpenFormLabel()),
                    getString(map, "preOpenFormPlaceholder", defaults.getPreOpenFormPlaceholder()),
                    getStringList(map, "optionLabels", defaults.getOptionLabels()),
                    getTicketOptionList(map, defaults),
                    getLongList(map, "supportRoleIds", defaults.getSupportRoleIds()),
                    getLongList(map, "blacklistedUserIds", defaults.getBlacklistedUserIds())
            );
        }

        public static Ticket defaultValues() {
            return new Ticket(
                    false,
                    null,
                    null,
                    null,
                    3,
                    1,
                    OpenUiMode.BUTTONS,
                    "",
                    "",
                    0x5865F2,
                    "PRIMARY",
                    3,
                    "",
                    false,
                    "",
                    "",
                    "",
                    List.of("General"),
                    List.of(TicketOption.defaultValues()),
                    List.of(),
                    List.of()
            );
        }

        public Ticket withEnabled(boolean enabled) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withPanelChannelId(Long panelChannelId) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withOpenCategoryId(Long openCategoryId) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withClosedCategoryId(Long closedCategoryId) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withAutoCloseDays(int autoCloseDays) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withMaxOpenPerUser(int maxOpenPerUser) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withOpenUiMode(OpenUiMode openUiMode) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withPanelTitle(String panelTitle) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withPanelDescription(String panelDescription) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withPanelColor(int panelColor) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withPanelButtonStyle(String panelButtonStyle) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withPanelButtonLimit(int panelButtonLimit) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withWelcomeMessage(String welcomeMessage) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withPreOpenFormEnabled(boolean preOpenFormEnabled) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withPreOpenFormTitle(String preOpenFormTitle) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withPreOpenFormLabel(String preOpenFormLabel) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withPreOpenFormPlaceholder(String preOpenFormPlaceholder) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withOptionLabels(List<String> optionLabels) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withOptions(List<TicketOption> options) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withSupportRoleIds(List<Long> supportRoleIds) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public Ticket withBlacklistedUserIds(List<Long> blacklistedUserIds) {
            return new Ticket(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode, panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage, preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options, supportRoleIds, blacklistedUserIds);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Long getPanelChannelId() {
            return panelChannelId;
        }

        public Long getOpenCategoryId() {
            return openCategoryId;
        }

        public Long getClosedCategoryId() {
            return closedCategoryId;
        }

        public int getAutoCloseDays() {
            return autoCloseDays;
        }

        public int getMaxOpenPerUser() {
            return maxOpenPerUser;
        }

        public OpenUiMode getOpenUiMode() {
            return openUiMode;
        }

        public String getPanelTitle() {
            return panelTitle;
        }

        public String getPanelDescription() {
            return panelDescription;
        }

        public int getPanelColor() {
            return panelColor;
        }

        public String getPanelButtonStyle() {
            return panelButtonStyle;
        }

        public int getPanelButtonLimit() {
            return panelButtonLimit;
        }

        public String getWelcomeMessage() {
            return welcomeMessage;
        }

        public boolean isPreOpenFormEnabled() {
            return preOpenFormEnabled;
        }

        public String getPreOpenFormTitle() {
            return preOpenFormTitle;
        }

        public String getPreOpenFormLabel() {
            return preOpenFormLabel;
        }

        public String getPreOpenFormPlaceholder() {
            return preOpenFormPlaceholder;
        }

        public List<String> getOptionLabels() {
            return optionLabels;
        }

        public List<TicketOption> getOptions() {
            return options;
        }

        public List<Long> getSupportRoleIds() {
            return supportRoleIds;
        }

        public List<Long> getBlacklistedUserIds() {
            return blacklistedUserIds;
        }

        private static String trimMax(String value, int max) {
            if (value == null) {
                return "";
            }
            String trimmed = value.trim();
            if (trimmed.length() <= max) {
                return trimmed;
            }
            return trimmed.substring(0, max);
        }

        private static String normalizeButtonStyle(String style) {
            if (style == null) {
                return "PRIMARY";
            }
            String normalized = style.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "SECONDARY", "SUCCESS", "DANGER" -> normalized;
                default -> "PRIMARY";
            };
        }

        private static List<TicketOption> normalizeOptions(List<TicketOption> source) {
            if (source == null || source.isEmpty()) {
                return List.of();
            }
            List<TicketOption> normalized = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            for (TicketOption option : source) {
                if (option == null) {
                    continue;
                }
                String id = sanitizeOptionId(option.getId());
                if (id.isBlank()) {
                    id = "option-" + normalized.size();
                }
                if (!ids.add(id)) {
                    continue;
                }
                normalized.add(new TicketOption(
                        id,
                        option.getLabel(),
                        option.getPanelTitle(),
                        option.getPanelDescription(),
                        option.getPanelButtonStyle(),
                        option.getWelcomeMessage(),
                        option.isPreOpenFormEnabled(),
                        option.getPreOpenFormTitle(),
                        option.getPreOpenFormLabel(),
                        option.getPreOpenFormPlaceholder()
                ));
            }
            return normalized;
        }

        private static String sanitizeOptionId(String value) {
            if (value == null) {
                return "";
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
            normalized = normalized.replaceAll("-{2,}", "-");
            if (normalized.startsWith("-")) {
                normalized = normalized.substring(1);
            }
            if (normalized.endsWith("-")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }

        private static List<TicketOption> getTicketOptionList(Map<String, Object> map, Ticket defaults) {
            if (!map.containsKey("options")) {
                return defaults == null ? List.of() : defaults.getOptions();
            }
            Object value = map.get("options");
            if (!(value instanceof Iterable<?> iterable)) {
                return List.of();
            }
            List<TicketOption> result = new ArrayList<>();
            int index = 0;
            for (Object item : iterable) {
                Map<String, Object> optionMap = asMap(item);
                if (optionMap.isEmpty()) {
                    continue;
                }
                TicketOption fallback = defaults != null && defaults.getOptions().size() > index
                        ? defaults.getOptions().get(index)
                        : TicketOption.defaultValues();
                result.add(TicketOption.fromMap(optionMap, fallback));
                index++;
            }
            return result;
        }
    }

    public static class MinecraftStatus {
        private final String userAgent;
        private final int requestTimeoutMillis;
        private final int internalCacheSeconds;

        private MinecraftStatus(String userAgent, int requestTimeoutMillis, int internalCacheSeconds) {
            String normalizedUserAgent = userAgent == null ? "" : userAgent.trim();
            this.userAgent = normalizedUserAgent.isBlank()
                    ? "NoRuleBot/1.0 contact: admin@norule.me"
                    : normalizedUserAgent;
            this.requestTimeoutMillis = Math.max(1_000, requestTimeoutMillis);
            this.internalCacheSeconds = Math.max(0, internalCacheSeconds);
        }

        public static MinecraftStatus fromMap(Map<String, Object> map, MinecraftStatus fallback) {
            MinecraftStatus defaults = fallback == null ? defaultValues() : fallback;
            return new MinecraftStatus(
                    getString(map, "userAgent", defaults.getUserAgent()),
                    getInt(map, "requestTimeoutMillis", defaults.getRequestTimeoutMillis()),
                    getInt(map, "internalCacheSeconds", defaults.getInternalCacheSeconds())
            );
        }

        public static MinecraftStatus defaultValues() {
            return new MinecraftStatus("NoRuleBot/1.0 contact: admin@norule.me", 15_000, 60);
        }

        public String getUserAgent() {
            return userAgent;
        }

        public int getRequestTimeoutMillis() {
            return requestTimeoutMillis;
        }

        public int getInternalCacheSeconds() {
            return internalCacheSeconds;
        }
    }

    public static class ShortUrl {
        public static final class CreationAbuseProtection {
            public static final class Limits {
                private final int maxRequestsPerMinute;
                private final int maxRequestsPer10Minutes;
                private final int maxCreatesPerDay;

                private Limits(int maxRequestsPerMinute,
                               int maxRequestsPer10Minutes,
                               int maxCreatesPerDay) {
                    this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute);
                    this.maxRequestsPer10Minutes = Math.max(
                            this.maxRequestsPerMinute, maxRequestsPer10Minutes);
                    this.maxCreatesPerDay = Math.max(1, maxCreatesPerDay);
                }

                private static Limits fromMap(Map<String, Object> map, Limits fallback) {
                    Limits defaults = fallback == null ? anonymousDefaults() : fallback;
                    return new Limits(
                            getInt(map, "maxRequestsPerMinute", defaults.getMaxRequestsPerMinute()),
                            getInt(map, "maxRequestsPer10Minutes", defaults.getMaxRequestsPer10Minutes()),
                            getInt(map, "maxCreatesPerDay", defaults.getMaxCreatesPerDay())
                    );
                }

                private static Limits anonymousDefaults() {
                    return new Limits(10, 50, 200);
                }

                private static Limits authenticatedDefaults() {
                    return new Limits(30, 150, 500);
                }

                public int getMaxRequestsPerMinute() { return maxRequestsPerMinute; }
                public int getMaxRequestsPer10Minutes() { return maxRequestsPer10Minutes; }
                public int getMaxCreatesPerDay() { return maxCreatesPerDay; }
            }

            private final boolean enabled;
            private final Limits anonymous;
            private final Limits authenticated;

            private CreationAbuseProtection(boolean enabled, Limits anonymous, Limits authenticated) {
                this.enabled = enabled;
                this.anonymous = anonymous == null ? Limits.anonymousDefaults() : anonymous;
                this.authenticated = authenticated == null ? Limits.authenticatedDefaults() : authenticated;
            }

            private static CreationAbuseProtection fromMap(Map<String, Object> map,
                                                           CreationAbuseProtection fallback) {
                CreationAbuseProtection defaults = fallback == null ? defaultValues() : fallback;
                return new CreationAbuseProtection(
                        getBoolean(map, "enabled", defaults.isEnabled()),
                        Limits.fromMap(asMap(map.get("anonymous")), defaults.getAnonymous()),
                        Limits.fromMap(asMap(map.get("authenticated")), defaults.getAuthenticated())
                );
            }

            private static CreationAbuseProtection defaultValues() {
                return new CreationAbuseProtection(
                        true, Limits.anonymousDefaults(), Limits.authenticatedDefaults());
            }

            public boolean isEnabled() { return enabled; }
            public Limits getAnonymous() { return anonymous; }
            public Limits getAuthenticated() { return authenticated; }
        }

        public static final class Public {
            private final String baseUrl;

            private Public(String baseUrl) {
                String normalized = (baseUrl == null || baseUrl.isBlank()) ? "https://s.norule.me" : baseUrl.trim();
                this.baseUrl = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
            }

            private static Public fromMap(Map<String, Object> map, Public fallback) {
                Public defaults = fallback == null ? defaultValues() : fallback;
                return new Public(getString(map, "baseUrl", defaults.getBaseUrl()));
            }

            private static Public defaultValues() {
                return new Public("https://s.norule.me");
            }

            public String getBaseUrl() {
                return baseUrl;
            }
        }

        public static final class Image {
            private static final int MAX_RETENTION_DAYS = 365;
            private static final int MAX_FILE_SIZE_MB = 20;
            private static final int MAX_VIDEO_FILE_SIZE_MB = 100;
            private static final int MAX_VIDEO_DURATION_SECONDS = 5 * 60;
            private static final int DEFAULT_EXPIRED_SHARE_RETENTION_DAYS = 30;

            public static final class IdentityContinuity {
                private final boolean enabled;
                private final int anonymousToAccountMergeWindowMinutes;
                private final int deviceLinkTtlDays;
                private final int deviceAccountSwitchCooldownHours;

                private IdentityContinuity(boolean enabled, int mergeWindowMinutes,
                                           int deviceLinkTtlDays, int switchCooldownHours) {
                    this.enabled = enabled;
                    this.anonymousToAccountMergeWindowMinutes = Math.max(1, mergeWindowMinutes);
                    this.deviceLinkTtlDays = Math.max(1, deviceLinkTtlDays);
                    this.deviceAccountSwitchCooldownHours = Math.max(0, switchCooldownHours);
                }

                private static IdentityContinuity fromMap(Map<String, Object> map,
                                                          IdentityContinuity fallback) {
                    IdentityContinuity defaults = fallback == null ? defaultValues() : fallback;
                    return new IdentityContinuity(
                            getBoolean(map, "enabled", defaults.isEnabled()),
                            getInt(map, "anonymousToAccountMergeWindowMinutes",
                                    defaults.getAnonymousToAccountMergeWindowMinutes()),
                            getInt(map, "deviceLinkTtlDays", defaults.getDeviceLinkTtlDays()),
                            getInt(map, "deviceAccountSwitchCooldownHours",
                                    defaults.getDeviceAccountSwitchCooldownHours()));
                }

                private static IdentityContinuity defaultValues() {
                    return new IdentityContinuity(true, 120, 30, 24);
                }

                public boolean isEnabled() { return enabled; }
                public int getAnonymousToAccountMergeWindowMinutes() {
                    return anonymousToAccountMergeWindowMinutes;
                }
                public int getDeviceLinkTtlDays() { return deviceLinkTtlDays; }
                public int getDeviceAccountSwitchCooldownHours() {
                    return deviceAccountSwitchCooldownHours;
                }
            }

            public static final class PasswordProtection {
                public static final class PerIp {
                    private final int maxVerificationRequestsPerMinute;
                    private final int maxVerificationRequestsPer10Minutes;

                    private PerIp(int perMinute, int perTenMinutes) {
                        this.maxVerificationRequestsPerMinute = Math.max(1, perMinute);
                        this.maxVerificationRequestsPer10Minutes = Math.max(
                                this.maxVerificationRequestsPerMinute, perTenMinutes);
                    }

                    private static PerIp fromMap(Map<String, Object> map, PerIp fallback) {
                        PerIp defaults = fallback == null ? defaultValues() : fallback;
                        return new PerIp(
                                getInt(map, "maxVerificationRequestsPerMinute",
                                        defaults.getMaxVerificationRequestsPerMinute()),
                                getInt(map, "maxVerificationRequestsPer10Minutes",
                                        defaults.getMaxVerificationRequestsPer10Minutes()));
                    }

                    private static PerIp defaultValues() { return new PerIp(20, 100); }
                    public int getMaxVerificationRequestsPerMinute() {
                        return maxVerificationRequestsPerMinute;
                    }
                    public int getMaxVerificationRequestsPer10Minutes() {
                        return maxVerificationRequestsPer10Minutes;
                    }
                }

                private final boolean enabled;
                private final boolean allowDateDefaultPassword;
                private final int minPasswordLength;
                private final int maxPasswordLength;
                private final int maxFailedAttempts;
                private final int failureWindowMinutes;
                private final int lockMinutes;
                private final int backoffInitialSeconds;
                private final int backoffMultiplier;
                private final int backoffMaxSeconds;
                private final int maxConcurrentVerifications;
                private final PerIp perIp;

                private PasswordProtection(boolean enabled, boolean allowDateDefaultPassword,
                                           int minPasswordLength, int maxPasswordLength,
                                           int maxFailedAttempts, int failureWindowMinutes,
                                           int lockMinutes, int backoffInitialSeconds,
                                           int backoffMultiplier, int backoffMaxSeconds,
                                           int maxConcurrentVerifications, PerIp perIp) {
                    this.enabled = enabled;
                    this.allowDateDefaultPassword = allowDateDefaultPassword;
                    this.minPasswordLength = Math.max(1, Math.min(128, minPasswordLength));
                    this.maxPasswordLength = Math.max(this.minPasswordLength,
                            Math.min(128, maxPasswordLength));
                    this.maxFailedAttempts = Math.max(1, maxFailedAttempts);
                    this.failureWindowMinutes = Math.max(1, failureWindowMinutes);
                    this.lockMinutes = Math.max(1, lockMinutes);
                    this.backoffInitialSeconds = Math.max(0, backoffInitialSeconds);
                    this.backoffMultiplier = Math.max(1, backoffMultiplier);
                    this.backoffMaxSeconds = Math.max(this.backoffInitialSeconds, backoffMaxSeconds);
                    this.maxConcurrentVerifications = Math.max(1, maxConcurrentVerifications);
                    this.perIp = perIp == null ? PerIp.defaultValues() : perIp;
                }

                private static PasswordProtection fromMap(Map<String, Object> map,
                                                          PasswordProtection fallback) {
                    PasswordProtection defaults = fallback == null ? defaultValues() : fallback;
                    return new PasswordProtection(
                            getBoolean(map, "enabled", defaults.isEnabled()),
                            getBoolean(map, "allowDateDefaultPassword",
                                    defaults.isAllowDateDefaultPassword()),
                            getInt(map, "minPasswordLength", defaults.getMinPasswordLength()),
                            getInt(map, "maxPasswordLength", defaults.getMaxPasswordLength()),
                            getInt(map, "maxFailedAttempts", defaults.getMaxFailedAttempts()),
                            getInt(map, "failureWindowMinutes", defaults.getFailureWindowMinutes()),
                            getInt(map, "lockMinutes", defaults.getLockMinutes()),
                            getInt(map, "backoffInitialSeconds", defaults.getBackoffInitialSeconds()),
                            getInt(map, "backoffMultiplier", defaults.getBackoffMultiplier()),
                            getInt(map, "backoffMaxSeconds", defaults.getBackoffMaxSeconds()),
                            getInt(map, "maxConcurrentVerifications",
                                    defaults.getMaxConcurrentVerifications()),
                            PerIp.fromMap(asMap(map.get("perIp")), defaults.getPerIp()));
                }

                private static PasswordProtection defaultValues() {
                    return new PasswordProtection(true, true, 4, 128, 5, 10,
                            10, 1, 2, 30, 8, PerIp.defaultValues());
                }

                public boolean isEnabled() { return enabled; }
                public boolean isAllowDateDefaultPassword() { return allowDateDefaultPassword; }
                public int getMinPasswordLength() { return minPasswordLength; }
                public int getMaxPasswordLength() { return maxPasswordLength; }
                public int getMaxFailedAttempts() { return maxFailedAttempts; }
                public int getFailureWindowMinutes() { return failureWindowMinutes; }
                public int getLockMinutes() { return lockMinutes; }
                public int getBackoffInitialSeconds() { return backoffInitialSeconds; }
                public int getBackoffMultiplier() { return backoffMultiplier; }
                public int getBackoffMaxSeconds() { return backoffMaxSeconds; }
                public int getMaxConcurrentVerifications() { return maxConcurrentVerifications; }
                public PerIp getPerIp() { return perIp; }
            }

            public static final class Storage {
                private final String activePath;
                private final String tempPath;
                private final String expiredArchivePath;
                private final int maxTotalStorageGb;
                private final int warningPercent;
                private final int filesystemStopPercent;

                private Storage(String activePath, String tempPath, String expiredArchivePath,
                                int maxTotalStorageGb, int warningPercent, int filesystemStopPercent) {
                    this.activePath = pathOrDefault(activePath, "data/short-url-images");
                    this.tempPath = pathOrDefault(tempPath, "data/tmp/uploads");
                    this.expiredArchivePath = pathOrDefault(expiredArchivePath,
                            "data/short-url-expired");
                    this.maxTotalStorageGb = Math.max(1, maxTotalStorageGb);
                    this.warningPercent = Math.max(1, Math.min(99, warningPercent));
                    this.filesystemStopPercent = Math.max(this.warningPercent,
                            Math.min(100, filesystemStopPercent));
                }

                private static Storage fromMap(Map<String, Object> map, Storage fallback,
                                               String legacyActivePath) {
                    Storage defaults = fallback == null ? defaultValues() : fallback;
                    String configuredActivePath = getString(map, "activePath", defaults.getActivePath());
                    if (legacyActivePath != null && !legacyActivePath.isBlank()
                            && !"data/short-url-images".equals(legacyActivePath)
                            && defaults.getActivePath().equals(configuredActivePath)) {
                        configuredActivePath = legacyActivePath;
                    }
                    return new Storage(
                            configuredActivePath,
                            getString(map, "tempPath", defaults.getTempPath()),
                            getString(map, "expiredArchivePath", defaults.getExpiredArchivePath()),
                            getInt(map, "maxTotalStorageGb", defaults.getMaxTotalStorageGb()),
                            getInt(map, "warningPercent", defaults.getWarningPercent()),
                            getInt(map, "filesystemStopPercent", defaults.getFilesystemStopPercent()));
                }

                private static Storage defaultValues() {
                    return new Storage("data/short-url-images", "data/tmp/uploads",
                            "data/short-url-expired", 50, 70, 80);
                }

                private static String pathOrDefault(String value, String fallback) {
                    return value == null || value.isBlank() ? fallback : value.trim();
                }

                public String getActivePath() { return activePath; }
                public String getTempPath() { return tempPath; }
                public String getExpiredArchivePath() { return expiredArchivePath; }
                public int getMaxTotalStorageGb() { return maxTotalStorageGb; }
                public int getWarningPercent() { return warningPercent; }
                public int getFilesystemStopPercent() { return filesystemStopPercent; }
            }

            public static final class Secrets {
                private final String quotaHmacSecret;
                private final String deviceHmacSecret;

                private Secrets(String quotaHmacSecret, String deviceHmacSecret) {
                    this.quotaHmacSecret = normalizeSecret(quotaHmacSecret);
                    this.deviceHmacSecret = normalizeSecret(deviceHmacSecret);
                }

                private static Secrets fromMap(Map<String, Object> map, Secrets fallback) {
                    Secrets defaults = fallback == null ? defaultValues() : fallback;
                    return new Secrets(
                            getString(map, "quotaHmacSecret", defaults.getQuotaHmacSecret()),
                            getString(map, "deviceHmacSecret", defaults.getDeviceHmacSecret()));
                }

                private static Secrets defaultValues() {
                    return new Secrets("", "");
                }

                private static String normalizeSecret(String value) {
                    return value == null ? "" : value.trim();
                }

                public String getQuotaHmacSecret() { return quotaHmacSecret; }
                public String getDeviceHmacSecret() { return deviceHmacSecret; }
            }

            public static final class AbuseProtection {
                private final IdentityContinuity identityContinuity;
                private final PasswordProtection passwordProtection;
                private final Storage storage;
                private final Secrets secrets;

                private AbuseProtection(IdentityContinuity identityContinuity,
                                        PasswordProtection passwordProtection, Storage storage,
                                        Secrets secrets) {
                    this.identityContinuity = identityContinuity == null
                            ? IdentityContinuity.defaultValues() : identityContinuity;
                    this.passwordProtection = passwordProtection == null
                            ? PasswordProtection.defaultValues() : passwordProtection;
                    this.storage = storage == null ? Storage.defaultValues() : storage;
                    this.secrets = secrets == null ? Secrets.defaultValues() : secrets;
                }

                private static AbuseProtection fromMap(Map<String, Object> map,
                                                       AbuseProtection fallback,
                                                       String legacyActivePath) {
                    AbuseProtection defaults = fallback == null ? defaultValues() : fallback;
                    return new AbuseProtection(
                            IdentityContinuity.fromMap(asMap(map.get("identityContinuity")),
                                    defaults.getIdentityContinuity()),
                            PasswordProtection.fromMap(asMap(map.get("passwordProtection")),
                                    defaults.getPasswordProtection()),
                            Storage.fromMap(asMap(map.get("storage")), defaults.getStorage(),
                                    legacyActivePath),
                            Secrets.fromMap(asMap(map.get("secrets")), defaults.getSecrets()));
                }

                private static AbuseProtection defaultValues() {
                    return new AbuseProtection(IdentityContinuity.defaultValues(),
                            PasswordProtection.defaultValues(), Storage.defaultValues(),
                            Secrets.defaultValues());
                }

                public IdentityContinuity getIdentityContinuity() { return identityContinuity; }
                public PasswordProtection getPasswordProtection() { return passwordProtection; }
                public Storage getStorage() { return storage; }
                public Secrets getSecrets() { return secrets; }
            }

            private final boolean enabled;
            private final int defaultRetentionHours;
            private final int maxRetentionDays;
            private final int maxFileSizeMb;
            private final int maxVideoFileSizeMb;
            private final int maxVideoDurationSeconds;
            private final int expiredShareRetentionDays;
            private final String storagePath;
            private final AbuseProtection abuseProtection;

            private Image(boolean enabled,
                          int defaultRetentionHours,
                          int maxRetentionDays,
                          int maxFileSizeMb,
                          int maxVideoFileSizeMb,
                          int maxVideoDurationSeconds,
                          int expiredShareRetentionDays,
                          String storagePath,
                          AbuseProtection abuseProtection) {
                this.enabled = enabled;
                this.maxRetentionDays = Math.max(1, Math.min(MAX_RETENTION_DAYS, maxRetentionDays));
                this.defaultRetentionHours = Math.max(1, Math.min(this.maxRetentionDays * 24, defaultRetentionHours));
                this.maxFileSizeMb = Math.max(1, Math.min(MAX_FILE_SIZE_MB, maxFileSizeMb));
                this.maxVideoFileSizeMb = Math.max(1, Math.min(MAX_VIDEO_FILE_SIZE_MB, maxVideoFileSizeMb));
                this.maxVideoDurationSeconds = Math.max(1, Math.min(MAX_VIDEO_DURATION_SECONDS, maxVideoDurationSeconds));
                this.expiredShareRetentionDays = Math.max(1,
                        Math.min(MAX_RETENTION_DAYS, expiredShareRetentionDays));
                this.storagePath = storagePath == null || storagePath.isBlank()
                        ? "data/short-url-images"
                        : storagePath.trim();
                this.abuseProtection = abuseProtection == null
                        ? AbuseProtection.defaultValues() : abuseProtection;
            }

            private static Image fromMap(Map<String, Object> map, Image fallback) {
                Image defaults = fallback == null ? defaultValues() : fallback;
                String storagePath = getString(map, "storagePath", defaults.getStoragePath());
                return new Image(
                        getBoolean(map, "enabled", defaults.isEnabled()),
                        getInt(map, "defaultRetentionHours", defaults.getDefaultRetentionHours()),
                        getInt(map, "maxRetentionDays", defaults.getMaxRetentionDays()),
                        getInt(map, "maxFileSizeMb", defaults.getMaxFileSizeMb()),
                        getInt(map, "maxVideoFileSizeMb", defaults.getMaxVideoFileSizeMb()),
                        getInt(map, "maxVideoDurationSeconds", defaults.getMaxVideoDurationSeconds()),
                        getInt(map, "expiredShareRetentionDays", defaults.getExpiredShareRetentionDays()),
                        storagePath,
                        AbuseProtection.fromMap(asMap(map.get("abuseProtection")),
                                defaults.getAbuseProtection(), storagePath)
                );
            }

            private static Image defaultValues() {
                return new Image(true, 1, MAX_RETENTION_DAYS, MAX_FILE_SIZE_MB,
                        MAX_VIDEO_FILE_SIZE_MB, MAX_VIDEO_DURATION_SECONDS, DEFAULT_EXPIRED_SHARE_RETENTION_DAYS,
                        "data/short-url-images", AbuseProtection.defaultValues());
            }

            public boolean isEnabled() {
                return enabled;
            }

            public int getDefaultRetentionHours() {
                return defaultRetentionHours;
            }

            public int getMaxRetentionDays() {
                return maxRetentionDays;
            }

            public int getMaxFileSizeMb() {
                return maxFileSizeMb;
            }

            public int getMaxVideoFileSizeMb() {
                return maxVideoFileSizeMb;
            }

            public int getMaxVideoDurationSeconds() {
                return maxVideoDurationSeconds;
            }

            public int getExpiredShareRetentionDays() {
                return expiredShareRetentionDays;
            }

            public String getStoragePath() {
                return storagePath;
            }

            public AbuseProtection getAbuseProtection() {
                return abuseProtection;
            }
        }

        private final boolean enabled;
        private final int bindPort;
        private final Public publicConfig;
        private final int codeLength;
        private final boolean allowPrivateTargets;
        private final String storage;
        private final boolean dedupe;
        private final int ttlDays;
        private final int cleanupIntervalMinutes;
        private final CreationAbuseProtection creationAbuseProtection;
        private final Image image;
        private final Mysql mysql;
        private final Sqlite sqlite;

        private ShortUrl(boolean enabled,
                         int bindPort,
                         Public publicConfig,
                         int codeLength,
                         boolean allowPrivateTargets,
                         String storage,
                         boolean dedupe,
                         int ttlDays,
                         int cleanupIntervalMinutes,
                         CreationAbuseProtection creationAbuseProtection,
                         Image image,
                         Mysql mysql,
                         Sqlite sqlite) {
            this.enabled = enabled;
            this.bindPort = Math.max(1, bindPort);
            this.publicConfig = publicConfig == null ? Public.defaultValues() : publicConfig;
            this.codeLength = Math.max(4, Math.min(32, codeLength));
            this.allowPrivateTargets = allowPrivateTargets;
            this.storage = normalizeStorage(storage);
            this.dedupe = dedupe;
            this.ttlDays = Math.max(1, ttlDays);
            this.cleanupIntervalMinutes = Math.max(1, cleanupIntervalMinutes);
            this.creationAbuseProtection = creationAbuseProtection == null
                    ? CreationAbuseProtection.defaultValues() : creationAbuseProtection;
            this.image = image == null ? Image.defaultValues() : image;
            this.mysql = mysql == null ? Mysql.defaultValues() : mysql;
            this.sqlite = sqlite == null ? Sqlite.defaultValues() : sqlite;
        }

        public static ShortUrl fromMap(Map<String, Object> map, ShortUrl fallback) {
            ShortUrl defaults = fallback == null ? defaultValues() : fallback;
            Map<String, Object> publicMap = asMap(map.get("public"));

            Map<String, Object> effectivePublicMap = new LinkedHashMap<>(publicMap);
            String publicBaseUrl = getString(map, "publicBaseUrl", "");
            if (!publicBaseUrl.isBlank()) {
                effectivePublicMap.put("baseUrl", publicBaseUrl);
            }
            publicBaseUrl = getString(effectivePublicMap, "baseUrl", "");
            if (publicBaseUrl.isBlank()) {
                publicBaseUrl = getString(map, "baseUrl", "");
            }
            if (publicBaseUrl.isBlank()) {
                String legacyDomain = getString(map, "domain", "");
                if (!legacyDomain.isBlank()) {
                    publicBaseUrl = "https://" + legacyDomain.trim().toLowerCase(Locale.ROOT);
                }
            }
            if (publicBaseUrl.isBlank()) {
                publicBaseUrl = defaults.getPublicBaseUrl();
            }
            effectivePublicMap.put("baseUrl", publicBaseUrl);
            Public publicConfig = Public.fromMap(effectivePublicMap, defaults.getPublic());

            return new ShortUrl(
                    getBoolean(map, "enabled", defaults.isEnabled()),
                    getInt(map, "bindPort", getInt(asMap(map.get("bind")), "port", defaults.getBindPort())),
                    publicConfig,
                    getInt(map, "codeLength", defaults.getCodeLength()),
                    getBoolean(map, "allowPrivateTargets", defaults.isAllowPrivateTargets()),
                    getString(map, "storage", defaults.getStorage()),
                    getBoolean(map, "dedupe", defaults.isDedupe()),
                    getInt(map, "ttlDays", defaults.getTtlDays()),
                    getInt(map, "cleanupIntervalMinutes", defaults.getCleanupIntervalMinutes()),
                    CreationAbuseProtection.fromMap(
                            asMap(asMap(map.get("abuseProtection")).get("creation")),
                            defaults.getCreationAbuseProtection()),
                    Image.fromMap(asMap(map.get("image")), defaults.getImage()),
                    Mysql.fromMap(asMap(map.get("mysql")), defaults.getMysql()),
                    Sqlite.fromMap(asMap(map.get("sqlite")), defaults.getSqlite())
            );
        }

        public static ShortUrl defaultValues() {
            return new ShortUrl(
                    false,
                    60001,
                    Public.defaultValues(),
                    7,
                    false,
                    "sqlite",
                    true,
                    7,
                    10,
                    CreationAbuseProtection.defaultValues(),
                    Image.defaultValues(),
                    Mysql.defaultValues(),
                    Sqlite.defaultValues()
            );
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Public getPublic() {
            return publicConfig;
        }

        public int getBindPort() {
            return bindPort;
        }

        public String getPublicBaseUrl() {
            return publicConfig.getBaseUrl();
        }

        public int getCodeLength() {
            return codeLength;
        }

        public boolean isAllowPrivateTargets() {
            return allowPrivateTargets;
        }

        @Deprecated
        public int getPort() {
            return getBindPort();
        }

        public String getStorage() {
            return storage;
        }

        public boolean isDedupe() {
            return dedupe;
        }

        public int getTtlDays() {
            return ttlDays;
        }

        public int getCleanupIntervalMinutes() {
            return cleanupIntervalMinutes;
        }

        public CreationAbuseProtection getCreationAbuseProtection() {
            return creationAbuseProtection;
        }

        public Image getImage() {
            return image;
        }

        public Mysql getMysql() {
            return mysql;
        }

        public Sqlite getSqlite() {
            return sqlite;
        }

        private static String normalizeStorage(String storage) {
            return (storage == null || storage.isBlank())
                    ? "sqlite"
                    : storage.trim().toLowerCase(Locale.ROOT);
        }

        public static class Mysql {
            private final String jdbcUrl;
            private final String username;
            private final String password;
            private final int poolSize;

            private Mysql(String jdbcUrl, String username, String password, int poolSize) {
                this.jdbcUrl = jdbcUrl;
                this.username = username;
                this.password = password;
                this.poolSize = Math.max(2, poolSize);
            }

            public static Mysql fromMap(Map<String, Object> map, Mysql fallback) {
                Mysql defaults = fallback == null ? defaultValues() : fallback;
                return new Mysql(
                        getString(map, "jdbcUrl", defaults.getJdbcUrl()),
                        getString(map, "username", defaults.getUsername()),
                        getString(map, "password", defaults.getPassword()),
                        getInt(map, "poolSize", defaults.getPoolSize())
                );
            }

            public static Mysql defaultValues() {
                return new Mysql(
                        "jdbc:mysql://localhost:3306/discord_bot?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        "root",
                        "",
                        8
                );
            }

            public String getJdbcUrl() {
                return jdbcUrl;
            }

            public String getUsername() {
                return username;
            }

            public String getPassword() {
                return password;
            }

            public int getPoolSize() {
                return poolSize;
            }
        }

        public static class Sqlite {
            private final String path;

            private Sqlite(String path) {
                this.path = (path == null || path.isBlank()) ? "data/norule.db" : path.trim();
            }

            public static Sqlite fromMap(Map<String, Object> map, Sqlite fallback) {
                Sqlite defaults = fallback == null ? defaultValues() : fallback;
                return new Sqlite(getString(map, "path", defaults.getPath()));
            }

            public static Sqlite defaultValues() {
                return new Sqlite("data/norule.db");
            }

            public String getPath() {
                return path;
            }
        }
    }

    public static class Web {
        public static final class Public {
            private final String baseUrl;

            private Public(String baseUrl) {
                String normalized = baseUrl == null ? "" : baseUrl.trim();
                if (normalized.endsWith("/")) {
                    normalized = normalized.substring(0, normalized.length() - 1);
                }
                this.baseUrl = normalized;
            }

            private static Public fromMap(Map<String, Object> map, Public fallback) {
                Public defaults = fallback == null ? defaultValues() : fallback;
                return new Public(getString(map, "baseUrl", defaults.getBaseUrl()));
            }

            private static Public defaultValues() {
                return new Public("https://dash.example.com");
            }

            public String getBaseUrl() {
                return baseUrl;
            }
        }

        private final boolean enabled;
        private final int bindPort;
        private final Public publicConfig;
        private final int sessionExpireMinutes;
        private final String discordClientId;
        private final String discordClientSecret;
        private final String discordRedirectUri;

        private Web(boolean enabled,
                    int bindPort,
                    Public publicConfig,
                    int sessionExpireMinutes,
                    String discordClientId,
                    String discordClientSecret,
                    String discordRedirectUri) {
            this.enabled = enabled;
            this.bindPort = Math.max(1, bindPort);
            this.publicConfig = publicConfig == null ? Public.defaultValues() : publicConfig;
            this.sessionExpireMinutes = Math.max(5, sessionExpireMinutes);
            this.discordClientId = discordClientId == null ? "" : discordClientId.trim();
            this.discordClientSecret = discordClientSecret == null ? "" : discordClientSecret.trim();
            String redirect = discordRedirectUri == null ? "" : discordRedirectUri.trim();
            if (redirect.isBlank()) {
                redirect = defaultRedirectUri(this.publicConfig.getBaseUrl());
            }
            this.discordRedirectUri = redirect;
        }

        public static Web fromMap(Map<String, Object> map, Web fallback) {
            Web defaults = fallback == null ? defaultValues() : fallback;
            Map<String, Object> bindMap = asMap(map.get("bind"));
            Map<String, Object> publicMap = asMap(map.get("public"));

            Map<String, Object> effectivePublicMap = new LinkedHashMap<>(publicMap);
            String publicBaseUrl = getString(effectivePublicMap, "baseUrl", "");
            if (publicBaseUrl.isBlank()) {
                publicBaseUrl = getString(map, "baseUrl", defaults.getPublicBaseUrl());
            }
            effectivePublicMap.put("baseUrl", publicBaseUrl);
            Public publicConfig = Public.fromMap(effectivePublicMap, defaults.getPublic());

            String redirectUri = getString(map, "discordRedirectUri", defaults.getDiscordRedirectUri());
            if (redirectUri.isBlank()) {
                redirectUri = defaultRedirectUri(publicBaseUrl);
            }

            return new Web(
                    getBoolean(map, "enabled", defaults.isEnabled()),
                    getInt(bindMap, "port", getInt(map, "port", defaults.getBindPort())),
                    publicConfig,
                    getInt(map, "sessionExpireMinutes", defaults.getSessionExpireMinutes()),
                    getString(map, "discordClientId", defaults.getDiscordClientId()),
                    getString(map, "discordClientSecret", defaults.getDiscordClientSecret()),
                    redirectUri
            );
        }

        public static Web defaultValues() {
            Public publicConfig = Public.defaultValues();
            return new Web(
                    false,
                    60000,
                    publicConfig,
                    720,
                    "",
                    "",
                    defaultRedirectUri(publicConfig.getBaseUrl())
            );
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Public getPublic() {
            return publicConfig;
        }

        public int getBindPort() {
            return bindPort;
        }

        public String getPublicBaseUrl() {
            return publicConfig.getBaseUrl();
        }

        @Deprecated
        public int getPort() {
            return getBindPort();
        }

        @Deprecated
        public String getBaseUrl() {
            return getPublicBaseUrl();
        }

        public int getSessionExpireMinutes() {
            return sessionExpireMinutes;
        }

        public String getDiscordClientId() {
            return discordClientId;
        }

        public String getDiscordClientSecret() {
            return discordClientSecret;
        }

        public String getDiscordRedirectUri() {
            return discordRedirectUri;
        }

        private static String defaultRedirectUri(String publicBaseUrl) {
            if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
                return "";
            }
            String base = publicBaseUrl.trim();
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return base + "/auth/callback";
        }

    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object object) {
        if (object instanceof Map) {
            return (Map<String, Object>) object;
        }
        return Map.of();
    }

    private static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        return String.valueOf(value).trim();
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static List<String> getStringList(Map<String, Object> map, String key, List<String> defaultValue) {
        if (!map.containsKey(key)) {
            return defaultValue == null ? List.of() : defaultValue;
        }
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        } else {
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }

    private static List<Long> getLongList(Map<String, Object> map, String key, List<Long> defaultValue) {
        if (!map.containsKey(key)) {
            return defaultValue == null ? List.of() : defaultValue;
        }
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                Long parsed = toLong(item);
                if (parsed != null && parsed > 0L) {
                    result.add(parsed);
                }
            }
        } else {
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                for (String part : text.split("[,\\s]+")) {
                    Long parsed = toLong(part);
                    if (parsed != null && parsed > 0L) {
                        result.add(parsed);
                    }
                }
            }
        }
        return result.stream().distinct().toList();
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long getLong(Map<String, Object> map, String key, Long defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        Long parsed = toLong(value);
        return parsed == null ? defaultValue : parsed;
    }

    private static int getColor(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() & 0xFFFFFF;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
        }
        try {
            return Integer.parseInt(text, 16) & 0xFFFFFF;
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text.trim();
    }

}






