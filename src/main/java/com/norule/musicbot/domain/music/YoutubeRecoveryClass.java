package com.norule.musicbot.domain.music;

public enum YoutubeRecoveryClass {
    RETRYABLE,
    AUTH_MAY_HELP,
    CLIENT_FALLBACK_MAY_HELP,
    DECODER_FALLBACK_MAY_HELP,
    CONFIGURATION_ERROR,
    PERMANENT,
    UNKNOWN
}
