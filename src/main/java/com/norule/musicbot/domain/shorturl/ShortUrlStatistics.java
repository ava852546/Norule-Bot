package com.norule.musicbot.domain.shorturl;

public record ShortUrlStatistics(
        ResourceType resourceType,
        String code,
        long viewCount,
        long createdAt,
        long lastAccessedAt,
        long expiresAt,
        String targetUrl,
        String contentType,
        long sizeBytes,
        boolean passwordProtected,
        boolean active
) {
    public ShortUrlStatistics {
        targetUrl = targetUrl == null ? "" : targetUrl;
        contentType = contentType == null ? "" : contentType;
        sizeBytes = Math.max(0L, sizeBytes);
    }

    public ShortUrlStatistics(ResourceType resourceType,
                              String code,
                              long viewCount,
                              long createdAt,
                              long lastAccessedAt,
                              long expiresAt) {
        this(resourceType, code, viewCount, createdAt, lastAccessedAt, expiresAt,
                "", "", 0L, false, expiresAt > System.currentTimeMillis());
    }

    public enum ResourceType {
        SHORT_URL,
        MEDIA_SHARE
    }
}
