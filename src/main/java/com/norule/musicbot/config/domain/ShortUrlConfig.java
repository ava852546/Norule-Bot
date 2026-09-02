package com.norule.musicbot.config.domain;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.service.shorturl.ImageShareService;
import com.norule.musicbot.service.shorturl.AnonymousDeviceIdentityService;
import com.norule.musicbot.service.shorturl.MediaPasswordAttemptGuard;
import com.norule.musicbot.service.shorturl.MediaQuotaService;
import com.norule.musicbot.service.shorturl.ShortUrlCreationGuard;
import com.norule.musicbot.service.shorturl.RateLimitService;
import java.util.Locale;
import java.util.List;

public final class ShortUrlConfig {
    public static final class Mysql {
        private final String jdbcUrl;
        private final String username;
        private final String password;
        private final int poolSize;

        public Mysql(String jdbcUrl, String username, String password, int poolSize) {
            this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
            this.poolSize = Math.max(1, poolSize);
        }

        public String getJdbcUrl() { return jdbcUrl; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
        public int getPoolSize() { return poolSize; }
    }

    public static final class Sqlite {
        private final String path;

        public Sqlite(String path) {
            this.path = path == null ? "data/norule.db" : path;
        }

        public String getPath() { return path; }
    }

    public static final class Image {
        private final boolean enabled;
        private final int defaultRetentionHours;
        private final int maxRetentionDays;
        private final int maxFileSizeMb;
        private final int maxVideoFileSizeMb;
        private final int maxVideoDurationSeconds;
        private final int expiredShareRetentionDays;
        private final String storagePath;

        public Image(boolean enabled,
                     int defaultRetentionHours,
                     int maxRetentionDays,
                     int maxFileSizeMb,
                     int maxVideoFileSizeMb,
                     int maxVideoDurationSeconds,
                     int expiredShareRetentionDays,
                     String storagePath) {
            this.enabled = enabled;
            this.maxRetentionDays = Math.max(1, Math.min(365, maxRetentionDays));
            this.defaultRetentionHours = Math.max(1, Math.min(this.maxRetentionDays * 24, defaultRetentionHours));
            this.maxFileSizeMb = Math.max(1, Math.min(20, maxFileSizeMb));
            this.maxVideoFileSizeMb = Math.max(1, Math.min(100, maxVideoFileSizeMb));
            this.maxVideoDurationSeconds = Math.max(1, Math.min(5 * 60, maxVideoDurationSeconds));
            this.expiredShareRetentionDays = Math.max(1, Math.min(365, expiredShareRetentionDays));
            this.storagePath = storagePath == null || storagePath.isBlank()
                    ? "data/short-url-images"
                    : storagePath.trim();
        }

        public boolean isEnabled() { return enabled; }
        public int getDefaultRetentionHours() { return defaultRetentionHours; }
        public int getMaxRetentionDays() { return maxRetentionDays; }
        public int getMaxFileSizeMb() { return maxFileSizeMb; }
        public int getMaxVideoFileSizeMb() { return maxVideoFileSizeMb; }
        public int getMaxVideoDurationSeconds() { return maxVideoDurationSeconds; }
        public int getExpiredShareRetentionDays() { return expiredShareRetentionDays; }
        public String getStoragePath() { return storagePath; }
    }

    private final String storage;
    private final boolean enabled;
    private final int bindPort;
    private final String publicBaseUrl;
    private final int codeLength;
    private final boolean allowPrivateTargets;
    private final boolean dedupe;
    private final int ttlDays;
    private final int cleanupIntervalMinutes;
    private final Image image;
    private final Mysql mysql;
    private final Sqlite sqlite;
    private final MediaPasswordAttemptGuard.Options passwordProtectionOptions;
    private final AnonymousDeviceIdentityService.Options identityContinuityOptions;
    private final MediaQuotaService.Options mediaQuotaOptions;
    private final ShortUrlCreationGuard.Options creationGuardOptions;
    private final RateLimitService.Options rateLimitOptions;
    private final List<String> trustedProxyCidrs;
    private final String legacyImageStoragePath;
    private final String temporaryStoragePath;
    private final String expiredArchivePath;
    private final int filesystemStopPercent;
    private final boolean allowDateDefaultPassword;
    private final int minPasswordLength;
    private final int maxPasswordLength;
    private final String quotaHmacSecret;
    private final String deviceHmacSecret;

