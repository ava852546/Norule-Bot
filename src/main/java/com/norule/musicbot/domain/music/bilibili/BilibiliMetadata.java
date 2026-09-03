package com.norule.musicbot.domain.music.bilibili;

import java.util.List;

public record BilibiliMetadata(
        String bvid,
        Long cid,
        String title,
        String author,
        long durationMillis,
        String thumbnail,
        String webpageUrl,
        String playlistName,
        boolean playlist,
        boolean searchResult,
        boolean degraded,
        Integer selectedPage,
        List<Page> pages
) {
    public BilibiliMetadata {
        bvid = BilibiliVideoIdentifier.normalizeBvid(bvid);
        title = safe(title);
        author = safe(author);
        thumbnail = safe(thumbnail);
        webpageUrl = safe(webpageUrl);
        playlistName = safe(playlistName);
        durationMillis = Math.max(0L, durationMillis);
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record Page(
            int number,
            Long cid,
            String title,
            String author,
            long durationMillis,
            String thumbnail,
            String webpageUrl,
            String identifier,
            boolean stream,
            String isrc,
            String trackType,
            String sourceId
    ) {
        public Page {
            number = Math.max(1, number);
            title = safe(title);
            author = safe(author);
            thumbnail = safe(thumbnail);
            webpageUrl = safe(webpageUrl);
            identifier = safe(identifier);
            isrc = safe(isrc);
            trackType = safe(trackType);
            sourceId = safe(sourceId);
            durationMillis = Math.max(0L, durationMillis);
        }
    }
}
