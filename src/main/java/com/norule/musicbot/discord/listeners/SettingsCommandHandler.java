package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.web.*;

import com.norule.musicbot.*;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class SettingsCommandHandler {
    private final MusicCommandListener owner;

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
                event.replyEmbeds(new EmbedBuilder()
                                .setColor(new Color(46, 204, 113))
                                .setTitle(owner.i18nService().t(lang, "settings.info_title"))
                                .setDescription("??" + owner.i18nService().t(lang, "settings.reload_done"))
                                .build())
                        .setEphemeral(true)
                        .queue();
            }
            case "language" -> owner.openLanguageMenu(event, lang);
            case "template" -> owner.openTemplateMenu(event, lang);
            case "module" -> owner.openModuleMenu(event, lang);
            case "reset" -> owner.openSettingsResetMenu(event, lang);
            case "logs" -> owner.openLogsMenu(event, lang);
            case "music" -> owner.openMusicMenu(event, lang);
            case "number-chain" -> owner.openNumberChainMenu(event, lang);
            default -> event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
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
        return false;
    }

    boolean handleModalInteraction(ModalInteractionEvent event, String lang) {
        if (event.getModalId().startsWith(MusicCommandListener.SETTINGS_MUSIC_MODAL_PREFIX)) {
            owner.handleMusicMenuModal(event, lang);
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
}


