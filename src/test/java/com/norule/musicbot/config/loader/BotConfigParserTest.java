package com.norule.musicbot.config.loader;

import com.norule.musicbot.config.BotConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotConfigParserTest {
    @TempDir
    Path tempDir;

    @Test
    void configApiKeyIsLoaded() throws IOException {
        BotConfig config = parse("""
                dictionary:
                  merriamWebster:
                    enabled: true
                    apiKey: "test-api-key"
                """, Map.of());

        assertEquals("test-api-key", config.getDictionary().getMerriamWebster().getApiKey());
        assertTrue(config.getDictionary().getMerriamWebster().isAvailable());
    }

    @Test
    void environmentApiKeyOverridesConfig() throws IOException {
        BotConfig config = parse("""
                dictionary:
                  merriamWebster:
                    enabled: true
                    apiKey: "config-test-key"
                """, Map.of("MERRIAM_WEBSTER_API_KEY", " environment-test-key "));

        assertEquals("environment-test-key", config.getDictionary().getMerriamWebster().getApiKey());
    }

    @Test
    void missingApiKeyDisablesFallback() throws IOException {
        BotConfig config = parse("""
                dictionary:
                  merriamWebster:
                    enabled: true
                    apiKey: ""
                """, Map.of());

        assertTrue(config.getDictionary().getMerriamWebster().isEnabled());
        assertFalse(config.getDictionary().getMerriamWebster().isAvailable());
    }

    @Test
    void missingDictionarySectionUsesDefaults() throws IOException {
        BotConfig.Dictionary dictionary = parse("", Map.of()).getDictionary();

        assertEquals(5, dictionary.getConnectTimeoutSeconds());
        assertEquals(5, dictionary.getRequestTimeoutSeconds());
        assertTrue(dictionary.getFreeDictionary().isEnabled());
        assertEquals(
                BotConfig.Dictionary.FreeDictionary.DEFAULT_ENDPOINT,
                dictionary.getFreeDictionary().getEndpoint()
        );
        assertFalse(dictionary.getMerriamWebster().isAvailable());
    }

    @Test
    void customEndpointsAreLoaded() throws IOException {
        BotConfig.Dictionary dictionary = parse("""
                dictionary:
                  freeDictionary:
                    endpoint: "https://free.example.test/entries/"
                  merriamWebster:
                    endpoint: "https://mw.example.test/entries/"
                    apiKey: "test-api-key"
                """, Map.of()).getDictionary();

        assertEquals("https://free.example.test/entries/", dictionary.getFreeDictionary().getEndpoint());
        assertEquals("https://mw.example.test/entries/", dictionary.getMerriamWebster().getEndpoint());
    }

    @Test
    void invalidTimeoutUsesSafeDefault() throws IOException {
        BotConfig.Dictionary dictionary = parse("""
                dictionary:
                  connectTimeoutSeconds: -1
                  requestTimeoutSeconds: "invalid"
                """, Map.of()).getDictionary();

        assertEquals(5, dictionary.getConnectTimeoutSeconds());
        assertEquals(5, dictionary.getRequestTimeoutSeconds());
    }

    @Test
    void missingDiscordLoginRetrySectionUsesEnabledDefaults() throws IOException {
        BotConfig.Discord.LoginRetry retry = parse("", Map.of()).getDiscord().getLoginRetry();

        assertTrue(retry.isEnabled());
        assertEquals(8, retry.getMaxAttempts());
        assertEquals(5, retry.getInitialDelaySeconds());
        assertEquals(60, retry.getMaxDelaySeconds());
    }

    @Test
    void customDiscordLoginRetrySettingsAreLoaded() throws IOException {
        BotConfig.Discord.LoginRetry retry = parse("""
                discord:
                  loginRetry:
                    enabled: false
                    maxAttempts: 4
                    initialDelaySeconds: 2
                    maxDelaySeconds: 20
                """, Map.of()).getDiscord().getLoginRetry();

        assertFalse(retry.isEnabled());
        assertEquals(4, retry.getMaxAttempts());
        assertEquals(2, retry.getInitialDelaySeconds());
        assertEquals(20, retry.getMaxDelaySeconds());
    }

    @Test
    void bilibiliEnvironmentOverridesConfigWithoutExposingCookie() throws IOException {
        BotConfig.Music.Bilibili bilibili = parse("""
                music:
                  bilibili:
                    enabled: false
                    cookie: "config-cookie"
                    metadataCache:
                      ttlHours: 12
                      maxEntries: 1000
                    rateLimit:
                      requestsPerSecond: 1
                      burst: 3
                    circuitBreaker:
                      failureThreshold: 3
                      windowSeconds: 60
                      cooldownSeconds: 300
                """, Map.ofEntries(
                Map.entry("BILIBILI_ENABLED", "true"),
                Map.entry("BILIBILI_COOKIE", "environment-cookie"),
                Map.entry("BILIBILI_METADATA_CACHE_ENABLED", "false"),
                Map.entry("BILIBILI_METADATA_CACHE_TTL_HOURS", "6"),
                Map.entry("BILIBILI_METADATA_CACHE_MAX_ENTRIES", "500"),
                Map.entry("BILIBILI_RATE_LIMIT_ENABLED", "false"),
                Map.entry("BILIBILI_RATE_LIMIT_RPS", "2"),
                Map.entry("BILIBILI_RATE_LIMIT_BURST", "4"),
                Map.entry("BILIBILI_CIRCUIT_BREAKER_ENABLED", "false"),
                Map.entry("BILIBILI_CIRCUIT_BREAKER_FAILURE_THRESHOLD", "5"),
                Map.entry("BILIBILI_CIRCUIT_BREAKER_WINDOW_SECONDS", "90"),
                Map.entry("BILIBILI_CIRCUIT_BREAKER_COOLDOWN_SECONDS", "600")
        )).getMusic().getBilibili();

        assertTrue(bilibili.isEnabled());
        assertEquals("environment-cookie", bilibili.getCookie());
        assertFalse(bilibili.getMetadataCache().isEnabled());
        assertEquals(6, bilibili.getMetadataCache().getTtlHours());
        assertEquals(500, bilibili.getMetadataCache().getMaxEntries());
        assertFalse(bilibili.getRateLimit().isEnabled());
        assertEquals(2, bilibili.getRateLimit().getRequestsPerSecond());
        assertEquals(4, bilibili.getRateLimit().getBurst());
        assertFalse(bilibili.getCircuitBreaker().isEnabled());
        assertEquals(5, bilibili.getCircuitBreaker().getFailureThreshold());
        assertEquals(90, bilibili.getCircuitBreaker().getWindowSeconds());
        assertEquals(600, bilibili.getCircuitBreaker().getCooldownSeconds());
    }

    private BotConfig parse(String extraYaml, Map<String, String> environment) throws IOException {
        Path configPath = tempDir.resolve("config-" + System.nanoTime() + ".yml");
        Files.writeString(
                configPath,
                "token: \"test-token\"\n" + extraYaml,
                StandardCharsets.UTF_8
        );
        return new BotConfigParser(environment::get).parse(configPath);
    }
}
