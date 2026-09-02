package com.norule.musicbot.domain.music.bilibili;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliCircuitBreakerTest {
    @Test
    void opensAfterThreeFailuresAndRecoversThroughHalfOpenProbe() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        BilibiliCircuitBreaker breaker = new BilibiliCircuitBreaker(
                true, 3, Duration.ofSeconds(60), Duration.ofSeconds(300), clock);

        assertEquals(BilibiliCircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquirePermission());
        breaker.recordFailure(412);
        breaker.recordFailure(412);
        assertEquals(BilibiliCircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(BilibiliCircuitBreaker.Transition.OPENED, breaker.recordFailure(412));
        assertEquals(BilibiliCircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquirePermission());

        clock.advance(Duration.ofSeconds(300));
        assertEquals(BilibiliCircuitBreaker.State.HALF_OPEN, breaker.state());
        assertTrue(breaker.tryAcquirePermission());
        assertFalse(breaker.tryAcquirePermission());
        assertEquals(BilibiliCircuitBreaker.Transition.RECOVERED, breaker.recordSuccess());
        assertEquals(BilibiliCircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void halfOpenFailureReopensForAnotherCooldown() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        BilibiliCircuitBreaker breaker = new BilibiliCircuitBreaker(
                true, 1, Duration.ofSeconds(60), Duration.ofSeconds(300), clock);
        breaker.recordFailure(429);
        clock.advance(Duration.ofSeconds(300));

        assertTrue(breaker.tryAcquirePermission());
        assertEquals(BilibiliCircuitBreaker.Transition.REOPENED, breaker.recordFailure(429));
        assertEquals(BilibiliCircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquirePermission());
    }

    @Test
    void oldFailuresFallOutsideRollingWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        BilibiliCircuitBreaker breaker = new BilibiliCircuitBreaker(
                true, 3, Duration.ofSeconds(60), Duration.ofSeconds(300), clock);
        breaker.recordFailure(412);
        breaker.recordFailure(412);
        clock.advance(Duration.ofSeconds(61));

        breaker.recordFailure(412);

        assertEquals(BilibiliCircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(1, breaker.failureCount());
    }
}
