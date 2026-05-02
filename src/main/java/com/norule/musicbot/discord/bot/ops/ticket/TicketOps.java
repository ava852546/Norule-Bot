package com.norule.musicbot.discord.bot.ops.ticket;

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;

public final class TicketOps {
    private static final String PREFIX = "ticket_";
    private static final String LEGACY_PREFIX = "ticket:";

    private final TicketService ticketService;
    private final TicketCommandHandler commandHandler;
    private final TicketButtonHandler buttonHandler;
    private final TicketModalHandler modalHandler;

    public TicketOps(TicketService ticketService) {
        this.ticketService = ticketService;
        this.commandHandler = new TicketCommandHandler(ticketService);
        this.buttonHandler = new TicketButtonHandler(ticketService);
        this.modalHandler = new TicketModalHandler(ticketService);
    }

    public void onReady(ReadyEvent event) {
        ticketService.onReady(event);
    }

    public void onMessage(MessageReceivedEvent event) {
        ticketService.onMessageReceived(event);
    }

    public boolean handleSlash(SlashCommandInteractionEvent event, String commandName, String lang) {
        if (!"ticket".equals(commandName)) {
            return false;
        }
        commandHandler.handle(event);
        return true;
    }

    public boolean handleButton(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!isTicketInteractionId(id)) {
            return false;
        }
        buttonHandler.handle(event);
        return true;
    }

    public boolean handleModal(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!isTicketInteractionId(id)) {
            return false;
        }
        modalHandler.handle(event);
        return true;
    }

    public boolean handleStringSelect(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!isTicketInteractionId(id)) {
            return false;
        }
        ticketService.onStringSelectInteraction(event);
        return true;
    }

    public boolean handleEntitySelect(EntitySelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!isTicketInteractionId(id)) {
            return false;
        }
        ticketService.onEntitySelectInteraction(event);
        return true;
    }

    private boolean isTicketInteractionId(String id) {
        return id != null && (id.startsWith(PREFIX) || id.startsWith(LEGACY_PREFIX));
    }
}
