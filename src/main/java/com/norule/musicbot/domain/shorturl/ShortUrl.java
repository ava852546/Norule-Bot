package com.norule.musicbot.domain.shorturl;

public record ShortUrl(
        String code,
        String target,
        long createdAt,
        long expiresAt,
        long viewCount,
        String ownerUserId,
        long lastAccessedAt
) {
    public ShortUrl {
        ownerUserId = ownerUserId == null ? "" : ownerUserId;
        lastAccessedAt = Math.max(0L, lastAccessedAt);
    }

    public ShortUrl(String code, String target, long createdAt, long expiresAt) {
        this(code, target, createdAt, expiresAt, 0L, "", 0L);
    }

    public ShortUrl(String code, String target, long createdAt, long expiresAt, long viewCount) {
        this(code, target, createdAt, expiresAt, viewCount, "", 0L);
    }
}
