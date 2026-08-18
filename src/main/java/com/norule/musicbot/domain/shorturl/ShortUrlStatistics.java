package com.norule.musicbot.domain.shorturl;

public record ShortUrlStatistics(
        ResourceType resourceType,
        String code,
        long viewCount,
        long createdAt,
        long lastAccessedAt,
        long expiresAt
) {
    public enum ResourceType {
        SHORT_URL,
        MEDIA_SHARE
    }
}
