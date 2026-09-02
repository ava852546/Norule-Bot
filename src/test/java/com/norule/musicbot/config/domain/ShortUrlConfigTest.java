package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.service.shorturl.ImageShareService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortUrlConfigTest {
    @Test
    void defaultsVideoUploadsToOneHundredMegabytesAndFiveMinutes() {
        ImageShareService.Options options = new ShortUrlConfig(null).toImageShareOptions();

        assertEquals(100L * 1024L * 1024L, options.maxVideoFileSizeBytes());
        assertEquals(5L * 60L * 1000L, options.maxVideoDurationMillis());
        assertEquals(20L * 1024L * 1024L, options.maxFileSizeBytes());
        assertEquals(30L * 24L * 60L * 60L * 1000L, options.expiredShareRetentionMillis());
    }

    @Test
    void mapsPasswordIdentityAndArchiveConfiguration() {
        BotConfig.ShortUrl parsed = BotConfig.ShortUrl.fromMap(Map.of(
                "image", Map.of(
                        "abuseProtection", Map.of(
                                "passwordProtection", Map.of(
                                        "allowDateDefaultPassword", false,
                                        "minPasswordLength", 8,
                                        "maxPasswordLength", 64,
                                        "maxConcurrentVerifications", 3
                                ),
                                "identityContinuity", Map.of(
                                        "anonymousToAccountMergeWindowMinutes", 90
                                ),
                                "storage", Map.of(
                                        "activePath", "data/active-media",
                                        "tempPath", "data/media-tmp",
                                        "expiredArchivePath", "data/media-archive",
                                        "filesystemStopPercent", 85
                                ),
                                "secrets", Map.of(
                                        "quotaHmacSecret", " quota-secret ",
                                        "deviceHmacSecret", " device-secret "
                                )
                        )
                )
        ), BotConfig.ShortUrl.defaultValues());
        ShortUrlConfig config = new ShortUrlConfig(parsed);
        ImageShareService.Options image = config.toImageShareOptions();

        assertFalse(image.allowDateDefaultPassword());
        assertEquals(8, image.minPasswordLength());
        assertEquals(64, image.maxPasswordLength());
        assertEquals(3, config.getPasswordProtectionOptions().maxConcurrentVerifications());
        assertEquals(90L * 60L * 1000L, config.getIdentityContinuityOptions().mergeWindowMillis());
        assertEquals("data/active-media", config.getImage().getStoragePath());
        assertEquals("data/media-tmp", config.getTemporaryStoragePath());
        assertEquals("data/media-archive", config.getExpiredArchivePath());
        assertEquals(85, image.filesystemStopPercent());
        assertEquals("quota-secret", config.getQuotaHmacSecret());
        assertEquals("device-secret", config.getDeviceHmacSecret());
    }

    @Test
    void keepsLegacyImageStoragePathWhenNestedStorageUsesDefault() {
        BotConfig.ShortUrl parsed = BotConfig.ShortUrl.fromMap(Map.of(
                "image", Map.of(
                        "storagePath", "data/custom-active",
                        "abuseProtection", Map.of(
                                "storage", Map.of("activePath", "data/short-url-images")
                        )
                )
        ), BotConfig.ShortUrl.defaultValues());

        ShortUrlConfig config = new ShortUrlConfig(parsed);
        assertEquals("data/custom-active", config.getImage().getStoragePath());
        assertEquals("data/custom-active", config.getLegacyImageStoragePath());
    }

    @Test
    void exposesLegacyStoragePathWhenNestedActivePathMoves() {
        BotConfig.ShortUrl parsed = BotConfig.ShortUrl.fromMap(Map.of(
                "image", Map.of(
                        "storagePath", "data/old-active",
                        "abuseProtection", Map.of(
                                "storage", Map.of("activePath", "data/new-active")
                        )
                )
        ), BotConfig.ShortUrl.defaultValues());

        ShortUrlConfig config = new ShortUrlConfig(parsed);
        assertEquals("data/new-active", config.getImage().getStoragePath());
        assertEquals("data/old-active", config.getLegacyImageStoragePath());
    }

    @Test
    void mapsCreationAbuseProtectionAndClampsUnsafeValues() {
        BotConfig.ShortUrl parsed = BotConfig.ShortUrl.fromMap(Map.of(
                "abuseProtection", Map.of(
                        "creation", Map.of(
                                "enabled", true,
                                "anonymous", Map.of(
                                        "maxRequestsPerMinute", -10,
                                        "maxRequestsPer10Minutes", 2,
                                        "maxCreatesPerDay", 20
                                ),
                                "authenticated", Map.of(
                                        "maxRequestsPerMinute", 40,
                                        "maxRequestsPer10Minutes", 200,
                                        "maxCreatesPerDay", 600
                                )
                        )
                )
        ), BotConfig.ShortUrl.defaultValues());

        var options = new ShortUrlConfig(parsed).getCreationGuardOptions();

        assertTrue(options.enabled());
        assertEquals(1, options.anonymousPerMinute());
        assertEquals(2, options.anonymousPerTenMinutes());
        assertEquals(20, options.anonymousDailyCreates());
        assertEquals(40, options.authenticatedPerMinute());
        assertEquals(200, options.authenticatedPerTenMinutes());
        assertEquals(600, options.authenticatedDailyCreates());
    }

    @Test
    void mapsApiRateLimitsConcurrencyAndTrustedProxies() {
        BotConfig.ShortUrl parsed = BotConfig.ShortUrl.fromMap(Map.of(
                "abuseProtection", Map.of(
                        "rateLimit", Map.of(
                                "enabled", true,
                                "mediaRequestsPerMinutePerIp", 11,
                                "mediaAuthenticatedRequestsPerMinutePerIp", 71,
                                "mediaRequestsPerMinutePerUser", 21,
                                "mediaRequestsPerDayPerUser", 201,
                                "shortUrlRequestsPerMinutePerIp", 31,
                                "shortUrlRequestsPerMinutePerUser", 61,
                                "mediaConcurrencyPerIp", 2,
                                "mediaConcurrencyPerUser", 3,
                                "trustedProxyCidrs", java.util.List.of("127.0.0.1/32", "10.0.0.0/8")
                        )
                )
        ), BotConfig.ShortUrl.defaultValues());

        ShortUrlConfig config = new ShortUrlConfig(parsed);
        var options = config.getRateLimitOptions();

        assertTrue(options.enabled());
        assertEquals(11, options.mediaPerMinutePerIp());
        assertEquals(71, options.mediaAuthenticatedPerMinutePerIp());
        assertEquals(21, options.mediaPerMinutePerUser());
        assertEquals(201, options.mediaPerDayPerUser());
        assertEquals(31, options.shortUrlPerMinutePerIp());
        assertEquals(61, options.shortUrlPerMinutePerUser());
        assertEquals(2, options.mediaConcurrencyPerIp());
        assertEquals(3, options.mediaConcurrencyPerUser());
        assertEquals(java.util.List.of("127.0.0.1/32", "10.0.0.0/8"),
                config.getTrustedProxyCidrs());
    }
}
