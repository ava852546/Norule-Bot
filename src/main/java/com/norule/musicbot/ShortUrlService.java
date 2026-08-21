package com.norule.musicbot;

import com.norule.musicbot.domain.shorturl.ShortUrlDomainService;
import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.ShortUrlAccessEvent;
import com.norule.musicbot.domain.shorturl.ShortUrlStatistics;
import com.norule.musicbot.service.shorturl.ImageShareService;
import com.norule.musicbot.service.shorturl.AnonymousDeviceIdentityService;
import com.norule.musicbot.service.shorturl.MediaPasswordAttemptGuard;
import com.norule.musicbot.service.shorturl.ShortUrlCreationGuard;
import com.norule.musicbot.domain.shorturl.QuotaSubject;
import com.norule.musicbot.shorturl.ShortUrlAccessPublisher;
import com.norule.musicbot.shorturl.ShortUrlRepository;

import java.net.URI;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicReference;

public final class ShortUrlService {
    public record Options(
            boolean dedupeEnabled,
            long ttlMillis,
            long cleanupIntervalMillis,
            String publicBaseUrl,
            int codeLength,
            boolean allowPrivateTargets
    ) {
        public Options {
            ttlMillis = Math.max(1L, ttlMillis);
            cleanupIntervalMillis = Math.max(60_000L, cleanupIntervalMillis);
            String base = publicBaseUrl == null || publicBaseUrl.isBlank() ? DEFAULT_PUBLIC_BASE_URL : publicBaseUrl.trim();
            publicBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            codeLength = Math.max(4, Math.min(32, codeLength));
        }
    }

    public record ShortUrlEntry(String code,
                                String target,
                                long createdAt,
                                long expiresAt,
                                long viewCount,
                                String ownerUserId,
                                long lastAccessedAt) {
        public ShortUrlEntry {
            ownerUserId = ownerUserId == null ? "" : ownerUserId.trim();
            lastAccessedAt = Math.max(0L, lastAccessedAt);
        }

        public ShortUrlEntry(String code, String target, long createdAt, long expiresAt) {
            this(code, target, createdAt, expiresAt, 0L, "", 0L);
        }

        public ShortUrlEntry(String code, String target, long createdAt, long expiresAt, long viewCount) {
            this(code, target, createdAt, expiresAt, viewCount, "", 0L);
        }

        public String getCode() { return code; }
        public String getTarget() { return target; }
        public long getCreatedAt() { return createdAt; }
        public long getExpiresAt() { return expiresAt; }
        public long getViewCount() { return viewCount; }
        public String getOwnerUserId() { return ownerUserId; }
        public long getLastAccessedAt() { return lastAccessedAt; }

        public ShortUrlEntry withViewCount(long updatedViewCount) {
            return new ShortUrlEntry(code, target, createdAt, expiresAt, Math.max(0L, updatedViewCount),
                    ownerUserId, lastAccessedAt);
        }

        public ShortUrlEntry withViewMetrics(long updatedViewCount, long updatedLastAccessedAt) {
            return new ShortUrlEntry(code, target, createdAt, expiresAt, Math.max(0L, updatedViewCount),
                    ownerUserId, updatedLastAccessedAt);
        }
    }

    public record CreationOutcome(ShortUrlEntry entry, boolean newlyCreated) {
    }

    private static final long DEFAULT_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final long DEFAULT_CLEANUP_INTERVAL_MILLIS = 10L * 60L * 1000L;
    private static final String DEFAULT_PUBLIC_BASE_URL = "https://s.norule.me";
    private static final char[] RANDOM_CODE_ALPHABET = "23456789abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int DEFAULT_RANDOM_CODE_LENGTH = 7;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ShortUrlDomainService domainService = new ShortUrlDomainService();
    private final ShortUrlRepository repository;
    private final ImageShareService imageShareService;
    private final AnonymousDeviceIdentityService anonymousDeviceIdentityService;
    private final ShortUrlCreationGuard creationGuard = new ShortUrlCreationGuard(
            ShortUrlCreationGuard.Options.defaults());
    private final AtomicReference<Options> options = new AtomicReference<>();
    private final AtomicReference<ShortUrlAccessPublisher> accessPublisher =
            new AtomicReference<>(ShortUrlAccessPublisher.NO_OP);
    private volatile Long logChannelId;
    private volatile long lastCleanupAt = 0L;

    public ShortUrlService(ShortUrlRepository repository) {
        this(repository, new Options(
                true,
                DEFAULT_TTL_MILLIS,
                DEFAULT_CLEANUP_INTERVAL_MILLIS,
                DEFAULT_PUBLIC_BASE_URL,
                DEFAULT_RANDOM_CODE_LENGTH,
                false
        ));
    }

