package com.norule.musicbot.discord.bot.app;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;

public final class HelpCommandHandler {
    private final MusicCommandService owner;
    public HelpCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }
    public void handleHelpSlash(SlashCommandInteractionEvent event, String lang) {
        event.replyEmbeds(owner.helpEmbed(event.getGuild(), lang, "general").build())
                .addComponents(
                        ActionRow.of(owner.helpMenu(lang)),
                        ActionRow.of(owner.helpButtonsPrimary(lang, "general")),
                        ActionRow.of(owner.helpButtonsSecondary(lang, "general"))
                )
                .setEphemeral(true)
                .queue();
    }
    public void handleTextHelp(TextChannel channel, Guild guild, String lang) {
        channel.sendMessageEmbeds(owner.helpEmbed(guild, lang, "general").build())
                .setComponents(
                        ActionRow.of(owner.helpMenu(lang)),
                        ActionRow.of(owner.helpButtonsPrimary(lang, "general")),
                        ActionRow.of(owner.helpButtonsSecondary(lang, "general"))
                )
                .queue();
    }
    public void handleHelpSelect(StringSelectInteractionEvent event, String lang) {
        String category = event.getValues().isEmpty() ? "general" : event.getValues().get(0);
        editHelpMessage(event, lang, category);
    }
    public void handleHelpButton(ButtonInteractionEvent event, String lang) {
        String category = event.getComponentId().substring(MusicCommandService.HELP_BUTTON_PREFIX.length());
        editHelpMessage(event, lang, category);
    }

    private void editHelpMessage(StringSelectInteractionEvent event, String lang, String category) {
        event.deferEdit().queue(
                ok -> event.getHook().editOriginalEmbeds(owner.helpEmbed(event.getGuild(), lang, category).build())
                        .setComponents(
                                ActionRow.of(owner.helpMenu(lang)),
                                ActionRow.of(owner.helpButtonsPrimary(lang, category)),
                                ActionRow.of(owner.helpButtonsSecondary(lang, category))
                        )
                        .queue(null, this::handleInteractionFailure),
                this::handleInteractionFailure
        );
    }

    private void editHelpMessage(ButtonInteractionEvent event, String lang, String category) {
        event.deferEdit().queue(
                ok -> event.getHook().editOriginalEmbeds(owner.helpEmbed(event.getGuild(), lang, category).build())
                        .setComponents(
                                ActionRow.of(owner.helpMenu(lang)),
                                ActionRow.of(owner.helpButtonsPrimary(lang, category)),
                                ActionRow.of(owner.helpButtonsSecondary(lang, category))
                        )
                        .queue(null, this::handleInteractionFailure),
                this::handleInteractionFailure
        );
    }

    private void handleInteractionFailure(Throwable error) {
        if (error instanceof ErrorResponseException e && e.getErrorCode() == 10062) {
            return;
        }
        System.out.println("[NoRule] Help interaction update failed: " + (error == null ? "unknown" : error.getMessage()));
    }
}




