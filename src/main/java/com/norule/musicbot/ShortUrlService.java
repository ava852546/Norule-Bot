package com.norule.musicbot;

import com.norule.musicbot.domain.shorturl.ShortUrlDomainService;
import com.norule.musicbot.shorturl.ShortUrlRepository;

import java.net.URI;
import java.security.SecureRandom;

public final class ShortUrlService {
    public static final class Options {
        private final boolean dedupeEnabled;
        private final long ttlMillis;
        private final long cleanupIntervalMillis;
        private final String publicBaseUrl;
        private final int codeLength;
        private final boolean allowPrivateTargets;

        public Options(boolean dedupeEnabled,
                       long ttlMillis,
                       long cleanupIntervalMillis,
                       String publicBaseUrl,
                       int codeLength,
                       boolean allowPrivateTargets) {
            this.dedupeEnabled = dedupeEnabled;
            this.ttlMillis = Math.max(1L, ttlMillis);
            this.cleanupIntervalMillis = Math.max(60_000L, cleanupIntervalMillis);
            String base = publicBaseUrl == null || publicBaseUrl.isBlank() ? "https://s.norule.me" : publicBaseUrl.trim();
            this.publicBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            this.codeLength = Math.max(4, Math.min(32, codeLength));
            this.allowPrivateTargets = allowPrivateTargets;
        }

        public boolean dedupeEnabled() { return dedupeEnabled; }
        public long ttlMillis() { return ttlMillis; }
        public long cleanupIntervalMillis() { return cleanupIntervalMillis; }
        public String publicBaseUrl() { return publicBaseUrl; }
        public int codeLength() { return codeLength; }
        public boolean allowPrivateTargets() { return allowPrivateTargets; }
    }

    public static final class ShortUrlEntry {
        private final String code;
        private final String target;
        private final long createdAt;
        private final long expiresAt;

