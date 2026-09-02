package com.norule.musicbot.domain.music.bilibili;

public final class BilibiliRequestException extends RuntimeException {
    private final BilibiliFailureCategory category;
    private final BilibiliFailureStage stage;
    private final int httpStatus;

    public BilibiliRequestException(BilibiliFailureCategory category,
                                    BilibiliFailureStage stage,
                                    int httpStatus,
                                    String message) {
        super(message);
        this.category = category;
        this.stage = stage;
        this.httpStatus = httpStatus;
    }

    public BilibiliFailureCategory category() {
        return category;
    }

    public BilibiliFailureStage stage() {
        return stage;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
