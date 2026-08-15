package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.YouTubePlaybackResolver;
import com.norule.musicbot.domain.music.YouTubePlaybackTrackFactory;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.Objects;

public final class CompanionYouTubePlaybackTrackFactory implements YouTubePlaybackTrackFactory {
    private final YouTubePlaybackResolver resolver;
    private final HttpAudioSourceManager companionHttpSource;

    public CompanionYouTubePlaybackTrackFactory(YouTubePlaybackResolver resolver,
                                                int connectTimeoutMillis,
                                                int requestTimeoutMillis) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.companionHttpSource = new HttpAudioSourceManager();
        this.companionHttpSource.configureRequests(existing ->
                org.apache.http.client.config.RequestConfig.copy(existing)
                        .setConnectTimeout(Math.max(1, connectTimeoutMillis))
                        .setConnectionRequestTimeout(Math.max(1, connectTimeoutMillis))
                        .setSocketTimeout(Math.max(1, requestTimeoutMillis))
                        .setRedirectsEnabled(false)
                        .build()
        );
    }

    @Override
    public AudioTrack prepare(String videoId, AudioTrack youtubeSourceTrack) {
        if (videoId == null
                || !videoId.matches("[A-Za-z0-9_-]{11}")
                || youtubeSourceTrack == null
                || !(youtubeSourceTrack instanceof InternalAudioTrack)
                || youtubeSourceTrack instanceof CompanionAudioTrack) {
            return youtubeSourceTrack;
        }
        return new CompanionAudioTrack(videoId, youtubeSourceTrack, resolver, companionHttpSource);
    }
}
