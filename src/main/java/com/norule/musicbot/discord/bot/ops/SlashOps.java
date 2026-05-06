package com.norule.musicbot.discord.bot.ops;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.ops.guild.GuildConfigOps;
import com.norule.musicbot.discord.bot.ops.meta.MetaOps;
import com.norule.musicbot.discord.bot.ops.moderation.ModerationOps;
import com.norule.musicbot.discord.bot.ops.music.MusicOps;
import com.norule.musicbot.discord.bot.ops.stats.StatsOps;
import com.norule.musicbot.discord.bot.ops.ticket.TicketOps;
import com.norule.musicbot.discord.bot.ops.wordchain.WordChainOps;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public final class SlashOps {
    private final MusicCommandService owner;
    private final MusicOps musicOps;
    private final GuildConfigOps guildConfigOps;
    private final ModerationOps moderationOps;
    private final MetaOps metaOps;
    private final StatsOps statsOps;
    private final WordChainOps wordChainOps;

    public SlashOps(MusicCommandService owner, WordChainOps wordChainOps) {
        this.owner = owner;
        this.musicOps = new MusicOps(owner);
        this.guildConfigOps = new GuildConfigOps(owner);
        this.moderationOps = new ModerationOps(owner);
        this.metaOps = new MetaOps(owner);
        this.statsOps = new StatsOps(owner);
        this.wordChainOps = wordChainOps;
    }

    public void handle(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || event.getGuild() == null) {
            event.reply("Guild only.").setEphemeral(true).queue();
            return;
        }
        String lang = owner.lang(event.getGuild().getIdLong());
        String commandName = owner.canonicalSlashName(event.getName());
        if (owner.isKnownSlashCommand(commandName)) {
            owner.logCommandUsage(event.getGuild(), event.getMember(), "/" + owner.buildSlashRoute(event), event.getChannel().getIdLong());
        }

        if (musicOps.handleSlash(commandName, event, lang)) {
            return;
        }
        if (guildConfigOps.handleSlash(commandName, event, lang)) {
            return;
        }
        if (wordChainOps != null && wordChainOps.handleSlash(commandName, event, lang)) {
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
        if (statsOps.handleSlash(commandName, event)) {
            return;
        }
        event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
    }
}

