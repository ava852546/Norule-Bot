package com.norule.musicbot.service.shorturl;

import com.norule.musicbot.domain.shorturl.ShortUrl;

public final class ShortUrlService {
    private final com.norule.musicbot.ShortUrlService coreService;

    public ShortUrlService(com.norule.musicbot.ShortUrlService coreService) {
        if (coreService == null) {
            throw new IllegalArgumentException("coreService cannot be null");
        }
        this.coreService = coreService;
    }

    public ShortUrl create(String url) {
        return create(url, "");
    }

    public ShortUrl create(String url, String customCode) {
        com.norule.musicbot.ShortUrlService.ShortUrlEntry created = coreService.create(url, customCode);
        return map(created);
    }

    public ShortUrl resolve(String code) {
        com.norule.musicbot.ShortUrlService.ShortUrlEntry entry = coreService.resolve(code);
        return map(entry);
    }

    public ShortUrl findActiveByTarget(String url) {
        com.norule.musicbot.ShortUrlService.ShortUrlEntry entry = coreService.findActiveByTarget(url);
        return map(entry);
    }

    private ShortUrl map(com.norule.musicbot.ShortUrlService.ShortUrlEntry entry) {
        if (entry == null) {
            return null;
        }
        return new ShortUrl(
                entry.getCode(),
                entry.getTarget(),
                entry.getCreatedAt(),
                entry.getExpiresAt()
        );
    }
}
