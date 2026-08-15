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
                web:
                  enabled: false
                  host: "127.0.0.1"
                  port: 62000
                  baseUrl: "https://dashboard.example.com"
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

        Map<String, Object> shortUrl = asMap(root.get("shortUrl"));
        assertEquals("127.0.0.1", shortUrl.get("bindHost"));
        assertEquals(62001, shortUrl.get("bindPort"));
        assertEquals("https://short.example.com", shortUrl.get("publicBaseUrl"));
        assertFalse(shortUrl.containsKey("bind"));
        assertFalse(shortUrl.containsKey("public"));

        Map<String, Object> music = asMap(root.get("music"));
        Map<String, Object> youtube = asMap(music.get("youtube"));
        Map<String, Object> companion = asMap(youtube.get("companion"));
        assertEquals("YOUTUBE_SOURCE", youtube.get("playbackBackend"));
        assertEquals(false, companion.get("enabled"));
        assertEquals("http://127.0.0.1:8282", companion.get("url"));
        assertEquals(true, companion.get("fallbackToSource"));

        String normalized = Files.readString(configPath, StandardCharsets.UTF_8);
        assertTrue(normalized.contains("# Startup-only YouTube playback backend"));
        assertTrue(normalized.contains("# Enable Companion API use."));
        assertTrue(normalized.contains("# Companion SERVER_SECRET_KEY"));
        initializer.initialize(configPath);
        assertEquals(normalized, Files.readString(configPath, StandardCharsets.UTF_8));
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
