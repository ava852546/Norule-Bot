package com.norule.musicbot.discord.bot.gateway.command.settings.menu;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.CommandNames;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SettingsLogsMenuHandler {
    private static final String SETTINGS_LOGS_SELECT_PREFIX       = ComponentIds.SETTINGS_LOGS_SELECT_PREFIX;
    private static final String SETTINGS_LOGS_CHANNEL_PREFIX      = ComponentIds.SETTINGS_LOGS_CHANNEL_PREFIX;
    private static final String SETTINGS_LOGS_MEMBER_MODE_PREFIX  = ComponentIds.SETTINGS_LOGS_MEMBER_MODE_PREFIX;
    private static final String SETTINGS_LOGS_MEMBER_SPLIT_PREFIX = ComponentIds.SETTINGS_LOGS_MEMBER_SPLIT_PREFIX;

    private static final String KEY_UNKNOWN_COMMAND       = "general.unknown_command";
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";
    private static final String OPTION_VALUE   = CommandOptions.VALUE;
    private static final String OPTION_CHANNEL = CommandOptions.CHANNEL;
    private static final String CMD_LEAVE      = CommandNames.CMD_LEAVE;
    private static final String ROUTE_MODULE   = "module";

    private final MusicCommandService owner;
    private final Map<String, MenuRequest> logsMenuRequests = new ConcurrentHashMap<>();

    public SettingsLogsMenuHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        logsMenuRequests.entrySet().removeIf(e -> e.getValue() == null || cutoff.isAfter(e.getValue().expiresAt));
    }

    public void openLogsMenu(SlashCommandInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle(owner.i18nService().t(lang, "settings.logs_menu_title"))
                        .setDescription(owner.i18nService().t(lang, "settings.logs_menu_desc"))
                        .build())
                .addComponents(ActionRow.of(settingsLogsMenu(token, event.getGuild(), lang)))
                .setEphemeral(true)
                .queue();
    }

    public void openLogsMenu(StringSelectInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle(owner.i18nService().t(lang, "settings.logs_menu_title"))
                        .setDescription(owner.i18nService().t(lang, "settings.logs_menu_desc"))
                        .build())
                .setComponents(ActionRow.of(settingsLogsMenu(token, event.getGuild(), lang)))
                .queue();
    }

    private StringSelectMenu settingsLogsMenu(String token, Guild guild, String lang) {
        long guildId = guild.getIdLong();
        return StringSelectMenu.create(SETTINGS_LOGS_SELECT_PREFIX + token)
                .setPlaceholder(owner.i18nService().t(lang, "settings.logs_menu_placeholder"))
                .addOptions(
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_key_log_channel"), "default-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "default-channel")),
                        SelectOption.of(owner.i18nService().t(lang, "settings.key_messageLogs_messageLogChannelId"), "messages-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "messages-channel")),
                        SelectOption.of(owner.i18nService().t(lang, "settings.key_notifications_memberChannelId"), "member-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "member-channel")),
                        SelectOption.of(owner.i18nService().t(lang, "settings.key_notifications_voiceChannelId"), "voice-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "voice-channel")),
                        SelectOption.of(owner.i18nService().t(lang, "settings.key_messageLogs_commandUsageChannelId"), "command-usage-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "command-usage-channel")),
                        SelectOption.of(owner.i18nService().t(lang, "settings.key_messageLogs_channelLifecycleChannelId"), "channel-events-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "channel-events-channel")),
                        SelectOption.of(owner.i18nService().t(lang, "settings.key_messageLogs_roleLogChannelId"), "role-events-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "role-events-channel")),
                        SelectOption.of(owner.i18nService().t(lang, "settings.key_messageLogs_moderationLogChannelId"), "moderation-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "moderation-channel"))
                )
                .build();
    }

    public void handleLogsMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_LOGS_SELECT_PREFIX.length());
        MenuRequest request = logsMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            logsMenuRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "settings.logs_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }
        String target = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        if (target.isBlank()) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }

        if ("member-channel".equals(target)) {
            var notifications = owner.settingsService().getNotifications(event.getGuild().getIdLong());
            boolean split = notifications.getMemberJoinChannelId() != null || notifications.getMemberLeaveChannelId() != null;
            if (!split) {
                openSharedMemberChannelPicker(event, token, lang);
                return;
            }
            event.editMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(241, 196, 15))
                            .setTitle(owner.i18nService().t(lang, "settings.logs_member_mode_title"))
                            .setDescription(owner.i18nService().t(lang, "settings.logs_member_mode_desc"))
                            .build())
                    .setComponents(ActionRow.of(settingsMemberChannelModeMenu(token, lang)))
                    .queue();
            return;
        }

        String channelComponentId = SETTINGS_LOGS_CHANNEL_PREFIX + token + ":" + target;
        EntitySelectMenu channelMenu = EntitySelectMenu.create(channelComponentId, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder(owner.i18nService().t(lang, "settings.logs_menu_channel_placeholder"))
                .setRequiredRange(1, 1)
                .build();
        String key = logsTargetKey(target);
        String keyText = key == null ? target : owner.i18nService().t(lang, key);
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle(owner.i18nService().t(lang, "settings.logs_menu_pick_channel_title"))
                        .setDescription(owner.i18nService().t(lang, "settings.logs_menu_pick_channel_desc", Map.of("target", keyText)))
                        .build())
                .setComponents(ActionRow.of(channelMenu))
                .queue();
    }

    private StringSelectMenu settingsMemberChannelModeMenu(String token, String lang) {
        return StringSelectMenu.create(SETTINGS_LOGS_MEMBER_MODE_PREFIX + token)
                .setPlaceholder(owner.i18nService().t(lang, "settings.logs_member_mode_placeholder"))
                .addOptions(
                        SelectOption.of(owner.i18nService().t(lang, "settings.logs_member_mode_shared"), "member-channel-shared"),
                        SelectOption.of(owner.i18nService().t(lang, "settings.logs_member_mode_split"), "member-channel-split")
                )
                .build();
    }

    private StringSelectMenu settingsMemberSplitMenu(String token, Guild guild, String lang) {
        var n = owner.settingsService().getNotifications(guild.getIdLong());
        String joinValue  = safe(formatTextChannel(guild, n.getMemberJoinChannelId(), lang), 80);
        String leaveValue = safe(formatTextChannel(guild, n.getMemberLeaveChannelId(), lang), 80);
        return StringSelectMenu.create(SETTINGS_LOGS_MEMBER_SPLIT_PREFIX + token)
                .setPlaceholder(owner.i18nService().t(lang, "settings.logs_member_split_placeholder"))
                .addOptions(
                        SelectOption.of(owner.i18nService().t(lang, "settings.logs_member_split_join"), "member-join-channel")
                                .withDescription(owner.i18nService().t(lang, "settings.music_menu_current", Map.of(OPTION_VALUE, joinValue))),
                        SelectOption.of(owner.i18nService().t(lang, "settings.logs_member_split_leave"), "member-leave-channel")
                                .withDescription(owner.i18nService().t(lang, "settings.music_menu_current", Map.of(OPTION_VALUE, leaveValue)))
                )
                .build();
    }

    public void handleLogsMemberModeSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_LOGS_MEMBER_MODE_PREFIX.length());
        MenuRequest request = logsMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            logsMenuRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "settings.logs_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }
        String mode = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        if ("member-channel-shared".equals(mode)) {
            openSharedMemberChannelPicker(event, token, lang);
            return;
        }
        if ("member-channel-split".equals(mode)) {
            event.editMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(241, 196, 15))
                            .setTitle(owner.i18nService().t(lang, "settings.logs_member_split_title"))
                            .setDescription(owner.i18nService().t(lang, "settings.logs_member_split_desc"))
                            .build())
                    .setComponents(ActionRow.of(settingsMemberSplitMenu(token, event.getGuild(), lang)))
                    .queue();
            return;
        }
        event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
    }

    private void openSharedMemberChannelPicker(StringSelectInteractionEvent event, String token, String lang) {
        String channelComponentId = SETTINGS_LOGS_CHANNEL_PREFIX + token + ":member-channel-shared";
        EntitySelectMenu channelMenu = EntitySelectMenu.create(channelComponentId, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder(owner.i18nService().t(lang, "settings.logs_menu_channel_placeholder"))
                .setRequiredRange(1, 1)
                .build();
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle(owner.i18nService().t(lang, "settings.logs_menu_pick_channel_title"))
                        .setDescription(owner.i18nService().t(lang, "settings.logs_menu_pick_channel_desc",
                                Map.of("target", owner.i18nService().t(lang, "settings.logs_member_mode_shared"))))
                        .build())
                .setComponents(ActionRow.of(channelMenu))
                .queue();
    }

    public void handleLogsMemberSplitSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_LOGS_MEMBER_SPLIT_PREFIX.length());
        MenuRequest request = logsMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            logsMenuRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "settings.logs_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }
        String target = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        if (!"member-join-channel".equals(target) && !"member-leave-channel".equals(target)) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String channelComponentId = SETTINGS_LOGS_CHANNEL_PREFIX + token + ":" + target;
        EntitySelectMenu channelMenu = EntitySelectMenu.create(channelComponentId, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder(owner.i18nService().t(lang, "settings.logs_menu_channel_placeholder"))
                .setRequiredRange(1, 1)
                .build();
        String targetText = "member-join-channel".equals(target)
                ? owner.i18nService().t(lang, "settings.logs_member_split_join")
                : owner.i18nService().t(lang, "settings.logs_member_split_leave");
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle(owner.i18nService().t(lang, "settings.logs_menu_pick_channel_title"))
                        .setDescription(owner.i18nService().t(lang, "settings.logs_menu_pick_channel_desc", Map.of("target", targetText)))
                        .build())
                .setComponents(ActionRow.of(channelMenu))
                .queue();
    }

    public void handleLogsChannelSelect(EntitySelectInteractionEvent event, String lang) {
        String suffix = event.getComponentId().substring(SETTINGS_LOGS_CHANNEL_PREFIX.length());
        int idx = suffix.indexOf(':');
        if (idx <= 0 || idx >= suffix.length() - 1) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String token  = suffix.substring(0, idx);
        String target = suffix.substring(idx + 1);

        MenuRequest request = logsMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            logsMenuRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "settings.logs_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }

        List<TextChannel> channels = event.getMentions().getChannels(TextChannel.class);
        if (channels.isEmpty()) {
            event.reply(owner.i18nService().t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
            return;
        }
        GuildChannel selected = channels.get(0);
        if (!(selected instanceof TextChannel textChannel)) {
            event.reply(owner.i18nService().t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
            return;
        }
        String missing = formatMissingPermissions(event.getGuild().getSelfMember(), textChannel,
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
        if (!"-".equals(missing)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions", Map.of("permissions", missing)))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        switch (target) {
            case "default-channel" ->
                    owner.settingsService().updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withChannelId(textChannel.getIdLong())));
            case "messages-channel" ->
                    owner.settingsService().updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withMessageLogChannelId(textChannel.getIdLong())));
            case "member-channel-shared" ->
                    owner.settingsService().updateSettings(guildId, s -> s.withNotifications(s.getNotifications()
                            .withMemberChannelId(textChannel.getIdLong())
                            .withMemberJoinChannelId(null)
                            .withMemberLeaveChannelId(null)));
            case "member-join-channel" ->
                    owner.settingsService().updateSettings(guildId, s -> s.withNotifications(s.getNotifications()
                            .withMemberChannelId(null)
                            .withMemberJoinChannelId(textChannel.getIdLong())));
            case "member-leave-channel" ->
                    owner.settingsService().updateSettings(guildId, s -> s.withNotifications(s.getNotifications()
                            .withMemberChannelId(null)
                            .withMemberLeaveChannelId(textChannel.getIdLong())));
            case "voice-channel" ->
                    owner.settingsService().updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withVoiceChannelId(textChannel.getIdLong())));
            case "command-usage-channel" ->
                    owner.settingsService().updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withCommandUsageChannelId(textChannel.getIdLong())));
            case "channel-events-channel" ->
                    owner.settingsService().updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withChannelLifecycleChannelId(textChannel.getIdLong())));
            case "role-events-channel" ->
                    owner.settingsService().updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withRoleLogChannelId(textChannel.getIdLong())));
            case "moderation-channel" ->
                    owner.settingsService().updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withModerationLogChannelId(textChannel.getIdLong())));
            default -> {
                event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
                return;
            }
        }

        String key     = logsTargetKey(target);
        String keyText = key == null ? target : owner.i18nService().t(lang, key);
        String savedText = owner.i18nService().t(lang, "general.settings_saved",
                Map.of("key", keyText, OPTION_VALUE, textChannel.getAsMention()));

        if ("member-join-channel".equals(target) || "member-leave-channel".equals(target)) {
            event.editMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(46, 204, 113))
                            .setTitle(owner.i18nService().t(lang, "settings.logs_member_split_title"))
                            .setDescription(savedText)
                            .build())
                    .setComponents(ActionRow.of(settingsMemberSplitMenu(token, event.getGuild(), lang)))
                    .queue();
            return;
        }
        if ("member-channel-shared".equals(target)) {
            event.editMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(46, 204, 113))
                            .setTitle(owner.i18nService().t(lang, "settings.logs_member_mode_title"))
                            .setDescription(savedText)
                            .build())
                    .setComponents(ActionRow.of(settingsMemberChannelModeMenu(token, lang)))
                    .queue();
            return;
        }
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(owner.i18nService().t(lang, "settings.logs_menu_title"))
                        .setDescription(savedText)
                        .build())
                .setComponents(ActionRow.of(settingsLogsMenu(token, event.getGuild(), lang)))
                .queue();
    }

    private String logsTargetKey(String target) {
        return switch (target) {
            case "default-channel"       -> "settings.info_key_log_channel";
            case "messages-channel"      -> "settings.key_messageLogs_messageLogChannelId";
            case "member-channel",
                 "member-channel-shared" -> "settings.key_notifications_memberChannelId";
            case "member-join-channel"   -> "settings.info_key_member_join_channel";
            case "member-leave-channel"  -> "settings.info_key_member_leave_channel";
            case "voice-channel"         -> "settings.key_notifications_voiceChannelId";
            case "command-usage-channel" -> "settings.key_messageLogs_commandUsageChannelId";
            case "channel-events-channel"-> "settings.key_messageLogs_channelLifecycleChannelId";
            case "role-events-channel"   -> "settings.key_messageLogs_roleLogChannelId";
            case "moderation-channel"    -> "settings.key_messageLogs_moderationLogChannelId";
            default -> null;
        };
    }

    private String logsModuleStatusText(String lang, long guildId, String target) {
        var s    = owner.settingsService().getSettings(guildId);
        var logs = s.getMessageLogs();
        var n    = s.getNotifications();
        String module = switch (target) {
            case "default-channel"        -> owner.i18nService().t(lang, "settings.logs_menu_module_default");
            case "messages-channel"       -> owner.i18nService().t(lang, "settings.logs_menu_module_state",
                    Map.of("state", boolText(lang, logs.isEnabled())));
            case "member-channel"         -> owner.i18nService().t(lang, "settings.logs_menu_module_member_state",
                    Map.of("join", boolText(lang, n.isMemberJoinEnabled()), CMD_LEAVE, boolText(lang, n.isMemberLeaveEnabled())));
            case "voice-channel"          -> owner.i18nService().t(lang, "settings.logs_menu_module_state",
                    Map.of("state", boolText(lang, n.isVoiceLogEnabled())));
            case "command-usage-channel"  -> owner.i18nService().t(lang, "settings.logs_menu_module_state",
                    Map.of("state", boolText(lang, logs.isCommandUsageLogEnabled())));
            case "channel-events-channel" -> owner.i18nService().t(lang, "settings.logs_menu_module_state",
                    Map.of("state", boolText(lang, logs.isChannelLifecycleLogEnabled())));
            case "role-events-channel"    -> owner.i18nService().t(lang, "settings.logs_menu_module_state",
                    Map.of("state", boolText(lang, logs.isRoleLogEnabled())));
            case "moderation-channel"     -> owner.i18nService().t(lang, "settings.logs_menu_module_state",
                    Map.of("state", boolText(lang, logs.isModerationLogEnabled())));
            default -> owner.i18nService().t(lang, "settings.logs_menu_module_none");
        };

        String channel = switch (target) {
            case "default-channel"        -> owner.i18nService().t(lang, "settings.logs_menu_channel_state",
                    Map.of("state", setStateText(lang, logs.getChannelId() != null)));
            case "messages-channel"       -> owner.i18nService().t(lang, "settings.logs_menu_channel_state",
                    Map.of("state", setStateText(lang, logs.getMessageLogChannelId() != null)));
            case "member-channel" -> {
                boolean split = n.getMemberJoinChannelId() != null || n.getMemberLeaveChannelId() != null;
                if (split) {
                    yield owner.i18nService().t(lang, "settings.logs_menu_channel_member_split",
                            Map.of("join",    setStateText(lang, n.getMemberJoinChannelId() != null),
                                   CMD_LEAVE, setStateText(lang, n.getMemberLeaveChannelId() != null)));
                }
                yield owner.i18nService().t(lang, "settings.logs_menu_channel_state",
                        Map.of("state", setStateText(lang, n.getMemberChannelId() != null)));
            }
            case "voice-channel"          -> owner.i18nService().t(lang, "settings.logs_menu_channel_state",
                    Map.of("state", setStateText(lang, n.getVoiceChannelId() != null)));
            case "command-usage-channel"  -> owner.i18nService().t(lang, "settings.logs_menu_channel_state",
                    Map.of("state", setStateText(lang, logs.getCommandUsageChannelId() != null)));
            case "channel-events-channel" -> owner.i18nService().t(lang, "settings.logs_menu_channel_state",
                    Map.of("state", setStateText(lang, logs.getChannelLifecycleChannelId() != null)));
            case "role-events-channel"    -> owner.i18nService().t(lang, "settings.logs_menu_channel_state",
                    Map.of("state", setStateText(lang, logs.getRoleLogChannelId() != null)));
            case "moderation-channel"     -> owner.i18nService().t(lang, "settings.logs_menu_channel_state",
                    Map.of("state", setStateText(lang, logs.getModerationLogChannelId() != null)));
            default -> owner.i18nService().t(lang, "settings.logs_menu_channel_state",
                    Map.of("state", setStateText(lang, false)));
        };

        return owner.i18nService().t(lang, "settings.logs_menu_status_format",
                Map.of(ROUTE_MODULE, module, OPTION_CHANNEL, channel));
    }

    private String setStateText(String lang, boolean set) {
        return owner.i18nService().t(lang, set ? "settings.logs_menu_channel_set" : "settings.logs_menu_channel_unset");
    }

    private String boolText(String lang, boolean value) {
        return owner.i18nService().t(lang, value ? "settings.info_bool_on" : "settings.info_bool_off");
    }

    private String formatTextChannel(Guild guild, Long id, String lang) {
        if (id == null) {
            return owner.i18nService().t(lang, "settings.info_channels_none");
        }
        TextChannel channel = guild.getTextChannelById(id);
        return channel == null ? "#" + id : channel.getAsMention() + " (" + id + ")";
    }

    private static String formatMissingPermissions(Member member, GuildChannel channel, Permission... permissions) {
        EnumSet<Permission> missing = EnumSet.noneOf(Permission.class);
        for (Permission p : permissions) {
            if (!member.hasPermission(channel, p)) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            return "-";
        }
        List<String> names = new ArrayList<>();
        for (Permission p : missing) {
            names.add(p.getName());
        }
        return String.join(", ", names);
    }

    private static String safe(String s, int max) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1);
    }

    private String registerMenuRequest(long requestUserId, long guildId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        logsMenuRequests.put(token, new MenuRequest(requestUserId, guildId, Instant.now().plusSeconds(120)));
        return token;
    }

    private static final class MenuRequest {
        final long requestUserId;
        final long guildId;
        final Instant expiresAt;

        MenuRequest(long requestUserId, long guildId, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.expiresAt = expiresAt;
        }
    }
}
