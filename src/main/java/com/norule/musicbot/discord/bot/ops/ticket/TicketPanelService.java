package com.norule.musicbot.discord.bot.ops.ticket;

import com.norule.musicbot.config.domain.TicketConfig;

public final class TicketPanelService {
    public boolean isEnabled(TicketConfig cfg) {
        return cfg != null && cfg.isEnabled();
    }
}
