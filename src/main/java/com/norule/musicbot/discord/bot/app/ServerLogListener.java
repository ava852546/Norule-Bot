package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent;
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent;
import net.dv8tion.jda.api.events.channel.update.ChannelUpdateNameEvent;
import net.dv8tion.jda.api.events.guild.GuildAuditLogEntryCreateEvent;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class ServerLogListener extends ListenerAdapter {
    private final ServerLogService service;

    public ServerLogListener(GuildSettingsService settingsService, I18nService i18n) {
        this.service = new ServerLogService(settingsService, i18n);
    }

    @Override
    public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) { service.onGuildMemberRoleAdd(event); }
    @Override
    public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) { service.onGuildMemberRoleRemove(event); }
    @Override
    public void onChannelCreate(ChannelCreateEvent event) { service.onChannelCreate(event); }
    @Override
    public void onChannelDelete(ChannelDeleteEvent event) { service.onChannelDelete(event); }
    @Override
    public void onChannelUpdateName(ChannelUpdateNameEvent event) { service.onChannelUpdateName(event); }
    @Override
    public void onGuildBan(GuildBanEvent event) { service.onGuildBan(event); }
    @Override
    public void onGuildUnban(GuildUnbanEvent event) { service.onGuildUnban(event); }
    @Override
    public void onGuildAuditLogEntryCreate(GuildAuditLogEntryCreateEvent event) { service.onGuildAuditLogEntryCreate(event); }
}

