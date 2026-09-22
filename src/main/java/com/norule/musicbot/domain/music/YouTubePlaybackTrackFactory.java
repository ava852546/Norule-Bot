package com.norule.musicbot.domain.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

@FunctionalInterface
public interface YouTubePlaybackTrackFactory {
    AudioTrack prepare(String videoId, AudioTrack youtubeSourceTrack);

    default YouTubePlaybackBackend backend() {
        return YouTubePlaybackBackend.YOUTUBE_SOURCE;
    }

    static YouTubePlaybackTrackFactory youtubeSource() {
        return (videoId, track) -> track;
    }
}
