package com.norule.musicbot.discord.bot.service.wordchain;

import com.norule.musicbot.discord.bot.gateway.wordchain.DictionaryApiGateway;
import com.norule.musicbot.domain.wordchain.DictionaryLookupResult;
import com.norule.musicbot.domain.wordchain.WordChainLeaderboardEntry;
import com.norule.musicbot.domain.wordchain.WordChainPlayerStatsSnapshot;
import com.norule.musicbot.domain.wordchain.WordChainProcessResult;
import com.norule.musicbot.domain.wordchain.WordChainStatusSnapshot;
import com.norule.musicbot.domain.wordchain.WordChainValidationResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordChainServiceTest {

    @Test
    void setChannelStatusAndPersistenceWork() throws Exception {
        Path dir = Files.createTempDirectory("wordchain-test");
        FakeGateway gateway = new FakeGateway();
        WordChainService service = new WordChainService(
                new WordChainStateRepository(dir),
                new DictionaryApiService(gateway)
        );

        WordChainStatusSnapshot status = service.setChannel(1L, 100L).join();
        assertTrue(status.enabled());
        assertEquals(100L, status.channelId());
        assertEquals(0, status.chainCount());
        assertNull(status.nextRequiredStartLetter());

        WordChainService reloaded = new WordChainService(
                new WordChainStateRepository(dir),
                new DictionaryApiService(gateway)
        );
        WordChainStatusSnapshot reloadedStatus = reloaded.status(1L).join();
        assertTrue(reloadedStatus.enabled());
        assertEquals(100L, reloadedStatus.channelId());
        assertEquals(0, reloadedStatus.chainCount());
    }

    @Test
    void successfulWordUpdatesStateAndNextLetter() throws Exception {
        Path dir = Files.createTempDirectory("wordchain-success");
        FakeGateway gateway = new FakeGateway();
        gateway.set("apple", DictionaryLookupResult.FOUND, 0);
        WordChainService service = new WordChainService(
                new WordChainStateRepository(dir),
                new DictionaryApiService(gateway)
        );
        service.setChannel(9L, 200L).join();

        WordChainProcessResult result = service.processMessage(9L, 200L, "  Apple ").join();
        assertEquals(WordChainValidationResult.OK, result.result());
        assertEquals('e', result.nextRequiredStartLetter());
        assertEquals(1, result.chainCount());

        WordChainStatusSnapshot status = service.status(9L).join();
        assertEquals("apple", status.lastWord());
        assertEquals(1, status.chainCount());
        assertEquals(1, status.usedWordCount());
        assertEquals('e', status.nextRequiredStartLetter());
    }

    @Test
    void validationResultsCovered() throws Exception {
        Path dir = Files.createTempDirectory("wordchain-validation");
        FakeGateway gateway = new FakeGateway();
        gateway.set("apple", DictionaryLookupResult.FOUND, 0);
        gateway.set("eagle", DictionaryLookupResult.FOUND, 0);
        gateway.set("elephantx", DictionaryLookupResult.NOT_FOUND, 0);
        gateway.set("errorapi", DictionaryLookupResult.API_ERROR, 0);
        WordChainService service = new WordChainService(
                new WordChainStateRepository(dir),
                new DictionaryApiService(gateway)
        );
        service.setChannel(7L, 77L).join();

        assertEquals(WordChainValidationResult.EMPTY, service.processMessage(7L, 77L, "   ").join().result());
        assertEquals(WordChainValidationResult.NOT_SINGLE_WORD, service.processMessage(7L, 77L, "two words").join().result());
        assertEquals(WordChainValidationResult.NOT_ENGLISH, service.processMessage(7L, 77L, "abc123").join().result());
        assertEquals(WordChainValidationResult.OK, service.processMessage(7L, 77L, "apple").join().result());
        assertEquals(WordChainValidationResult.WORD_USED, service.processMessage(7L, 77L, "apple").join().result());
        assertEquals(WordChainValidationResult.WRONG_START_LETTER, service.processMessage(7L, 77L, "kite").join().result());
        assertEquals(WordChainValidationResult.WORD_NOT_FOUND, service.processMessage(7L, 77L, "elephantx").join().result());
        assertEquals(WordChainValidationResult.DICTIONARY_API_ERROR, service.processMessage(7L, 77L, "errorapi").join().result());
        assertEquals(WordChainValidationResult.OK, service.processMessage(7L, 77L, "eagle").join().result());
    }

    @Test
    void apiErrorDoesNotMutateState() throws Exception {
        Path dir = Files.createTempDirectory("wordchain-apierror");
        FakeGateway gateway = new FakeGateway();
        gateway.set("apple", DictionaryLookupResult.FOUND, 0);
        gateway.set("errorword", DictionaryLookupResult.API_ERROR, 0);
        WordChainService service = new WordChainService(
                new WordChainStateRepository(dir),
                new DictionaryApiService(gateway)
        );
        service.setChannel(11L, 111L).join();
        service.processMessage(11L, 111L, "apple").join();

        WordChainStatusSnapshot before = service.status(11L).join();
        assertEquals("apple", before.lastWord());
        assertEquals(1, before.chainCount());

        WordChainProcessResult error = service.processMessage(11L, 111L, "errorword").join();
        assertEquals(WordChainValidationResult.DICTIONARY_API_ERROR, error.result());

        WordChainStatusSnapshot after = service.status(11L).join();
        assertEquals("apple", after.lastWord());
        assertEquals(1, after.chainCount());
    }

    @Test
    void guildProcessingIsSerialized() throws Exception {
        Path dir = Files.createTempDirectory("wordchain-serialized");
        FakeGateway gateway = new FakeGateway();
        gateway.set("apple", DictionaryLookupResult.FOUND, 300);
        gateway.set("kite", DictionaryLookupResult.FOUND, 0);
        WordChainService service = new WordChainService(
                new WordChainStateRepository(dir),
                new DictionaryApiService(gateway)
        );
        service.setChannel(3L, 33L).join();

        CompletableFuture<WordChainProcessResult> first = service.processMessage(3L, 33L, "apple");
        CompletableFuture<WordChainProcessResult> second = service.processMessage(3L, 33L, "kite");

        assertEquals(WordChainValidationResult.OK, first.join().result());
        assertEquals(WordChainValidationResult.WRONG_START_LETTER, second.join().result());
    }

    @Test
    void disableAndResetWork() throws Exception {
        Path dir = Files.createTempDirectory("wordchain-reset");
        FakeGateway gateway = new FakeGateway();
        gateway.set("apple", DictionaryLookupResult.FOUND, 0);
        WordChainService service = new WordChainService(
                new WordChainStateRepository(dir),
                new DictionaryApiService(gateway)
        );
        service.setChannel(5L, 55L).join();
        service.processMessage(5L, 55L, "apple").join();

        WordChainStatusSnapshot reset = service.reset(5L).join();
        assertEquals(0, reset.chainCount());
        assertEquals("", reset.lastWord());

        WordChainStatusSnapshot disabled = service.disable(5L).join();
        assertFalse(disabled.enabled());
        assertNull(disabled.channelId());
    }

    @Test
    void statsAndLeaderboardUseSuccessCountFirstThenRate() throws Exception {
        Path dir = Files.createTempDirectory("wordchain-stats");
        FakeGateway gateway = new FakeGateway();
        gateway.set("apple", DictionaryLookupResult.FOUND, 0);
        gateway.set("eagle", DictionaryLookupResult.FOUND, 0);
        gateway.set("errorapi", DictionaryLookupResult.API_ERROR, 0);
        gateway.set("elephantx", DictionaryLookupResult.NOT_FOUND, 0);
        WordChainService service = new WordChainService(
                new WordChainStateRepository(dir),
                new DictionaryApiService(gateway)
        );
        service.setChannel(99L, 990L).join();

        // user 1: success 2, total 3 => 66.67%
        service.processMessage(99L, 990L, 1L, "apple").join();
        service.processMessage(99L, 990L, 1L, "eagle").join();
        service.processMessage(99L, 990L, 1L, "elephantx").join();

        service.reset(99L).join();
        // user 2: success 1, total 1 => 100%
        service.processMessage(99L, 990L, 2L, "apple").join();
        // API error should not count as invalid
        service.processMessage(99L, 990L, 2L, "errorapi").join();

        WordChainPlayerStatsSnapshot one = service.stats(99L, 1L).join();
        WordChainPlayerStatsSnapshot two = service.stats(99L, 2L).join();
        assertEquals(3L, one.totalMessages());
        assertEquals(2L, one.successCount());
        assertEquals(1L, one.invalidCount());
        assertEquals(1L, two.totalMessages());
        assertEquals(1L, two.successCount());
        assertEquals(0L, two.invalidCount());

        List<WordChainLeaderboardEntry> ranking = service.leaderboard(99L, 10).join();
        assertEquals(2, ranking.size());
        assertEquals(1L, ranking.get(0).userId()); // success count first
        assertEquals(2L, ranking.get(0).successCount());
        assertEquals(2L, ranking.get(1).userId());
    }

    private static final class FakeGateway implements DictionaryApiGateway {
        private static final class Reply {
            private final DictionaryLookupResult result;
            private final long delayMillis;

            private Reply(DictionaryLookupResult result, long delayMillis) {
                this.result = result;
                this.delayMillis = delayMillis;
            }
        }

        private final Map<String, Reply> replies = new ConcurrentHashMap<>();

        void set(String word, DictionaryLookupResult result, long delayMillis) {
            replies.put(word, new Reply(result, delayMillis));
        }

        @Override
        public CompletableFuture<DictionaryLookupResult> lookup(String word) {
            Reply reply = replies.getOrDefault(word, new Reply(DictionaryLookupResult.NOT_FOUND, 0));
            CompletableFuture<DictionaryLookupResult> future = new CompletableFuture<>();
            if (reply.delayMillis <= 0) {
                future.complete(reply.result);
            } else {
                CompletableFuture.delayedExecutor(reply.delayMillis, TimeUnit.MILLISECONDS)
                        .execute(() -> future.complete(reply.result));
            }
            return future;
        }
    }
}
