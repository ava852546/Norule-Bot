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
            }, scheduler::nanos);

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
        scheduler.advance(3_000);
    }

    @Test
    void progressSweepUpdatesWithinTenSecondsAndStopsWhenPaused() {
        var lastSuccess = new java.util.concurrent.atomic.AtomicLong();
        var paused = new java.util.concurrent.atomic.AtomicBoolean();
        var deliveries = new ArrayList<Long>();
        try (var refresh = new MusicPanelRefreshCoordinator(scheduler, (guild, reasons) ->
                CompletableFuture.completedFuture(new MusicPanelRefreshCoordinator.Update(10, 100,
                        snapshot("position " + scheduler.nanos()), () -> {
                            deliveries.add(TimeUnit.NANOSECONDS.toMillis(scheduler.nanos()));
                            lastSuccess.set(deliveries.getLast());
                            return CompletableFuture.completedFuture(null);
                        })), scheduler::nanos)) {
            for (int second = 1; second <= 40; second++) {
                scheduler.advance(1_000);
                long now = TimeUnit.NANOSECONDS.toMillis(scheduler.nanos());
                if (!refresh.hasPending(1) && MusicPanelRefreshService.progressRefreshDue(
                        !paused.get(), lastSuccess.get(), now, 8_000)) {
                    refresh.requestRefresh(1, RefreshReason.PERIODIC_REFRESH);
                }
            }
            assertEquals(List.of(9_000L, 18_000L, 27_000L, 36_000L), deliveries);
            paused.set(true);
            for (int second = 0; second < 60; second++) {
                scheduler.advance(1_000);
                assertFalse(MusicPanelRefreshService.progressRefreshDue(!paused.get(), lastSuccess.get(),
                        TimeUnit.NANOSECONDS.toMillis(scheduler.nanos()), 8_000));
            }
            assertEquals(4, deliveries.size());
        }
    }

    @Test
    void foregroundEventAfterProgressIsDeliveredWithinOneSecondAndMetricsAreAvailable() {
        requestAndDrain(1);
        coordinator.submitted(1);
        flights.getFirst().complete(null);
        content = "new song";
        coordinator.requestRefresh(1, RefreshReason.TRACK_START);
        scheduler.advance(999);
        assertEquals(1, sent.size());
        scheduler.advance(1);
        assertEquals("1:new song", sent.getLast());
        coordinator.submitted(1);
        coordinator.observeRateLimit(1);
        scheduler.advance(2_000);
        flights.getLast().complete(null);
        assertTrue(coordinator.diagnostics(1).contains("route429Count=1"));
        assertTrue(coordinator.diagnostics(1).contains("restMs=2000 totalMs=3000"));
        requestAndDrain(1);
        assertTrue(coordinator.diagnostics(1).contains("unchangedCount=1"));
    }

    @Test
    void manualCommandWaitsForAutomaticPatchAndReportsActualDelivery() {
        requestAndDrain(1);
        var delivery = new CompletableFuture<Void>();
        var manual = coordinator.submit(1, () -> CompletableFuture.completedFuture(
                new MusicPanelRefreshCoordinator.Update(10, 100, snapshot("manual"), () -> {
                    sent.add("manual");
                    return delivery;
                })));
        scheduler.advance(750);
        assertFalse(manual.isDone());
        assertEquals(1, sent.size());
        flights.getFirst().complete(null);
        scheduler.advance(3_000);
        assertEquals(List.of("1:volume 30", "manual"), sent);
        assertFalse(manual.isDone());
        delivery.completeExceptionally(new IllegalStateException("Missing Access"));
        assertTrue(manual.isCompletedExceptionally());
        assertEquals(0, scheduler.pending());
    }

    @Test
    void rapidManualCreationRequestsResolveSequentiallyAndCreateOnlyOnce() {
        var creation = new CompletableFuture<MusicPanelRefreshCoordinator.Update>();
        var created = new java.util.concurrent.atomic.AtomicBoolean();
        var sends = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<CompletableFuture<MusicPanelRefreshCoordinator.Update>> prepareManual = () -> {
            if (created.get()) {
                return CompletableFuture.completedFuture(null);
            }
            sends.incrementAndGet();
            return creation;
        };
        var first = coordinator.submit(1, prepareManual);
        var second = coordinator.submit(1, prepareManual);
        scheduler.advance(750);
        assertEquals(1, sends.get());
        assertFalse(second.isDone());
        created.set(true);
        creation.complete(null);
        scheduler.advance(750);
        assertTrue(first.isDone());
        assertTrue(second.isDone());
        assertEquals(1, sends.get());
    }

    @Test
    void identicalManualUpdateCompletesWithoutPatchAndClosedQueueRejectsCommands() {
        requestAndDrain(1);
        flights.getFirst().complete(null);
        var manual = coordinator.submit(1, () -> CompletableFuture.completedFuture(
                new MusicPanelRefreshCoordinator.Update(10, 100, snapshot(content), () -> {
                    fail("Identical content must not PATCH");
                    return CompletableFuture.completedFuture(null);
                })));
        scheduler.advance(3_000);
        assertTrue(manual.isDone());
        assertFalse(manual.isCompletedExceptionally());
        coordinator.close();
        assertTrue(coordinator.submit(1, () -> CompletableFuture.completedFuture(null)).isCompletedExceptionally());
    }

    private static MusicPanelSnapshot snapshot(String content) {
        return new MusicPanelSnapshot(new EmbedBuilder().setDescription(content).build(), List.of());
    }

    @Test
    void continuousChangesRespectOneSecondIntervalAndEventuallyShowLatestState() {
        coordinator.requestRefresh(1, RefreshReason.TRACK_START);
        scheduler.advance(750);
        flights.getLast().complete(null);
        for (int i = 1; i <= 9; i++) {
            content = "track " + i;
            coordinator.requestRefresh(1, RefreshReason.QUEUE_CHANGED);
            scheduler.advance(1_000);
            flights.getLast().complete(null);
        }
        scheduler.advance(3_000);
        flights.getLast().complete(null);
        assertEquals(10, sent.size());
        assertEquals("1:track 9", sent.getLast());
        assertEquals(0, scheduler.pending());
    }

    @Test
    void minimumIntervalIsNotOnlyADebounceAndRendersAtDeliveryTime() {
        coordinator.requestRefresh(1, RefreshReason.TRACK_START);
        scheduler.advance(750);
        flights.getFirst().complete(null);
        content = "track B";
        coordinator.requestRefresh(1, RefreshReason.TRACK_START);
        scheduler.advance(750);
        assertEquals(1, sent.size());
        content = "track C";
        coordinator.requestRefresh(1, RefreshReason.QUEUE_CHANGED);
        scheduler.advance(249);
        assertEquals(1, sent.size());
        scheduler.advance(1);
        assertEquals(List.of("1:volume 30", "1:track C"), sent);
    }

    @Test
    void highPriorityReplacesUnsentProgressAndBackgroundCannotDisplaceIt() {
        coordinator.requestRefresh(1, RefreshReason.PERIODIC_REFRESH);
        coordinator.requestRefresh(1, RefreshReason.PAUSE);
        coordinator.requestRefresh(1, RefreshReason.PERIODIC_REFRESH);
        coordinator.requestRefresh(1, RefreshReason.PERIODIC_REFRESH);
        scheduler.advance(750);
        assertEquals(Set.of(RefreshReason.PAUSE), renderedReasons);
        assertEquals(1, sent.size());
    }

    @Test
    void longJdaRateLimitWaitKeepsOneFlightAndOnePendingLatestState() {
        requestAndDrain(1);
        for (int i = 0; i < 10_000; i++) {
            content = "track " + i;
            coordinator.requestRefresh(1, RefreshReason.TRACK_START);
            coordinator.requestRefresh(1, RefreshReason.PERIODIC_REFRESH);
        }
        scheduler.advance(60_000);
        assertEquals(1, sent.size());
        assertEquals(0, scheduler.pending());
        assertTrue(coordinator.diagnostics(1).contains("pendingRefresh=true inFlight=true"));
        flights.getFirst().complete(null); // JDA, rather than an application retry, completes the edit.
        scheduler.advance(750);
        assertEquals(List.of("1:volume 30", "1:track 9999"), sent);
        assertEquals(Set.of(RefreshReason.TRACK_START), renderedReasons);
    }

    @Test
    void cancelledProgressTaskAlreadyWakingCannotDrainForegroundWorkTwice() {
        coordinator.requestRefresh(1, RefreshReason.PERIODIC_REFRESH);
        Runnable wakingProgress = scheduler.latestCommand;
        coordinator.requestRefresh(1, RefreshReason.PAUSE);
        // cancel(false) cannot prevent a runnable already waiting for the state lock.
        wakingProgress.run();
        assertTrue(sent.isEmpty());
        scheduler.advance(750);
        assertEquals(1, sent.size());
        assertEquals(Set.of(RefreshReason.PAUSE), renderedReasons);
    }

    @Test
    void failedFirstEditStillThrottlesSubsequentEventsWithoutAutomaticRetry() {
        coordinator.requestRefresh(1, RefreshReason.MANUAL);
        scheduler.advance(750);
        flights.getFirst().completeExceptionally(new IllegalStateException("network failure"));
        coordinator.requestRefresh(1, RefreshReason.MANUAL);
        scheduler.advance(750);
        assertEquals(1, sent.size());
        scheduler.advance(250);
        assertEquals(2, sent.size());
    }

    @Test
    void oldDeliveryCannotOverwriteSnapshotRecordedForReplacementPanel() {
        requestAndDrain(1);
        messageId = 200;
        content = "new panel";
        coordinator.recordCreated(1, new MusicPanelRefreshCoordinator.Update(10, 200, snapshot(content), null));
        flights.getFirst().complete(null);
        requestAndDrain(1);
        assertEquals(1, sent.size());
        assertEquals(0, scheduler.pending());
    }

    @Test
    void intervalStartsAgainWhenBackgroundWorkActuallyEntersRestAfterLongAdmissionWait() {
        requestAndDrain(1);
        scheduler.advance(60_000); // The background supplier has not submitted HTTP yet.
        coordinator.submitted(1);
        content = "latest";
        coordinator.requestRefresh(1, RefreshReason.TRACK_START);
        flights.getFirst().complete(null);
        scheduler.advance(999);
        assertEquals(1, sent.size());
        scheduler.advance(1);
        assertEquals(List.of("1:volume 30", "1:latest"), sent);
    }

    @Test
    void aNewPanelCreationAlsoSpacesSubsequentEditsAndLegacyReplacementWork() {
        coordinator.recordCreated(1, new MusicPanelRefreshCoordinator.Update(10, 100, snapshot(content), null));
        content = "new song";
        coordinator.requestRefresh(1, RefreshReason.PLAY_ADDED);
        scheduler.advance(750);
        assertTrue(sent.isEmpty());
        scheduler.advance(250);
        assertEquals(List.of("1:new song"), sent);
    }

    @Test
    void discardedQueuedPayloadDoesNotReplaceTheLastSuccessfulSnapshot() {
        requestAndDrain(1);
        flights.getFirst().complete(null);
        content = "stale queued progress";
        requestAndDrain(1);
        coordinator.discardPrepared(1);
        flights.getLast().complete(null);
        content = "volume 30";
        requestAndDrain(1);
        assertEquals(2, sent.size());
        assertEquals(0, scheduler.pending());
    }

    @Test
    void playSupersedesQueuedProgressWithoutWaitingForGlobalMaintenancePause() {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var foreground = new java.util.concurrent.atomic.AtomicInteger();
        try (var background = new com.norule.musicbot.discord.bot.gateway.DiscordBackgroundRestQueue(scheduler, now::get);
             var refresh = new MusicPanelRefreshCoordinator(scheduler, (guildId, reasons) ->
                     CompletableFuture.completedFuture(new MusicPanelRefreshCoordinator.Update(
                             10, 100, snapshot(reasons.toString()), () -> {
                                 if (reasons.equals(Set.of(RefreshReason.PERIODIC_REFRESH))) {
                                     return background.submit("music-panel:" + guildId, () -> {
                                         fail("Superseded periodic REST must not run");
                                         return null;
                                     });
                                 }
                                 foreground.incrementAndGet();
                                 return CompletableFuture.completedFuture(null);
                             })), scheduler::nanos)) {
            background.pause(60_000);
            refresh.requestRefresh(1, RefreshReason.PERIODIC_REFRESH);
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_000));
            scheduler.advance(1_000);
            assertEquals(1, background.pendingCount());
            // The same cancellation/admission sequence used by MusicPanelRefreshService.
            background.cancelPending("music-panel:1");
            refresh.requestRefresh(1, RefreshReason.PLAY_ADDED);
            refresh.requestRefresh(2, RefreshReason.BUTTON_INTERACTION);
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(3_000));
            scheduler.advance(3_000);
            assertEquals(2, foreground.get());
            assertEquals(0, background.pendingCount());
        }
    }

    /** Virtual time: exercise real scheduling decisions without sleeps or timing-sensitive assertions. */
    static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<Task> tasks = new ArrayList<>();
        private long now;
        private Runnable latestCommand;

        ManualScheduler() {
            super(1);
        }

        long nanos() {
            return TimeUnit.MILLISECONDS.toNanos(now);
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            latestCommand = command;
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
