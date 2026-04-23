package com.norule.musicbot.config;

import com.norule.musicbot.*;
import com.norule.musicbot.i18n.*;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class BotConfig {
    private final String token;
    private final String prefix;
    private final Long commandGuildId;
    private final String guildSettingsDir;
    private final String languageDir;
    private final DataPaths dataPaths;
    private final String defaultLanguage;
    private final int commandCooldownSeconds;
    private final BotProfile botProfile;
    private final Map<String, String> commandDescriptions;
    private final Notifications notifications;
    private final Welcome welcome;
    private final MessageLogs messageLogs;
    private final Music music;
    private final PrivateRoom privateRoom;
    private final Ticket ticket;
    private final Web web;

    private BotConfig(String token,
                      String prefix,
                      Long commandGuildId,
                      String guildSettingsDir,
                      String languageDir,
                      DataPaths dataPaths,
                      String defaultLanguage,
                      int commandCooldownSeconds,
                      BotProfile botProfile,
                      Map<String, String> commandDescriptions,
                      Notifications notifications,
                      Welcome welcome,
                      MessageLogs messageLogs,
                      Music music,
                      PrivateRoom privateRoom,
                      Ticket ticket,
                      Web web) {
        this.token = token;
        this.prefix = prefix;
        this.commandGuildId = commandGuildId;
        this.guildSettingsDir = guildSettingsDir;
        this.languageDir = languageDir;
        this.dataPaths = dataPaths;
        this.defaultLanguage = defaultLanguage;
        this.commandCooldownSeconds = Math.max(0, commandCooldownSeconds);
        this.botProfile = botProfile;
        this.commandDescriptions = commandDescriptions;
        this.notifications = notifications;
        this.welcome = welcome;
        this.messageLogs = messageLogs;
        this.music = music;
        this.privateRoom = privateRoom;
        this.ticket = ticket;
        this.web = web;
    }

    public static BotConfig load(Path path) {
        initializeConfigAndLang(path);
        if (!Files.exists(path)) {
            throw new IllegalStateException("config.yml not found: " + path.toAbsolutePath());
        }

        try (InputStream inputStream = Files.newInputStream(path)) {
            Yaml yaml = new Yaml();
            Object rootObject = yaml.load(inputStream);
            Map<String, Object> root = asMap(rootObject);

            String tokenFromConfig = getString(root, "token", "");
            String tokenFromEnv = System.getenv("DISCORD_TOKEN");
            String token = !tokenFromConfig.isBlank() ? tokenFromConfig : nullToEmpty(tokenFromEnv);
            if (token.isBlank()) {
                throw new IllegalStateException("Token missing. Set token in config.yml or DISCORD_TOKEN.");
            }

            String prefix = getString(root, "prefix", "!");
            Long commandGuildId = toLong(root.get("commandGuildId"));
            DataPaths dataPaths = DataPaths.fromConfig(root);
            String guildSettingsDir = dataPaths.getGuildSettingsDir();
            String languageDir = dataPaths.getLanguageDir();
            String defaultLanguage = getString(root, "defaultLanguage", "en");
            int commandCooldownSeconds = Math.max(0, getInt(root, "commandCooldownSeconds", 3));
            BotProfile botProfile = BotProfile.fromMap(asMap(root.get("bot")), null);
            Map<String, String> commandDescriptions = resolveCommandDescriptions(asMap(root.get("commandDescriptions")));
            Notifications notifications = Notifications.fromMap(asMap(root.get("notifications")), null);
            Welcome welcome = Welcome.fromMap(asMap(root.get("welcome")), null);
            MessageLogs messageLogs = MessageLogs.fromMap(asMap(root.get("messageLogs")), null);
            Music music = Music.fromMap(asMap(root.get("music")), null);
            PrivateRoom privateRoom = PrivateRoom.fromMap(asMap(root.get("privateRoom")), null);
            Ticket ticket = Ticket.fromMap(asMap(root.get("ticket")), null);
            Web web = Web.fromMap(asMap(root.get("web")), null);

            return new BotConfig(token, prefix, commandGuildId, guildSettingsDir, languageDir, dataPaths, defaultLanguage, commandCooldownSeconds, botProfile,
                    commandDescriptions,
                    notifications, welcome, messageLogs, music, privateRoom, ticket, web);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read config.yml: " + path.toAbsolutePath(), e);
        }
    }

    private static void initializeConfigAndLang(Path configPath) {
        Map<String, Object> defaultConfig = readDefaultConfigMap();
        if (defaultConfig.isEmpty()) {
            return;
        }

        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (!Files.exists(configPath)) {
                writeResourceIfMissing("defaults/config.yml", configPath);
                if (!Files.exists(configPath)) {
                    writeYaml(configPath, defaultConfig);
                }
            }

            Map<String, Object> currentConfig = readYamlMap(configPath);
            if (currentConfig == null) {
                return;
            }
            if (currentConfig.isEmpty()) {
                currentConfig = new LinkedHashMap<>();
            }

            String languageDir = DataPaths.fromConfigWithDefaults(currentConfig, defaultConfig).getLanguageDir();
            Path baseDir = parent == null ? Path.of(".") : parent;
            Path languagePath = resolvePath(baseDir, languageDir);
            ensureDefaultLanguageFiles(languagePath);
            mergeLanguageDefaults(languagePath);
            ensureWebCertificateDirectory(baseDir, currentConfig, defaultConfig);

            Map<String, Object> merged = deepMerge(defaultConfig, currentConfig);
            if (!merged.equals(currentConfig)) {
                backupConfig(configPath);
                writeYaml(configPath, merged);
            }
        } catch (Exception ignored) {
        }
    }

    private static Map<String, Object> readDefaultConfigMap() {
        try (InputStream in = BotConfig.class.getClassLoader().getResourceAsStream("defaults/config.yml")) {
            if (in == null) {
                return Map.of();
            }
            Object obj = new Yaml().load(in);
            return asMap(obj);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Map<String, Object> readYamlMap(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object obj = new Yaml().load(reader);
            return asMap(obj);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void ensureDefaultLanguageFiles(Path languageDir) {
        try {
            Files.createDirectories(languageDir);
            Files.createDirectories(languageDir.resolve("web"));
            writeResourceIfMissing("defaults/lang/zh-TW.yml", languageDir.resolve("zh-TW.yml"));
            writeResourceIfMissing("defaults/lang/zh-CN.yml", languageDir.resolve("zh-CN.yml"));
            writeResourceIfMissing("defaults/lang/en.yml", languageDir.resolve("en.yml"));
            migrateLegacyWebLanguageFiles(languageDir);
            writeResourceIfMissing("defaults/lang/web/web-zh-TW.yml", languageDir.resolve("web").resolve("web-zh-TW.yml"));
            writeResourceIfMissing("defaults/lang/web/web-zh-CN.yml", languageDir.resolve("web").resolve("web-zh-CN.yml"));
            writeResourceIfMissing("defaults/lang/web/web-en.yml", languageDir.resolve("web").resolve("web-en.yml"));
        } catch (Exception ignored) {
        }
    }

    private static void mergeLanguageDefaults(Path languageDir) {
        try {
            mergeLanguageFile(languageDir.resolve("zh-TW.yml"), "defaults/lang/zh-TW.yml");
            mergeLanguageFile(languageDir.resolve("zh-CN.yml"), "defaults/lang/zh-CN.yml");
            mergeLanguageFile(languageDir.resolve("en.yml"), "defaults/lang/en.yml");
            mergeLanguageFile(languageDir.resolve("web").resolve("web-zh-TW.yml"), "defaults/lang/web/web-zh-TW.yml");
            mergeLanguageFile(languageDir.resolve("web").resolve("web-zh-CN.yml"), "defaults/lang/web/web-zh-CN.yml");
            mergeLanguageFile(languageDir.resolve("web").resolve("web-en.yml"), "defaults/lang/web/web-en.yml");
        } catch (Exception ignored) {
        }
    }

    private static void migrateLegacyWebLanguageFiles(Path languageDir) {
        migrateLegacyWebLanguageFile(languageDir, "web-zh-TW.yml");
        migrateLegacyWebLanguageFile(languageDir, "web-zh-CN.yml");
        migrateLegacyWebLanguageFile(languageDir, "web-en.yml");
    }

    private static void migrateLegacyWebLanguageFile(Path languageDir, String fileName) {
        Path legacy = languageDir.resolve(fileName);
        Path target = languageDir.resolve("web").resolve(fileName);
        try {
            if (!Files.exists(legacy) || Files.exists(target)) {
                return;
            }
            Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private static void ensureWebCertificateDirectory(Path baseDir, Map<String, Object> currentConfig, Map<String, Object> defaultConfig) {
        try {
            Map<String, Object> currentWeb = asMap(currentConfig.get("web"));
            Map<String, Object> defaultWeb = asMap(defaultConfig.get("web"));
            Map<String, Object> currentSsl = asMap(currentWeb.get("ssl"));
            Map<String, Object> defaultSsl = asMap(defaultWeb.get("ssl"));
            String certDirRaw = getString(currentSsl, "certDir", getString(defaultSsl, "certDir", "certs"));
            if (certDirRaw.isBlank()) {
                certDirRaw = "certs";
            }
            Path certDir = resolvePath(baseDir, certDirRaw);
            Files.createDirectories(certDir);
        } catch (Exception ignored) {
        }
    }

    private static Path resolvePath(Path baseDir, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return baseDir.resolve("certs").normalize();
        }
        Path path = Path.of(rawPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDir.resolve(path).normalize();
    }

    private static void mergeLanguageFile(Path targetFile, String resourcePath) {
        Map<String, Object> defaults = readYamlResourceMap(resourcePath);
        if (defaults.isEmpty()) {
            return;
        }
        YamlReadResult existingResult = readYamlMapWithStatus(targetFile);
        if (existingResult.parseError) {
            backupCorruptedLanguageFile(targetFile);
            writeQuietly(targetFile, defaults);
            return;
        }
        Map<String, Object> existing = existingResult.map;
        if (existing == null || existing.isEmpty()) {
            writeQuietly(targetFile, defaults);
            return;
        }
        Map<String, Object> merged = deepMerge(defaults, existing);
        if (!merged.equals(existing)) {
            writeQuietly(targetFile, merged);
        }
    }

    @SuppressWarnings("unchecked")
    private static YamlReadResult readYamlMapWithStatus(Path file) {
        if (file == null || !Files.exists(file)) {
            return new YamlReadResult(Map.of(), false);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object obj = new Yaml().load(reader);
            if (obj == null) {
                return new YamlReadResult(Map.of(), false);
            }
            if (obj instanceof Map<?, ?> map) {
                return new YamlReadResult((Map<String, Object>) map, false);
            }
            return new YamlReadResult(Map.of(), true);
        } catch (Exception ignored) {
            return new YamlReadResult(Map.of(), true);
        }
    }

    private static Map<String, Object> readYamlResourceMap(String resourcePath) {
        try (InputStream in = BotConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Map.of();
            }
            Object obj = new Yaml().load(in);
            return asMap(obj);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static void writeQuietly(Path file, Map<String, Object> root) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writeYaml(file, root);
        } catch (Exception ignored) {
        }
    }

    private static void writeResourceIfMissing(String resourcePath, Path target) {
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = BotConfig.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> defaults, Map<String, Object> existing) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            Object defaultValue = entry.getValue();
            if (!existing.containsKey(key)) {
                result.put(key, defaultValue);
                continue;
            }
            Object existingValue = existing.get(key);
            if (defaultValue instanceof Map<?, ?> defaultMap && existingValue instanceof Map<?, ?> existingMap) {
                result.put(key, deepMerge((Map<String, Object>) defaultMap, (Map<String, Object>) existingMap));
            } else {
                result.put(key, existingValue);
            }
        }
        for (Map.Entry<String, Object> entry : existing.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static void backupConfig(Path configPath) {
        if (!Files.exists(configPath)) {
            return;
        }
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String fileName = configPath.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            String ext = dot > 0 ? fileName.substring(dot) : "";
            Path backup = configPath.resolveSibling(base + ".backup-" + timestamp + ext);
            Files.copy(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private static void backupCorruptedLanguageFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            String fileName = path.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            String ext = dot > 0 ? fileName.substring(dot) : "";
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path backup = path.resolveSibling(base + ".corrupt-" + timestamp + ext + ".bak");
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[NoRule] Corrupted language file backed up: " + backup.getFileName());
        } catch (Exception ignored) {
        }
    }

    private static class YamlReadResult {
        private final Map<String, Object> map;
        private final boolean parseError;

        private YamlReadResult(Map<String, Object> map, boolean parseError) {
            this.map = map == null ? Map.of() : map;
            this.parseError = parseError;
        }
    }

    private static void writeYaml(Path file, Map<String, Object> root) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        Yaml yaml = new Yaml(options);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            yaml.dump(root, writer);
        }
    }

    public String getToken() {
        return token;
    }

    public String getPrefix() {
        return prefix;
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

    public String getCacheDir() {
        return dataPaths.getCacheDir();
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

    public BotProfile getBotProfile() {
        return botProfile;
    }

    public static class DataPaths {
        private final String guildSettingsDir;
        private final String languageDir;
        private final String cacheDir;
        private final String musicDir;
        private final String moderationDir;
        private final String ticketDir;
        private final String ticketTranscriptDir;
        private final String honeypotDir;
        private final String logDir;

        private DataPaths(String guildSettingsDir,
                          String languageDir,
                          String cacheDir,
                          String musicDir,
                          String moderationDir,
                          String ticketDir,
                          String ticketTranscriptDir,
                          String honeypotDir,
                          String logDir) {
            this.guildSettingsDir = blankToDefault(guildSettingsDir, "guild-configs");
            this.languageDir = blankToDefault(languageDir, "lang");
            this.cacheDir = blankToDefault(cacheDir, "cache");
            this.musicDir = blankToDefault(musicDir, "guild-music");
            this.moderationDir = blankToDefault(moderationDir, "guild-moderation");
            this.ticketDir = blankToDefault(ticketDir, "guild-tickets");
            this.ticketTranscriptDir = blankToDefault(ticketTranscriptDir, "ticket-transcripts");
            this.honeypotDir = blankToDefault(honeypotDir, "guild-honeypot");
            this.logDir = blankToDefault(logDir, "LOG");
        }

        private static DataPaths fromConfig(Map<String, Object> root) {
            return fromConfigWithDefaults(root, Map.of());
        }

        private static DataPaths fromConfigWithDefaults(Map<String, Object> root, Map<String, Object> defaultRoot) {
            Map<String, Object> data = asMap(root.get("data"));
            Map<String, Object> defaults = asMap(defaultRoot.get("data"));
            return new DataPaths(
                    configuredPath(data, root, defaults, defaultRoot, "guildSettingsDir", "guild-configs"),
                    configuredPath(data, root, defaults, defaultRoot, "languageDir", "lang"),
                    configuredPath(data, root, defaults, defaultRoot, "cacheDir", "cache"),
                    configuredPath(data, root, defaults, defaultRoot, "musicDir", "guild-music"),
                    configuredPath(data, root, defaults, defaultRoot, "moderationDir", "guild-moderation"),
                    configuredPath(data, root, defaults, defaultRoot, "ticketDir", "guild-tickets"),
                    configuredPath(data, root, defaults, defaultRoot, "ticketTranscriptDir", "ticket-transcripts"),
                    configuredPath(data, root, defaults, defaultRoot, "honeypotDir", "guild-honeypot"),
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

        public String getCacheDir() {
            return cacheDir;
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

    public String getCommandDescription(String key, String fallback) {
        String value = commandDescriptions.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
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

    public Web getWeb() {
        return web;
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

        private Music(boolean autoLeaveEnabled,
                      int autoLeaveMinutes,
                      boolean autoplayEnabled,
                      RepeatMode defaultRepeatMode,
                      Long commandChannelId,
                      int historyLimit,
                      int statsRetentionDays) {
            this.autoLeaveEnabled = autoLeaveEnabled;
            this.autoLeaveMinutes = autoLeaveMinutes;
            this.autoplayEnabled = autoplayEnabled;
            this.defaultRepeatMode = defaultRepeatMode;
            this.commandChannelId = commandChannelId;
            this.historyLimit = Math.max(1, historyLimit);
            this.statsRetentionDays = Math.max(0, statsRetentionDays);
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
                    getInt(map, "statsRetentionDays", defaults.getStatsRetentionDays())
            );
        }

        public static Music defaultValues() {
            return new Music(true, 5, true, RepeatMode.OFF, null, 50, 0);
        }

        public Music withAutoLeaveEnabled(boolean enabled) {
            return new Music(enabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays);
        }

        public Music withAutoLeaveMinutes(int minutes) {
            return new Music(autoLeaveEnabled, Math.max(1, minutes), autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays);
        }

        public Music withAutoplayEnabled(boolean enabled) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, enabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays);
        }

        public Music withDefaultRepeatMode(RepeatMode mode) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, mode, commandChannelId, historyLimit, statsRetentionDays);
        }

        public Music withCommandChannelId(Long commandChannelId) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays);
        }

        public Music withHistoryLimit(int historyLimit) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays);
        }

        public Music withStatsRetentionDays(int statsRetentionDays) {
            return new Music(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId, historyLimit, statsRetentionDays);
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

        private static RepeatMode parseRepeatMode(String value) {
            try {
                return RepeatMode.valueOf(value.trim().toUpperCase());
            } catch (Exception ignored) {
                return RepeatMode.OFF;
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
public static class Web {
        private final boolean enabled;
        private final String host;
        private final int port;
        private final String baseUrl;
        private final int sessionExpireMinutes;
        private final String discordClientId;
        private final String discordClientSecret;
        private final String discordRedirectUri;
        private final Ssl ssl;

        private Web(boolean enabled,
                    String host,
                    int port,
                    String baseUrl,
                    int sessionExpireMinutes,
                    String discordClientId,
                    String discordClientSecret,
                    String discordRedirectUri,
                    Ssl ssl) {
            this.enabled = enabled;
            this.host = host;
            this.port = Math.max(1, port);
            this.baseUrl = baseUrl;
            this.sessionExpireMinutes = Math.max(5, sessionExpireMinutes);
            this.discordClientId = discordClientId;
            this.discordClientSecret = discordClientSecret;
            this.discordRedirectUri = discordRedirectUri;
            this.ssl = ssl == null ? Ssl.defaultValues() : ssl;
        }

        public static Web fromMap(Map<String, Object> map, Web fallback) {
            Web defaults = fallback == null ? defaultValues() : fallback;
            return new Web(
                    getBoolean(map, "enabled", defaults.isEnabled()),
                    getString(map, "host", defaults.getHost()),
                    getInt(map, "port", defaults.getPort()),
                    getString(map, "baseUrl", defaults.getBaseUrl()),
                    getInt(map, "sessionExpireMinutes", defaults.getSessionExpireMinutes()),
                    getString(map, "discordClientId", defaults.getDiscordClientId()),
                    getString(map, "discordClientSecret", defaults.getDiscordClientSecret()),
                    getString(map, "discordRedirectUri", defaults.getDiscordRedirectUri()),
                    Ssl.fromMap(asMap(map.get("ssl")), defaults.getSsl())
            );
        }

        public static Web defaultValues() {
            return new Web(
                    false,
                    "0.0.0.0",
                    60000,
                    "http://localhost:60000",
                    720,
                    "",
                    "",
                    "http://localhost:60000/auth/callback",
                    Ssl.defaultValues()
            );
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getBaseUrl() {
            return baseUrl;
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

    private static Map<String, String> resolveCommandDescriptions(Map<String, Object> source) {
        Map<String, String> resolved = new LinkedHashMap<>(defaultCommandDescriptions());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            String value = String.valueOf(entry.getValue()).trim();
            if (!key.isBlank() && !value.isBlank()) {
                resolved.put(key, value);
            }
        }
        return resolved;
    }

    private static Map<String, String> defaultCommandDescriptions() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("help", "Show bot help");
        map.put("join", "Join your voice channel");
        map.put("play", "Play music");
        map.put("play.query", "URL / keywords / Spotify URL");
        map.put("skip", "Skip current track");
        map.put("stop", "Stop playback and clear queue");
        map.put("leave", "Leave voice channel");
        map.put("music-panel", "Create music control panel");
        map.put("repeat", "Set repeat mode");
        map.put("repeat.mode", "off / single / all");
        map.put("delete", "Delete messages");
        map.put("delete.channel", "Delete messages in selected channel");
        map.put("delete.channel.channel", "Text channel");
        map.put("delete.channel.amount", "1-99");
        map.put("delete.user", "Delete messages by selected user");
        map.put("delete.user.user", "Target user");
        map.put("delete.user.amount", "1-99");
        map.put("delete-en", "Delete messages");
        map.put("settings", "Guild settings");
        map.put("settings.info", "Show current guild settings");
        map.put("settings.reload", "Reload guild settings");
        map.put("settings.reset", "Reset guild settings by section");
        map.put("settings.template", "Open template edit menu");
        map.put("settings.module", "Open module toggle menu");
        map.put("settings.logs", "Open logs channel menu");
        map.put("settings.music", "Open music settings menu");
        map.put("settings.language", "Set language");
        map.put("settings.language.code", "en or zh-TW");
        map.put("private-room-settings", "Manage your private room");
        map.put("warnings", "Manage warning counts");
        map.put("warnings.add", "Add warnings to user");
        map.put("warnings.remove", "Remove warnings from user");
        map.put("warnings.view", "View user warnings");
        map.put("warnings.clear", "Clear user warnings");
        map.put("warnings.user", "Target user");
        map.put("warnings.amount", "Warning amount");
        map.put("anti_duplicate", "Duplicate message detection settings");
        map.put("anti_duplicate.enable", "Enable or disable duplicate detection");
        map.put("anti_duplicate.status", "Show duplicate detection status");
        map.put("anti_duplicate.value", "true or false");
        map.put("number_chain", "Number chain settings");
        map.put("number_chain.set_channel", "Set number chain channel");
        map.put("number_chain.channel", "Text channel");
        map.put("number_chain.enable", "Enable or disable number chain");
        map.put("number_chain.value", "true or false");
        map.put("number_chain.status", "Show number chain status");
        map.put("number_chain.reset", "Reset number chain progress");
        map.put("ticket", "Ticket system");
        map.put("ticket.panel", "Send ticket panel");
        map.put("ticket.panel.channel", "Target text channel");
        map.put("ticket.close", "Close current ticket channel");
        map.put("ticket.close.reason", "Close reason");
        map.put("ticket.limit", "Set max open tickets per user");
        map.put("ticket.limit.value", "1-20");
        map.put("ticket.blacklist_add", "Add user to ticket blacklist");
        map.put("ticket.blacklist_add.user", "Target user");
        map.put("ticket.blacklist_remove", "Remove user from ticket blacklist");
        map.put("ticket.blacklist_remove.user", "Target user");
        map.put("ticket.blacklist_list", "Show ticket blacklist users");
        return map;
    }
}





