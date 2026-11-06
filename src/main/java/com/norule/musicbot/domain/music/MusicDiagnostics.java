package com.norule.musicbot.domain.music;

import java.net.URI;
import java.time.Instant;

/** Immutable diagnostic values; no live player or Discord objects escape in a snapshot. */
public final class MusicDiagnostics {
    private static final java.util.regex.Pattern URL = java.util.regex.Pattern.compile("(?i)https?://[^\\s<>]+");
    private MusicDiagnostics() {}

    public record Event(long guildId, String kind, Instant at, Long channelId, Long requesterId,
                        String source, String title, String publicUrl, String outcome) {}
    public record Playback(String state, String title, long positionMillis, long durationMillis,
                           boolean stream, Integer volume, String repeat, int queued) {}
    public record Error(Instant at, String type, String reason) {}
    public record Timer(String reason, Instant deadline) {}
    public record AutoLeave(String reason, Boolean idleEligible) {}

    /** Only known public song pages are retained. Arbitrary and signed media URLs are omitted. */
    public static String publicSongUrl(String raw) {
        if (raw == null || raw.length() > 2048) return null;
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getFragment() != null || uri.getHost() == null) return null;
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            String path = uri.getPath();
            if ((host.equals("www.youtube.com") || host.equals("youtube.com")) && path.equals("/watch")
                    && uri.getRawQuery() != null && uri.getRawQuery().matches("v=[A-Za-z0-9_-]{11}")) return raw;
            if (host.equals("youtu.be") && path.matches("/[A-Za-z0-9_-]{11}") && uri.getRawQuery() == null) return raw;
            if ((host.equals("www.bilibili.com") || host.equals("bilibili.com"))
                    && path.matches("/video/(BV[A-Za-z0-9]+|av[0-9]+)/?")
                    && (uri.getRawQuery() == null || uri.getRawQuery().matches("p=[0-9]+"))) return raw;
            if (host.equals("open.spotify.com") && path.matches("/track/[A-Za-z0-9]+") && uri.getRawQuery() == null) return raw;
            if (host.equals("soundcloud.com") && path.matches("/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+") && uri.getRawQuery() == null) return raw;
        } catch (IllegalArgumentException invalid) {
            // Unknown or malformed media locations are deliberately not retained.
        }
        return null;
    }

    public static String text(String value, int limit) {
        if (value == null || value.isBlank()) return "-";
        String withoutUrls = URL.matcher(value).replaceAll(match -> {
            String publicUrl = publicSongUrl(match.group());
            return java.util.regex.Matcher.quoteReplacement(publicUrl == null ? "[URL omitted]" : publicUrl);
        });
        String clean = withoutUrls.replaceAll("[\\p{Cntrl}]", " ").replace('`', '\'')
                .replace('@', '＠').replace('[', '(').replace(']', ')');
        return clean.length() <= limit ? clean : clean.substring(0, limit - 1) + "…";
    }
}
