package com.norule.musicbot.gateway.youtube;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.norule.musicbot.domain.music.ResolvedYouTubePlayback;
import com.norule.musicbot.domain.music.YouTubePlaybackException;
import com.norule.musicbot.domain.music.YoutubeFailureCategory;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPlaybackClientTest {
    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static final String SECRET = "ChangeMe12345678";

    @Test
    void selectsOpusAndReturnsOnlyCompanionProxyUri() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        CompanionPlaybackClient client = client(request -> {
            captured.set(request);
            return response(200, successfulPlayerResponse());
        });

        ResolvedYouTubePlayback resolved = client.resolve(VIDEO_ID);

        assertEquals("/companion/youtubei/v1/player", captured.get().uri().getPath());
        assertEquals("Bearer " + SECRET, captured.get().headers().firstValue("Authorization").orElseThrow());
        assertEquals("opus", resolved.codec());
        assertEquals(128_000L, resolved.bitrate());
        assertEquals("127.0.0.1", resolved.streamUri().getHost());
        assertEquals("/companion/videoplayback", resolved.streamUri().getPath());
        assertTrue(resolved.streamUri().getRawQuery().startsWith("host=rr1---sn-test.googlevideo.com&"));
        assertTrue(resolved.streamUri().getRawQuery().contains("sig=a%2Fb"));
        assertFalse(resolved.streamUri().toString().contains("%252F"));
        assertTrue(resolved.expiresAt().isAfter(Instant.now()));
    }

    @Test
    void timeoutHasCompanionTimeoutCategory() {
        CompanionPlaybackClient client = client(request -> {
            throw new HttpTimeoutException("timeout");
        });

        YouTubePlaybackException failure = assertThrows(
                YouTubePlaybackException.class,
                () -> client.resolve(VIDEO_ID)
        );

        assertEquals(YoutubeFailureCategory.COMPANION_TIMEOUT, failure.category());
        assertTrue(failure.allowsSourceFallback());
    }

    @Test
    void serverErrorHasUnavailableCategoryForFallback() {
        CompanionPlaybackClient client = client(request -> response(503, "temporarily unavailable"));

        YouTubePlaybackException failure = assertThrows(
                YouTubePlaybackException.class,
                () -> client.resolve(VIDEO_ID)
        );

        assertEquals(YoutubeFailureCategory.COMPANION_UNAVAILABLE, failure.category());
        assertEquals(503, failure.httpStatus());
    }

    @Test
    void rejectsNonGoogleVideoStreamBeforeBuildingProxy() {
        String body = successfulPlayerResponse().replace(
                "rr1---sn-test.googlevideo.com",
                "127.0.0.1"
        );
        CompanionPlaybackClient client = client(request -> response(200, body));

        YouTubePlaybackException failure = assertThrows(
                YouTubePlaybackException.class,
                () -> client.resolve(VIDEO_ID)
        );

        assertEquals(YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE, failure.category());
    }

    @Test
    void healthCheckUsesOriginHealthzOutsideCompanionBasePath() {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        CompanionPlaybackClient client = client(request -> {
            captured.set(request);
            return response(200, "OK");
        });

        assertTrue(client.healthCheck().healthy());
        assertEquals("/healthz", captured.get().uri().getPath());
        assertTrue(captured.get().headers().firstValue("Authorization").isEmpty());
    }

    private CompanionPlaybackClient client(CompanionPlaybackClient.HttpTransport transport) {
        return new CompanionPlaybackClient(
                "http://127.0.0.1:8282",
                SECRET,
                1000,
                new ObjectMapper(),
                transport
        );
    }

    private CompanionPlaybackClient.HttpResponseData response(int status, String body) {
        return new CompanionPlaybackClient.HttpResponseData(status, body);
    }

    private String successfulPlayerResponse() {
        return """
                {
                  "playabilityStatus": {"status": "OK"},
                  "streamingData": {
                    "adaptiveFormats": [
                      {
                        "mimeType": "audio/mp4; codecs=\\\"mp4a.40.2\\\"",
                        "bitrate": 256000,
                        "contentLength": "2000",
                        "url": "https://rr1---sn-test.googlevideo.com/videoplayback?expire=4102444800&sig=a%2Fb&itag=140"
                      },
                      {
                        "mimeType": "audio/webm; codecs=\\\"opus\\\"",
                        "bitrate": 128000,
                        "contentLength": "1000",
                        "url": "https://rr1---sn-test.googlevideo.com/videoplayback?expire=4102444800&sig=a%2Fb&itag=251"
                      }
                    ]
                  }
                }
                """;
    }
}
