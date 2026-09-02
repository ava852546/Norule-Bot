package com.norule.musicbot.domain.music.bilibili;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

public final class BilibiliMetadataCache {
    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private boolean enabled;
    private Duration ttl;
    private int maxEntries;

    public BilibiliMetadataCache(boolean enabled, Duration ttl, int maxEntries) {
        this(enabled, ttl, maxEntries, Clock.systemUTC());
    }

    public BilibiliMetadataCache(boolean enabled, Duration ttl, int maxEntries, Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        updateConfig(enabled, ttl, maxEntries);
    }

    public synchronized void updateConfig(boolean enabled, Duration ttl, int maxEntries) {
        this.enabled = enabled;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofHours(12) : ttl;
        this.maxEntries = Math.max(1, maxEntries);
        if (!enabled) {
            entries.clear();
        } else {
            evictExpired(clock.instant());
            evictOverflow();
        }
    }

    public synchronized Optional<BilibiliMetadata> get(String bvid) {
        if (!enabled) {
            misses.increment();
            return Optional.empty();
        }
        String key = BilibiliVideoIdentifier.normalizeBvid(bvid);
        Entry entry = entries.get(key);
        Instant now = clock.instant();
        if (entry == null || !now.isBefore(entry.expiresAt())) {
            if (entry != null) {
                entries.remove(key);
                evictions.increment();
            }
            misses.increment();
            return Optional.empty();
        }
        hits.increment();
        return Optional.of(entry.metadata());
    }

    public synchronized void put(BilibiliMetadata metadata) {
        if (!enabled || metadata == null || metadata.bvid().isBlank() || metadata.pages().isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        evictExpired(now);
        entries.put(metadata.bvid(), new Entry(metadata, now.plus(ttl)));
        evictOverflow();
    }

    public synchronized void cleanupExpired() {
        evictExpired(clock.instant());
    }

    public synchronized int size() {
        evictExpired(clock.instant());
        return entries.size();
    }

    public Statistics statistics() {
        return new Statistics(hits.sum(), misses.sum(), evictions.sum(), size());
    }

    private void evictExpired(Instant now) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            if (!now.isBefore(iterator.next().expiresAt())) {
                iterator.remove();
                evictions.increment();
            }
        }
    }

    private void evictOverflow() {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (entries.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
            evictions.increment();
        }
    }

    private record Entry(BilibiliMetadata metadata, Instant expiresAt) {
    }

    public record Statistics(long hits, long misses, long evictions, int size) {
    }
}
