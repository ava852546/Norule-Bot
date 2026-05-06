package com.norule.musicbot.domain.wordchain;

public record WordChainLeaderboardEntry(
        long userId,
        long totalMessages,
        long successCount,
        long invalidCount,
        double successRate
) {
}

