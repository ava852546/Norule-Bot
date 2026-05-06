package com.norule.musicbot.domain.wordchain;

public record WordChainPlayerStats(
        long totalMessages,
        long successCount,
        long invalidCount
) {
    public WordChainPlayerStats {
        totalMessages = Math.max(0L, totalMessages);
        successCount = Math.max(0L, successCount);
        invalidCount = Math.max(0L, invalidCount);
    }

    public static WordChainPlayerStats empty() {
        return new WordChainPlayerStats(0L, 0L, 0L);
    }

    public WordChainPlayerStats recordSuccess() {
        return new WordChainPlayerStats(totalMessages + 1L, successCount + 1L, invalidCount);
    }

    public WordChainPlayerStats recordInvalid() {
        return new WordChainPlayerStats(totalMessages + 1L, successCount, invalidCount + 1L);
    }

    public double successRate() {
        if (totalMessages <= 0L) {
            return 0D;
        }
        return (double) successCount / (double) totalMessages;
    }
}

