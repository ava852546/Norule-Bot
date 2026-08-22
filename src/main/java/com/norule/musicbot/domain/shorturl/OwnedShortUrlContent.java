package com.norule.musicbot.domain.shorturl;

public record OwnedShortUrlContent(
        ShortUrlStatistics.ResourceType resourceType,
        String code,
        String targetUrl,
        String contentType,
        long sizeBytes,
        long createdAt,
        long expiresAt,
        long viewCount,
        long lastAccessedAt,
        boolean passwordProtected,
        boolean active
) {
    public OwnedShortUrlContent {
        targetUrl = targetUrl == null ? "" : targetUrl;
        contentType = contentType == null ? "" : contentType;
        sizeBytes = Math.max(0L, sizeBytes);
        viewCount = Math.max(0L, viewCount);
        lastAccessedAt = Math.max(0L, lastAccessedAt);
    }
}
