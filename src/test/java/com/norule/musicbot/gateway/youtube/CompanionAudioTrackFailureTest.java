package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.*;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import org.apache.http.client.HttpResponseException;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class CompanionAudioTrackFailureTest {
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
