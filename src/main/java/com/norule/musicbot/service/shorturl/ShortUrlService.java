package com.norule.musicbot.service.shorturl;

import com.norule.musicbot.domain.shorturl.ShortUrl;
import com.norule.musicbot.domain.shorturl.ShortUrlCreationError;

public final class ShortUrlService {
    public record CreateResult(ShortUrl shortUrl,
                               boolean newlyCreated,
                               ShortUrlCreationError error) {
    }

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

    public ShortUrl create(String url, String customCode, String creatorDiscordUserId, String clientAddress) {
        return createWithOutcome(url, customCode, creatorDiscordUserId, clientAddress).shortUrl();
    }

    public CreateResult createWithOutcome(String url,
                                          String customCode,
                                          String creatorDiscordUserId,
                                          String clientAddress) {
        com.norule.musicbot.ShortUrlService.CreationOutcome outcome = coreService.createWithOutcome(
                url,
                customCode,
                creatorDiscordUserId,
                clientAddress
        );
        return new CreateResult(map(outcome.entry()), outcome.newlyCreated(), outcome.error());
    }

    public ShortUrl resolve(String code) {
        com.norule.musicbot.ShortUrlService.ShortUrlEntry entry = coreService.resolve(code);
        return map(entry);
    }

    public ShortUrl findActiveByTarget(String url) {
        com.norule.musicbot.ShortUrlService.ShortUrlEntry entry = coreService.findActiveByTarget(url);
        return map(entry);
    }

    public ShortUrl recordView(String code, String clientAddress, String userAgent) {
        com.norule.musicbot.ShortUrlService.ShortUrlEntry entry = coreService.resolve(code);
        return map(coreService.recordView(entry, clientAddress, userAgent));
    }

    private ShortUrl map(com.norule.musicbot.ShortUrlService.ShortUrlEntry entry) {
        if (entry == null) {
            return null;
        }
        return new ShortUrl(
                entry.getCode(),
                entry.getTarget(),
                entry.getCreatedAt(),
                entry.getExpiresAt(),
                entry.getViewCount(),
                entry.getOwnerUserId(),
                entry.getLastAccessedAt()
        );
    }
}
