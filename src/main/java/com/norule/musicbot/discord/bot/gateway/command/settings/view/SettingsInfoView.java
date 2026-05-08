package com.norule.musicbot.discord.bot.gateway.command.settings.view;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.CommandNames;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;

import java.awt.Color;
import java.time.Instant;
import java.util.List;

public final class SettingsInfoView {
    private static final String SETTINGS_INFO_SELECT_ID = ComponentIds.SETTINGS_INFO_SELECT_ID;
    private static final String SETTINGS_INFO_BUTTON_PREFIX = ComponentIds.SETTINGS_INFO_BUTTON_PREFIX;
    private static final String ROUTE_NUMBER_CHAIN = CommandNames.CMD_NUMBER_CHAIN;
    private static final String ROUTE_MODULE = "module";
    private static final String ROUTE_MUSIC = CommandNames.CMD_MUSIC;

    private final MusicCommandService owner;

    public SettingsInfoView(MusicCommandService owner) {
        this.owner = owner;
    }

    public EmbedBuilder settingsInfoEmbed(Guild guild, String lang, String section) {
        long guildId = guild.getIdLong();
        String currentSection = section == null || section.isBlank() ? "notifications" : section;
        GuildSettingsService.GuildSettings settings = owner.settingsService().getSettings(guildId);
        var n = settings.getNotifications();
        var logs = settings.getMessageLogs();
        var music = settings.getMusic();
        var room = settings.getPrivateRoom();
        boolean numberChainEnabled = owner.moderationService().isNumberChainEnabled(guildId);
        Long numberChainChannelId = owner.moderationService().getNumberChainChannelId(guildId);
        long numberChainNext = owner.moderationService().getNumberChainNext(guildId);
        long numberChainHighest = owner.moderationService().getNumberChainHighestNumber(guildId);

        String notifications = joinLines(
                line(lang, "settings.info_key_enabled", compare(moduleSwitchTextCode(lang, n.isEnabled()))),
                line(lang, "settings.info_key_member_join_enabled", compare(moduleSwitchTextCode(lang, n.isMemberJoinEnabled()))),
                line(lang, "settings.info_key_member_leave_enabled", compare(moduleSwitchTextCode(lang, n.isMemberLeaveEnabled()))),
                line(lang, "settings.info_key_voice_log_enabled", compare(moduleSwitchTextCode(lang, n.isVoiceLogEnabled()))),
                line(lang, "settings.info_key_member_channel_mode", compare(formatMemberChannelMode(lang, n.getMemberJoinChannelId(), n.getMemberLeaveChannelId()))),
                line(lang, "settings.info_key_member_channel", compare(formatTextChannelInfo(guild, n.getMemberChannelId()))),
                line(lang, "settings.info_key_member_join_channel", compare(formatTextChannelInfo(guild, n.getMemberJoinChannelId()))),
                line(lang, "settings.info_key_member_leave_channel", compare(formatTextChannelInfo(guild, n.getMemberLeaveChannelId()))),
                line(lang, "settings.info_key_voice_channel", compare(formatTextChannelInfo(guild, n.getVoiceChannelId())))
        );
        String notificationTemplates = joinInfoBlocks(
                templateCompareMarkdown(lang, "settings.info_key_member_join_template", n.getMemberJoinMessage()),
                line(lang, "settings.info_key_member_join_color", compare(formatColor(n.getMemberJoinColor()))),
                templateCompareMarkdown(lang, "settings.info_key_member_leave_template", n.getMemberLeaveMessage()),
                line(lang, "settings.info_key_member_leave_color", compare(formatColor(n.getMemberLeaveColor()))),
                templateCompareMarkdown(lang, "settings.info_key_voice_join_template", n.getVoiceJoinMessage()),
                templateCompareMarkdown(lang, "settings.info_key_voice_leave_template", n.getVoiceLeaveMessage()),
                templateCompareMarkdown(lang, "settings.info_key_voice_move_template", n.getVoiceMoveMessage())
        );
        String messageLogs = joinLines(
                line(lang, "settings.info_key_enabled", compare(moduleSwitchTextCode(lang, logs.isEnabled()))),
                line(lang, "settings.info_key_log_channel", compare(formatTextChannelInfo(guild, logs.getChannelId()))),
                line(lang, "settings.info_key_message_log_channel", compare(formatTextChannelInfo(guild, logs.getMessageLogChannelId()))),
                line(lang, "settings.info_key_log_role_channel", compare(formatTextChannelInfo(guild, logs.getRoleLogChannelId()))),
                line(lang, "settings.info_key_log_moderation_channel", compare(formatTextChannelInfo(guild, logs.getModerationLogChannelId()))),
                line(lang, "settings.info_key_log_command_channel", compare(formatTextChannelInfo(guild, logs.getCommandUsageChannelId()))),
                line(lang, "settings.info_key_log_channel_events_channel", compare(formatTextChannelInfo(guild, logs.getChannelLifecycleChannelId()))),
                line(lang, "settings.info_key_log_role", compare(moduleSwitchTextCode(lang, logs.isRoleLogEnabled()))),
                line(lang, "settings.info_key_log_channel_lifecycle", compare(moduleSwitchTextCode(lang, logs.isChannelLifecycleLogEnabled()))),
                line(lang, "settings.info_key_log_moderation", compare(moduleSwitchTextCode(lang, logs.isModerationLogEnabled()))),
                line(lang, "settings.info_key_log_command_usage", compare(moduleSwitchTextCode(lang, logs.isCommandUsageLogEnabled()))),
                line(lang, "settings.info_key_log_ignored_members", compare(formatIgnoredMembersInfo(lang, logs.getIgnoredMemberIds()))),
                lineLabel("\uD83C\uDFF7\uFE0F", ignoredRolesInfoLabel(lang), compare(formatIgnoredRolesInfo(lang, logs.getIgnoredRoleIds()))),
                line(lang, "settings.info_key_log_ignored_channels", compare(formatIgnoredChannelsInfo(lang, logs.getIgnoredChannelIds()))),
                line(lang, "settings.info_key_log_ignored_prefixes", compare(formatIgnoredPrefixesInfo(lang, logs.getIgnoredPrefixes())))
        );
        String musicInfo = joinLines(
                line(lang, "settings.info_key_auto_leave_enabled", compare(moduleSwitchTextCode(lang, music.isAutoLeaveEnabled()))),
                line(lang, "settings.info_key_auto_leave_minutes", compare(String.valueOf(music.getAutoLeaveMinutes()))),
                line(lang, "settings.info_key_autoplay_enabled", compare(moduleSwitchTextCode(lang, owner.isAutoplayEnabledForSettings(guildId)))),
                line(lang, "settings.info_key_default_repeat_mode", compare(music.getDefaultRepeatMode().name())),
                line(lang, "settings.info_key_music_command_channel", compare(formatTextChannelInfo(guild, music.getCommandChannelId())))
        );
        String privateRoom = joinLines(
                line(lang, "settings.info_key_enabled", compare(moduleSwitchTextCode(lang, room.isEnabled()))),
                line(lang, "settings.info_key_trigger_channel", compare(formatVoiceChannelInfo(guild, room.getTriggerVoiceChannelId()))),
                line(lang, "settings.info_key_category_auto", compare(resolveTriggerCategoryWithSource(guild, room.getTriggerVoiceChannelId()))),
                line(lang, "settings.info_key_user_limit", compare(String.valueOf(room.getUserLimit())))
        );
        String numberChainInfo = joinLines(
                line(lang, "settings.info_key_number_chain_enabled", compare(moduleSwitchTextCode(lang, numberChainEnabled))),
                line(lang, "settings.info_key_number_chain_channel", compare(formatTextChannelInfo(guild, numberChainChannelId))),
                line(lang, "settings.info_key_number_chain_next", compare(String.valueOf(numberChainNext))),
                lineLabel("\uD83C\uDFC6", owner.numberChainHighestLabel(lang), String.valueOf(numberChainHighest)),
                lineLabel("\uD83D\uDC65", owner.numberChainTopContributorsLabel(lang), owner.formatNumberChainTopContributors(guild, lang))
        );
        String moduleInfo = joinLines(
                "**" + owner.i18nService().t(lang, "settings.module_section_core") + "**",
                moduleLine(lang, "settings.key_notifications_enabled", n.isEnabled()),
                moduleLine(lang, "settings.key_messageLogs_enabled", logs.isEnabled()),
                moduleLine(lang, "settings.key_welcome_enabled", settings.getWelcome().isEnabled()),
                "",
                "**" + owner.i18nService().t(lang, "settings.module_section_notifications") + "**",
                moduleLine(lang, "settings.key_notifications_memberJoinEnabled", n.isMemberJoinEnabled()),
                moduleLine(lang, "settings.key_notifications_memberLeaveEnabled", n.isMemberLeaveEnabled()),
                moduleLine(lang, "settings.key_notifications_voiceLogEnabled", n.isVoiceLogEnabled()),
                "",
                "**" + owner.i18nService().t(lang, "settings.module_section_logs") + "**",
                moduleLine(lang, "settings.info_key_log_command_usage", logs.isCommandUsageLogEnabled()),
                moduleLine(lang, "settings.info_key_log_channel_lifecycle", logs.isChannelLifecycleLogEnabled()),
                moduleLine(lang, "settings.info_key_log_role", logs.isRoleLogEnabled()),
                moduleLine(lang, "settings.info_key_log_moderation", logs.isModerationLogEnabled()),
                "",
                "**" + owner.i18nService().t(lang, "settings.module_section_music_others") + "**",
                moduleLine(lang, "settings.key_music_autoLeaveEnabled", music.isAutoLeaveEnabled()),
                moduleLine(lang, "settings.key_music_autoplayEnabled", music.isAutoplayEnabled()),
                moduleLine(lang, "settings.key_numberChain_enabled", owner.moderationService().isNumberChainEnabled(guildId)),
                moduleLine(lang, "settings.key_ticket_enabled", settings.getTicket().isEnabled()),
                line(lang, "settings.key_ticket_maxOpenPerUser", String.valueOf(settings.getTicket().getMaxOpenPerUser())),
                line(lang, "settings.key_ticket_blacklistUserIds", String.valueOf(settings.getTicket().getBlacklistedUserIds().size())),
                moduleLine(lang, "settings.key_privateRoom_enabled", room.isEnabled())
        );

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(26, 188, 156))
                .setTitle("\u2699\uFE0F " + owner.i18nService().t(lang, "settings.info_title"))
                .setDescription(owner.i18nService().t(lang, "settings.info_desc") + "\n`" + guild.getName() + "`")
                .setTimestamp(Instant.now());

