package com.norule.musicbot.domain.music;

public final class YoutubeSourcePlaybackResolver implements YouTubePlaybackResolver {
    @Override
    public ResolvedYouTubePlayback resolve(String videoId) {
        return ResolvedYouTubePlayback.youtubeSource(videoId);
    }
}
