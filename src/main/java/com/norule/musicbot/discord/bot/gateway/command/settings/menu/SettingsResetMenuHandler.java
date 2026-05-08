package com.norule.musicbot.discord.bot.gateway.command.settings.menu;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import com.norule.musicbot.config.GuildSettingsService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SettingsResetMenuHandler {
    private static final String SETTINGS_RESET_SELECT_PREFIX = ComponentIds.SETTINGS_RESET_SELECT_PREFIX;
    private static final String SETTINGS_RESET_CONFIRM_PREFIX = ComponentIds.SETTINGS_RESET_CONFIRM_PREFIX;
    private static final String SETTINGS_RESET_CANCEL_PREFIX = ComponentIds.SETTINGS_RESET_CANCEL_PREFIX;
    private static final String KEY_UNKNOWN_COMMAND = "general.unknown_command";
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";
    private static final String ROUTE_LANGUAGE = "language";
    private static final String ROUTE_NUMBER_CHAIN = "number-chain";
    private static final String CMD_MUSIC = "music";

    private final MusicCommandService owner;
    private final Map<String, ResetRequest> resetRequests = new ConcurrentHashMap<>();
    private final Map<String, ResetConfirmRequest> resetConfirmRequests = new ConcurrentHashMap<>();

    public SettingsResetMenuHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        resetRequests.entrySet().removeIf(entry -> entry.getValue() == null || cutoff.isAfter(entry.getValue().expiresAt));
        resetConfirmRequests.entrySet().removeIf(entry -> entry.getValue() == null || cutoff.isAfter(entry.getValue().expiresAt));
    }

    public void openSettingsResetMenu(SlashCommandInteractionEvent event, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        resetRequests.put(token, new ResetRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(230, 126, 34))
                        .setTitle(owner.i18nService().t(lang, "settings.reset_title"))
                        .setDescription(owner.i18nService().t(lang, "settings.reset_desc"))
                        .build())
                .addComponents(ActionRow.of(settingsResetMenu(token, lang)))
                .setEphemeral(true)
                .queue();
    }

    public void openSettingsResetMenu(StringSelectInteractionEvent event, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        resetRequests.put(token, new ResetRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(230, 126, 34))
                        .setTitle(owner.i18nService().t(lang, "settings.reset_title"))
                        .setDescription(owner.i18nService().t(lang, "settings.reset_desc"))
                        .build())
                .setComponents(ActionRow.of(settingsResetMenu(token, lang)))
                .queue();
    }

    public void handleSettingsResetSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_RESET_SELECT_PREFIX.length());
        ResetRequest request = resetRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            resetRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "settings.reset_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }
        String selection = event.getValues().isEmpty() ? "all" : event.getValues().get(0);
        resetRequests.remove(token);
        if (!isResetSelection(selection)) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }

        String confirmToken = UUID.randomUUID().toString().replace("-", "");
        resetConfirmRequests.put(confirmToken, new ResetConfirmRequest(
                request.requestUserId,
                request.guildId,
                selection,
                Instant.now().plusSeconds(120)
        ));
        String target = owner.i18nService().t(lang, "settings.reset_target_" + selection);
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(231, 76, 60))
                        .setTitle(owner.i18nService().t(lang, "settings.reset_confirm_title"))
                        .setDescription(owner.i18nService().t(lang, "settings.reset_confirm_desc", Map.of("target", target)))
                        .build())
                .setComponents(ActionRow.of(
                        Button.danger(SETTINGS_RESET_CONFIRM_PREFIX + confirmToken, owner.i18nService().t(lang, "settings.reset_confirm_button")),
                        Button.secondary(SETTINGS_RESET_CANCEL_PREFIX + confirmToken, owner.i18nService().t(lang, "settings.reset_cancel_button"))
                ))
                .queue();
    }

    public void handleSettingsResetConfirmButtons(ButtonInteractionEvent event, String lang) {
        String id = event.getComponentId();
        String token = id.substring(id.lastIndexOf(':') + 1);
        ResetConfirmRequest request = resetConfirmRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            resetConfirmRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "settings.reset_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }

        if (id.startsWith(SETTINGS_RESET_CANCEL_PREFIX)) {
            resetConfirmRequests.remove(token);
            event.editMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(149, 165, 166))
                            .setTitle(owner.i18nService().t(lang, "settings.reset_title"))
                            .setDescription(owner.i18nService().t(lang, "settings.reset_cancelled"))
                            .build())
                    .setComponents(List.of())
                    .queue();
            return;
        }

        if (!applyResetSelection(request.guildId, request.selection)) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        resetConfirmRequests.remove(token);
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(owner.i18nService().t(lang, "settings.reset_title"))
                        .setDescription(owner.i18nService().t(lang, "settings.reset_done",
                                Map.of("target", owner.i18nService().t(lang, "settings.reset_target_" + request.selection))))
                        .build())
                .setComponents(List.of())
                .queue();
    }

    private StringSelectMenu settingsResetMenu(String token, String lang) {
        return StringSelectMenu.create(SETTINGS_RESET_SELECT_PREFIX + token)
                .setPlaceholder(owner.i18nService().t(lang, "settings.reset_placeholder"))
                .addOptions(
                        SelectOption.of(owner.i18nService().t(lang, "settings.reset_option_language"), ROUTE_LANGUAGE),
                        SelectOption.of(owner.i18nService().t(lang, "settings.reset_option_notifications"), "notifications"),
                        SelectOption.of(owner.i18nService().t(lang, "settings.reset_option_message_logs"), "message-logs"),
                        SelectOption.of(owner.i18nService().t(lang, "settings.reset_option_music"), CMD_MUSIC),
                        SelectOption.of(owner.i18nService().t(lang, "settings.reset_option_private_room"), "private-room"),
                        SelectOption.of(owner.i18nService().t(lang, "settings.reset_option_ticket"), "ticket"),
                        SelectOption.of(owner.i18nService().t(lang, "settings.reset_option_number_chain"), ROUTE_NUMBER_CHAIN),
                        SelectOption.of(owner.i18nService().t(lang, "settings.reset_option_all"), "all")
                )
                .build();
    }

    private boolean isResetSelection(String selection) {
        return ROUTE_LANGUAGE.equals(selection)
                || "notifications".equals(selection)
                || "message-logs".equals(selection)
                || CMD_MUSIC.equals(selection)
                || "private-room".equals(selection)
                || "ticket".equals(selection)
                || ROUTE_NUMBER_CHAIN.equals(selection)
                || "all".equals(selection);
    }

    private boolean applyResetSelection(long guildId, String selection) {
        switch (selection) {
            case ROUTE_LANGUAGE -> owner.settingsService().updateSettings(guildId,
                    s -> s.withLanguage(owner.runtimeConfigSnapshot().getDefaultLanguage()));
            case "notifications" -> owner.settingsService().updateSettings(guildId,
                    s -> s.withNotifications(owner.runtimeConfigSnapshot().getDefaultNotifications()));
            case "message-logs" -> owner.settingsService().updateSettings(guildId,
                    s -> s.withMessageLogs(owner.runtimeConfigSnapshot().getDefaultMessageLogs()));
            case CMD_MUSIC -> owner.settingsService().updateSettings(guildId,
                    s -> s.withMusic(owner.runtimeConfigSnapshot().getDefaultMusic()));
            case "private-room" -> owner.settingsService().updateSettings(guildId,
                    s -> s.withPrivateRoom(owner.runtimeConfigSnapshot().getDefaultPrivateRoom()));
            case "ticket" -> owner.settingsService().updateSettings(guildId,
                    s -> s.withTicket(owner.runtimeConfigSnapshot().getDefaultTicket()));
            case ROUTE_NUMBER_CHAIN -> resetNumberChainSettings(guildId);
            case "all" -> {
                owner.settingsService().updateSettings(guildId, s -> new GuildSettingsService.GuildSettings(
                        owner.runtimeConfigSnapshot().getDefaultLanguage(),
                        owner.runtimeConfigSnapshot().getDefaultNotifications(),
                        owner.runtimeConfigSnapshot().getDefaultWelcome(),
                        owner.runtimeConfigSnapshot().getDefaultMessageLogs(),
                        owner.runtimeConfigSnapshot().getDefaultMusic(),
                        owner.runtimeConfigSnapshot().getDefaultPrivateRoom(),
                        owner.runtimeConfigSnapshot().getDefaultTicket()
                ));
                resetNumberChainSettings(guildId);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void resetNumberChainSettings(long guildId) {
        owner.moderationService().setNumberChainEnabled(guildId, false);
        owner.moderationService().setNumberChainChannelId(guildId, null);
        owner.moderationService().resetNumberChain(guildId);
    }

    private static class ResetRequest {
        private final long requestUserId;
        private final long guildId;
        private final Instant expiresAt;

        private ResetRequest(long requestUserId, long guildId, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.expiresAt = expiresAt;
        }
    }

    private static class ResetConfirmRequest {
        private final long requestUserId;
        private final long guildId;
        private final String selection;
        private final Instant expiresAt;

        private ResetConfirmRequest(long requestUserId, long guildId, String selection, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.selection = selection;
            this.expiresAt = expiresAt;
        }
    }
}
