package com.norule.musicbot.discord.bot.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DiscordBackgroundRestQueueTest {
    @Test
    void overloadIsBoundedAndCloseReleasesAllPendingWaiters() {
        try (var clock = new VirtualScheduler(); var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            queue.pause(60_000);
            var results = new ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < DiscordBackgroundRestQueue.MAX_PENDING; i++) {
                results.add(queue.submit("guild:" + i, () -> CompletableFuture.completedFuture(null)));
            }
            assertTrue(queue.submit("overflow", () -> CompletableFuture.completedFuture(null)).isCompletedExceptionally());
            assertEquals(1, queue.metrics().rejected());
            queue.close();
            assertTrue(results.stream().allMatch(CompletableFuture::isCancelled));
            assertEquals(DiscordBackgroundRestQueue.MAX_PENDING, queue.metrics().cancelled());
            assertEquals(0, queue.pendingCount());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 300, 500})
    void manyGuildsHaveBoundedPendingAndOutstandingRequests(int count) {
        try (var clock = new VirtualScheduler(); var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            var flights = new ArrayList<CompletableFuture<Void>>();
            for (int guild = 0; guild < count; guild++) {
                queue.submit("guild:" + guild, () -> {
                    var future = new CompletableFuture<Void>();
                    flights.add(future);
                    return future;
                });
            }
            assertEquals(Math.min(count, DiscordBackgroundRestQueue.MAX_PENDING), queue.pendingCount());
            clock.advance(0);
            assertEquals(1, flights.size());
            clock.advance(DiscordBackgroundRestQueue.INTERVAL_MS - 1);
            assertEquals(1, flights.size());
            clock.advance(1);
            assertEquals(2, flights.size());
            for (int i = 2; i < DiscordBackgroundRestQueue.MAX_IN_FLIGHT; i++) {
                clock.advance(DiscordBackgroundRestQueue.INTERVAL_MS);
            }
            clock.advance(60_000);
            assertEquals(DiscordBackgroundRestQueue.MAX_IN_FLIGHT, flights.size());
            flights.getFirst().complete(null);
            clock.advance(0);
            assertEquals(DiscordBackgroundRestQueue.MAX_IN_FLIGHT + 1, flights.size());
            assertEquals(DiscordBackgroundRestQueue.MAX_IN_FLIGHT, queue.inFlightCount());
        }
    }

    @Test
    void stalledKeyDoesNotBlockOtherGuildsOrOverlapItsOwnReplacement() {
        try (var clock = new VirtualScheduler(); var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            var active = new CompletableFuture<Void>();
            var sent = new ArrayList<String>();
            queue.submit("guild:1", () -> active);
            clock.advance(0);
            queue.submit("guild:1", () -> { fail("Superseded"); return null; });
            queue.submit("guild:1", () -> { sent.add("latest"); return CompletableFuture.completedFuture(null); });
            queue.submit("guild:2", () -> { sent.add("other"); return CompletableFuture.completedFuture(null); });
            clock.advance(DiscordBackgroundRestQueue.INTERVAL_MS);
            assertEquals(List.of("other"), sent);
            clock.advance(10_000);
            assertEquals(List.of("other"), sent);
            active.complete(null);
            clock.advance(0);
            assertEquals(List.of("other", "latest"), sent);
            assertEquals(1, queue.metrics().coalesced());
            assertEquals(3, queue.metrics().completed());
        }
    }

    @Test
    void throwingSupplierAndRejectedSchedulerReleaseCallers() {
        try (var clock = new VirtualScheduler(); var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            var failed = queue.submit("one", () -> { throw new IllegalStateException("failed"); });
            clock.advance(0);
            assertTrue(failed.isCompletedExceptionally());
            assertEquals(0, queue.inFlightCount());
            var next = queue.submit("one", () -> CompletableFuture.completedFuture(null));
            clock.advance(DiscordBackgroundRestQueue.INTERVAL_MS);
            assertTrue(next.isDone());
        }
        var stopped = new ScheduledThreadPoolExecutor(1);
        stopped.shutdownNow();
        try (var queue = new DiscordBackgroundRestQueue(stopped)) {
            assertTrue(queue.submit("one", () -> CompletableFuture.completedFuture(null)).isCompletedExceptionally());
            assertEquals(0, queue.pendingCount());
        }
    }

    @Test
    void sameGuildPendingUpdatesKeepOnlyLatestAndNoFailureRetries() {
        try (var clock = new VirtualScheduler(); var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            queue.pause(5000);
            var values = new ArrayList<String>();
            var first = queue.submit("panel:1", () -> { fail("Stale update"); return null; });
            var second = queue.submit("panel:1", () -> {
                values.add("latest");
                return CompletableFuture.failedFuture(new IllegalStateException("429 final failure"));
            });
            assertSame(first, second);
            assertEquals(1, queue.pendingCount());
            clock.advance(4999);
            assertTrue(values.isEmpty());
            clock.advance(1);
            assertEquals(List.of("latest"), values);
            assertTrue(first.isCompletedExceptionally());
            clock.advance(60_000);
            assertEquals(1, values.size());
            assertEquals(0, queue.inFlightCount());
        }
    }

    @Test
    void foregroundPlayCanSupersedeQueuedProgressDuringGlobalPause() {
        try (var clock = new VirtualScheduler(); var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            queue.pause(10_000);
            var progress = queue.submit("music-panel:1", () -> { fail("Cancelled progress"); return null; });
            var otherGuild = new AtomicInteger();
            queue.submit("music-panel:2", () -> {
                otherGuild.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            queue.cancelPending("music-panel:1");
            assertTrue(progress.isCancelled());
            // Foreground dispatch has no dependency on optional background admission.
            var play = CompletableFuture.completedFuture("queued");
            assertEquals("queued", play.join());
            clock.advance(10_000);
            assertEquals(1, otherGuild.get());
        }
    }

    @Test
    void closeCancelsPendingAndDoesNotResubmitActiveWork() {
        try (var clock = new VirtualScheduler()) {
            var queue = new DiscordBackgroundRestQueue(clock, clock::nanos);
            var active = new CompletableFuture<Void>();
            queue.submit("one", () -> active);
            clock.advance(0);
            var pending = queue.submit("two", () -> { fail("Closed queue"); return null; });
            queue.close();
            active.complete(null);
            clock.advance(60_000);
            assertTrue(pending.isCancelled());
            assertTrue(queue.submit("three", () -> active).isCompletedExceptionally());
        }
    }

    static final class VirtualScheduler extends ScheduledThreadPoolExecutor {
        private long now;
        private final List<Task> tasks = new ArrayList<>();
        VirtualScheduler() { super(1); }
        long nanos() { return TimeUnit.MILLISECONDS.toNanos(now); }
        @Override public ScheduledFuture<?> schedule(Runnable runnable, long delay, TimeUnit unit) {
            var task = new Task(runnable, now + unit.toMillis(delay));
            tasks.add(task);
            return task;
        }
        void advance(long millis) {
            now += millis;
            for (var task : List.copyOf(tasks)) {
                if (!task.isDone() && task.due <= now) task.run();
            }
            tasks.removeIf(Task::isDone);
        }
        private final class Task extends FutureTask<Void> implements ScheduledFuture<Void> {
            final long due;
            Task(Runnable action, long due) { super(action, null); this.due = due; }
            public long getDelay(TimeUnit unit) { return unit.convert(due - now, TimeUnit.MILLISECONDS); }
            public int compareTo(Delayed other) { return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS)); }
        }
    }
}
