package com.norule.musicbot.discord.bot.ops.ticket;

public final class TicketAutoCloseService {
    public long cutoffMillis(int autoCloseDays, long nowMillis) {
        return nowMillis - (Math.max(1, autoCloseDays) * 24L * 60L * 60L * 1000L);
    }

    public boolean shouldAutoClose(long lastInteractionAt, long cutoffMillis) {
        return lastInteractionAt <= cutoffMillis;
    }
}
