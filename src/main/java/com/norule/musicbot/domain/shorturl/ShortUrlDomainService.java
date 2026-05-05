package com.norule.musicbot.domain.shorturl;

import java.util.Locale;

public final class ShortUrlDomainService {
    private static final char[] BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    public String normalizeTarget(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    public boolean isValidTarget(String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        String lower = target.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
                || lower.startsWith("https://");
    }

    public String normalizeSlug(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public boolean isValidSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        return slug.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,62}");
    }

    public String toCode(long value) {
        if (value <= 0L) {
            return "0";
        }
        StringBuilder out = new StringBuilder();
        long current = value;
        while (current > 0L) {
            int index = (int) (current % 62L);
            out.append(BASE62[index]);
            current /= 62L;
        }
        return out.reverse().toString();
    }

    public boolean isExpired(long expiresAt, long nowMillis) {
        return expiresAt > 0L && expiresAt <= nowMillis;
    }
}
