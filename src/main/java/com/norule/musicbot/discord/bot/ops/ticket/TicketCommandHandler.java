package com.norule.musicbot.discord.bot.ops.ticket;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public final class TicketCommandHandler {
    private final TicketService ticketService;

    public TicketCommandHandler(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    public void handle(SlashCommandInteractionEvent event) {
        ticketService.onSlashCommandInteraction(event);
    }
}
