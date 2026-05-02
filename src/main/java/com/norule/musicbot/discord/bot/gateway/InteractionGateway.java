package com.norule.musicbot.discord.bot.gateway;

import com.norule.musicbot.discord.bot.flow.AutoCompleteFlow;
import com.norule.musicbot.discord.bot.flow.ButtonFlow;
import com.norule.musicbot.discord.bot.flow.ModalFlow;
import com.norule.musicbot.discord.bot.flow.SelectFlow;
import com.norule.musicbot.discord.bot.flow.SlashFlow;
import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.gateway.Signals;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

public final class InteractionGateway {
    private final SlashFlow slashFlow;
    private final ButtonFlow buttonFlow;
    private final ModalFlow modalFlow;
    private final SelectFlow selectFlow;
    private final AutoCompleteFlow autoCompleteFlow;

    public InteractionGateway(MusicCommandService owner, Signals signals) {
        this.slashFlow = new SlashFlow(owner, signals);
        this.buttonFlow = new ButtonFlow(owner);
        this.modalFlow = new ModalFlow(owner);
        this.selectFlow = new SelectFlow(owner);
        this.autoCompleteFlow = new AutoCompleteFlow(owner);
    }

    public void onSlash(SlashCommandInteractionEvent event) {
        slashFlow.run(event);
    }

    public void onButton(ButtonInteractionEvent event) {
        buttonFlow.run(event);
    }

    public void onModal(ModalInteractionEvent event) {
        modalFlow.run(event);
    }

    public void onStringSelect(StringSelectInteractionEvent event) {
        selectFlow.runString(event);
    }

    public void onEntitySelect(EntitySelectInteractionEvent event) {
        selectFlow.runEntity(event);
    }

    public void onAutoComplete(CommandAutoCompleteInteractionEvent event) {
        autoCompleteFlow.run(event);
    }
}

