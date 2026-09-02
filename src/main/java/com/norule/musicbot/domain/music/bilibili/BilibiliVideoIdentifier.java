package com.norule.musicbot.domain.music.bilibili;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BilibiliVideoIdentifier {
    private static final Pattern BVID = Pattern.compile("(?i)(BV[0-9A-Za-z]{10})");
    private static final Pattern PAGE = Pattern.compile("(?:^|&)p=(\\d+)(?:&|$)", Pattern.CASE_INSENSITIVE);

    private BilibiliVideoIdentifier() {
    }

    public static Optional<VideoRequest> from(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        final URI uri;
        try {
            uri = URI.create(input.trim());
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
        if (!isBilibiliHost(uri.getHost())) {
            return Optional.empty();
        }
        Matcher matcher = BVID.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (!matcher.find()) {
            return Optional.empty();
        }
        String bvid = normalizeBvid(matcher.group(1));
        Integer page = extractPage(uri.getRawQuery());
        return Optional.of(new VideoRequest(bvid, page));
    }

    public static String normalizeBvid(String bvid) {
        if (bvid == null || bvid.length() < 2) {
            return "";
        }
        return "BV" + bvid.substring(2);
    }

    public static boolean isBilibiliInput(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        try {
            return isBilibiliHost(URI.create(input.trim()).getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Integer extractPage(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        Matcher matcher = PAGE.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        try {
            int page = Integer.parseInt(matcher.group(1));
            return page > 0 ? page : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isBilibiliHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return "bilibili.com".equals(normalized)
                || normalized.endsWith(".bilibili.com")
                || "b23.tv".equals(normalized)
                || normalized.endsWith(".b23.tv");
    }

    public record VideoRequest(String bvid, Integer page) {
        public String singleFlightKey() {
            return page == null ? bvid : bvid + ":p=" + page;
        }
    }
}
