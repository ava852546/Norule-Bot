package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

/** Cache-only channel evidence shared by mode selection and every panel destination. */
final class MusicPanelTargetResolver {
    record Target(GuildMessageChannel channel, ChannelType type, boolean cacheHit, String reason) {
        boolean available() { return "AVAILABLE".equals(reason); }
    }

    private MusicPanelTargetResolver() {}

    static GuildMessageChannel cached(Guild guild, long id) {
        GuildMessageChannel channel = guild.getTextChannelById(id);
        if (channel == null) channel = guild.getNewsChannelById(id);
        if (channel == null) channel = guild.getThreadChannelById(id);
        if (channel == null) channel = guild.getVoiceChannelById(id);
        return channel;
    }

    static Target resolve(Guild guild, Long id, GuildMessageChannel interaction) {
        if (id == null) return new Target(null, null, false, "NO_INTERACTION_CHANNEL");
        GuildChannel known = cached(guild, id);
        boolean cacheHit = known != null;
        if (known == null) {
            known = guild.getGuildChannelById(id);
            cacheHit = known != null;
        }
        if (known == null && interaction != null && interaction.getGuild().getIdLong() == guild.getIdLong()
                && id.equals(interaction.getIdLong())) known = interaction;
        if (known == null) return new Target(null, null, false, "CHANNEL_NOT_CACHED");
        if (!(known instanceof TextChannel || known instanceof NewsChannel || known instanceof ThreadChannel || known instanceof VoiceChannel)) {
            return new Target(null, known.getType(), cacheHit, "UNSUPPORTED_CHANNEL_TYPE");
        }
        GuildMessageChannel channel = (GuildMessageChannel) known;
        return new Target(channel, known.getType(), cacheHit, unavailableReason(channel));
    }

    static String unavailableReason(GuildMessageChannel channel) {
        if (channel == null) return "CHANNEL_NOT_CACHED";
        if (!(channel instanceof TextChannel || channel instanceof NewsChannel || channel instanceof ThreadChannel || channel instanceof VoiceChannel)) {
            return "UNSUPPORTED_CHANNEL_TYPE";
        }
        var self = channel.getGuild().getSelfMember();
        if (!self.hasPermission(channel, Permission.VIEW_CHANNEL)) return "MISSING_VIEW_PERMISSION";
        if (!self.hasPermission(channel, MusicPanelModeResolver.sendPermission(channel))) return "MISSING_SEND_PERMISSION";
        if (!self.hasPermission(channel, Permission.MESSAGE_EMBED_LINKS)) return "MISSING_EMBED_PERMISSION";
        if (channel instanceof ThreadChannel thread) {
            if (thread.isLocked()) return "THREAD_LOCKED";
            if (thread.isArchived()) return "THREAD_ARCHIVED";
            if (!thread.canTalk()) return "CHANNEL_NOT_WRITABLE";
        }
        return "AVAILABLE";
    }
}
