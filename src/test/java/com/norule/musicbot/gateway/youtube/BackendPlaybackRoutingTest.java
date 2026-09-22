package com.norule.musicbot.gateway.youtube;

import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.spotify.SpotifyAudioTrack;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.norule.musicbot.domain.music.*;
import com.sedmelluq.discord.lavaplayer.player.*;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidMusicWithThumbnail;
import dev.lavalink.youtube.track.format.TrackFormats;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BackendPlaybackRoutingTest {
    private static final String SPOTIFY_ID = "5A9TqSd3SfHX544euhLY3w";
    private static final String SPOTIFY_URL = "https://open.spotify.com/track/" + SPOTIFY_ID;
    private static final String VIDEO_ID = "dQw4w9WgXcQ"; // Fixture candidate, not a claimed live Spotify match.
    private static final AudioTrackInfo INFO = new AudioTrackInfo("Fixture song", "Fixture artist", 1000,
            VIDEO_ID, false, "https://www.youtube.com/watch?v=" + VIDEO_ID);

    @Test
    void spotifyLazyMirrorUsesCompanionWithStandaloneCipherDisabled() throws Exception {
        runSpotify(false);
    }

    @Test
    void spotifyCompanionFailureNeverExtractsWithYoutubeSource() throws Exception {
        runSpotify(true);
    }

    private void runSpotify(boolean failCompanion) throws Exception {
        AtomicInteger companion = new AtomicInteger();
        AtomicInteger sourcePlayback = new AtomicInteger();
        AtomicInteger cipher = new AtomicInteger();
        AtomicInteger metadata = new AtomicInteger();
        AtomicInteger audio = new AtomicInteger();
        var factory = companionFactory(companion, audio, failCompanion);
        var source = new BackendYoutubeAudioSourceManager(factory, client(sourcePlayback, metadata));
        source.setCipherManager(new PolicyCipherManager(new CipherPolicy(false), () -> {
            cipher.incrementAndGet();
            throw new AssertionError("Cipher delegate must not be created");
        }));
        var manager = new DefaultAudioPlayerManager();
        manager.registerSourceManager(source);
        var spotify = new SpotifySourceManager("fixture-id", "fixture-secret", "TW", manager,
                new DefaultMirroringAudioTrackResolver(new String[]{"ytsearch:%QUERY%"}));
        try {
            var track = new SpotifyAudioTrack(new AudioTrackInfo("Fixture song", "Fixture artist", 1000,
                    SPOTIFY_ID, false, SPOTIFY_URL), spotify);
            track.setUserData(new TrackLoadContext(SPOTIFY_URL, SPOTIFY_URL, "spotify", null, "", 0));
            if (failCompanion) {
                Exception failure = assertThrows(Exception.class, () -> track.process(executor(track)));
                assertEquals(YoutubeFailureCategory.COMPANION_TIMEOUT, new YoutubeFailureClassifier().classify(failure).category());
                assertTrue(new YoutubeFailureClassifier().isYoutubeSourceFailure(failure));
            } else {
                track.process(executor(track));
            }
            assertTrue(metadata.get() > 0, "YouTube is allowed for candidate discovery");
            assertEquals(1, companion.get());
            assertEquals(failCompanion ? 0 : 1, audio.get());
            assertEquals(0, sourcePlayback.get());
            assertEquals(0, cipher.get());
        } finally {
            spotify.shutdown();
            manager.shutdown();
        }
    }

    @Test
    void directPlaylistCloneRecoveryAndDecodedTracksKeepCompanionBackend() throws Exception {
        AtomicInteger companion = new AtomicInteger();
        AtomicInteger sourceCalls = new AtomicInteger();
        var factory = companionFactory(companion, new AtomicInteger(), false);
        var source = new BackendYoutubeAudioSourceManager(factory, client(sourceCalls, new AtomicInteger()));
        try {
            AudioTrack direct = source.buildAudioTrack(INFO);
            // The service preparation path must not wrap the backend wrapper a second time.
            assertSame(direct, factory.prepare(VIDEO_ID, direct));
            for (AudioTrack track : List.of(direct, direct.makeClone(), source.buildAudioTrack(INFO), source.decodeTrack(INFO, null))) {
                ((InternalAudioTrack) track).process(executor((InternalAudioTrack) track));
            }
            assertEquals(4, companion.get());
            assertEquals(0, sourceCalls.get());
        } finally {
            source.shutdown();
        }
    }

    @Test
    void companionStreamFailureNeverUsesSourcePlayback() {
        AtomicInteger companion = new AtomicInteger();
        AtomicInteger sourceCalls = new AtomicInteger();
        var factory = companionFactory(companion, new AtomicInteger(), false, true);
        var source = new BackendYoutubeAudioSourceManager(factory, client(sourceCalls, new AtomicInteger()));
        try {
            var track = source.buildAudioTrack(INFO);
            Exception failure = assertThrows(Exception.class, () -> track.process(executor(track)));
            assertEquals(YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE,
                    new YoutubeFailureClassifier().classify(failure).category());
            assertEquals(1, companion.get());
            assertEquals(0, sourceCalls.get());
        } finally {
            source.shutdown();
        }
    }

    @Test
    void backendLeakIsBlockedBeforeSourceExtraction() {
        AtomicInteger sourceCalls = new AtomicInteger();
        var badFactory = new YouTubePlaybackTrackFactory() {
            public AudioTrack prepare(String videoId, AudioTrack track) { return track; }
            public YouTubePlaybackBackend backend() { return YouTubePlaybackBackend.COMPANION; }
        };
        var source = new BackendYoutubeAudioSourceManager(badFactory, client(sourceCalls, new AtomicInteger()));
        try {
            var track = source.buildAudioTrack(INFO);
            assertThrows(IllegalStateException.class, () -> track.process(executor(track)));
            assertEquals(0, sourceCalls.get());
        } finally {
            source.shutdown();
        }
    }

    @Test
    void youtubeSourceModeInvokesSourceExtraction() {
        AtomicInteger sourceCalls = new AtomicInteger();
        var source = new BackendYoutubeAudioSourceManager(YouTubePlaybackTrackFactory.youtubeSource(),
                client(sourceCalls, new AtomicInteger()));
        try {
            var track = source.buildAudioTrack(INFO);
            assertThrows(Exception.class, () -> track.process(executor(track)));
            assertEquals(1, sourceCalls.get());
        } finally {
            source.shutdown();
        }
    }

    private static AndroidMusicWithThumbnail client(AtomicInteger playback, AtomicInteger metadata) {
        return new AndroidMusicWithThumbnail() {
            @Override
            public AudioItem loadSearch(YoutubeAudioSourceManager source, HttpInterface http, String query) {
                metadata.incrementAndGet();
                return new BasicAudioPlaylist("fixture search", List.of(source.buildAudioTrack(INFO)), null, true);
            }

            @Override
            public TrackFormats loadFormats(YoutubeAudioSourceManager source, HttpInterface http, String videoId) throws IOException {
                playback.incrementAndGet();
                throw new IOException("Fixture extraction reached; no external network");
            }
        };
    }

    private static CompanionYouTubePlaybackTrackFactory companionFactory(AtomicInteger companion, AtomicInteger audio,
                                                                         boolean failure) {
        return companionFactory(companion, audio, failure, false);
    }

    private static CompanionYouTubePlaybackTrackFactory companionFactory(AtomicInteger companion, AtomicInteger audio,
                                                                         boolean failure, boolean streamFailure) {
        return new CompanionYouTubePlaybackTrackFactory(videoId -> {
            companion.incrementAndGet();
            if (failure) {
                throw new YouTubePlaybackException(YoutubeFailureCategory.COMPANION_TIMEOUT, "Fixture timeout");
            }
            return new ResolvedYouTubePlayback(videoId, YouTubePlaybackBackend.COMPANION,
                    URI.create("http://127.0.0.1/fixture-audio"), "audio/webm", "opus", 251, 1000L, 1000L,
                    Instant.now().plusSeconds(60), null);
        }, new HttpAudioSourceManager() {
            @Override
            public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
                if (streamFailure) {
                    throw new IllegalStateException("Fixture stream failure");
                }
                return new DelegatedAudioTrack(INFO) {
                    @Override
                    public void process(LocalAudioTrackExecutor executor) {
                        audio.incrementAndGet();
                    }

                    @Override
                    protected AudioTrack makeShallowClone() {
                        throw new UnsupportedOperationException("Fixture stream is not cloned");
                    }

                    @Override
                    public com.sedmelluq.discord.lavaplayer.source.AudioSourceManager getSourceManager() {
                        return null;
                    }
                };
            }
        });
    }

    private static LocalAudioTrackExecutor executor(InternalAudioTrack track) {
        return new LocalAudioTrackExecutor(track, new AudioConfiguration(), new AudioPlayerOptions(), false, 5000);
    }
}
