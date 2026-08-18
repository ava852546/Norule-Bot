package com.norule.musicbot.domain.music;

import java.net.URI;
import java.time.Instant;

public record ResolvedYouTubePlayback(
        String videoId,
        YouTubePlaybackBackend backend,
        URI streamUri,
        String mimeType,
        String codec,
        Integer itag,
        Long bitrate,
        Long contentLength,
        Instant expiresAt,
        YoutubeFailureCategory primaryFailureCategory
) {
    public ResolvedYouTubePlayback {
        videoId = videoId == null ? "" : videoId;
        backend = backend == null ? YouTubePlaybackBackend.YOUTUBE_SOURCE : backend;
        mimeType = mimeType == null ? "" : mimeType;
        codec = codec == null ? "" : codec;
    }

    public ResolvedYouTubePlayback(String videoId,
                                   YouTubePlaybackBackend backend,
                                   URI streamUri,
                                   String mimeType,
                                   String codec,
                                   Long bitrate,
                                   Long contentLength,
                                   Instant expiresAt) {
        this(videoId, backend, streamUri, mimeType, codec, null, bitrate, contentLength, expiresAt, null);
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
                null,
                null,
                null
        );
    }

    public ResolvedYouTubePlayback withPrimaryFailure(YoutubeFailureCategory category) {
        return new ResolvedYouTubePlayback(
                videoId,
                backend,
                streamUri,
                mimeType,
                codec,
                itag,
                bitrate,
                contentLength,
                expiresAt,
                category
        );
    }

    public boolean usesCompanionStream() {
        return backend == YouTubePlaybackBackend.COMPANION && streamUri != null;
    }
}
