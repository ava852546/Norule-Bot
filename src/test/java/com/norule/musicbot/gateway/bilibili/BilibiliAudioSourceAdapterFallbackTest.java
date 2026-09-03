package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import dev.lavalink.bilibili.BilibiliAudioTrack;
import org.apache.http.client.methods.HttpGet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliAudioSourceAdapterFallbackTest {
    private static final String VIDEO_URL = "https://www.bilibili.com/video/BV1Na4Q64Eos/";

    @Test
    void recoversPrimary412WithNativePagelistTrackWithoutBreakerFailure() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        BilibiliAudioSourceAdapter adapter = adapter(
                config(true, 3),
                (manager, reference) -> {
                    primaryCalls.incrementAndGet();
                    throw primary412();
                },
                resolver(fallbackCalls, 200, singlePageJson())
        );
        try {
            AudioItem item = adapter.loadItem(null, new AudioReference(VIDEO_URL, null));

            BilibiliAudioTrack track = assertInstanceOf(BilibiliAudioTrack.class, item);
            assertEquals(41_414_165_124L, track.getCid());
            assertEquals("Part 1", track.getInfo().title);
            assertEquals("BV1Na4Q64Eos", track.getInfo().identifier);
            assertEquals(1, primaryCalls.get());
            assertEquals(1, fallbackCalls.get());
            assertEquals(0, adapter.breakerFailureCount());
            assertEquals("CLOSED", adapter.breakerState());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void cachesFullFallbackPagelistAndSelectsExplicitPages() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        BilibiliAudioSourceAdapter adapter = adapter(
                config(true, 3),
                (manager, reference) -> {
                    primaryCalls.incrementAndGet();
                    throw primary412();
                },
                resolver(fallbackCalls, 200, BilibiliPagelistMetadataResolverTest.multiPageJson())
        );
        try {
            BilibiliAudioTrack second = assertInstanceOf(
                    BilibiliAudioTrack.class,
                    adapter.loadItem(null, new AudioReference(VIDEO_URL + "?p=2", null))
            );
            BilibiliAudioTrack first = assertInstanceOf(
                    BilibiliAudioTrack.class,
                    adapter.loadItem(null, new AudioReference(VIDEO_URL + "?p=1&spm_id_from=test", null))
            );
            AudioPlaylist playlist = assertInstanceOf(
                    AudioPlaylist.class,
                    adapter.loadItem(null, new AudioReference(VIDEO_URL + "?spm_id_from=test", null))
            );

            assertEquals(222L, second.getCid());
            assertEquals(111L, first.getCid());
            assertEquals(111L, assertInstanceOf(BilibiliAudioTrack.class, playlist.getSelectedTrack()).getCid());
            assertEquals(1, primaryCalls.get());
            assertEquals(1, fallbackCalls.get());
            assertEquals(2, adapter.cacheStatistics().hits());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void classifiesPrimaryAndFallback412AsFinalRiskControlFailure() {
        BilibiliAudioSourceAdapter adapter = adapter(
                config(false, 1),
                (manager, reference) -> {
                    throw primary412();
                },
                resolver(new AtomicInteger(), 412, "")
        );
        try {
            BilibiliRequestException failure = assertThrows(
                    BilibiliRequestException.class,
                    () -> adapter.loadItem(null, new AudioReference(VIDEO_URL, null))
            );

            assertEquals(BilibiliFailureCategory.BILIBILI_RISK_CONTROL, failure.category());
            assertEquals(412, failure.httpStatus());
            assertEquals(1, adapter.breakerFailureCount());
            assertEquals("OPEN", adapter.breakerState());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void apiCodeFailureDoesNotCreateTrackOrIncrementBreaker() {
        BilibiliAudioSourceAdapter adapter = adapter(
                config(false, 1),
                (manager, reference) -> {
                    throw primary412();
                },
                resolver(new AtomicInteger(), 200, "{\"code\":-404,\"data\":null}")
        );
        try {
            BilibiliRequestException failure = assertThrows(
                    BilibiliRequestException.class,
                    () -> adapter.loadItem(null, new AudioReference(VIDEO_URL, null))
            );

            assertEquals(BilibiliFailureCategory.BILIBILI_METADATA_FAILED, failure.category());
            assertEquals(0, adapter.breakerFailureCount());
            assertEquals("CLOSED", adapter.breakerState());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void repeatedRecovered412ResponsesNeverOpenBreaker() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        BilibiliAudioSourceAdapter adapter = adapter(
                config(false, 3),
                (manager, reference) -> {
                    primaryCalls.incrementAndGet();
                    throw primary412();
                },
                resolver(fallbackCalls, 200, singlePageJson())
        );
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                assertInstanceOf(
                        BilibiliAudioTrack.class,
                        adapter.loadItem(null, new AudioReference(VIDEO_URL, null))
                );
            }

            assertEquals(10, primaryCalls.get());
            assertEquals(10, fallbackCalls.get());
            assertEquals(0, adapter.breakerFailureCount());
            assertEquals("CLOSED", adapter.breakerState());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void primarySuccessDoesNotCallPagelist() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        BilibiliAudioSourceAdapter adapter = adapter(
                config(false, 3),
                (manager, reference) -> AudioReference.NO_TRACK,
                resolver(fallbackCalls, 200, singlePageJson())
        );
        try {
            AudioItem item = adapter.loadItem(null, new AudioReference(VIDEO_URL, null));

            assertEquals(AudioReference.NO_TRACK, item);
            assertEquals(0, fallbackCalls.get());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void primary403AndMalformedVideoUrlDoNotTriggerPagelist() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        BilibiliAudioSourceAdapter adapter = adapter(
                config(false, 3),
                (manager, reference) -> {
                    throw primaryHttpFailure(403);
                },
                resolver(fallbackCalls, 200, singlePageJson())
        );
        try {
            assertThrows(
                    FriendlyException.class,
                    () -> adapter.loadItem(null, new AudioReference(VIDEO_URL, null))
            );
            assertThrows(
                    FriendlyException.class,
                    () -> adapter.loadItem(
                            null,
                            new AudioReference("https://www.bilibili.com/video/not-a-bvid/", null)
                    )
            );
            assertEquals(0, fallbackCalls.get());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void defersOnlyViewAndPagelistHttpFailuresForFinalOutcomeAccounting() {
        BilibiliAudioSourceAdapter adapter = adapter(
                config(false, 3),
                (manager, reference) -> AudioReference.NO_TRACK,
                resolver(new AtomicInteger(), 200, singlePageJson())
        );
        try {
            assertTrue(adapter.defersMetadataFailure(
                    new HttpGet("https://api.bilibili.com/x/web-interface/view?bvid=BV1Na4Q64Eos")
            ));
            assertTrue(adapter.defersMetadataFailure(
                    new HttpGet("https://api.bilibili.com/x/player/pagelist?bvid=BV1Na4Q64Eos")
            ));
            assertFalse(adapter.defersMetadataFailure(
                    new HttpGet("https://api.bilibili.com/x/player/playurl?bvid=BV1Na4Q64Eos&cid=1")
            ));
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void concurrentLoadsShareOnePrimaryAndOneFallbackRequest() throws Exception {
        int participants = 10;
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger fallbackCalls = new AtomicInteger();
        CountDownLatch primaryEntered = new CountDownLatch(1);
        CountDownLatch releasePrimary = new CountDownLatch(1);
        BilibiliAudioSourceAdapter adapter = adapter(
                config(true, 3),
                (manager, reference) -> {
                    primaryCalls.incrementAndGet();
                    primaryEntered.countDown();
                    await(releasePrimary);
                    throw primary412();
                },
                resolver(fallbackCalls, 200, singlePageJson())
        );
        ExecutorService executor = Executors.newFixedThreadPool(participants);
        try {
            List<Future<AudioItem>> futures = new ArrayList<>();
            for (int index = 0; index < participants; index++) {
                futures.add(executor.submit(() -> adapter.loadItem(
                        null,
                        new AudioReference(VIDEO_URL + "?p=1", null)
                )));
            }
            assertTrue(primaryEntered.await(2, TimeUnit.SECONDS));
            awaitParticipants(adapter, participants);
            releasePrimary.countDown();

            for (Future<AudioItem> future : futures) {
                assertInstanceOf(BilibiliAudioTrack.class, future.get(2, TimeUnit.SECONDS));
            }
            assertEquals(1, primaryCalls.get());
            assertEquals(1, fallbackCalls.get());
        } finally {
            releasePrimary.countDown();
            executor.shutdownNow();
            adapter.shutdown();
        }
    }

    private BilibiliAudioSourceAdapter adapter(MusicConfig.Bilibili config,
                                                BilibiliAudioSourceAdapter.PrimaryMetadataLoader primary,
                                                BilibiliPagelistMetadataResolver resolver) {
        return new BilibiliAudioSourceAdapter(config, primary, resolver);
    }

    private BilibiliPagelistMetadataResolver resolver(AtomicInteger calls, int status, String body) {
        return new BilibiliPagelistMetadataResolver(uri -> {
            calls.incrementAndGet();
            return new BilibiliPagelistMetadataResolver.PagelistResponse(status, body);
        });
    }

    private MusicConfig.Bilibili config(boolean cacheEnabled, int breakerThreshold) {
        return new MusicConfig.Bilibili(
                true,
                "",
                new MusicConfig.Bilibili.MetadataCache(cacheEnabled, 12, 1000),
                new MusicConfig.Bilibili.RateLimit(false, 1, 3),
                new MusicConfig.Bilibili.CircuitBreaker(true, breakerThreshold, 60, 300)
        );
    }

    private FriendlyException primary412() {
        return primaryHttpFailure(412);
    }

    private FriendlyException primaryHttpFailure(int status) {
        return new FriendlyException(
                "Something went wrong when looking up the track",
                FriendlyException.Severity.SUSPICIOUS,
                new IOException("Invalid status code for bilibili video metadata: " + status)
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void awaitParticipants(BilibiliAudioSourceAdapter adapter, int expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
        while (adapter.singleFlightParticipantCount() < expected && Instant.now().isBefore(deadline)) {
            Thread.onSpinWait();
        }
        assertEquals(expected, adapter.singleFlightParticipantCount());
    }

    private String singlePageJson() {
        return """
                {
                  "code":0,
                  "data":[{
                    "cid":41414165124,
                    "page":1,
                    "part":"Part 1",
                    "duration":1474,
                    "first_frame":"http://example.test/frame.jpg"
                  }]
                }
                """;
    }
}
