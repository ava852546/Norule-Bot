package com.norule.musicbot.web.ops;

import com.norule.musicbot.domain.shorturl.ShortUrl;
import com.norule.musicbot.service.shorturl.ShortUrlService;

public final class ShortUrlOps {
    private final ShortUrlService shortUrlService;

    public ShortUrlOps(ShortUrlService shortUrlService) {
        if (shortUrlService == null) {
            throw new IllegalArgumentException("shortUrlService cannot be null");
        }
        this.shortUrlService = shortUrlService;
    }

    public ShortUrl create(String url, String customCode) {
        return shortUrlService.create(url, customCode);
    }

    public ShortUrl resolve(String code) {
        return shortUrlService.resolve(code);
    }

    public ShortUrl findActiveByTarget(String url) {
        return shortUrlService.findActiveByTarget(url);
    }
}
