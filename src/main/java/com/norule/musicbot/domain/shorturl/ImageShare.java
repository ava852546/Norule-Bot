package com.norule.musicbot.domain.shorturl;

public record ImageShare(
        String code,
        String storageName,
        String contentType,
        long sizeBytes,
        long createdAt,
        long expiresAt,
        String passwordHash,
        String contentHash,
        long viewCount,
        MediaStorageState storageState,
        String archiveStorageName,
        long archivedAt,
        MediaOwnerType ownerType,
        String ownerId,
        String quotaGroupId,
        String createdDeviceIdHash,
        String createdIpHash,
        long lastAccessedAt,
        long blobId
) {
    public ImageShare {
        storageState = storageState == null ? MediaStorageState.ACTIVE : storageState;
        archiveStorageName = archiveStorageName == null ? "" : archiveStorageName;
        ownerType = ownerType == null ? MediaOwnerType.ANONYMOUS_DEVICE : ownerType;
        ownerId = ownerId == null ? "" : ownerId;
        quotaGroupId = quotaGroupId == null ? "" : quotaGroupId;
        createdDeviceIdHash = createdDeviceIdHash == null ? "" : createdDeviceIdHash;
        createdIpHash = createdIpHash == null ? "" : createdIpHash;
        blobId = Math.max(0L, blobId);
    }

    public ImageShare(String code,
                      String storageName,
                      String contentType,
                      long sizeBytes,
                      long createdAt,
                      long expiresAt,
                      String passwordHash,
                      String contentHash) {
        this(code, storageName, contentType, sizeBytes, createdAt, expiresAt, passwordHash, contentHash, 0L);
    }

    public ImageShare(String code,
                      String storageName,
                      String contentType,
                      long sizeBytes,
                      long createdAt,
                      long expiresAt,
                      String passwordHash,
                      String contentHash,
                      long viewCount) {
        this(code, storageName, contentType, sizeBytes, createdAt, expiresAt, passwordHash, contentHash,
                viewCount, MediaStorageState.ACTIVE, "", 0L, MediaOwnerType.ANONYMOUS_DEVICE,
                "", "", "", "", 0L, 0L);
    }

    public ImageShare(String code,
                      String storageName,
                      String contentType,
                      long sizeBytes,
                      long createdAt,
                      long expiresAt,
                      String passwordHash,
                      String contentHash,
                      long viewCount,
                      MediaStorageState storageState,
                      String archiveStorageName,
                      long archivedAt,
                      MediaOwnerType ownerType,
                      String ownerId,
                      String quotaGroupId,
                      String createdDeviceIdHash,
                      String createdIpHash) {
        this(code, storageName, contentType, sizeBytes, createdAt, expiresAt, passwordHash, contentHash,
                viewCount, storageState, archiveStorageName, archivedAt, ownerType, ownerId,
                quotaGroupId, createdDeviceIdHash, createdIpHash, 0L, 0L);
    }

    public ImageShare(String code,
                      String storageName,
                      String contentType,
                      long sizeBytes,
                      long createdAt,
                      long expiresAt,
                      String passwordHash,
                      String contentHash,
                      long viewCount,
                      MediaStorageState storageState,
                      String archiveStorageName,
                      long archivedAt,
                      MediaOwnerType ownerType,
                      String ownerId,
                      String quotaGroupId,
                      String createdDeviceIdHash,
                      String createdIpHash,
                      long lastAccessedAt) {
        this(code, storageName, contentType, sizeBytes, createdAt, expiresAt, passwordHash,
                contentHash, viewCount, storageState, archiveStorageName, archivedAt, ownerType,
                ownerId, quotaGroupId, createdDeviceIdHash, createdIpHash, lastAccessedAt, 0L);
    }

    public boolean isPasswordProtected() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public boolean isVideo() {
        return contentType != null && contentType.startsWith("video/");
    }

    public ImageShare withViewCount(long updatedViewCount) {
        return withViewMetrics(updatedViewCount, lastAccessedAt);
    }

    public ImageShare withViewMetrics(long updatedViewCount, long updatedLastAccessedAt) {
        return new ImageShare(code, storageName, contentType, sizeBytes, createdAt, expiresAt,
                passwordHash, contentHash, Math.max(0L, updatedViewCount), storageState,
                archiveStorageName, archivedAt, ownerType, ownerId, quotaGroupId,
                createdDeviceIdHash, createdIpHash, Math.max(0L, updatedLastAccessedAt), blobId);
    }

    public ImageShare withStorageState(MediaStorageState updatedState, String updatedArchiveStorageName,
                                       long updatedArchivedAt) {
        return new ImageShare(code, storageName, contentType, sizeBytes, createdAt, expiresAt,
                passwordHash, contentHash, viewCount, updatedState, updatedArchiveStorageName,
                updatedArchivedAt, ownerType, ownerId, quotaGroupId, createdDeviceIdHash, createdIpHash,
                lastAccessedAt, blobId);
    }

    public ImageShare withOwnership(MediaOwnerType updatedOwnerType, String updatedOwnerId,
                                    String updatedQuotaGroupId) {
        return new ImageShare(code, storageName, contentType, sizeBytes, createdAt, expiresAt,
                passwordHash, contentHash, viewCount, storageState, archiveStorageName, archivedAt,
                updatedOwnerType, updatedOwnerId, updatedQuotaGroupId, createdDeviceIdHash, createdIpHash,
                lastAccessedAt, blobId);
    }

    public ImageShare withBlob(MediaBlob blob) {
        if (blob == null) {
            return this;
        }
        return new ImageShare(code, blob.storageName(), blob.contentType(), blob.sizeBytes(), createdAt,
                expiresAt, passwordHash, blob.sha256(), viewCount, blob.storageState(),
                blob.archiveStorageName(), blob.archivedAt(), ownerType, ownerId, quotaGroupId,
                createdDeviceIdHash, createdIpHash, lastAccessedAt, blob.id());
    }

    public String ownerUserId() {
        return ownerType == MediaOwnerType.DISCORD_USER ? ownerId : "";
    }

    public boolean isPubliclyAvailable(long nowMillis) {
        return storageState == MediaStorageState.ACTIVE && expiresAt > nowMillis;
    }
}