    public ShortUrlConfig(boolean enabled,
                          int bindPort,
                          String publicBaseUrl,
                          int codeLength,
                          boolean allowPrivateTargets,
                          String storage,
                          boolean dedupe,
                          int ttlDays,
                          int cleanupIntervalMinutes,
                          Mysql mysql,
                          Sqlite sqlite) {
        this(enabled, bindPort, publicBaseUrl, codeLength, allowPrivateTargets, storage, dedupe,
                ttlDays, cleanupIntervalMinutes,
                new Image(true, 1, 365, 20, 100, 5 * 60, 30, "data/short-url-images"), mysql, sqlite);
    }

    public ShortUrlConfig(boolean enabled,
                          int bindPort,
                          String publicBaseUrl,
                          int codeLength,
                          boolean allowPrivateTargets,
                          String storage,
                          boolean dedupe,
                          int ttlDays,
                          int cleanupIntervalMinutes,
                          Image image,
                          Mysql mysql,
                          Sqlite sqlite) {
        this.enabled = enabled;
        this.bindPort = Math.max(1, bindPort);
        String normalizedBaseUrl = publicBaseUrl == null || publicBaseUrl.isBlank()
                ? "https://s.norule.me"
                : publicBaseUrl.trim();
        this.publicBaseUrl = normalizedBaseUrl.endsWith("/")
                ? normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1)
                : normalizedBaseUrl;
        this.codeLength = Math.max(4, Math.min(32, codeLength));
        this.allowPrivateTargets = allowPrivateTargets;
        this.storage = normalizeStorage(storage);
        this.dedupe = dedupe;
        this.ttlDays = Math.max(1, ttlDays);
        this.cleanupIntervalMinutes = Math.max(1, cleanupIntervalMinutes);
        this.image = image == null
                ? new Image(true, 1, 365, 20, 100, 5 * 60, 30, "data/short-url-images")
                : image;
        this.mysql = mysql == null ? new Mysql("", "", "", 8) : mysql;
        this.sqlite = sqlite == null ? new Sqlite("data/norule.db") : sqlite;
        this.passwordProtectionOptions = MediaPasswordAttemptGuard.Options.defaults();
        this.identityContinuityOptions = AnonymousDeviceIdentityService.Options.defaults();
        this.mediaQuotaOptions = MediaQuotaService.Options.defaults();
        this.creationGuardOptions = ShortUrlCreationGuard.Options.defaults();
        this.rateLimitOptions = RateLimitService.Options.defaults();
        this.trustedProxyCidrs = List.of("127.0.0.1/32", "::1/128");
        this.legacyImageStoragePath = this.image.getStoragePath();
        this.temporaryStoragePath = "data/tmp/uploads";
        this.expiredArchivePath = "data/short-url-expired";
        this.filesystemStopPercent = 80;
        this.allowDateDefaultPassword = true;
        this.minPasswordLength = 4;
        this.maxPasswordLength = 128;
        this.quotaHmacSecret = "";
        this.deviceHmacSecret = "";
    }

