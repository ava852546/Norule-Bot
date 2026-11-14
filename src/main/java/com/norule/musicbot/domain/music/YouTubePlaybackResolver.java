package com.norule.musicbot.domain.music;

@FunctionalInterface
public interface YouTubePlaybackResolver {
    ResolvedYouTubePlayback resolve(String videoId) throws YouTubePlaybackException;

    default java.util.concurrent.CompletableFuture<ResolvedYouTubePlayback> resolveAsync(String videoId, long guildId) {
        try { return java.util.concurrent.CompletableFuture.completedFuture(resolve(videoId)); }
        catch (YouTubePlaybackException failure) { return java.util.concurrent.CompletableFuture.failedFuture(failure); }
    }

    default ResolvedYouTubePlayback fallback(String videoId,
                                             YouTubePlaybackException primaryFailure)
            throws YouTubePlaybackException {
        throw primaryFailure;
    }
}
