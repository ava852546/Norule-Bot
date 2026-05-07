package com.norule.musicbot.discord.bot.flow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ComponentInteractionRateGate {
    private static final long DEFAULT_INTERVAL_MILLIS = 1200L;
    private static final long STALE_ENTRY_MILLIS = 5L * 60L * 1000L;
    private static final int MAX_CACHE_SIZE = 4096;

    private final long minIntervalMillis;
    private final Map<Long, Long> lastSeenByMessageId = new ConcurrentHashMap<>();

    public ComponentInteractionRateGate() {
        this(DEFAULT_INTERVAL_MILLIS);
    }

    public ComponentInteractionRateGate(long minIntervalMillis) {
        this.minIntervalMillis = Math.max(250L, minIntervalMillis);
    }

    public boolean allow(long messageId) {
        long now = System.currentTimeMillis();
        pruneIfNeeded(now);
        Long previous = lastSeenByMessageId.put(messageId, now);
        return previous == null || now - previous >= minIntervalMillis;
    }

    private void pruneIfNeeded(long now) {
        if (lastSeenByMessageId.size() < MAX_CACHE_SIZE) {
            return;
        }
        lastSeenByMessageId.entrySet().removeIf(entry -> now - entry.getValue() > STALE_ENTRY_MILLIS);
    }
}
