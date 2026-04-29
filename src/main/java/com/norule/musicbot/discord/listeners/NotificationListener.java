package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.config.*;
import com.norule.musicbot.i18n.*;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.Permission;

import java.awt.Color;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class NotificationListener extends ListenerAdapter {
    private final GuildSettingsService guildSettingsService;
    private final I18nService i18n;

    public NotificationListener(GuildSettingsService guildSettingsService, I18nService i18n) {
        this.guildSettingsService = guildSettingsService;
        this.i18n = i18n;
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        BotConfig.Notifications config = guildSettingsService.getNotifications(event.getGuild().getIdLong());
        if (event.getUser().isBot()) {
            return;
        }
        String lang = guildSettingsService.getLanguage(event.getGuild().getIdLong());
        if (config.isEnabled() && config.isMemberJoinEnabled()) {
            sendMemberMessage(
                    event.getGuild(),
                    config,
                    formatUserTemplate(resolveMemberTemplate(lang, config.getMemberJoinMessage(), true), event.getUser(), event.getGuild()),
                    config.getMemberJoinColor(),
                    event.getUser(),
                    true
            );
        }
        sendWelcomeMessage(event.getGuild(), guildSettingsService.getWelcome(event.getGuild().getIdLong()), event.getUser(), lang);
    }

    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        BotConfig.Notifications config = guildSettingsService.getNotifications(event.getGuild().getIdLong());
        User user = event.getUser();
        if (!config.isEnabled() || !config.isMemberLeaveEnabled() || user.isBot()) {
            return;
        }
        String lang = guildSettingsService.getLanguage(event.getGuild().getIdLong());

        sendMemberMessage(
                event.getGuild(),
                config,
                formatUserTemplate(resolveMemberTemplate(lang, config.getMemberLeaveMessage(), false), user, event.getGuild()),
                config.getMemberLeaveColor(),
                user,
                false
        );
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        BotConfig.Notifications config = guildSettingsService.getNotifications(event.getGuild().getIdLong());
        if (!config.isEnabled() || !config.isVoiceLogEnabled() || event.getEntity().getUser().isBot()) {
            return;
        }
        String lang = guildSettingsService.getLanguage(event.getGuild().getIdLong());
        String userText = mentionWithId(event.getEntity().getAsMention(), event.getEntity().getId());
        String fromId = event.getChannelLeft() == null ? null : event.getChannelLeft().getId();
        String toId = event.getChannelJoined() == null ? null : event.getChannelJoined().getId();
        String fromText = event.getChannelLeft() == null
                ? null
                : event.getChannelLeft().getAsMention();
        String toText = event.getChannelJoined() == null
                ? null
                : event.getChannelJoined().getAsMention();
        if (event.getChannelLeft() == null && event.getChannelJoined() != null) {
            String message = formatVoiceTemplate(
                    resolveVoiceTemplate(lang, config.getVoiceJoinMessage(), "join"),
                    userText,
                    null,
                    toText,
                    null,
                    toId
            );
            sendVoiceMessage(
                    event.getGuild(),
                    config,
                    lang,
                    i18n.t(lang, "notifications.embed.voice_join_title"),
                    message,
                    config.getVoiceJoinColor()
            );
        } else if (event.getChannelLeft() != null && event.getChannelJoined() == null) {
            String message = formatVoiceTemplate(
                    resolveVoiceTemplate(lang, config.getVoiceLeaveMessage(), "leave"),
                    userText,
                    fromText,
                    null,
                    fromId,
                    null
            );
            sendVoiceMessage(
                    event.getGuild(),
                    config,
                    lang,
                    i18n.t(lang, "notifications.embed.voice_leave_title"),
                    message,
                    config.getVoiceLeaveColor()
            );
        } else if (event.getChannelLeft() != null && event.getChannelJoined() != null) {
            String message = formatVoiceTemplate(
                    resolveVoiceTemplate(lang, config.getVoiceMoveMessage(), "move"),
                    userText,
                    fromText,
                    toText,
                    fromId,
                    toId
            );
            sendVoiceMessage(
                    event.getGuild(),
                    config,
                    lang,
                    i18n.t(lang, "notifications.embed.voice_move_title"),
                    message,
                    config.getVoiceMoveColor()
            );
        }
    }

    private void sendMemberMessage(Guild guild, BotConfig.Notifications config, String message, int color, User user, boolean joinEvent) {
        Long channelId = resolveMemberChannelId(config, joinEvent);
        if (channelId == null) {
            channelId = guildSettingsService.getMessageLogs(guild.getIdLong()).getChannelId();
        }
        if (channelId == null || message == null || message.isBlank()) {
            return;
        }

        String lang = guildSettingsService.getLanguage(guild.getIdLong());
        Instant createdAt = user.getTimeCreated().toInstant();
        Instant now = Instant.now();

        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel != null) {
            String title = joinEvent
                    ? (config.getMemberJoinTitle() == null || config.getMemberJoinTitle().isBlank()
                        ? i18n.t(lang, "notifications.embed.member_join_title")
                        : formatUserTemplate(config.getMemberJoinTitle(), user, guild))
                    : i18n.t(lang, "notifications.embed.member_leave_title");
            EmbedBuilder eb = new EmbedBuilder()
                    .setColor(new Color(color & 0xFFFFFF))
                    .setTitle(title)
                    .setDescription(message)
                    .addField(i18n.t(lang, "notifications.embed.user_field"), user.getAsMention() + " (`" + user.getAsTag() + "`)", false)
                    .addField("ID", user.getId(), true)
                    .addField(i18n.t(lang, "notifications.embed.account_created_field"), discordCreatedAt(createdAt), true)
                    .addField(i18n.t(lang, joinEvent
                                    ? "notifications.embed.join_notify_time_field"
                                    : "notifications.embed.leave_notify_time_field"),
                            discordTimestamp(now),
                            false
                    );
            if (joinEvent) {
                String thumbnailUrl = sanitizeExternalUrl(config.getMemberJoinThumbnailUrl());
                String imageUrl = sanitizeExternalUrl(config.getMemberJoinImageUrl());
                eb.setThumbnail(thumbnailUrl != null ? thumbnailUrl : user.getEffectiveAvatarUrl());
                if (imageUrl != null) {
                    eb.setImage(imageUrl);
                }
            } else {
                eb.setThumbnail(user.getEffectiveAvatarUrl());
            }
            channel.sendMessageEmbeds(eb.build()).queue();
        }
    }

    private void sendWelcomeMessage(Guild guild, BotConfig.Welcome welcome, User user, String lang) {
        if (welcome == null || !welcome.isEnabled() || welcome.getChannelId() == null) {
            return;
        }
        TextChannel channel = guild.getTextChannelById(welcome.getChannelId());
        if (channel == null) {
            return;
        }
        if (!guild.getSelfMember().hasPermission(channel,
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_EMBED_LINKS)) {
            return;
        }
        String title = formatUserTemplate(resolveWelcomeTitle(welcome, lang), user, guild);
        String message = formatUserTemplate(resolveWelcomeMessage(welcome, lang), user, guild);
        if (message.isBlank()) {
            return;
        }
        String thumbnailUrl = sanitizeExternalUrl(welcome.getThumbnailUrl());
        String imageUrl = sanitizeExternalUrl(welcome.getImageUrl());
        EmbedBuilder eb = buildWelcomeEmbed(guild, user, title, message, thumbnailUrl, imageUrl);
        channel.sendMessageEmbeds(eb.build()).queue(
                null,
                error -> {
                    System.err.println("[NoRule] Failed to send welcome embed in guild " + guild.getId() + ": " + error.getMessage());
                    EmbedBuilder fallback = buildWelcomeEmbed(guild, user, title, message, null, null);
                    channel.sendMessageEmbeds(fallback.build()).queue(
                            null,
                            retryError -> System.err.println("[NoRule] Welcome fallback send failed in guild " + guild.getId() + ": " + retryError.getMessage())
                    );
                }
        );
    }

    private EmbedBuilder buildWelcomeEmbed(Guild guild, User user, String title, String message, String thumbnailUrl, String imageUrl) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(0x2ECC71))
                .setTitle(title)
                .setDescription(message)
                .setTimestamp(Instant.now())
                .setFooter(guild.getName(), guild.getIconUrl());
        eb.setThumbnail(thumbnailUrl != null ? thumbnailUrl : user.getEffectiveAvatarUrl());
        if (imageUrl != null) {
            eb.setImage(imageUrl);
        }
        return eb;
    }

    private Long resolveMemberChannelId(BotConfig.Notifications config, boolean joinEvent) {
        if (joinEvent && config.getMemberJoinChannelId() != null) {
            return config.getMemberJoinChannelId();
        }
        if (!joinEvent && config.getMemberLeaveChannelId() != null) {
            return config.getMemberLeaveChannelId();
        }
        return config.getMemberChannelId();
    }

    private void sendVoiceMessage(
            Guild guild,
            BotConfig.Notifications config,
            String lang,
            String title,
            String message,
            int color
    ) {
        Long channelId = config.getVoiceChannelId();
        if (channelId == null) {
            channelId = guildSettingsService.getMessageLogs(guild.getIdLong()).getChannelId();
        }
        if (channelId == null || message == null || message.isBlank()) {
            return;
        }

        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) {
            return;
        }
        if (!guild.getSelfMember().hasPermission(channel,
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_EMBED_LINKS)) {
            return;
        }
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(color & 0xFFFFFF))
                .setTitle(title == null || title.isBlank() ? i18n.t(lang, "notifications.embed.voice_move_title") : title)
                .setDescription(message);
        channel.sendMessageEmbeds(eb.build()).queue();
    }

    private String formatUserTemplate(String template, User user, Guild guild) {
        Instant createdAt = user.getTimeCreated().toInstant();
        long accountAgeDays = ChronoUnit.DAYS.between(createdAt, Instant.now());
        return template
                .replace("{user}", user.getAsMention())
                .replace("{使用者}", user.getAsMention())
                .replace("{用户}", user.getAsMention())
                .replace("{username}", user.getName())
                .replace("{使用者名稱}", user.getName())
                .replace("{用户名}", user.getName())
                .replace("{guild}", guild.getName())
                .replace("{伺服器}", guild.getName())
                .replace("{服务器}", guild.getName())
                .replace("{id}", user.getId())
                .replace("{tag}", user.getAsTag())
                .replace("{isBot}", String.valueOf(user.isBot()))
                .replace("{createdAt}", discordCreatedAt(createdAt))
                .replace("{accountAgeDays}", String.valueOf(Math.max(0L, accountAgeDays)));
    }

    private String resolveWelcomeTitle(BotConfig.Welcome welcome, String lang) {
        String title = welcome.getTitle();
        if (title == null || title.isBlank()) {
            return i18n.t(lang, "welcome.default_title");
        }
        return title;
    }

    private String resolveWelcomeMessage(BotConfig.Welcome welcome, String lang) {
        String message = welcome.getMessage();
        if (message == null || message.isBlank()) {
            return i18n.t(lang, "welcome.default_message");
        }
        return message;
    }

    private String sanitizeExternalUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isBlank()) {
            return null;
        }
        return text.startsWith("http://") || text.startsWith("https://") ? text : null;
    }

    private String formatVoiceTemplate(String template,
                                       String userMention,
                                       String fromChannel,
                                       String toChannel,
                                       String fromChannelId,
                                       String toChannelId) {
        String result = template.replace("{user}", userMention);
        result = result.replace("{channel}", toChannel != null ? toChannel : fromChannel != null ? fromChannel : "");
        result = result.replace("{channelID}", toChannelId != null ? toChannelId : fromChannelId != null ? fromChannelId : "");
        result = result.replace("{from}", fromChannel != null ? fromChannel : "");
        result = result.replace("{fromID}", fromChannelId != null ? fromChannelId : "");
        result = result.replace("{to}", toChannel != null ? toChannel : "");
        result = result.replace("{toID}", toChannelId != null ? toChannelId : "");
        return result;
    }

    private String mentionWithId(String mention, String id) {
        if (mention == null || mention.isBlank()) {
            return id == null ? "" : "ID: " + id;
        }
        if (id == null || id.isBlank()) {
            return mention;
        }
        return mention + " (ID: " + id + ")";
    }

    private String resolveVoiceTemplate(String lang, String template, String type) {
        if (!isZhTw(lang)) {
            return template;
        }
        BotConfig.Notifications defaults = BotConfig.Notifications.defaultValues();
        return switch (type) {
            case "join" -> template.equals(defaults.getVoiceJoinMessage())
                    ? i18n.t(lang, "notifications.template.default.voice_join")
                    : template;
            case "leave" -> template.equals(defaults.getVoiceLeaveMessage())
                    ? i18n.t(lang, "notifications.template.default.voice_leave")
                    : template;
            case "move" -> template.equals(defaults.getVoiceMoveMessage())
                    ? i18n.t(lang, "notifications.template.default.voice_move")
                    : template;
            default -> template;
        };
    }

    private String resolveMemberTemplate(String lang, String template, boolean join) {
        if (!isZhTw(lang)) {
            return template;
        }
        BotConfig.Notifications defaults = BotConfig.Notifications.defaultValues();
        if (join) {
            if (template.equals(defaults.getMemberJoinMessage())) {
                return i18n.t(lang, "notifications.template.default.member_join");
            }
            return template;
        }
        if (template.equals(defaults.getMemberLeaveMessage())) {
            return i18n.t(lang, "notifications.template.default.member_leave");
        }
        return template;
    }

    private boolean isZhTw(String lang) {
        return lang != null && lang.equalsIgnoreCase("zh-TW");
    }

    private String discordTimestamp(Instant instant) {
        return "<t:" + instant.getEpochSecond() + ">";
    }

    private String discordCreatedAt(Instant instant) {
        long sec = instant.getEpochSecond();
        return "<t:" + sec + ":S> (<t:" + sec + ":R>)";
    }
}





