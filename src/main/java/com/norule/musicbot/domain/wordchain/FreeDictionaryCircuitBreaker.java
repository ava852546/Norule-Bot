package com.norule.musicbot.domain.wordchain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class FreeDictionaryCircuitBreaker {
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    public enum Transition {
        NONE,
        OPENED,
        REOPENED,
        RECOVERED
    }

    private final boolean enabled;
    private final int failureThreshold;
    private final Duration cooldown;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean halfOpenProbeInFlight;

    public FreeDictionaryCircuitBreaker(
            boolean enabled,
            int failureThreshold,
            Duration cooldown
    ) {
        this(enabled, failureThreshold, cooldown, Clock.systemUTC());
    }

    public FreeDictionaryCircuitBreaker(
            boolean enabled,
            int failureThreshold,
            Duration cooldown,
            Clock clock
    ) {
        this.enabled = enabled;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldown = positive(cooldown, Duration.ofSeconds(60));
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public synchronized boolean tryAcquirePermission() {
        if (!enabled) {
            return true;
        }
        refreshState(clock.instant());
        if (state == State.OPEN) {
            return false;
        }
        if (state == State.HALF_OPEN) {
            if (halfOpenProbeInFlight) {
                return false;
            }
            halfOpenProbeInFlight = true;
        }
        return true;
    }

    public synchronized Transition recordFailure() {
        if (!enabled) {
            return Transition.NONE;
        }
        Instant now = clock.instant();
        refreshState(now);
        if (state == State.HALF_OPEN) {
            consecutiveFailures = failureThreshold;
            open(now);
            return Transition.REOPENED;
        }
        if (state == State.OPEN) {
            return Transition.NONE;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open(now);
            return Transition.OPENED;
        }
        return Transition.NONE;
    }

    public synchronized Transition recordSuccess() {
        if (!enabled) {
            return Transition.NONE;
        }
        refreshState(clock.instant());
        if (state == State.HALF_OPEN) {
            close();
            return Transition.RECOVERED;
        }
        if (state == State.CLOSED) {
            consecutiveFailures = 0;
        }
        return Transition.NONE;
    }

    public synchronized State state() {
        if (enabled) {
            refreshState(clock.instant());
        }
        return state;
    }

    public synchronized int failureCount() {
        return consecutiveFailures;
    }

    private void refreshState(Instant now) {
        if (state == State.OPEN && openedAt != null && !now.isBefore(openedAt.plus(cooldown))) {
            state = State.HALF_OPEN;
            halfOpenProbeInFlight = false;
        }
    }

    private void open(Instant now) {
        state = State.OPEN;
        openedAt = now;
        halfOpenProbeInFlight = false;
    }

    private void close() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        openedAt = null;
        halfOpenProbeInFlight = false;
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
