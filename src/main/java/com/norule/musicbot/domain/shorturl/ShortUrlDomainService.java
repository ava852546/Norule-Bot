package com.norule.musicbot.domain.shorturl;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class ShortUrlDomainService {
    private static final char[] BASE62 = "23456789abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final Set<String> RESERVED_CODES = Set.of(
            "api", "assets", "static", "web", "dashboard", "short-url", "index", "404"
    );

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
        try {
            URI uri = URI.create(target);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isBlank()) {
                return false;
            }
            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
                return false;
            }
            String host = uri.getHost();
            return host != null && !host.isBlank();
        } catch (Exception ignored) {
            return false;
        }
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
        return slug.matches("[A-Za-z0-9_-]{1,63}");
    }

    public boolean isReservedCode(String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        return RESERVED_CODES.contains(slug.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isPrivateOrLocalTarget(String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(target);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String normalizedHost = host.trim().toLowerCase(Locale.ROOT);
            if ("localhost".equals(normalizedHost) || normalizedHost.endsWith(".localhost")) {
                return true;
            }
            if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
                normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
            }
            if (isIpv4Literal(normalizedHost)) {
                return isPrivateOrLocalIpv4(normalizedHost);
            }
            if (normalizedHost.contains(":")) {
                return isPrivateOrLocalIpv6(normalizedHost);
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
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

    private boolean isIpv4Literal(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isBlank() || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (Exception ignored) {
                return false;
            }
        }
        return true;
    }

    private boolean isPrivateOrLocalIpv4(String host) {
        String[] parts = host.split("\\.");
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[1]);
        if (a == 10) {
            return true;
        }
        if (a == 127) {
            return true;
        }
        if (a == 169 && b == 254) {
            return true;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return true;
        }
        if (a == 192 && b == 168) {
            return true;
        }
        return a == 100 && b >= 64 && b <= 127;
    }

    private boolean isPrivateOrLocalIpv6(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            if (!(address instanceof Inet6Address)) {
                return false;
            }
            byte[] bytes = address.getAddress();
            if (bytes.length != 16) {
                return false;
            }
            if (address.isLoopbackAddress()) {
                return true;
            }
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return true; // fc00::/7 unique local
            }
            return first == 0xFE && (second & 0xC0) == 0x80; // fe80::/10 link-local
        } catch (Exception ignored) {
            return false;
        }
    }
}
