package com.norule.musicbot.discord.bot.ops.ticket;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.nio.file.Path;

public final class TicketTranscriptService {
    private final com.norule.musicbot.TicketService ticketStore;

    public TicketTranscriptService(com.norule.musicbot.TicketService ticketStore) {
        this.ticketStore = ticketStore;
    }

    public Path writeTranscript(long guildId, String guildName, TextChannel channel,
                                com.norule.musicbot.TicketService.TicketRecord record,
                                String closedByTag) {
        return ticketStore.writeTranscriptHtml(guildId, guildName, channel, record, closedByTag);
    }

    public Path writeTranscript(Guild guild, TextChannel channel,
                                com.norule.musicbot.TicketService.TicketRecord record,
                                String closedByTag) {
        return writeTranscript(guild.getIdLong(), guild.getName(), channel, record, closedByTag);
    }
}
