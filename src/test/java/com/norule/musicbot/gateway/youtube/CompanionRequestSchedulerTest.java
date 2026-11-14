package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.YouTubePlaybackException;
import com.norule.musicbot.domain.music.YoutubeFailureCategory;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CompanionRequestSchedulerTest {
    @Test void cancellingOneMetadataObserverDoesNotCancelAnother() throws Exception {
        try (var scheduler = new CompanionRequestScheduler(new TestClock(), false)) {
            var calls = new AtomicInteger();
            var first = scheduler.submit(1, "same", false, calls::incrementAndGet);
            var second = scheduler.submit(1, "same", false, calls::incrementAndGet);
            first.cancel(false);
            assertFalse(second.isDone());
            scheduler.tick(); assertEquals(1, second.get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        }
    }

    @Test void threeGuildOutageOpensGlobalBreakerAndFailedProbeReopensCooldown() throws Exception {
        var clock = new TestClock(); var calls = new AtomicInteger();
        try (var scheduler = new CompanionRequestScheduler(clock, false)) {
            for (long guild = 1; guild <= 3; guild++) {
                var failed = scheduler.submit(guild, "v", true, () -> {
                    calls.incrementAndGet(); throw new YouTubePlaybackException(YoutubeFailureCategory.BOT_DETECTED, "fixture");
                });
                scheduler.tick(); assertThrows(ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS));
                clock.advance(750);
            }
            assertTrue(scheduler.submit(4, "v", true, calls::incrementAndGet).isCompletedExceptionally());
            clock.advance(60_000);
            var probe = scheduler.submit(4, "v", true, () -> {
                calls.incrementAndGet(); throw new YouTubePlaybackException(YoutubeFailureCategory.BOT_DETECTED, "probe");
            });
            scheduler.tick(); assertThrows(ExecutionException.class, () -> probe.get(2, TimeUnit.SECONDS));
            assertTrue(scheduler.submit(5, "v", true, calls::incrementAndGet).isCompletedExceptionally());
            assertEquals(4, calls.get());
            clock.advance(60_000);
            var restored = scheduler.submit(5, "v", true, calls::incrementAndGet);
            scheduler.tick(); assertEquals(5, restored.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void hundredMetadataRequestsArePacedAndPlaybackHasPriority() throws Exception {
        var clock = new TestClock();
        var calls = new AtomicInteger();
        try (var scheduler = new CompanionRequestScheduler(clock, false)) {
            var futures = new ArrayList<CompletableFuture<Integer>>();
            for (int i = 0; i < 100; i++) futures.add(scheduler.submit(i % 10, "video" + i, false, calls::incrementAndGet));
            assertEquals(0, calls.get());
            var play = scheduler.submit(99, "play", true, calls::incrementAndGet);
            scheduler.tick();
            assertEquals(1, play.get(2, TimeUnit.SECONDS));
            scheduler.tick();
            assertEquals(1, calls.get());
            for (int i = 0; i < 5; i++) {
                clock.advance(750); scheduler.tick(); futures.get(i).get(2, TimeUnit.SECONDS);
            }
            assertEquals(6, calls.get());
            assertEquals(95, scheduler.pending());
            clock.advance(30_000); scheduler.tick();
            assertEquals(0, scheduler.pending());
            assertEquals(6, calls.get());
        }
    }

    @Test void fiveHundredSubmissionsHaveBoundedStorageAndCancellationReleasesCapacity() {
        try (var scheduler = new CompanionRequestScheduler(new TestClock(), false)) {
            var futures = new ArrayList<CompletableFuture<Integer>>();
            for (int i = 0; i < 600; i++) futures.add(scheduler.submit(i, "v" + i, false, () -> 1));
            assertEquals(256, scheduler.pending());
            assertEquals(344, futures.stream().filter(CompletableFuture::isCompletedExceptionally).count());
            scheduler.cancelGuild(10);
            assertEquals(255, scheduler.pending());
            assertTrue(futures.get(10).isCancelled());
        }
    }

    @Test void guildRotationDoesNotStarveThirdGuildAndMetadataMergesOnlyWhileInFlight() throws Exception {
        var clock = new TestClock();
        var order = new CopyOnWriteArrayList<Long>();
        try (var scheduler = new CompanionRequestScheduler(clock, false)) {
            var all = new ArrayList<CompletableFuture<Integer>>();
            for (long guild = 1; guild <= 3; guild++) {
                final long id = guild;
                for (int i = 0; i < 4; i++) all.add(scheduler.submit(id, "v" + i, false, () -> { order.add(id); return 1; }));
            }
            scheduler.tick(); all.get(0).get(2, TimeUnit.SECONDS);
            clock.advance(750); scheduler.tick(); all.get(4).get(2, TimeUnit.SECONDS);
            clock.advance(750); scheduler.tick(); all.get(8).get(2, TimeUnit.SECONDS);
            assertEquals(List.of(1L, 2L, 3L), order);
        }
        var calls = new AtomicInteger();
        try (var scheduler = new CompanionRequestScheduler(clock, false)) {
            var a = scheduler.submit(1, "same", false, calls::incrementAndGet);
            var b = scheduler.submit(1, "same", false, calls::incrementAndGet);
            scheduler.tick();
            assertEquals(1, a.get(2, TimeUnit.SECONDS)); assertEquals(1, b.get(2, TimeUnit.SECONDS));
            clock.advance(750);
            var c = scheduler.submit(1, "same", false, calls::incrementAndGet);
            scheduler.tick(); assertEquals(2, c.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void streamResolutionsNeverShareSignedUrls() throws Exception {
        var clock = new TestClock(); var calls = new AtomicInteger();
        try (var scheduler = new CompanionRequestScheduler(clock, false)) {
            var a = scheduler.submit(1, "same", true, calls::incrementAndGet);
            var b = scheduler.submit(1, "same", true, calls::incrementAndGet);
            scheduler.tick(); assertEquals(1, a.get(2, TimeUnit.SECONDS));
            clock.advance(750); scheduler.tick(); assertEquals(2, b.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void botCooldownRejectsWithoutHttpAndSuccessfulProbeRestoresService() throws Exception {
        var clock = new TestClock(); var calls = new AtomicInteger();
        try (var scheduler = new CompanionRequestScheduler(clock, false)) {
            var failed = scheduler.submit(1, "v", true, () -> { calls.incrementAndGet(); throw new YouTubePlaybackException(YoutubeFailureCategory.BOT_DETECTED, "fixture"); });
            scheduler.tick(); assertThrows(ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS));
            assertTrue(scheduler.submit(1, "other", false, calls::incrementAndGet).isCompletedExceptionally());
            assertEquals(1, calls.get());
            clock.advance(60_000);
            var probe = scheduler.submit(1, "probe", true, calls::incrementAndGet);
            scheduler.tick(); assertEquals(2, probe.get(2, TimeUnit.SECONDS));
            assertFalse(scheduler.submit(1, "healthy", false, calls::incrementAndGet).isDone());
        }
    }

    @Test void timeoutCloseAndLocalQuotaNeverCreateRetries() throws Exception {
        var clock = new TestClock(); var calls = new AtomicInteger();
        var scheduler = new CompanionRequestScheduler(clock, false);
        var pending = scheduler.submit(1, "v", false, calls::incrementAndGet);
        assertTrue(scheduler.submitImmediate("stream", calls::incrementAndGet).isCompletedExceptionally());
        clock.advance(30_000); scheduler.tick();
        assertTrue(pending.isCompletedExceptionally()); assertEquals(0, calls.get());
        var next = scheduler.submit(1, "next", false, calls::incrementAndGet);
        scheduler.close(); assertTrue(next.isCancelled()); assertEquals(0, scheduler.pending());
    }

    private static final class TestClock extends Clock {
        private long millis = 100_000;
        void advance(long delta) { millis += delta; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
