package com.norule.musicbot.discord.bot.ops.ticket;

import com.norule.musicbot.config.domain.TicketConfig;

public final class TicketLimitService {
    public boolean canOpenTicket(TicketConfig cfg, int openCount) {
        if (cfg == null) {
            return false;
        }
        return openCount < cfg.getMaxOpenPerUser();
    }
}
