package com.norule.musicbot.domain.music.bilibili;

public final class BilibiliRequestException extends RuntimeException {
    private final BilibiliFailureCategory category;
    private final BilibiliFailureStage stage;
    private final int httpStatus;
    private final boolean retryable;
    private final String videoId;

    public BilibiliRequestException(BilibiliFailureCategory category,
                                    BilibiliFailureStage stage,
                                    int httpStatus,
                                    String message) {
        this(category, stage, httpStatus, message, false, "", null);
    }

    public BilibiliRequestException(BilibiliFailureCategory category,
                                    BilibiliFailureStage stage,
                                    int httpStatus,
                                    String message,
                                    boolean retryable,
                                    String videoId,
                                    Throwable cause) {
        super(message, cause);
        this.category = category;
        this.stage = stage;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.videoId = videoId == null ? "" : videoId;
    }

    public boolean retryable() {
        return retryable;
    }

    public String videoId() {
        return videoId;
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
