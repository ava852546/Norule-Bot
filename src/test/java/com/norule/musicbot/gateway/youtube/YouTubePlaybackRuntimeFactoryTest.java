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
                environment::get
        );
    }
}
