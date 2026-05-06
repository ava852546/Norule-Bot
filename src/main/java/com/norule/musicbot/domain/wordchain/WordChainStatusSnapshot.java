package com.norule.musicbot.domain.wordchain;

public record WordChainStatusSnapshot(
        boolean enabled,
        Long channelId,
        String lastWord,
        Character nextRequiredStartLetter,
        int chainCount,
        int usedWordCount
) {
}

