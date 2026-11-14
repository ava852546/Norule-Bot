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
    public java.util.concurrent.CompletableFuture<ResolvedYouTubePlayback> resolveAsync(String videoId, long guildId) {
        var pending = primary.resolveAsync(videoId, guildId);
        var result = pending.handle((value, error) -> {
            if (error == null) return value;
            Throwable cause = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
            if (cause instanceof YouTubePlaybackException failure) {
                try { return fallback(videoId, failure); }
                catch (YouTubePlaybackException rejected) { throw new java.util.concurrent.CompletionException(rejected); }
            }
            throw new java.util.concurrent.CompletionException(cause);
        });
        result.whenComplete((value, error) -> { if (result.isCancelled()) pending.cancel(false); });
        return result;
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
        try {
            return fallback.resolve(videoId).withPrimaryFailure(primaryFailure.category());
        } catch (YouTubePlaybackException finalFailure) {
            if (finalFailure != primaryFailure) finalFailure.addSuppressed(primaryFailure);
            throw finalFailure;
        }
    }
}
