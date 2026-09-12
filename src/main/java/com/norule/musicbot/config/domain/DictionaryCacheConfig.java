package com.norule.musicbot.config.domain;

import java.time.Duration;

public final class DictionaryCacheConfig {
    private static final Duration DEFAULT_FOUND_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_NOT_FOUND_TTL = Duration.ofMinutes(30);
    private static final Duration DEFAULT_TEMPORARY_FAILURE_TTL = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final boolean enabled;
    private final Duration foundTtl;
    private final Duration notFoundTtl;
    private final Duration temporaryFailureTtl;
    private final int maxEntries;

    public DictionaryCacheConfig(
            boolean enabled,
            Duration foundTtl,
            Duration notFoundTtl,
            Duration temporaryFailureTtl,
            int maxEntries
    ) {
        this.enabled = enabled;
        this.foundTtl = positive(foundTtl, DEFAULT_FOUND_TTL);
        this.notFoundTtl = positive(notFoundTtl, DEFAULT_NOT_FOUND_TTL);
        this.temporaryFailureTtl = nonNegative(temporaryFailureTtl, DEFAULT_TEMPORARY_FAILURE_TTL);
        this.maxEntries = maxEntries > 0 ? maxEntries : DEFAULT_MAX_ENTRIES;
    }

    public static DictionaryCacheConfig defaultValues() {
        return new DictionaryCacheConfig(
                true,
                DEFAULT_FOUND_TTL,
                DEFAULT_NOT_FOUND_TTL,
                DEFAULT_TEMPORARY_FAILURE_TTL,
                DEFAULT_MAX_ENTRIES
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Duration getFoundTtl() {
        return foundTtl;
    }

    public Duration getNotFoundTtl() {
        return notFoundTtl;
    }

    public Duration getTemporaryFailureTtl() {
        return temporaryFailureTtl;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static Duration nonNegative(Duration value, Duration fallback) {
        return value == null || value.isNegative() ? fallback : value;
    }
}