    public ShortUrlService(ShortUrlRepository repository, Options options) {
        this(repository, options, null);
    }

    public ShortUrlService(ShortUrlRepository repository, Options options, ImageShareService imageShareService) {
        this(repository, options, imageShareService, null);
    }

    public ShortUrlService(ShortUrlRepository repository, Options options, ImageShareService imageShareService,
                           AnonymousDeviceIdentityService anonymousDeviceIdentityService) {
        if (repository == null) {
            throw new IllegalArgumentException("repository cannot be null");
        }
        this.repository = repository;
        this.imageShareService = imageShareService;
        this.anonymousDeviceIdentityService = anonymousDeviceIdentityService;
        this.logChannelId = normalizeChannelId(repository.findLogChannelId());
        this.options.set(options == null
                ? new Options(
                true,
                DEFAULT_TTL_MILLIS,
                DEFAULT_CLEANUP_INTERVAL_MILLIS,
                DEFAULT_PUBLIC_BASE_URL,
                DEFAULT_RANDOM_CODE_LENGTH,
                false
        )
                : options);
    }

    public ShortUrlEntry create(String rawTarget) {
        return create(rawTarget, "");
    }

    public ShortUrlEntry create(String rawTarget, String customSlug) {
        return create(rawTarget, customSlug, "", "");
    }

    public ShortUrlEntry create(String rawTarget,
                                String customSlug,
                                String creatorDiscordUserId,
                                String clientAddress) {
        return createWithOutcome(rawTarget, customSlug, creatorDiscordUserId, clientAddress).entry();
    }

    public CreationOutcome createWithOutcome(String rawTarget,
                                             String customSlug,
                                             String creatorDiscordUserId,
                                             String clientAddress) {
        return createOutcome(rawTarget, customSlug, options.get().ttlMillis(),
                creatorDiscordUserId, clientAddress);
    }

    public ShortUrlEntry create(String rawTarget, long ttlMillis) {
        return createOutcome(rawTarget, null, ttlMillis, "", "").entry();
    }

    public ShortUrlEntry create(String rawTarget, String customSlug, long ttlMillis) {
        return createOutcome(rawTarget, customSlug, ttlMillis, "", "").entry();
    }

    private CreationOutcome createOutcome(String rawTarget,
                                          String customSlug,
                                          long ttlMillis,
                                          String creatorDiscordUserId,
                                          String clientAddress) {
        String target = domainService.normalizeTarget(rawTarget);
        if (!domainService.isValidTarget(target)) {
            return new CreationOutcome(null, false);
        }
        Options currentOptions = options.get();
        if (!currentOptions.allowPrivateTargets() && domainService.isPrivateOrLocalTarget(target)) {
            return new CreationOutcome(null, false);
        }
        if (isSelfDomainTarget(target)) {
            return new CreationOutcome(null, false);
        }

        long now = System.currentTimeMillis();
        maybeCleanup(now);

        long safeTtl = ttlMillis <= 0L ? currentOptions.ttlMillis() : ttlMillis;
        String normalizedOwnerUserId = creatorDiscordUserId == null ? "" : creatorDiscordUserId.trim();
        String requestedSlug = domainService.normalizeSlug(customSlug);
        if (requestedSlug.isBlank() && currentOptions.dedupeEnabled()) {
            ShortUrlEntry existing = repository.findActiveByTarget(target, now);
            if (existing != null && !domainService.isExpired(existing.getExpiresAt(), now)
                    && existing.ownerUserId().equals(normalizedOwnerUserId)) {
                return new CreationOutcome(existing, false);
            }
        }

        String code = resolveCodeForCreate(customSlug, now);
        if (code == null) {
            return new CreationOutcome(null, false);
        }
        ShortUrlEntry created = new ShortUrlEntry(
                code, target, now, now + safeTtl, 0L, normalizedOwnerUserId, 0L);
        repository.save(created);
        publishAccess(ShortUrlAccessEvent.Action.CREATED, ShortUrlAccessEvent.ResourceType.URL,
                created.code(), created.target(), created.viewCount(), created.expiresAt(), false, 0L,
                creatorDiscordUserId, clientAddress, "");
        return new CreationOutcome(created, true);
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
        return options.get().publicBaseUrl() + "/" + code.trim();
    }

    public String publicBaseUrl() {
        return options.get().publicBaseUrl();
    }

    public void updateOptions(Options options) {
        if (options == null) {
            return;
        }
        this.options.set(options);
    }

    public void updateCreationGuardOptions(ShortUrlCreationGuard.Options options) {
        creationGuard.updateOptions(options);
    }

    public ShortUrlCreationGuard.Decision checkCreationRequest(String creatorDiscordUserId,
                                                                String clientAddress) {
        return creationGuard.checkRequest(creatorDiscordUserId, clientAddress);
    }

