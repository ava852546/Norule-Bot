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
    void nestedPotAuthAndDifferentiatedCacheTtlsMapToDomainConfig() {
        BotConfig.Music.Youtube parsed = BotConfig.Music.Youtube.fromMap(
                Map.of(
                        "auth", Map.of(
                                "mode", "POT",
                                "strictAuthConfig", true,
                                "poToken", "configured-token",
                                "visitorData", "configured-visitor"
                        ),
                        "strictPrecheck", Map.of(
                                "cacheTtlHours", 12,
                                "cache", Map.of(
                                        "playableTtlHours", 24,
                                        "temporaryFailureTtlMinutes", 15,
                                        "permanentFailureTtlHours", 48
                                )
                        )
                ),
                BotConfig.Music.Youtube.defaultValues()
        );

        MusicConfig.Youtube config = MusicConfig.Youtube.fromLegacy(parsed);

        assertEquals(MusicConfig.Youtube.AuthMode.POT, config.getAuth().getMode());
        assertTrue(config.getAuth().isStrictAuthConfig());
        assertEquals("configured-token", config.getAuth().getPoToken());
        assertEquals("configured-visitor", config.getAuth().getVisitorData());
        assertEquals(24, config.getStrictPrecheck().getPlayableTtlHours());
        assertEquals(15, config.getStrictPrecheck().getTemporaryFailureTtlMinutes());
        assertEquals(48, config.getStrictPrecheck().getPermanentFailureTtlHours());
    }

    @Test
    void legacyOauthKeysRemainBackwardCompatible() {
        BotConfig.Music.Youtube parsed = BotConfig.Music.Youtube.fromMap(
                Map.of(
                        "oauthEnabled", true,
                        "oauthRefreshToken", "legacy-refresh-token"
                ),
                BotConfig.Music.Youtube.defaultValues()
        );

        MusicConfig.Youtube config = MusicConfig.Youtube.fromLegacy(parsed);

        assertEquals(MusicConfig.Youtube.AuthMode.OAUTH, config.getAuth().getMode());
        assertEquals("legacy-refresh-token", config.getAuth().getOauthRefreshToken());
        assertFalse(config.getAuth().isStrictAuthConfig());
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
