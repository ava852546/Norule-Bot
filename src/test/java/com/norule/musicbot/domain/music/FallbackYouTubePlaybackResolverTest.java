package com.norule.musicbot.domain.music;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FallbackYouTubePlaybackResolverTest {
    private static final String VIDEO_ID = "dQw4w9WgXcQ";

    @Test
    void companionSuccessDoesNotCallYoutubeSource() throws Exception {
        AtomicInteger primaryAttempts = new AtomicInteger();
        AtomicInteger fallbackAttempts = new AtomicInteger();
        YouTubePlaybackResolver resolver = new FallbackYouTubePlaybackResolver(
                videoId -> {
                    primaryAttempts.incrementAndGet();
                    return companion(videoId);
                },
                videoId -> {
                    fallbackAttempts.incrementAndGet();
                    return ResolvedYouTubePlayback.youtubeSource(videoId);
                }
        );

        assertEquals(YouTubePlaybackBackend.COMPANION, resolver.resolve(VIDEO_ID).backend());
        assertEquals(1, primaryAttempts.get());
        assertEquals(0, fallbackAttempts.get());
    }

    @Test
    void temporaryCompanionFailuresEachUseOneSourceFallback() throws Exception {
        assertSingleFallback(YoutubeFailureCategory.COMPANION_TIMEOUT);
        assertSingleFallback(YoutubeFailureCategory.COMPANION_UNAVAILABLE);
        assertSingleFallback(YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE);
    }

    @Test
    void configurationFailuresDoNotFallback() {
        assertNoFallback(YoutubeFailureCategory.COMPANION_AUTH_FAILED);
        assertNoFallback(YoutubeFailureCategory.COMPANION_BAD_REQUEST);
    }

    private void assertNoFallback(YoutubeFailureCategory category) {
        AtomicInteger fallbackAttempts = new AtomicInteger();
        YouTubePlaybackResolver resolver = new FallbackYouTubePlaybackResolver(
                videoId -> {
                    throw new YouTubePlaybackException(
                            category,
                            "configuration failure"
                    );
                },
                videoId -> {
                    fallbackAttempts.incrementAndGet();
                    return ResolvedYouTubePlayback.youtubeSource(videoId);
                }
        );

        assertThrows(YouTubePlaybackException.class, () -> resolver.resolve(VIDEO_ID));
        assertEquals(0, fallbackAttempts.get());
    }

    @Test
    void failedFallbackDoesNotLoopBackToPrimary() {
        AtomicInteger primaryAttempts = new AtomicInteger();
        AtomicInteger fallbackAttempts = new AtomicInteger();
        YouTubePlaybackResolver resolver = new FallbackYouTubePlaybackResolver(
                videoId -> {
                    primaryAttempts.incrementAndGet();
                    throw new YouTubePlaybackException(
                            YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                            "offline"
                    );
                },
                videoId -> {
                    fallbackAttempts.incrementAndGet();
                    throw new YouTubePlaybackException(YoutubeFailureCategory.BOT_DETECTED, "bot detected");
                }
        );

        assertThrows(YouTubePlaybackException.class, () -> resolver.resolve(VIDEO_ID));
        assertEquals(1, primaryAttempts.get());
        assertEquals(1, fallbackAttempts.get());
    }

    private void assertSingleFallback(YoutubeFailureCategory category) throws Exception {
        AtomicInteger primaryAttempts = new AtomicInteger();
        AtomicInteger fallbackAttempts = new AtomicInteger();
        YouTubePlaybackResolver resolver = new FallbackYouTubePlaybackResolver(
                videoId -> {
                    primaryAttempts.incrementAndGet();
                    throw new YouTubePlaybackException(category, "temporary failure");
                },
                videoId -> {
                    fallbackAttempts.incrementAndGet();
                    return ResolvedYouTubePlayback.youtubeSource(videoId);
                }
        );

        ResolvedYouTubePlayback resolved = resolver.resolve(VIDEO_ID);
        assertEquals(YouTubePlaybackBackend.YOUTUBE_SOURCE, resolved.backend());
        assertEquals(category, resolved.primaryFailureCategory());
        assertEquals(1, primaryAttempts.get());
        assertEquals(1, fallbackAttempts.get());
    }

    private ResolvedYouTubePlayback companion(String videoId) {
        return new ResolvedYouTubePlayback(
                videoId,
                YouTubePlaybackBackend.COMPANION,
                URI.create("http://127.0.0.1:8282/companion/videoplayback?host=test.googlevideo.com"),
                "audio/webm; codecs=\"opus\"",
                "opus",
                128_000L,
                1_000L,
                Instant.now().plusSeconds(300)
        );
    }
}
