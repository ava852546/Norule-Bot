package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.EmbedBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MusicPanelRefreshCoordinatorTest {
    private final ManualScheduler scheduler = new ManualScheduler();
    private final List<String> sent = new ArrayList<>();
    private final List<CompletableFuture<Void>> flights = new ArrayList<>();
    private String content = "volume 30";
    private long messageId = 100L;
    private Set<RefreshReason> renderedReasons;
    private final MusicPanelRefreshCoordinator coordinator = new MusicPanelRefreshCoordinator(scheduler,
            (guildId, reasons) -> {
                renderedReasons = reasons;
                String rendered = content;
                return CompletableFuture.completedFuture(new MusicPanelRefreshCoordinator.Update(
                        guildId * 10, messageId, snapshot(rendered), () -> {
                            sent.add(guildId + ":" + rendered);
                            var future = new CompletableFuture<Void>();
                            flights.add(future);
                            return future;
                        }));
            });

    @AfterEach
    void close() {
        coordinator.close();
        scheduler.shutdownNow();
    }

    @Test
    void tenRequestsDebounceToOneLatestRenderAfter750Millis() {
        for (int i = 0; i < 10; i++) {
            coordinator.requestRefresh(1, RefreshReason.QUEUE_CHANGED);
        }
        assertEquals(1, scheduler.pending());
        scheduler.advance(749);
        assertTrue(sent.isEmpty());
        content = "volume 50";
        scheduler.advance(1);
        assertEquals(List.of("1:volume 50"), sent);
    }

    @Test
    void identicalSnapshotsAreSkippedAfterFirstSuccessfulPatch() {
        requestAndDrain(1);
        flights.getFirst().complete(null);
        for (int i = 0; i < 10; i++) {
            requestAndDrain(1);
        }
        assertEquals(1, sent.size());
        assertEquals(0, scheduler.pending());
    }

    @Test
    void inFlightEventsKeepOnlyOneLatestFollowUp() {
        requestAndDrain(1);
        content = "volume 40";
        coordinator.requestRefresh(1, RefreshReason.VOLUME_CHANGED);
        content = "volume 50";
        coordinator.requestRefresh(1, RefreshReason.PAUSE);
        scheduler.advance(10_000);
        assertEquals(1, sent.size());
        assertEquals(0, scheduler.pending());
        flights.getFirst().complete(null);
        assertEquals(1, scheduler.pending());
        scheduler.advance(750);
        assertEquals(List.of("1:volume 30", "1:volume 50"), sent);
        assertEquals(Set.of(RefreshReason.VOLUME_CHANGED, RefreshReason.PAUSE), renderedReasons);
        flights.get(1).complete(null);
        assertEquals(0, scheduler.pending());
    }

    @Test
    void dirtyButUnchangedDoesNotSendAgain() {
        requestAndDrain(1);
        coordinator.requestRefresh(1, RefreshReason.BUTTON_INTERACTION);
        flights.getFirst().complete(null);
        scheduler.advance(750);
        assertEquals(1, sent.size());
    }

    @Test
    void failedDeliveryIsNotCachedOrAutomaticallyRetried() {
        requestAndDrain(1);
        flights.getFirst().completeExceptionally(new IllegalStateException("transport failed"));
        scheduler.advance(60_000);
        assertEquals(1, sent.size());
        assertEquals(0, scheduler.pending());
        requestAndDrain(1);
        assertEquals(2, sent.size());
    }

    @Test
    void differentGuildDoesNotWaitForPendingPatch() {
        requestAndDrain(1);
        requestAndDrain(2);
        assertEquals(List.of("1:volume 30", "2:volume 30"), sent);
        assertFalse(flights.getFirst().isDone());
    }

    @Test
    void repeatedStopStartCancelsOldTasks() {
        for (int i = 0; i < 10; i++) {
            coordinator.requestRefresh(1, RefreshReason.TRACK_START);
            coordinator.clear(1);
        }
        scheduler.advance(30_000);
        assertTrue(sent.isEmpty());
        assertEquals(0, scheduler.pending());
        requestAndDrain(1);
        assertEquals(1, sent.size());
    }

    @Test
    void clearingInFlightKeepsBarrierAndDoesNotCacheStaleSuccess() {
        requestAndDrain(1);
        coordinator.clear(1);
        messageId = 200L;
        coordinator.requestRefresh(1, RefreshReason.TRACK_START);
        scheduler.advance(30_000);
        assertEquals(1, sent.size());
        flights.getFirst().complete(null);
        scheduler.advance(750);
        assertEquals(2, sent.size());
    }

    @Test
    void clearedAsyncPreparationCannotSubmitStalePatch() {
        var prepared = new CompletableFuture<MusicPanelRefreshCoordinator.Update>();
        try (var other = new MusicPanelRefreshCoordinator(scheduler, (guild, reasons) -> prepared)) {
            other.requestRefresh(1, RefreshReason.TRACK_START);
            scheduler.advance(750);
            other.clear(1);
            prepared.complete(new MusicPanelRefreshCoordinator.Update(10, 100, snapshot("old"), () -> {
                fail("stale prepared edit was submitted");
                return CompletableFuture.completedFuture(null);
            }));
        }
    }

    @Test
    void newlyCreatedPayloadDoesNotNeedAnIdenticalPatch() {
        coordinator.recordCreated(1, new MusicPanelRefreshCoordinator.Update(10, 100, snapshot(content), null));
        requestAndDrain(1);
        assertTrue(sent.isEmpty());
        content = "changed during creation";
        requestAndDrain(1);
        assertEquals(1, sent.size());
    }

    @Test
    void concurrentPlayerAndInteractionEventsShareOneTask() throws Exception {
        var reasons = List.of(RefreshReason.TRACK_START, RefreshReason.QUEUE_CHANGED,
                RefreshReason.VOLUME_CHANGED, RefreshReason.PAUSE);
        try (var workers = Executors.newFixedThreadPool(4)) {
            CountDownLatch ready = new CountDownLatch(4);
            CountDownLatch start = new CountDownLatch(1);
            var tasks = reasons.stream().map(reason -> workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 25; i++) {
                    coordinator.requestRefresh(1, reason);
                }
                return null;
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        }
        assertEquals(1, scheduler.pending());
        scheduler.advance(750);
        assertEquals(1, sent.size());
        assertEquals(Set.copyOf(reasons), renderedReasons);
    }

    @Test
    void shutdownDropsPendingAndRejectsNewWork() {
        coordinator.requestRefresh(1, RefreshReason.MANUAL);
        coordinator.close();
        coordinator.requestRefresh(2, RefreshReason.MANUAL);
        scheduler.advance(30_000);
        assertTrue(sent.isEmpty());
        assertEquals(0, scheduler.pending());
    }

    private void requestAndDrain(long guildId) {
        coordinator.requestRefresh(guildId, RefreshReason.MANUAL);
        scheduler.advance(750);
    }

    private static MusicPanelSnapshot snapshot(String content) {
        return new MusicPanelSnapshot(new EmbedBuilder().setDescription(content).build(), List.of());
    }

    /** Virtual time: exercise real scheduling decisions without sleeps or timing-sensitive assertions. */
    static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<Task> tasks = new ArrayList<>();
        private long now;

        ManualScheduler() {
            super(1);
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            Task task = new Task(command, now + unit.toMillis(delay));
            tasks.add(task);
            return task;
        }

        synchronized int pending() {
            return (int) tasks.stream().filter(task -> !task.isDone()).count();
        }

        void advance(long millis) {
            now += millis;
            for (Task task : List.copyOf(tasks)) {
                if (!task.isDone() && task.due <= now) {
                    task.run();
                }
            }
            tasks.removeIf(Task::isDone);
        }

        private final class Task extends FutureTask<Void> implements ScheduledFuture<Void> {
            private final long due;

            Task(Runnable command, long due) {
                super(command, null);
                this.due = due;
            }

            @Override
            public long getDelay(TimeUnit unit) {
                return unit.convert(due - now, TimeUnit.MILLISECONDS);
            }

            @Override
            public int compareTo(Delayed other) {
                return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
            }
        }
    }
}
