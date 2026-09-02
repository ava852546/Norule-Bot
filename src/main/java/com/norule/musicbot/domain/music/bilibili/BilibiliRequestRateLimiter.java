package com.norule.musicbot.domain.music.bilibili;

import java.time.Clock;
import java.time.Instant;

public final class BilibiliRequestRateLimiter {
    private final Clock clock;
    private boolean enabled;
    private double requestsPerSecond;
    private int burst;
    private double availableTokens;
    private Instant lastRefill;

    public BilibiliRequestRateLimiter(boolean enabled, int requestsPerSecond, int burst) {
        this(enabled, requestsPerSecond, burst, Clock.systemUTC());
    }

    public BilibiliRequestRateLimiter(boolean enabled, int requestsPerSecond, int burst, Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.lastRefill = this.clock.instant();
        updateConfig(enabled, requestsPerSecond, burst);
    }

    public synchronized void updateConfig(boolean enabled, int requestsPerSecond, int burst) {
        refill(clock.instant());
        this.enabled = enabled;
        this.requestsPerSecond = Math.max(1, requestsPerSecond);
        this.burst = Math.max(1, burst);
        this.availableTokens = Math.min(this.burst, enabled ? Math.max(0D, availableTokens) : this.burst);
        if (availableTokens == 0D) {
            availableTokens = this.burst;
        }
        this.lastRefill = clock.instant();
    }

    public synchronized boolean tryAcquire() {
        if (!enabled) {
            return true;
        }
        refill(clock.instant());
        if (availableTokens < 1D) {
            return false;
        }
        availableTokens -= 1D;
        return true;
    }

    public synchronized double availableTokens() {
        refill(clock.instant());
        return availableTokens;
    }

    private void refill(Instant now) {
        if (lastRefill == null || requestsPerSecond <= 0D || !now.isAfter(lastRefill)) {
            return;
        }
        long elapsedMillis = java.time.Duration.between(lastRefill, now).toMillis();
        availableTokens = Math.min(burst, availableTokens + elapsedMillis * requestsPerSecond / 1000D);
        lastRefill = now;
    }
}
