package com.norule.musicbot.discord.bot.flow;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.gateway.FlowContext;
import com.norule.musicbot.discord.gateway.FlowStep;
import com.norule.musicbot.discord.gateway.Intent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.util.Map;

public final class SlashPolicyGate implements FlowStep<SlashCommandInteractionEvent> {
    private final MusicCommandService owner;

    public SlashPolicyGate(MusicCommandService owner) {
        this.owner = owner;
    }

    @Override
    public boolean apply(SlashCommandInteractionEvent event, FlowContext context) {
        String lang = context.get("lang", String.class);
        Intent intent = context.get("intent", Intent.class);
        String commandName = intent == null ? owner.canonicalSlashName(event.getName()) : intent.key();

        if (!owner.isBotReadyForSlashCommands()) {
            event.reply(owner.i18nService().t(lang, "general.bot_starting_up"))
                    .setEphemeral(true)
                    .queue();
            return false;
        }

        long remaining = owner.acquireCooldown(event.getUser().getIdLong());
        if (remaining > 0) {
            event.reply(owner.i18nService().t(lang, "general.command_cooldown",
                            Map.of("seconds", String.valueOf(owner.toCooldownSeconds(remaining)))))
                    .setEphemeral(true)
                    .queue();
            return false;
        }

        if (owner.isSlashMusicCommand(commandName)
                && !owner.isMusicCommandChannelAllowed(event.getGuild(), event.getChannel().getIdLong())) {
            event.reply(owner.i18nService().t(lang, "music.command_channel_restricted"))
                    .setEphemeral(true)
                    .queue();
            return false;
        }
        return true;
    }
}

