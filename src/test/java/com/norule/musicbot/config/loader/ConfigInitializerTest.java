package com.norule.musicbot.config.loader;

import com.norule.musicbot.config.lang.LanguageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigInitializerTest {

    @TempDir
    Path tempDir;

    @Test
    void normalizesConfigOrderAndRemovesObsoleteValues() throws IOException {
        Path configPath = tempDir.resolve("config.yml");
        Files.writeString(configPath, """
                # Discord Bot Token (or set DISCORD_TOKEN env var)
                token: "test-token"
                guildSettingsDir: "legacy-guild-configs"
                languageDir: "legacy-lang"
                data:
                  cacheDir: "cache"
                bot:
                  activityType: WATCHING
                  activityText: "legacy activity"
                music:
                  youtube:
                    oauthEnabled: true # OAuth 啟用註解
                    oauthRefreshToken: "legacy-refresh-token"
                    cipherEnabled: true
                    # Cipher 伺服器註解
                    cipherServer: "http://cipher.example.com"
                    cipherPassword: "legacy-password"
                    cipherUserAgent: "legacy-agent"
                    auth:
                      mode: OAUTH
                      oauthRefreshToken: "auth-refresh-token"
                  spotify:
                    customTokenEndpoint: "https://token.example.com"
                web:
                  enabled: false
                  host: "127.0.0.1"
                  port: 62000
                  baseUrl: "https://dashboard.example.com"
                  ssl:
                    enabled: true
                    keyStorePath: "certs/web.p12"
                stats:
                  storage: "sqlite"
                  sqlite:
                    path: "data/message-stats.db"
                shortUrl:
                  enabled: false
                  bind:
                    host: "127.0.0.1"
                    port: 62001
                  public:
                    baseUrl: "https://short.example.com"
                """, StandardCharsets.UTF_8);

        ConfigInitializer initializer = new ConfigInitializer(new LanguageManager());
        initializer.initialize(configPath);

        Map<String, Object> root = readMap(configPath);
        assertEquals(List.of(
                "token",
                "discord",
                "prefix",
                "debug",
                "runtime-dependencies",
                "commandGuildId",
                "data",
                "defaultLanguage",
                "commandCooldownSeconds",
                "numberChainReactionDelayMillis",
                "bot",
                "developers",
                "music",
                "dictionary",
                "web",
                "database",
                "shortUrl",
                "minecraftStatus"
        ), List.copyOf(root.keySet()));

        Map<String, Object> data = asMap(root.get("data"));
        assertEquals("legacy-guild-configs", data.get("guildSettingsDir"));
        assertEquals("legacy-lang", data.get("languageDir"));
        assertFalse(data.containsKey("cacheDir"));
        assertFalse(root.containsKey("guildSettingsDir"));
        assertFalse(root.containsKey("languageDir"));
        assertFalse(root.containsKey("stats"));

        Map<String, Object> bot = asMap(root.get("bot"));
        assertEquals(List.of("WATCHING|legacy activity"), bot.get("activities"));
        assertFalse(bot.containsKey("activityType"));
        assertFalse(bot.containsKey("activityText"));

        Map<String, Object> web = asMap(root.get("web"));
        Map<String, Object> webBind = asMap(web.get("bind"));
        assertEquals(62000, webBind.get("port"));
        assertFalse(webBind.containsKey("host"));
        assertFalse(web.containsKey("host"));
        assertFalse(web.containsKey("ssl"));

        Map<String, Object> shortUrl = asMap(root.get("shortUrl"));
        assertEquals(62001, shortUrl.get("bindPort"));
        assertEquals("https://short.example.com", shortUrl.get("publicBaseUrl"));
        assertFalse(shortUrl.containsKey("bindHost"));
        assertFalse(shortUrl.containsKey("bind"));
        assertFalse(shortUrl.containsKey("public"));

        Map<String, Object> music = asMap(root.get("music"));
        Map<String, Object> youtube = asMap(music.get("youtube"));
        Map<String, Object> companion = asMap(youtube.get("companion"));
        assertEquals("YOUTUBE_SOURCE", youtube.get("playbackBackend"));
        assertEquals(false, companion.get("enabled"));
        assertEquals("http://127.0.0.1:8282", companion.get("url"));
        assertEquals(true, companion.get("fallbackToSource"));
        assertFalse(youtube.containsKey("auth"));
        assertFalse(youtube.containsKey("oauthEnabled"));
        assertFalse(youtube.containsKey("oauthRefreshToken"));
        assertFalse(youtube.containsKey("cipherEnabled"));
        assertFalse(youtube.containsKey("cipherServer"));
        assertFalse(youtube.containsKey("cipherPassword"));
        assertFalse(youtube.containsKey("cipherUserAgent"));

        Map<String, Object> oauth = asMap(music.get("oauth"));
        assertEquals(true, oauth.get("enabled"));
        assertEquals("legacy-refresh-token", oauth.get("refreshToken"));

        Map<String, Object> cipher = asMap(music.get("cipher"));
        assertEquals(true, cipher.get("enabled"));
        assertEquals("http://cipher.example.com", cipher.get("server"));
        assertEquals("legacy-password", cipher.get("password"));
        assertEquals("legacy-agent", cipher.get("userAgent"));

        Map<String, Object> spotify = asMap(music.get("spotify"));
        assertFalse(spotify.containsKey("customTokenEndpoint"));

        String normalized = Files.readString(configPath, StandardCharsets.UTF_8);
        assertTrue(normalized.contains("# Startup-only YouTube playback backend"));
        assertTrue(normalized.contains("# Enable Companion API use."));
        assertTrue(normalized.contains("# Companion SERVER_SECRET_KEY"));
        assertTrue(normalized.contains("enabled: true # OAuth 啟用註解"));
        assertTrue(normalized.contains("# Cipher 伺服器註解\n    server: 'http://cipher.example.com'"));
        initializer.initialize(configPath);
        assertEquals(normalized, Files.readString(configPath, StandardCharsets.UTF_8));
    }

    @Test
    void preservesExistingChineseCommentsWhenUpdatingConfig() throws IOException {
        Path configPath = tempDir.resolve("config.yml");
        String tokenComment = "\u6a5f\u5668\u4eba\u6b0a\u6756\uff0c\u8acb\u52ff\u5916\u6d41";
        String inlineComment = "\u4e2d\u6587\u884c\u5c3e\u8a3b\u89e3";
        String prefixComment = "\u6307\u4ee4\u524d\u7db4\u8aaa\u660e";
        String activityComment = "\u8f2a\u64ad\u6d3b\u52d5\u8a3b\u89e3";
        String youtubeComment = "YouTube \u4e2d\u6587\u8a3b\u89e3";
        String trailingComment = "\u6a94\u6848\u7d50\u5c3e\u8a3b\u89e3";
        Files.writeString(configPath, """
                # %s
                token: "test-token" # %s
                # %s
                prefix: "?"
                bot:
                  activities:
                    # %s
                    - "CUSTOM|NoRule Music Bot"
                music:
                  # %s
                  youtube:
                    auth:
                      mode: NONE
                # %s
                """.formatted(
                tokenComment,
                inlineComment,
                prefixComment,
                activityComment,
                youtubeComment,
                trailingComment
        ), StandardCharsets.UTF_8);

        ConfigInitializer initializer = new ConfigInitializer(new LanguageManager());
        initializer.initialize(configPath);

        String updated = Files.readString(configPath, StandardCharsets.UTF_8);
        assertTrue(updated.contains("# " + tokenComment + "\ntoken: 'test-token' # " + inlineComment));
        assertTrue(updated.contains("# " + prefixComment + "\nprefix: '?'"));
        assertTrue(updated.contains("# " + activityComment));
        assertTrue(updated.contains("# " + youtubeComment + "\n  youtube:"));
        assertTrue(updated.endsWith("# " + trailingComment + "\n"));
        assertEquals("?", readMap(configPath).get("prefix"));

        initializer.initialize(configPath);
        assertEquals(updated, Files.readString(configPath, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return (Map<String, Object>) new Yaml().load(reader);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
