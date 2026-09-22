package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.YouTubePlaybackBackend;
import com.norule.musicbot.domain.music.YouTubePlaybackTrackFactory;
import com.norule.musicbot.domain.music.TrackLoadContext;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.track.YoutubeAudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** Search/metadata still use youtube-source; every resulting track routes playback here,
 * including tracks resolved lazily inside LavaSrc's Spotify mirroring resolver. */
final class BackendYoutubeAudioSourceManager extends YoutubeAudioSourceManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackendYoutubeAudioSourceManager.class);
    private static final java.util.regex.Pattern SPOTIFY_TRACK = java.util.regex.Pattern.compile(
            "open\\.spotify\\.com/(?:intl-[a-z]{2}/)?track/([A-Za-z0-9]+)");
    private final YouTubePlaybackTrackFactory trackFactory;

    BackendYoutubeAudioSourceManager(YouTubePlaybackTrackFactory trackFactory, Client... clients) {
        super(clients);
        this.trackFactory = Objects.requireNonNull(trackFactory);
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        String identifier = reference.identifier;
        String search = identifier != null && (identifier.startsWith("ytsearch:") || identifier.startsWith("ytmsearch:"))
                ? identifier.replace('\r', ' ').replace('\n', ' ') : "-";
        LOGGER.debug("[NoRule] YouTube candidate discovery: provider=YOUTUBE_SOURCE configuredBackend={} searchQuery={}",
                trackFactory.backend(), search.substring(0, Math.min(search.length(), 240)));
        try {
            return super.loadItem(manager, reference);
        } catch (RuntimeException failure) {
            LOGGER.warn("[NoRule] YouTube candidate discovery failed: configuredBackend={} provider=YOUTUBE_SOURCE "
                    + "stage=METADATA_DISCOVERY failureType={}", trackFactory.backend(), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    @Override
    public YoutubeAudioTrack buildAudioTrack(AudioTrackInfo info) {
        return new BackendTrack(super.buildAudioTrack(info));
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo info, java.io.DataInput input) {
        return buildAudioTrack(info);
    }

    final class BackendTrack extends YoutubeAudioTrack {
        private final YoutubeAudioTrack sourceTrack;

        BackendTrack(YoutubeAudioTrack sourceTrack) {
            super(sourceTrack.getInfo(), BackendYoutubeAudioSourceManager.this);
            this.sourceTrack = sourceTrack;
        }

        @Override
        public void process(LocalAudioTrackExecutor executor) throws Exception {
            AudioTrack selected = trackFactory.prepare(trackInfo.identifier, sourceTrack);
            YouTubePlaybackBackend actual = selected instanceof CompanionAudioTrack
                    ? YouTubePlaybackBackend.COMPANION : YouTubePlaybackBackend.YOUTUBE_SOURCE;
            TrackLoadContext context = getUserData() instanceof TrackLoadContext value ? value : null;
            String source = context == null ? "youtube" : context.sourceName();
            String sourceTrackId = "-";
            if (context != null) {
                var matcher = SPOTIFY_TRACK.matcher(context.resolvedIdentifier());
                if (matcher.find()) {
                    sourceTrackId = matcher.group(1);
                }
            }
            LOGGER.debug("[NoRule] YouTube playback routing: source={} sourceTrackId={} resolvedYoutubeVideoId={} "
                            + "configuredBackend={} actualBackend={} stage=PLAYBACK_EXTRACTION",
                    source, sourceTrackId, trackInfo.identifier, trackFactory.backend(), actual);
            if (actual != trackFactory.backend()) {
                LOGGER.error("[NoRule] Playback backend violation: configured={} attempted={} videoId={}",
                        trackFactory.backend(), actual, trackInfo.identifier);
                throw new IllegalStateException("Playback backend violation");
            }
            selected.setUserData(getUserData());
            try {
                processDelegate((InternalAudioTrack) selected, executor);
            } catch (Exception failure) {
                LOGGER.warn("[NoRule] YouTube playback failed: source={} sourceTrackId={} videoId={} "
                                + "configuredBackend={} failureType={}",
                        source, sourceTrackId, trackInfo.identifier, trackFactory.backend(), failure.getClass().getSimpleName());
                throw failure;
            }
        }

        @Override
        protected AudioTrack makeShallowClone() {
            return buildAudioTrack(trackInfo);
        }
    }
}
