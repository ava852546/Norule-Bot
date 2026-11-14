package com.norule.musicbot.gateway.youtube;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.norule.musicbot.domain.music.YouTubePlaybackTrackFactory;
import com.norule.musicbot.domain.music.YoutubeFailureClassifier;
import com.norule.musicbot.domain.music.YoutubeRecoveryClass;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.*;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.AllClientsFailedException;
import dev.lavalink.youtube.clients.WebWithThumbnail;
import org.apache.http.client.HttpResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BackendYoutubeLoadLoggingTest {
    @ParameterizedTest
    @ValueSource(strings = {"This video is age restricted", "This video is private",
            "Video unavailable", "not available in your country"})
    void knownSourceFailuresKeepClassificationWithoutConsoleStacks(String reason) {
        int status = reason.equals("Video unavailable") ? 404 : 403;
        var failure = new FriendlyException(reason, FriendlyException.Severity.SUSPICIOUS,
                new HttpResponseException(status, reason));
        var second = new FriendlyException(reason, FriendlyException.Severity.SUSPICIOUS,
                new HttpResponseException(status, reason));
        try (var capture = new LoadCapture(failure, second)) {
            assertEquals(FriendlyException.Severity.COMMON, capture.failure.severity);
            var classifier = new YoutubeFailureClassifier();
            var report = classifier.classify(capture.failure);
            assertEquals(classifier.classify(failure).category(), report.category());
            assertEquals(YoutubeRecoveryClass.PERMANENT, report.recoveryClass());
            assertEquals(status, report.httpStatus());
            assertEquals(2, report.clientFailures().size());
            assertTrue(report.allClientsFailed());
            assertFalse(report.allowsPlaybackRecovery(null));
            assertEquals(1, capture.logs.list.size()); // One bounded summary; no per-client stacks.
            var summary = capture.logs.list.getFirst();
            assertEquals(Level.INFO, summary.getLevel());
            assertNull(summary.getThrowableProxy());
            assertTrue(summary.getFormattedMessage().contains("category=" + report.category()));
            assertTrue(summary.getFormattedMessage().contains("httpStatus=" + status));
            assertFalse(capture.consoleText().contains("\tat "));
            assertFalse(capture.consoleText().contains("Caused by:"));
        }
    }

    @Test
    void rateLimitFailureKeepsRecoveryClassificationWithoutAggregateConsoleStacks() {
        var failure = new FriendlyException("YouTube returned HTTP 429", FriendlyException.Severity.SUSPICIOUS,
                new HttpResponseException(429, "Too Many Requests"));
        try (var capture = new LoadCapture(failure)) {
            var classifier = new YoutubeFailureClassifier();
            assertEquals(classifier.classify(failure).category(), classifier.classify(capture.failure).category());
            assertEquals(classifier.classify(failure).recoveryClass(), classifier.classify(capture.failure).recoveryClass());
            assertEquals(FriendlyException.Severity.COMMON, capture.failure.severity);
            assertEquals(1, capture.logs.list.size());
            assertFalse(capture.consoleText().contains("\tat "));
            assertFalse(capture.consoleText().contains("Caused by:"));
        }
    }

    @Test
    void unknownSourceFailureKeepsStacks() {
        assertTraceable(new IllegalStateException("unexpected fixture bug"));
    }

    @Test
    void nullPointerWithRestrictionTextStillKeepsStacks() {
        assertTraceable(new NullPointerException("This video is private"));
    }

    @Test
    void mixedKnownAndUnknownClientFailuresStillKeepStacks() {
        var known = new FriendlyException("This video is private", FriendlyException.Severity.COMMON, null);
        try (var capture = new LoadCapture(known, new NullPointerException("fixture bug"))) {
            assertEquals(FriendlyException.Severity.SUSPICIOUS, capture.failure.severity);
            assertTrue(capture.consoleText().contains("fixture bug"));
            assertTrue(capture.consoleText().contains("\tat "));
        }
    }

    @Test
    void suppressedBugStillKeepsStacks() {
        var known = new FriendlyException("This video is private", FriendlyException.Severity.SUSPICIOUS, null);
        known.addSuppressed(new NullPointerException("suppressed fixture bug"));
        assertTraceable(known);
    }

    private static void assertTraceable(RuntimeException failure) {
        try (var capture = new LoadCapture(failure)) {
            assertEquals(FriendlyException.Severity.SUSPICIOUS, capture.failure.severity);
            assertTrue(capture.logs.list.stream().anyMatch(log -> log.getLevel().isGreaterOrEqual(Level.WARN)
                    && log.getThrowableProxy() != null));
            assertTrue(capture.consoleText().contains("\tat "));
            var aggregate = assertInstanceOf(AllClientsFailedException.class, capture.failure);
            assertSame(failure, aggregate.getClientExceptions().getFirst().getCause());
        }
    }

    private static final class LoadCapture implements AutoCloseable {
        private final DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
        private final FriendlyException failure;

        private LoadCapture(RuntimeException... failures) {
            var clients = new WebWithThumbnail[failures.length];
            for (int i = 0; i < failures.length; i++) {
                var failure = failures[i];
                clients[i] = new WebWithThumbnail() {
                    @Override public boolean requirePlayerScript() { return false; }
                    @Override public AudioItem loadVideo(YoutubeAudioSourceManager source, HttpInterface http, String id) {
                        throw failure;
                    }
                };
            }
            manager.registerSourceManager(new BackendYoutubeAudioSourceManager(YouTubePlaybackTrackFactory.youtubeSource(), clients));
            logs.start();
            root.addAppender(logs);
            var result = new AtomicReference<FriendlyException>();
            manager.loadItemSync(new AudioReference("https://www.youtube.com/watch?v=xfuIlmywvXI", null),
                    new AudioLoadResultHandler() {
                        @Override public void trackLoaded(AudioTrack track) { fail("Expected failure"); }
                        @Override public void playlistLoaded(AudioPlaylist playlist) { fail("Expected failure"); }
                        @Override public void noMatches() { fail("Expected failure"); }
                        @Override public void loadFailed(FriendlyException exception) { result.set(exception); }
                    });
            failure = result.get();
            assertNotNull(failure);
        }

        private String consoleText() {
            var layout = new PatternLayout();
            layout.setContext(root.getLoggerContext());
            layout.setPattern("%msg%n"); // Same pattern as the production console, including automatic throwable output.
            layout.start();
            try {
                return logs.list.stream().map(layout::doLayout).collect(java.util.stream.Collectors.joining());
            } finally { layout.stop(); }
        }

        @Override public void close() {
            root.detachAppender(logs);
            logs.stop();
            manager.shutdown();
        }
    }
}
