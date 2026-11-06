package com.norule.musicbot.discord.bot.gateway.panel;

public enum RefreshReason {
    TRACK_START, TRACK_END, QUEUE_CHANGED, PAUSE, RESUME, VOLUME_CHANGED,
    LOOP_CHANGED, SHUFFLE_CHANGED, SEEK, RECOVERY, STATE_CHANGED,
    BUTTON_INTERACTION, PERIODIC_REFRESH, MANUAL, PLAY_ADDED;

    public boolean highPriority() {
        // STATE_CHANGED also covers resume/leave/speed operations; treat it conservatively as foreground.
        return this != PERIODIC_REFRESH;
    }
}
