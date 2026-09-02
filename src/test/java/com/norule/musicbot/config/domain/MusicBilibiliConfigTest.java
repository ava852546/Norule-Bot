package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicBilibiliConfigTest {
    @Test
    void defaultsUseBoundedCacheRateLimitAndCircuitBreaker() {
        MusicConfig.Bilibili config = MusicConfig.defaultValues().getBilibili();

        assertTrue(config.isEnabled());
        assertEquals("", config.getCookie());
        assertTrue(config.getMetadataCache().isEnabled());
        assertEquals(12, config.getMetadataCache().getTtlHours());
        assertEquals(1000, config.getMetadataCache().getMaxEntries());
        assertEquals(1, config.getRateLimit().getRequestsPerSecond());
        assertEquals(3, config.getRateLimit().getBurst());
        assertEquals(3, config.getCircuitBreaker().getFailureThreshold());
        assertEquals(60, config.getCircuitBreaker().getWindowSeconds());
        assertEquals(300, config.getCircuitBreaker().getCooldownSeconds());
    }

    @Test
    void mapsBilibiliSettingsIntoDomainConfig() {
        BotConfig.Music legacy = BotConfig.Music.fromMap(Map.of(
                "bilibili", Map.of(
                        "enabled", false,
                        "cookie", "test-cookie",
                        "metadataCache", Map.of("enabled", false, "ttlHours", 6, "maxEntries", 50),
                        "rateLimit", Map.of("enabled", false, "requestsPerSecond", 2, "burst", 4),
                        "circuitBreaker", Map.of(
                                "enabled", false,
                                "failureThreshold", 5,
                                "windowSeconds", 90,
                                "cooldownSeconds", 600
                        )
                )
        ), null);

        MusicConfig.Bilibili config = MusicConfig.fromLegacy(legacy, legacy).getBilibili();

        assertFalse(config.isEnabled());
        assertEquals("test-cookie", config.getCookie());
        assertFalse(config.getMetadataCache().isEnabled());
        assertEquals(6, config.getMetadataCache().getTtlHours());
        assertEquals(50, config.getMetadataCache().getMaxEntries());
        assertFalse(config.getRateLimit().isEnabled());
        assertEquals(2, config.getRateLimit().getRequestsPerSecond());
        assertEquals(4, config.getRateLimit().getBurst());
        assertFalse(config.getCircuitBreaker().isEnabled());
        assertEquals(5, config.getCircuitBreaker().getFailureThreshold());
        assertEquals(90, config.getCircuitBreaker().getWindowSeconds());
        assertEquals(600, config.getCircuitBreaker().getCooldownSeconds());
    }
}
