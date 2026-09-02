package com.norule.musicbot.service.shorturl;

import com.norule.musicbot.shorturl.RateLimitStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

public final class RateLimitService {
    public record Options(boolean enabled,
                          int mediaPerMinutePerIp,
                          int mediaAuthenticatedPerMinutePerIp,
                          int mediaPerMinutePerUser,
                          int mediaPerDayPerUser,
                          int shortUrlPerMinutePerIp,
                          int shortUrlPerMinutePerUser,
                          int mediaConcurrencyPerIp,
                          int mediaConcurrencyPerUser) {
        public Options {
            mediaPerMinutePerIp = Math.max(1, mediaPerMinutePerIp);
            mediaAuthenticatedPerMinutePerIp = Math.max(
                    mediaPerMinutePerIp, mediaAuthenticatedPerMinutePerIp);
            mediaPerMinutePerUser = Math.max(1, mediaPerMinutePerUser);
            mediaPerDayPerUser = Math.max(mediaPerMinutePerUser, mediaPerDayPerUser);
            shortUrlPerMinutePerIp = Math.max(1, shortUrlPerMinutePerIp);
            shortUrlPerMinutePerUser = Math.max(1, shortUrlPerMinutePerUser);
            mediaConcurrencyPerIp = Math.max(1, mediaConcurrencyPerIp);
            mediaConcurrencyPerUser = Math.max(1, mediaConcurrencyPerUser);
        }

        public Options(boolean enabled,
                       int mediaPerMinutePerIp,
                       int mediaPerMinutePerUser,
                       int mediaPerDayPerUser,
                       int shortUrlPerMinutePerIp,
                       int shortUrlPerMinutePerUser,
                       int mediaConcurrencyPerIp,
                       int mediaConcurrencyPerUser) {
            this(enabled, mediaPerMinutePerIp, Math.max(60, mediaPerMinutePerIp),
                    mediaPerMinutePerUser, mediaPerDayPerUser,
                    shortUrlPerMinutePerIp, shortUrlPerMinutePerUser,
                    mediaConcurrencyPerIp, mediaConcurrencyPerUser);
        }

        public static Options defaults() {
            return new Options(true, 10, 60, 20, 200, 30, 60, 2, 3);
        }
    }

    public record Result(boolean allowed, long retryAfterSeconds) {
        public static Result allowedResult() {
            return new Result(true, 0L);
        }
    }

    public static final class UploadPermit implements AutoCloseable {
        private final boolean allowed;
        private final long retryAfterSeconds;
        private final RateLimitStore.ConcurrencyLease ipLease;
        private final RateLimitStore.ConcurrencyLease userLease;

        private UploadPermit(boolean allowed, long retryAfterSeconds,
                             RateLimitStore.ConcurrencyLease ipLease,
                             RateLimitStore.ConcurrencyLease userLease) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
            this.ipLease = ipLease;
            this.userLease = userLease;
        }

        public boolean allowed() {
            return allowed;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }

        @Override
        public void close() {
            if (userLease != null) {
                userLease.close();
            }
            if (ipLease != null) {
                ipLease.close();
            }
        }
    }

    private static final long MINUTE_MILLIS = 60_000L;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private final RateLimitStore store;
    private final Options options;
    private final Clock clock;

    public RateLimitService(RateLimitStore store, Options options) {
        this(store, options, Clock.systemUTC());
    }

    public RateLimitService(RateLimitStore store, Options options, Clock clock) {
        if (store == null) {
            throw new IllegalArgumentException("rate limit store cannot be null");
        }
        this.store = store;
        this.options = options == null ? Options.defaults() : options;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Result checkMediaUpload(String clientIp, String ownerId) {
        if (!options.enabled()) {
            return Result.allowedResult();
        }
        long now = clock.millis();
        boolean authenticated = ownerId != null && !ownerId.isBlank();
        int ipLimit = authenticated
                ? options.mediaAuthenticatedPerMinutePerIp()
                : options.mediaPerMinutePerIp();
        Result ip = consume(authenticated ? "media:ip:authenticated" : "media:ip:anonymous",
                clientIp, ipLimit, MINUTE_MILLIS, now);
        if (!ip.allowed()) {
            return ip;
        }
        if (!authenticated) {
            return Result.allowedResult();
        }
        Result userMinute = consume(
                "media:user:minute", ownerId, options.mediaPerMinutePerUser(), MINUTE_MILLIS, now);
        if (!userMinute.allowed()) {
            return userMinute;
        }
        // This persisted-independent counter represents every authenticated HTTP upload attempt,
        // including requests that later reuse an existing MediaShare.
        return consume("media:user:request:day", ownerId,
                options.mediaPerDayPerUser(), DAY_MILLIS, now);
    }

    public Result checkShortUrlCreation(String clientIp, String ownerId) {
        if (!options.enabled()) {
            return Result.allowedResult();
        }
        long now = clock.millis();
        Result ip = consume(
                "short:ip", clientIp, options.shortUrlPerMinutePerIp(), MINUTE_MILLIS, now);
        if (!ip.allowed() || ownerId == null || ownerId.isBlank()) {
            return ip;
        }
        return consume("short:user", ownerId, options.shortUrlPerMinutePerUser(), MINUTE_MILLIS, now);
    }

    public UploadPermit beginMediaUpload(String clientIp, String ownerId) {
        if (!options.enabled()) {
            return new UploadPermit(true, 0L, null, null);
        }
        long now = clock.millis();
        RateLimitStore.ConcurrencyLease ipLease = store.tryAcquire(
                key("media:concurrency:ip", clientIp), options.mediaConcurrencyPerIp(), now);
        if (!ipLease.acquired()) {
            return new UploadPermit(false, 1L, null, null);
        }
        RateLimitStore.ConcurrencyLease userLease = null;
        if (ownerId != null && !ownerId.isBlank()) {
            userLease = store.tryAcquire(key("media:concurrency:user", ownerId),
                    options.mediaConcurrencyPerUser(), now);
            if (!userLease.acquired()) {
                ipLease.close();
                return new UploadPermit(false, 1L, null, null);
            }
        }
        return new UploadPermit(true, 0L, ipLease, userLease);
    }

    private Result consume(String scope, String identity, int limit, long windowMillis, long now) {
        RateLimitStore.Consumption result = store.tryConsume(
                key(scope, identity), limit, windowMillis, now);
        return new Result(result.allowed(), result.retryAfterSeconds());
    }

    private String key(String scope, String identity) {
        String normalized = identity == null || identity.isBlank() ? "unknown" : identity.trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return scope + ':' + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
