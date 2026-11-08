package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.*;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import org.apache.http.client.HttpResponseException;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class CompanionAudioTrackFailureTest {
    @Test void signedStream403CanFallbackWithoutMisreportingApiCredentials() {
        var failure = CompanionAudioTrack.classifyStreamFailure(new HttpResponseException(403, "Forbidden"));
        assertEquals(YoutubeFailureCategory.HTTP_FORBIDDEN, failure.category());
        assertEquals(403, failure.httpStatus());
        assertTrue(failure.allowsSourceFallback());
    }

    @Test void transportFailuresRetainHttpStatusAndTimeoutClassification() {
        for (int status : new int[]{429, 500, 503}) {
            var failure = CompanionAudioTrack.classifyStreamFailure(new HttpResponseException(status, "rejected"));
            assertEquals(YoutubeFailureCategory.COMPANION_UNAVAILABLE, failure.category());
            assertEquals(status, failure.httpStatus());
            assertTrue(failure.allowsSourceFallback());
        }
        assertEquals(YoutubeFailureCategory.COMPANION_TIMEOUT,
                CompanionAudioTrack.classifyStreamFailure(new java.net.SocketTimeoutException()).category());
        assertEquals(YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                CompanionAudioTrack.classifyStreamFailure(new IOException("connection reset")).category());
        assertFalse(CompanionAudioTrack.classifyStreamFailure(new HttpResponseException(401, "unauthorized")).allowsSourceFallback());
    }

    @Test void expirySafetyMarginUsesAnExplicitClockInstant() {
        var now = java.time.Instant.parse("2026-11-08T00:00:00Z");
        for (int seconds : new int[]{-1, 0, 5, 6}) {
            var resolved = new ResolvedYouTubePlayback("xfuIlmywvXI", YouTubePlaybackBackend.COMPANION,
                    java.net.URI.create("http://localhost/stream"), "audio/webm", "opus", 128000L, 1000L,
                    now.plusSeconds(seconds));
            assertEquals(seconds <= 5, CompanionAudioTrack.expiresSoon(resolved, now));
        }
    }
    @Test void structuredFailureWinsOverGenericWrapperAndIoException() {
        var cause = new YouTubePlaybackException(YoutubeFailureCategory.COMPANION_BAD_REQUEST, "rejected", 400, null);
        var wrapped = new FriendlyException("Something went wrong when decoding the track",
                FriendlyException.Severity.FAULT, new IOException("read failure", cause));
        assertSame(cause, CompanionAudioTrack.classifyStreamFailure(wrapped));
    }

    @Test void stream400KeepsCauseAndIsNotDecoderFailure() {
        var cause = new HttpResponseException(400, "rejected");
        var wrapped = new FriendlyException("Something went wrong when decoding the track", FriendlyException.Severity.FAULT, cause);
        var failure = CompanionAudioTrack.classifyStreamFailure(wrapped);
        assertEquals(YoutubeFailureCategory.COMPANION_BAD_REQUEST, failure.category());
        assertEquals(400, failure.httpStatus());
        assertSame(wrapped, failure.getCause());
    }

    @Test void realDecoderFailureRemainsDistinct() {
        var cause = new IllegalStateException("Expected decoding to halt, got: 5");
        var failure = CompanionAudioTrack.classifyStreamFailure(cause);
        assertEquals(YoutubeFailureCategory.DECODER_FAILURE, failure.category());
        assertSame(cause, failure.getCause());
    }
}
