package com.norule.musicbot.domain.music.bilibili;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliFailureClassifierTest {
    private final BilibiliFailureClassifier classifier = new BilibiliFailureClassifier();

    @Test
    void classifiesHttp412AsNonRetryableRiskControl() {
        BilibiliFailureReport report = classifier.classify(
                friendly("Something went wrong when looking up the track",
                        new IOException("Invalid status code for bilibili video metadata: 412")),
                BilibiliFailureStage.METADATA
        );

        assertEquals(BilibiliFailureCategory.BILIBILI_RISK_CONTROL, report.category());
        assertEquals(BilibiliFailureStage.METADATA, report.stage());
        assertEquals(412, report.httpStatus());
        assertFalse(report.retryable());
        assertTrue(report.breakerFailure());
    }

    @Test
    void maps403And429ToDedicatedCategories() {
        BilibiliFailureReport forbidden = classifier.classify(
                new IOException("Invalid status code for bilibili video metadata: 403"),
                BilibiliFailureStage.METADATA
        );
        BilibiliFailureReport limited = classifier.classify(
                new IOException("Invalid status code for bilibili video metadata: 429"),
                BilibiliFailureStage.METADATA
        );

        assertEquals(BilibiliFailureCategory.BILIBILI_ACCESS_DENIED, forbidden.category());
        assertFalse(forbidden.breakerFailure());
        assertEquals(BilibiliFailureCategory.BILIBILI_RATE_LIMITED, limited.category());
        assertTrue(limited.breakerFailure());
    }

    @Test
    void distinguishesMetadataAndPlaybackFailures() {
        assertEquals(
                BilibiliFailureCategory.BILIBILI_METADATA_FAILED,
                classifier.classify(new IOException("Bilibili metadata response was invalid"),
                        BilibiliFailureStage.METADATA).category()
        );
        assertEquals(
                BilibiliFailureCategory.BILIBILI_PLAYBACK_FAILED,
                classifier.classify(new IOException("Bilibili playback audio stream failed"),
                        BilibiliFailureStage.PLAYBACK).category()
        );
    }

    @Test
    void localRateLimiterRejectionDoesNotCountAsRemoteBreakerFailure() {
        BilibiliFailureReport report = classifier.classify(
                new BilibiliRequestException(
                        BilibiliFailureCategory.BILIBILI_RATE_LIMITED,
                        BilibiliFailureStage.METADATA,
                        0,
                        "local throttle"
                ),
                BilibiliFailureStage.METADATA
        );

        assertEquals(BilibiliFailureCategory.BILIBILI_RATE_LIMITED, report.category());
        assertFalse(report.breakerFailure());
    }

    private FriendlyException friendly(String message, Throwable cause) {
        return new FriendlyException(message, FriendlyException.Severity.SUSPICIOUS, cause);
    }
}
