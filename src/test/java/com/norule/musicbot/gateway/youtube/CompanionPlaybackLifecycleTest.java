package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.*;
import com.sedmelluq.discord.lavaplayer.player.*;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CompanionPlaybackLifecycleTest {
    private static final String ID = "xfuIlmywvXI";
    private static final AudioTrackInfo INFO = new AudioTrackInfo("Fixture", "Artist", 120000, ID, false,
            "https://www.youtube.com/watch?v=" + ID);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void normalCompanionPlaybackUsesOnlyItsAudioDelegate() throws Exception {
        var source = new AtomicInteger();
        var audio = new AtomicInteger();
        run(video -> stream(300), source, http(audio, null));
        assertEquals(1, audio.get());
        assertEquals(0, source.get());
    }

    @Test
    void streamFailureFallsBackExactlyOnceAndRetainsOriginalCorrelation() throws Exception {
        var source = new AtomicInteger();
        var attempts = new AtomicInteger();
        var audio = new AtomicInteger();
        var resolver = new FallbackYouTubePlaybackResolver(video -> {
            attempts.incrementAndGet(); return stream(300);
        }, new YoutubeSourcePlaybackResolver());
        var primary = new java.io.IOException("connection reset");
        run(resolver, source, http(audio, primary));
        assertEquals(1, source.get());
        assertEquals(1, attempts.get());
        assertEquals(0, audio.get());
    }

    @Test
    void expiredUrlRefreshesOnceBeforeOpeningAnyAudioAndDoesNotLoop() throws Exception {
        var attempts = new AtomicInteger();
        var source = new AtomicInteger();
        var audio = new AtomicInteger();
        run(video -> stream(attempts.incrementAndGet() == 1 ? -1 : 300), source, http(audio, null));
        assertEquals(2, attempts.get());
        assertEquals(1, audio.get());
        assertEquals(0, source.get());

        attempts.set(0); audio.set(0);
        run(new FallbackYouTubePlaybackResolver(video -> {
            attempts.incrementAndGet(); return stream(-1);
        }, new YoutubeSourcePlaybackResolver()), source, http(audio, null));
        assertEquals(2, attempts.get());
        assertEquals(0, audio.get());
        assertEquals(1, source.get());
    }

    @Test
    void failedSourceNeverReturnsToCompanionAndRetainsBothCauses() {
        var primary = new AtomicInteger();
        var source = new AtomicInteger();
        var track = new CompanionAudioTrack(ID, delegate(() -> {
            source.incrementAndGet(); throw new CipherDisabledException();
        }), new FallbackYouTubePlaybackResolver(video -> {
            primary.incrementAndGet();
            throw new YouTubePlaybackException(YoutubeFailureCategory.COMPANION_UNAVAILABLE, "offline");
        }, new YoutubeSourcePlaybackResolver()), http(new AtomicInteger(), null), CLOCK);
        var wrapped = assertThrows(com.sedmelluq.discord.lavaplayer.tools.FriendlyException.class,
                () -> track.process(executor(track)));
        var failure = assertInstanceOf(YouTubePlaybackException.class, wrapped.getCause());
        assertInstanceOf(CipherDisabledException.class, failure.getCause());
        assertEquals("YOUTUBE_SOURCE", new YoutubeFailureClassifier().provider(wrapped));
        assertEquals(YoutubeFailureCategory.CIPHER_REQUIRED_BUT_DISABLED, new YoutubeFailureClassifier().classify(wrapped).category());
        assertEquals(1, primary.get());
        assertEquals(1, source.get());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                ((YouTubePlaybackException) failure.getSuppressed()[0]).category());
    }

    @Test
    void concurrentTracksDoNotShareResolvedStreamsOrSourceState() throws Exception {
        var source = new AtomicInteger();
        var audio = new AtomicInteger();
        try (var callers = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 16; i++) {
                int guild = i;
                futures.add(callers.submit(() -> {
                    try {
                        run(video -> {
                            if (guild % 2 == 0) throw new YouTubePlaybackException(
                                    YoutubeFailureCategory.COMPANION_TIMEOUT, "timeout");
                            return stream(300);
                        }, source, http(audio, null), true);
                    } catch (Exception failure) { throw new AssertionError(failure); }
                }));
            }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertEquals(8, source.get());
        assertEquals(8, audio.get());
    }

    private static void run(YouTubePlaybackResolver resolver, AtomicInteger source, HttpAudioSourceManager http) throws Exception {
        run(resolver, source, http, false);
    }

    private static void run(YouTubePlaybackResolver resolver, AtomicInteger source, HttpAudioSourceManager http, boolean wrap) throws Exception {
        var context = new TrackLoadContext(ID, ID, "youtube", 123L, "fixture", 0);
        var sourceTrack = delegate(source::incrementAndGet);
        var track = new CompanionAudioTrack(ID, sourceTrack, wrap
                ? new FallbackYouTubePlaybackResolver(resolver, new YoutubeSourcePlaybackResolver()) : resolver, http, CLOCK);
        track.setUserData(context);
        track.process(executor(track));
        assertSame(context, track.getUserData());
        if (sourceTrack.getUserData() != null) assertSame(context, sourceTrack.getUserData());
    }

    private static ResolvedYouTubePlayback stream(int seconds) {
        return new ResolvedYouTubePlayback(ID, YouTubePlaybackBackend.COMPANION,
                URI.create("http://localhost/companion/videoplayback"), "audio/webm", "opus", 128000L, 1000L,
                CLOCK.instant().plusSeconds(seconds));
    }

    private static LocalAudioTrackExecutor executor(InternalAudioTrack track) {
        return new LocalAudioTrackExecutor(track, new AudioConfiguration(), new AudioPlayerOptions(), false, 5000);
    }

    private static InternalAudioTrack delegate(Runnable play) {
        return new DelegatedAudioTrack(INFO) {
            @Override public void process(LocalAudioTrackExecutor executor) { play.run(); }
            @Override protected AudioTrack makeShallowClone() { return delegate(play); }
            @Override public AudioSourceManager getSourceManager() { return null; }
        };
    }

    private static HttpAudioSourceManager http(AtomicInteger audio, Exception failure) {
        return new HttpAudioSourceManager() {
            @Override public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
                if (failure != null) throw new com.sedmelluq.discord.lavaplayer.tools.FriendlyException(
                        "stream failure", com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS, failure);
                return delegate(audio::incrementAndGet);
            }
        };
    }
}
