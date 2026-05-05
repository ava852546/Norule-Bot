package com.norule.musicbot.config.loader;

import com.norule.musicbot.config.BotConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class BotConfigParser {
    BotConfig parse(Path path) {
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
            boolean debug = getBoolean(root, "debug", false);
            Long commandGuildId = toLong(root.get("commandGuildId"));
            BotConfig.DataPaths dataPaths = BotConfig.DataPaths.fromConfig(root);
            String guildSettingsDir = dataPaths.getGuildSettingsDir();
            String languageDir = dataPaths.getLanguageDir();
            String defaultLanguage = getString(root, "defaultLanguage", "en");
            int commandCooldownSeconds = Math.max(0, getInt(root, "commandCooldownSeconds", 3));
            int numberChainReactionDelayMillis = Math.max(0, getInt(root, "numberChainReactionDelayMillis", 500));
            BotConfig.BotProfile botProfile = BotConfig.BotProfile.fromMap(asMap(root.get("bot")), null);
            BotConfig.Developers developers = BotConfig.Developers.fromMap(asMap(root.get("developers")), null);
            BotConfig.Notifications notifications = BotConfig.Notifications.fromMap(asMap(root.get("notifications")), null);
            BotConfig.Welcome welcome = BotConfig.Welcome.fromMap(asMap(root.get("welcome")), null);
            BotConfig.MessageLogs messageLogs = BotConfig.MessageLogs.fromMap(asMap(root.get("messageLogs")), null);
            BotConfig.Music music = BotConfig.Music.fromMap(asMap(root.get("music")), null);
            BotConfig.PrivateRoom privateRoom = BotConfig.PrivateRoom.fromMap(asMap(root.get("privateRoom")), null);
            BotConfig.Ticket ticket = BotConfig.Ticket.fromMap(asMap(root.get("ticket")), null);
            Map<String, Object> sharedDatabase = asMap(root.get("database"));
            Map<String, Object> shortUrlMap = applySharedDatabase(asMap(root.get("shortUrl")), sharedDatabase);
            Map<String, Object> statsMap = applySharedDatabase(asMap(root.get("stats")), sharedDatabase);
            BotConfig.ShortUrl shortUrl = BotConfig.ShortUrl.fromMap(shortUrlMap, null);
            BotConfig.Web web = BotConfig.Web.fromMap(asMap(root.get("web")), null);
            BotConfig.Stats stats = BotConfig.Stats.fromMap(statsMap, null);

            return new BotConfig(
                    token,
                    prefix,
                    debug,
                    commandGuildId,
                    guildSettingsDir,
                    languageDir,
                    dataPaths,
                    defaultLanguage,
                    commandCooldownSeconds,
                    numberChainReactionDelayMillis,
                    botProfile,
                    developers,
                    notifications,
                    welcome,
                    messageLogs,
                    music,
                    privateRoom,
                    ticket,
                    shortUrl,
                    web,
                    stats
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read config.yml: " + path.toAbsolutePath(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object object) {
        if (object instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
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
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
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

    private static String nullToEmpty(String text) {
        return text == null ? "" : text.trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applySharedDatabase(Map<String, Object> module, Map<String, Object> sharedDatabase) {
        if (sharedDatabase == null || sharedDatabase.isEmpty()) {
            return module;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (module != null && !module.isEmpty()) {
            result.putAll(module);
        }
        Object storage = sharedDatabase.get("storage");
        if (storage != null) {
            result.put("storage", String.valueOf(storage));
        }
        Object mysql = sharedDatabase.get("mysql");
        if (mysql instanceof Map<?, ?> mysqlMap) {
            result.put("mysql", new LinkedHashMap<>((Map<String, Object>) mysqlMap));
        }
        Object sqlite = sharedDatabase.get("sqlite");
        if (sqlite instanceof Map<?, ?> sqliteMap) {
            result.put("sqlite", new LinkedHashMap<>((Map<String, Object>) sqliteMap));
        }
        return result;
    }
}
