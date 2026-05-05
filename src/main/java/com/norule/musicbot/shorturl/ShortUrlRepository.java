package com.norule.musicbot.shorturl;

import com.norule.musicbot.ShortUrlService;

public interface ShortUrlRepository {
    ShortUrlService.ShortUrlEntry findByCode(String code);

    ShortUrlService.ShortUrlEntry findActiveByTarget(String target, long nowMillis);

    void save(ShortUrlService.ShortUrlEntry entry);

    void deleteByCode(String code);

    int cleanupExpired(long nowMillis);
}
