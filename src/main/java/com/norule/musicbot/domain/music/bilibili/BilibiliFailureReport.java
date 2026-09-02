package com.norule.musicbot.domain.music.bilibili;

public record BilibiliFailureReport(
        BilibiliFailureCategory category,
        BilibiliFailureStage stage,
        int httpStatus,
        boolean retryable,
        boolean breakerFailure
) {
    public String errorKey() {
        return category.name();
    }
}
