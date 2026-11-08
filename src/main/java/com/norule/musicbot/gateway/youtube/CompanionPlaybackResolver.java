package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.ResolvedYouTubePlayback;
import com.norule.musicbot.domain.music.YouTubePlaybackException;
import com.norule.musicbot.domain.music.YouTubePlaybackResolver;

import java.util.Objects;

public final class CompanionPlaybackResolver implements YouTubePlaybackResolver {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CompanionPlaybackResolver.class);
    private static final long RETRY_DELAY_MILLIS = 150;
    private final CompanionPlaybackClient client;
    private final RetryDelay retryDelay;

    public CompanionPlaybackResolver(CompanionPlaybackClient client) {
        this(client, Thread::sleep);
    }

    CompanionPlaybackResolver(CompanionPlaybackClient client, RetryDelay retryDelay) {
        this.client = Objects.requireNonNull(client, "client");
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
    }

    @Override
    public ResolvedYouTubePlayback resolve(String videoId) throws YouTubePlaybackException {
        try {
            return client.resolve(videoId);
        } catch (YouTubePlaybackException firstFailure) {
            if (!retryable(firstFailure)) throw firstFailure;
            LOGGER.info("event=YOUTUBE_PLAYBACK_RETRY videoId={} actualBackend=COMPANION stage=PLAYER_REQUEST "
                    + "failureCategory={} httpStatus={} retryCount=1 backoffMs={}",
                    videoId, firstFailure.category(), firstFailure.httpStatus(), RETRY_DELAY_MILLIS);
            try {
                retryDelay.pause(RETRY_DELAY_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new YouTubePlaybackException(
                        com.norule.musicbot.domain.music.YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                        "Companion retry was interrupted.", null, interrupted);
            }
            try {
                return client.resolve(videoId);
            } catch (YouTubePlaybackException finalFailure) {
                finalFailure.addSuppressed(firstFailure);
                throw finalFailure;
            }
        }
    }

    private static boolean retryable(YouTubePlaybackException failure) {
        if (failure.getCause() instanceof InterruptedException) return false;
        return failure.category() == com.norule.musicbot.domain.music.YoutubeFailureCategory.COMPANION_TIMEOUT
                || (failure.category() == com.norule.musicbot.domain.music.YoutubeFailureCategory.COMPANION_UNAVAILABLE
                    && (failure.httpStatus() == null || failure.httpStatus() >= 500));
    }

    @FunctionalInterface
    interface RetryDelay {
        void pause(long millis) throws InterruptedException;
    }
}
