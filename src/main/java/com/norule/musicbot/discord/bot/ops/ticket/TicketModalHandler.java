package com.norule.musicbot.discord.bot.ops.ticket;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;

public final class TicketModalHandler {
    private final TicketService ticketService;

    public TicketModalHandler(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    public void handle(ModalInteractionEvent event) {
        ticketService.onModalInteraction(event);
    }
}
