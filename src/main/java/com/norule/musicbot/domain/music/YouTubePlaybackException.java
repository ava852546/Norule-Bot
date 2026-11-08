package com.norule.musicbot.domain.music;

public final class YouTubePlaybackException extends Exception {
    private final YoutubeFailureCategory category;
    private final Integer httpStatus;
    private final YouTubePlaybackBackend backend;

    public YouTubePlaybackException(YoutubeFailureCategory category, String message) {
        this(category, message, null, null);
    }

    public YouTubePlaybackException(YoutubeFailureCategory category,
                                    String message,
                                    Integer httpStatus,
                                    Throwable cause) {
        this(category, message, httpStatus, cause, YouTubePlaybackBackend.COMPANION);
    }

    public YouTubePlaybackException(YoutubeFailureCategory category, String message, Integer httpStatus,
                                    Throwable cause, YouTubePlaybackBackend backend) {
        super(message, cause);
        this.category = category == null ? YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE : category;
        this.httpStatus = httpStatus;
        this.backend = backend == null ? YouTubePlaybackBackend.COMPANION : backend;
    }

    public YouTubePlaybackBackend backend() {
        return backend;
    }

    public YoutubeFailureCategory category() {
        return category;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public boolean allowsSourceFallback() {
        if (getCause() instanceof InterruptedException) return false;
        return category == YoutubeFailureCategory.COMPANION_UNAVAILABLE
                || category == YoutubeFailureCategory.COMPANION_TIMEOUT
                || category == YoutubeFailureCategory.HTTP_FORBIDDEN
                || category == YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE;
    }
}
