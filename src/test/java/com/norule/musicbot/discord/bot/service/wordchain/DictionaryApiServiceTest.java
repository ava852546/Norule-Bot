package com.norule.musicbot.discord.bot.service.wordchain;

import com.norule.musicbot.config.domain.DictionaryCacheConfig;
import com.norule.musicbot.discord.bot.gateway.wordchain.DictionaryApiGateway;
import com.norule.musicbot.discord.bot.gateway.wordchain.FallbackDictionaryApiGateway;
import com.norule.musicbot.domain.wordchain.DictionaryLookupResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DictionaryApiServiceTest {

    @Test
    void foundResultIsCached() {
        FakeGateway gateway = new FakeGateway();
        gateway.set("apple", DictionaryLookupResult.FOUND);
        DictionaryApiService service = new DictionaryApiService(gateway);

        assertEquals(DictionaryLookupResult.FOUND, service.lookupWord("apple").join());
        assertEquals(DictionaryLookupResult.FOUND, service.lookupWord("apple").join());
        assertEquals(1, gateway.calls("apple"));
    }

    @Test
    void notFoundResultIsCached() {
        FakeGateway gateway = new FakeGateway();
        gateway.set("ghostword", DictionaryLookupResult.NOT_FOUND);
        DictionaryApiService service = new DictionaryApiService(gateway);

        assertEquals(DictionaryLookupResult.NOT_FOUND, service.lookupWord("ghostword").join());
        assertEquals(DictionaryLookupResult.NOT_FOUND, service.lookupWord("ghostword").join());
        assertEquals(1, gateway.calls("ghostword"));
    }

    @Test
    void apiErrorUsesTemporaryCacheButIsRetriedAfterExpiry() {
        MutableClock clock = new MutableClock();
        FakeGateway gateway = new FakeGateway();
        gateway.set("flaky", DictionaryLookupResult.API_ERROR);
        DictionaryApiService service = new DictionaryApiService(
                gateway,
                cacheConfig(Duration.ofSeconds(10), 100),
                clock
        );

        assertEquals(DictionaryLookupResult.API_ERROR, service.lookupWord("flaky").join());
        gateway.set("flaky", DictionaryLookupResult.FOUND);
        assertEquals(DictionaryLookupResult.API_ERROR, service.lookupWord("flaky").join());
        assertEquals(1, gateway.calls("flaky"));

        clock.advance(Duration.ofSeconds(11));
        assertEquals(DictionaryLookupResult.FOUND, service.lookupWord("flaky").join());
        assertEquals(2, gateway.calls("flaky"));
    }

    @Test
    void foundAndNotFoundEntriesExpireAtTheirOwnTtl() {
        MutableClock clock = new MutableClock();
        FakeGateway gateway = new FakeGateway();
        gateway.set("apple", DictionaryLookupResult.FOUND);
        gateway.set("missing", DictionaryLookupResult.NOT_FOUND);
        DictionaryApiService service = new DictionaryApiService(
                gateway,
                new DictionaryCacheConfig(
                        true,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(10),
                        100
                ),
                clock
        );

        service.lookupWord("apple").join();
        service.lookupWord("missing").join();
        clock.advance(Duration.ofMillis(1_500));

        assertEquals(DictionaryLookupResult.FOUND, service.lookupWord("apple").join());
        assertEquals(DictionaryLookupResult.NOT_FOUND, service.lookupWord("missing").join());
        assertEquals(1, gateway.calls("apple"));
        assertEquals(2, gateway.calls("missing"));
    }

    @Test
    void oldestEntryIsRemovedWhenCapacityIsExceeded() {
        FakeGateway gateway = new FakeGateway();
        gateway.set("alpha", DictionaryLookupResult.FOUND);
        gateway.set("bravo", DictionaryLookupResult.FOUND);
        gateway.set("charlie", DictionaryLookupResult.FOUND);
        DictionaryApiService service = new DictionaryApiService(
                gateway,
                cacheConfig(Duration.ofSeconds(10), 2)
        );

        service.lookupWord("alpha").join();
        service.lookupWord("bravo").join();
        service.lookupWord("charlie").join();
        service.lookupWord("alpha").join();

        assertEquals(2, gateway.calls("alpha"));
        assertEquals(1, gateway.calls("bravo"));
        assertEquals(1, gateway.calls("charlie"));
    }

    @Test
    void concurrentLookupsForSameWordShareOneFuture() {
        ControlledGateway gateway = new ControlledGateway();
        DictionaryApiService service = new DictionaryApiService(gateway);

        CompletableFuture<DictionaryLookupResult> first = service.lookupWord("apple");
        CompletableFuture<DictionaryLookupResult> second = service.lookupWord(" APPLE ");
        CompletableFuture<DictionaryLookupResult> third = service.lookupWord("apple");

        assertSame(first, second);
        assertSame(first, third);
        assertEquals(1, gateway.calls.get());

        gateway.pending.complete(DictionaryLookupResult.FOUND);
        assertEquals(DictionaryLookupResult.FOUND, first.join());
        assertEquals(DictionaryLookupResult.FOUND, second.join());
        assertEquals(DictionaryLookupResult.FOUND, third.join());
        assertEquals(DictionaryLookupResult.FOUND, service.lookupWord("apple").join());
        assertEquals(1, gateway.calls.get());
    }

    @Test
    void fallbackFoundWordIsCachedAsValid() {
        FakeGateway primary = new FakeGateway();
        FakeGateway fallback = new FakeGateway();
        primary.set("validfallback", DictionaryLookupResult.NOT_FOUND);
        fallback.set("validfallback", DictionaryLookupResult.FOUND);
        DictionaryApiService service = service(primary, fallback);

        assertEquals(DictionaryLookupResult.FOUND, service.lookupWord("validfallback").join());
        assertEquals(DictionaryLookupResult.FOUND, service.lookupWord("validfallback").join());
        assertEquals(1, primary.calls("validfallback"));
        assertEquals(1, fallback.calls("validfallback"));
    }

    @Test
    void bothProvidersNotFoundCachesInvalid() {
        FakeGateway primary = new FakeGateway();
        FakeGateway fallback = new FakeGateway();
        primary.set("missing", DictionaryLookupResult.NOT_FOUND);
        fallback.set("missing", DictionaryLookupResult.NOT_FOUND);
        DictionaryApiService service = service(primary, fallback);

        assertEquals(DictionaryLookupResult.NOT_FOUND, service.lookupWord("missing").join());
        assertEquals(DictionaryLookupResult.NOT_FOUND, service.lookupWord("missing").join());
        assertEquals(1, primary.calls("missing"));
        assertEquals(1, fallback.calls("missing"));
    }

    private static DictionaryApiService service(FakeGateway primary, FakeGateway fallback) {
        return new DictionaryApiService(new FallbackDictionaryApiGateway(primary, fallback, true));
    }

    private static DictionaryCacheConfig cacheConfig(
            Duration temporaryFailureTtl,
            int maxEntries
    ) {
        return new DictionaryCacheConfig(
                true,
                Duration.ofHours(24),
                Duration.ofMinutes(30),
                temporaryFailureTtl,
                maxEntries
        );
    }

    private static final class FakeGateway implements DictionaryApiGateway {
        private final Map<String, DictionaryLookupResult> results = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        void set(String word, DictionaryLookupResult result) {
            results.put(word, result);
        }

        int calls(String word) {
            return calls.getOrDefault(word, new AtomicInteger()).get();
        }

        @Override
        public CompletableFuture<DictionaryLookupResult> lookup(String word) {
            calls.computeIfAbsent(word, ignored -> new AtomicInteger()).incrementAndGet();
            return CompletableFuture.completedFuture(
                    results.getOrDefault(word, DictionaryLookupResult.NOT_FOUND)
            );
        }
    }

    private static final class ControlledGateway implements DictionaryApiGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private final CompletableFuture<DictionaryLookupResult> pending = new CompletableFuture<>();

        @Override
        public CompletableFuture<DictionaryLookupResult> lookup(String word) {
            calls.incrementAndGet();
            return pending;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
