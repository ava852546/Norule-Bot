package com.norule.musicbot.domain.music.bilibili;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

public final class BilibiliCircuitBreaker {
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

    private final Clock clock;
    private final Deque<Instant> failures = new ArrayDeque<>();
    private boolean enabled;
    private int failureThreshold;
    private Duration failureWindow;
    private Duration cooldown;
    private State state = State.CLOSED;
    private Instant openedAt;
    private boolean halfOpenProbeInFlight;
    private int lastFailureStatus;

    public BilibiliCircuitBreaker(boolean enabled,
                                  int failureThreshold,
                                  Duration failureWindow,
                                  Duration cooldown) {
        this(enabled, failureThreshold, failureWindow, cooldown, Clock.systemUTC());
    }

    public BilibiliCircuitBreaker(boolean enabled,
                                  int failureThreshold,
                                  Duration failureWindow,
                                  Duration cooldown,
                                  Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        updateConfig(enabled, failureThreshold, failureWindow, cooldown);
    }

    public synchronized void updateConfig(boolean enabled,
                                          int failureThreshold,
                                          Duration failureWindow,
                                          Duration cooldown) {
        this.enabled = enabled;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.failureWindow = positive(failureWindow, Duration.ofSeconds(60));
        this.cooldown = positive(cooldown, Duration.ofSeconds(300));
        if (!enabled) {
            close();
        } else {
            pruneFailures(clock.instant());
        }
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

    public synchronized Transition recordFailure(int httpStatus) {
        if (!enabled) {
            return Transition.NONE;
        }
        Instant now = clock.instant();
        refreshState(now);
        lastFailureStatus = httpStatus;
        if (state == State.HALF_OPEN) {
            open(now);
            return Transition.REOPENED;
        }
        if (state == State.OPEN) {
            return Transition.NONE;
        }
        pruneFailures(now);
        failures.addLast(now);
        if (failures.size() >= failureThreshold) {
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
        return Transition.NONE;
    }

    public synchronized void releaseHalfOpenProbe() {
        if (state == State.HALF_OPEN) {
            halfOpenProbeInFlight = false;
        }
    }

    public synchronized State state() {
        if (enabled) {
            refreshState(clock.instant());
        }
        return state;
    }

    public synchronized int failureCount() {
        pruneFailures(clock.instant());
        return failures.size();
    }

    public synchronized int lastFailureStatus() {
        return lastFailureStatus;
    }

    private void refreshState(Instant now) {
        if (state == State.OPEN && openedAt != null && !now.isBefore(openedAt.plus(cooldown))) {
            state = State.HALF_OPEN;
            halfOpenProbeInFlight = false;
        }
    }

    private void pruneFailures(Instant now) {
        Instant cutoff = now.minus(failureWindow);
        while (!failures.isEmpty() && !failures.peekFirst().isAfter(cutoff)) {
            failures.removeFirst();
        }
    }

    private void open(Instant now) {
        state = State.OPEN;
        openedAt = now;
        halfOpenProbeInFlight = false;
    }

    private void close() {
        state = State.CLOSED;
        openedAt = null;
        halfOpenProbeInFlight = false;
        failures.clear();
        lastFailureStatus = 0;
    }

    private Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
