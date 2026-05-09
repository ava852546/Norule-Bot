package com.norule.musicbot.discord.bot.gateway.command.settings.menu;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class SettingsLanguageMenuHandler {
    private static final String SETTINGS_LANGUAGE_SELECT_PREFIX = ComponentIds.SETTINGS_LANGUAGE_SELECT_PREFIX;
    private static final String ROUTE_LANGUAGE            = "language";
    private static final String KEY_UNKNOWN_COMMAND       = "general.unknown_command";
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";

    private final Supplier<I18nService> i18n;
    private final GuildSettingsService settingsService;
    private final ConcurrentHashMap<String, MenuRequest> languageMenuRequests = new ConcurrentHashMap<>();

    public SettingsLanguageMenuHandler(Supplier<I18nService> i18n, GuildSettingsService settingsService) {
        this.i18n = i18n;
        this.settingsService = settingsService;
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        languageMenuRequests.entrySet().removeIf(e -> e.getValue() == null || cutoff.isAfter(e.getValue().expiresAt));
    }

    public void openLanguageMenu(SlashCommandInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(52, 152, 219))
                        .setTitle(i18n.get().t(lang, "settings.language_menu_title"))
                        .setDescription(i18n.get().t(lang, "settings.language_menu_desc"))
                        .build())
                .addComponents(ActionRow.of(settingsLanguageMenu(token, event.getGuild().getIdLong(), lang)))
                .setEphemeral(true)
                .queue();
    }

    public void openLanguageMenu(StringSelectInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(52, 152, 219))
                        .setTitle(i18n.get().t(lang, "settings.language_menu_title"))
                        .setDescription(i18n.get().t(lang, "settings.language_menu_desc"))
                        .build())
                .setComponents(ActionRow.of(settingsLanguageMenu(token, event.getGuild().getIdLong(), lang)))
                .queue();
    }

    public void handleLanguageMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_LANGUAGE_SELECT_PREFIX.length());
        MenuRequest request = languageMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            languageMenuRequests.remove(token);
            event.reply(i18n.get().t(lang, "settings.language_menu_expired"))
                    .setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.get().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.get().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }
        String code = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        if (!i18n.get().hasLanguage(code)) {
            event.reply(i18n.get().t(lang, "settings.language_invalid",
                            Map.of(ROUTE_LANGUAGE, code)))
                    .setEphemeral(true).queue();
            return;
        }
        String normalized = i18n.get().normalizeLanguage(code);
        settingsService.updateSettings(event.getGuild().getIdLong(), s -> s.withLanguage(normalized));
        String languageDisplay = languageDisplayText(normalized);
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(i18n.get().t(normalized, "settings.language_menu_title"))
                        .setDescription(i18n.get().t(normalized, "settings.language_updated",
                                Map.of(ROUTE_LANGUAGE, languageDisplay)))
                        .build())
                .setComponents(ActionRow.of(settingsLanguageMenu(token, event.getGuild().getIdLong(), normalized)))
                .queue();
    }

    private StringSelectMenu settingsLanguageMenu(String token, long guildId, String lang) {
        String current = settingsService.getLanguage(guildId);
        StringSelectMenu.Builder builder = StringSelectMenu.create(SETTINGS_LANGUAGE_SELECT_PREFIX + token)
                .setPlaceholder(i18n.get().t(lang, "settings.language_menu_placeholder"));
        int count = 0;
        for (Map.Entry<String, String> entry : i18n.get().getAvailableLanguages().entrySet()) {
            if (count >= 25) {
                break;
            }
            String code = entry.getKey();
            String name = entry.getValue() == null || entry.getValue().isBlank() ? code : entry.getValue();
            builder.addOptions(SelectOption.of(code + " - " + name, code)
                    .withDefault(code.equalsIgnoreCase(current)));
            count++;
        }
        return builder.build();
    }

    private String languageDisplayText(String languageCode) {
        String normalized = i18n.get().normalizeLanguage(languageCode);
        String display = i18n.get().getAvailableLanguages().get(normalized);
        if (display == null || display.isBlank()) {
            return normalized;
        }
        return display + " (" + normalized + ")";
    }

    private String registerMenuRequest(long requestUserId, long guildId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        languageMenuRequests.put(token, new MenuRequest(requestUserId, guildId,
                Instant.now().plusSeconds(120)));
        return token;
    }

    private static final class MenuRequest {
        final long requestUserId;
        final long guildId;
        final Instant expiresAt;

        MenuRequest(long requestUserId, long guildId, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId       = guildId;
            this.expiresAt     = expiresAt;
        }
    }
}
