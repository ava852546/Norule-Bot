package com.norule.musicbot.service.shorturl;

import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.ImageShareDomainService;
import com.norule.musicbot.domain.shorturl.MediaOwnerType;
import com.norule.musicbot.domain.shorturl.MediaStorageState;
import com.norule.musicbot.domain.shorturl.QuotaSubject;
import com.norule.musicbot.shorturl.InMemoryMediaSecurityRepository;
import com.norule.musicbot.shorturl.ImageShareRepository;
import com.norule.musicbot.shorturl.ImageShareStorage;
import com.norule.musicbot.shorturl.ShortUrlRepository;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.InputStream;
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
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ImageShareDomainService domainService = new ImageShareDomainService();
    private final ImageShareRepository imageRepository;
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
        if (imageRepository == null || shortUrlRepository == null || storage == null) {
            throw new IllegalArgumentException("image share dependencies cannot be null");
        }
        this.imageRepository = imageRepository;
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

        if (storage.filesystemUsagePercent() >= currentOptions.filesystemStopPercent()) {
            return new UploadResult(null, UploadError.FILESYSTEM_FULL);
        }
        if (quotaService != null && quotaSubject != null) {
            MediaQuotaService.Rejection rejection = quotaService.checkUpload(
                    quotaSubject, upload.content().length, retention);
            UploadError quotaError = mapQuotaRejection(rejection);
            if (quotaError != null) {
                return new UploadResult(null, quotaError);
            }
        }

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
        for (ImageShare existing : imageRepository.findActiveByContentHash(contentHash, now)) {
            if (!hasSameAccessAndExpiration(existing, upload.passwordProtected(), effectivePassword,
                    customExpiration, retention, expiresAt, quotaSubject)) {
                continue;
            }
            if (storage.exists(existing)) {
                if (quotaService != null && quotaSubject != null) {
                    quotaService.recordSuccessfulUpload(quotaSubject, existing.sizeBytes());
                }
                return new UploadResult(existing, null);
            }
            delete(existing);
        }
        String passwordHash = upload.passwordProtected() ? hashPassword(effectivePassword) : "";
        String code = nextAvailableCode(currentOptions.codeLength());
        if (code == null) {
            return new UploadResult(null, UploadError.CREATE_FAILED);
        }
        ImageShare imageShare = new ImageShare(
                code,
                code + "." + mediaType.extension(),
                mediaType.contentType(),
                upload.content().length,
                now,
                expiresAt,
                passwordHash,
                contentHash,
                0L,
                MediaStorageState.ACTIVE,
                "",
                0L,
                quotaSubject == null ? MediaOwnerType.ANONYMOUS_DEVICE : quotaSubject.ownerType(),
                quotaSubject == null ? "" : quotaSubject.ownerId(),
                quotaSubject == null ? "" : quotaSubject.quotaGroupId(),
                quotaSubject == null ? "" : quotaSubject.deviceIdHash(),
                quotaSubject == null ? "" : quotaSubject.ipHash(),
                0L
        );
        try {
            storage.save(imageShare, upload.content());
        } catch (Exception e) {
            logUploadFailure("store", e);
            return new UploadResult(null, UploadError.STORAGE_FAILED);
        }
        try {
            imageRepository.save(imageShare);
            if (quotaService != null && quotaSubject != null) {
                quotaService.recordSuccessfulUpload(quotaSubject, imageShare.sizeBytes());
            }
            return new UploadResult(imageShare, null);
        } catch (RuntimeException e) {
            deleteStoredImage(imageShare);
            logUploadFailure("save metadata for", e);
            return new UploadResult(null, UploadError.PERSISTENCE_FAILED);
        }
    }

    public ImageShare resolve(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        long now = clock.millis();
        maybeCleanup(now);
        ImageShare imageShare = imageRepository.findByCode(code.trim());
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
            delete(imageShare);
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
        ImageShare imageShare = imageRepository.findByCode(code.trim());
        return imageShare != null && !imageShare.isPubliclyAvailable(now) ? imageShare : null;
    }

    public InputStream open(ImageShare imageShare) {
        if (imageShare == null || !imageShare.isPubliclyAvailable(clock.millis())) {
            return null;
        }
        try {
            return storage.open(imageShare);
        } catch (Exception ignored) {
            delete(imageShare);
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
        return imageShare.withViewMetrics(viewCount, accessedAt);
    }

    public Options options() {
        return options.get();
    }

    public boolean isCodeInUse(String code) {
        return code != null && !code.isBlank() && imageRepository.findByCode(code.trim()) != null;
    }

    public void updateOptions(Options updatedOptions) {
        if (updatedOptions != null) {
            options.set(updatedOptions);
        }
    }

    public synchronized void cleanupExpired() {
        long now = clock.millis();
        List<ImageShare> expired = imageRepository.findExpired(now);
        for (ImageShare imageShare : expired) {
            if (imageShare.storageState() == MediaStorageState.ACTIVE) {
                markArchivePending(imageShare, now);
            }
        }
        retryPendingArchives(now);
        reconcileArchivedMedia(now);
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
            if (imageRepository.findByCode(code) == null && shortUrlRepository.findByCode(code) == null) {
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

    private void delete(ImageShare imageShare) {
        try {
            storage.delete(imageShare);
        } catch (Exception ignored) {
            // The metadata must still be removed so an unavailable or expired image cannot be served.
        }
        imageRepository.deleteByCode(imageShare.code());
    }

    private void retireExpiredShare(ImageShare imageShare, long now) {
        ImageShare pending = markArchivePending(imageShare, now);
        if (pending != null) {
            archivePendingShare(pending, now);
        }
    }

    private ImageShare markArchivePending(ImageShare imageShare, long now) {
        if (imageShare == null || imageShare.expiresAt() > now
                || imageShare.storageState() == MediaStorageState.ARCHIVED
                || imageShare.storageState() == MediaStorageState.ARCHIVE_DELETED
                || imageShare.storageState() == MediaStorageState.MISSING) {
            return null;
        }
        ImageShare pending = imageShare.storageState() == MediaStorageState.ARCHIVE_PENDING
                ? imageShare
                : imageShare.withStorageState(MediaStorageState.ARCHIVE_PENDING,
                imageShare.archiveStorageName(), imageShare.archivedAt());
        if (pending != imageShare) {
            imageRepository.update(pending);
        }
        return pending;
    }

    private void retryPendingArchives(long now) {
        for (ImageShare pending : imageRepository.findByStorageStates(
                Set.of(MediaStorageState.ARCHIVE_PENDING))) {
            archivePendingShare(pending, now);
        }
    }

    private void archivePendingShare(ImageShare pending, long now) {
        try {
            ImageShareStorage.ArchiveResult result = storage.archiveOrReconcile(pending);
            if (result.status() == ImageShareStorage.ArchiveStatus.MISSING) {
                imageRepository.update(pending.withStorageState(MediaStorageState.MISSING, "", 0L));
                logMissingMedia(pending, MediaStorageState.MISSING);
                return;
            }
            long archivedAt = pending.archivedAt() > 0L ? pending.archivedAt() : now;
            imageRepository.update(pending.withStorageState(
                    MediaStorageState.ARCHIVED, result.archiveStorageName(), archivedAt));
            logArchiveReconciliation(pending, result.status());
        } catch (Exception exception) {
            logArchiveFailure(pending, exception);
        }
    }

    private void reconcileArchivedMedia(long now) {
        for (ImageShare archived : imageRepository.findByStorageStates(
                Set.of(MediaStorageState.ARCHIVED))) {
            try {
                ImageShareStorage.ArchiveResult result = storage.archiveOrReconcile(archived);
                if (result.status() == ImageShareStorage.ArchiveStatus.MISSING) {
                    imageRepository.update(archived.withStorageState(
                            MediaStorageState.ARCHIVE_DELETED,
                            archived.archiveStorageName(), archived.archivedAt()));
                    logMissingMedia(archived, MediaStorageState.ARCHIVE_DELETED);
                    continue;
                }
                long archivedAt = archived.archivedAt() > 0L ? archived.archivedAt() : now;
                if (!result.archiveStorageName().equals(archived.archiveStorageName())
                        || archived.archivedAt() <= 0L) {
                    imageRepository.update(archived.withStorageState(
                            MediaStorageState.ARCHIVED, result.archiveStorageName(), archivedAt));
                }
                if (result.status() == ImageShareStorage.ArchiveStatus.LEGACY_MIGRATED) {
                    logArchiveReconciliation(archived, result.status());
                }
            } catch (Exception exception) {
                logArchiveFailure(archived, exception);
            }
        }
    }

    private void deleteStoredImage(ImageShare imageShare) {
        try {
            storage.delete(imageShare);
        } catch (Exception ignored) {
            // The failed upload has no metadata and cannot be served; leave cleanup to the operator if deletion also fails.
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
