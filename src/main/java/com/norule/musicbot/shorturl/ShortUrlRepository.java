package com.norule.musicbot.shorturl;

import com.norule.musicbot.ShortUrlService;

import java.util.List;

public interface ShortUrlRepository {
    ShortUrlService.ShortUrlEntry findByCode(String code);

    ShortUrlService.ShortUrlEntry findActiveByTarget(String target, long nowMillis);

    default List<ShortUrlService.ShortUrlEntry> findByOwnerUserId(String ownerUserId,
                                                                  int offset,
                                                                  int limit) {
        return List.of();
    }

    default List<ShortUrlService.ShortUrlEntry> findByOwnerUserId(String ownerUserId,
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

    void save(ShortUrlService.ShortUrlEntry entry);

    void deleteByCode(String code);

    int cleanupExpired(long nowMillis);

    long incrementViewCount(String code);

    default long incrementViewCount(String code, long lastAccessedAt) {
        return incrementViewCount(code);
    }

    Long findLogChannelId();

    void saveLogChannelId(Long channelId);
}
