package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.config.*;
import com.norule.musicbot.i18n.*;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateNameEvent;
import net.dv8tion.jda.api.events.guild.GuildAuditLogEntryCreateEvent;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.awt.Color;
import java.time.Instant;
import java.util.stream.Collectors;

public class ServerLogListener extends ListenerAdapter {
    private final GuildSettingsService settingsService;
    private final I18nService i18n;

    public ServerLogListener(GuildSettingsService settingsService, I18nService i18n) {
        this.settingsService = settingsService;
        this.i18n = i18n;
    }

    @Override
    public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
        BotConfig.MessageLogs logs = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!logs.isEnabled() || !logs.isRoleLogEnabled() || event.getRoles().isEmpty()) {
            return;
        }
        String roles = event.getRoles().stream().map(r -> r.getAsMention()).collect(Collectors.joining(", "));
        EmbedBuilder eb = base(event.getGuild(), "\u2795 " + t(event.getGuild(), "logs.role_added"), new Color(46, 204, 113))
                .addField(t(event.getGuild(), "logs.user"), event.getMember().getAsMention(), false)
                .addField(t(event.getGuild(), "logs.roles"), roles, false);
        sendRoleChangeToConfiguredLogs(event.getGuild(), logs, eb);
    }

    @Override
    public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
        BotConfig.MessageLogs logs = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!logs.isEnabled() || !logs.isRoleLogEnabled() || event.getRoles().isEmpty()) {
            return;
        }
        String roles = event.getRoles().stream().map(r -> r.getAsMention()).collect(Collectors.joining(", "));
        EmbedBuilder eb = base(event.getGuild(), "\u2796 " + t(event.getGuild(), "logs.role_removed"), new Color(231, 76, 60))
                .addField(t(event.getGuild(), "logs.user"), event.getMember().getAsMention(), false)
                .addField(t(event.getGuild(), "logs.roles"), roles, false);
        sendRoleChangeToConfiguredLogs(event.getGuild(), logs, eb);
    }

    @Override
    public void onChannelCreate(ChannelCreateEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        BotConfig.MessageLogs logs = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!logs.isEnabled() || !logs.isChannelLifecycleLogEnabled()) {
            return;
        }
        Channel channel = event.getChannel();
        EmbedBuilder eb = base(event.getGuild(), "\uD83C\uDD95 " + t(event.getGuild(), "logs.channel_created"), new Color(52, 152, 219))
                .addField(t(event.getGuild(), "logs.channel"), channel.getAsMention() + " (`" + channel.getType().name() + "`)", false);
        send(event.getGuild(), logs.getChannelLifecycleChannelId(), eb);
    }

    @Override
    public void onChannelDelete(ChannelDeleteEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        BotConfig.MessageLogs logs = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!logs.isEnabled() || !logs.isChannelLifecycleLogEnabled()) {
            return;
        }
        Channel channel = event.getChannel();
        EmbedBuilder eb = base(event.getGuild(), "\uD83D\uDDD1\uFE0F " + t(event.getGuild(), "logs.channel_deleted"), new Color(231, 76, 60))
                .addField(t(event.getGuild(), "logs.channel"), "`" + channel.getName() + "` (`" + channel.getType().name() + "`)", false);
        send(event.getGuild(), logs.getChannelLifecycleChannelId(), eb);
    }

    @Override
    public void onChannelUpdateName(ChannelUpdateNameEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        BotConfig.MessageLogs logs = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!logs.isEnabled() || !logs.isChannelLifecycleLogEnabled()) {
            return;
        }
        Channel channel = event.getChannel();
        String before = event.getOldValue() == null ? "-" : event.getOldValue();
        String after = event.getNewValue() == null ? "-" : event.getNewValue();
        EmbedBuilder eb = base(event.getGuild(), "\u270F\uFE0F " + t(event.getGuild(), "logs.channel_renamed"), new Color(155, 89, 182))
                .addField(t(event.getGuild(), "logs.channel"), channel.getAsMention() + " (`" + channel.getType().name() + "`)", false)
                .addField(t(event.getGuild(), "logs.before"), "`" + before.replace("`", "") + "`", true)
                .addField(t(event.getGuild(), "logs.after"), "`" + after.replace("`", "") + "`", true);
        send(event.getGuild(), logs.getChannelLifecycleChannelId(), eb);
    }

    @Override
    public void onGuildBan(GuildBanEvent event) {
        BotConfig.MessageLogs logs = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!logs.isEnabled() || !logs.isModerationLogEnabled()) {
            return;
        }
        EmbedBuilder eb = base(event.getGuild(), "\u26D4 " + t(event.getGuild(), "logs.user_banned"), new Color(192, 57, 43))
                .addField(t(event.getGuild(), "logs.user"), event.getUser().getAsMention() + " (`" + event.getUser().getAsTag() + "`)", false);
        send(event.getGuild(), logs.getModerationLogChannelId(), eb);
    }

    @Override
    public void onGuildUnban(GuildUnbanEvent event) {
        BotConfig.MessageLogs logs = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!logs.isEnabled() || !logs.isModerationLogEnabled()) {
            return;
        }
        EmbedBuilder eb = base(event.getGuild(), "\u2705 " + t(event.getGuild(), "logs.user_unbanned"), new Color(39, 174, 96))
                .addField(t(event.getGuild(), "logs.user"), event.getUser().getAsMention() + " (`" + event.getUser().getAsTag() + "`)", false);
        send(event.getGuild(), logs.getModerationLogChannelId(), eb);
    }

    @Override
    public void onGuildAuditLogEntryCreate(GuildAuditLogEntryCreateEvent event) {
        if (event.getEntry().getType() != ActionType.KICK) {
            return;
        }
        BotConfig.MessageLogs logs = settingsService.getMessageLogs(event.getGuild().getIdLong());
        if (!logs.isEnabled() || !logs.isModerationLogEnabled()) {
            return;
        }
        User target = event.getJDA().getUserById(event.getEntry().getTargetIdLong());
        String targetText = target == null ? "`" + event.getEntry().getTargetId() + "`"
                : target.getAsMention() + " (`" + target.getAsTag() + "`)";
        User actor = event.getEntry().getUser();
        String actorText = actor == null ? "-" : actor.getAsMention() + " (`" + actor.getAsTag() + "`)";
        EmbedBuilder eb = base(event.getGuild(), "\uD83D\uDC62 " + t(event.getGuild(), "logs.user_kicked"), new Color(230, 126, 34))
                .addField(t(event.getGuild(), "logs.target"), targetText, false)
                .addField(t(event.getGuild(), "logs.moderator"), actorText, false);
        send(event.getGuild(), logs.getModerationLogChannelId(), eb);
    }

    private EmbedBuilder base(Guild guild, String title, Color color) {
        return new EmbedBuilder()
                .setTitle(title)
                .setColor(color)
                .setTimestamp(Instant.now())
                .setFooter(guild.getName(), guild.getIconUrl());
    }

    private void send(Guild guild, Long preferredChannelId, EmbedBuilder eb) {
        BotConfig.MessageLogs logs = settingsService.getMessageLogs(guild.getIdLong());
        Long channelId = preferredChannelId != null ? preferredChannelId : logs.getChannelId();
        if (channelId == null) {
            return;
        }
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) {
            return;
        }
        var selfMember = guild.getSelfMember();
        if (selfMember == null || !selfMember.hasAccess(channel) || !channel.canTalk(selfMember)) {
            return;
        }
        try {
            channel.sendMessageEmbeds(eb.build()).queue(success -> {
            }, error -> {
            });
        } catch (RuntimeException ignored) {
        }
    }

    private String t(Guild guild, String key) {
        return i18n.t(settingsService.getLanguage(guild.getIdLong()), key);
    }

    private void sendRoleChangeToConfiguredLogs(Guild guild, BotConfig.MessageLogs logs, EmbedBuilder eb) {
        Long roleTarget = resolveTargetChannelId(logs.getRoleLogChannelId(), logs.getChannelId());
        send(guild, roleTarget, eb);

        if (!logs.isChannelLifecycleLogEnabled()) {
            return;
        }
        Long channelTarget = resolveTargetChannelId(logs.getChannelLifecycleChannelId(), logs.getChannelId());
        if (channelTarget == null || channelTarget.equals(roleTarget)) {
            return;
        }
        send(guild, channelTarget, eb);
    }

    private Long resolveTargetChannelId(Long preferred, Long fallback) {
        return preferred != null ? preferred : fallback;
    }
}


