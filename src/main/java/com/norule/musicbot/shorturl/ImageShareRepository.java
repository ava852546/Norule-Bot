package com.norule.musicbot.shorturl;

import com.norule.musicbot.domain.shorturl.ImageShare;

import java.util.List;
import java.util.Set;
import com.norule.musicbot.domain.shorturl.MediaStorageState;

public interface ImageShareRepository {
    ImageShare findByCode(String code);

    List<ImageShare> findActiveByContentHash(String contentHash, long nowMillis);

    void save(ImageShare imageShare);

    default void update(ImageShare imageShare) {
        deleteByCode(imageShare.code());
        save(imageShare);
    }

    void deleteByCode(String code);

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
