package com.norule.musicbot.service.music;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistLoadCoordinatorTest {
    @Test void twentyFiveEntriesAreSequentialAndCountersReflectCallbacksNotSubmission() {
        ManualLoadTimer timer = new ManualLoadTimer();
        List<Integer> started = new ArrayList<>();
        List<CompletableFuture<PlaylistLoadCoordinator.TrackResult>> pending = new ArrayList<>();
        try (var coordinator = new PlaylistLoadCoordinator(timer.clock, timer)) {
            var tracks = tasks(25, index -> {
                started.add(index);
                var future = new CompletableFuture<PlaylistLoadCoordinator.TrackResult>();
                pending.add(future);
                return future;
            });
            var submission = coordinator.submit(1, 5L, "Example", tracks);
            assertEquals(25, submission.initial().submitted());
            assertEquals(0, submission.initial().queued());
            assertEquals(25, submission.initial().pending());
            timer.runReady();
            assertEquals(List.of(0), started);
            for (int i = 0; i < 25; i++) {
                assertEquals(i + 1, pending.size());
                pending.get(i).complete(new PlaylistLoadCoordinator.TrackResult(i == 24 ? 0 : 1, i == 24 ? 0 : 1, i == 24, false));
                timer.runReady();
            }
            var result = submission.completion().toCompletableFuture().join();
            assertEquals(24, result.loaded()); assertEquals(24, result.queued());
            assertEquals(1, result.failed()); assertEquals(0, result.pending());
            for (int i = 0; i < 25; i++) assertEquals(i, started.get(i));
        }
    }

    @Test void duplicateIsPerGuildAndNormalizedNameAndCanReloadAfterCompletion() {
        ManualLoadTimer timer = new ManualLoadTimer();
        try (var coordinator = new PlaylistLoadCoordinator(timer.clock, timer)) {
            var tracks = tasks(1, ignored -> CompletableFuture.completedFuture(new PlaylistLoadCoordinator.TrackResult(1, 1, false, false)));
            var first = coordinator.submit(1, 5L, "Example", tracks);
            var duplicate = coordinator.submit(1, 6L, " example ", tracks);
            assertEquals(PlaylistLoadCoordinator.Status.DUPLICATE, duplicate.status());
            assertEquals(first.initial().batchId(), duplicate.initial().batchId());
            assertEquals(PlaylistLoadCoordinator.Status.ACCEPTED, coordinator.submit(2, 5L, "Example", tracks).status());
            assertEquals(PlaylistLoadCoordinator.Status.ACCEPTED, coordinator.submit(1, 5L, "Different", tracks).status());
            timer.runReady();
            assertEquals(PlaylistLoadCoordinator.Status.ACCEPTED, coordinator.submit(1, 5L, "Example", tracks).status());
        }
    }

    @Test void cancelAndDeadlineFenceLateQueueMutationAndReleaseCapacity() {
        for (boolean timeout : new boolean[]{false, true}) {
            ManualLoadTimer timer = new ManualLoadTimer();
            List<PlaylistLoadCoordinator.EntryContext> contexts = new ArrayList<>();
            try (var coordinator = new PlaylistLoadCoordinator(timer.clock, timer)) {
                var tracks = tasks(25, ignored -> new CompletableFuture<>());
                tracks.set(0, context -> { contexts.add(context); return new CompletableFuture<>(); });
                var submission = coordinator.submit(1, 5L, "Example", tracks);
                timer.runReady();
                if (timeout) timer.advance(600_000);
                else { coordinator.cancelGuild(1); timer.runReady(); }
                assertEquals(25, submission.completion().toCompletableFuture().join().cancelled());
                assertFalse(contexts.get(0).isCurrent().getAsBoolean());
                contexts.get(0).commit().accept(() -> fail("late queue mutation"));
                assertEquals(PlaylistLoadCoordinator.Status.ACCEPTED, coordinator.submit(1, 5L, "Example", tracks).status());
            }
        }
    }

    @Test void rejectsExcessWorkGloballyAndPerGuildAndCountsSkippedAndErrors() {
        ManualLoadTimer timer = new ManualLoadTimer();
        try (var coordinator = new PlaylistLoadCoordinator(timer.clock, timer)) {
            var tracks = tasks(100, ignored -> new CompletableFuture<>());
            for (long guild = 1; guild <= 10; guild++)
                assertEquals(PlaylistLoadCoordinator.Status.ACCEPTED, coordinator.submit(guild, null, "A", tracks).status());
            assertEquals(PlaylistLoadCoordinator.Status.FULL, coordinator.submit(1, null, "B", tracks).status());
            assertEquals(PlaylistLoadCoordinator.Status.FULL, coordinator.submit(11, null, "A", tracks).status());
            coordinator.cancelGuild(1);
            var mixed = tasks(3, index -> switch (index) {
                case 0 -> CompletableFuture.completedFuture(new PlaylistLoadCoordinator.TrackResult(0, 0, false, true));
                case 1 -> CompletableFuture.failedFuture(new IllegalArgumentException("invalid"));
                default -> CompletableFuture.completedFuture(new PlaylistLoadCoordinator.TrackResult(1, 1, false, false));
            });
            var result = coordinator.submit(1, null, "B", mixed);
            timer.runReady();
            var completed = result.completion().toCompletableFuture().join();
            assertEquals(1, completed.skipped()); assertEquals(1, completed.failed()); assertEquals(1, completed.queued());
        }
    }

    @Test void cancellationRetainsAlreadyCommittedMetadataAndQueueCounts() {
        ManualLoadTimer timer = new ManualLoadTimer();
        try (var coordinator = new PlaylistLoadCoordinator(timer.clock, timer)) {
            var tracks = tasks(2, ignored -> new CompletableFuture<>());
            tracks.set(0, context -> {
                context.progress().accept(1, 0);
                context.commit().accept(() -> context.progress().accept(0, 1));
                return new CompletableFuture<>();
            });
            var submission = coordinator.submit(1, 5L, "Example", tracks);
            timer.runReady();
            coordinator.cancelGuild(1); timer.runReady();
            var result = submission.completion().toCompletableFuture().join();
            assertEquals(1, result.loaded()); assertEquals(1, result.queued());
            assertEquals(1, result.cancelled());
        }
    }

    private static List<Function<PlaylistLoadCoordinator.EntryContext, CompletionStage<PlaylistLoadCoordinator.TrackResult>>> tasks(
            int count, Function<Integer, CompletionStage<PlaylistLoadCoordinator.TrackResult>> task) {
        List<Function<PlaylistLoadCoordinator.EntryContext, CompletionStage<PlaylistLoadCoordinator.TrackResult>>> result = new ArrayList<>();
        for (int i = 0; i < count; i++) { int index = i; result.add(ignored -> task.apply(index)); }
        return result;
    }
}
