package com.norule.musicbot.domain.music.bilibili;

public enum BilibiliFailureStage {
    SHORT_URL_RESOLVE,
    METADATA,
    PLAY_URL,
    CDN,
    AUDIO_SELECTION,
    MP4_PARSE,
    AAC_DECODE,
    PCM_OUTPUT,
    PLAYBACK
}
