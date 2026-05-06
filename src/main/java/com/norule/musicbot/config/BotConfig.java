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
    private final Web web;
    private final Stats stats;

    public BotConfig(String token,
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
                      Web web,
                      Stats stats) {
        this.token = token;
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
        this.web = web;
        this.stats = stats;
    }

    

public String getToken() {
        return token;
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
        private final String guildSettingsDir;
        private final String languageDir;
        private final String musicDir;
        private final String moderationDir;
        private final String ticketDir;
        private final String ticketTranscriptDir;
        private final String honeypotDir;
        private final String logDir;

        private DataPaths(String guildSettingsDir,
                          String languageDir,
                          String musicDir,
                          String moderationDir,
                          String ticketDir,
                          String ticketTranscriptDir,
                          String honeypotDir,
                          String logDir) {
            this.guildSettingsDir = blankToDefault(guildSettingsDir, "guild/configs");
            this.languageDir = blankToDefault(languageDir, "lang");
            this.musicDir = blankToDefault(musicDir, "guild/music");
            this.moderationDir = blankToDefault(moderationDir, "guild/moderation");
            this.ticketDir = blankToDefault(ticketDir, "guild/tickets");
            this.ticketTranscriptDir = blankToDefault(ticketTranscriptDir, "ticket-transcripts");
            this.honeypotDir = blankToDefault(honeypotDir, "guild/honeypot");
            this.logDir = blankToDefault(logDir, "LOG");
        }

        public static DataPaths fromConfig(Map<String, Object> root) {
            return fromConfigWithDefaults(root, Map.of());
        }

        private static DataPaths fromConfigWithDefaults(Map<String, Object> root, Map<String, Object> defaultRoot) {
            Map<String, Object> data = asMap(root.get("data"));
            Map<String, Object> defaults = asMap(defaultRoot.get("data"));
            return new DataPaths(
                    configuredPath(data, root, defaults, defaultRoot, "guildSettingsDir", "guild/configs"),
                    configuredPath(data, root, defaults, defaultRoot, "languageDir", "lang"),
                    configuredPath(data, root, defaults, defaultRoot, "musicDir", "guild/music"),
                    configuredPath(data, root, defaults, defaultRoot, "moderationDir", "guild/moderation"),
                    configuredPath(data, root, defaults, defaultRoot, "ticketDir", "guild/tickets"),
                    configuredPath(data, root, defaults, defaultRoot, "ticketTranscriptDir", "ticket-transcripts"),
                    configuredPath(data, root, defaults, defaultRoot, "honeypotDir", "guild/honeypot"),
                    configuredPath(data, root, defaults, defaultRoot, "logDir", "LOG")
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

    public Web getWeb() {
        return web;
    }

    public Stats getStats() {
        return stats;
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
        private final Spotify spotify;

        private Music(boolean autoLeaveEnabled,
                      int autoLeaveMinutes,
                      boolean autoplayEnabled,
                      RepeatMode defaultRepeatMode,
                      Long commandChannelId,
                      int historyLimit,
                      int statsRetentionDays,
                      int playlistTrackLimit,
                      Youtube youtube,
                      Spotify spotify) {
            this.autoLeaveEnabled = autoLeaveEnabled;
            this.autoLeaveMinutes = autoLeaveMinutes;
            this.autoplayEnabled = autoplayEnabled;
            this.defaultRepeatMode = defaultRepeatMode;
            this.commandChannelId = commandChannelId;
            this.historyLimit = Math.max(1, historyLimit);
            this.statsRetentionDays = Math.max(0, statsRetentionDays);
            this.playlistTrackLimit = Math.max(1, playlistTrackLimit);
            this.youtube = youtube == null ? Youtube.defaultValues() : youtube;
            this.spotify = spotify == null ? Spotify.defaultValues() : spotify;
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
                    Spotify.fromMap(asMap(map.get("spotify")), defaults.getSpotify())
            );
        }

        public static Music defaultValues() {
            return new Music(true, 5, true, RepeatMode.OFF, null, 50, 0, 100, Youtube.defaultValues(), Spotify.defaultValues());
        }

        public Music withAutoLeaveEnabled(boolean enabled) {
            return new Music(enabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, spotify);
        }

        public Music withAutoLeaveMinutes(int minutes) {
            return new Music(autoLeaveEnabled, Math.max(1, minutes), autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, spotify);
        }

        public Music withAutoplayEnabled(boolean enabled) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, enabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, spotify);
        }

        public Music withDefaultRepeatMode(RepeatMode mode) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, mode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, spotify);
        }

        public Music withCommandChannelId(Long commandChannelId) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, spotify);
        }

        public Music withHistoryLimit(int historyLimit) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, spotify);
        }

        public Music withStatsRetentionDays(int statsRetentionDays) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, spotify);
        }

        public Music withPlaylistTrackLimit(int playlistTrackLimit) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays, playlistTrackLimit, youtube, spotify);
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

        public Spotify getSpotify() {
            return spotify;
        }

        private static RepeatMode parseRepeatMode(String value) {
            try {
                return RepeatMode.valueOf(value.trim().toUpperCase());
            } catch (Exception ignored) {
                return RepeatMode.OFF;
            }
        }

        public static class Spotify {
            private final boolean enabled;
            private final String clientId;
            private final String clientSecret;
            private final String spDc;
            private final String countryCode;
            private final boolean preferAnonymousToken;
            private final String customTokenEndpoint;
            private final int playlistMaxTracks;
            private final int playlistLoadCooldownSeconds;

            private Spotify(boolean enabled,
                            String clientId,
                            String clientSecret,
                            String spDc,
                            String countryCode,
                            boolean preferAnonymousToken,
                            String customTokenEndpoint,
                            int playlistMaxTracks,
                            int playlistLoadCooldownSeconds) {
                this.enabled = enabled;
                this.clientId = nullToEmpty(clientId);
                this.clientSecret = nullToEmpty(clientSecret);
                this.spDc = nullToEmpty(spDc);
                this.countryCode = nullToEmpty(countryCode);
                this.preferAnonymousToken = preferAnonymousToken;
                this.customTokenEndpoint = nullToEmpty(customTokenEndpoint);
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
                        getString(map, "customTokenEndpoint", defaults.getCustomTokenEndpoint()),
                        getInt(map, "playlistMaxTracks", defaults.getPlaylistMaxTracks()),
                        getInt(map, "playlistLoadCooldownSeconds", defaults.getPlaylistLoadCooldownSeconds())
                );
            }

            public static Spotify defaultValues() {
                return new Spotify(false, "", "", "", "TW", false, "", 50, 60);
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

            public String getCustomTokenEndpoint() {
                return customTokenEndpoint;
            }

            public int getPlaylistMaxTracks() {
                return playlistMaxTracks;
            }

            public int getPlaylistLoadCooldownSeconds() {
                return playlistLoadCooldownSeconds;
            }
        }

        public static class Youtube {
            private final boolean oauthEnabled;
            private final boolean cipherEnabled;
            private final String oauthRefreshToken;
            private final String cipherServer;
            private final String cipherPassword;
            private final String cipherUserAgent;

            private Youtube(boolean oauthEnabled,
                            boolean cipherEnabled,
                            String oauthRefreshToken,
                            String cipherServer,
                            String cipherPassword,
                            String cipherUserAgent) {
                this.oauthEnabled = oauthEnabled;
                this.cipherEnabled = cipherEnabled;
                this.oauthRefreshToken = nullToEmpty(oauthRefreshToken);
                this.cipherServer = nullToEmpty(cipherServer);
                this.cipherPassword = nullToEmpty(cipherPassword);
                this.cipherUserAgent = nullToEmpty(cipherUserAgent);
            }

            public static Youtube fromMap(Map<String, Object> map, Youtube fallback) {
                Youtube defaults = fallback == null ? defaultValues() : fallback;
                return new Youtube(
                        getBoolean(map, "oauthEnabled", defaults.isOauthEnabled()),
                        getBoolean(map, "cipherEnabled", defaults.isCipherEnabled()),
                        getString(map, "oauthRefreshToken", defaults.getOauthRefreshToken()),
                        getString(map, "cipherServer", defaults.getCipherServer()),
                        getString(map, "cipherPassword", defaults.getCipherPassword()),
                        getString(map, "cipherUserAgent", defaults.getCipherUserAgent())
                );
            }

            public static Youtube defaultValues() {
                return new Youtube(false, false, "", "", "", "");
            }

            public boolean isOauthEnabled() {
                return oauthEnabled;
            }

            public boolean isCipherEnabled() {
                return cipherEnabled;
            }

            public String getOauthRefreshToken() {
                return oauthRefreshToken;
            }

            public String getCipherServer() {
                return cipherServer;
            }

            public String getCipherPassword() {
                return cipherPassword;
            }

            public String getCipherUserAgent() {
                return cipherUserAgent;
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
        private final String activityType;
        private final String activityText;
        private final int activityRotationSeconds;
        private final List<String> activities;

        private BotProfile(String description,
                           String presenceStatus,
                           String activityType,
                           String activityText,
                           int activityRotationSeconds,
                           List<String> activities) {
            this.description = description;
            this.presenceStatus = presenceStatus;
            this.activityType = activityType;
            this.activityText = activityText;
            this.activityRotationSeconds = Math.max(5, activityRotationSeconds);
            this.activities = activities == null ? List.of() : List.copyOf(activities);
        }

        public static BotProfile fromMap(Map<String, Object> map, BotProfile fallback) {
            BotProfile defaults = fallback == null ? defaultValues() : fallback;
            return new BotProfile(
                    getString(map, "description", defaults.getDescription()),
                    getString(map, "presenceStatus", defaults.getPresenceStatus()),
                    getString(map, "activityType", defaults.getActivityType()),
                    getString(map, "activityText", defaults.getActivityText()),
                    Math.max(5, getInt(map, "rotationSeconds",
                            getInt(map, "activityRotationSeconds", defaults.getActivityRotationSeconds()))),
                    getStringList(map, "activities", defaults.getActivities())
            );
        }

        public static BotProfile defaultValues() {
            return new BotProfile("NoRule Bot", "ONLINE", "PLAYING", "/help", 20, List.of());
        }

        public String getDescription() {
            return description;
        }

        public String getPresenceStatus() {
            return presenceStatus;
        }

        public String getActivityType() {
            return activityType;
        }

        public String getActivityText() {
            return activityText;
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

        private Developers(List<Long> ids) {
            this.ids = ids == null ? List.of() : List.copyOf(ids);
        }

        public static Developers fromMap(Map<String, Object> map, Developers fallback) {
            Developers defaults = fallback == null ? defaultValues() : fallback;
            return new Developers(getLongList(map, "ids", defaults.getIds()));
        }

        public static Developers defaultValues() {
            return new Developers(List.of());
        }

        public List<Long> getIds() {
            return ids;
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
        public static final class Bind {
            private final String host;
            private final int port;

            private Bind(String host, int port) {
                this.host = (host == null || host.isBlank()) ? "0.0.0.0" : host.trim();
                this.port = Math.max(1, port);
            }

            private static Bind fromMap(Map<String, Object> map, Bind fallback) {
                Bind defaults = fallback == null ? defaultValues() : fallback;
                return new Bind(
                        getString(map, "host", defaults.getHost()),
                        getInt(map, "port", defaults.getPort())
                );
            }

            private static Bind defaultValues() {
                return new Bind("0.0.0.0", 60001);
            }

            public String getHost() {
                return host;
            }

            public int getPort() {
                return port;
            }
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

        private final boolean enabled;
        private final Bind bind;
        private final Public publicConfig;
        private final int codeLength;
        private final boolean allowPrivateTargets;
        private final String storage;
        private final boolean dedupe;
        private final int ttlDays;
        private final int cleanupIntervalMinutes;
        private final Mysql mysql;
        private final Sqlite sqlite;

        private ShortUrl(boolean enabled,
                         Bind bind,
                         Public publicConfig,
                         int codeLength,
                         boolean allowPrivateTargets,
                         String storage,
                         boolean dedupe,
                         int ttlDays,
                         int cleanupIntervalMinutes,
                         Mysql mysql,
                         Sqlite sqlite) {
            this.enabled = enabled;
            this.bind = bind == null ? Bind.defaultValues() : bind;
            this.publicConfig = publicConfig == null ? Public.defaultValues() : publicConfig;
            this.codeLength = Math.max(4, Math.min(32, codeLength));
            this.allowPrivateTargets = allowPrivateTargets;
            this.storage = normalizeStorage(storage);
            this.dedupe = dedupe;
            this.ttlDays = Math.max(1, ttlDays);
            this.cleanupIntervalMinutes = Math.max(1, cleanupIntervalMinutes);
            this.mysql = mysql == null ? Mysql.defaultValues() : mysql;
            this.sqlite = sqlite == null ? Sqlite.defaultValues() : sqlite;
        }

        public static ShortUrl fromMap(Map<String, Object> map, ShortUrl fallback) {
            ShortUrl defaults = fallback == null ? defaultValues() : fallback;
            Map<String, Object> bindMap = asMap(map.get("bind"));
            Map<String, Object> publicMap = asMap(map.get("public"));

            Map<String, Object> effectiveBindMap = new LinkedHashMap<>(bindMap);
            String bindHost = getString(map, "bindHost", "");
            if (!bindHost.isBlank()) {
                effectiveBindMap.put("host", bindHost);
            }
            int bindPort = getInt(map, "bindPort", -1);
            if (bindPort > 0) {
                effectiveBindMap.put("port", bindPort);
            }
            if (!effectiveBindMap.containsKey("host")) {
                effectiveBindMap.put("host", getString(map, "host", defaults.getBindHost()));
            }
            if (!effectiveBindMap.containsKey("port")) {
                effectiveBindMap.put("port", getInt(map, "port", defaults.getBindPort()));
            }
            Bind bind = Bind.fromMap(effectiveBindMap, defaults.getBind());

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
                    bind,
                    publicConfig,
                    getInt(map, "codeLength", defaults.getCodeLength()),
                    getBoolean(map, "allowPrivateTargets", defaults.isAllowPrivateTargets()),
                    getString(map, "storage", defaults.getStorage()),
                    getBoolean(map, "dedupe", defaults.isDedupe()),
                    getInt(map, "ttlDays", defaults.getTtlDays()),
                    getInt(map, "cleanupIntervalMinutes", defaults.getCleanupIntervalMinutes()),
                    Mysql.fromMap(asMap(map.get("mysql")), defaults.getMysql()),
                    Sqlite.fromMap(asMap(map.get("sqlite")), defaults.getSqlite())
            );
        }

        public static ShortUrl defaultValues() {
            return new ShortUrl(
                    false,
                    Bind.defaultValues(),
                    Public.defaultValues(),
                    7,
                    false,
                    "sqlite",
                    true,
                    7,
                    10,
                    Mysql.defaultValues(),
                    Sqlite.defaultValues()
            );
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Bind getBind() {
            return bind;
        }

        public Public getPublic() {
            return publicConfig;
        }

        public String getBindHost() {
            return bind.getHost();
        }

        public int getBindPort() {
            return bind.getPort();
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
        public String getHost() {
            return getBindHost();
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
                this.path = (path == null || path.isBlank()) ? "data/short-url.db" : path.trim();
            }

            public static Sqlite fromMap(Map<String, Object> map, Sqlite fallback) {
                Sqlite defaults = fallback == null ? defaultValues() : fallback;
                return new Sqlite(getString(map, "path", defaults.getPath()));
            }

            public static Sqlite defaultValues() {
                return new Sqlite("data/short-url.db");
            }

            public String getPath() {
                return path;
            }
        }
    }

    public static class Web {
        public static final class Bind {
            private final String host;
            private final int port;

            private Bind(String host, int port) {
                this.host = host == null || host.isBlank() ? "0.0.0.0" : host.trim();
                this.port = Math.max(1, port);
            }

            private static Bind fromMap(Map<String, Object> map, Bind fallback) {
                Bind defaults = fallback == null ? defaultValues() : fallback;
                return new Bind(
                        getString(map, "host", defaults.getHost()),
                        getInt(map, "port", defaults.getPort())
                );
            }

            private static Bind defaultValues() {
                return new Bind("0.0.0.0", 60000);
            }

            public String getHost() {
                return host;
            }

            public int getPort() {
                return port;
            }
        }

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
        private final Bind bind;
        private final Public publicConfig;
        private final int sessionExpireMinutes;
        private final String discordClientId;
        private final String discordClientSecret;
        private final String discordRedirectUri;
        private final Ssl ssl;

        private Web(boolean enabled,
                    Bind bind,
                    Public publicConfig,
                    int sessionExpireMinutes,
                    String discordClientId,
                    String discordClientSecret,
                    String discordRedirectUri,
                    Ssl ssl) {
            this.enabled = enabled;
            this.bind = bind == null ? Bind.defaultValues() : bind;
            this.publicConfig = publicConfig == null ? Public.defaultValues() : publicConfig;
            this.sessionExpireMinutes = Math.max(5, sessionExpireMinutes);
            this.discordClientId = discordClientId == null ? "" : discordClientId.trim();
            this.discordClientSecret = discordClientSecret == null ? "" : discordClientSecret.trim();
            String redirect = discordRedirectUri == null ? "" : discordRedirectUri.trim();
            if (redirect.isBlank()) {
                redirect = defaultRedirectUri(this.publicConfig.getBaseUrl());
            }
            this.discordRedirectUri = redirect;
            this.ssl = ssl == null ? Ssl.defaultValues() : ssl;
        }

        public static Web fromMap(Map<String, Object> map, Web fallback) {
            Web defaults = fallback == null ? defaultValues() : fallback;
            Map<String, Object> bindMap = asMap(map.get("bind"));
            Map<String, Object> publicMap = asMap(map.get("public"));

            Map<String, Object> effectiveBindMap = new LinkedHashMap<>(bindMap);
            if (!effectiveBindMap.containsKey("host")) {
                effectiveBindMap.put("host", getString(map, "host", defaults.getBindHost()));
            }
            if (!effectiveBindMap.containsKey("port")) {
                effectiveBindMap.put("port", getInt(map, "port", defaults.getBindPort()));
            }
            Bind bind = Bind.fromMap(effectiveBindMap, defaults.getBind());

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
                    bind,
                    publicConfig,
                    getInt(map, "sessionExpireMinutes", defaults.getSessionExpireMinutes()),
                    getString(map, "discordClientId", defaults.getDiscordClientId()),
                    getString(map, "discordClientSecret", defaults.getDiscordClientSecret()),
                    redirectUri,
                    Ssl.fromMap(asMap(map.get("ssl")), defaults.getSsl())
            );
        }

        public static Web defaultValues() {
            Public publicConfig = Public.defaultValues();
            return new Web(
                    false,
                    Bind.defaultValues(),
                    publicConfig,
                    720,
                    "",
                    "",
                    defaultRedirectUri(publicConfig.getBaseUrl()),
                    Ssl.defaultValues()
            );
        }

        public boolean isEnabled() {
            return enabled;
        }

        public Bind getBind() {
            return bind;
        }

        public Public getPublic() {
            return publicConfig;
        }

        public String getBindHost() {
            return bind.getHost();
        }

        public int getBindPort() {
            return bind.getPort();
        }

        public String getPublicBaseUrl() {
            return publicConfig.getBaseUrl();
        }

        @Deprecated
        public String getHost() {
            return getBindHost();
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

        public Ssl getSsl() {
            return ssl;
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

        public static class Ssl {
            private final boolean enabled;
            private final String certDir;
            private final String privateKeyFile;
            private final String fullChainFile;
            private final String keyStoreFile;
            private final String keyStorePassword;
            private final String keyStoreType;
            private final String keyPassword;

            private Ssl(boolean enabled,
                        String certDir,
                        String privateKeyFile,
                        String fullChainFile,
                        String keyStoreFile,
                        String keyStorePassword,
                        String keyStoreType,
                        String keyPassword) {
                this.enabled = enabled;
                this.certDir = certDir;
                this.privateKeyFile = privateKeyFile;
                this.fullChainFile = fullChainFile;
                this.keyStoreFile = keyStoreFile;
                this.keyStorePassword = keyStorePassword;
                this.keyStoreType = keyStoreType;
                this.keyPassword = keyPassword;
            }

            public static Ssl fromMap(Map<String, Object> map, Ssl fallback) {
                Ssl defaults = fallback == null ? defaultValues() : fallback;
                return new Ssl(
                        getBoolean(map, "enabled", defaults.isEnabled()),
                        getString(map, "certDir", defaults.getCertDir()),
                        getString(map, "privateKeyFile", defaults.getPrivateKeyFile()),
                        getString(map, "fullChainFile", defaults.getFullChainFile()),
                        getString(map, "keyStoreFile", defaults.getKeyStoreFile()),
                        getString(map, "keyStorePassword", defaults.getKeyStorePassword()),
                        getString(map, "keyStoreType", defaults.getKeyStoreType()),
                        getString(map, "keyPassword", defaults.getKeyPassword())
                );
            }

            public static Ssl defaultValues() {
                return new Ssl(
                        false,
                        "certs",
                        "privkey.pem",
                        "fullchain.pem",
                        "web-keystore.p12",
                        "",
                        "PKCS12",
                        ""
                );
            }

            public boolean isEnabled() {
                return enabled;
            }

            public String getCertDir() {
                return certDir;
            }

            public String getPrivateKeyFile() {
                return privateKeyFile;
            }

            public String getFullChainFile() {
                return fullChainFile;
            }

            public String getKeyStoreFile() {
                return keyStoreFile;
            }

            public String getKeyStorePassword() {
                return keyStorePassword;
            }

            public String getKeyStoreType() {
                return keyStoreType;
            }

            public String getKeyPassword() {
                return keyPassword;
            }
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






