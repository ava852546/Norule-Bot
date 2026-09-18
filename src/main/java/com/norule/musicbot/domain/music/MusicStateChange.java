package com.norule.musicbot.domain.music;

/** Playback state events, independent of their UI subscribers. */
public enum MusicStateChange {
    TRACK_START, TRACK_END, QUEUE_CHANGED, PAUSE, RESUME, VOLUME_CHANGED,
    LOOP_CHANGED, SHUFFLE_CHANGED, RECOVERY, STATE_CHANGED
}
