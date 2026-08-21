package com.norule.musicbot.service.shorturl;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded in-process protection for public short URL creation. Request windows and daily successful
 * creation counters intentionally reset when the process restarts; persisting anonymous daily quota
 * requires a repository schema that records a trusted creation identity.
 */
public final class ShortUrlCreationGuard {
    public static final int MAX_TRACKED_IDENTITIES = 20_000;

    private static final long ONE_MINUTE_MILLIS = 60_000L;
    private static final long TEN_MINUTES_MILLIS = 10L * ONE_MINUTE_MILLIS;
    private static final long STALE_BUCKET_MILLIS = 2L * 24L * 60L * 60L * 1000L;
    private static final long CLEANUP_INTERVAL_MILLIS = ONE_MINUTE_MILLIS;

    public enum Status {
        ALLOWED,
        REQUEST_RATE_LIMITED,
        DAILY_QUOTA_EXCEEDED,
        TRACKING_CAPACITY_EXCEEDED
    }

    public record Options(boolean enabled,
                          int anonymousPerMinute,
                          int anonymousPerTenMinutes,
                          int anonymousDailyCreates,
                          int authenticatedPerMinute,
                          int authenticatedPerTenMinutes,
                          int authenticatedDailyCreates) {
        public Options {
            anonymousPerMinute = Math.max(1, anonymousPerMinute);
            anonymousPerTenMinutes = Math.max(anonymousPerMinute, anonymousPerTenMinutes);
            anonymousDailyCreates = Math.max(1, anonymousDailyCreates);
            authenticatedPerMinute = Math.max(1, authenticatedPerMinute);
            authenticatedPerTenMinutes = Math.max(authenticatedPerMinute, authenticatedPerTenMinutes);
            authenticatedDailyCreates = Math.max(1, authenticatedDailyCreates);
        }

        public static Options defaults() {
            return new Options(true, 10, 50, 200, 30, 150, 500);
        }
    }

    public record Decision(Status status, long retryAfterSeconds) {
        public boolean allowed() {
            return status == Status.ALLOWED;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicReference<Options> options = new AtomicReference<>();
    private final AtomicLong lastCleanupAt = new AtomicLong();
    private final Object capacityLock = new Object();
    private final Clock clock;

    public ShortUrlCreationGuard(Options options) {
        this(options, Clock.systemUTC());
    }

    ShortUrlCreationGuard(Options options, Clock clock) {
        this.options.set(options == null ? Options.defaults() : options);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void updateOptions(Options options) {
        if (options != null) {
            this.options.set(options);
        }
    }

    public Decision checkRequest(String authenticatedUserId, String clientAddress) {
        Options current = options.get();
        if (!current.enabled()) {
            return allowedDecision();
        }

        long now = clock.millis();
        Subject subject = subject(authenticatedUserId, clientAddress);
        Bucket bucket = findOrCreateBucket(subject.key(), now);
        if (bucket == null) {
            return new Decision(Status.TRACKING_CAPACITY_EXCEEDED, 60L);
        }

        int perMinute = subject.authenticated()
                ? current.authenticatedPerMinute() : current.anonymousPerMinute();
        int perTenMinutes = subject.authenticated()
                ? current.authenticatedPerTenMinutes() : current.anonymousPerTenMinutes();
        synchronized (bucket) {
            bucket.lastSeenMillis = now;
            pruneRequests(bucket.requests, now);
            long minuteCutoff = now - ONE_MINUTE_MILLIS;
            int minuteRequests = 0;
            for (Long requestedAt : bucket.requests) {
                if (requestedAt > minuteCutoff) {
                    minuteRequests++;
                }
            }
            long retryAfterMillis = 0L;
            if (minuteRequests >= perMinute) {
                retryAfterMillis = retryAfterForWindow(bucket.requests, minuteCutoff, ONE_MINUTE_MILLIS, now);
            }
            if (bucket.requests.size() >= perTenMinutes) {
                retryAfterMillis = Math.max(retryAfterMillis,
                        retryAfterForWindow(bucket.requests, now - TEN_MINUTES_MILLIS, TEN_MINUTES_MILLIS, now));
            }
            if (retryAfterMillis > 0L) {
                return new Decision(Status.REQUEST_RATE_LIMITED, toRetryAfterSeconds(retryAfterMillis));
            }
            bucket.requests.addLast(now);
            return allowedDecision();
        }
    }

    public CreationPermit beginCreation(String authenticatedUserId, String clientAddress) {
        Options current = options.get();
        if (!current.enabled()) {
            return CreationPermit.allowedWithoutReservation();
        }

        long now = clock.millis();
        Subject subject = subject(authenticatedUserId, clientAddress);
        Bucket bucket = findOrCreateBucket(subject.key(), now);
        if (bucket == null) {
            return CreationPermit.denied(Status.TRACKING_CAPACITY_EXCEEDED, 60L);
        }

        int dailyLimit = subject.authenticated()
                ? current.authenticatedDailyCreates() : current.anonymousDailyCreates();
        synchronized (bucket) {
            bucket.lastSeenMillis = now;
            rotateDailyQuota(bucket, now);
            if (bucket.successfulCreates + bucket.pendingCreates >= dailyLimit) {
                return CreationPermit.denied(
                        Status.DAILY_QUOTA_EXCEEDED,
                        secondsUntilNextUtcDay(now)
                );
            }
            bucket.pendingCreates++;
            return new CreationPermit(bucket);
        }
    }

    int trackedIdentityCount() {
        return buckets.size();
    }

    private Bucket findOrCreateBucket(String key, long now) {
        Bucket existing = buckets.get(key);
        if (existing != null) {
            return existing;
        }
        cleanupStaleBuckets(now);
        synchronized (capacityLock) {
            existing = buckets.get(key);
            if (existing != null) {
                return existing;
            }
            if (buckets.size() >= MAX_TRACKED_IDENTITIES) {
                cleanupStaleBucketsNow(now);
                if (buckets.size() >= MAX_TRACKED_IDENTITIES) {
                    return null;
                }
            }
            Bucket created = new Bucket(now, utcDay(now));
            buckets.put(key, created);
            return created;
        }
    }

    private void cleanupStaleBuckets(long now) {
        long previous = lastCleanupAt.get();
        if (now - previous < CLEANUP_INTERVAL_MILLIS || !lastCleanupAt.compareAndSet(previous, now)) {
            return;
        }
        cleanupStaleBucketsNow(now);
    }

    private void cleanupStaleBucketsNow(long now) {
        buckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                return bucket.pendingCreates == 0 && now - bucket.lastSeenMillis > STALE_BUCKET_MILLIS;
            }
        });
    }

