package com.norule.musicbot.domain.music;

@FunctionalInterface
public interface YouTubePlaybackResolver {
    ResolvedYouTubePlayback resolve(String videoId) throws YouTubePlaybackException;

    default ResolvedYouTubePlayback fallback(String videoId,
                                             YouTubePlaybackException primaryFailure)
            throws YouTubePlaybackException {
        throw primaryFailure;
    }
}
