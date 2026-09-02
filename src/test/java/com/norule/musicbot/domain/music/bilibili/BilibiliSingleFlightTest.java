package com.norule.musicbot.domain.music.bilibili;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliSingleFlightTest {
    @Test
    void tenConcurrentRequestsShareOneResolution() throws Exception {
        BilibiliSingleFlight<String> singleFlight = new BilibiliSingleFlight<>();
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch ready = new CountDownLatch(10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch releaseResolution = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 10; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return singleFlight.execute("BV1Na4Q64Eos", Duration.ofSeconds(2), () -> {
                        resolutions.incrementAndGet();
                        assertTrue(releaseResolution.await(2, TimeUnit.SECONDS));
                        return "metadata";
                    });
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            awaitParticipants(singleFlight, 10);
            releaseResolution.countDown();

            for (Future<String> future : futures) {
                assertEquals("metadata", future.get(2, TimeUnit.SECONDS));
            }
            assertEquals(1, resolutions.get());
            assertEquals(0, singleFlight.inFlightCount());
        } finally {
            releaseResolution.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void exceptionalResolutionIsRemoved() {
        BilibiliSingleFlight<String> singleFlight = new BilibiliSingleFlight<>();

        assertThrows(IOException.class, () -> singleFlight.execute(
                "BV1Na4Q64Eos",
                Duration.ofSeconds(1),
                () -> { throw new IOException("metadata failed"); }
        ));

        assertEquals(0, singleFlight.inFlightCount());
    }

    @Test
    void timedOutFollowerRemovesStaleInFlightEntry() throws Exception {
        BilibiliSingleFlight<String> singleFlight = new BilibiliSingleFlight<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        try {
            Future<String> owner = executor.submit(() -> singleFlight.execute(
                    "BV1Na4Q64Eos",
                    Duration.ofSeconds(2),
                    () -> {
                        ownerStarted.countDown();
                        assertTrue(releaseOwner.await(2, TimeUnit.SECONDS));
                        return "metadata";
                    }
            ));
            assertTrue(ownerStarted.await(2, TimeUnit.SECONDS));

            assertThrows(TimeoutException.class, () -> singleFlight.execute(
                    "BV1Na4Q64Eos",
                    Duration.ofMillis(10),
                    () -> "unexpected"
            ));
            assertEquals(0, singleFlight.inFlightCount());

            releaseOwner.countDown();
            assertEquals("metadata", owner.get(2, TimeUnit.SECONDS));
        } finally {
            releaseOwner.countDown();
            executor.shutdownNow();
        }
    }

    private void awaitParticipants(BilibiliSingleFlight<?> singleFlight, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (singleFlight.activeParticipantCount() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, singleFlight.activeParticipantCount());
    }
}
