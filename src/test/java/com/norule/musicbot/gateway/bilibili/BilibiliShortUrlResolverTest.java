package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureClassifier;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureStage;
import com.norule.musicbot.domain.music.bilibili.BilibiliRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliShortUrlResolverTest {
    private static final String SHORT_URL = "https://b23.tv/7QpBUhW";
    private static final String VIDEO_URL = "https://www.bilibili.com/video/BV1Na4Q64Eos/?token=secret&p=2";

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void acceptsRedirectsWithoutFetchingFinalPage(int status) {
        AtomicInteger calls = new AtomicInteger();
        BilibiliShortUrlResolver resolver = resolver((uri, head) -> {
            assertEquals(SHORT_URL, uri.toString());
            assertTrue(head);
            calls.incrementAndGet();
            return new BilibiliShortUrlResolver.RedirectResponse(status, VIDEO_URL);
        });
        assertEquals(VIDEO_URL, resolver.resolve(SHORT_URL).toString());
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://b23.tv/code", "https://www.b23.tv/code", "https://share.b23.tv/code"})
    void acceptsShortHostVariantsAndRelativeRedirects(String input) {
        BilibiliShortUrlResolver resolver = resolver((uri, head) ->
                new BilibiliShortUrlResolver.RedirectResponse(302,
                        uri.getPath().equals("/code") ? "/next" : "//m.bilibili.com/video/BV1Na4Q64Eos?p=2"));
        assertTrue(BilibiliShortUrlResolver.isShortUrl(input));
        assertEquals("https://m.bilibili.com/video/BV1Na4Q64Eos?p=2".replace("https:", URI.create(input).getScheme() + ":"),
                resolver.resolve(input).toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 405, 501})
    void fallsBackToGetWhenHeadCannotResolve(int headStatus) {
        List<Boolean> methods = new ArrayList<>();
        BilibiliShortUrlResolver resolver = resolver((uri, head) -> {
            methods.add(head);
            return new BilibiliShortUrlResolver.RedirectResponse(head ? headStatus : 302, head ? null : VIDEO_URL);
        });
        assertEquals(URI.create(VIDEO_URL), resolver.resolve(SHORT_URL));
        assertEquals(List.of(true, false), methods);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/", "http://localhost/", "http://127.0.0.1/",
            "http://192.168.1.1/", "http://10.0.0.1/", "http://172.16.0.1/", "http://0.0.0.0/",
            "http://[::1]/", "http://169.254.169.254/", "http://intranet/", "file:///tmp/video",
            "ftp://b23.tv/video", "https://bilibili.com.evil.test/", "https://evilbilibili.com/",
            "https://b23.tv.evil.test/", "https://b23.tv:8443/video", "https://user:secret@b23.tv/video"})
    void rejectsUnsafeDestinationsBeforeAnotherRequest(String destination) {
        AtomicInteger calls = new AtomicInteger();
        BilibiliShortUrlResolver resolver = resolver((uri, head) -> {
            calls.incrementAndGet();
            return new BilibiliShortUrlResolver.RedirectResponse(302, destination);
        });
        BilibiliRequestException failure = assertThrows(BilibiliRequestException.class, () -> resolver.resolve(SHORT_URL));
        assertEquals(BilibiliFailureStage.SHORT_URL_RESOLVE, failure.stage());
        assertFalse(failure.retryable());
        assertEquals(1, calls.get());
        assertFalse(failure.getMessage().contains(destination));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.bilibili.com/video/BV1Na4Q64Eos", "https://example.com/", "file:///tmp/a",
            "http://127.0.0.1/", "https://b23.tv:9000/a", "https://secret@b23.tv/a", "not a URL"})
    void rejectsInvalidInitialUrlsWithoutIo(String input) {
        BilibiliShortUrlResolver resolver = resolver((uri, head) -> {
            fail("Invalid input must not reach transport");
            return null;
        });
        assertThrows(BilibiliRequestException.class, () -> resolver.resolve(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "10.0.0.1", "172.16.0.1", "192.168.1.1",
            "169.254.1.1", "100.64.0.1", "224.0.0.1", "198.18.0.1", "::1", "::", "fc00::1",
            "fe80::1", "ff02::1", "::ffff:127.0.0.1", "2001:db8::1", "2002:7f00:1::"})
    void rejectsPrivateDnsAnswersIncludingMixedResults(String address) throws Exception {
        InetAddress[] answers = {InetAddress.getByName("8.8.8.8"), InetAddress.getByName(address)};
        BilibiliRequestException failure = assertThrows(BilibiliRequestException.class,
                () -> BilibiliShortUrlResolver.validateAddresses(answers));
        assertTrue(failure.getMessage().endsWith("FORBIDDEN_ADDRESS"));
    }

    @Test
    void permitsPublicDnsAddresses() throws Exception {
        InetAddress[] answers = {InetAddress.getByName("8.8.8.8"), InetAddress.getByName("2606:4700:4700::1111")};
        assertSame(answers, BilibiliShortUrlResolver.validateAddresses(answers));
    }

    @Test
    void detectsLoopIgnoringFragment() {
        BilibiliShortUrlResolver resolver = resolver((uri, head) ->
                new BilibiliShortUrlResolver.RedirectResponse(302, SHORT_URL + "#another"));
        assertReason(resolver, "REDIRECT_LOOP");
    }

    @Test
    void boundsRedirectChain() {
        AtomicInteger calls = new AtomicInteger();
        BilibiliShortUrlResolver resolver = resolver((uri, head) ->
                new BilibiliShortUrlResolver.RedirectResponse(302, "/next" + calls.incrementAndGet()));
        assertReason(resolver, "TOO_MANY_REDIRECTS");
        assertEquals(5, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "http://[", "https://b23.tv/a b", "\r\nInjected: value"})
    void rejectsInvalidLocation(String location) {
        assertReason(resolver((uri, head) -> new BilibiliShortUrlResolver.RedirectResponse(302, location)),
                "INVALID_LOCATION");
    }

    @Test
    void classifiesTimeoutDnsAndNetworkWithoutInferringStatusFromMessages() {
        assertIoFailure(new SocketTimeoutException("412 token=secret"), "TIMEOUT");
        assertIoFailure(new UnknownHostException("412 token=secret"), "DNS_FAILURE");
        assertIoFailure(new IOException("412 token=secret"), "NETWORK_FAILURE");
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 412, 429, 500, 502})
    void preservesActualHttpStatus(int status) {
        BilibiliRequestException failure = assertThrows(BilibiliRequestException.class,
                () -> resolver((uri, head) -> new BilibiliShortUrlResolver.RedirectResponse(status, null)).resolve(SHORT_URL));
        assertEquals(status, failure.httpStatus());
        assertEquals(status == 412, failure.category() == BilibiliFailureCategory.BILIBILI_RISK_CONTROL);
        assertEquals(status >= 500, failure.retryable());
    }

    @Test
    void cacheExpiresAndEvictsLeastRecentlyUsedEntry() {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        BilibiliShortUrlResolver resolver = new BilibiliShortUrlResolver((uri, head) -> {
            calls.incrementAndGet();
            return new BilibiliShortUrlResolver.RedirectResponse(302, VIDEO_URL);
        }, clock, 2);
        resolver.resolve(SHORT_URL);
        resolver.resolve(SHORT_URL + "#fragment");
        assertEquals(1, calls.get());
        resolver.resolve(SHORT_URL + "2");
        resolver.resolve(SHORT_URL); // Make original most recently used.
        resolver.resolve(SHORT_URL + "3");
        resolver.resolve(SHORT_URL + "2");
        assertEquals(4, calls.get());
        clock.now = clock.now.plus(Duration.ofMinutes(30));
        resolver.resolve(SHORT_URL + "2");
        assertEquals(5, calls.get());
    }

    @Test
    void concurrentResolvesShareOneRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BilibiliShortUrlResolver resolver = resolver((uri, head) -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            return new BilibiliShortUrlResolver.RedirectResponse(302, VIDEO_URL);
        });
        var executor = Executors.newFixedThreadPool(5);
        try {
            List<Future<URI>> results = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                results.add(executor.submit(() -> resolver.resolve(SHORT_URL)));
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Instant deadline = Instant.now().plusSeconds(2);
            while (resolver.participantCount() < 5 && Instant.now().isBefore(deadline)) {
                Thread.onSpinWait();
            }
            assertEquals(5, resolver.participantCount());
            release.countDown();
            for (Future<URI> result : results) {
                assertEquals(URI.create(VIDEO_URL), result.get(2, TimeUnit.SECONDS));
            }
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void failedResolutionIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        BilibiliShortUrlResolver resolver = resolver((uri, head) -> {
            if (calls.incrementAndGet() == 1) {
                throw new SocketTimeoutException();
            }
            return new BilibiliShortUrlResolver.RedirectResponse(302, VIDEO_URL);
        });
        assertThrows(BilibiliRequestException.class, () -> resolver.resolve(SHORT_URL));
        assertEquals(URI.create(VIDEO_URL), resolver.resolve(SHORT_URL));
        assertEquals(2, calls.get());
    }

    private void assertIoFailure(IOException io, String reason) {
        BilibiliRequestException failure = assertReason(resolver((uri, head) -> { throw io; }), reason);
        var report = new BilibiliFailureClassifier().classify(failure, BilibiliFailureStage.METADATA);
        assertEquals(BilibiliFailureStage.SHORT_URL_RESOLVE, report.stage());
        assertTrue(report.retryable());
        assertFalse(report.breakerFailure());
        assertEquals(BilibiliFailureCategory.BILIBILI_METADATA_FAILED, report.category());
        assertEquals(0, report.httpStatus());
    }

    private BilibiliRequestException assertReason(BilibiliShortUrlResolver resolver, String reason) {
        BilibiliRequestException failure = assertThrows(BilibiliRequestException.class, () -> resolver.resolve(SHORT_URL));
        assertTrue(failure.getMessage().endsWith(reason), failure.getMessage());
        return failure;
    }

    private BilibiliShortUrlResolver resolver(BilibiliShortUrlResolver.RedirectTransport transport) {
        return new BilibiliShortUrlResolver(transport, Clock.systemUTC(), 1000);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
