package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.EmbedBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Virtual player state and fake sender; these deliveries are not Discord HTTP measurements. */
class MusicPanelLatestStateTest {
    @ParameterizedTest
    @ValueSource(ints = {100, 300, 500})
    void batchPendingAndFinalVersionsStayIndependentAcrossGuilds(int guilds) {
        try (var clock = new MusicPanelRefreshCoordinatorTest.ManualScheduler()) {
            int[] version = new int[guilds], displayed = new int[guilds], deliveries = new int[guilds];
            try (var panels = new MusicPanelRefreshCoordinator(clock, (guild, reasons) -> {
                int id = Math.toIntExact(guild);
                int latest = version[id];
                return CompletableFuture.completedFuture(new MusicPanelRefreshCoordinator.Update(id + 10, 100,
                        snapshot(id + ":" + latest), () -> {
                    displayed[id] = latest;
                    deliveries[id]++;
                    return CompletableFuture.completedFuture(null);
                }));
            }, clock::nanos)) {
                for (int id = 0; id < guilds; id++) panels.requestRefresh(id, RefreshReason.TRACK_START);
                clock.advance(1_000);
                for (int entry = 0; entry < 96; entry++) {
                    for (int id = 0; id < guilds; id++) {
                        version[id] = id * 1_000 + entry + 1;
                        panels.requestRefresh(id, RefreshReason.BATCH_PROGRESS, true);
                    }
                    clock.advance(480);
                    assertEquals(0, clock.pending());
                }
                for (int id = 0; id < guilds; id++) panels.requestRefresh(id, RefreshReason.BATCH_COMPLETE);
                clock.advance(1_000);
                for (int id = 0; id < guilds; id++) {
                    assertEquals(version[id], displayed[id]);
                    assertEquals(2, deliveries[id]);
                    assertFalse(panels.hasPending(id));
                }
                System.out.printf("PANEL_LATEST_GUILDS guilds=%d deliveries=%d maxDirtyPerGuild=1 finalCorrect=true%n", guilds,
                        java.util.Arrays.stream(deliveries).sum());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {96, 500})
    void sustainedBatchKeepsOneDirtyRequestAndDeliversFirstAndFinalOnly(int count) {
        try (var clock = new MusicPanelRefreshCoordinatorTest.ManualScheduler()) {
            var tracks = new ArrayList<Integer>();
            var displayed = new ArrayList<String>();
            var latencies = new ArrayList<Long>();
            var ref = new AtomicReference<MusicPanelRefreshCoordinator>();
            int[] active = {0}, maxActive = {0}, maxPending = {0}, renders = {0};
            long[] requestedAt = {0};
            try (var panels = new MusicPanelRefreshCoordinator(clock, (guild, reasons) -> {
                renders[0]++;
                String latest = content(tracks);
                return CompletableFuture.completedFuture(new MusicPanelRefreshCoordinator.Update(10, 100,
                        snapshot(latest), () -> {
                    active[0]++;
                    maxActive[0] = Math.max(maxActive[0], active[0]);
                    ref.get().patchSubmitted(guild);
                    displayed.add(latest);
                    latencies.add(TimeUnit.NANOSECONDS.toMillis(clock.nanos()) - requestedAt[0]);
                    active[0]--;
                    return CompletableFuture.completedFuture(null);
                }));
            }, clock::nanos)) {
                ref.set(panels);
                panels.requestRefresh(1, RefreshReason.TRACK_START);
                clock.advance(1_000);
                for (int i = 0; i < count; i++) {
                    tracks.add(i);
                    // Production entry begin, queue commit, and entry end retain their origin.
                    for (int event = 0; event < 3; event++) panels.requestRefresh(1, RefreshReason.BATCH_PROGRESS, true);
                    clock.advance(480);
                    maxPending[0] = Math.max(maxPending[0], panels.hasPending(1) ? 1 : 0);
                    assertEquals(0, clock.pending(), "Metadata must not allocate a per-entry timer");
                    assertEquals(1, renders[0], "Dirty events must not allocate historical embeds");
                }
                requestedAt[0] = TimeUnit.NANOSECONDS.toMillis(clock.nanos());
                panels.requestRefresh(1, RefreshReason.BATCH_COMPLETE);
                clock.advance(1_000);
                assertEquals(List.of(content(List.of()), content(tracks)), displayed);
                assertEquals(java.util.stream.IntStream.range(0, count).boxed().toList(), tracks);
                assertFalse(panels.hasPending(1));
                panels.requestRefresh(1, RefreshReason.BATCH_COMPLETE);
                clock.advance(1_000); // Duplicate completion must not force an identical PATCH.
                assertEquals(2, displayed.size());
                assertEquals(1, panels.metrics(1).unchanged());
                assertEquals(1, maxActive[0]);
                System.out.printf("PANEL_LATEST tracks=%d deliveries=%d coalesced=%d unchanged=%d maxPending=%d maxInFlight=%d finalCorrect=true latencyMeanMs=%.1f latencyMaxMs=%d%n",
                        count, displayed.size(), panels.metrics(1).coalesced(), panels.metrics(1).unchanged(),
                        maxPending[0], maxActive[0], latencies.stream().mapToLong(Long::longValue).average().orElseThrow(),
                        latencies.stream().mapToLong(Long::longValue).max().orElseThrow());
            }
        }
    }

    @Test
    void periodicProgressCanFlushDirtyBatchWithoutWaitingForCompletion() {
        try (var clock = new MusicPanelRefreshCoordinatorTest.ManualScheduler()) {
            var reasonsSeen = new ArrayList<java.util.Set<RefreshReason>>();
            try (var panels = new MusicPanelRefreshCoordinator(clock, (guild, reasons) -> {
                reasonsSeen.add(reasons);
                return CompletableFuture.completedFuture(null);
            }, clock::nanos)) {
                panels.requestRefresh(1, RefreshReason.BATCH_PROGRESS, true);
                assertFalse(panels.hasScheduledWork(1));
                panels.requestRefresh(1, RefreshReason.PERIODIC_REFRESH);
                clock.advance(1_000);
                assertEquals(1, reasonsSeen.size());
                assertTrue(reasonsSeen.getFirst().contains(RefreshReason.PERIODIC_REFRESH));
                assertFalse(panels.hasPending(1));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = RefreshReason.class, names = {"TRACK_START", "PAUSE", "RESUME", "TRACK_END", "LOOP_CHANGED",
            "BUTTON_INTERACTION", "MANUAL", "RECOVERY", "PLAY_ADDED", "QUEUE_CHANGED", "SHUFFLE_CHANGED", "STATE_CHANGED"})
    void foregroundReadsTrueLatestOrderAfterDeletionShuffleAndSkip(RefreshReason operation) {
        try (var clock = new MusicPanelRefreshCoordinatorTest.ManualScheduler()) {
            var tracks = new ArrayList<>(List.of(1, 2, 3));
            var displayed = new ArrayList<String>();
            var flights = new ArrayList<CompletableFuture<Void>>();
            try (var panels = new MusicPanelRefreshCoordinator(clock, (guild, reasons) -> {
                String latest = content(tracks);
                return CompletableFuture.completedFuture(new MusicPanelRefreshCoordinator.Update(10, 100, snapshot(latest), () -> {
                    displayed.add(latest);
                    var flight = new CompletableFuture<Void>();
                    flights.add(flight);
                    return flight;
                }));
            }, clock::nanos)) {
                panels.requestRefresh(1, RefreshReason.TRACK_START);
                clock.advance(1_000);
                tracks.add(4);
                panels.requestRefresh(1, RefreshReason.BATCH_PROGRESS, true);
                tracks.remove(Integer.valueOf(2));
                Collections.reverse(tracks);
                tracks.removeFirst(); // Latest skip/delete need not be a superset of the old queue.
                panels.requestRefresh(1, operation);
                clock.advance(5_000);
                assertEquals(1, displayed.size());
                assertEquals(0, clock.pending());
                flights.getFirst().complete(null);
                clock.advance(1_000);
                assertEquals(List.of(content(List.of(1, 2, 3)), content(tracks)), displayed);
                flights.getLast().complete(null);
                assertFalse(panels.hasPending(1));
            }
        }
    }

    static String content(List<Integer> tracks) {
        return "playing=a queued=" + tracks.size() + " preview=" + tracks.subList(0, Math.min(10, tracks.size()));
    }

    static MusicPanelSnapshot snapshot(String text) {
        return new MusicPanelSnapshot(new EmbedBuilder().setDescription(text).build(), List.of());
    }
}
