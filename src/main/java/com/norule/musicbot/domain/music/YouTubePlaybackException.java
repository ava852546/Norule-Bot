package com.norule.musicbot.domain.music;

public final class YouTubePlaybackException extends Exception {
    private final YoutubeFailureCategory category;
    private final Integer httpStatus;

    public YouTubePlaybackException(YoutubeFailureCategory category, String message) {
        this(category, message, null, null);
    }

    public YouTubePlaybackException(YoutubeFailureCategory category,
                                    String message,
                                    Integer httpStatus,
                                    Throwable cause) {
        super(message, cause);
        this.category = category == null ? YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE : category;
        this.httpStatus = httpStatus;
    }

    public YoutubeFailureCategory category() {
        return category;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public boolean allowsSourceFallback() {
        return category == YoutubeFailureCategory.COMPANION_UNAVAILABLE
                || category == YoutubeFailureCategory.COMPANION_TIMEOUT
                || category == YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE;
    }
}
