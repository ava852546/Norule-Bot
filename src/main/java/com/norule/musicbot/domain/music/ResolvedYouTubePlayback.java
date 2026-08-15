package com.norule.musicbot.domain.music;

import java.net.URI;
import java.time.Instant;

public record ResolvedYouTubePlayback(
        String videoId,
        YouTubePlaybackBackend backend,
        URI streamUri,
        String mimeType,
        String codec,
        Long bitrate,
        Long contentLength,
        Instant expiresAt
) {
    public ResolvedYouTubePlayback {
        videoId = videoId == null ? "" : videoId;
        backend = backend == null ? YouTubePlaybackBackend.YOUTUBE_SOURCE : backend;
        mimeType = mimeType == null ? "" : mimeType;
        codec = codec == null ? "" : codec;
    }

    public static ResolvedYouTubePlayback youtubeSource(String videoId) {
        return new ResolvedYouTubePlayback(
                videoId,
                YouTubePlaybackBackend.YOUTUBE_SOURCE,
                null,
                "",
                "",
                null,
                null,
                null
        );
    }

    public boolean usesCompanionStream() {
        return backend == YouTubePlaybackBackend.COMPANION && streamUri != null;
    }
}
