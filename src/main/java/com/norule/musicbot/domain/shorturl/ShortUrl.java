package com.norule.musicbot.domain.shorturl;

public record ShortUrl(
        String code,
        String target,
        long createdAt,
        long expiresAt
) {
}
