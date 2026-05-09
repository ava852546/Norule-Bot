package com.norule.musicbot.discord.bot.gateway.command.settings.menu;

import com.norule.musicbot.ModerationService;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import com.norule.musicbot.discord.bot.ops.wordchain.WordChainOps;
import com.norule.musicbot.i18n.I18nService;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import com.norule.musicbot.discord.bot.gateway.command.settings.view.SettingsUiText;
import com.norule.musicbot.domain.wordchain.WordChainStatusSnapshot;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class SettingsWordChainMenuHandler {
    private static final String SETTINGS_WORD_CHAIN_SELECT_PREFIX  = ComponentIds.SETTINGS_WORD_CHAIN_SELECT_PREFIX;
    private static final String SETTINGS_WORD_CHAIN_CHANNEL_PREFIX = ComponentIds.SETTINGS_WORD_CHAIN_CHANNEL_PREFIX;

    private static final String OPTION_VALUE   = CommandOptions.VALUE;
    private static final String OPTION_RESET   = CommandOptions.RESET;

    private static final String KEY_UNKNOWN_COMMAND       = "general.unknown_command";
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";

    private final Supplier<I18nService> i18n;
    private final WordChainOps wordChainOps;
    private final SettingsUiText uiText;
    private final ConcurrentHashMap<String, MenuRequest> wordChainMenuRequests = new ConcurrentHashMap<>();

    public SettingsWordChainMenuHandler(Supplier<I18nService> i18n, WordChainOps wordChainOps, ModerationService moderationService) {
        this.i18n = i18n;
        this.wordChainOps = wordChainOps;
        this.uiText = new SettingsUiText(i18n, moderationService);
    }

    private String boolText(String lang, boolean value) {
        return value ? i18n.get().t(lang, "settings.info_bool_on") : i18n.get().t(lang, "settings.info_bool_off");
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        wordChainMenuRequests.entrySet().removeIf(e -> e.getValue() == null || cutoff.isAfter(e.getValue().expiresAt));
    }

    public void openWordChainMenu(SlashCommandInteractionEvent event, String lang) {
        if (wordChainOps == null) {
            event.reply(i18n.get().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.deferReply(true).queue(hook -> wordChainOps.status(event.getGuild().getIdLong())
                .thenAccept(status -> hook.editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, status, null).build())
                        .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, status)))
                        .queue())
                .exceptionally(error -> {
                    hook.editOriginal(i18n.get().t(lang, "general.action_failed")).queue();
                    return null;
                }));
    }

    public void openWordChainMenu(StringSelectInteractionEvent event, String lang) {
        if (wordChainOps == null) {
            event.reply(i18n.get().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.deferEdit().queue(ignored -> wordChainOps.status(event.getGuild().getIdLong())
                .thenAccept(status -> event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, status, null).build())
                        .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, status)))
                        .queue())
                .exceptionally(error -> {
                    event.getHook().editOriginal(i18n.get().t(lang, "general.action_failed")).queue();
                    return null;
                }));
    }

    public void handleWordChainMenuSelect(StringSelectInteractionEvent event, String lang) {
        if (wordChainOps == null) {
            event.reply(i18n.get().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String token = event.getComponentId().substring(SETTINGS_WORD_CHAIN_SELECT_PREFIX.length());
        MenuRequest request = wordChainMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            wordChainMenuRequests.remove(token);
            event.reply(i18n.get().t(lang, "settings.word_chain_menu_expired"))
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
        long guildId = event.getGuild().getIdLong();
        String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        switch (action) {
            case "enable-toggle" -> event.deferEdit().queue(ignored -> wordChainOps.status(guildId)
                    .thenAccept(current -> {
                        if (current.enabled()) {
                            wordChainOps.disable(guildId)
                                    .thenAccept(updated -> {
                                        String changed = i18n.get().t(lang, "general.settings_saved",
                                                Map.of("key", i18n.get().t(lang, "settings.info_key_word_chain_enabled"),
                                                        OPTION_VALUE, boolText(lang, updated.enabled())));
                                        event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, updated, changed).build())
                                                .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, updated)))
                                                .queue();
                                    })
                                    .exceptionally(error -> {
                                        event.getHook().editOriginal(i18n.get().t(lang, "general.action_failed")).queue();
                                        return null;
                                    });
                            return;
                        }
                        if (current.channelId() == null) {
                            String changed = i18n.get().t(lang, "settings.word_chain_channel_required");
                            event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, current, changed).build())
                                    .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, current)))
                                    .queue();
                            return;
                        }
                        wordChainOps.setChannel(guildId, current.channelId())
                                .thenAccept(updated -> {
                                    String changed = i18n.get().t(lang, "general.settings_saved",
                                            Map.of("key", i18n.get().t(lang, "settings.info_key_word_chain_enabled"),
                                                    OPTION_VALUE, boolText(lang, updated.enabled())));
                                    event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, updated, changed).build())
                                            .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, updated)))
                                            .queue();
                                })
                                .exceptionally(error -> {
                                    event.getHook().editOriginal(i18n.get().t(lang, "general.action_failed")).queue();
                                    return null;
                                });
                    })
                    .exceptionally(error -> {
                        event.getHook().editOriginal(i18n.get().t(lang, "general.action_failed")).queue();
                        return null;
                    }));
            case "set-channel" -> {
                EntitySelectMenu channelMenu = EntitySelectMenu
                        .create(SETTINGS_WORD_CHAIN_CHANNEL_PREFIX + token, EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT)
                        .setRequiredRange(1, 1)
                        .setPlaceholder(i18n.get().t(lang, "settings.word_chain_menu_channel_placeholder"))
                        .build();
                event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(46, 204, 113))
                                .setTitle(i18n.get().t(lang, "settings.word_chain_menu_pick_channel_title"))
                                .setDescription(i18n.get().t(lang, "settings.word_chain_menu_pick_channel_desc"))
                                .build())
                        .setComponents(ActionRow.of(channelMenu))
                        .queue();
            }
            case OPTION_RESET -> event.deferEdit().queue(ignored -> wordChainOps.reset(guildId)
                    .thenAccept(status -> {
                        String changed = i18n.get().t(lang, "general.settings_saved",
                                Map.of("key", i18n.get().t(lang, "settings.info_key_word_chain_chain_count"),
                                        OPTION_VALUE, "0"));
                        event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, status, changed).build())
                                .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, status)))
                                .queue();
                    })
                    .exceptionally(error -> {
                        event.getHook().editOriginal(i18n.get().t(lang, "general.action_failed")).queue();
                        return null;
                    }));
            default -> event.reply(i18n.get().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
        }
    }

    public void handleWordChainChannelSelect(EntitySelectInteractionEvent event, String lang) {
        if (wordChainOps == null) {
            event.reply(i18n.get().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String token = event.getComponentId().substring(SETTINGS_WORD_CHAIN_CHANNEL_PREFIX.length());
        MenuRequest request = wordChainMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            wordChainMenuRequests.remove(token);
            event.reply(i18n.get().t(lang, "settings.word_chain_menu_expired"))
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
        List<IMentionable> values = event.getValues();
        if (values.isEmpty() || !(values.get(0) instanceof TextChannel channel)) {
            event.reply(i18n.get().t(lang, "settings.validation_expected_text_channel"))
                    .setEphemeral(true).queue();
            return;
        }
        event.deferEdit().queue(ignored -> wordChainOps.setChannel(event.getGuild().getIdLong(), channel.getIdLong())
                .thenAccept(status -> {
                    String changed = i18n.get().t(lang, "general.settings_saved",
                            Map.of("key", i18n.get().t(lang, "settings.info_key_word_chain_channel"),
                                    OPTION_VALUE, channel.getAsMention()));
                    event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, status, changed).build())
                            .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, status)))
                            .queue();
                })
                .exceptionally(error -> {
                    event.getHook().editOriginal(i18n.get().t(lang, "general.action_failed")).queue();
                    return null;
                }));
    }

    private StringSelectMenu settingsWordChainMenu(String token, Guild guild, String lang, WordChainStatusSnapshot status) {
        return StringSelectMenu.create(SETTINGS_WORD_CHAIN_SELECT_PREFIX + token)
                .setPlaceholder(i18n.get().t(lang, "settings.word_chain_menu_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.get().t(lang, "settings.info_key_word_chain_enabled"), "enable-toggle")
                                .withDescription(i18n.get().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, boolText(lang, status.enabled())))),
                        SelectOption.of(i18n.get().t(lang, "settings.info_key_word_chain_channel"), "set-channel")
                                .withDescription(i18n.get().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, uiText.limitText(uiText.formatTextChannel(guild, status.channelId(), lang), 60)))),
                        SelectOption.of(i18n.get().t(lang, "settings.info_key_word_chain_next"), OPTION_RESET)
                                .withDescription(i18n.get().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, wordChainNextLetter(status))))
                )
                .build();
    }

    private EmbedBuilder wordChainMenuEmbed(Guild guild, String lang, WordChainStatusSnapshot status, String changedText) {
        String body = String.join("\n\n",
                uiText.quotedSettingLine(lang, "settings.info_key_word_chain_enabled", "settings.status_label",
                        boolText(lang, status.enabled())),
                uiText.quotedSettingLine(lang, "settings.info_key_word_chain_channel", "settings.value_label",
                        uiText.formatTextChannel(guild, status.channelId(), lang)),
                uiText.quotedSettingLine(lang, "settings.info_key_word_chain_last_word", "settings.value_label",
                        status.lastWord() == null || status.lastWord().isBlank() ? "-" : status.lastWord()),
                uiText.quotedSettingLine(lang, "settings.info_key_word_chain_next", "settings.value_label",
                        wordChainNextLetter(status)),
                uiText.quotedSettingLine(lang, "settings.info_key_word_chain_chain_count", "settings.value_label",
                        String.valueOf(status.chainCount()))
        );
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(46, 204, 113))
                .setTitle("🔠 " + i18n.get().t(lang, "settings.word_chain_menu_title"))
                .setDescription(i18n.get().t(lang, "settings.word_chain_menu_desc"))
                .addField("🔠 " + i18n.get().t(lang, "settings.info_word_chain"), body, false);
        if (changedText != null && !changedText.isBlank()) {
            eb.addField(i18n.get().t(lang, "settings.template_updated"), changedText, false);
        }
        return eb;
    }

    private static String wordChainNextLetter(WordChainStatusSnapshot status) {
        if (status == null || status.nextRequiredStartLetter() == null) {
            return "-";
        }
        return String.valueOf(status.nextRequiredStartLetter());
    }

    private String registerMenuRequest(long requestUserId, long guildId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        wordChainMenuRequests.put(token, new MenuRequest(requestUserId, guildId,
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
