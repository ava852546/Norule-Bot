package com.norule.musicbot.discord.bot.gateway.command.settings.view;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.ModerationService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.ArrayList;
import java.util.List;

public final class SettingsUiText {
    private final MusicCommandService owner;

    public SettingsUiText(MusicCommandService owner) {
        this.owner = owner;
    }

    public String quotedSettingLine(String lang, String key, String labelKey, String value) {
        return keyIcon(key) + " " + owner.i18nService().t(lang, key)
                + "\n> " + owner.i18nService().t(lang, labelKey) + ": " + value;
    }

    public String limitText(String value, int max) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.length() <= max ? value : value.substring(0, max - 1);
    }

    public String formatTextChannel(Guild guild, Long id, String lang) {
        if (id == null) {
            return owner.i18nService().t(lang, "settings.info_channels_none");
        }
        TextChannel channel = guild.getTextChannelById(id);
        return channel == null ? "#" + id : channel.getAsMention() + " (" + id + ")";
    }

    public String keyIcon(String key) {
        return switch (key) {
            case "settings.info_language" -> "\uD83C\uDF10";
            case "settings.info_key_enabled", "settings.key_messageLogs_enabled",
                 "settings.key_notifications_enabled", "settings.key_privateRoom_enabled" -> "\u2699\uFE0F";
            case "settings.info_key_member_join_enabled", "settings.key_notifications_memberJoinEnabled",
                 "settings.info_key_member_join_template", "settings.info_key_member_join_color",
                 "settings.info_key_member_join_channel" -> "\uD83D\uDC4B";
            case "settings.key_welcome_enabled" -> "\uD83C\uDF89";
            case "settings.info_key_member_leave_enabled", "settings.key_notifications_memberLeaveEnabled",
                 "settings.info_key_member_leave_template", "settings.info_key_member_leave_color",
                 "settings.info_key_member_leave_channel" -> "\uD83D\uDEAA";
            case "settings.info_key_member_channel", "settings.key_notifications_memberChannelId",
                 "settings.info_key_member_channel_mode" -> "\uD83D\uDC65";
            case "settings.info_key_voice_log_enabled", "settings.key_notifications_voiceLogEnabled",
                 "settings.info_key_voice_channel", "settings.key_notifications_voiceChannelId",
                 "settings.info_key_voice_join_template", "settings.info_key_voice_leave_template",
                 "settings.info_key_voice_move_template" -> "\uD83D\uDD0A";
            case "settings.info_key_log_channel", "settings.key_messageLogs_channelId",
                 "settings.info_key_message_log_channel", "settings.key_messageLogs_messageLogChannelId" -> "\uD83D\uDCCC";
            case "settings.info_key_log_command_channel", "settings.key_messageLogs_commandUsageChannelId",
                 "settings.info_key_log_command_usage" -> "\uD83E\uDDED";
            case "settings.info_key_log_channel_events_channel", "settings.key_messageLogs_channelLifecycleChannelId",
                 "settings.info_key_log_channel_lifecycle" -> "\uD83C\uDFD7\uFE0F";
            case "settings.info_key_log_role_channel", "settings.key_messageLogs_roleLogChannelId",
                 "settings.info_key_log_role" -> "\uD83C\uDFF7\uFE0F";
            case "settings.info_key_log_moderation_channel", "settings.key_messageLogs_moderationLogChannelId",
                 "settings.info_key_log_moderation" -> "\uD83D\uDEE1\uFE0F";
            case "settings.key_music_autoLeaveEnabled", "settings.key_music_autoLeaveMinutes",
                 "settings.info_key_auto_leave_enabled", "settings.info_key_auto_leave_minutes" -> "\u23F1\uFE0F";
            case "settings.key_music_autoplayEnabled", "settings.info_key_autoplay_enabled" -> "\uD83D\uDD01";
            case "settings.key_numberChain_enabled" -> "\u0031\u20E3";
            case "settings.key_ticket_enabled" -> "\uD83C\uDFAB";
            case "settings.key_ticket_maxOpenPerUser" -> "\uD83D\uDD22";
            case "settings.key_ticket_blacklistUserIds" -> "\uD83D\uDEAB";
            case "settings.info_key_number_chain_enabled",
                 "settings.info_key_number_chain_channel",
                 "settings.info_key_number_chain_next" -> "\u0031\u20E3";
            case "settings.info_key_default_repeat_mode" -> "\uD83D\uDD02";
            case "settings.key_music_commandChannelId", "settings.info_key_music_command_channel" -> "\uD83C\uDFB6";
            case "settings.key_privateRoom_triggerVoiceChannelId", "settings.info_key_trigger_channel" -> "\uD83C\uDFA4";
            case "settings.info_key_category_auto", "settings.info_key_category" -> "\uD83D\uDCC2";
            case "settings.key_privateRoom_userLimit", "settings.info_key_user_limit" -> "\uD83D\uDC64";
            default -> "\u25AB\uFE0F";
        };
    }

    public String numberChainHighestLabel(String lang) {
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "\u6700\u9ad8\u7eaa\u5f55";
        }
        if (lang != null && lang.toLowerCase().startsWith("zh")) {
            return "\u6700\u9ad8\u7d00\u9304";
        }
        return "Highest Record";
    }

    public String numberChainTopContributorsLabel(String lang) {
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "\u63a5\u9f99\u6210\u5458\u524d 5 \u540d";
        }
        if (lang != null && lang.toLowerCase().startsWith("zh")) {
            return "\u63a5\u9f8d\u6210\u54e1\u524d 5 \u540d";
        }
        return "Top 5 Contributors";
    }

    public String formatNumberChainTopContributors(Guild guild, String lang) {
        List<ModerationService.NumberChainContributor> contributors =
                owner.moderationService().getTopNumberChainContributors(guild.getIdLong(), 5);
        if (contributors.isEmpty()) {
            return owner.i18nService().t(lang, "settings.info_channels_none");
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < contributors.size(); i++) {
            ModerationService.NumberChainContributor contributor = contributors.get(i);
            lines.add((i + 1) + ". <@" + contributor.getUserId() + "> - " + contributor.getCount());
        }
        return String.join("\n", lines);
    }
}
