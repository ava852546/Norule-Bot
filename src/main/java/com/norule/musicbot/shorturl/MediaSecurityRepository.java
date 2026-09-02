package com.norule.musicbot.shorturl;

import com.norule.musicbot.domain.shorturl.MediaPasswordAttemptLock;

public interface MediaSecurityRepository extends AutoCloseable {
    enum IdentityMergeStatus {
        MERGED,
        ALREADY_MERGED,
        NO_RECENT_DEVICE_ACTIVITY,
        ACCOUNT_SWITCH_BLOCKED
    }

    record IdentityMergeResult(IdentityMergeStatus status, String quotaGroupId) {
    }

    MediaPasswordAttemptLock findPasswordAttemptLock(String shareCode, String ipHash);

    void savePasswordAttemptLock(MediaPasswordAttemptLock lock);

    void deletePasswordAttemptLock(String shareCode, String ipHash);

    String resolveOrCreateQuotaGroup(String identityType, String identityHash,
                                     String linkedDiscordUserId, long nowMillis);

    IdentityMergeResult mergeAnonymousDeviceIntoDiscord(String deviceHash, String discordIdentityHash,
                                                        String discordUserId, long nowMillis,
                                                        long recentActivityCutoffMillis,
                                                        long accountSwitchCooldownMillis);

    void recordUploadEvent(String quotaGroupId, String ipHash, long createdAt,
                           long sizeBytes, boolean success);

    long countSuccessfulUploads(String quotaGroupId, long sinceMillis);

    /** Records one newly-created MediaShare. Reused shares must not call this method. */
    default void recordCreatedShare(String quotaGroupId, String ipHash, long createdAt,
                                    long logicalSizeBytes) {
        recordUploadEvent(quotaGroupId, ipHash, createdAt, logicalSizeBytes, true);
    }

    /** Counts newly-created MediaShare records, not HTTP upload requests. */
    default long countCreatedShares(String quotaGroupId, long sinceMillis) {
        return countSuccessfulUploads(quotaGroupId, sinceMillis);
    }

    long activeStorageBytes(String quotaGroupId);

    default long activeStorageBytes(String quotaGroupId, long nowMillis) {
        return activeStorageBytes(quotaGroupId);
    }

    long globalManagedStorageBytes();

    @Override
    default void close() {
        // Most implementations do not own a long-lived resource.
    }
}
