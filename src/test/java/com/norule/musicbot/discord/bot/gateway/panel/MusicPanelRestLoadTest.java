package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.discord.bot.gateway.DiscordBackgroundRestQueue;
import net.dv8tion.jda.api.EmbedBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real coordinator + admission queue; synthetic REST/player lifecycle, never live Discord. */
class MusicPanelRestLoadTest {
    @ParameterizedTest
    @ValueSource(ints = {100, 300, 500})
    void sustainedMixedModesAndForegroundBursts(int guilds) {
        for (int repetition = 0; repetition < 3; repetition++) run(guilds, repetition);
    }

    private void run(int guilds, int repetition) {
        try (var clock = new MusicPanelRefreshCoordinatorTest.ManualScheduler();
             var background = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            var owner = new AtomicReference<MusicPanelRefreshCoordinator>();
            long[] lastSuccess = new long[guilds], versions = new long[guilds], messageIds = new long[guilds];
            int[] active = new int[guilds], deliveries = new int[guilds], attempted = {0}, failed = {0}, rebuilt = {0};
            long[] finalVersion = new long[guilds];
            var intervals = new ArrayList<Long>();
            try (var coordinator = new MusicPanelRefreshCoordinator(clock, (guildId, reasons) -> {
                int id = Math.toIntExact(guildId);
                boolean periodic = reasons.equals(Set.of(RefreshReason.PERIODIC_REFRESH));
                boolean replace = id % 2 == 1 && reasons.contains(RefreshReason.PLAY_ADDED);
                if (replace) messageIds[id]++;
                long message = messageIds[id];
                var prepared = snapshot(versions[id], clock.nanos());
                return CompletableFuture.completedFuture(new MusicPanelRefreshCoordinator.Update(id + 1000, message,
                        prepared, () -> {
                    java.util.function.Supplier<CompletableFuture<Void>> send = () -> {
                        assertEquals(0, active[id], "Conflicting lifecycle work for guild " + id);
                        active[id]++;
                        attempted[0]++;
                        var completion = new CompletableFuture<Void>();
                        // As in sendLatestBackground, render again after admission, not at enqueue time.
                        long latestVersion = versions[id];
                        var latest = snapshot(latestVersion, clock.nanos());
                        owner.get().submitted(id);
                        int attempt = attempted[0];
                        clock.schedule(() -> {
                            active[id]--;
                            if (attempt % 97 == 0) {
                                failed[0]++;
                                completion.completeExceptionally(new IllegalStateException("simulated REST timeout"));
                                return;
                            }
                            long now = TimeUnit.NANOSECONDS.toMillis(clock.nanos());
                            if (deliveries[id] > 0) intervals.add(now - lastSuccess[id]);
                            deliveries[id]++;
                            lastSuccess[id] = now;
                            finalVersion[id] = latestVersion;
                            if (replace) rebuilt[0]++;
                            owner.get().recordCreated(id, new MusicPanelRefreshCoordinator.Update(id + 1000, message, latest, null));
                            completion.complete(null);
                        }, id < 2 && clock.nanos() < TimeUnit.SECONDS.toNanos(20) ? 10_000 : replace ? 300 : 100,
                                TimeUnit.MILLISECONDS);
                        return completion;
                    };
                    return periodic ? background.submit("music-panel:" + id, send) : send.get();
                }));
            }, clock::nanos)) {
                owner.set(coordinator);
                int maximumPending = 0, maximumTasks = 0;
                for (int tick = 0; tick < 120_000; tick += 5) {
                    clock.advance(5);
                    if (tick == 60_000) background.pause(2_000);
                    if (tick % 1_000 == 0) {
                        long now = TimeUnit.NANOSECONDS.toMillis(clock.nanos());
                        for (int id = 0; id < guilds; id++) {
                            if (!coordinator.hasPending(id) && now - lastSuccess[id] >= 8_000) {
                                coordinator.requestRefresh(id, RefreshReason.PERIODIC_REFRESH);
                            }
                        }
                    }
                    // Ten hot guilds receive bursts of ten successful play events, every two seconds.
                    if (tick % 2_000 == 0) {
                        for (int id = 0; id < 10; id++) {
                            versions[id]++;
                            background.cancelPending("music-panel:" + id);
                            for (int burst = 0; burst < 10; burst++) coordinator.requestRefresh(id, RefreshReason.PLAY_ADDED);
                        }
                    }
                    maximumPending = Math.max(maximumPending, background.pendingCount());
                    maximumTasks = Math.max(maximumTasks, clock.pending());
                }
                // A final user event must converge, including after failed background work.
                for (int id = 0; id < guilds; id++) {
                    background.cancelPending("music-panel:" + id);
                    coordinator.requestRefresh(id, RefreshReason.STATE_CHANGED);
                }
                for (int tick = 0; tick < 30_000; tick += 5) clock.advance(5);
                for (int id = 0; id < guilds; id++) {
                    assertEquals(0, active[id]);
                    assertFalse(coordinator.hasPending(id));
                    assertTrue(deliveries[id] > 0, "Starved guild " + id);
                    assertEquals(versions[id], finalVersion[id]);
                }
                assertEquals(0, background.pendingCount());
                assertEquals(0, background.inFlightCount());
                assertTrue(maximumPending <= guilds);
                assertTrue(maximumTasks <= guilds + DiscordBackgroundRestQueue.MAX_IN_FLIGHT + 1);
                intervals.sort(Long::compare);
                System.out.printf("PANEL_LOAD guilds=%d repeat=%d attempts=%d failed=%d legacyRebuilt=%d maxPending=%d maxScheduled=%d intervalMeanMs=%.1f intervalP95Ms=%d intervalP99Ms=%d%n",
                        guilds, repetition, attempted[0], failed[0], rebuilt[0], maximumPending, maximumTasks,
                        intervals.stream().mapToLong(Long::longValue).average().orElseThrow(),
                        intervals.get((int) Math.ceil(intervals.size() * .95) - 1),
                        intervals.get((int) Math.ceil(intervals.size() * .99) - 1));
            }
        }
    }

    private static MusicPanelSnapshot snapshot(long version, long nanos) {
        return new MusicPanelSnapshot(new EmbedBuilder().setDescription("version=" + version + " position="
                + TimeUnit.NANOSECONDS.toSeconds(nanos)).build(), List.of());
    }
}
