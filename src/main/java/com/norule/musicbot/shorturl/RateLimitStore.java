package com.norule.musicbot.shorturl;

public interface RateLimitStore {
    record Consumption(boolean allowed, long retryAfterSeconds) {
    }

    interface ConcurrencyLease extends AutoCloseable {
        boolean acquired();

        @Override
        void close();
    }

    Consumption tryConsume(String key, int limit, long windowMillis, long nowMillis);

    ConcurrencyLease tryAcquire(String key, int maximumConcurrency, long nowMillis);
}
