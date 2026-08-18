package com.norule.musicbot.gateway.youtube;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.norule.musicbot.domain.music.ResolvedYouTubePlayback;
import com.norule.musicbot.domain.music.YouTubePlaybackBackend;
import com.norule.musicbot.domain.music.YouTubePlaybackException;
import com.norule.musicbot.domain.music.YoutubeFailureCategory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPlaybackClientTest {
    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static final String SECRET = "ChangeMe12345678";

    @Test
    void selectsHighestBitrateOpusAndPreservesSignedProxyQuery() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        CompanionPlaybackClient client = client(request -> {
            captured.set(request);
            return response(200, successfulPlayerResponse());
        });

        ResolvedYouTubePlayback resolved = client.resolve(VIDEO_ID);

        assertEquals("/companion/youtubei/v1/player", captured.get().uri().getPath());
        assertEquals("Bearer " + SECRET, captured.get().headers().firstValue("Authorization").orElseThrow());
        assertEquals(YouTubePlaybackBackend.COMPANION, resolved.backend());
        assertEquals(251, resolved.itag());
        assertEquals("opus", resolved.codec());
        assertEquals(154_682L, resolved.bitrate());
        assertEquals("127.0.0.1", resolved.streamUri().getHost());
        assertEquals("/companion/videoplayback", resolved.streamUri().getPath());
        String proxyQuery = resolved.streamUri().getRawQuery();
        assertTrue(proxyQuery.startsWith("host=rr1---sn-test.googlevideo.com&"));
        assertEquals(1, Arrays.stream(proxyQuery.split("&"))
                .filter(value -> value.startsWith("host="))
                .count());
        for (String required : new String[] {"expire=", "c=", "pot=", "sig=", "lsig=", "n=", "spc="}) {
            assertTrue(proxyQuery.contains(required), () -> "Missing signed query parameter: " + required);
        }
        assertFalse(resolved.streamUri().toString().contains("%252F"));
        assertTrue(resolved.expiresAt().isAfter(Instant.now()));
    }

    @Test
    void defaultTransportUsesHttp11WithoutH2cUpgrade() throws Exception {
        AtomicBoolean h2cUpgradeSeen = new AtomicBoolean();
        AtomicReference<String> protocol = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/companion/youtubei/v1/player", exchange -> {
            h2cUpgradeSeen.set(exchange.getRequestHeaders().containsKey("Upgrade"));
            protocol.set(exchange.getProtocol());
            byte[] responseBody = successfulPlayerResponse().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(h2cUpgradeSeen.get() ? 400 : 200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
        try {
            CompanionPlaybackClient client = new CompanionPlaybackClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    SECRET,
                    1000,
                    1000
            );

            ResolvedYouTubePlayback resolved = client.resolve(VIDEO_ID);

            assertEquals(251, resolved.itag());
            assertEquals("HTTP/1.1", protocol.get());
            assertFalse(h2cUpgradeSeen.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void authorizationFailureHasDedicatedCategoryAndStatus() {
        CompanionPlaybackClient client = client(request -> response(401, "unauthorized"));

        YouTubePlaybackException failure = assertThrows(
                YouTubePlaybackException.class,
                () -> client.resolve(VIDEO_ID)
        );

        assertEquals(YoutubeFailureCategory.COMPANION_AUTH_FAILED, failure.category());
        assertEquals(401, failure.httpStatus());
        assertFalse(failure.allowsSourceFallback());
    }

    @Test
    void badRequestHasDedicatedCategory() {
        CompanionPlaybackClient client = client(request -> response(400, "bad request"));

        YouTubePlaybackException failure = assertThrows(
                YouTubePlaybackException.class,
                () -> client.resolve(VIDEO_ID)
        );

        assertEquals(YoutubeFailureCategory.COMPANION_BAD_REQUEST, failure.category());
        assertEquals(400, failure.httpStatus());
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
        assertTrue(failure.allowsSourceFallback());
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
    void noAdaptiveAudioHasStreamUnavailableCategory() {
        CompanionPlaybackClient client = client(request -> response(200, """
                {
                  "playabilityStatus": {"status": "OK"},
                  "streamingData": {
                    "adaptiveFormats": [
                      {
                        "itag": 137,
                        "mimeType": "video/mp4; codecs=\\\"avc1.640028\\\"",
                        "bitrate": 4000000,
                        "url": "https://rr1---sn-test.googlevideo.com/videoplayback?expire=4102444800&c=WEB"
                      }
                    ]
                  }
                }
                """));

        YouTubePlaybackException failure = assertThrows(
                YouTubePlaybackException.class,
                () -> client.resolve(VIDEO_ID)
        );

        assertEquals(YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE, failure.category());
        assertTrue(failure.allowsSourceFallback());
    }

    @Test
    void secretAuthorizationAndSignedUrlAreAbsentFromFailureAndLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(CompanionPlaybackClient.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            String signedUrl = "https://rr1---sn-test.googlevideo.com/videoplayback?sig=private&pot=private";
            CompanionPlaybackClient client = client(request -> response(
                    401,
                    "Authorization: Bearer " + SECRET + " url=" + signedUrl
            ));

            YouTubePlaybackException failure = assertThrows(
                    YouTubePlaybackException.class,
                    () -> client.resolve(VIDEO_ID)
            );
            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            String combined = failure.getMessage() + "\n" + logged;

            assertFalse(combined.contains(SECRET));
            assertFalse(combined.contains("Bearer " + SECRET));
            assertFalse(combined.contains(signedUrl));
            assertFalse(combined.contains("sig=private"));
            assertFalse(combined.contains("pot=private"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
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

    @Test
    void lavaplayerHttpTrackDebugUrlLoggingIsSuppressed() {
        Logger httpTrackLogger = (Logger) LoggerFactory.getLogger(
                "com.sedmelluq.discord.lavaplayer.source.http.HttpAudioTrack"
        );

        assertEquals(Level.INFO, httpTrackLogger.getEffectiveLevel());
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
        return new CompanionPlaybackClient.HttpResponseData(status, "application/json", body);
    }

    private String successfulPlayerResponse() {
        return """
                {
                  "playabilityStatus": {"status": "OK"},
                  "streamingData": {
                    "adaptiveFormats": [
                      {
                        "itag": 140,
                        "mimeType": "audio/mp4; codecs=\\\"mp4a.40.2\\\"",
                        "bitrate": 256000,
                        "contentLength": "2000",
                        "url": "https://rr1---sn-test.googlevideo.com/videoplayback?expire=4102444800&c=WEB&pot=po-token&sig=a%2Fb&lsig=l%2Fs&n=nonce&spc=value&itag=140"
                      },
                      {
                        "itag": 249,
                        "mimeType": "audio/webm; codecs=\\\"opus\\\"",
                        "bitrate": 128000,
                        "contentLength": "1000",
                        "url": "https://rr1---sn-test.googlevideo.com/videoplayback?expire=4102444800&c=WEB&pot=po-token&sig=a%2Fb&lsig=l%2Fs&n=nonce&spc=value&itag=249"
                      },
                      {
                        "itag": 251,
                        "mimeType": "audio/webm; codecs=\\\"opus\\\"",
                        "bitrate": 154682,
                        "contentLength": "5402674",
                        "url": "https://rr1---sn-test.googlevideo.com/videoplayback?expire=4102444800&c=WEB&pot=po-token&sig=a%2Fb&lsig=l%2Fs&n=nonce&spc=value&itag=251"
                      }
                    ]
                  }
                }
                """;
    }
}
