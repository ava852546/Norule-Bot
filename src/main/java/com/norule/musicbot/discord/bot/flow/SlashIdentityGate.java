package com.norule.musicbot.discord.bot.flow;

import com.norule.musicbot.discord.gateway.FlowContext;
import com.norule.musicbot.discord.gateway.FlowStep;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public final class SlashIdentityGate implements FlowStep<SlashCommandInteractionEvent> {
    @Override
    public boolean apply(SlashCommandInteractionEvent event, FlowContext context) {
        if (event.isFromGuild() && event.getGuild() != null) {
            return true;
        }
        event.reply("Guild only.").setEphemeral(true).queue();
        return false;
    }
}

