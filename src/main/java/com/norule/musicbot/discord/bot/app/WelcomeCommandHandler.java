package com.norule.musicbot.discord.bot.app;


import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public final class WelcomeCommandHandler {
    private final MusicCommandService owner;
    public WelcomeCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }
    public void handleWelcomeSlash(SlashCommandInteractionEvent event, String lang) {
        if (!owner.has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String action = event.getOption("action") == null ? null : event.getOption("action").getAsString();
        if (action != null && !action.isBlank()) {
            var current = owner.settingsService().getWelcome(event.getGuild().getIdLong());
            switch (action) {
                case "enable" -> {
                    boolean enabled = !current.isEnabled();
                    owner.settingsService().updateSettings(event.getGuild().getIdLong(), s -> s.withWelcome(
                            s.getWelcome().withEnabled(enabled)
                    ));
                    event.reply(owner.i18nService().t(lang, "welcome.result_set_enabled", Map.of("status", owner.boolText(lang, enabled))))
                            .setEphemeral(true)
                            .queue();
                    return;
                }
                case "status" -> {
                    String channelText = current.getChannelId() == null
                            ? owner.i18nService().t(lang, "settings.info_channels_none")
                            : "<#" + current.getChannelId() + ">";
                    String titleText = (current.getTitle() == null || current.getTitle().isBlank())
                            ? owner.i18nService().t(lang, "welcome.default_title")
                            : safe(current.getTitle(), 80);
                    event.reply(owner.i18nService().t(lang, "welcome.result_status", Map.of(
                                    "status", owner.boolText(lang, current.isEnabled()),
                                    "channel", channelText,
                                    "title", titleText
                            )))
                            .setEphemeral(true)
                            .queue();
                    return;
                }
                default -> {
                    event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
                    return;
                }
            }
        }
        var channelOption = event.getOption("channel");
        if (channelOption == null) {
            channelOption = event.getOption(MusicCommandService.OPTION_WELCOME_CHANNEL_ZH);
        }
        if (channelOption != null) {
            if (channelOption.getAsChannel().getType() != ChannelType.TEXT) {
                event.reply(owner.i18nService().t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                return;
            }
            TextChannel textChannel = channelOption.getAsChannel().asTextChannel();
            String missing = formatMissingPermissions(event.getGuild().getSelfMember(), textChannel,
                    Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
            if (!"-".equals(missing)) {
                event.reply(owner.i18nService().t(lang, "general.missing_permissions", Map.of("permissions", missing)))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            owner.settingsService().updateSettings(event.getGuild().getIdLong(), s -> s.withWelcome(
                    s.getWelcome()
                            .withChannelId(textChannel.getIdLong())
                            .withEnabled(true)
            ));
            event.reply(owner.i18nService().t(lang, "welcome.channel_saved", Map.of("channel", textChannel.getAsMention())))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        var welcome = owner.settingsService().getWelcome(event.getGuild().getIdLong());
        event.replyModal(buildWelcomeModal(welcome.getTitle(), welcome.getMessage(), lang)).queue();
    }
    public void handleWelcomeModal(ModalInteractionEvent event, String lang) {
        if (!owner.has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String title = event.getValue("title") == null ? "" : event.getValue("title").getAsString().trim();
        String message = event.getValue("message") == null ? "" : event.getValue("message").getAsString().trim();
        if (message.isBlank()) {
            event.reply(owner.i18nService().t(lang, "welcome.modal_message_required")).setEphemeral(true).queue();
            return;
        }
        owner.settingsService().updateSettings(event.getGuild().getIdLong(), s -> s.withWelcome(
                s.getWelcome()
                        .withEnabled(true)
                        .withTitle(title)
                        .withMessage(message)
        ));

        String previewTitle = title.isBlank()
                ? owner.i18nService().t(lang, "welcome.default_title")
                : owner.previewWelcomeText(title, event.getGuild(), event.getUser());
        String previewBody = owner.previewWelcomeText(message, event.getGuild(), event.getUser());
        EmbedBuilder preview = new EmbedBuilder()
                .setColor(new Color(0x2ECC71))
                .setTitle(previewTitle)
                .setDescription(previewBody)
                .addField(owner.i18nService().t(lang, "welcome.saved_title"), owner.i18nService().t(lang, "welcome.saved_desc"), false)
                .setThumbnail(event.getUser().getEffectiveAvatarUrl());
        event.replyEmbeds(preview.build()).setEphemeral(true).queue();
    }

    private Modal buildWelcomeModal(String currentTitle, String currentMessage, String lang) {
        String defaultTitle = currentTitle;
        if (defaultTitle == null || defaultTitle.isBlank()) {
            defaultTitle = owner.i18nService().t(lang, "welcome.default_title");
        }
        TextInput.Builder titleInput = TextInput.create("title", TextInputStyle.SHORT)
                .setPlaceholder(owner.i18nService().t(lang, "welcome.modal_title_placeholder"))
                .setRequired(false)
                .setMaxLength(100);
        if (!defaultTitle.isBlank()) {
            titleInput.setValue(defaultTitle.length() > 100 ? defaultTitle.substring(0, 100) : defaultTitle);
        }

        String defaultBody = currentMessage;
        TextInput.Builder bodyInput = TextInput.create("message", TextInputStyle.PARAGRAPH)
                .setPlaceholder(owner.i18nService().t(lang, "welcome.modal_message_placeholder"))
                .setRequired(true)
                .setMaxLength(1000);
        if (defaultBody != null && !defaultBody.isBlank()) {
            bodyInput.setValue(defaultBody.length() > 1000 ? defaultBody.substring(0, 1000) : defaultBody);
        }

        return Modal.create(MusicCommandService.WELCOME_MODAL_ID, owner.i18nService().t(lang, "welcome.modal_form_title"))
                .addComponents(
                        Label.of(owner.i18nService().t(lang, "welcome.modal_title_label"), titleInput.build()),
                        Label.of(owner.i18nService().t(lang, "welcome.modal_message_label"), bodyInput.build())
                )
                .build();
    }

    private String formatMissingPermissions(Member member, GuildChannel channel, Permission... permissions) {
        EnumSet<Permission> missing = EnumSet.noneOf(Permission.class);
        for (Permission permission : permissions) {
            if (!member.hasPermission(channel, permission)) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            return "-";
        }
        List<String> names = new ArrayList<>();
        for (Permission permission : missing) {
            names.add(permission.getName());
        }
        return String.join(", ", names);
    }

    private String safe(String s, int max) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1);
    }
}




