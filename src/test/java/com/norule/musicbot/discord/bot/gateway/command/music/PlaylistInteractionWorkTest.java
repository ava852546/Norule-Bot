package com.norule.musicbot.discord.bot.gateway.command.music;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistInteractionWorkTest {
    @Test
    void duplicateAcknowledgedAndExpiredEventsCannotBeClaimed() throws Exception {
        long now = 10_000;
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var work = new PlaylistInteractionWork(timer, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC))) {
            assertTrue(work.claim(1, now, false));
            assertFalse(work.claim(1, now, false));
            assertFalse(work.claim(2, now, true));
            assertFalse(work.claim(3, now - 2500, false));
            assertTrue(work.claim(4, now - 2499, false));
        }
    }

    @Test
    void concurrentDuplicateDeliveryHasExactlyOneWinner() throws Exception {
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var work = new PlaylistInteractionWork(timer, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
             var callers = Executors.newFixedThreadPool(8)) {
            var wins = new AtomicInteger();
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 100; i++) futures.add(callers.submit(() -> {
                if (work.claim(42, 0, false)) wins.incrementAndGet();
            }));
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(1, wins.get());
        }
    }

    @Test
    void slowQueryDoesNotBlockCallerAndLateResultCannotReplyAgain() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var timer = new ManualDeadline();
        try (var work = new PlaylistInteractionWork(timer, Clock.systemUTC())) {
            var replies = new AtomicInteger();
            var result = work.autocomplete(() -> {
                entered.countDown();
                await(release);
                return List.of("late private data");
            }, 1200);
            result.thenAccept(ignored -> replies.incrementAndGet());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            timer.expire();
            assertEquals(List.of(), result.get(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(1, replies.get());
        } finally { release.countDown(); timer.shutdownNow(); }
    }

    @Test
    void resultsAreFreshAndConcurrentGuildQueriesStayIsolated() throws Exception {
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var work = new PlaylistInteractionWork(timer, Clock.systemUTC())) {
            var current = new java.util.concurrent.atomic.AtomicReference<>(List.of("guild-a-old"));
            assertEquals(List.of("guild-a-old"), work.autocomplete(current::get, 10_000).get());
            current.set(List.of("guild-a-new"));
            var a = work.autocomplete(current::get, 10_000);
            var b = work.autocomplete(() -> List.of("guild-b"), 10_000);
            assertEquals(List.of("guild-a-new"), a.get());
            assertEquals(List.of("guild-b"), b.get());
        }
    }

    @Test
    void noBudgetNeverStartsQueryAndDatabaseFailureIsObservable() throws Exception {
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var work = new PlaylistInteractionWork(timer, Clock.systemUTC())) {
            assertEquals(List.of(), work.autocomplete(() -> { fail("expired query"); return List.of(); }, 0).get());
            var result = work.autocomplete(() -> { throw new IllegalStateException("busy"); }, 10_000);
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, result::get).getCause());
        }
    }

    @Test
    void saturatedDatabasePoolReturnsEmptyWithoutRunningOnCaller() throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(2);
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var work = new PlaylistInteractionWork(timer, Clock.systemUTC())) {
            for (int i = 0; i < 2; i++) work.autocomplete(() -> {
                entered.countDown(); await(release); return List.of("slow");
            }, 10_000);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 32; i++) work.autocomplete(() -> List.of("queued"), 10_000);
            assertEquals(List.of(), work.autocomplete(() -> { fail("overload"); return List.of(); }, 10_000).get());
            release.countDown();
        } finally { release.countDown(); }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }

    private static final class ManualDeadline extends ScheduledThreadPoolExecutor {
        private Runnable deadline;
        ManualDeadline() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            deadline = task;
            return super.schedule(() -> {}, 1, TimeUnit.DAYS);
        }
        void expire() { deadline.run(); }
    }
}
