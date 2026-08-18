package com.norule.musicbot.domain.music;

import java.util.Objects;

public final class FallbackYouTubePlaybackResolver implements YouTubePlaybackResolver {
    private final YouTubePlaybackResolver primary;
    private final YouTubePlaybackResolver fallback;

    public FallbackYouTubePlaybackResolver(YouTubePlaybackResolver primary,
                                           YouTubePlaybackResolver fallback) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public ResolvedYouTubePlayback resolve(String videoId) throws YouTubePlaybackException {
        try {
            return primary.resolve(videoId);
        } catch (YouTubePlaybackException failure) {
            return fallback(videoId, failure);
        }
    }

    @Override
    public ResolvedYouTubePlayback fallback(String videoId,
                                            YouTubePlaybackException primaryFailure)
            throws YouTubePlaybackException {
        if (primaryFailure == null || !primaryFailure.allowsSourceFallback()) {
            throw primaryFailure == null
                    ? new YouTubePlaybackException(
                    YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE,
                    "Companion playback failed without a classified cause."
            )
                    : primaryFailure;
        }
        return fallback.resolve(videoId).withPrimaryFailure(primaryFailure.category());
    }
}
