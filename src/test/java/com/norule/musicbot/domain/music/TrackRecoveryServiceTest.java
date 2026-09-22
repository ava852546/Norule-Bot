package com.norule.musicbot.domain.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackRecoveryServiceTest {
    @Test
    void reloadsFirstStuckTrackAndRewindsTwoSeconds() {
        TrackRecoveryService service = new TrackRecoveryService(true, 2, 2_000L);
        FakeGateway gateway = new FakeGateway("old");
        gateway.autoReplacement = "replacement";

        TrackRecoveryService.StartResult result = service.recover(
                1L, "old", context(0), 30_000L, true, gateway, TrackRecoveryService.Listener.NOOP
        );

        assertEquals(TrackRecoveryService.StartResult.STARTED, result);
        assertEquals("replacement", gateway.playingTrack);
        assertEquals(28_000L, gateway.resumePosition);
        assertEquals(1, gateway.appliedContext.recoveryAttempts());
        assertFalse(service.isRecoveryInProgress(1L));
    }

    @Test
    void permitsSecondRecoveryButSkipsOnThirdStuckEvent() {
        TrackRecoveryService service = new TrackRecoveryService(true, 2, 2_000L);
        FakeGateway second = new FakeGateway("track-2");
        second.autoReplacement = "replacement-2";

        assertEquals(TrackRecoveryService.StartResult.STARTED,
                service.recover(1L, "track-2", context(1), 5_000L, true, second, TrackRecoveryService.Listener.NOOP));
        assertEquals(2, second.appliedContext.recoveryAttempts());

        FakeGateway third = new FakeGateway("track-3");
        assertEquals(TrackRecoveryService.StartResult.EXHAUSTED,
                service.recover(2L, "track-3", context(2), 5_000L, true, third, TrackRecoveryService.Listener.NOOP));
        assertTrue(third.skipped);
        assertFalse(third.reloadCalled);
    }

    @Test
    void staleCallbackCannotReviveTrackAfterManualSkipOrClear() {
        TrackRecoveryService service = new TrackRecoveryService(true, 2, 2_000L);
        FakeGateway gateway = new FakeGateway("old");

        service.recover(1L, "old", context(0), 10_000L, true, gateway, TrackRecoveryService.Listener.NOOP);
        gateway.playingTrack = "new";
        gateway.generation++;
        gateway.complete("replacement", true);

        assertEquals("new", gateway.playingTrack);
        assertNull(gateway.appliedContext);
    }

    @Test
    void disconnectedGuildCannotCompleteRecovery() {
        TrackRecoveryService service = new TrackRecoveryService(true, 2, 2_000L);
        FakeGateway gateway = new FakeGateway("old");

        service.recover(1L, "old", context(0), 10_000L, true, gateway, TrackRecoveryService.Listener.NOOP);
        gateway.connected = false;
        gateway.complete("replacement", true);

        assertEquals("old", gateway.playingTrack);
        assertNull(gateway.appliedContext);
    }

    @Test
    void reloadFailureSkipsAndUnseekableTrackDoesNotSeek() {
        TrackRecoveryService service = new TrackRecoveryService(true, 2, 2_000L);
        FakeGateway failed = new FakeGateway("old");
        failed.autoFailure = new RuntimeException("reload failed");

        service.recover(1L, "old", context(0), 10_000L, true, failed, TrackRecoveryService.Listener.NOOP);
        assertTrue(failed.skipped);

        FakeGateway live = new FakeGateway("live");
        live.autoReplacement = "live-replacement";
        service.recover(2L, "live", context(0), 10_000L, false, live, TrackRecoveryService.Listener.NOOP);
        assertEquals(0L, live.resumePosition);
    }

    @Test
    void preventsConcurrentRecoveryWithinOneGuild() {
        TrackRecoveryService service = new TrackRecoveryService(true, 2, 2_000L);
        FakeGateway gateway = new FakeGateway("old");

        assertEquals(TrackRecoveryService.StartResult.STARTED,
                service.recover(1L, "old", context(0), 10_000L, true, gateway, TrackRecoveryService.Listener.NOOP));
        assertEquals(TrackRecoveryService.StartResult.ALREADY_IN_PROGRESS,
                service.recover(1L, "old", context(0), 10_000L, true, gateway, TrackRecoveryService.Listener.NOOP));
    }

    private TrackLoadContext context(int attempts) {
        return new TrackLoadContext("original", "resolved", "youtube", 42L, "requester", attempts);
    }

    @Test
    void cancelledRecoveryIgnoresFailuresAndDuplicateCallbacks() {
        var service = new TrackRecoveryService(true, 2, 0);
        var gateway = new FakeGateway("old");
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        var listener = new TrackRecoveryService.Listener() {
            @Override public void recoveryFailed(Throwable failure) { failures.incrementAndGet(); }
        };
        service.recover(1, "old", context(0), 0, true, gateway, listener);
        service.cancel(1);
        gateway.pendingHandler.failed(new RuntimeException("stale"));
        gateway.complete("stale replacement", true);
        assertEquals(0, failures.get());
        assertFalse(gateway.skipped);
        assertEquals("old", gateway.playingTrack);
    }

    @Test
    void recoveryContextRetainsOriginalAndLaterCausesAndCorrelation() {
        var original = new YouTubePlaybackException(YoutubeFailureCategory.COMPANION_TIMEOUT, "first");
        var later = new YouTubePlaybackException(YoutubeFailureCategory.COMPANION_BAD_REQUEST, "second", 400, null);
        var context = context(0).withFailure(original);
        var replacement = context.withRecoveryAttempt(1, 0).withFailure(later);
        assertEquals(context.correlationId(), replacement.correlationId());
        assertEquals(java.util.List.of(original, later), replacement.failures());
        assertTrue(replacement.resetRecovery().failures().isEmpty());
    }

    @Test
    void failedReloadAfterGenerationChangeDoesNotNotifyOrSkip() {
        var service = new TrackRecoveryService(true, 2, 0);
        var gateway = new FakeGateway("old");
        var failures = new java.util.concurrent.atomic.AtomicInteger();
        service.recover(1, "old", context(0), 0, true, gateway, new TrackRecoveryService.Listener() {
            @Override public void recoveryFailed(Throwable failure) { failures.incrementAndGet(); }
        });
        gateway.playingTrack = "new";
        gateway.generation++;
        gateway.pendingHandler.failed(new IllegalStateException("old load failed"));
        assertEquals("new", gateway.playingTrack);
        assertFalse(gateway.skipped);
        assertEquals(0, failures.get());
    }

    private static final class FakeGateway implements TrackRecoveryService.RecoveryGateway {
        private Object playingTrack;
        private long generation = 7L;
        private boolean connected = true;
        private boolean paused;
        private boolean skipped;
        private boolean reloadCalled;
        private long resumePosition = -1L;
        private TrackLoadContext appliedContext;
        private Object autoReplacement;
        private Throwable autoFailure;
        private TrackRecoveryService.RecoveryLoadHandler pendingHandler;

        private FakeGateway(Object playingTrack) {
            this.playingTrack = playingTrack;
        }

        @Override
        public long playbackGeneration() {
            return generation;
        }

        @Override
        public boolean isActive(Object track, long expectedGeneration) {
            return connected && playingTrack == track && generation == expectedGeneration;
        }

        @Override
        public void pause(Object track, long expectedGeneration) {
            paused = isActive(track, expectedGeneration);
        }

        @Override
        public void reload(String identifier, TrackRecoveryService.RecoveryLoadHandler handler) {
            reloadCalled = true;
            pendingHandler = handler;
            if (autoFailure != null) {
                handler.failed(autoFailure);
            } else if (autoReplacement != null) {
                handler.loaded(autoReplacement, true);
            }
        }

        @Override
        public boolean replace(Object expectedTrack,
                               Object replacement,
                               long expectedGeneration,
                               long newPosition,
                               TrackLoadContext context) {
            if (!isActive(expectedTrack, expectedGeneration)) {
                return false;
            }
            playingTrack = replacement;
            generation++;
            paused = false;
            resumePosition = newPosition;
            appliedContext = context;
            return true;
        }

        @Override
        public boolean skip(Object expectedTrack, long expectedGeneration) {
            if (isActive(expectedTrack, expectedGeneration)) {
                skipped = true;
                playingTrack = null;
                generation++;
                paused = false;
                return true;
            }
            return false;
        }

        private void complete(Object replacement, boolean seekable) {
            pendingHandler.loaded(replacement, seekable);
        }
    }
}
