package com.norule.musicbot.web.ops;

import com.norule.musicbot.domain.shorturl.ShortUrl;
import com.norule.musicbot.domain.shorturl.ShortUrlCreationError;
import com.norule.musicbot.service.shorturl.ShortUrlService;

public final class ShortUrlOps {
    public record CreationResult(ShortUrl shortUrl,
                                 boolean newlyCreated,
                                 ShortUrlCreationError error) {
    }

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

    public ShortUrl createFromWeb(String url, String customCode, String clientAddress) {
        return createFromWeb(url, customCode, "", clientAddress);
    }

    public ShortUrl createFromWeb(String url,
                                  String customCode,
                                  String ownerUserId,
                                  String clientAddress) {
        return shortUrlService.create(url, customCode, ownerUserId, clientAddress);
    }

    public CreationResult createFromWebWithOutcome(String url,
                                                   String customCode,
                                                   String ownerUserId,
                                                   String clientAddress) {
        ShortUrlService.CreateResult result = shortUrlService.createWithOutcome(
                url, customCode, ownerUserId, clientAddress);
        return new CreationResult(result.shortUrl(), result.newlyCreated(), result.error());
    }

    public ShortUrl resolve(String code) {
        return shortUrlService.resolve(code);
    }

    public ShortUrl findActiveByTarget(String url) {
        return shortUrlService.findActiveByTarget(url);
    }

    public ShortUrl recordView(String code, String clientAddress, String userAgent) {
        return shortUrlService.recordView(code, clientAddress, userAgent);
    }
}
