package com.norule.musicbot.domain.music.bilibili;

public enum BilibiliFailureCategory {
    BILIBILI_RISK_CONTROL,
    BILIBILI_ACCESS_DENIED,
    BILIBILI_RATE_LIMITED,
    BILIBILI_METADATA_FAILED,
    BILIBILI_PLAYBACK_FAILED,
    DASH_AUDIO_EMPTY,
    AUDIO_FORMAT_UNSUPPORTED,
    AUDIO_URL_MISSING,
    AUDIO_RESPONSE_INVALID;

    public boolean isAudioSelectionFailure() {
        return this == DASH_AUDIO_EMPTY || this == AUDIO_FORMAT_UNSUPPORTED
                || this == AUDIO_URL_MISSING || this == AUDIO_RESPONSE_INVALID;
    }
}