    private void pruneRequests(Deque<Long> requests, long now) {
        long cutoff = now - TEN_MINUTES_MILLIS;
        while (!requests.isEmpty() && requests.peekFirst() <= cutoff) {
            requests.removeFirst();
        }
    }

    private long retryAfterForWindow(Deque<Long> requests, long cutoff, long windowMillis, long now) {
        for (Long requestedAt : requests) {
            if (requestedAt > cutoff) {
                return Math.max(1L, requestedAt + windowMillis - now);
            }
        }
        return 1L;
    }

    private void rotateDailyQuota(Bucket bucket, long now) {
        LocalDate currentDay = utcDay(now);
        if (!currentDay.equals(bucket.utcDay)) {
            bucket.utcDay = currentDay;
            bucket.successfulCreates = 0;
        }
    }

    private LocalDate utcDay(long now) {
        return Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private long secondsUntilNextUtcDay(long now) {
        Instant nextDay = Instant.ofEpochMilli(now)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        long millis = ChronoUnit.MILLIS.between(Instant.ofEpochMilli(now), nextDay);
        return toRetryAfterSeconds(millis);
    }

    private long toRetryAfterSeconds(long millis) {
        return Math.max(1L, (millis + 999L) / 1000L);
    }

    private Subject subject(String authenticatedUserId, String clientAddress) {
        String userId = authenticatedUserId == null ? "" : authenticatedUserId.trim();
        if (!userId.isBlank()) {
            return new Subject("account:" + userId, true);
        }
        String address = clientAddress == null || clientAddress.isBlank()
                ? "unknown" : clientAddress.trim();
        return new Subject("ip:" + address, false);
    }

    private Decision allowedDecision() {
        return new Decision(Status.ALLOWED, 0L);
    }

    private record Subject(String key, boolean authenticated) {
    }

    private static final class Bucket {
        private final Deque<Long> requests = new ArrayDeque<>();
        private long lastSeenMillis;
        private LocalDate utcDay;
        private int successfulCreates;
        private int pendingCreates;

        private Bucket(long now, LocalDate utcDay) {
            this.lastSeenMillis = now;
            this.utcDay = utcDay;
        }
    }

    public static final class CreationPermit implements AutoCloseable {
        private final Status status;
        private final long retryAfterSeconds;
        private final Bucket bucket;
        private boolean finished;

        private CreationPermit(Bucket bucket) {
            this.status = Status.ALLOWED;
            this.retryAfterSeconds = 0L;
            this.bucket = bucket;
        }

        private CreationPermit(Status status, long retryAfterSeconds) {
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
            this.bucket = null;
            this.finished = true;
        }

        private static CreationPermit allowedWithoutReservation() {
            return new CreationPermit(Status.ALLOWED, 0L);
        }

        private static CreationPermit denied(Status status, long retryAfterSeconds) {
            return new CreationPermit(status, retryAfterSeconds);
        }

        public boolean allowed() {
            return status == Status.ALLOWED;
        }

        public Status status() {
            return status;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }

        public void commitSuccessfulCreation() {
            if (bucket == null || finished) {
                finished = true;
                return;
            }
            synchronized (bucket) {
                if (!finished) {
                    bucket.pendingCreates--;
                    bucket.successfulCreates++;
                    finished = true;
                }
            }
        }

        @Override
        public void close() {
            if (bucket == null || finished) {
                return;
            }
            synchronized (bucket) {
                if (!finished) {
                    bucket.pendingCreates--;
                    finished = true;
                }
            }
        }
    }
}
