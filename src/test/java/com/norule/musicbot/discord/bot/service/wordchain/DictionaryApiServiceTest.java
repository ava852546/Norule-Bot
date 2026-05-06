package com.norule.musicbot.discord.bot.service.wordchain;

import com.norule.musicbot.discord.bot.gateway.wordchain.DictionaryApiGateway;
import com.norule.musicbot.domain.wordchain.DictionaryLookupResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DictionaryApiServiceTest {

    @Test
    void cachesFoundAndNotFoundButNotApiError() {
        FakeGateway gateway = new FakeGateway();
        gateway.set("apple", DictionaryLookupResult.FOUND);
        gateway.set("ghostword", DictionaryLookupResult.NOT_FOUND);
        gateway.set("flaky", DictionaryLookupResult.API_ERROR);
        DictionaryApiService service = new DictionaryApiService(gateway);

        assertEquals(DictionaryLookupResult.FOUND, service.lookupWord("apple").join());
        assertEquals(DictionaryLookupResult.FOUND, service.lookupWord("apple").join());
        assertEquals(1, gateway.calls("apple"));

        assertEquals(DictionaryLookupResult.NOT_FOUND, service.lookupWord("ghostword").join());
        assertEquals(DictionaryLookupResult.NOT_FOUND, service.lookupWord("ghostword").join());
        assertEquals(1, gateway.calls("ghostword"));

        assertEquals(DictionaryLookupResult.API_ERROR, service.lookupWord("flaky").join());
        gateway.set("flaky", DictionaryLookupResult.FOUND);
        assertEquals(DictionaryLookupResult.FOUND, service.lookupWord("flaky").join());
        assertEquals(2, gateway.calls("flaky"));
    }

    private static final class FakeGateway implements DictionaryApiGateway {
        private final Map<String, DictionaryLookupResult> results = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        void set(String word, DictionaryLookupResult result) {
            results.put(word, result);
        }

        int calls(String word) {
            return calls.getOrDefault(word, new AtomicInteger(0)).get();
        }

        @Override
        public CompletableFuture<DictionaryLookupResult> lookup(String word) {
            calls.computeIfAbsent(word, ignored -> new AtomicInteger()).incrementAndGet();
            return CompletableFuture.completedFuture(results.getOrDefault(word, DictionaryLookupResult.NOT_FOUND));
        }
    }
}