        public ShortUrlEntry(String code, String target, long createdAt, long expiresAt) {
            this.code = code;
            this.target = target;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        public String getCode() {
            return code;
        }

        public String getTarget() {
            return target;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public long getExpiresAt() {
            return expiresAt;
        }
    }

    private static final long DEFAULT_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final long DEFAULT_CLEANUP_INTERVAL_MILLIS = 10L * 60L * 1000L;
    private static final char[] RANDOM_CODE_ALPHABET = "23456789abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int DEFAULT_RANDOM_CODE_LENGTH = 7;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ShortUrlDomainService domainService = new ShortUrlDomainService();
    private final ShortUrlRepository repository;
    private volatile Options options;
    private volatile long lastCleanupAt = 0L;

    public ShortUrlService(ShortUrlRepository repository) {
        this(repository, new Options(
                true,
                DEFAULT_TTL_MILLIS,
                DEFAULT_CLEANUP_INTERVAL_MILLIS,
                "https://s.norule.me",
                DEFAULT_RANDOM_CODE_LENGTH,
                false
        ));
    }

    public ShortUrlService(ShortUrlRepository repository, Options options) {
        if (repository == null) {
            throw new IllegalArgumentException("repository cannot be null");
        }
        this.repository = repository;
        this.options = options == null
                ? new Options(
                true,
                DEFAULT_TTL_MILLIS,
                DEFAULT_CLEANUP_INTERVAL_MILLIS,
                "https://s.norule.me",
                DEFAULT_RANDOM_CODE_LENGTH,
                false
        )
                : options;
    }

    public ShortUrlEntry create(String rawTarget) {
        return create(rawTarget, options.ttlMillis());
    }

    public ShortUrlEntry create(String rawTarget, String customSlug) {
        return create(rawTarget, customSlug, options.ttlMillis());
    }

    public ShortUrlEntry create(String rawTarget, long ttlMillis) {
        return create(rawTarget, null, ttlMillis);
    }

    public ShortUrlEntry create(String rawTarget, String customSlug, long ttlMillis) {
        String target = domainService.normalizeTarget(rawTarget);
        if (!domainService.isValidTarget(target)) {
            return null;
        }
        if (!options.allowPrivateTargets() && domainService.isPrivateOrLocalTarget(target)) {
            return null;
        }
        if (isSelfDomainTarget(target)) {
            return null;
        }

        long now = System.currentTimeMillis();
        maybeCleanup(now);

        long safeTtl = ttlMillis <= 0L ? options.ttlMillis() : ttlMillis;
        String requestedSlug = domainService.normalizeSlug(customSlug);
        if (requestedSlug.isBlank() && options.dedupeEnabled()) {
            ShortUrlEntry existing = repository.findActiveByTarget(target, now);
            if (existing != null && !domainService.isExpired(existing.getExpiresAt(), now)) {
                return existing;
            }
        }

        String code = resolveCodeForCreate(customSlug, now);
        if (code == null) {
            return null;
        }
        ShortUrlEntry created = new ShortUrlEntry(code, target, now, now + safeTtl);
        repository.save(created);
        return created;
    }

    public String resolveTarget(String code) {
        ShortUrlEntry entry = resolve(code);
        return entry == null ? null : entry.getTarget();
    }

    public ShortUrlEntry resolve(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        maybeCleanup(now);

        String normalized = code.trim();
        ShortUrlEntry entry = repository.findByCode(normalized);
        if (entry == null) {
            return null;
        }
        if (domainService.isExpired(entry.getExpiresAt(), now)) {
            repository.deleteByCode(normalized);
            return null;
        }
        return entry;
    }

    public ShortUrlEntry findActiveByTarget(String rawTarget) {
        String target = domainService.normalizeTarget(rawTarget);
        if (!domainService.isValidTarget(target)) {
            return null;
        }

        long now = System.currentTimeMillis();
        maybeCleanup(now);

        ShortUrlEntry existing = repository.findActiveByTarget(target, now);
        if (existing == null) {
            return null;
        }
        if (domainService.isExpired(existing.getExpiresAt(), now)) {
            repository.deleteByCode(existing.getCode());
            return null;
        }
        return existing;
    }

    public String toPublicUrl(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return options.publicBaseUrl() + "/" + code.trim();
    }

    public void updateOptions(Options options) {
        if (options == null) {
            return;
        }
        this.options = options;
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        repository.cleanupExpired(now);
        lastCleanupAt = now;
    }

    private void maybeCleanup(long now) {
        if (now - lastCleanupAt < options.cleanupIntervalMillis()) {
            return;
        }
        synchronized (this) {
            if (now - lastCleanupAt < options.cleanupIntervalMillis()) {
                return;
            }
            repository.cleanupExpired(now);
            lastCleanupAt = now;
        }
    }

    private String nextAvailableCode(int length) {
        for (int i = 0; i < 10_000; i++) {
            String code = randomCode(length);
            if (repository.findByCode(code) == null) {
                return code;
            }
        }
        throw new IllegalStateException("Unable to allocate short url code");
    }

    private String randomCode(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = RANDOM_CODE_ALPHABET[SECURE_RANDOM.nextInt(RANDOM_CODE_ALPHABET.length)];
        }
        return new String(chars);
    }

    private String resolveCodeForCreate(String customSlug, long nowMillis) {
        String slug = domainService.normalizeSlug(customSlug);
        if (slug.isBlank()) {
            return nextAvailableCode(options.codeLength());
        }
        if (!domainService.isValidSlug(slug) || domainService.isReservedCode(slug)) {
            return null;
        }
        ShortUrlEntry existing = repository.findByCode(slug);
        if (existing == null) {
            return slug;
        }
        if (domainService.isExpired(existing.getExpiresAt(), nowMillis)) {
            repository.deleteByCode(slug);
            return slug;
        }
        return null;
    }

    private boolean isSelfDomainTarget(String target) {
        try {
            URI targetUri = URI.create(target);
            URI baseUri = URI.create(options.publicBaseUrl());
            String targetHost = targetUri.getHost();
            String baseHost = baseUri.getHost();
            if (targetHost == null || targetHost.isBlank() || baseHost == null || baseHost.isBlank()) {
                return false;
            }
            return targetHost.equalsIgnoreCase(baseHost);
        } catch (Exception ignored) {
            return false;
        }
    }
}
