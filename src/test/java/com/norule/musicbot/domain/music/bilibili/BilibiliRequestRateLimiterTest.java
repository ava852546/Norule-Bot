package com.norule.musicbot.domain.music.bilibili;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliRequestRateLimiterTest {
    @Test
    void permitsConfiguredBurstThenRefillsWithoutSleeping() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        BilibiliRequestRateLimiter limiter = new BilibiliRequestRateLimiter(true, 1, 3, clock);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        clock.advance(Duration.ofSeconds(1));
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }
}
