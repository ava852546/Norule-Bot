package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.web.*;

import com.norule.musicbot.*;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class SettingsCommandHandler {
    private static final String LOG_SETTINGS_SELECT_PREFIX = "settings:log-settings:select:";
    private static final String LOG_SETTINGS_MEMBER_PREFIX = "settings:log-settings:member:";
    private static final String LOG_SETTINGS_ROLE_PREFIX = "settings:log-settings:role:";
    private static final String LOG_SETTINGS_CHANNEL_PREFIX = "settings:log-settings:channel:";
    private static final String LOG_SETTINGS_PREFIX_MODAL_PREFIX = "settings:log-settings:prefix:";
    private static final String LOG_SETTINGS_REMOVE_MEMBER_PREFIX = "settings:log-settings:remove-member:";
    private static final String LOG_SETTINGS_REMOVE_ROLE_PREFIX = "settings:log-settings:remove-role:";
    private static final String LOG_SETTINGS_REMOVE_CHANNEL_PREFIX = "settings:log-settings:remove-channel:";
    private static final String LOG_SETTINGS_REMOVE_PREFIX_MODAL_PREFIX = "settings:log-settings:remove-prefix:";

    private final MusicCommandListener owner;
    private final Map<String, LogSettingsRequest> logSettingsRequests = new ConcurrentHashMap<>();

    SettingsCommandHandler(MusicCommandListener owner) {
        this.owner = owner;
    }

    void handleSettings(SlashCommandInteractionEvent event, String lang) {
        if (!owner.has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName()))).setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        String sub = event.getSubcommandName();
        if ((sub == null || sub.isBlank()) && event.getOption("action") != null) {
            sub = event.getOption("action").getAsString();
        }
        if (sub == null || sub.isBlank()) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        sub = owner.canonicalSettingsSubcommand(sub);
        String group = event.getSubcommandGroup();
        String route = group == null ? sub : group + ":" + sub;
        String validationError = owner.validateSettingsActionOptions(event, route, lang);
        if (validationError != null) {
            event.reply(validationError).setEphemeral(true).queue();
            return;
        }
        switch (route) {
            case "info" -> event.replyEmbeds(owner.settingsInfoEmbed(event.getGuild(), lang, "notifications").build())
                    .addComponents(
                            ActionRow.of(owner.settingsInfoMenu(lang, "notifications")),
                            ActionRow.of(owner.settingsInfoButtons(lang, "notifications", 0)),
                            ActionRow.of(owner.settingsInfoButtons(lang, "notifications", 1))
                    )
                    .setEphemeral(true)
                    .queue();
            case "reload" -> {
                owner.settingsService().reload(guildId);
                owner.moderationService().reload(guildId);
                event.replyEmbeds(new EmbedBuilder()
                                .setColor(new Color(46, 204, 113))
                                .setTitle(owner.i18nService().t(lang, "settings.info_title"))
                                .setDescription("\u2705 " + owner.i18nService().t(lang, "settings.reload_done"))
                                .build())
                        .setEphemeral(true)
                        .queue();
            }
            case "language" -> owner.openLanguageMenu(event, lang);
            case "template" -> owner.openTemplateMenu(event, lang);
            case "module" -> owner.openModuleMenu(event, lang);
            case "reset" -> owner.openSettingsResetMenu(event, lang);
            case "logs" -> owner.openLogsMenu(event, lang);
            case "log-settings" -> openLogSettingsMenu(event, lang);
            case "music" -> owner.openMusicMenu(event, lang);
            case "number-chain" -> owner.openNumberChainMenu(event, lang);
            default -> event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }

    private void openLogSettingsMenu(SlashCommandInteractionEvent event, String lang) {
        String token = Long.toHexString(System.nanoTime());
        logSettingsRequests.put(token, new LogSettingsRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(52, 152, 219))
                        .setTitle(owner.i18nService().t(lang, "settings.log_settings.title"))
                        .setDescription(owner.i18nService().t(lang, "settings.log_settings.menu_desc"))
                        .build())
                .addComponents(ActionRow.of(logSettingsMenu(token, lang)))
                .setEphemeral(true)
                .queue();
    }

    private StringSelectMenu logSettingsMenu(String token, String lang) {
        return StringSelectMenu.create(LOG_SETTINGS_SELECT_PREFIX + token)
                .setPlaceholder(owner.i18nService().t(lang, "settings.log_settings.menu_placeholder"))
                .addOptions(
                        SelectOption.of(owner.i18nService().t(lang, "settings.log_settings.ignore_prefix"), "ignore-prefix"),
                        SelectOption.of(logSettingsRemovePrefixLabel(lang), "remove-prefix"),
                        SelectOption.of(owner.i18nService().t(lang, "settings.log_settings.ignore_member"), "ignore-member"),
                        SelectOption.of(logSettingsRemoveMemberLabel(lang), "remove-member"),
                        SelectOption.of(logSettingsIgnoreRoleLabel(lang), "ignore-role"),
                        SelectOption.of(logSettingsRemoveRoleLabel(lang), "remove-role"),
                        SelectOption.of(owner.i18nService().t(lang, "settings.log_settings.ignore_channel"), "ignore-channel"),
                        SelectOption.of(logSettingsRemoveChannelLabel(lang), "remove-channel"),
                        SelectOption.of(owner.i18nService().t(lang, "settings.log_settings.view_ignore"), "view-ignore")
                )
                .build();
    }

    private LogSettingsRequest requireLogSettingsRequest(String token, long guildId, long userId, String lang, java.util.function.Consumer<String> reply) {
        LogSettingsRequest request = logSettingsRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            logSettingsRequests.remove(token);
            reply.accept(owner.i18nService().t(lang, "settings.log_settings.menu_expired"));
            return null;
        }
        if (request.guildId != guildId || request.requestUserId != userId) {
            reply.accept(owner.i18nService().t(lang, "delete.only_requester"));
            return null;
        }
        return request;
    }

    private void handleIgnoredMemberSelect(EntitySelectInteractionEvent event, String token, String lang) {
        if (event.getMentions().getUsers().isEmpty()) {
            event.reply(owner.i18nService().t(lang, "settings.log_settings.user_required")).setEphemeral(true).queue();
            return;
        }
        User user = event.getMentions().getUsers().get(0);
        BotConfig.MessageLogs logs = owner.settingsService().getMessageLogs(event.getGuild().getIdLong());
        List<Long> updated = new ArrayList<>(logs.getIgnoredMemberIds());
        if (!updated.contains(user.getIdLong())) {
            updated.add(user.getIdLong());
        }
        owner.settingsService().updateSettings(event.getGuild().getIdLong(),
                settings -> settings.withMessageLogs(settings.getMessageLogs().withIgnoredMemberIds(updated)));
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(owner.i18nService().t(lang, "settings.log_settings.title"))
                        .setDescription(owner.i18nService().t(lang, "general.settings_saved",
                                Map.of(
                                        "key", owner.i18nService().t(lang, "settings.log_settings.ignore_member"),
                                        "value", user.getAsMention()
                                )))
                        .build())
                .setComponents(ActionRow.of(logSettingsMenu(token, lang)))
                .queue();
    }

    private void handleIgnoredChannelSelect(EntitySelectInteractionEvent event, String token, String lang) {
        List<TextChannel> channels = event.getMentions().getChannels(TextChannel.class);
        if (channels.isEmpty()) {
            event.reply(owner.i18nService().t(lang, "settings.log_settings.channel_required")).setEphemeral(true).queue();
            return;
        }
        TextChannel channel = channels.get(0);
        BotConfig.MessageLogs logs = owner.settingsService().getMessageLogs(event.getGuild().getIdLong());
        List<Long> updated = new ArrayList<>(logs.getIgnoredChannelIds());
        if (!updated.contains(channel.getIdLong())) {
            updated.add(channel.getIdLong());
        }
        owner.settingsService().updateSettings(event.getGuild().getIdLong(),
                settings -> settings.withMessageLogs(settings.getMessageLogs().withIgnoredChannelIds(updated)));
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(owner.i18nService().t(lang, "settings.log_settings.title"))
                        .setDescription(owner.i18nService().t(lang, "general.settings_saved",
                                Map.of(
                                        "key", owner.i18nService().t(lang, "settings.log_settings.ignore_channel"),
                                        "value", channel.getAsMention()
                                )))
                        .build())
                .setComponents(ActionRow.of(logSettingsMenu(token, lang)))
                .queue();
    }

    private void handleIgnoredRoleSelect(EntitySelectInteractionEvent event, String token, String lang) {
        if (event.getMentions().getRoles().isEmpty()) {
            event.reply(logSettingsRoleRequiredText(lang)).setEphemeral(true).queue();
            return;
        }
        Role role = event.getMentions().getRoles().get(0);
        BotConfig.MessageLogs logs = owner.settingsService().getMessageLogs(event.getGuild().getIdLong());
        List<Long> updated = new ArrayList<>(logs.getIgnoredRoleIds());
        if (!updated.contains(role.getIdLong())) {
            updated.add(role.getIdLong());
        }
        owner.settingsService().updateSettings(event.getGuild().getIdLong(),
                settings -> settings.withMessageLogs(settings.getMessageLogs().withIgnoredRoleIds(updated)));
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(owner.i18nService().t(lang, "settings.log_settings.title"))
                        .setDescription(owner.i18nService().t(lang, "general.settings_saved",
                                Map.of(
                                        "key", logSettingsIgnoreRoleLabel(lang),
                                        "value", role.getAsMention()
                                )))
                        .build())
                .setComponents(ActionRow.of(logSettingsMenu(token, lang)))
                .queue();
    }

    private void handleIgnoredPrefixModal(ModalInteractionEvent event, String token, String lang) {
        String prefix = event.getValue("prefix") == null ? "" : event.getValue("prefix").getAsString().trim();
        if (prefix.isBlank()) {
            event.reply(owner.i18nService().t(lang, "settings.log_settings.prefix_required")).setEphemeral(true).queue();
            return;
        }
        BotConfig.MessageLogs logs = owner.settingsService().getMessageLogs(event.getGuild().getIdLong());
        List<String> updated = new ArrayList<>(logs.getIgnoredPrefixes());
        if (!updated.contains(prefix)) {
            updated.add(prefix);
        }
        owner.settingsService().updateSettings(event.getGuild().getIdLong(),
                settings -> settings.withMessageLogs(settings.getMessageLogs().withIgnoredPrefixes(updated)));
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(owner.i18nService().t(lang, "settings.log_settings.title"))
                        .setDescription(owner.i18nService().t(lang, "general.settings_saved",
                                Map.of(
                                        "key", owner.i18nService().t(lang, "settings.log_settings.ignore_prefix"),
                                        "value", "`" + prefix.replace("`", "'") + "`"
                                )))
                        .build())
                .addComponents(ActionRow.of(logSettingsMenu(token, lang)))
                .setEphemeral(true)
                .queue();
    }

    private void handleRemovedMemberSelect(EntitySelectInteractionEvent event, String token, String lang) {
        if (event.getMentions().getUsers().isEmpty()) {
            event.reply(owner.i18nService().t(lang, "settings.log_settings.user_required")).setEphemeral(true).queue();
            return;
        }
        User user = event.getMentions().getUsers().get(0);
        BotConfig.MessageLogs logs = owner.settingsService().getMessageLogs(event.getGuild().getIdLong());
        List<Long> updated = new ArrayList<>(logs.getIgnoredMemberIds());
        boolean removed = updated.remove(Long.valueOf(user.getIdLong()));
        if (!removed) {
            event.editMessageEmbeds(logSettingsStatusEmbed(lang, new Color(241, 196, 15), logSettingsMemberNotIgnoredText(lang)).build())
                    .setComponents(ActionRow.of(logSettingsMenu(token, lang)))
                    .queue();
            return;
        }
        owner.settingsService().updateSettings(event.getGuild().getIdLong(),
                settings -> settings.withMessageLogs(settings.getMessageLogs().withIgnoredMemberIds(updated)));
        event.editMessageEmbeds(logSettingsStatusEmbed(lang, new Color(46, 204, 113),
                        owner.i18nService().t(lang, "general.settings_saved",
                                Map.of(
                                        "key", logSettingsRemoveMemberLabel(lang),
                                        "value", user.getAsMention()
                                )))
                        .build())
                .setComponents(ActionRow.of(logSettingsMenu(token, lang)))
                .queue();
    }

    private void handleRemovedChannelSelect(EntitySelectInteractionEvent event, String token, String lang) {
        List<TextChannel> channels = event.getMentions().getChannels(TextChannel.class);
        if (channels.isEmpty()) {
            event.reply(owner.i18nService().t(lang, "settings.log_settings.channel_required")).setEphemeral(true).queue();
            return;
        }
        TextChannel channel = channels.get(0);
        BotConfig.MessageLogs logs = owner.settingsService().getMessageLogs(event.getGuild().getIdLong());
        List<Long> updated = new ArrayList<>(logs.getIgnoredChannelIds());
        boolean removed = updated.remove(Long.valueOf(channel.getIdLong()));
        if (!removed) {
            event.editMessageEmbeds(logSettingsStatusEmbed(lang, new Color(241, 196, 15), logSettingsChannelNotIgnoredText(lang)).build())
                    .setComponents(ActionRow.of(logSettingsMenu(token, lang)))
                    .queue();
            return;
        }
        owner.settingsService().updateSettings(event.getGuild().getIdLong(),
                settings -> settings.withMessageLogs(settings.getMessageLogs().withIgnoredChannelIds(updated)));
        event.editMessageEmbeds(logSettingsStatusEmbed(lang, new Color(46, 204, 113),
                        owner.i18nService().t(lang, "general.settings_saved",
                                Map.of(
                                        "key", logSettingsRemoveChannelLabel(lang),
                                        "value", channel.getAsMention()
                                )))
                        .build())
                .setComponents(ActionRow.of(logSettingsMenu(token, lang)))
                .queue();
    }

    private void handleRemovedRoleSelect(EntitySelectInteractionEvent event, String token, String lang) {
        if (event.getMentions().getRoles().isEmpty()) {
            event.reply(logSettingsRoleRequiredText(lang)).setEphemeral(true).queue();
            return;
        }
        Role role = event.getMentions().getRoles().get(0);
        BotConfig.MessageLogs logs = owner.settingsService().getMessageLogs(event.getGuild().getIdLong());
        List<Long> updated = new ArrayList<>(logs.getIgnoredRoleIds());
        boolean removed = updated.remove(Long.valueOf(role.getIdLong()));
        if (!removed) {
            event.editMessageEmbeds(logSettingsStatusEmbed(lang, new Color(241, 196, 15), logSettingsRoleNotIgnoredText(lang)).build())
                    .setComponents(ActionRow.of(logSettingsMenu(token, lang)))
                    .queue();
            return;
        }
        owner.settingsService().updateSettings(event.getGuild().getIdLong(),
                settings -> settings.withMessageLogs(settings.getMessageLogs().withIgnoredRoleIds(updated)));
        event.editMessageEmbeds(logSettingsStatusEmbed(lang, new Color(46, 204, 113),
                        owner.i18nService().t(lang, "general.settings_saved",
                                Map.of(
                                        "key", logSettingsRemoveRoleLabel(lang),
                                        "value", role.getAsMention()
                                )))
                        .build())
                .setComponents(ActionRow.of(logSettingsMenu(token, lang)))
                .queue();
    }

    private void handleRemovedPrefixModal(ModalInteractionEvent event, String token, String lang) {
        String prefix = event.getValue("prefix") == null ? "" : event.getValue("prefix").getAsString().trim();
        if (prefix.isBlank()) {
            event.reply(owner.i18nService().t(lang, "settings.log_settings.prefix_required")).setEphemeral(true).queue();
            return;
        }
        BotConfig.MessageLogs logs = owner.settingsService().getMessageLogs(event.getGuild().getIdLong());
        List<String> updated = new ArrayList<>(logs.getIgnoredPrefixes());
        boolean removed = updated.remove(prefix);
        if (!removed) {
            event.replyEmbeds(logSettingsStatusEmbed(lang, new Color(241, 196, 15), logSettingsPrefixNotIgnoredText(lang)).build())
                    .addComponents(ActionRow.of(logSettingsMenu(token, lang)))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        owner.settingsService().updateSettings(event.getGuild().getIdLong(),
                settings -> settings.withMessageLogs(settings.getMessageLogs().withIgnoredPrefixes(updated)));
        event.replyEmbeds(logSettingsStatusEmbed(lang, new Color(46, 204, 113),
                        owner.i18nService().t(lang, "general.settings_saved",
                                Map.of(
                                        "key", logSettingsRemovePrefixLabel(lang),
                                        "value", "`" + prefix.replace("`", "'") + "`"
                                )))
                        .build())
                .addComponents(ActionRow.of(logSettingsMenu(token, lang)))
                .setEphemeral(true)
                .queue();
    }

    private EmbedBuilder logSettingsListEmbed(String lang, BotConfig.MessageLogs logs) {
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(52, 152, 219))
                .setTitle(owner.i18nService().t(lang, "settings.log_settings.list_title"));
        embed.addField(owner.i18nService().t(lang, "settings.log_settings.ignore_prefix"),
                formatPrefixList(logs.getIgnoredPrefixes(), owner.i18nService().t(lang, "settings.info_channels_none")), false);
        embed.addField(owner.i18nService().t(lang, "settings.log_settings.ignore_member"),
                formatIdList(logs.getIgnoredMemberIds(), "<@", ">", owner.i18nService().t(lang, "settings.info_channels_none")), false);
        embed.addField(logSettingsIgnoreRoleLabel(lang),
                formatIdList(logs.getIgnoredRoleIds(), "<@&", ">", owner.i18nService().t(lang, "settings.info_channels_none")), false);
        embed.addField(owner.i18nService().t(lang, "settings.log_settings.ignore_channel"),
                formatIdList(logs.getIgnoredChannelIds(), "<#", ">", owner.i18nService().t(lang, "settings.info_channels_none")), false);
        return embed;
    }

    private String formatIdList(List<Long> ids, String prefix, String suffix, String emptyText) {
        if (ids == null || ids.isEmpty()) {
            return emptyText;
        }
        List<String> values = ids.stream()
                .map(id -> prefix + id + suffix)
                .toList();
        return String.join(", ", values);
    }

    private String formatPrefixList(List<String> prefixes, String emptyText) {
        if (prefixes == null || prefixes.isEmpty()) {
            return emptyText;
        }
        return prefixes.stream()
                .map(value -> "`" + value.replace("`", "'") + "`")
                .reduce((left, right) -> left + ", " + right)
                .orElse(emptyText);
    }

    private EmbedBuilder logSettingsStatusEmbed(String lang, Color color, String description) {
        return new EmbedBuilder()
                .setColor(color)
                .setTitle(owner.i18nService().t(lang, "settings.log_settings.title"))
                .setDescription(description);
    }

    private boolean isZhTw(String lang) {
        if (lang == null) {
            return false;
        }
        String normalized = lang.trim().toLowerCase();
        return "zh-tw".equals(normalized) || "zh_tw".equals(normalized);
    }

    private boolean isZhCn(String lang) {
        if (lang == null) {
            return false;
        }
        String normalized = lang.trim().toLowerCase();
        return "zh-cn".equals(normalized) || "zh_cn".equals(normalized);
    }

    private boolean isEnglish(String lang) {
        if (lang == null) {
            return false;
        }
        return lang.trim().toLowerCase().startsWith("en");
    }

    private boolean isZh(String lang) {
        return !isEnglish(lang);
    }

    private String logSettingsRemoveMemberLabel(String lang) {
        if (isZhCn(lang)) {
            return "移除忽略成员";
        }
        if (isZh(lang)) {
            return "移除忽略成員";
        }
        return "Remove Ignored Member";
    }

    private String logSettingsIgnoreRoleLabel(String lang) {
        if (isZhCn(lang)) {
            return "忽略身份组";
        }
        if (isZh(lang)) {
            return "忽略身分組";
        }
        return "Ignore Role";
    }

    private String logSettingsRemoveRoleLabel(String lang) {
        if (isZhCn(lang)) {
            return "移除忽略身份组";
        }
        if (isZh(lang)) {
            return "移除忽略身分組";
        }
        return "Remove Ignored Role";
    }

    private String logSettingsRemoveChannelLabel(String lang) {
        if (isZhCn(lang)) {
            return "移除忽略频道";
        }
        if (isZh(lang)) {
            return "移除忽略頻道";
        }
        return "Remove Ignored Channel";
    }

    private String logSettingsRemovePrefixLabel(String lang) {
        if (isZhCn(lang)) {
            return "移除忽略前缀";
        }
        if (isZh(lang)) {
            return "移除忽略前綴";
        }
        return "Remove Ignored Prefix";
    }

    private String logSettingsRemoveMemberDesc(String lang) {
        if (isZhCn(lang)) {
            return "选择要从忽略列表移除的成员。";
        }
        if (isZh(lang)) {
            return "選擇要從忽略清單移除的成員。";
        }
        return "Choose a member to remove from the ignore list.";
    }

    private String logSettingsIgnoreRoleDesc(String lang) {
        if (isZhCn(lang)) {
            return "选择要加入忽略列表的身份组。";
        }
        if (isZh(lang)) {
            return "選擇要加入忽略清單的身分組。";
        }
        return "Choose a role to add to the ignore list.";
    }

    private String logSettingsRemoveRoleDesc(String lang) {
        if (isZhCn(lang)) {
            return "选择要从忽略列表移除的身份组。";
        }
        if (isZh(lang)) {
            return "選擇要從忽略清單移除的身分組。";
        }
        return "Choose a role to remove from the ignore list.";
    }

    private String logSettingsRemoveChannelDesc(String lang) {
        if (isZhCn(lang)) {
            return "选择要从忽略列表移除的文字频道。";
        }
        if (isZh(lang)) {
            return "選擇要從忽略清單移除的文字頻道。";
        }
        return "Choose a text channel to remove from the ignore list.";
    }

    private String logSettingsRemovePrefixModalTitle(String lang) {
        if (isZhCn(lang)) {
            return "移除忽略前缀";
        }
        if (isZh(lang)) {
            return "移除忽略前綴";
        }
        return "Remove Ignored Prefix";
    }

    private String logSettingsMemberNotIgnoredText(String lang) {
        if (isZhCn(lang)) {
            return "该成员目前不在忽略列表中。";
        }
        if (isZh(lang)) {
            return "此成員目前不在忽略清單中。";
        }
        return "That member is not currently in the ignore list.";
    }

    private String logSettingsRoleNotIgnoredText(String lang) {
        if (isZhCn(lang)) {
            return "该身份组目前不在忽略列表中。";
        }
        if (isZh(lang)) {
            return "此身分組目前不在忽略清單中。";
        }
        return "That role is not currently in the ignore list.";
    }

    private String logSettingsChannelNotIgnoredText(String lang) {
        if (isZhCn(lang)) {
            return "该频道目前不在忽略列表中。";
        }
        if (isZh(lang)) {
            return "此頻道目前不在忽略清單中。";
        }
        return "That channel is not currently in the ignore list.";
    }

    private String logSettingsPrefixNotIgnoredText(String lang) {
        if (isZhCn(lang)) {
            return "该前缀目前不在忽略列表中。";
        }
        if (isZh(lang)) {
            return "此前綴目前不在忽略清單中。";
        }
        return "That prefix is not currently in the ignore list.";
    }

    private String logSettingsRoleRequiredText(String lang) {
        if (isZhCn(lang)) {
            return "请选择一个身份组。";
        }
        if (isZh(lang)) {
            return "請選擇一個身分組。";
        }
        return "Please choose a role.";
    }

    void handleSettingsNumberChain(SlashCommandInteractionEvent event, String lang) {
        if (!owner.has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions",
                            Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        String action = event.getOption("action") == null
                ? null
                : event.getOption("action").getAsString();
        boolean shouldReset = event.getOption("reset") != null && event.getOption("reset").getAsBoolean();
        TextChannel channel = event.getOption("channel") == null
                ? null
                : event.getOption("channel").getAsChannel().asTextChannel();

        List<String> responses = new ArrayList<>();

        if ("enable".equals(action)) {
            boolean currentEnabled = owner.moderationService().isNumberChainEnabled(guildId);
            boolean nextEnabled = !currentEnabled;
            owner.moderationService().setNumberChainEnabled(guildId, nextEnabled);
            responses.add(owner.i18nService().t(lang, "number_chain.result_set_enabled",
                    Map.of("status", owner.boolText(lang, nextEnabled))));
        }

        if (shouldReset) {
            owner.moderationService().resetNumberChain(guildId);
            responses.add(owner.i18nService().t(lang, "number_chain.result_reset"));
        }

        if (channel != null) {
            owner.moderationService().setNumberChainChannelId(guildId, channel.getIdLong());
            owner.moderationService().resetNumberChain(guildId);
            responses.add(owner.i18nService().t(lang, "number_chain.result_set_channel", Map.of("channel", channel.getAsMention())));
        }

        if (responses.isEmpty()) {
            boolean currentEnabled = owner.moderationService().isNumberChainEnabled(guildId);
            Long channelId = owner.moderationService().getNumberChainChannelId(guildId);
            long next = owner.moderationService().getNumberChainNext(guildId);
            String channelText = channelId == null ? owner.i18nService().t(lang, "settings.info_channels_none") : "<#" + channelId + ">";
            event.reply(owner.i18nService().t(lang, "number_chain.result_status",
                            Map.of(
                                    "status", owner.boolText(lang, currentEnabled),
                                    "channel", channelText,
                                    "next", String.valueOf(next)
                            )))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.reply(String.join("\n", responses)).setEphemeral(true).queue();
    }

    boolean handleStringSelectInteraction(StringSelectInteractionEvent event, String lang) {
        String componentId = event.getComponentId();
        if (MusicCommandListener.SETTINGS_INFO_SELECT_ID.equals(componentId)) {
            String section = event.getValues().isEmpty() ? "notifications" : event.getValues().get(0);
            event.editMessageEmbeds(owner.settingsInfoEmbed(event.getGuild(), lang, section).build())
                    .setComponents(
                            ActionRow.of(owner.settingsInfoMenu(lang, section)),
                            ActionRow.of(owner.settingsInfoButtons(lang, section, 0)),
                            ActionRow.of(owner.settingsInfoButtons(lang, section, 1))
                    )
                    .queue();
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_TEMPLATE_SELECT_PREFIX)) {
            owner.handleTemplateMenuSelect(event, lang);
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_MODULE_SELECT_PREFIX)) {
            owner.handleModuleMenuSelect(event, lang);
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_LOGS_SELECT_PREFIX)) {
            owner.handleLogsMenuSelect(event, lang);
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_LOGS_MEMBER_MODE_PREFIX)) {
            owner.handleLogsMemberModeSelect(event, lang);
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_LOGS_MEMBER_SPLIT_PREFIX)) {
            owner.handleLogsMemberSplitSelect(event, lang);
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_MUSIC_SELECT_PREFIX)) {
            owner.handleMusicMenuSelect(event, lang);
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_LANGUAGE_SELECT_PREFIX)) {
            owner.handleLanguageMenuSelect(event, lang);
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_NUMBER_CHAIN_SELECT_PREFIX)) {
            owner.handleNumberChainMenuSelect(event, lang);
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_RESET_SELECT_PREFIX)) {
            owner.handleSettingsResetSelect(event, lang);
            return true;
        }
        if (componentId.startsWith(LOG_SETTINGS_SELECT_PREFIX)) {
            String token = event.getComponentId().substring(LOG_SETTINGS_SELECT_PREFIX.length());
            LogSettingsRequest request = requireLogSettingsRequest(
                    token,
                    event.getGuild().getIdLong(),
                    event.getUser().getIdLong(),
                    lang,
                    message -> event.reply(message).setEphemeral(true).queue()
            );
            if (request == null) {
                return true;
            }
            String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
            switch (action) {
                case "view-ignore" -> event.editMessageEmbeds(logSettingsListEmbed(lang, owner.settingsService().getMessageLogs(event.getGuild().getIdLong())).build())
                        .setComponents(ActionRow.of(logSettingsMenu(token, lang)))
                        .queue();
                case "ignore-member" -> event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(52, 152, 219))
                                .setTitle(owner.i18nService().t(lang, "settings.log_settings.ignore_member"))
                                .setDescription(owner.i18nService().t(lang, "settings.log_settings.member_pick_desc"))
                                .build())
                        .setComponents(ActionRow.of(EntitySelectMenu.create(LOG_SETTINGS_MEMBER_PREFIX + token, EntitySelectMenu.SelectTarget.USER)
                                .setPlaceholder(owner.i18nService().t(lang, "settings.log_settings.member_placeholder"))
                                .setRequiredRange(1, 1)
                                .build()))
                        .queue();
                case "ignore-role" -> event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(52, 152, 219))
                                .setTitle(logSettingsIgnoreRoleLabel(lang))
                                .setDescription(logSettingsIgnoreRoleDesc(lang))
                                .build())
                        .setComponents(ActionRow.of(EntitySelectMenu.create(LOG_SETTINGS_ROLE_PREFIX + token, EntitySelectMenu.SelectTarget.ROLE)
                                .setPlaceholder(logSettingsIgnoreRoleLabel(lang))
                                .setRequiredRange(1, 1)
                                .build()))
                        .queue();
                case "ignore-channel" -> event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(52, 152, 219))
                                .setTitle(owner.i18nService().t(lang, "settings.log_settings.ignore_channel"))
                                .setDescription(owner.i18nService().t(lang, "settings.log_settings.channel_pick_desc"))
                                .build())
                        .setComponents(ActionRow.of(EntitySelectMenu.create(LOG_SETTINGS_CHANNEL_PREFIX + token, EntitySelectMenu.SelectTarget.CHANNEL)
                                .setChannelTypes(ChannelType.TEXT)
                                .setPlaceholder(owner.i18nService().t(lang, "settings.log_settings.channel_placeholder"))
                                .setRequiredRange(1, 1)
                                .build()))
                        .queue();
                case "ignore-prefix" -> event.replyModal(Modal.create(LOG_SETTINGS_PREFIX_MODAL_PREFIX + token,
                                        owner.i18nService().t(lang, "settings.log_settings.prefix_modal_title"))
                                .addComponents(Label.of(owner.i18nService().t(lang, "settings.log_settings.prefix"),
                                        TextInput.create("prefix", TextInputStyle.SHORT)
                                                .setPlaceholder(owner.i18nService().t(lang, "settings.log_settings.prefix_placeholder"))
                                                .setRequired(true)
                                                .setMaxLength(50)
                                                .build()))
                                .build())
                        .queue();
                case "remove-member" -> event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(52, 152, 219))
                                .setTitle(logSettingsRemoveMemberLabel(lang))
                                .setDescription(logSettingsRemoveMemberDesc(lang))
                                .build())
                        .setComponents(ActionRow.of(EntitySelectMenu.create(LOG_SETTINGS_REMOVE_MEMBER_PREFIX + token, EntitySelectMenu.SelectTarget.USER)
                                .setPlaceholder(owner.i18nService().t(lang, "settings.log_settings.member_placeholder"))
                                .setRequiredRange(1, 1)
                                .build()))
                        .queue();
                case "remove-role" -> event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(52, 152, 219))
                                .setTitle(logSettingsRemoveRoleLabel(lang))
                                .setDescription(logSettingsRemoveRoleDesc(lang))
                                .build())
                        .setComponents(ActionRow.of(EntitySelectMenu.create(LOG_SETTINGS_REMOVE_ROLE_PREFIX + token, EntitySelectMenu.SelectTarget.ROLE)
                                .setPlaceholder(logSettingsRemoveRoleLabel(lang))
                                .setRequiredRange(1, 1)
                                .build()))
                        .queue();
                case "remove-channel" -> event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(52, 152, 219))
                                .setTitle(logSettingsRemoveChannelLabel(lang))
                                .setDescription(logSettingsRemoveChannelDesc(lang))
                                .build())
                        .setComponents(ActionRow.of(EntitySelectMenu.create(LOG_SETTINGS_REMOVE_CHANNEL_PREFIX + token, EntitySelectMenu.SelectTarget.CHANNEL)
                                .setChannelTypes(ChannelType.TEXT)
                                .setPlaceholder(owner.i18nService().t(lang, "settings.log_settings.channel_placeholder"))
                                .setRequiredRange(1, 1)
                                .build()))
                        .queue();
                case "remove-prefix" -> event.replyModal(Modal.create(LOG_SETTINGS_REMOVE_PREFIX_MODAL_PREFIX + token,
                                        logSettingsRemovePrefixModalTitle(lang))
                                .addComponents(Label.of(owner.i18nService().t(lang, "settings.log_settings.prefix"),
                                        TextInput.create("prefix", TextInputStyle.SHORT)
                                                .setPlaceholder(owner.i18nService().t(lang, "settings.log_settings.prefix_placeholder"))
                                                .setRequired(true)
                                                .setMaxLength(50)
                                                .build()))
                                .build())
                        .queue();
                default -> event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            }
            return true;
        }
        return false;
    }

    boolean handleModalInteraction(ModalInteractionEvent event, String lang) {
        if (event.getModalId().startsWith(MusicCommandListener.SETTINGS_MUSIC_MODAL_PREFIX)) {
            owner.handleMusicMenuModal(event, lang);
            return true;
        }
        if (event.getModalId().startsWith(LOG_SETTINGS_PREFIX_MODAL_PREFIX)) {
            String token = event.getModalId().substring(LOG_SETTINGS_PREFIX_MODAL_PREFIX.length());
            LogSettingsRequest request = requireLogSettingsRequest(
                    token,
                    event.getGuild().getIdLong(),
                    event.getUser().getIdLong(),
                    lang,
                    message -> event.reply(message).setEphemeral(true).queue()
            );
            if (request == null) {
                return true;
            }
            handleIgnoredPrefixModal(event, token, lang);
            return true;
        }
        if (event.getModalId().startsWith(LOG_SETTINGS_REMOVE_PREFIX_MODAL_PREFIX)) {
            String token = event.getModalId().substring(LOG_SETTINGS_REMOVE_PREFIX_MODAL_PREFIX.length());
            LogSettingsRequest request = requireLogSettingsRequest(
                    token,
                    event.getGuild().getIdLong(),
                    event.getUser().getIdLong(),
                    lang,
                    message -> event.reply(message).setEphemeral(true).queue()
            );
            if (request == null) {
                return true;
            }
            handleRemovedPrefixModal(event, token, lang);
            return true;
        }
        if (!event.getModalId().startsWith(MusicCommandListener.TEMPLATE_MODAL_PREFIX)) {
            return false;
        }
        if (!owner.has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions",
                            Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                    .setEphemeral(true)
                    .queue();
            return true;
        }

        String templateType = event.getModalId().substring(MusicCommandListener.TEMPLATE_MODAL_PREFIX.length());
        String template = event.getValue("template") == null ? "" : event.getValue("template").getAsString();
        Integer color = null;
        if ("member-join".equals(templateType) || "member-leave".equals(templateType)) {
            String colorRaw = event.getValue("color") == null ? "" : event.getValue("color").getAsString();
            if (colorRaw != null && !colorRaw.isBlank()) {
                color = owner.parseHexColor(colorRaw);
                if (color == null) {
                    event.reply(owner.i18nService().t(lang, "settings.template_color_invalid"))
                            .setEphemeral(true)
                            .queue();
                    return true;
                }
            }
        }

        String displayKey = owner.applyTemplate(event.getGuild().getIdLong(), templateType, template, color, lang);
        if (displayKey == null) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return true;
        }

        int previewColor = owner.resolveTemplateColor(event.getGuild().getIdLong(), templateType);
        String preview = owner.renderTemplatePreview(template, event.getGuild().getName());
        EmbedBuilder previewEmbed = new EmbedBuilder()
                .setTitle(owner.i18nService().t(lang, "settings.template_preview_title"))
                .setDescription(preview)
                .setColor(previewColor)
                .addField(owner.i18nService().t(lang, "settings.template_updated"), displayKey, false);
        event.replyEmbeds(previewEmbed.build()).setEphemeral(true).queue();
        return true;
    }

    boolean handleButtonInteraction(ButtonInteractionEvent event, String lang) {
        String id = event.getComponentId();
        if (id.startsWith(MusicCommandListener.SETTINGS_RESET_CONFIRM_PREFIX)
                || id.startsWith(MusicCommandListener.SETTINGS_RESET_CANCEL_PREFIX)) {
            owner.handleSettingsResetConfirmButtons(event, lang);
            return true;
        }
        if (id.startsWith(MusicCommandListener.SETTINGS_INFO_BUTTON_PREFIX)) {
            String section = id.substring(MusicCommandListener.SETTINGS_INFO_BUTTON_PREFIX.length());
            event.editMessageEmbeds(owner.settingsInfoEmbed(event.getGuild(), lang, section).build())
                    .setComponents(
                            ActionRow.of(owner.settingsInfoMenu(lang, section)),
                            ActionRow.of(owner.settingsInfoButtons(lang, section, 0)),
                            ActionRow.of(owner.settingsInfoButtons(lang, section, 1))
                    )
                    .queue();
            return true;
        }
        return false;
    }

    boolean handleEntitySelectInteraction(EntitySelectInteractionEvent event, String lang) {
        String componentId = event.getComponentId();
        if (componentId.startsWith(LOG_SETTINGS_MEMBER_PREFIX)) {
            String token = componentId.substring(LOG_SETTINGS_MEMBER_PREFIX.length());
            LogSettingsRequest request = requireLogSettingsRequest(
                    token,
                    event.getGuild().getIdLong(),
                    event.getUser().getIdLong(),
                    lang,
                    message -> event.reply(message).setEphemeral(true).queue()
            );
            if (request == null) {
                return true;
            }
            handleIgnoredMemberSelect(event, token, lang);
            return true;
        }
        if (componentId.startsWith(LOG_SETTINGS_CHANNEL_PREFIX)) {
            String token = componentId.substring(LOG_SETTINGS_CHANNEL_PREFIX.length());
            LogSettingsRequest request = requireLogSettingsRequest(
                    token,
                    event.getGuild().getIdLong(),
                    event.getUser().getIdLong(),
                    lang,
                    message -> event.reply(message).setEphemeral(true).queue()
            );
            if (request == null) {
                return true;
            }
            handleIgnoredChannelSelect(event, token, lang);
            return true;
        }
        if (componentId.startsWith(LOG_SETTINGS_ROLE_PREFIX)) {
            String token = componentId.substring(LOG_SETTINGS_ROLE_PREFIX.length());
            LogSettingsRequest request = requireLogSettingsRequest(
                    token,
                    event.getGuild().getIdLong(),
                    event.getUser().getIdLong(),
                    lang,
                    message -> event.reply(message).setEphemeral(true).queue()
            );
            if (request == null) {
                return true;
            }
            handleIgnoredRoleSelect(event, token, lang);
            return true;
        }
        if (componentId.startsWith(LOG_SETTINGS_REMOVE_MEMBER_PREFIX)) {
            String token = componentId.substring(LOG_SETTINGS_REMOVE_MEMBER_PREFIX.length());
            LogSettingsRequest request = requireLogSettingsRequest(
                    token,
                    event.getGuild().getIdLong(),
                    event.getUser().getIdLong(),
                    lang,
                    message -> event.reply(message).setEphemeral(true).queue()
            );
            if (request == null) {
                return true;
            }
            handleRemovedMemberSelect(event, token, lang);
            return true;
        }
        if (componentId.startsWith(LOG_SETTINGS_REMOVE_ROLE_PREFIX)) {
            String token = componentId.substring(LOG_SETTINGS_REMOVE_ROLE_PREFIX.length());
            LogSettingsRequest request = requireLogSettingsRequest(
                    token,
                    event.getGuild().getIdLong(),
                    event.getUser().getIdLong(),
                    lang,
                    message -> event.reply(message).setEphemeral(true).queue()
            );
            if (request == null) {
                return true;
            }
            handleRemovedRoleSelect(event, token, lang);
            return true;
        }
        if (componentId.startsWith(LOG_SETTINGS_REMOVE_CHANNEL_PREFIX)) {
            String token = componentId.substring(LOG_SETTINGS_REMOVE_CHANNEL_PREFIX.length());
            LogSettingsRequest request = requireLogSettingsRequest(
                    token,
                    event.getGuild().getIdLong(),
                    event.getUser().getIdLong(),
                    lang,
                    message -> event.reply(message).setEphemeral(true).queue()
            );
            if (request == null) {
                return true;
            }
            handleRemovedChannelSelect(event, token, lang);
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_LOGS_CHANNEL_PREFIX)) {
            owner.handleLogsChannelSelect(event, lang);
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_MUSIC_CHANNEL_PREFIX)) {
            owner.handleMusicChannelSelect(event, lang);
            return true;
        }
        if (componentId.startsWith(MusicCommandListener.SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX)) {
            owner.handleNumberChainChannelSelect(event, lang);
            return true;
        }
        return false;
    }

    private static class LogSettingsRequest {
        private final long requestUserId;
        private final long guildId;
        private final Instant expiresAt;

        private LogSettingsRequest(long requestUserId, long guildId, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.expiresAt = expiresAt;
        }
    }
}


