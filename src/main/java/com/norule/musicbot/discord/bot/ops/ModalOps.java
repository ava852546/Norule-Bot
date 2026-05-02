package com.norule.musicbot.discord.bot.ops;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.ops.ticket.TicketOps;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;

public final class ModalOps {
    private final MusicCommandService owner;

    public ModalOps(MusicCommandService owner) {
        this.owner = owner;
    }

    public void handle(ModalInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        String modalId = event.getModalId();
        TicketOps ticketOps = owner.ticketOps();
        if (ticketOps != null && ticketOps.handleModal(event)) {
            return;
        }
        if (modalId.startsWith(MusicCommandService.ROOM_LIMIT_MODAL_PREFIX)
                || modalId.startsWith(MusicCommandService.ROOM_RENAME_MODAL_PREFIX)) {
            owner.handleRoomSettingsModal(event);
            return;
        }
        if (owner.settingsCommandHandler().handleModalInteraction(event, lang)) {
            return;
        }
        if (modalId.startsWith(MusicCommandService.WARNING_REASON_MODAL_PREFIX)) {
            owner.handleWarningReasonModal(event, lang);
            return;
        }
        if (MusicCommandService.WELCOME_MODAL_ID.equals(modalId)) {
            owner.welcomeCommandHandler().handleWelcomeModal(event, lang);
            return;
        }
    }
}

