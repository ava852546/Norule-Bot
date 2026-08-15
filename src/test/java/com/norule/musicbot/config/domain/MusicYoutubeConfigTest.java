package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.domain.music.YouTubePlaybackBackend;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicYoutubeConfigTest {
    @Test
    void oauthCipherAndDifferentiatedCacheTtlsMapToDomainConfig() {
        BotConfig.Music parsed = BotConfig.Music.fromMap(
                Map.of(
                        "oauth", Map.of(
                                "enabled", true,
                                "refreshToken", "configured-refresh-token"
                        ),
                        "cipher", Map.of(
                                "enabled", true,
                                "server", "http://cipher.test:8001",
                                "password", "configured-password",
                                "userAgent", "configured-agent"
                        ),
                        "youtube", Map.of(
                                "strictPrecheck", Map.of(
                                        "cacheTtlHours", 12,
                                        "cache", Map.of(
                                                "playableTtlHours", 24,
                                                "temporaryFailureTtlMinutes", 15,
                                                "permanentFailureTtlHours", 48
                                        )
                                )
                        )
                ),
                BotConfig.Music.defaultValues()
        );

        MusicConfig config = MusicConfig.fromLegacy(parsed, parsed);

        assertTrue(config.getOauth().isEnabled());
        assertEquals("configured-refresh-token", config.getOauth().getRefreshToken());
        assertTrue(config.getCipher().isEnabled());
        assertEquals("http://cipher.test:8001", config.getCipher().getServer());
        assertEquals("configured-password", config.getCipher().getPassword());
        assertEquals("configured-agent", config.getCipher().getUserAgent());
        assertEquals(24, config.getYoutube().getStrictPrecheck().getPlayableTtlHours());
        assertEquals(15, config.getYoutube().getStrictPrecheck().getTemporaryFailureTtlMinutes());
        assertEquals(48, config.getYoutube().getStrictPrecheck().getPermanentFailureTtlHours());
    }

    @Test
    void companionPlaybackSettingsMapToDomainConfig() {
        BotConfig.Music.Youtube parsed = BotConfig.Music.Youtube.fromMap(
                Map.of(
                        "playbackBackend", "companion",
                        "companion", Map.of(
                                "enabled", true,
                                "url", "http://companion.test:8282/companion",
                                "secret", "ChangeMe12345678",
                                "fallbackToSource", false,
                                "connectTimeoutMillis", 2500,
                                "requestTimeoutMillis", 7500
                        )
                ),
                BotConfig.Music.Youtube.defaultValues()
        );

        MusicConfig.Youtube config = MusicConfig.Youtube.fromLegacy(parsed);

        assertEquals(YouTubePlaybackBackend.COMPANION, config.getPlaybackBackend());
        assertTrue(config.getCompanion().isEnabled());
        assertEquals("http://companion.test:8282/companion", config.getCompanion().getUrl());
        assertEquals("ChangeMe12345678", config.getCompanion().getSecret());
        assertFalse(config.getCompanion().isFallbackToSource());
        assertEquals(2500, config.getCompanion().getConnectTimeoutMillis());
        assertEquals(7500, config.getCompanion().getRequestTimeoutMillis());
    }
}