    public ShortUrlCreationGuard.CreationPermit beginShortUrlCreation(String creatorDiscordUserId,
                                                                      String clientAddress) {
        return creationGuard.beginCreation(creatorDiscordUserId, clientAddress);
    }

    public ImageShareService.UploadResult createImageShare(ImageShareService.Upload upload) {
        return createImageShare(upload, "", "");
    }

    public ImageShareService.UploadResult createImageShare(ImageShareService.Upload upload,
                                                            String clientAddress,
                                                            String userAgent) {
        return createImageShare(upload, clientAddress, userAgent, null);
    }

    public ImageShareService.UploadResult createImageShare(ImageShareService.Upload upload,
                                                            String clientAddress,
                                                            String userAgent,
                                                            QuotaSubject quotaSubject) {
        if (imageShareService == null) {
            return new ImageShareService.UploadResult(null, ImageShareService.UploadError.DISABLED);
        }
        ImageShareService.UploadResult result = imageShareService.create(upload, quotaSubject);
        if (result.isSuccess()) {
            ImageShare imageShare = result.imageShare();
            publishAccess(ShortUrlAccessEvent.Action.CREATED, mediaResourceType(imageShare),
                    imageShare.code(), imageShare.contentType(), imageShare.viewCount(), imageShare.expiresAt(),
                    imageShare.isPasswordProtected(), imageShare.sizeBytes(), "", clientAddress, userAgent);
        }
        return result;
    }

    public ImageShare resolveImageShare(String code) {
        return imageShareService == null ? null : imageShareService.resolve(code);
    }

    public ImageShare findExpiredImageShare(String code) {
        return imageShareService == null ? null : imageShareService.findExpired(code);
    }

    public java.io.InputStream openImageShare(ImageShare imageShare) {
        return imageShareService == null ? null : imageShareService.open(imageShare);
    }

    public boolean verifyImageSharePassword(ImageShare imageShare, String password) {
        return imageShareService != null && imageShareService.verifyPassword(imageShare, password);
    }

    public MediaPasswordAttemptGuard.Result verifyImageSharePasswordGuarded(ImageShare imageShare,
                                                                             String password,
                                                                             String clientAddress) {
        if (imageShareService == null) {
            return new MediaPasswordAttemptGuard.Result(
                    MediaPasswordAttemptGuard.Status.INVALID_PASSWORD, 0L);
        }
        return imageShareService.verifyPasswordGuarded(imageShare, password, clientAddress);
    }

    public AnonymousDeviceIdentityService.DeviceIdentity resolveAnonymousDevice(String deviceToken,
                                                                                 String clientAddress) {
        return anonymousDeviceIdentityService == null ? null
                : anonymousDeviceIdentityService.resolveAnonymous(deviceToken, clientAddress);
    }

    public AnonymousDeviceIdentityService.AuthenticationResult authenticateMediaIdentity(
            String deviceToken, String discordUserId, String clientAddress) {
        return anonymousDeviceIdentityService == null ? null
                : anonymousDeviceIdentityService.authenticate(deviceToken, discordUserId, clientAddress);
    }

    public long anonymousDeviceCookieMaxAgeSeconds() {
        return anonymousDeviceIdentityService == null ? 30L * 24L * 60L * 60L
                : anonymousDeviceIdentityService.deviceCookieMaxAgeSeconds();
    }

    public long mediaMaxRetentionMillis(QuotaSubject quotaSubject) {
        return imageShareService == null ? 0L : imageShareService.maxRetentionMillisFor(quotaSubject);
    }

    public ShortUrlEntry recordView(ShortUrlEntry entry, String clientAddress, String userAgent) {
        if (entry == null) {
            return null;
        }
        long accessedAt = System.currentTimeMillis();
        ShortUrlEntry viewed = entry.withViewMetrics(
                repository.incrementViewCount(entry.code(), accessedAt), accessedAt);
        publishAccess(ShortUrlAccessEvent.Action.VIEWED, ShortUrlAccessEvent.ResourceType.URL,
                viewed.code(), viewed.target(), viewed.viewCount(), viewed.expiresAt(), false, 0L,
                "", clientAddress, userAgent);
        return viewed;
    }

    public ImageShare recordImageShareView(ImageShare imageShare, String clientAddress, String userAgent) {
        if (imageShareService == null || imageShare == null) {
            return null;
        }
        ImageShare viewed = imageShareService.recordView(imageShare);
        publishAccess(ShortUrlAccessEvent.Action.VIEWED, mediaResourceType(viewed),
                viewed.code(), viewed.contentType(), viewed.viewCount(), viewed.expiresAt(),
                viewed.isPasswordProtected(), viewed.sizeBytes(), "", clientAddress, userAgent);
        return viewed;
    }

