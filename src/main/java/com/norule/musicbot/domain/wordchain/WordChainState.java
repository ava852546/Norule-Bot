package com.norule.musicbot.domain.wordchain;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

public final class WordChainState {
    private final boolean enabled;
    private final Long channelId;
    private final String lastWord;
    private final int chainCount;
    private final LinkedHashSet<String> usedWords;
    private final LinkedHashMap<Long, WordChainPlayerStats> playerStats;

    public WordChainState(boolean enabled,
                          Long channelId,
                          String lastWord,
                          int chainCount,
                          Set<String> usedWords,
                          Map<Long, WordChainPlayerStats> playerStats) {
        this.enabled = enabled;
        this.channelId = channelId;
        this.lastWord = normalizeWord(lastWord);
        this.chainCount = Math.max(0, chainCount);
        this.usedWords = normalizeSet(usedWords);
        this.playerStats = normalizeStats(playerStats);
    }

    public static WordChainState empty() {
        return new WordChainState(false, null, "", 0, Set.of(), Map.of());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Long getChannelId() {
        return channelId;
    }

    public String getLastWord() {
        return lastWord;
    }

    public int getChainCount() {
        return chainCount;
    }

    public Set<String> getUsedWords() {
        return new LinkedHashSet<>(usedWords);
    }

    public Map<Long, WordChainPlayerStats> getPlayerStats() {
        return new LinkedHashMap<>(playerStats);
    }

    public WordChainPlayerStats getPlayerStats(long userId) {
        return playerStats.getOrDefault(userId, WordChainPlayerStats.empty());
    }

    public WordChainState withChannelAndEnable(Long nextChannelId) {
        return new WordChainState(true, nextChannelId, lastWord, chainCount, usedWords, playerStats);
    }

    public WordChainState disabled() {
        return new WordChainState(false, null, lastWord, chainCount, usedWords, playerStats);
    }

    public WordChainState resetProgress() {
        return new WordChainState(enabled, channelId, "", 0, Set.of(), playerStats);
    }

    public WordChainState acceptWord(String word) {
        String normalized = normalizeWord(word);
        LinkedHashSet<String> nextUsed = new LinkedHashSet<>(usedWords);
        nextUsed.add(normalized);
        return new WordChainState(enabled, channelId, normalized, chainCount + 1, nextUsed, playerStats);
    }

    public WordChainState recordAttempt(long userId, WordChainValidationResult result) {
        if (userId <= 0L || result == null || result == WordChainValidationResult.DICTIONARY_API_ERROR) {
            return this;
        }
        LinkedHashMap<Long, WordChainPlayerStats> nextStats = new LinkedHashMap<>(playerStats);
        WordChainPlayerStats current = nextStats.getOrDefault(userId, WordChainPlayerStats.empty());
        WordChainPlayerStats updated = result == WordChainValidationResult.OK
                ? current.recordSuccess()
                : current.recordInvalid();
        nextStats.put(userId, updated);
        return new WordChainState(enabled, channelId, lastWord, chainCount, usedWords, nextStats);
    }

    private static LinkedHashSet<String> normalizeSet(Set<String> words) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (words == null) {
            return normalized;
        }
        for (String word : words) {
            String one = normalizeWord(word);
            if (!one.isBlank()) {
                normalized.add(one);
            }
        }
        return normalized;
    }

    private static String normalizeWord(String word) {
        if (word == null) {
            return "";
        }
        return word.trim().toLowerCase(Locale.ROOT);
    }

    private static LinkedHashMap<Long, WordChainPlayerStats> normalizeStats(Map<Long, WordChainPlayerStats> stats) {
        LinkedHashMap<Long, WordChainPlayerStats> normalized = new LinkedHashMap<>();
        if (stats == null) {
            return normalized;
        }
        for (Map.Entry<Long, WordChainPlayerStats> entry : stats.entrySet()) {
            if (entry.getKey() == null || entry.getKey() <= 0L || entry.getValue() == null) {
                continue;
            }
            WordChainPlayerStats value = entry.getValue();
            normalized.put(entry.getKey(), new WordChainPlayerStats(
                    Math.max(0L, value.totalMessages()),
                    Math.max(0L, value.successCount()),
                    Math.max(0L, value.invalidCount())
            ));
        }
        return normalized;
    }
}