    public ShortUrlConfig(BotConfig.ShortUrl config) {
        BotConfig.ShortUrl source = config == null ? BotConfig.ShortUrl.defaultValues() : config;
        this.enabled = source.isEnabled();
        this.bindPort = source.getBindPort();
        this.publicBaseUrl = source.getPublicBaseUrl();
        this.codeLength = source.getCodeLength();
        this.allowPrivateTargets = source.isAllowPrivateTargets();
        this.storage = source.getStorage();
        this.dedupe = source.isDedupe();
        this.ttlDays = source.getTtlDays();
        this.cleanupIntervalMinutes = source.getCleanupIntervalMinutes();
        BotConfig.ShortUrl.Image.AbuseProtection abuse = source.getImage().getAbuseProtection();
        BotConfig.ShortUrl.Image.PasswordProtection password = abuse.getPasswordProtection();
        BotConfig.ShortUrl.Image.IdentityContinuity identity = abuse.getIdentityContinuity();
        BotConfig.ShortUrl.Image.Storage mediaStorage = abuse.getStorage();
        this.image = new Image(
                source.getImage().isEnabled(),
                source.getImage().getDefaultRetentionHours(),
                source.getImage().getMaxRetentionDays(),
                source.getImage().getMaxFileSizeMb(),
                source.getImage().getMaxVideoFileSizeMb(),
                source.getImage().getMaxVideoDurationSeconds(),
                source.getImage().getExpiredShareRetentionDays(),
                mediaStorage.getActivePath()
        );
        this.mysql = new Mysql(
                source.getMysql().getJdbcUrl(),
                source.getMysql().getUsername(),
                source.getMysql().getPassword(),
                source.getMysql().getPoolSize()
        );
        this.sqlite = new Sqlite(source.getSqlite().getPath());
        this.passwordProtectionOptions = new MediaPasswordAttemptGuard.Options(
                password.isEnabled(),
                password.getMaxFailedAttempts(),
                password.getFailureWindowMinutes() * 60L * 1000L,
                password.getLockMinutes() * 60L * 1000L,
                password.getBackoffInitialSeconds() * 1000L,
                password.getBackoffMultiplier(),
                password.getBackoffMaxSeconds() * 1000L,
                password.getMaxConcurrentVerifications(),
                password.getPerIp().getMaxVerificationRequestsPerMinute(),
                password.getPerIp().getMaxVerificationRequestsPer10Minutes()
        );
        this.identityContinuityOptions = new AnonymousDeviceIdentityService.Options(
                identity.isEnabled(),
                identity.getAnonymousToAccountMergeWindowMinutes() * 60L * 1000L,
                identity.getDeviceLinkTtlDays() * 24L * 60L * 60L * 1000L,
                identity.getDeviceAccountSwitchCooldownHours() * 60L * 60L * 1000L
        );
        MediaQuotaService.Options defaults = MediaQuotaService.Options.defaults();
        this.mediaQuotaOptions = new MediaQuotaService.Options(true, defaults.anonymous(),
                defaults.authenticated(), mediaStorage.getMaxTotalStorageGb() * 1024L * 1024L * 1024L);
        BotConfig.ShortUrl.CreationAbuseProtection creation = source.getCreationAbuseProtection();
        this.creationGuardOptions = new ShortUrlCreationGuard.Options(
                creation.isEnabled(),
                creation.getAnonymous().getMaxRequestsPerMinute(),
                creation.getAnonymous().getMaxRequestsPer10Minutes(),
                creation.getAnonymous().getMaxCreatesPerDay(),
                creation.getAuthenticated().getMaxRequestsPerMinute(),
                creation.getAuthenticated().getMaxRequestsPer10Minutes(),
                creation.getAuthenticated().getMaxCreatesPerDay()
        );
        BotConfig.ShortUrl.ApiRateLimit rateLimit = source.getApiRateLimit();
        this.rateLimitOptions = new RateLimitService.Options(
                rateLimit.isEnabled(),
                rateLimit.getMediaRequestsPerMinutePerIp(),
                rateLimit.getMediaAuthenticatedRequestsPerMinutePerIp(),
                rateLimit.getMediaRequestsPerMinutePerUser(),
                rateLimit.getMediaRequestsPerDayPerUser(),
                rateLimit.getShortUrlRequestsPerMinutePerIp(),
                rateLimit.getShortUrlRequestsPerMinutePerUser(),
                rateLimit.getMediaConcurrencyPerIp(),
                rateLimit.getMediaConcurrencyPerUser());
        this.trustedProxyCidrs = rateLimit.getTrustedProxyCidrs();
        this.legacyImageStoragePath = source.getImage().getStoragePath();
        this.temporaryStoragePath = mediaStorage.getTempPath();
        this.expiredArchivePath = mediaStorage.getExpiredArchivePath();
        this.filesystemStopPercent = mediaStorage.getFilesystemStopPercent();
        this.allowDateDefaultPassword = password.isAllowDateDefaultPassword();
        this.minPasswordLength = password.getMinPasswordLength();
        this.maxPasswordLength = password.getMaxPasswordLength();
        this.quotaHmacSecret = abuse.getSecrets().getQuotaHmacSecret();
        this.deviceHmacSecret = abuse.getSecrets().getDeviceHmacSecret();
    }