    public ShortUrlStatistics findStatisticsForOwner(String code, String ownerUserId) {
        if (code == null || code.isBlank() || ownerUserId == null || ownerUserId.isBlank()) {
            return null;
        }
        String normalizedCode = code.trim();
        String normalizedOwner = ownerUserId.trim();
        ImageShare imageShare = resolveImageShare(normalizedCode);
        if (imageShare != null && normalizedOwner.equals(imageShare.ownerUserId())) {
            return new ShortUrlStatistics(
                    ShortUrlStatistics.ResourceType.MEDIA_SHARE,
                    imageShare.code(),
                    imageShare.viewCount(),
                    imageShare.createdAt(),
                    imageShare.lastAccessedAt(),
                    imageShare.expiresAt()
            );
        }
        ShortUrlEntry entry = resolve(normalizedCode);
        if (entry == null || !normalizedOwner.equals(entry.ownerUserId())) {
            return null;
        }
        return new ShortUrlStatistics(
                ShortUrlStatistics.ResourceType.SHORT_URL,
                entry.code(),
                entry.viewCount(),
                entry.createdAt(),
                entry.lastAccessedAt(),
                entry.expiresAt()
        );
    }

    private ShortUrlAccessEvent.ResourceType mediaResourceType(ImageShare imageShare) {
        return imageShare != null && imageShare.isVideo()
                ? ShortUrlAccessEvent.ResourceType.VIDEO
                : ShortUrlAccessEvent.ResourceType.IMAGE;
    }

    public Long getLogChannelId() {
        return logChannelId;
    }

    public void updateLogChannelId(Long channelId) {
        Long normalized = normalizeChannelId(channelId);
        repository.saveLogChannelId(normalized);
        logChannelId = normalized;
    }

    public void updateAccessPublisher(ShortUrlAccessPublisher publisher) {
        accessPublisher.set(publisher == null ? ShortUrlAccessPublisher.NO_OP : publisher);
    }

    public ImageShareService.Options imageShareOptions() {
        return imageShareService == null ? null : imageShareService.options();
    }

    public void updateImageShareOptions(ImageShareService.Options options) {
        if (imageShareService != null) {
            imageShareService.updateOptions(options);
        }
    }

    public void cleanupExpired() {
        long now = System.currentTimeMillis();
        repository.cleanupExpired(now);
        if (imageShareService != null) {
            imageShareService.cleanupExpired();
        }
        lastCleanupAt = now;
    }

    private void maybeCleanup(long now) {
        if (now - lastCleanupAt < options.get().cleanupIntervalMillis()) {
            return;
        }
        synchronized (this) {
            if (now - lastCleanupAt < options.get().cleanupIntervalMillis()) {
                return;
            }
            repository.cleanupExpired(now);
            if (imageShareService != null) {
                imageShareService.cleanupExpired();
            }
            lastCleanupAt = now;
        }
    }

    private String nextAvailableCode(int length) {
        for (int i = 0; i < 10_000; i++) {
            String code = randomCode(length);
            if (repository.findByCode(code) == null && !isImageCodeInUse(code)) {
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
            return nextAvailableCode(options.get().codeLength());
        }
        if (!domainService.isValidSlug(slug) || domainService.isReservedCode(slug)) {
            return null;
        }
        if (isImageCodeInUse(slug)) {
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
            URI baseUri = URI.create(options.get().publicBaseUrl());
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

    private boolean isImageCodeInUse(String code) {
        return imageShareService != null && imageShareService.isCodeInUse(code);
    }

    private void publishAccess(ShortUrlAccessEvent.Action action,
                               ShortUrlAccessEvent.ResourceType resourceType,
                               String code,
                               String target,
                               long viewCount,
                               long expiresAt,
                               boolean passwordProtected,
                               long fileSizeBytes,
                               String creatorDiscordUserId,
                               String clientAddress,
                               String userAgent) {
        Long channelId = logChannelId;
        if (channelId == null) {
            return;
        }
        try {
            accessPublisher.get().publish(channelId, new ShortUrlAccessEvent(
                    action,
                    resourceType,
                    code,
                    toPublicUrl(code),
                    target == null ? "" : target,
                    Math.max(0L, viewCount),
                    expiresAt,
                    passwordProtected,
                    Math.max(0L, fileSizeBytes),
                    creatorDiscordUserId == null ? "" : creatorDiscordUserId,
                    clientAddress == null ? "" : clientAddress,
                    userAgent == null ? "" : userAgent,
                    System.currentTimeMillis()
            ));
        } catch (RuntimeException ignored) {
            // Short URL creation and access must remain available if Discord logging is temporarily unavailable.
        }
    }

    private Long normalizeChannelId(Long channelId) {
        return channelId == null || channelId <= 0L ? null : channelId;
    }
}
