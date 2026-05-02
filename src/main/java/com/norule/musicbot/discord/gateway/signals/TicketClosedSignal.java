package com.norule.musicbot.discord.gateway.signals;

public record TicketClosedSignal(long guildId, long channelId, String closedBy, boolean autoClosed) {
}

