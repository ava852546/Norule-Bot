package com.norule.musicbot.domain.music;

/** One MUSIC panel per guild. */
public record MusicPanelBinding(long guildId, long channelId, long messageId) {
}
