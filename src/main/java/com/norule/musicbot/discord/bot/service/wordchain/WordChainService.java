package com.norule.musicbot.discord.bot.service.wordchain;

import com.norule.musicbot.domain.wordchain.DictionaryLookupResult;
import com.norule.musicbot.domain.wordchain.WordChainLeaderboardEntry;
import com.norule.musicbot.domain.wordchain.WordChainPlayerStats;
import com.norule.musicbot.domain.wordchain.WordChainPlayerStatsSnapshot;
import com.norule.musicbot.domain.wordchain.WordChainProcessResult;
import com.norule.musicbot.domain.wordchain.WordChainState;
import com.norule.musicbot.domain.wordchain.WordChainStatusSnapshot;
import com.norule.musicbot.domain.wordchain.WordChainValidationResult;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class WordChainService {
    private final WordChainStateRepository repository;
    private final DictionaryApiService dictionaryApiService;
    private final ConcurrentHashMap<Long, WordChainState> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> guildQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Object> guildLocks = new ConcurrentHashMap<>();

    public WordChainService(WordChainStateRepository repository, DictionaryApiService dictionaryApiService) {
        if (repository == null) {
            throw new IllegalArgumentException("repository cannot be null");
        }
        if (dictionaryApiService == null) {
            throw new IllegalArgumentException("dictionaryApiService cannot be null");
        }
        this.repository = repository;
        this.dictionaryApiService = dictionaryApiService;
    }

    public CompletableFuture<WordChainStatusSnapshot> setChannel(long guildId, long channelId) {
        return enqueue(guildId, () -> {
            WordChainState current = state(guildId);
            WordChainState updated = current.withChannelAndEnable(channelId).resetProgress();
            persist(guildId, updated);
            return CompletableFuture.completedFuture(toStatus(updated));
        });
    }

    public CompletableFuture<WordChainStatusSnapshot> disable(long guildId) {
        return enqueue(guildId, () -> {
            WordChainState current = state(guildId);
            WordChainState updated = current.disabled().resetProgress();
            persist(guildId, updated);
            return CompletableFuture.completedFuture(toStatus(updated));
        });
    }

    public CompletableFuture<WordChainStatusSnapshot> reset(long guildId) {
        return enqueue(guildId, () -> {
            WordChainState current = state(guildId);
            WordChainState updated = current.resetProgress();
            persist(guildId, updated);
            return CompletableFuture.completedFuture(toStatus(updated));
        });
    }

    public CompletableFuture<WordChainStatusSnapshot> status(long guildId) {
        return enqueue(guildId, () -> CompletableFuture.completedFuture(toStatus(state(guildId))));
    }

    public CompletableFuture<WordChainPlayerStatsSnapshot> stats(long guildId, long userId) {
        return enqueue(guildId, () -> {
            WordChainPlayerStats stats = state(guildId).getPlayerStats(userId);
            return CompletableFuture.completedFuture(toStatsSnapshot(userId, stats));
        });
    }

    public CompletableFuture<List<WordChainLeaderboardEntry>> leaderboard(long guildId, int limit) {
        int safeLimit = Math.max(1, Math.min(20, limit));
        return enqueue(guildId, () -> {
            Map<Long, WordChainPlayerStats> statsMap = state(guildId).getPlayerStats();
            List<WordChainLeaderboardEntry> ranking = statsMap.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getKey() > 0L && entry.getValue() != null)
                    .map(entry -> {
                        WordChainPlayerStats value = entry.getValue();
                        return new WordChainLeaderboardEntry(
                                entry.getKey(),
                                value.totalMessages(),
                                value.successCount(),
                                value.invalidCount(),
                                value.successRate()
                        );
                    })
                    .filter(entry -> entry.totalMessages() > 0L)
                    .sorted(Comparator
                            .comparingLong(WordChainLeaderboardEntry::successCount).reversed()
                            .thenComparing(Comparator.comparingDouble(WordChainLeaderboardEntry::successRate).reversed())
                            .thenComparing(Comparator.comparingLong(WordChainLeaderboardEntry::totalMessages).reversed())
                            .thenComparingLong(WordChainLeaderboardEntry::userId))
                    .limit(safeLimit)
                    .toList();
            return CompletableFuture.completedFuture(ranking);
        });
    }

    public CompletableFuture<WordChainProcessResult> processMessage(long guildId, long channelId, long userId, String contentRaw) {
        return enqueue(guildId, () -> processQueued(guildId, channelId, userId, contentRaw));
    }

    public CompletableFuture<WordChainProcessResult> processMessage(long guildId, long channelId, String contentRaw) {
        return processMessage(guildId, channelId, 0L, contentRaw);
    }

    private CompletableFuture<WordChainProcessResult> processQueued(long guildId, long channelId, long userId, String contentRaw) {
        WordChainState current = state(guildId);
        if (!current.isEnabled() || current.getChannelId() == null || !current.getChannelId().equals(channelId)) {
            return CompletableFuture.completedFuture(WordChainProcessResult.ignored());
        }

        String word = normalize(contentRaw);
        if (word.isBlank()) {
            return CompletableFuture.completedFuture(fail(guildId, current, userId, WordChainValidationResult.EMPTY, word));
        }
        if (word.matches(".*\\s+.*")) {
            return CompletableFuture.completedFuture(fail(guildId, current, userId, WordChainValidationResult.NOT_SINGLE_WORD, word));
        }
        if (!word.matches("[a-z]+")) {
            return CompletableFuture.completedFuture(fail(guildId, current, userId, WordChainValidationResult.NOT_ENGLISH, word));
        }
        if (current.getUsedWords().contains(word)) {
            return CompletableFuture.completedFuture(fail(guildId, current, userId, WordChainValidationResult.WORD_USED, word));
        }

        Character expectedStart = expectedStartLetter(current);
        if (expectedStart != null && word.charAt(0) != expectedStart) {
            return CompletableFuture.completedFuture(fail(guildId, current, userId, WordChainValidationResult.WRONG_START_LETTER, word));
        }

        return dictionaryApiService.lookupWord(word)
                .thenApply(lookup -> {
                    if (lookup == DictionaryLookupResult.NOT_FOUND) {
                        return fail(guildId, current, userId, WordChainValidationResult.WORD_NOT_FOUND, word);
                    }
                    if (lookup == DictionaryLookupResult.API_ERROR) {
                        return fail(guildId, current, userId, WordChainValidationResult.DICTIONARY_API_ERROR, word);
                    }
                    WordChainState updated = current.acceptWord(word).recordAttempt(userId, WordChainValidationResult.OK);
                    persist(guildId, updated);
                    return new WordChainProcessResult(
                            true,
                            WordChainValidationResult.OK,
                            word,
                            expectedStart,
                            expectedStartLetter(updated),
                            updated.getChainCount()
                    );
                });
    }

    private WordChainProcessResult fail(long guildId, WordChainState state, long userId, WordChainValidationResult result, String word) {
        WordChainState updated = state.recordAttempt(userId, result);
        if (updated != state) {
            persist(guildId, updated);
        }
        return new WordChainProcessResult(
                true,
                result,
                word,
                expectedStartLetter(state),
                expectedStartLetter(state),
                state.getChainCount()
        );
    }

    private WordChainStatusSnapshot toStatus(WordChainState state) {
        return new WordChainStatusSnapshot(
                state.isEnabled(),
                state.getChannelId(),
                state.getLastWord(),
                expectedStartLetter(state),
                state.getChainCount(),
                state.getUsedWords().size()
        );
    }

    private WordChainPlayerStatsSnapshot toStatsSnapshot(long userId, WordChainPlayerStats stats) {
        WordChainPlayerStats safe = stats == null ? WordChainPlayerStats.empty() : stats;
        return new WordChainPlayerStatsSnapshot(
                userId,
                safe.totalMessages(),
                safe.successCount(),
                safe.invalidCount(),
                safe.successRate()
        );
    }

    private Character expectedStartLetter(WordChainState state) {
        if (state == null || state.getLastWord().isBlank()) {
            return null;
        }
        String last = state.getLastWord();
        return last.charAt(last.length() - 1);
    }

    private WordChainState state(long guildId) {
        return cache.computeIfAbsent(guildId, repository::load);
    }

    private void persist(long guildId, WordChainState state) {
        cache.put(guildId, state);
        repository.save(guildId, state);
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private <T> CompletableFuture<T> enqueue(long guildId, Supplier<CompletableFuture<T>> supplier) {
        CompletableFuture<T> output = new CompletableFuture<>();
        Object lock = guildLocks.computeIfAbsent(guildId, ignored -> new Object());
        synchronized (lock) {
            CompletableFuture<Void> tail = guildQueues.get(guildId);
            CompletableFuture<Void> base = tail == null ? CompletableFuture.completedFuture(null) : tail;
            CompletableFuture<Void> next = base.handle((ignored, error) -> null)
                    .thenCompose(ignored -> supplier.get()
                            .handle((value, error) -> {
                                if (error != null) {
                                    output.completeExceptionally(error);
                                } else {
                                    output.complete(value);
                                }
                                return null;
                            }));
            guildQueues.put(guildId, next);
            next.whenComplete((ignored, error) -> {
                synchronized (lock) {
                    CompletableFuture<Void> current = guildQueues.get(guildId);
                    if (current == next) {
                        guildQueues.remove(guildId);
                        guildLocks.remove(guildId, lock);
                    }
                }
            });
        }
        return output;
    }
}
