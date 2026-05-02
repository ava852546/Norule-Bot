package com.norule.musicbot.discord.bot.ops.ticket;

import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

public final class TicketButtonHandler {
    private final TicketService ticketService;

    public TicketButtonHandler(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    public void handle(ButtonInteractionEvent event) {
        ticketService.onButtonInteraction(event);
    }
}
