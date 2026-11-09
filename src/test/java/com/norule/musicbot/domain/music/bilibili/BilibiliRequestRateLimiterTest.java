package com.norule.musicbot.domain.music.bilibili;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void delayIsAdvisoryAndHandlesFractionalRefillsWithoutTakingAPermit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var limiter = new BilibiliRequestRateLimiter(true, 1, 1, clock);
        assertEquals(0L, limiter.nextPermitDelayMillis());
        assertTrue(limiter.tryAcquire());
        assertEquals(1000L, limiter.nextPermitDelayMillis());
        clock.advance(Duration.ofMillis(999));
        assertEquals(1L, limiter.nextPermitDelayMillis());
        assertFalse(limiter.tryAcquire());
        clock.advance(Duration.ofMillis(1));
        assertEquals(0L, limiter.nextPermitDelayMillis());
        assertTrue(limiter.tryAcquire());
    }
}
