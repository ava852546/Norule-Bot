package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureClassifier;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureStage;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import dev.lavalink.bilibili.BilibiliAudioTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliAudioSourceAdapterShortUrlTest {
    private static final String VIDEO_URL = "https://www.bilibili.com/video/BV1Na4Q64Eos/";
    private static final String SHORT_URL = "https://b23.tv/7QpBUhW";

    @ParameterizedTest
    @ValueSource(strings = {VIDEO_URL, "https://m.bilibili.com/video/BV1Na4Q64Eos/", VIDEO_URL + "?p=2"})
    void directVideoUrlsKeepOriginalReferenceAndSkipResolver(String url) {
        AtomicInteger calls = new AtomicInteger();
        var adapter = adapter((manager, reference) -> {
            assertEquals(url, reference.identifier);
            calls.incrementAndGet();
            return AudioReference.NO_TRACK;
        }, (uri, head) -> { fail("Direct URLs must not resolve redirects"); return null; });
        try {
            assertSame(AudioReference.NO_TRACK, adapter.loadItem(null, new AudioReference(url, null)));
            assertEquals(1, calls.get());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void shortUrlUsesCanonicalReferenceAndPreservesTitle() {
        var adapter = adapter((manager, reference) -> {
            assertEquals(VIDEO_URL + "?p=2", reference.identifier);
            assertEquals("title", reference.title);
            return AudioReference.NO_TRACK;
        }, (uri, head) -> new BilibiliShortUrlResolver.RedirectResponse(302, VIDEO_URL + "?token=secret&p=2"));
        try {
            assertSame(AudioReference.NO_TRACK, adapter.loadItem(null, new AudioReference(SHORT_URL, "title")));
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void resolved412UsesExistingPagelistAndBothCachesWithoutExhaustingApiBurst() {
        AtomicInteger redirectCalls = new AtomicInteger();
        AtomicInteger primaryCalls = new AtomicInteger();
        var adapter = adapter((manager, reference) -> {
            primaryCalls.incrementAndGet();
            assertEquals(VIDEO_URL + "?p=2", reference.identifier);
            throw primary412();
        }, (uri, head) -> {
            redirectCalls.incrementAndGet();
            return new BilibiliShortUrlResolver.RedirectResponse(302, VIDEO_URL + "?token=secret&p=2");
        });
        try {
            BilibiliAudioTrack second = assertInstanceOf(BilibiliAudioTrack.class,
                    adapter.loadItem(null, new AudioReference(SHORT_URL, null)));
            assertEquals(222L, second.getCid());
            assertEquals("BV1Na4Q64Eos", second.getInfo().identifier);
            assertEquals(222L, assertInstanceOf(BilibiliAudioTrack.class,
                    adapter.loadItem(null, new AudioReference(SHORT_URL, null))).getCid());
            AudioPlaylist playlist = assertInstanceOf(AudioPlaylist.class,
                    adapter.loadItem(null, new AudioReference(VIDEO_URL, null)));
            assertEquals(3, playlist.getTracks().size());
            assertEquals(1, redirectCalls.get());
            assertEquals(1, primaryCalls.get());
            assertEquals(0, adapter.breakerFailureCount());
            assertEquals("CLOSED", adapter.breakerState());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void resolvedUrlWithoutPageRetainsPlaylistSelection() {
        var adapter = adapter((manager, reference) -> {
            assertEquals(VIDEO_URL, reference.identifier);
            throw primary412();
        }, (uri, head) -> new BilibiliShortUrlResolver.RedirectResponse(302, VIDEO_URL));
        try {
            AudioPlaylist playlist = assertInstanceOf(AudioPlaylist.class,
                    adapter.loadItem(null, new AudioReference(SHORT_URL, null)));
            assertEquals(111L, assertInstanceOf(BilibiliAudioTrack.class, playlist.getSelectedTrack()).getCid());
        } finally {
            adapter.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.bilibili.com/", "https://example.com/", "http://127.0.0.1/"})
    void malformedDestinationsNeverCallMetadataOrOpenBreaker(String destination) {
        var adapter = adapter((manager, reference) -> { fail("No metadata before BVID"); return null; },
                (uri, head) -> new BilibiliShortUrlResolver.RedirectResponse(302, destination));
        try {
            for (int index = 0; index < 3; index++) {
                BilibiliRequestException failure = assertThrows(BilibiliRequestException.class,
                        () -> adapter.loadItem(null, new AudioReference(SHORT_URL, null)));
                assertEquals(BilibiliFailureStage.SHORT_URL_RESOLVE, failure.stage());
                assertEquals(BilibiliFailureCategory.BILIBILI_METADATA_FAILED, failure.category());
                if (destination.equals("https://www.bilibili.com/")) {
                    assertTrue(failure.getMessage().endsWith("MISSING_BVID"));
                }
            }
            assertEquals(0, adapter.breakerFailureCount());
            assertEquals("CLOSED", adapter.breakerState());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void shortUrlTimeoutDoesNotEnterMetadataOrFallback() {
        var adapter = adapter((manager, reference) -> { fail("No metadata on timeout"); return null; },
                (uri, head) -> { throw new SocketTimeoutException("412"); });
        try {
            BilibiliRequestException failure = assertThrows(BilibiliRequestException.class,
                    () -> adapter.loadItem(null, new AudioReference(SHORT_URL, null)));
            assertEquals(BilibiliFailureStage.SHORT_URL_RESOLVE, failure.stage());
            assertEquals(0, failure.httpStatus());
            assertEquals(0, adapter.breakerFailureCount());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void metadataFailureCarriesResolvedBvidThroughFriendlyException() {
        var adapter = adapter((manager, reference) -> {
            throw new FriendlyException("metadata failed", FriendlyException.Severity.SUSPICIOUS,
                    new IOException("Invalid status code for bilibili video metadata: 403"));
        }, (uri, head) -> new BilibiliShortUrlResolver.RedirectResponse(302, VIDEO_URL));
        try {
            BilibiliRequestException failure = assertThrows(BilibiliRequestException.class,
                    () -> adapter.loadItem(null, new AudioReference(SHORT_URL, null)));
            assertEquals("BV1Na4Q64Eos", failure.videoId());
            var report = new BilibiliFailureClassifier().classify(
                    new FriendlyException("load failed", FriendlyException.Severity.SUSPICIOUS, failure),
                    BilibiliFailureStage.METADATA);
            assertEquals(403, report.httpStatus());
            assertEquals(BilibiliFailureStage.METADATA, report.stage());
        } finally {
            adapter.shutdown();
        }
    }

    @Test
    void differentShortCodesShareBvidSingleFlightWithDirectUrl() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger primaryCalls = new AtomicInteger();
        var adapter = adapter((manager, reference) -> {
            primaryCalls.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            throw primary412();
        }, (uri, head) -> new BilibiliShortUrlResolver.RedirectResponse(302, VIDEO_URL));
        var executor = Executors.newFixedThreadPool(3);
        try {
            var first = executor.submit(() -> adapter.loadItem(null, new AudioReference(SHORT_URL, null)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> adapter.loadItem(null, new AudioReference(SHORT_URL + "2", null)));
            var direct = executor.submit(() -> adapter.loadItem(null, new AudioReference(VIDEO_URL, null)));
            Instant deadline = Instant.now().plusSeconds(2);
            while (adapter.singleFlightParticipantCount() < 3 && Instant.now().isBefore(deadline)) {
                Thread.onSpinWait();
            }
            assertEquals(3, adapter.singleFlightParticipantCount());
            release.countDown();
            AudioItem one = first.get(2, TimeUnit.SECONDS);
            AudioItem two = second.get(2, TimeUnit.SECONDS);
            assertInstanceOf(AudioPlaylist.class, one);
            assertInstanceOf(AudioPlaylist.class, two);
            assertNotSame(one, two);
            assertInstanceOf(AudioPlaylist.class, direct.get(2, TimeUnit.SECONDS));
            assertEquals(1, primaryCalls.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
            adapter.shutdown();
        }
    }

    private BilibiliAudioSourceAdapter adapter(BilibiliAudioSourceAdapter.PrimaryMetadataLoader primary,
                                               BilibiliShortUrlResolver.RedirectTransport redirects) {
        MusicConfig.Bilibili config = new MusicConfig.Bilibili(true, "",
                new MusicConfig.Bilibili.MetadataCache(true, 12, 1000),
                new MusicConfig.Bilibili.RateLimit(true, 1, 2),
                new MusicConfig.Bilibili.CircuitBreaker(true, 1, 60, 300));
        return new BilibiliAudioSourceAdapter(config, primary,
                new BilibiliPagelistMetadataResolver(uri -> new BilibiliPagelistMetadataResolver.PagelistResponse(
                        200, BilibiliPagelistMetadataResolverTest.multiPageJson())),
                new BilibiliShortUrlResolver(redirects, Clock.systemUTC(), 1000));
    }

    private FriendlyException primary412() {
        return new FriendlyException("metadata failed", FriendlyException.Severity.SUSPICIOUS,
                new IOException("Invalid status code for bilibili video metadata: 412"));
    }
}
