package com.norule.musicbot.discord.bot.service.wordchain;

import com.norule.musicbot.config.domain.DictionaryCacheConfig;
import com.norule.musicbot.discord.bot.gateway.wordchain.DictionaryApiGateway;
import com.norule.musicbot.domain.wordchain.DictionaryLookupResult;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DictionaryApiService {
    private final DictionaryApiGateway gateway;
    private final DictionaryCacheConfig cacheConfig;
    private final Clock clock;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<DictionaryLookupResult>> inFlight =
            new ConcurrentHashMap<>();
    private final AtomicLong cacheSequence = new AtomicLong();
    private final Object evictionLock = new Object();

    public DictionaryApiService(DictionaryApiGateway gateway) {
        this(gateway, DictionaryCacheConfig.defaultValues());
    }

    public DictionaryApiService(
            DictionaryApiGateway gateway,
            DictionaryCacheConfig cacheConfig
    ) {
        this(gateway, cacheConfig, Clock.systemUTC());
    }

    DictionaryApiService(
            DictionaryApiGateway gateway,
            DictionaryCacheConfig cacheConfig,
            Clock clock
    ) {
        if (gateway == null) {
            throw new IllegalArgumentException("gateway cannot be null");
        }
        this.gateway = gateway;
        this.cacheConfig = cacheConfig == null ? DictionaryCacheConfig.defaultValues() : cacheConfig;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public CompletableFuture<DictionaryLookupResult> lookupWord(String word) {
        String normalized = normalize(word);
        if (normalized.isBlank()) {
            return CompletableFuture.completedFuture(DictionaryLookupResult.NOT_FOUND);
        }

        DictionaryLookupResult cached = cachedResult(normalized);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<DictionaryLookupResult> shared = new CompletableFuture<>();
        CompletableFuture<DictionaryLookupResult> existing = inFlight.putIfAbsent(normalized, shared);
        if (existing != null) {
            return existing;
        }

        startLookup(normalized, shared);
        return shared;
    }

    private void startLookup(
            String word,
            CompletableFuture<DictionaryLookupResult> shared
    ) {
        CompletableFuture<DictionaryLookupResult> lookup;
        try {
            lookup = gateway.lookup(word);
        } catch (RuntimeException error) {
            lookup = null;
        }
        if (lookup == null) {
            finishLookup(word, DictionaryLookupResult.API_ERROR, shared);
            return;
        }
        lookup.whenComplete((result, error) -> finishLookup(
                word,
                error == null && result != null ? result : DictionaryLookupResult.API_ERROR,
                shared
        ));
    }

    private void finishLookup(
            String word,
            DictionaryLookupResult result,
            CompletableFuture<DictionaryLookupResult> shared
    ) {
        try {
            cacheResult(word, result);
        } finally {
            inFlight.remove(word, shared);
            shared.complete(result);
        }
    }

    private DictionaryLookupResult cachedResult(String word) {
        if (!cacheConfig.isEnabled()) {
            return null;
        }
        CacheEntry entry = cache.get(word);
        if (entry == null) {
            return null;
        }
        long now = clock.millis();
        if (entry.expiresAtMillis() <= now) {
            cache.remove(word, entry);
            return null;
        }
        return entry.result();
    }

    private void cacheResult(String word, DictionaryLookupResult result) {
        if (!cacheConfig.isEnabled()) {
            return;
        }
        Duration ttl = ttlFor(result);
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        long now = clock.millis();
        long ttlMillis = ttl.toMillis();
        long expiresAt = ttlMillis >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + ttlMillis;
        cache.put(word, new CacheEntry(result, expiresAt, cacheSequence.incrementAndGet()));
        evictIfNeeded(now);
    }

    private Duration ttlFor(DictionaryLookupResult result) {
        return switch (result) {
            case FOUND -> cacheConfig.getFoundTtl();
            case NOT_FOUND -> cacheConfig.getNotFoundTtl();
            case API_ERROR -> cacheConfig.getTemporaryFailureTtl();
        };
    }

    private void evictIfNeeded(long now) {
        if (cache.size() <= cacheConfig.getMaxEntries()) {
            return;
        }
        synchronized (evictionLock) {
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
            int overflow = cache.size() - cacheConfig.getMaxEntries();
            if (overflow <= 0) {
                return;
            }
            cache.entrySet().stream()
                    .sorted(Comparator.comparingLong(entry -> entry.getValue().sequence()))
                    .limit(overflow)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(cache::remove);
        }
    }

    private String normalize(String word) {
        if (word == null) {
            return "";
        }
        return word.trim().toLowerCase(Locale.ROOT);
    }

    private record CacheEntry(
            DictionaryLookupResult result,
            long expiresAtMillis,
            long sequence
    ) {
    }
}
