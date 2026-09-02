package com.norule.musicbot.shorturl;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryRateLimitStore implements RateLimitStore {
    public static final int MAX_TRACKED_KEYS = 50_000;
    private static final long STALE_MILLIS = 2L * 24L * 60L * 60L * 1000L;
    private static final long CLEANUP_INTERVAL_MILLIS = 60_000L;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupAt = new AtomicLong();
    private final Object capacityLock = new Object();

    @Override
    public Consumption tryConsume(String key, int limit, long windowMillis, long nowMillis) {
        Bucket bucket = findOrCreate(key, nowMillis);
        if (bucket == null) {
            return new Consumption(false, 60L);
        }
        long safeWindow = Math.max(1L, windowMillis);
        int safeLimit = Math.max(1, limit);
        synchronized (bucket) {
            bucket.lastSeenMillis = nowMillis;
            long cutoff = nowMillis - safeWindow;
            while (!bucket.requests.isEmpty() && bucket.requests.peekFirst() <= cutoff) {
                bucket.requests.removeFirst();
            }
            if (bucket.requests.size() >= safeLimit) {
                long retryMillis = Math.max(1L, bucket.requests.peekFirst() + safeWindow - nowMillis);
                return new Consumption(false, Math.max(1L, (retryMillis + 999L) / 1000L));
            }
            bucket.requests.addLast(nowMillis);
            return new Consumption(true, 0L);
        }
    }

    @Override
    public ConcurrencyLease tryAcquire(String key, int maximumConcurrency, long nowMillis) {
        Bucket bucket = findOrCreate(key, nowMillis);
        if (bucket == null) {
            return DeniedLease.INSTANCE;
        }
        synchronized (bucket) {
            bucket.lastSeenMillis = nowMillis;
            if (bucket.active >= Math.max(1, maximumConcurrency)) {
                return DeniedLease.INSTANCE;
            }
            bucket.active++;
            return new AcquiredLease(bucket);
        }
    }

    private Bucket findOrCreate(String key, long nowMillis) {
        String safeKey = key == null || key.isBlank() ? "unknown" : key;
        Bucket existing = buckets.get(safeKey);
        if (existing != null) {
            return existing;
        }
        cleanup(nowMillis);
        synchronized (capacityLock) {
            existing = buckets.get(safeKey);
            if (existing != null) {
                return existing;
            }
            if (buckets.size() >= MAX_TRACKED_KEYS) {
                cleanupNow(nowMillis);
                if (buckets.size() >= MAX_TRACKED_KEYS) {
                    return null;
                }
            }
            Bucket created = new Bucket(nowMillis);
            buckets.put(safeKey, created);
            return created;
        }
    }

    private void cleanup(long nowMillis) {
        long previous = lastCleanupAt.get();
        if (nowMillis - previous >= CLEANUP_INTERVAL_MILLIS
                && lastCleanupAt.compareAndSet(previous, nowMillis)) {
            cleanupNow(nowMillis);
        }
    }

    private void cleanupNow(long nowMillis) {
        buckets.entrySet().removeIf(entry -> {
            Bucket bucket = entry.getValue();
            synchronized (bucket) {
                return bucket.active == 0 && nowMillis - bucket.lastSeenMillis > STALE_MILLIS;
            }
        });
    }

    private static final class Bucket {
        private final Deque<Long> requests = new ArrayDeque<>();
        private long lastSeenMillis;
        private int active;

        private Bucket(long nowMillis) {
            this.lastSeenMillis = nowMillis;
        }
    }

    private static final class AcquiredLease implements ConcurrencyLease {
        private final Bucket bucket;
        private final AtomicBoolean closed = new AtomicBoolean();

        private AcquiredLease(Bucket bucket) {
            this.bucket = bucket;
        }

        @Override
        public boolean acquired() {
            return true;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                synchronized (bucket) {
                    bucket.active = Math.max(0, bucket.active - 1);
                }
            }
        }
    }

    private enum DeniedLease implements ConcurrencyLease {
        INSTANCE;

        @Override
        public boolean acquired() {
            return false;
        }

        @Override
        public void close() {
            // No permit was acquired.
        }
    }
}
