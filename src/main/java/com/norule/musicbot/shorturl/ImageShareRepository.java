package com.norule.musicbot.shorturl;

import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.domain.shorturl.MediaBlob;
import com.norule.musicbot.domain.shorturl.MediaOwnerType;

import java.util.List;
import java.util.Set;
import com.norule.musicbot.domain.shorturl.MediaStorageState;

public interface ImageShareRepository {
    ImageShare findByCode(String code);

    List<ImageShare> findActiveByContentHash(String contentHash, long nowMillis);

    default ImageShare findActiveByOwnerAndBlob(MediaOwnerType ownerType, String ownerId,
                                                 long blobId, long nowMillis) {
        if (ownerId == null || ownerId.isBlank() || blobId <= 0L) {
            return null;
        }
        return null;
    }

    default List<ImageShare> findByOwnerUserId(String ownerUserId, int offset, int limit) {
        return List.of();
    }

    default List<ImageShare> findByOwnerUserId(String ownerUserId,
                                               Boolean active,
                                               long nowMillis,
                                               int offset,
                                               int limit) {
        return findByOwnerUserId(ownerUserId, offset, limit);
    }

    default long countByOwnerUserId(String ownerUserId) {
        return 0L;
    }

    default long countByOwnerUserId(String ownerUserId, Boolean active, long nowMillis) {
        return countByOwnerUserId(ownerUserId);
    }

    void save(ImageShare imageShare);

    default boolean saveIfAbsent(ImageShare imageShare) {
        if (findByCode(imageShare.code()) != null) {
            return false;
        }
        save(imageShare);
        return true;
    }

    default void update(ImageShare imageShare) {
        deleteByCode(imageShare.code());
        save(imageShare);
    }

    void deleteByCode(String code);

    default List<ImageShare> findWithoutBlob(int limit) {
        return List.of();
    }

    default List<ImageShare> findLinkedStorageMismatches(int limit) {
        return List.of();
    }

    default void attachBlob(String code, long blobId, String sha256) {
        ImageShare share = findByCode(code);
        if (share != null) {
            update(new ImageShare(share.code(), share.storageName(), share.contentType(),
                    share.sizeBytes(), share.createdAt(), share.expiresAt(), share.passwordHash(),
                    sha256, share.viewCount(), share.storageState(), share.archiveStorageName(),
                    share.archivedAt(), share.ownerType(), share.ownerId(), share.quotaGroupId(),
                    share.createdDeviceIdHash(), share.createdIpHash(), share.lastAccessedAt(), blobId));
        }
    }

    default void alignStorageWithBlob(String code, MediaBlob blob) {
        // Optional migration optimization for repositories that persist legacy storage metadata.
    }

    default boolean hasActiveShareForBlob(long blobId, long nowMillis) {
        return false;
    }

    default long countByBlobId(long blobId) {
        return 0L;
    }

    default void releaseExpiredReuseKeys(long nowMillis) {
        // Repositories without a database-level active-share key have nothing to release.
    }

    default void updateStorageStateForBlob(long blobId, MediaStorageState state,
                                           String archiveStorageName, long archivedAt) {
        // Optional compatibility mirror for legacy short_url_images columns.
    }

    List<ImageShare> findExpired(long nowMillis);

    default List<ImageShare> findByStorageStates(Set<MediaStorageState> states) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }
        return findExpired(Long.MAX_VALUE).stream()
                .filter(imageShare -> states.contains(imageShare.storageState()))
                .toList();
    }

    long incrementViewCount(String code);

    default long incrementViewCount(String code, long lastAccessedAt) {
        return incrementViewCount(code);
    }
}
