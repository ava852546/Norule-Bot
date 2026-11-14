package com.norule.musicbot.gateway.youtube;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.norule.musicbot.domain.music.*;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CompanionPlaybackResolverTest {
    private static final String ID = "xfuIlmywvXI";
    private static final String PLAYABLE = """
            {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[{
            "itag":251,"mimeType":"audio/webm; codecs=\\"opus\\"","bitrate":128000,
            "url":"https://fixture.googlevideo.com/videoplayback?expire=2100000000&sig=fixture"}]}}
            """;

    @Test void transientPlayerFailureDoesNotAmplifyRequests() {
        var requests = new AtomicInteger();
        var client = client(request -> {
            requests.incrementAndGet();
            return new CompanionPlaybackClient.HttpResponseData(503, PLAYABLE);
        });
        var resolver = new CompanionPlaybackResolver(client);
        assertEquals(YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                assertThrows(YouTubePlaybackException.class, () -> resolver.resolve(ID)).category());
        assertEquals(1, requests.get());
    }
    @Test void transientFailureFallsBackOnceWithoutPlayerRetry() throws Exception {
        var requests = new AtomicInteger();
        var fallbacks = new AtomicInteger();
        var primary = new CompanionPlaybackResolver(client(request -> {
            requests.incrementAndGet();
            throw new java.net.http.HttpTimeoutException("fixture");
        }));
        var resolver = new FallbackYouTubePlaybackResolver(primary, video -> {
            fallbacks.incrementAndGet(); return ResolvedYouTubePlayback.youtubeSource(video);
        });
        assertEquals(YouTubePlaybackBackend.YOUTUBE_SOURCE, resolver.resolve(ID).backend());
        assertEquals(1, requests.get());
        assertEquals(1, fallbacks.get());
    }

    @Test void rateLimitsAndConfigurationFailuresNeverRepeatPlayerRequest() {
        for (int status : new int[]{400, 401, 403, 404, 429}) {
            var requests = new AtomicInteger();
            var resolver = new CompanionPlaybackResolver(client(request -> {
                requests.incrementAndGet();
                return new CompanionPlaybackClient.HttpResponseData(status, "{}");
            }));
            assertThrows(YouTubePlaybackException.class, () -> resolver.resolve(ID));
            assertEquals(1, requests.get());
        }
    }

    @Test void permanentVideoRestrictionIsNotRetried() {
        var resolver = new CompanionPlaybackResolver(client(request -> new CompanionPlaybackClient.HttpResponseData(
                200, "{\"playabilityStatus\":{\"status\":\"ERROR\",\"reason\":\"Private video\"}}")));
        assertEquals(YoutubeFailureCategory.VIDEO_PRIVATE,
                assertThrows(YouTubePlaybackException.class, () -> resolver.resolve(ID)).category());
    }

    @Test void interruptedTransportDoesNotAttemptFallback() {
        var requests = new AtomicInteger();
        var primary = new CompanionPlaybackResolver(client(request -> {
            requests.incrementAndGet();
            throw new InterruptedException("stopped");
        }));
        var resolver = new FallbackYouTubePlaybackResolver(primary, video -> {
            fail("Interrupted playback must not open another backend"); return null;
        });
        assertThrows(YouTubePlaybackException.class, () -> resolver.resolve(ID));
        assertEquals(1, requests.get());
    }
    private static CompanionPlaybackClient client(CompanionPlaybackClient.HttpTransport transport) {
        return new CompanionPlaybackClient("http://localhost:8282", "FixtureKey123456", 1000, new ObjectMapper(), transport);
    }
}
