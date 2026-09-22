package com.norule.musicbot.gateway.youtube;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.YouTubePlaybackTrackFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class YouTubePlaybackRuntimeFactoryTest {
    @Test
    void cipherFalseStillCallsCompanionAndSourceFallbackRequiresExplicitOptIn() throws Exception {
        for (boolean optIn : new boolean[]{false, true}) {
            var requests = new java.util.concurrent.atomic.AtomicInteger();
            var sourceCalls = new java.util.concurrent.atomic.AtomicInteger();
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/healthz", exchange -> {
                byte[] body = "OK".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/companion/youtubei/v1/player", exchange -> {
                requests.incrementAndGet();
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });
            server.start();
            try {
                var environment = new java.util.HashMap<String, String>();
                environment.put("YOUTUBE_PLAYBACK_BACKEND", "COMPANION");
                environment.put("YOUTUBE_COMPANION_ENABLED", "true");
                environment.put("YOUTUBE_COMPANION_URL", "http://127.0.0.1:" + server.getAddress().getPort());
                environment.put("YOUTUBE_COMPANION_SECRET", "FixtureKey123456");
                if (optIn) {
                    environment.put("YOUTUBE_COMPANION_FALLBACK_TO_SOURCE", "true");
                }
                var factory = YouTubePlaybackRuntimeFactory.create(MusicConfig.defaultValues().getYoutube(),
                        new com.norule.musicbot.domain.music.CipherPolicy(false), environment::get);
                var info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                        "Fixture", "Fixture", 1000, "dQw4w9WgXcQ", false, "https://youtube.com/watch?v=dQw4w9WgXcQ");
                var source = new dev.lavalink.youtube.track.YoutubeAudioTrack(info, null) {
                    @Override
                    public void process(com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor executor) {
                        sourceCalls.incrementAndGet();
                    }
                };
                var track = (com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack) factory.prepare(info.identifier, source);
                var executor = new com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor(track,
                        new com.sedmelluq.discord.lavaplayer.player.AudioConfiguration(),
                        new com.sedmelluq.discord.lavaplayer.player.AudioPlayerOptions(), false, 5000);
                if (optIn) {
                    track.process(executor);
                } else {
                    org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> track.process(executor));
                }
                assertEquals(1, requests.get(), "Standalone Cipher false must not disable Companion");
                assertEquals(optIn ? 1 : 0, sourceCalls.get());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void selectsYoutubeSourceBackendFromEnvironment() {
        YouTubePlaybackTrackFactory factory = createWith(Map.of(
                "YOUTUBE_PLAYBACK_BACKEND", "YOUTUBE_SOURCE"
        ));

        assertFalse(factory instanceof CompanionYouTubePlaybackTrackFactory);
    }

    @Test
    void selectsCompanionBackendWithoutOpeningGlobalHttpSource() {
        YouTubePlaybackTrackFactory factory = createWith(Map.of(
                "YOUTUBE_PLAYBACK_BACKEND", "COMPANION",
                "YOUTUBE_COMPANION_ENABLED", "false"
        ));

        assertInstanceOf(CompanionYouTubePlaybackTrackFactory.class, factory);
    }

    @Test
    void fallsBackToYoutubeSourceForUnknownBackend() {
        YouTubePlaybackTrackFactory factory = createWith(Map.of(
                "YOUTUBE_PLAYBACK_BACKEND", "not-a-backend"
        ));

        assertFalse(factory instanceof CompanionYouTubePlaybackTrackFactory);
    }

    @Test
    void secretSelectionReportsPrecedenceWithoutLoggingSecretValue() {
        assertEquals(
                YouTubePlaybackRuntimeFactory.SecretSource.ENVIRONMENT,
                YouTubePlaybackRuntimeFactory.selectSecret("Environment1234", "ConfigSecret1234").source()
        );
        assertEquals(
                YouTubePlaybackRuntimeFactory.SecretSource.CONFIG,
                YouTubePlaybackRuntimeFactory.selectSecret(" ", "ConfigSecret1234").source()
        );
        assertEquals(
                YouTubePlaybackRuntimeFactory.SecretSource.NONE,
                YouTubePlaybackRuntimeFactory.selectSecret(null, "").source()
        );

        String secret = "Environment1234";
        Logger logger = (Logger) LoggerFactory.getLogger(YouTubePlaybackRuntimeFactory.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            createWith(Map.of(
                    "YOUTUBE_PLAYBACK_BACKEND", "COMPANION",
                    "YOUTUBE_COMPANION_ENABLED", "false",
                    "YOUTUBE_COMPANION_SECRET", secret
            ));
            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);

            assertFalse(logged.contains(secret));
            org.junit.jupiter.api.Assertions.assertTrue(logged.contains("secretLength=15"));
            org.junit.jupiter.api.Assertions.assertTrue(logged.contains("source=ENVIRONMENT"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static YouTubePlaybackTrackFactory createWith(Map<String, String> environment) {
        return YouTubePlaybackRuntimeFactory.create(
                MusicConfig.defaultValues().getYoutube(),
                new com.norule.musicbot.domain.music.CipherPolicy(true),
                environment::get
        );
    }
}
