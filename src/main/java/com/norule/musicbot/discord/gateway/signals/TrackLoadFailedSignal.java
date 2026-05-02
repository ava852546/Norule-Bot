package com.norule.musicbot.discord.gateway.signals;

public record TrackLoadFailedSignal(long guildId, String title, String error) {
}

