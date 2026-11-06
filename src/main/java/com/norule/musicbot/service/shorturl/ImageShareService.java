package com.norule.musicbot.service.shorturl;

import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.ImageShareDomainService;
import com.norule.musicbot.domain.shorturl.MediaOwnerType;
import com.norule.musicbot.domain.shorturl.MediaBlob;
import com.norule.musicbot.domain.shorturl.MediaStorageState;
import com.norule.musicbot.domain.shorturl.QuotaSubject;
import com.norule.musicbot.shorturl.InMemoryMediaSecurityRepository;
import com.norule.musicbot.shorturl.InMemoryMediaBlobRepository;
import com.norule.musicbot.shorturl.ImageShareRepository;
import com.norule.musicbot.shorturl.ImageShareStorage;
import com.norule.musicbot.shorturl.MediaBlobRepository;
import com.norule.musicbot.shorturl.ShortUrlRepository;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class ImageShareService {
    public enum UploadError {
        DISABLED,
        IMAGE_REQUIRED,
        UNSUPPORTED_MEDIA,
        IMAGE_TOO_LARGE,
        VIDEO_TOO_LARGE,
        VIDEO_TOO_LONG,
        RETENTION_TOO_LONG,
        PASSWORD_REQUIRED,
        INVALID_PASSWORD,
        ACCESS_POLICY_CONFLICT,
        UPLOAD_RATE_LIMITED,
        DAILY_QUOTA_EXCEEDED,
        ACTIVE_STORAGE_QUOTA_EXCEEDED,
        GLOBAL_STORAGE_FULL,
        FILESYSTEM_FULL,
        STORAGE_FAILED,
        PERSISTENCE_FAILED,
        CREATE_FAILED
    }

    public record Options(
            boolean enabled,
            long defaultRetentionMillis,
            long maxRetentionMillis,
            long maxFileSizeBytes,
            long maxVideoFileSizeBytes,
            long maxVideoDurationMillis,
            long expiredShareRetentionMillis,
            long cleanupIntervalMillis,
            int codeLength,
            boolean allowDateDefaultPassword,
            int minPasswordLength,
            int maxPasswordLength,
            int filesystemStopPercent
    ) {
        private static final long MAX_RETENTION_MILLIS = 365L * 24L * 60L * 60L * 1000L;
        private static final long MAX_FILE_SIZE_BYTES = 20L * 1024L * 1024L;
        private static final long MAX_VIDEO_FILE_SIZE_BYTES = 100L * 1024L * 1024L;
        private static final long MAX_VIDEO_DURATION_MILLIS = 5L * 60L * 1000L;
        private static final long DEFAULT_EXPIRED_SHARE_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L;

        public Options {
            maxRetentionMillis = Math.max(1L, Math.min(MAX_RETENTION_MILLIS, maxRetentionMillis));
            defaultRetentionMillis = Math.max(1L, Math.min(maxRetentionMillis, defaultRetentionMillis));
            maxFileSizeBytes = Math.max(1L, Math.min(MAX_FILE_SIZE_BYTES, maxFileSizeBytes));
            maxVideoFileSizeBytes = Math.max(1L, Math.min(MAX_VIDEO_FILE_SIZE_BYTES, maxVideoFileSizeBytes));
            maxVideoDurationMillis = Math.max(1L, Math.min(MAX_VIDEO_DURATION_MILLIS, maxVideoDurationMillis));
            expiredShareRetentionMillis = Math.max(1L, Math.min(MAX_RETENTION_MILLIS, expiredShareRetentionMillis));
            cleanupIntervalMillis = Math.max(60_000L, cleanupIntervalMillis);
            codeLength = Math.max(4, Math.min(32, codeLength));
            minPasswordLength = Math.max(1, Math.min(128, minPasswordLength));
            maxPasswordLength = Math.max(minPasswordLength, Math.min(128, maxPasswordLength));
            filesystemStopPercent = Math.max(1, Math.min(100, filesystemStopPercent));
        }

        public Options(boolean enabled,
                       long defaultRetentionMillis,
                       long maxRetentionMillis,
                       long maxFileSizeBytes,
                       long maxVideoFileSizeBytes,
                       long maxVideoDurationMillis,
                       long expiredShareRetentionMillis,
                       long cleanupIntervalMillis,
                       int codeLength) {
            this(enabled, defaultRetentionMillis, maxRetentionMillis, maxFileSizeBytes,
                    maxVideoFileSizeBytes, maxVideoDurationMillis, expiredShareRetentionMillis,
                    cleanupIntervalMillis, codeLength, true, 4, 128, 80);
        }

        public Options(boolean enabled,
                       long defaultRetentionMillis,
                       long maxRetentionMillis,
                       long maxFileSizeBytes,
                       long maxVideoFileSizeBytes,
                       long maxVideoDurationMillis,
                       long cleanupIntervalMillis,
                       int codeLength) {
            this(enabled, defaultRetentionMillis, maxRetentionMillis, maxFileSizeBytes,
                    maxVideoFileSizeBytes, maxVideoDurationMillis, DEFAULT_EXPIRED_SHARE_RETENTION_MILLIS,
                    cleanupIntervalMillis, codeLength, true, 4, 128, 80);
        }

        public Options(boolean enabled,
                       long defaultRetentionMillis,
                       long maxRetentionMillis,
                       long maxFileSizeBytes,
                       long cleanupIntervalMillis,
                       int codeLength) {
            this(enabled, defaultRetentionMillis, maxRetentionMillis, maxFileSizeBytes,
                    MAX_VIDEO_FILE_SIZE_BYTES, MAX_VIDEO_DURATION_MILLIS, DEFAULT_EXPIRED_SHARE_RETENTION_MILLIS,
                    cleanupIntervalMillis, codeLength, true, 4, 128, 80);
        }

        public long maxUploadSizeBytes() {
            return Math.max(maxFileSizeBytes, maxVideoFileSizeBytes);
        }
    }

    public record Upload(
            byte[] content,
            boolean passwordProtected,
            String password,
            long requestedRetentionMillis,
            long requestedExpiresAtMillis
    ) {
        public Upload(byte[] content, boolean passwordProtected, String password, long requestedRetentionMillis) {
            this(content, passwordProtected, password, requestedRetentionMillis, 0L);
        }
    }

    public record UploadResult(ImageShare imageShare, UploadError error) {
        public boolean isSuccess() {
            return imageShare != null && error == null;
        }
    }

    public record SecurityDependencies(MediaPasswordAttemptGuard passwordAttemptGuard,
                                       MediaQuotaService quotaService) {
    }

    private static final char[] RANDOM_CODE_ALPHABET = "23456789abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int PASSWORD_ITERATIONS = 120_000;
    private static final int PASSWORD_KEY_LENGTH_BITS = 256;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ImageShareService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ImageShareDomainService domainService = new ImageShareDomainService();
    private final ImageShareRepository imageRepository;
    private final MediaBlobRepository blobRepository;
    private final ShortUrlRepository shortUrlRepository;
    private final ImageShareStorage storage;
    private final Clock clock;
    private final MediaPasswordAttemptGuard passwordAttemptGuard;
    private final MediaQuotaService quotaService;
    private final AtomicReference<Options> options;
    private volatile long lastCleanupAt;

    public ImageShareService(ImageShareRepository imageRepository,
                             ShortUrlRepository shortUrlRepository,
                             ImageShareStorage storage,
                             Options options) {
        this(imageRepository, shortUrlRepository, storage, options, Clock.systemDefaultZone());
    }

    public ImageShareService(ImageShareRepository imageRepository,
                             ShortUrlRepository shortUrlRepository,
                             ImageShareStorage storage,
                             Options options,
                             Clock clock) {
        this(imageRepository, shortUrlRepository, storage, options, clock, null);
    }

    public ImageShareService(ImageShareRepository imageRepository,
                             ShortUrlRepository shortUrlRepository,
                             ImageShareStorage storage,
                             Options options,
                             Clock clock,
                             SecurityDependencies securityDependencies) {
        this(imageRepository, new InMemoryMediaBlobRepository(), shortUrlRepository, storage,
                options, clock, securityDependencies);
    }

    public ImageShareService(ImageShareRepository imageRepository,
                             MediaBlobRepository blobRepository,
                             ShortUrlRepository shortUrlRepository,
                             ImageShareStorage storage,
                             Options options,
                             Clock clock,
                             SecurityDependencies securityDependencies) {
        if (imageRepository == null || shortUrlRepository == null || storage == null) {
            throw new IllegalArgumentException("image share dependencies cannot be null");
        }
        this.imageRepository = imageRepository;
        this.blobRepository = blobRepository == null ? new InMemoryMediaBlobRepository() : blobRepository;
        this.shortUrlRepository = shortUrlRepository;
        this.storage = storage;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
        this.options = new AtomicReference<>(options == null
                ? new Options(true, 60L * 60L * 1000L, 365L * 24L * 60L * 60L * 1000L,
                20L * 1024L * 1024L, 100L * 1024L * 1024L, 5L * 60L * 1000L,
                30L * 24L * 60L * 60L * 1000L, 10L * 60L * 1000L, 7,
                true, 4, 128, 80)
                : options);
        if (securityDependencies == null) {
            InMemoryMediaSecurityRepository securityRepository = new InMemoryMediaSecurityRepository();
            this.passwordAttemptGuard = new MediaPasswordAttemptGuard(
                    securityRepository, MediaPasswordAttemptGuard.Options.defaults(), "");
            this.quotaService = new MediaQuotaService(
                    securityRepository, new MediaQuotaService.Options(false, null, null, Long.MAX_VALUE));
        } else {
            this.passwordAttemptGuard = securityDependencies.passwordAttemptGuard();
            this.quotaService = securityDependencies.quotaService();
        }
        migrateLegacyMedia();
    }

    public synchronized UploadResult create(Upload upload) {
        return create(upload, null);
    }

    public synchronized UploadResult create(Upload upload, QuotaSubject quotaSubject) {
        Options currentOptions = options.get();
        if (!currentOptions.enabled()) {
            return new UploadResult(null, UploadError.DISABLED);
        }
        if (upload == null || upload.content() == null || upload.content().length == 0) {
            return new UploadResult(null, UploadError.IMAGE_REQUIRED);
        }
        ImageShareDomainService.MediaType mediaType = domainService.detectMediaType(upload.content());
        if (mediaType == null) {
            return new UploadResult(null, UploadError.UNSUPPORTED_MEDIA);
        }
        if (mediaType.video()) {
            if (upload.content().length > currentOptions.maxVideoFileSizeBytes()) {
                return new UploadResult(null, UploadError.VIDEO_TOO_LARGE);
            }
            if (mediaType.durationMillis() > currentOptions.maxVideoDurationMillis()) {
                return new UploadResult(null, UploadError.VIDEO_TOO_LONG);
            }
        } else if (upload.content().length > currentOptions.maxFileSizeBytes()) {
            return new UploadResult(null, UploadError.IMAGE_TOO_LARGE);
        }

        long now = clock.millis();
        boolean customExpiration = upload.requestedExpiresAtMillis() > 0L;
        long retention;
        if (customExpiration) {
            retention = upload.requestedExpiresAtMillis() - now;
        } else if (upload.requestedRetentionMillis() <= 0L) {
            retention = currentOptions.defaultRetentionMillis();
        } else {
            retention = upload.requestedRetentionMillis();
        }
        if (retention <= 0L || retention > currentOptions.maxRetentionMillis()) {
            return new UploadResult(null, UploadError.RETENTION_TOO_LONG);
        }
        long expiresAt = customExpiration ? upload.requestedExpiresAtMillis() : now + retention;

        String effectivePassword = "";
        if (upload.passwordProtected()) {
            effectivePassword = domainService.normalizePassword(upload.password());
            if (effectivePassword.isBlank()) {
                if (!currentOptions.allowDateDefaultPassword()) {
                    return new UploadResult(null, UploadError.PASSWORD_REQUIRED);
                }
                effectivePassword = domainService.defaultPassword(LocalDate.now(clock));
            }
            if (!domainService.isValidPassword(effectivePassword,
                    currentOptions.minPasswordLength(), currentOptions.maxPasswordLength())) {
                return new UploadResult(null, UploadError.INVALID_PASSWORD);
            }
        }

        maybeCleanup(now);
        String contentHash = contentHash(upload.content());
        MediaBlob existingBlob = blobRepository.findBySha256(contentHash);
        ImageShare existingReusable = existingBlob == null ? null : findReusableShare(
                existingBlob, now, upload.passwordProtected(), effectivePassword,
                customExpiration, retention, expiresAt, quotaSubject);
        boolean createsShare = existingReusable == null;
        if (existingReusable != null && !hasSameAccessAndExpiration(existingReusable,
                upload.passwordProtected(), effectivePassword, customExpiration, retention, expiresAt, quotaSubject)) {
            return new UploadResult(null, UploadError.ACCESS_POLICY_CONFLICT);
        }
        long logicalAdditionalBytes = createsShare ? upload.content().length : 0L;
        long physicalAdditionalBytes = addsManagedStorage(existingBlob)
                ? upload.content().length : 0L;
        if (storage.filesystemUsagePercent() >= currentOptions.filesystemStopPercent()) {
            return new UploadResult(null, UploadError.FILESYSTEM_FULL);
        }
        if (quotaService != null && quotaSubject != null) {
            MediaQuotaService.Rejection rejection = quotaService.checkUpload(
                    quotaSubject, logicalAdditionalBytes, physicalAdditionalBytes,
                    retention, createsShare);
            UploadError quotaError = mapQuotaRejection(rejection);
            if (quotaError != null) {
                return new UploadResult(null, quotaError);
            }
        }
        MediaBlob blob;
        try {
            blob = findOrCreateBlob(contentHash, upload.content(), mediaType, now);
        } catch (Exception e) {
            logUploadFailure("store", e);
            return new UploadResult(null, UploadError.STORAGE_FAILED);
        }

        ImageShare reusable = findReusableShare(blob, now, upload.passwordProtected(), effectivePassword,
                customExpiration, retention, expiresAt, quotaSubject);
        if (reusable != null) {
            if (!hasSameAccessAndExpiration(reusable, upload.passwordProtected(), effectivePassword,
                    customExpiration, retention, expiresAt, quotaSubject)) {
                return new UploadResult(null, UploadError.ACCESS_POLICY_CONFLICT);
            }
            return new UploadResult(hydrate(reusable), null);
        }
        String passwordHash = upload.passwordProtected() ? hashPassword(effectivePassword) : "";
        imageRepository.releaseExpiredReuseKeys(now);
        for (int attempt = 0; attempt < 10_000; attempt++) {
            String code = nextAvailableCode(currentOptions.codeLength());
            if (code == null) {
                break;
            }
            ImageShare imageShare = new ImageShare(
                    code,
                    blob.storageName(),
                    blob.contentType(),
                    blob.sizeBytes(),
                    now,
                    expiresAt,
                    passwordHash,
                    blob.sha256(),
                    0L,
                    blob.storageState(),
                    blob.archiveStorageName(),
                    blob.archivedAt(),
                    quotaSubject == null ? MediaOwnerType.ANONYMOUS_DEVICE : quotaSubject.ownerType(),
                    quotaSubject == null ? "" : quotaSubject.ownerId(),
                    quotaSubject == null ? "" : quotaSubject.quotaGroupId(),
                    quotaSubject == null ? "" : quotaSubject.deviceIdHash(),
                    quotaSubject == null ? "" : quotaSubject.ipHash(),
                    0L,
                    blob.id()
            );
            try {
                if (!imageRepository.saveIfAbsent(imageShare)) {
                    ImageShare concurrent = findReusableShare(blob, now, upload.passwordProtected(),
                            effectivePassword, customExpiration, retention, expiresAt, quotaSubject);
                    if (concurrent != null) {
                        if (!hasSameAccessAndExpiration(concurrent, upload.passwordProtected(), effectivePassword,
                                customExpiration, retention, expiresAt, quotaSubject)) {
                            return new UploadResult(null, UploadError.ACCESS_POLICY_CONFLICT);
                        }
                        return new UploadResult(hydrate(concurrent), null);
                    }
                    continue;
                }
                if (quotaService != null && quotaSubject != null) {
                    quotaService.recordCreatedShare(quotaSubject, imageShare.sizeBytes());
                }
                return new UploadResult(imageShare, null);
            } catch (RuntimeException e) {
                logUploadFailure("save metadata for", e);
                return new UploadResult(null, UploadError.PERSISTENCE_FAILED);
            }
        }
        return new UploadResult(null, UploadError.CREATE_FAILED);
    }

    public ImageShare resolve(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        long now = clock.millis();
        maybeCleanup(now);
        ImageShare imageShare = hydrate(imageRepository.findByCode(code.trim()));
        if (imageShare == null) {
            return null;
        }
        if (!imageShare.isPubliclyAvailable(now)) {
            if (imageShare.storageState() == MediaStorageState.ACTIVE
                    && imageShare.expiresAt() <= now) {
                retireExpiredShare(imageShare, now);
            }
            return null;
        }
        if (!storage.exists(imageShare)) {
            markBlobMissing(imageShare);
            return null;
        }
        return imageShare;
    }

    /**
     * Finds an expired share retained for the configured post-expiration period.
     * The record is only used to present an expiration response and cannot be served.
     */
    public ImageShare findExpired(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        long now = clock.millis();
        maybeCleanup(now);
        ImageShare imageShare = hydrate(imageRepository.findByCode(code.trim()));
        return imageShare != null && !imageShare.isPubliclyAvailable(now) ? imageShare : null;
    }

    public ImageShare findByCodeForOwner(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return hydrate(imageRepository.findByCode(code.trim()));
    }

    public List<ImageShare> findByOwnerUserId(String ownerUserId, int offset, int limit) {
        return findByOwnerUserId(ownerUserId, null, 0L, offset, limit);
    }

    public List<ImageShare> findByOwnerUserId(String ownerUserId,
                                              Boolean active,
                                              long nowMillis,
                                              int offset,
                                              int limit) {
        if (ownerUserId == null || ownerUserId.isBlank()) {
            return List.of();
        }
        return imageRepository.findByOwnerUserId(ownerUserId.trim(), active, nowMillis, offset, limit)
                .stream().map(this::hydrate).toList();
    }

    public long countByOwnerUserId(String ownerUserId) {
        return countByOwnerUserId(ownerUserId, null, 0L);
    }

    public long countByOwnerUserId(String ownerUserId, Boolean active, long nowMillis) {
        if (ownerUserId == null || ownerUserId.isBlank()) {
            return 0L;
        }
        return imageRepository.countByOwnerUserId(ownerUserId.trim(), active, nowMillis);
    }

    public InputStream open(ImageShare imageShare) {
        ImageShare hydrated = hydrate(imageShare);
        if (hydrated == null || !hydrated.isPubliclyAvailable(clock.millis())) {
            return null;
        }
        try {
            return storage.open(hydrated);
        } catch (Exception ignored) {
            markBlobMissing(hydrated);
            return null;
        }
    }

    public boolean verifyPassword(ImageShare imageShare, String password) {
        if (imageShare == null || !imageShare.isPasswordProtected()) {
            return imageShare != null;
        }
        String normalized = domainService.normalizePassword(password);
        return !normalized.isBlank() && verifyPasswordHash(normalized, imageShare.passwordHash());
    }

    public MediaPasswordAttemptGuard.Result verifyPasswordGuarded(ImageShare imageShare, String password,
                                                                  String clientIp) {
        if (imageShare == null || !imageShare.isPasswordProtected()) {
            return new MediaPasswordAttemptGuard.Result(
                    imageShare == null ? MediaPasswordAttemptGuard.Status.INVALID_PASSWORD
                            : MediaPasswordAttemptGuard.Status.SUCCESS, 0L);
        }
        if (passwordAttemptGuard == null) {
            return verifyPassword(imageShare, password)
                    ? new MediaPasswordAttemptGuard.Result(MediaPasswordAttemptGuard.Status.SUCCESS, 0L)
                    : new MediaPasswordAttemptGuard.Result(MediaPasswordAttemptGuard.Status.INVALID_PASSWORD, 0L);
        }
        return passwordAttemptGuard.verify(clientIp, imageShare.code(),
                () -> verifyPassword(imageShare, password));
    }

    public ImageShare recordView(ImageShare imageShare) {
        if (imageShare == null) {
            return null;
        }
        long accessedAt = clock.millis();
        long viewCount = imageRepository.incrementViewCount(imageShare.code(), accessedAt);
        return hydrate(imageShare.withViewMetrics(viewCount, accessedAt));
    }

    public Options options() {
        return options.get();
    }

    public Path createTemporaryUpload() throws IOException {
        return storage.createTemporaryUpload();
    }

    public void deleteTemporaryUpload(Path path) throws IOException {
        storage.deleteTemporaryUpload(path);
    }

    public boolean isCodeInUse(String code) {
        return code != null && !code.isBlank() && imageRepository.isCodeInUseIgnoreCase(code.trim());
    }

    public void updateOptions(Options updatedOptions) {
        if (updatedOptions != null) {
            options.set(updatedOptions);
        }
    }

    public synchronized void cleanupExpired() {
        long now = clock.millis();
        migrateLegacyMedia();
        canonicalizeLegacyBlobs();
        imageRepository.releaseExpiredReuseKeys(now);
        for (ImageShare expired : imageRepository.findExpired(now)) {
            if (expired.expiresAt() > lastCleanupAt) LOG.info(
                    "event=MEDIA_SHARE_EXPIRED code={} ownerUserId={} blobId={} sha256={} expiredAt={} codeReleased=false",
                    expired.code(), expired.ownerId(), expired.blobId(), expired.contentHash(), expired.expiresAt());
        }
        for (MediaBlob blob : blobRepository.findByStorageStates(Set.of(MediaStorageState.ACTIVE))) {
            if (!hasActiveShare(blob, now)) {
                MediaBlob pending = blob.withStorageState(
                        MediaStorageState.ARCHIVE_PENDING, blob.archiveStorageName(), blob.archivedAt());
                updateBlobState(pending);
                archivePendingBlob(pending, now);
            }
        }
        retryPendingBlobArchives(now);
        reconcileArchivedBlobs(now);
        purgeExpiredArchives(now);
        lastCleanupAt = now;
    }

    private void maybeCleanup(long now) {
        if (now - lastCleanupAt < options.get().cleanupIntervalMillis()) {
            return;
        }
        synchronized (this) {
            if (now - lastCleanupAt >= options.get().cleanupIntervalMillis()) {
                cleanupExpired();
            }
        }
    }

    private String nextAvailableCode(int codeLength) {
        for (int attempt = 0; attempt < 10_000; attempt++) {
            String code = randomCode(codeLength);
            if (!imageRepository.isCodeInUseIgnoreCase(code) && shortUrlRepository.findByCodeIgnoreCase(code) == null) {
                return code;
            }
        }
        return null;
    }

    private String randomCode(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = RANDOM_CODE_ALPHABET[SECURE_RANDOM.nextInt(RANDOM_CODE_ALPHABET.length)];
        }
        return new String(chars);
    }

    private String contentHash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash image-share content", e);
        }
    }

    private MediaBlob findOrCreateBlob(String sha256, byte[] content,
                                       ImageShareDomainService.MediaType mediaType,
                                       long now) throws Exception {
        MediaBlob existing = blobRepository.findBySha256(sha256);
        if (existing != null) {
            return ensureActiveBlob(existing, content);
        }

        MediaBlob candidate = new MediaBlob(
                0L,
                sha256,
                sha256 + "." + mediaType.extension(),
                content.length,
                mediaType.contentType(),
                mediaType.extension(),
                now,
                MediaStorageState.ACTIVE,
                "",
                0L
        );
        ImageShare candidateFile = storageView(candidate);
        storage.save(candidateFile, content);
        MediaBlob persisted;
        try {
            persisted = blobRepository.saveIfAbsent(candidate);
        } catch (RuntimeException exception) {
            // Another successful insert may already reference this content-addressed file.
            throw exception;
        }
        if (!persisted.storageName().equals(candidate.storageName())) {
            storage.delete(candidateFile);
        }
        return ensureActiveBlob(persisted, content);
    }

    private MediaBlob ensureActiveBlob(MediaBlob blob, byte[] content) throws Exception {
        ImageShare file = storageView(blob);
        if (blob.storageState() == MediaStorageState.ACTIVE && storage.exists(file)) {
            return blob;
        }
        if (!storage.restore(file)) {
            storage.save(file.withStorageState(MediaStorageState.ACTIVE, "", 0L), content);
        }
        if (storage.existsArchived(file)) {
            storage.deleteArchived(file);
        }
        MediaBlob active = blob.withStorageState(MediaStorageState.ACTIVE, "", 0L);
        updateBlobState(active);
        return active;
    }

    private boolean addsManagedStorage(MediaBlob blob) {
        return blob == null || (blob.storageState() != MediaStorageState.ACTIVE
                && blob.storageState() != MediaStorageState.ARCHIVE_PENDING
                && blob.storageState() != MediaStorageState.ARCHIVED);
    }

    private ImageShare findReusableShare(MediaBlob blob,
                                         long now,
                                         boolean passwordProtected,
                                         String password,
                                         boolean customExpiration,
                                         long retention,
                                         long expiresAt,
                                         QuotaSubject quotaSubject) {
        if (quotaSubject != null && !quotaSubject.ownerId().isBlank()) {
            ImageShare reusable = imageRepository.findActiveByOwnerAndBlob(
                    quotaSubject.ownerType(), quotaSubject.ownerId(), blob.id(), now);
            if (reusable != null) {
                return reusable;
            }
            return imageRepository.findActiveByContentHash(blob.sha256(), now).stream()
                    .filter(existing -> existing.blobId() == blob.id()
                            && existing.ownerType() == quotaSubject.ownerType()
                            && quotaSubject.ownerId().equals(existing.ownerId()))
                    .findFirst()
                    .orElse(null);
        }
        for (ImageShare existing : imageRepository.findActiveByContentHash(blob.sha256(), now)) {
            if (hasSameAccessAndExpiration(existing, passwordProtected, password,
                    customExpiration, retention, expiresAt, quotaSubject)) {
                return existing;
            }
        }
        return null;
    }

    private ImageShare hydrate(ImageShare imageShare) {
        if (imageShare == null || imageShare.blobId() <= 0L) {
            return imageShare;
        }
        MediaBlob blob = blobRepository.findById(imageShare.blobId());
        return blob == null ? imageShare : imageShare.withBlob(blob);
    }

    private ImageShare storageView(MediaBlob blob) {
        return new ImageShare(
                "blob-" + Math.max(0L, blob.id()), blob.storageName(), blob.contentType(),
                blob.sizeBytes(), blob.createdAt(), Long.MAX_VALUE, "", blob.sha256(), 0L,
                blob.storageState(), blob.archiveStorageName(), blob.archivedAt(),
                MediaOwnerType.ANONYMOUS_DEVICE, "", "", "", "", 0L, blob.id());
    }

    private void markBlobMissing(ImageShare imageShare) {
        if (imageShare == null || imageShare.blobId() <= 0L) {
            return;
        }
        MediaBlob blob = blobRepository.findById(imageShare.blobId());
        if (blob != null) {
            updateBlobState(blob.withStorageState(MediaStorageState.MISSING, "", 0L));
        }
    }

    private void updateBlobState(MediaBlob blob) {
        blobRepository.update(blob);
        imageRepository.updateStorageStateForBlob(blob.id(), blob.storageState(),
                blob.archiveStorageName(), blob.archivedAt());
    }

    private void retryPendingBlobArchives(long now) {
        for (MediaBlob pending : blobRepository.findByStorageStates(
                Set.of(MediaStorageState.ARCHIVE_PENDING))) {
            if (!hasActiveShare(pending, now)) {
                archivePendingBlob(pending, now);
            }
        }
    }

    private void archivePendingBlob(MediaBlob pending, long now) {
        if (hasActiveShare(pending, now)) {
            updateBlobState(pending.withStorageState(MediaStorageState.ACTIVE, "", 0L));
            return;
        }
        ImageShare file = storageView(pending);
        try {
            ImageShareStorage.ArchiveResult result = storage.archiveOrReconcile(file);
            if (result.status() == ImageShareStorage.ArchiveStatus.MISSING) {
                MediaBlob missing = pending.withStorageState(MediaStorageState.MISSING, "", 0L);
                updateBlobState(missing);
                logMissingMedia(file, MediaStorageState.MISSING);
                return;
            }
            long archivedAt = pending.archivedAt() > 0L ? pending.archivedAt() : now;
            MediaBlob archived = pending.withStorageState(
                    MediaStorageState.ARCHIVED, result.archiveStorageName(), archivedAt);
            updateBlobState(archived);
            LOG.info("event=MEDIA_BLOB_ARCHIVED blobId={} sha256={} storageName={} archivedAt={} purgeAt={} reason=NO_ACTIVE_SHARES",
                    archived.id(), archived.sha256(), archived.storageName(), archivedAt,
                    archivedAt + options.get().expiredShareRetentionMillis());
            logArchiveReconciliation(file, result.status());
        } catch (Exception exception) {
            logArchiveFailure(file, exception);
        }
    }

    private void reconcileArchivedBlobs(long now) {
        for (MediaBlob archived : blobRepository.findByStorageStates(Set.of(MediaStorageState.ARCHIVED))) {
            ImageShare file = storageView(archived);
            try {
                ImageShareStorage.ArchiveResult result = storage.archiveOrReconcile(file);
                if (result.status() == ImageShareStorage.ArchiveStatus.MISSING) {
                    updateBlobState(archived.withStorageState(
                            MediaStorageState.ARCHIVE_DELETED,
                            archived.archiveStorageName(), archived.archivedAt()));
                    logMissingMedia(file, MediaStorageState.ARCHIVE_DELETED);
                    continue;
                }
                long archivedAt = archived.archivedAt() > 0L ? archived.archivedAt() : now;
                if (!result.archiveStorageName().equals(archived.archiveStorageName())
                        || archived.archivedAt() <= 0L) {
                    updateBlobState(archived.withStorageState(
                            MediaStorageState.ARCHIVED, result.archiveStorageName(), archivedAt));
                }
            } catch (Exception exception) {
                logArchiveFailure(file, exception);
            }
        }
    }

    private void purgeExpiredArchives(long now) {
        for (MediaBlob blob : blobRepository.findByStorageStates(
                Set.of(MediaStorageState.ARCHIVED, MediaStorageState.ARCHIVE_DELETED))) {
            if (hasActiveShare(blob, now) || now - blob.archivedAt() < options.get().expiredShareRetentionMillis()) {
                continue;
            }
            try {
                storage.deleteArchived(storageView(blob));
                blobRepository.deleteById(blob.id());
                LOG.info("event=MEDIA_BLOB_PURGED blobId={} sha256={} storageName={} archivedAt={} purgedAt={} retentionDays={}",
                        blob.id(), blob.sha256(), blob.storageName(), blob.archivedAt(), now,
                        options.get().expiredShareRetentionMillis() / 86_400_000L);
            } catch (Exception exception) {
                logUploadFailure("purge archive", exception);
            }
        }
    }

    public synchronized com.norule.musicbot.ShortUrlService.MutationResult deleteOwned(String code, String owner) {
        if (owner == null || owner.isBlank()) return com.norule.musicbot.ShortUrlService.MutationResult.UNAUTHORIZED;
        ImageShare share = findByCodeForOwner(code);
        if (share == null) return com.norule.musicbot.ShortUrlService.MutationResult.NOT_FOUND;
        if (share.ownerType() != MediaOwnerType.DISCORD_USER || !owner.equals(share.ownerId())) {
            return com.norule.musicbot.ShortUrlService.MutationResult.FORBIDDEN;
        }
        if (!imageRepository.deleteOwned(share.code(), owner, share.createdAt())) {
            return com.norule.musicbot.ShortUrlService.MutationResult.NOT_FOUND;
        }
        long now = clock.millis();
        retireExpiredShare(share, now);
        LOG.info("event=MEDIA_SHARE_PERMANENTLY_DELETED actorType=DISCORD_USER userId={} code={} blobId={} sha256={} wasExpired={} deletedAt={} codeReleased=true physicalMediaDeleted=false",
                owner, share.code(), share.blobId(), share.contentHash(), share.expiresAt() <= now, now);
        return com.norule.musicbot.ShortUrlService.MutationResult.SUCCESS;
    }

    private boolean hasActiveShare(MediaBlob blob, long now) {
        if (blob == null || blob.id() <= 0L) {
            return false;
        }
        if (imageRepository.hasActiveShareForBlob(blob.id(), now)) {
            return true;
        }
        return imageRepository.findActiveByContentHash(blob.sha256(), now).stream()
                .anyMatch(share -> share.blobId() == blob.id()
                        || blob.sha256().equals(share.contentHash()));
    }

    private void canonicalizeLegacyBlobs() {
        for (MediaBlob previous : blobRepository.findByStorageStates(
                Set.of(MediaStorageState.ACTIVE, MediaStorageState.ARCHIVED, MediaStorageState.ARCHIVE_PENDING))) {
            if (previous.storageName().equals(previous.sha256() + "." + previous.extension())
                    && (previous.archiveStorageName().isBlank()
                    || previous.archiveStorageName().equals(previous.storageName()))) continue;
            try {
                ImageShare source = storageView(previous);
                HashAndSize actual;
                if (storage.exists(source)) {
                    actual = hashStream(storage.open(source), MediaStorageState.ACTIVE, "", 0L);
                } else if (storage.existsArchived(source)) {
                    actual = hashStream(storage.openArchived(source), MediaStorageState.ARCHIVED,
                            source.archiveStorageName(), source.archivedAt());
                } else {
                    continue;
                }
                source = source.withStorageState(actual.storageState(), actual.archiveStorageName(), actual.archivedAt());
                byte[] header;
                try (InputStream input = actual.storageState() == MediaStorageState.ARCHIVED
                        ? storage.openArchived(source) : storage.open(source)) {
                    header = input.readNBytes(64 * 1024);
                }
                ImageShareDomainService.MediaType detected = domainService.detectStoredMediaType(header);
                if (detected == null) throw new IOException("Cannot detect legacy media content type");
                String extension = detected.extension();
                String contentType = detected.contentType();
                String name = actual.sha256() + "." + extension;
                MediaBlob existing = blobRepository.findBySha256(actual.sha256());
                boolean active = hasActiveShare(previous, clock.millis()) || hasActiveShare(existing, clock.millis());
                MediaStorageState state = active ? MediaStorageState.ACTIVE : actual.storageState();
                long archivedAt = state == MediaStorageState.ARCHIVED ? actual.archivedAt() : 0L;
                if (existing != null && existing.storageState() == MediaStorageState.ARCHIVED
                        && existing.archivedAt() > 0L && archivedAt > 0L) {
                    archivedAt = Math.min(archivedAt, existing.archivedAt());
                }
                MediaBlob canonical = new MediaBlob(existing == null ? 0L : existing.id(), actual.sha256(), name,
                        actual.sizeBytes(), contentType, extension, previous.createdAt(), state,
                        state == MediaStorageState.ARCHIVED ? name : "", archivedAt);
                storage.canonicalize(source, storageView(canonical));
                if (canonical.id() == 0L) canonical = blobRepository.saveIfAbsent(canonical);
                else blobRepository.update(canonical);
                imageRepository.replaceBlobReferences(previous.id(), canonical);
                if (previous.id() != canonical.id()) blobRepository.deleteById(previous.id());
                if (!previous.storageName().equals(name)) {
                    // Canonical contents were verified before switching all references.
                    storage.delete(source);
                    storage.deleteArchived(source);
                    storage.deleteLegacy(source);
                }
            } catch (Exception exception) {
                logUploadFailure("canonicalize legacy", exception);
            }
        }
    }

    private void migrateLegacyMedia() {
        for (ImageShare legacy : imageRepository.findWithoutBlob(10_000)) {
            try {
                HashAndSize actual = hashStoredMedia(legacy);
                if (actual == null) {
                    continue;
                }
                String extension = extensionOf(legacy.storageName());
                MediaBlob candidate = new MediaBlob(0L, actual.sha256(), legacy.storageName(),
                        actual.sizeBytes(), legacy.contentType(), extension, legacy.createdAt(),
                        actual.storageState(), actual.archiveStorageName(), actual.archivedAt());
                MediaBlob blob = blobRepository.saveIfAbsent(candidate);
                imageRepository.attachBlob(legacy.code(), blob.id(), actual.sha256());
                deleteMigratedDuplicate(legacy, blob);
                imageRepository.alignStorageWithBlob(legacy.code(), blob);
            } catch (Exception exception) {
                logUploadFailure("migrate legacy", exception);
            }
        }
        for (ImageShare legacy : imageRepository.findLinkedStorageMismatches(10_000)) {
            MediaBlob blob = blobRepository.findById(legacy.blobId());
            if (blob == null) {
                continue;
            }
            try {
                deleteMigratedDuplicate(legacy, blob);
                imageRepository.alignStorageWithBlob(legacy.code(), blob);
            } catch (Exception exception) {
                logUploadFailure("clean migrated duplicate", exception);
            }
        }
    }

    private HashAndSize hashStoredMedia(ImageShare legacy) throws Exception {
        if (legacy.storageState() == MediaStorageState.MISSING
                || legacy.storageState() == MediaStorageState.ARCHIVE_DELETED) {
            return null;
        }
        if (storage.exists(legacy)) {
            return hashStream(storage.open(legacy), MediaStorageState.ACTIVE, "", 0L);
        }
        if (storage.existsArchived(legacy)) {
            return hashArchivedLegacy(legacy, legacy.archiveStorageName().isBlank()
                    ? legacy.storageName() : legacy.archiveStorageName());
        }
        InputStream legacyInput = storage.openLegacy(legacy);
        if (legacyInput != null) {
            if (legacy.expiresAt() > clock.millis()) {
                byte[] content;
                try (InputStream input = legacyInput) {
                    content = input.readAllBytes();
                }
                storage.save(legacy.withStorageState(MediaStorageState.ACTIVE, "", 0L), content);
                storage.deleteLegacy(legacy);
                return new HashAndSize(contentHash(content), content.length,
                        MediaStorageState.ACTIVE, "", 0L);
            }
            return hashStream(legacyInput, MediaStorageState.ACTIVE, "", 0L);
        }

        ImageShareStorage.ArchiveResult result;
        try {
            result = storage.archiveOrReconcile(legacy);
        } catch (Exception exception) {
            imageRepository.update(legacy.withStorageState(
                    MediaStorageState.ARCHIVE_PENDING,
                    legacy.archiveStorageName(), legacy.archivedAt()));
            throw exception;
        }
        if (result.status() == ImageShareStorage.ArchiveStatus.MISSING) {
            imageRepository.update(legacy.withStorageState(MediaStorageState.MISSING, "", 0L));
            logMissingMedia(legacy, MediaStorageState.MISSING);
            return null;
        }
        return hashArchivedLegacy(legacy, result.archiveStorageName());
    }

    private HashAndSize hashArchivedLegacy(ImageShare legacy, String archiveStorageName) throws Exception {
        long archivedAt = legacy.archivedAt() > 0L ? legacy.archivedAt() : clock.millis();
        ImageShare archived = legacy.withStorageState(
                MediaStorageState.ARCHIVED, archiveStorageName, archivedAt);
        if (legacy.expiresAt() > clock.millis()) {
            byte[] content;
            try (InputStream input = storage.openArchived(archived)) {
                content = input.readAllBytes();
            }
            ImageShare active = legacy.withStorageState(MediaStorageState.ACTIVE, "", 0L);
            storage.save(active, content);
            storage.deleteArchived(archived);
            return new HashAndSize(contentHash(content), content.length,
                    MediaStorageState.ACTIVE, "", 0L);
        }
        return hashStream(storage.openArchived(archived), MediaStorageState.ARCHIVED,
                archiveStorageName, archivedAt);
    }

    private HashAndSize hashStream(InputStream input, MediaStorageState storageState,
                                   String archiveStorageName, long archivedAt) throws Exception {
        try (InputStream content = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long size = 0L;
            int read;
            while ((read = content.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
            return new HashAndSize(HexFormat.of().formatHex(digest.digest()), size,
                    storageState, archiveStorageName, archivedAt);
        }
    }

    private void deleteMigratedDuplicate(ImageShare legacy, MediaBlob blob) throws Exception {
        if (legacy.storageName().equals(blob.storageName())) {
            return;
        }
        ImageShare canonical = storageView(blob);
        HashAndSize verified;
        if (storage.exists(canonical)) {
            verified = hashStream(storage.open(canonical), MediaStorageState.ACTIVE, "", 0L);
        } else if (storage.existsArchived(canonical)) {
            verified = hashStream(storage.openArchived(canonical), MediaStorageState.ARCHIVED,
                    canonical.archiveStorageName(), canonical.archivedAt());
        } else {
            throw new IOException("Canonical media is missing; retaining legacy file");
        }
        if (!verified.sha256().equals(blob.sha256()) || verified.sizeBytes() != blob.sizeBytes()) {
            throw new IOException("Canonical media verification failed; retaining legacy file");
        }
        ImageShare activeLegacy = legacy.withStorageState(MediaStorageState.ACTIVE, "", 0L);
        ImageShare archivedLegacy = legacy.withStorageState(
                MediaStorageState.ARCHIVED, legacy.storageName(), legacy.archivedAt());
        storage.delete(activeLegacy);
        storage.deleteArchived(archivedLegacy);
    }

    private String extensionOf(String storageName) {
        int dot = storageName == null ? -1 : storageName.lastIndexOf('.');
        return dot < 0 || dot == storageName.length() - 1 ? "bin" : storageName.substring(dot + 1);
    }

    private record HashAndSize(String sha256, long sizeBytes,
                               MediaStorageState storageState,
                               String archiveStorageName,
                               long archivedAt) {
    }

    private boolean hasSameAccessAndExpiration(ImageShare existing,
                                               boolean passwordProtected,
                                               String password,
                                               boolean customExpiration,
                                               long retention,
                                               long expiresAt,
                                               QuotaSubject quotaSubject) {
        String requestedQuotaGroup = quotaSubject == null ? "" : quotaSubject.quotaGroupId();
        if (!existing.quotaGroupId().equals(requestedQuotaGroup)) {
            return false;
        }
        if (existing.isPasswordProtected() != passwordProtected) {
            return false;
        }
        if (passwordProtected && !verifyPasswordHash(password, existing.passwordHash())) {
            return false;
        }
        if (customExpiration) {
            return existing.expiresAt() == expiresAt;
        }
        return existing.expiresAt() - existing.createdAt() == retention;
    }

    private void retireExpiredShare(ImageShare imageShare, long now) {
        ImageShare hydrated = hydrate(imageShare);
        if (hydrated == null || hydrated.blobId() <= 0L
                || hasActiveShare(blobRepository.findById(hydrated.blobId()), now)) {
            return;
        }
        MediaBlob blob = blobRepository.findById(hydrated.blobId());
        if (blob != null && blob.storageState() == MediaStorageState.ACTIVE) {
            MediaBlob pending = blob.withStorageState(
                    MediaStorageState.ARCHIVE_PENDING, blob.archiveStorageName(), blob.archivedAt());
            updateBlobState(pending);
            archivePendingBlob(pending, now);
        }
    }

    private void logUploadFailure(String action, Exception exception) {
        String detail = exception.getMessage();
        System.err.println("[NoRule] Failed to " + action + " short-url media: "
                + exception.getClass().getSimpleName() + (detail == null || detail.isBlank() ? "" : " - " + detail));
    }

    private void logArchiveFailure(ImageShare imageShare, Exception exception) {
        String detail = exception.getMessage();
        System.err.println("[NoRule] Failed to archive expired short-url media " + imageShare.code() + ": "
                + exception.getClass().getSimpleName()
                + (detail == null || detail.isBlank() ? "" : " - " + detail));
    }

    private void logArchiveReconciliation(ImageShare imageShare, ImageShareStorage.ArchiveStatus status) {
        if (status == ImageShareStorage.ArchiveStatus.ALREADY_ARCHIVED) {
            System.out.println("[NoRule] Reconciled expired short-url media " + imageShare.code()
                    + ": archive file already exists; metadata marked ARCHIVED");
        } else if (status == ImageShareStorage.ArchiveStatus.LEGACY_MIGRATED) {
            System.out.println("[NoRule] Migrated expired short-url media " + imageShare.code()
                    + " from legacy storage; metadata marked ARCHIVED");
        }
    }

    private void logMissingMedia(ImageShare imageShare, MediaStorageState terminalState) {
        System.err.println("[NoRule] Expired short-url media " + imageShare.code()
                + " is missing from active, archive, and legacy storage; metadata marked "
                + terminalState.name());
    }

    private UploadError mapQuotaRejection(MediaQuotaService.Rejection rejection) {
        if (rejection == null || rejection == MediaQuotaService.Rejection.NONE) {
            return null;
        }
        return switch (rejection) {
            case RETENTION_LIMIT -> UploadError.RETENTION_TOO_LONG;
            case ROLLING_RATE_LIMIT -> UploadError.UPLOAD_RATE_LIMITED;
            case DAILY_LIMIT -> UploadError.DAILY_QUOTA_EXCEEDED;
            case ACTIVE_STORAGE_LIMIT -> UploadError.ACTIVE_STORAGE_QUOTA_EXCEEDED;
            case GLOBAL_STORAGE_LIMIT -> UploadError.GLOBAL_STORAGE_FULL;
            case NONE -> null;
        };
    }

    public int maximumPasswordVerificationConcurrency() {
        return passwordAttemptGuard == null ? 0 : passwordAttemptGuard.maximumConcurrency();
    }

    public long maxRetentionMillisFor(QuotaSubject quotaSubject) {
        long configured = options.get().maxRetentionMillis();
        if (quotaSubject == null || quotaService == null) {
            return configured;
        }
        return Math.min(configured, quotaService.maxRetentionMillis(quotaSubject.accessTier()));
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        byte[] hash = derivePasswordHash(password.toCharArray(), salt, PASSWORD_ITERATIONS);
        return PASSWORD_ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    private boolean verifyPasswordHash(String password, String encodedHash) {
        try {
            String[] parts = encodedHash.split(":", 3);
            if (parts.length != 3) {
                return false;
            }
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = derivePasswordHash(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    private byte[] derivePasswordHash(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, PASSWORD_KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash image-share password", e);
        } finally {
            spec.clearPassword();
        }
    }
}
