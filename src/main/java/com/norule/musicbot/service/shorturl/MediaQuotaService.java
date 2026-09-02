package com.norule.musicbot.service.shorturl;

import com.norule.musicbot.domain.shorturl.AccessTier;
import com.norule.musicbot.domain.shorturl.QuotaSubject;
import com.norule.musicbot.shorturl.MediaSecurityRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

public final class MediaQuotaService {
    public enum Rejection {
        NONE,
        RETENTION_LIMIT,
        ROLLING_RATE_LIMIT,
        DAILY_LIMIT,
        ACTIVE_STORAGE_LIMIT,
        GLOBAL_STORAGE_LIMIT
    }

    public record TierLimits(int maxUploadsPerTenMinutes, int maxUploadsPerDay,
                             long maxActiveStorageBytes, long maxRetentionMillis) {
        public TierLimits {
            maxUploadsPerTenMinutes = Math.max(1, maxUploadsPerTenMinutes);
            maxUploadsPerDay = Math.max(1, maxUploadsPerDay);
            maxActiveStorageBytes = Math.max(1L, maxActiveStorageBytes);
            maxRetentionMillis = Math.max(1L, maxRetentionMillis);
        }
    }

    public record Options(boolean enabled, TierLimits anonymous, TierLimits authenticated,
                          long maxTotalManagedStorageBytes) {
        public Options {
            anonymous = anonymous == null ? defaultAnonymous() : anonymous;
            authenticated = authenticated == null ? defaultAuthenticated() : authenticated;
            maxTotalManagedStorageBytes = Math.max(1L, maxTotalManagedStorageBytes);
        }

        public static Options defaults() {
            return new Options(true, defaultAnonymous(), defaultAuthenticated(),
                    50L * 1024L * 1024L * 1024L);
        }

        private static TierLimits defaultAnonymous() {
            return new TierLimits(5, 20, 500L * 1024L * 1024L,
                    7L * 24L * 60L * 60L * 1000L);
        }

        private static TierLimits defaultAuthenticated() {
            return new TierLimits(30, 200, 5L * 1024L * 1024L * 1024L,
                    365L * 24L * 60L * 60L * 1000L);
        }
    }

    private static final long TEN_MINUTES_MILLIS = 10L * 60L * 1000L;

    private final MediaSecurityRepository repository;
    private final Options options;
    private final Clock clock;

    public MediaQuotaService(MediaSecurityRepository repository, Options options) {
        this(repository, options, Clock.systemUTC());
    }

    public MediaQuotaService(MediaSecurityRepository repository, Options options, Clock clock) {
        if (repository == null) {
            throw new IllegalArgumentException("repository cannot be null");
        }
        this.repository = repository;
        this.options = options == null ? Options.defaults() : options;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Rejection checkUpload(QuotaSubject subject, long sizeBytes, long retentionMillis) {
        return checkUpload(subject, sizeBytes, sizeBytes, retentionMillis, true);
    }

    public Rejection checkUpload(QuotaSubject subject,
                                 long logicalAdditionalBytes,
                                 long physicalAdditionalBytes,
                                 long retentionMillis,
                                 boolean createsShare) {
        if (!options.enabled() || subject == null || subject.quotaGroupId().isBlank()) {
            return Rejection.NONE;
        }
        long now = clock.millis();
        TierLimits limits = limits(subject.accessTier());
        if (retentionMillis > limits.maxRetentionMillis()) {
            return Rejection.RETENTION_LIMIT;
        }
        if (createsShare) {
            if (repository.countCreatedShares(subject.quotaGroupId(), now - TEN_MINUTES_MILLIS)
                    >= limits.maxUploadsPerTenMinutes()) {
                return Rejection.ROLLING_RATE_LIMIT;
            }
            long dayStart = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            if (repository.countCreatedShares(subject.quotaGroupId(), dayStart)
                    >= limits.maxUploadsPerDay()) {
                return Rejection.DAILY_LIMIT;
            }
        }
        if (wouldExceed(repository.activeStorageBytes(subject.quotaGroupId(), now),
                logicalAdditionalBytes,
                limits.maxActiveStorageBytes())) {
            return Rejection.ACTIVE_STORAGE_LIMIT;
        }
        if (wouldExceed(repository.globalManagedStorageBytes(), physicalAdditionalBytes,
                options.maxTotalManagedStorageBytes())) {
            return Rejection.GLOBAL_STORAGE_LIMIT;
        }
        return Rejection.NONE;
    }

    public void recordSuccessfulUpload(QuotaSubject subject, long sizeBytes) {
        recordCreatedShare(subject, sizeBytes);
    }

    public void recordCreatedShare(QuotaSubject subject, long logicalSizeBytes) {
        if (options.enabled() && subject != null && !subject.quotaGroupId().isBlank()) {
            repository.recordCreatedShare(subject.quotaGroupId(), subject.ipHash(), clock.millis(),
                    Math.max(0L, logicalSizeBytes));
        }
    }

    public long maxRetentionMillis(AccessTier tier) {
        return options.enabled() ? limits(tier).maxRetentionMillis() : Long.MAX_VALUE;
    }

    private TierLimits limits(AccessTier tier) {
        return tier == AccessTier.ANONYMOUS ? options.anonymous() : options.authenticated();
    }

    private boolean wouldExceed(long current, long addition, long maximum) {
        return addition > maximum || current > maximum - Math.max(0L, addition);
    }
}