        switch (currentSection) {
            case "notifications" -> eb.addField(infoSectionTitle(lang, "settings.info_notifications"), notifications, false);
            case "templates" -> eb.addField(infoSectionTitle(lang, "settings.info_notification_templates"), notificationTemplates, false);
            case "logs" -> eb.addField(infoSectionTitle(lang, "settings.info_message_logs"), messageLogs, false);
            case ROUTE_MUSIC -> eb.addField(infoSectionTitle(lang, "settings.info_music"), musicInfo, false);
            case "private-room" -> eb.addField(infoSectionTitle(lang, "settings.info_private_room"), privateRoom, false);
            case ROUTE_NUMBER_CHAIN -> eb.addField(infoSectionTitle(lang, "settings.info_number_chain"), numberChainInfo, false);
            case ROUTE_MODULE -> eb.addField(infoSectionTitle(lang, "settings.info_module"), moduleInfo, false);
            default -> eb.addField(infoSectionTitle(lang, "settings.info_notifications"), notifications, false);
        }
        return eb;
    }

    public StringSelectMenu settingsInfoMenu(String lang, String selected) {
        String current = selected == null || selected.isBlank() ? "notifications" : selected;
        return StringSelectMenu.create(SETTINGS_INFO_SELECT_ID)
                .setPlaceholder(owner.i18nService().t(lang, "settings.info_select_placeholder"))
                .addOptions(
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_notifications"), "notifications").withDefault("notifications".equals(current)),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_notification_templates"), "templates").withDefault("templates".equals(current)),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_message_logs"), "logs").withDefault("logs".equals(current)),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_music"), ROUTE_MUSIC).withDefault(ROUTE_MUSIC.equals(current)),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_private_room"), "private-room").withDefault("private-room".equals(current)),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_number_chain"), ROUTE_NUMBER_CHAIN).withDefault(ROUTE_NUMBER_CHAIN.equals(current)),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_module"), ROUTE_MODULE).withDefault(ROUTE_MODULE.equals(current))
                )
                .build();
    }

    public List<Button> settingsInfoButtons(String lang, String selected, int rowIndex) {
        String current = selected == null || selected.isBlank() ? "notifications" : selected;
        List<Button> row0 = List.of(
                infoSectionButton(lang, "notifications", current, "settings.info_notifications"),
                infoSectionButton(lang, "templates", current, "settings.info_notification_templates"),
                infoSectionButton(lang, "logs", current, "settings.info_message_logs"),
                infoSectionButton(lang, ROUTE_MUSIC, current, "settings.info_music")
        );
        List<Button> row1 = List.of(
                infoSectionButton(lang, "private-room", current, "settings.info_private_room"),
                infoSectionButton(lang, ROUTE_NUMBER_CHAIN, current, "settings.info_number_chain"),
                infoSectionButton(lang, ROUTE_MODULE, current, "settings.info_module")
        );
        return rowIndex == 0 ? row0 : row1;
    }

    private Button infoSectionButton(String lang, String value, String current, String labelKey) {
        String id = SETTINGS_INFO_BUTTON_PREFIX + value;
        String label = owner.i18nService().t(lang, labelKey);
        if (value.equals(current)) {
            return Button.primary(id, safe(label, 80)).asDisabled();
        }
        return Button.secondary(id, safe(label, 80));
    }

    private String moduleLine(String lang, String key, boolean value) {
        return keyIcon(key) + " " + owner.i18nService().t(lang, key) + ": " + moduleSwitchTextCode(lang, value);
    }

    private String moduleSwitchTextCode(String lang, boolean enabled) {
        return "`" + moduleSwitchTextPlain(lang, enabled) + "`";
    }

    private String moduleSwitchTextPlain(String lang, boolean enabled) {
        String state = moduleSwitchState(lang, enabled);
        return enabled ? "\uD83D\uDFE2 " + state : "\u26AA " + state;
    }

    private String moduleSwitchState(String lang, boolean enabled) {
        return owner.boolText(lang, enabled)
                .replace("\u2705", "")
                .replace("\u274C", "")
                .replace("\u2714\uFE0F", "")
                .replace("\u2716\uFE0F", "")
                .trim();
    }

    private String formatColor(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    private String formatTextChannelInfo(Guild guild, Long id) {
        if (id == null) {
            return owner.i18nService().t(owner.lang(guild.getIdLong()), "settings.info_channels_none");
        }
        TextChannel channel = guild.getTextChannelById(id);
        return channel == null ? "#" + id : channel.getAsMention();
    }

    private String formatVoiceChannelInfo(Guild guild, Long id) {
        if (id == null) {
            return owner.i18nService().t(owner.lang(guild.getIdLong()), "settings.info_channels_none");
        }
        AudioChannel channel = guild.getVoiceChannelById(id);
        if (channel == null) {
            channel = guild.getStageChannelById(id);
        }
        return channel == null ? "#" + id : "<#" + id + ">";
    }

    private String formatMemberChannelMode(String lang, Long memberJoinChannelId, Long memberLeaveChannelId) {
        boolean split = memberJoinChannelId != null || memberLeaveChannelId != null;
        return split ? owner.i18nService().t(lang, "settings.member_channel_mode_split")
                : owner.i18nService().t(lang, "settings.member_channel_mode_same");
    }

    private String resolveTriggerCategoryWithSource(Guild guild, Long triggerVoiceChannelId) {
        if (triggerVoiceChannelId == null) {
            return owner.i18nService().t(owner.lang(guild.getIdLong()), "settings.info_channels_none");
        }
        AudioChannel trigger = guild.getVoiceChannelById(triggerVoiceChannelId);
        if (trigger == null) {
            trigger = guild.getStageChannelById(triggerVoiceChannelId);
        }
        if (!(trigger instanceof ICategorizableChannel)) {
            return "<#" + triggerVoiceChannelId + ">";
        }
        ICategorizableChannel categorizable = (ICategorizableChannel) trigger;
        Category parent = categorizable.getParentCategory();
        if (parent == null) {
            return "<#" + triggerVoiceChannelId + "> -> "
                    + owner.i18nService().t(owner.lang(guild.getIdLong()), "settings.info_channels_none");
        }
        return "<#" + triggerVoiceChannelId + "> -> " + parent.getName() + " (" + parent.getId() + ")";
    }

    private String templateCompareMarkdown(String lang, String titleKey, String effective) {
        String effectiveText = trimTemplate(effective);
        return "**" + owner.i18nService().t(lang, titleKey) + "**\n`" + effectiveText + "`";
    }

    private String trimTemplate(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return safe(value.replace("\n", "\\n"), 180);
    }

    private String line(String lang, String key, String value) {
        String icon = keyIcon(key);
        return icon + " " + owner.i18nService().t(lang, key) + ": " + value;
    }

    private String lineLabel(String icon, String label, String value) {
        return icon + " " + label + ": " + value;
    }

    private String keyIcon(String key) {
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

    private String ignoredRolesInfoLabel(String lang) {
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "\u5ffd\u7565\u7684\u8eab\u5206\u7ec4";
        }
        if (lang != null && lang.toLowerCase().startsWith("zh")) {
            return "\u5ffd\u7565\u7684\u8eab\u5206\u7d44";
        }
        return "Ignored Roles";
    }

    private String formatIgnoredMembersInfo(String lang, List<Long> ids) {
        return formatCompactList(lang, ids == null ? List.of() : ids.stream()
                .map(id -> "<@" + id + ">")
                .toList());
    }

    private String formatIgnoredRolesInfo(String lang, List<Long> ids) {
        return formatCompactList(lang, ids == null ? List.of() : ids.stream()
                .map(id -> "<@&" + id + ">")
                .toList());
    }

    private String formatIgnoredChannelsInfo(String lang, List<Long> ids) {
        return formatCompactList(lang, ids == null ? List.of() : ids.stream()
                .map(id -> "<#" + id + ">")
                .toList());
    }

    private String formatIgnoredPrefixesInfo(String lang, List<String> prefixes) {
        return formatCompactList(lang, prefixes == null ? List.of() : prefixes.stream()
                .map(prefix -> "`" + prefix.replace("`", "'") + "`")
                .toList());
    }

    private String formatCompactList(String lang, List<String> values) {
        if (values == null || values.isEmpty()) {
            return owner.i18nService().t(lang, "settings.info_channels_none");
        }
        int limit = Math.min(5, values.size());
        String result = String.join(", ", values.subList(0, limit));
        if (values.size() > limit) {
            result += " +" + (values.size() - limit);
        }
        return result;
    }

    private String infoSectionTitle(String lang, String key) {
        return sectionIcon(key) + " " + owner.i18nService().t(lang, key);
    }

    private String sectionIcon(String key) {
        return switch (key) {
            case "settings.info_section_overview" -> "\uD83D\uDCCC";
            case "settings.info_notifications" -> "\uD83D\uDD14";
            case "settings.info_notification_templates" -> "\uD83E\uDDE9";
            case "settings.info_message_logs" -> "\uD83D\uDDD2\uFE0F";
            case "settings.info_music" -> "\uD83C\uDFB5";
            case "settings.info_private_room" -> "\uD83C\uDFE0";
            case "settings.info_number_chain" -> "\u0031\u20E3";
            case "settings.info_module" -> "\uD83E\uDDF0";
            default -> "\uD83D\uDCC4";
        };
    }

    private String joinInfoBlocks(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private String joinLines(String... values) {
        return String.join("\n", values);
    }

    private String compare(String effective) {
        return safe(effective, 160);
    }

    private String safe(String s, int max) {
        if (s == null) {
            return "-";
        }
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
