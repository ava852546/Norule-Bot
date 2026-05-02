package com.norule.musicbot.discord.bot.ops;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.ops.guild.GuildConfigOps;
import com.norule.musicbot.discord.bot.ops.meta.MetaOps;
import com.norule.musicbot.discord.bot.ops.moderation.ModerationOps;
import com.norule.musicbot.discord.bot.ops.music.MusicOps;
import com.norule.musicbot.discord.bot.ops.ticket.TicketOps;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.util.Map;

public final class SlashOps {
    private final MusicCommandService owner;
    private final MusicOps musicOps;
    private final GuildConfigOps guildConfigOps;
    private final ModerationOps moderationOps;
    private final MetaOps metaOps;

    public SlashOps(MusicCommandService owner) {
        this.owner = owner;
        this.musicOps = new MusicOps(owner);
        this.guildConfigOps = new GuildConfigOps(owner);
        this.moderationOps = new ModerationOps(owner);
        this.metaOps = new MetaOps(owner);
    }

    public void handle(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || event.getGuild() == null) {
            event.reply("Guild only.").setEphemeral(true).queue();
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        String commandName = owner.canonicalSlashName(event.getName());

        if (!owner.isBotReadyForSlashCommands()) {
            event.reply(owner.i18nService().t(lang, "general.bot_starting_up"))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        long remaining = owner.acquireCooldown(event.getUser().getIdLong());
        if (remaining > 0) {
            event.reply(owner.i18nService().t(lang, "general.command_cooldown",
                            Map.of("seconds", String.valueOf(owner.toCooldownSeconds(remaining)))))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if (owner.isSlashMusicCommand(commandName) && !owner.isMusicCommandChannelAllowed(event.getGuild(), event.getChannel().getIdLong())) {
            event.reply(owner.i18nService().t(lang, "music.command_channel_restricted")).setEphemeral(true).queue();
            return;
        }
        if (owner.isKnownSlashCommand(commandName)) {
            owner.logCommandUsage(event.getGuild(), event.getMember(), "/" + owner.buildSlashRoute(event), event.getChannel().getIdLong());
        }

        if (musicOps.handleSlash(commandName, event, lang)) {
            return;
        }
        if (guildConfigOps.handleSlash(commandName, event, lang)) {
            return;
        }
        if (moderationOps.handleSlash(commandName, event, lang)) {
            return;
        }
        if (metaOps.handleSlash(commandName, event, lang)) {
            return;
        }
        TicketOps ticketOps = owner.ticketOps();
        if (ticketOps != null && ticketOps.handleSlash(event, commandName, lang)) {
            return;
        }
        switch (commandName) {
            case "stats", "top" -> {
                // handled by stats listener
            }
            default -> event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }
}