    public ShortUrlService.Options toOptions() {
        return new ShortUrlService.Options(
                dedupe,
                ttlDays * 24L * 60L * 60L * 1000L,
                cleanupIntervalMinutes * 60L * 1000L,
                publicBaseUrl,
                codeLength,
                allowPrivateTargets
        );
    }

    public ImageShareService.Options toImageShareOptions() {
        return new ImageShareService.Options(
                image.isEnabled(),
                image.getDefaultRetentionHours() * 60L * 60L * 1000L,
                image.getMaxRetentionDays() * 24L * 60L * 60L * 1000L,
                image.getMaxFileSizeMb() * 1024L * 1024L,
                image.getMaxVideoFileSizeMb() * 1024L * 1024L,
                image.getMaxVideoDurationSeconds() * 1000L,
                image.getExpiredShareRetentionDays() * 24L * 60L * 60L * 1000L,
                cleanupIntervalMinutes * 60L * 1000L,
                codeLength,
                allowDateDefaultPassword,
                minPasswordLength,
                maxPasswordLength,
                filesystemStopPercent
        );
    }

    public boolean isEnabled() { return enabled; }
    public int getBindPort() { return bindPort; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public int getCodeLength() { return codeLength; }
    public boolean isAllowPrivateTargets() { return allowPrivateTargets; }
    @Deprecated
    public int getPort() { return getBindPort(); }
    public String getStorage() { return storage; }
    public boolean isDedupe() { return dedupe; }
    public int getTtlDays() { return ttlDays; }
    public int getCleanupIntervalMinutes() { return cleanupIntervalMinutes; }
    public Image getImage() { return image; }
    public Mysql getMysql() { return mysql; }
    public Sqlite getSqlite() { return sqlite; }
    public MediaPasswordAttemptGuard.Options getPasswordProtectionOptions() { return passwordProtectionOptions; }
    public AnonymousDeviceIdentityService.Options getIdentityContinuityOptions() { return identityContinuityOptions; }
    public MediaQuotaService.Options getMediaQuotaOptions() { return mediaQuotaOptions; }
    public ShortUrlCreationGuard.Options getCreationGuardOptions() { return creationGuardOptions; }
    public RateLimitService.Options getRateLimitOptions() { return rateLimitOptions; }
    public List<String> getTrustedProxyCidrs() { return trustedProxyCidrs; }
    public String getLegacyImageStoragePath() { return legacyImageStoragePath; }
    public String getTemporaryStoragePath() { return temporaryStoragePath; }
    public String getExpiredArchivePath() { return expiredArchivePath; }
    public int getFilesystemStopPercent() { return filesystemStopPercent; }
    public String getQuotaHmacSecret() { return quotaHmacSecret; }
    public String getDeviceHmacSecret() { return deviceHmacSecret; }

    private static String normalizeStorage(String storage) {
        return storage == null ? "sqlite" : storage.trim().toLowerCase(Locale.ROOT);
    }
}
