package com.norule.musicbot.discord.bot.gateway;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Reproducible admission benchmark, with no network, token, sleeps or wall-clock timing. */
class DiscordRestLoadTest {
    @ParameterizedTest
    @ValueSource(ints = {100, 300, 500})
    void deterministicMultiGuildLoad(int guilds) {
        for (boolean delayed : new boolean[]{false, true}) {
            for (int repetition = 0; repetition < 3; repetition++) {
                run(guilds, delayed, repetition);
            }
        }
    }

    private void run(int guilds, boolean delayed, int repetition) {
        try (var clock = new DiscordBackgroundRestQueueTest.VirtualScheduler();
             var queue = new DiscordBackgroundRestQueue(clock, clock::nanos)) {
            var latencies = new ArrayList<Long>();
            int[] started = {0}, rejected = {0}, failed = {0}, maxPending = {0};
            for (int guild = 0; guild < guilds; guild++) {
                final int id = guild;
                var result = queue.submit("music-panel:" + guild, () -> {
                    started[0]++;
                    var response = new CompletableFuture<Void>();
                    // Two independent JDA buckets hold their request for 10 seconds.
                    clock.schedule(() -> {
                        latencies.add(TimeUnit.NANOSECONDS.toMillis(clock.nanos()));
                        if (delayed && id % 37 == 0) {
                            failed[0]++;
                            response.completeExceptionally(new IllegalStateException("simulated final failure"));
                        } else response.complete(null);
                    }, delayed && id < 2 ? 10_000 : 100, TimeUnit.MILLISECONDS);
                    return response;
                });
                if (result.isCompletedExceptionally()) rejected[0]++;
            }
            maxPending[0] = queue.pendingCount();
            // Fixed 1ms ticks make comparisons independent of the queue's pacing constant.
            for (int tick = 0; tick <= 150_000; tick++) {
                clock.advance(1);
                maxPending[0] = Math.max(maxPending[0], queue.pendingCount());
                if (queue.pendingCount() == 0 && queue.inFlightCount() == 0) break;
            }
            Collections.sort(latencies);
            assertEquals(guilds - rejected[0], latencies.size());
            assertEquals(latencies.size(), started[0]);
            assertTrue(maxPending[0] <= DiscordBackgroundRestQueue.MAX_PENDING);
            long last = latencies.getLast();
            System.out.printf("REST_LOAD guilds=%d delayed=%s repeat=%d completed=%d rejected=%d failed=%d maxPending=%d meanMs=%.1f p95Ms=%d p99Ms=%d lastMs=%d throughput=%.2f%n",
                    guilds, delayed, repetition, latencies.size(), rejected[0], failed[0], maxPending[0],
                    latencies.stream().mapToLong(Long::longValue).average().orElseThrow(),
                    latencies.get((int) Math.ceil(latencies.size() * .95) - 1),
                    latencies.get((int) Math.ceil(latencies.size() * .99) - 1), last,
                    latencies.size() * 1000.0 / last);
        }
    }
}
