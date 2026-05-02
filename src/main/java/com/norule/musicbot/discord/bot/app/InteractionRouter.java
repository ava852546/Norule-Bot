package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.discord.bot.ops.AutoCompleteOps;
import com.norule.musicbot.discord.bot.ops.ButtonOps;
import com.norule.musicbot.discord.bot.ops.ModalOps;
import com.norule.musicbot.discord.bot.ops.SelectOps;
import com.norule.musicbot.discord.bot.ops.SlashOps;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

public final class InteractionRouter {
    private final SlashOps slashOps;
    private final AutoCompleteOps autoCompleteOps;
    private final ButtonOps buttonOps;
    private final ModalOps modalOps;
    private final SelectOps selectOps;

    public InteractionRouter(MusicCommandService owner) {
        this.slashOps = new SlashOps(owner);
        this.autoCompleteOps = new AutoCompleteOps(owner);
        this.buttonOps = new ButtonOps(owner);
        this.modalOps = new ModalOps(owner);
        this.selectOps = new SelectOps(owner);
    }

    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        slashOps.handle(event);
    }

    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        autoCompleteOps.handle(event);
    }

    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        selectOps.handleString(event);
    }

    public void onModalInteraction(ModalInteractionEvent event) {
        modalOps.handle(event);
    }

    public void onButtonInteraction(ButtonInteractionEvent event) {
        buttonOps.handle(event);
    }

    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        selectOps.handleEntity(event);
    }
}

