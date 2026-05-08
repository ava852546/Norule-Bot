package com.norule.musicbot.discord.bot.gateway.command.settings.menu;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;

import java.awt.Color;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SettingsTemplateMenuHandler {
    private static final String SETTINGS_TEMPLATE_SELECT_PREFIX = ComponentIds.SETTINGS_TEMPLATE_SELECT_PREFIX;
    private static final String TEMPLATE_MODAL_PREFIX = ComponentIds.TEMPLATE_MODAL_PREFIX;
    private static final String KEY_UNKNOWN_COMMAND = "general.unknown_command";
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";
    private static final String ROUTE_TEMPLATE = "template";
    private static final String VALUE_MEMBER_JOIN = "member-join";
    private static final String VALUE_MEMBER_LEAVE = "member-leave";
    private static final String VALUE_VOICE_JOIN = "voice-join";
    private static final String VALUE_VOICE_LEAVE = "voice-leave";
    private static final String VALUE_VOICE_MOVE = "voice-move";

    private final MusicCommandService owner;
    private final Map<String, TemplateMenuRequest> templateMenuRequests = new ConcurrentHashMap<>();

    public SettingsTemplateMenuHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        templateMenuRequests.entrySet().removeIf(entry -> entry.getValue() == null || cutoff.isAfter(entry.getValue().expiresAt));
    }

    public void openTemplateMenu(SlashCommandInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(owner.i18nService().t(lang, "settings.template_menu_title"))
                        .setDescription(owner.i18nService().t(lang, "settings.template_menu_desc")
                                + "\n\n"
                                + owner.i18nService().t(lang, "settings.template_member_placeholders_guide"))
                        .build())
                .addComponents(ActionRow.of(settingsTemplateMenu(token, lang)))
                .setEphemeral(true)
                .queue();
    }

    public void openTemplateMenu(StringSelectInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(owner.i18nService().t(lang, "settings.template_menu_title"))
                        .setDescription(owner.i18nService().t(lang, "settings.template_menu_desc")
                                + "\n\n"
                                + owner.i18nService().t(lang, "settings.template_member_placeholders_guide"))
                        .build())
                .setComponents(ActionRow.of(settingsTemplateMenu(token, lang)))
                .queue();
    }

    public void handleTemplateMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_TEMPLATE_SELECT_PREFIX.length());
        TemplateMenuRequest request = templateMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            templateMenuRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "settings.template_menu_expired")).setEphemeral(true).queue();
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
        String type = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        switch (type) {
            case VALUE_MEMBER_JOIN -> event.replyModal(buildTemplateModal(
                    VALUE_MEMBER_JOIN,
                    owner.i18nService().t(lang, "settings.template_member_placeholders_short"),
                    true,
                    owner.settingsService().getNotifications(event.getGuild().getIdLong()).getMemberJoinColor(),
                    lang
            )).queue();
            case VALUE_MEMBER_LEAVE -> event.replyModal(buildTemplateModal(
                    VALUE_MEMBER_LEAVE,
                    owner.i18nService().t(lang, "settings.template_member_placeholders_short"),
                    true,
                    owner.settingsService().getNotifications(event.getGuild().getIdLong()).getMemberLeaveColor(),
                    lang
            )).queue();
            case VALUE_VOICE_JOIN -> event.replyModal(buildTemplateModal(VALUE_VOICE_JOIN, "{user} {channel} {from} {to}", false, null, lang)).queue();
            case VALUE_VOICE_LEAVE -> event.replyModal(buildTemplateModal(VALUE_VOICE_LEAVE, "{user} {channel} {from} {to}", false, null, lang)).queue();
            case VALUE_VOICE_MOVE -> event.replyModal(buildTemplateModal(VALUE_VOICE_MOVE, "{user} {channel} {from} {to}", false, null, lang)).queue();
            default -> event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
        }
    }

    public boolean handleTemplateModal(ModalInteractionEvent event, String lang) {
        if (!event.getModalId().startsWith(TEMPLATE_MODAL_PREFIX)) {
            return false;
        }
        if (!owner.has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions",
                            Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                    .setEphemeral(true)
                    .queue();
            return true;
        }

        String templateType = event.getModalId().substring(TEMPLATE_MODAL_PREFIX.length());
        String template = event.getValue(ROUTE_TEMPLATE) == null ? "" : event.getValue(ROUTE_TEMPLATE).getAsString();
        Integer color = null;
        if (VALUE_MEMBER_JOIN.equals(templateType) || VALUE_MEMBER_LEAVE.equals(templateType)) {
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

        String displayKey = applyTemplate(event.getGuild().getIdLong(), templateType, template, color, lang);
        if (displayKey == null) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return true;
        }

        int previewColor = owner.resolveTemplateColor(event.getGuild().getIdLong(), templateType);
        String preview = renderTemplatePreview(template, event.getGuild().getName());
        EmbedBuilder previewEmbed = new EmbedBuilder()
                .setTitle(owner.i18nService().t(lang, "settings.template_preview_title"))
                .setDescription(preview)
                .setColor(previewColor)
                .addField(owner.i18nService().t(lang, "settings.template_updated"), displayKey, false);
        event.replyEmbeds(previewEmbed.build()).setEphemeral(true).queue();
        return true;
    }

    private String registerMenuRequest(long requestUserId, long guildId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        templateMenuRequests.put(token, new TemplateMenuRequest(
                requestUserId,
                guildId,
                Instant.now().plusSeconds(120)
        ));
        return token;
    }

    private StringSelectMenu settingsTemplateMenu(String token, String lang) {
        return StringSelectMenu.create(SETTINGS_TEMPLATE_SELECT_PREFIX + token)
                .setPlaceholder(owner.i18nService().t(lang, "settings.template_menu_placeholder"))
                .addOptions(
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_key_member_join_template"), VALUE_MEMBER_JOIN),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_key_member_leave_template"), VALUE_MEMBER_LEAVE),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_key_voice_join_template"), VALUE_VOICE_JOIN),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_key_voice_leave_template"), VALUE_VOICE_LEAVE),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_key_voice_move_template"), VALUE_VOICE_MOVE)
                )
                .build();
    }

    private Modal buildTemplateModal(
            String templateType,
            String placeholders,
            boolean includeColor,
            Integer currentColor,
            String lang
    ) {
        TextInput input = TextInput.create(ROUTE_TEMPLATE, TextInputStyle.PARAGRAPH)
                .setPlaceholder(placeholders)
                .setRequired(true)
                .setMaxLength(1000)
                .build();

        Modal.Builder modalBuilder = Modal.create(TEMPLATE_MODAL_PREFIX + templateType, owner.i18nService().t(lang, "settings.template_modal_title"))
                .addComponents(Label.of(owner.i18nService().t(lang, "settings.template_modal_label"), input));
        if (includeColor) {
            String placeholder = currentColor == null
                    ? "#00FF00"
                    : String.format("#%06X", currentColor & 0xFFFFFF);
            TextInput colorInput = TextInput.create("color", TextInputStyle.SHORT)
                    .setPlaceholder(placeholder)
                    .setRequired(false)
                    .setMinLength(3)
                    .setMaxLength(9)
                    .build();
            modalBuilder.addComponents(Label.of(owner.i18nService().t(lang, "settings.template_modal_color_label"), colorInput));
        }
        return modalBuilder.build();
    }

    private String applyTemplate(long guildId, String templateType, String template, Integer color, String lang) {
        switch (templateType) {
            case VALUE_MEMBER_JOIN -> {
                owner.settingsService().updateSettings(guildId, s -> {
                    var notifications = s.getNotifications().withMemberJoinMessage(template);
                    if (color != null) {
                        notifications = notifications.withMemberJoinColor(color);
                    }
                    return s.withNotifications(notifications);
                });
                return owner.i18nService().t(lang, "settings.info_key_member_join_template");
            }
            case VALUE_MEMBER_LEAVE -> {
                owner.settingsService().updateSettings(guildId, s -> {
                    var notifications = s.getNotifications().withMemberLeaveMessage(template);
                    if (color != null) {
                        notifications = notifications.withMemberLeaveColor(color);
                    }
                    return s.withNotifications(notifications);
                });
                return owner.i18nService().t(lang, "settings.info_key_member_leave_template");
            }
            case VALUE_VOICE_JOIN -> {
                owner.settingsService().updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withVoiceJoinMessage(template)));
                return owner.i18nService().t(lang, "settings.info_key_voice_join_template");
            }
            case VALUE_VOICE_LEAVE -> {
                owner.settingsService().updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withVoiceLeaveMessage(template)));
                return owner.i18nService().t(lang, "settings.info_key_voice_leave_template");
            }
            case VALUE_VOICE_MOVE -> {
                owner.settingsService().updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withVoiceMoveMessage(template)));
                return owner.i18nService().t(lang, "settings.info_key_voice_move_template");
            }
            default -> {
                return null;
            }
        }
    }

    private String renderTemplatePreview(String template, String guildName) {
        return template
                .replace("{user}", "@NoRuleUser (ID: 123456789012345678)")
                .replace("{username}", "NoRuleUser")
                .replace("{guild}", guildName)
                .replace("{id}", "123456789012345678")
                .replace("{tag}", "NoRuleUser#0001")
                .replace("{isBot}", "false")
                .replace("{createdAt}", "2024-01-01 12:00:00 UTC")
                .replace("{accountAgeDays}", "999")
                .replace("{channel}", "General Voice (ID: 234567890123456789)")
                .replace("{from}", "Lobby (ID: 345678901234567890)")
                .replace("{to}", "Gaming (ID: 456789012345678901)");
    }

    private static class TemplateMenuRequest {
        private final long requestUserId;
        private final long guildId;
        private final Instant expiresAt;

        private TemplateMenuRequest(long requestUserId, long guildId, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.expiresAt = expiresAt;
        }
    }
}
