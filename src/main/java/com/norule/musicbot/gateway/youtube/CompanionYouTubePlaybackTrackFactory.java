package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.YouTubePlaybackResolver;
import com.norule.musicbot.domain.music.YouTubePlaybackTrackFactory;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.Objects;

public final class CompanionYouTubePlaybackTrackFactory implements YouTubePlaybackTrackFactory {
    private final YouTubePlaybackResolver resolver;
    private final CompanionPlaybackClient metadataClient;
    private final HttpAudioSourceManager companionHttpSource;
    private final java.util.concurrent.ThreadPoolExecutor mirrorWorkers = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64), runnable -> {
                Thread thread = new Thread(runnable, "companion-spotify-metadata"); thread.setDaemon(true); return thread;
            });

    CompanionYouTubePlaybackTrackFactory(YouTubePlaybackResolver resolver, HttpAudioSourceManager httpSource) {
        this(resolver, httpSource, null);
    }

    CompanionYouTubePlaybackTrackFactory(YouTubePlaybackResolver resolver, HttpAudioSourceManager httpSource,
                                         CompanionPlaybackClient metadataClient) {
        this.resolver = Objects.requireNonNull(resolver);
        this.companionHttpSource = Objects.requireNonNull(httpSource);
        this.metadataClient = metadataClient;
    }

    public CompanionYouTubePlaybackTrackFactory(YouTubePlaybackResolver resolver,
                                                int connectTimeoutMillis,
                                                int requestTimeoutMillis) {
        this(resolver, connectTimeoutMillis, requestTimeoutMillis, null);
    }

    CompanionYouTubePlaybackTrackFactory(YouTubePlaybackResolver resolver, int connectTimeoutMillis,
                                         int requestTimeoutMillis, CompanionPlaybackClient metadataClient) {
        this.metadataClient = metadataClient;
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

    com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo loadMetadata(String videoId)
            throws com.norule.musicbot.domain.music.YouTubePlaybackException {
        if (metadataClient == null) {
            throw new com.norule.musicbot.domain.music.YouTubePlaybackException(
                    com.norule.musicbot.domain.music.YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                    "Companion metadata client is unavailable.");
        }
        return metadataClient.loadMetadata(videoId);
    }

    boolean allowsSourceFallback(String videoId, com.norule.musicbot.domain.music.YouTubePlaybackException failure) {
        try {
            return resolver.fallback(videoId, failure).backend()
                    == com.norule.musicbot.domain.music.YouTubePlaybackBackend.YOUTUBE_SOURCE;
        } catch (com.norule.musicbot.domain.music.YouTubePlaybackException rejected) {
            return false;
        }
    }

    void cancelGuild(long guildId) { if (metadataClient != null) metadataClient.cancelGuild(guildId); }
    void close() {
        mirrorWorkers.shutdownNow();
        if (metadataClient != null) metadataClient.close();
        companionHttpSource.shutdown();
    }

    @Override
    public com.norule.musicbot.domain.music.YouTubePlaybackBackend backend() {
        return com.norule.musicbot.domain.music.YouTubePlaybackBackend.COMPANION;
    }

    @Override
    public AudioTrack prepare(String videoId, AudioTrack youtubeSourceTrack) {
        if (youtubeSourceTrack instanceof com.github.topi314.lavasrc.mirror.MirroringAudioTrack mirror && !mirror.isPreview())
            return new CompanionMirroredAudioTrack(mirror, mirrorWorkers);
        if (videoId == null
                || !videoId.matches("[A-Za-z0-9_-]{11}")
                || youtubeSourceTrack == null
                || !(youtubeSourceTrack instanceof InternalAudioTrack)
                || youtubeSourceTrack instanceof CompanionAudioTrack
                || youtubeSourceTrack instanceof BackendYoutubeAudioSourceManager.BackendTrack) {
            return youtubeSourceTrack;
        }
        return new CompanionAudioTrack(videoId, youtubeSourceTrack, resolver, companionHttpSource);
    }
}
