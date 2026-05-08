package com.norule.musicbot.discord.bot.gateway.command.settings.menu;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import com.norule.musicbot.discord.bot.gateway.command.settings.view.SettingsUiText;
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

public final class SettingsNumberChainMenuHandler {
    private static final String SETTINGS_NUMBER_CHAIN_SELECT_PREFIX  = ComponentIds.SETTINGS_NUMBER_CHAIN_SELECT_PREFIX;
    private static final String SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX = ComponentIds.SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX;

    private static final String OPTION_VALUE   = CommandOptions.VALUE;
    private static final String OPTION_CHANNEL = CommandOptions.CHANNEL;
    private static final String OPTION_RESET   = CommandOptions.RESET;

    private static final String KEY_UNKNOWN_COMMAND       = "general.unknown_command";
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";

    private final MusicCommandService owner;
    private final SettingsUiText uiText;
    private final ConcurrentHashMap<String, MenuRequest> numberChainMenuRequests = new ConcurrentHashMap<>();

    public SettingsNumberChainMenuHandler(MusicCommandService owner) {
        this.owner = owner;
        this.uiText = new SettingsUiText(owner);
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        numberChainMenuRequests.entrySet().removeIf(e -> e.getValue() == null || cutoff.isAfter(e.getValue().expiresAt));
    }

    public void openNumberChainMenu(SlashCommandInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.replyEmbeds(numberChainMenuEmbed(event.getGuild(), lang, null).build())
                .addComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                .setEphemeral(true)
                .queue();
    }

    public void openNumberChainMenu(StringSelectInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.editMessageEmbeds(numberChainMenuEmbed(event.getGuild(), lang, null).build())
                .setComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                .queue();
    }

    public void handleNumberChainMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_NUMBER_CHAIN_SELECT_PREFIX.length());
        MenuRequest request = numberChainMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            numberChainMenuRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "settings.number_chain_menu_expired"))
                    .setEphemeral(true).queue();
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

        long guildId = event.getGuild().getIdLong();
        String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        switch (action) {
            case "enable-toggle" -> {
                boolean value = !owner.moderationService().isNumberChainEnabled(guildId);
                owner.moderationService().setNumberChainEnabled(guildId, value);
                String changed = owner.i18nService().t(lang, "general.settings_saved",
                        Map.of("key", owner.i18nService().t(lang, "settings.info_key_number_chain_enabled"),
                               OPTION_VALUE, owner.boolText(lang, value)));
                event.editMessageEmbeds(numberChainMenuEmbed(event.getGuild(), lang, changed).build())
                        .setComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                        .queue();
            }
            case "set-channel" -> {
                EntitySelectMenu channelMenu = EntitySelectMenu
                        .create(SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX + token, EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT)
                        .setRequiredRange(1, 1)
                        .setPlaceholder(owner.i18nService().t(lang, "settings.number_chain_menu_channel_placeholder"))
                        .build();
                event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(46, 204, 113))
                                .setTitle(owner.i18nService().t(lang, "settings.number_chain_menu_pick_channel_title"))
                                .setDescription(owner.i18nService().t(lang, "settings.number_chain_menu_pick_channel_desc"))
                                .build())
                        .setComponents(ActionRow.of(channelMenu))
                        .queue();
            }
            case OPTION_RESET -> {
                owner.moderationService().resetNumberChain(guildId);
                String changed = owner.i18nService().t(lang, "number_chain.result_reset");
                event.editMessageEmbeds(numberChainMenuEmbed(event.getGuild(), lang, changed).build())
                        .setComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                        .queue();
            }
            default -> event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
        }
    }

    public void handleNumberChainChannelSelect(EntitySelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX.length());
        MenuRequest request = numberChainMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            numberChainMenuRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "settings.number_chain_menu_expired"))
                    .setEphemeral(true).queue();
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

        List<IMentionable> values = event.getValues();
        if (values.isEmpty() || !(values.get(0) instanceof TextChannel channel)) {
            event.reply(owner.i18nService().t(lang, "settings.validation_expected_text_channel"))
                    .setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        owner.moderationService().setNumberChainChannelId(guildId, channel.getIdLong());
        owner.moderationService().resetNumberChain(guildId);
        String changed = owner.i18nService().t(lang, "number_chain.result_set_channel",
                Map.of(OPTION_CHANNEL, channel.getAsMention()));
        event.editMessageEmbeds(numberChainMenuEmbed(event.getGuild(), lang, changed).build())
                .setComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                .queue();
    }

    private StringSelectMenu settingsNumberChainMenu(String token, Guild guild, String lang) {
        long guildId = guild.getIdLong();
        boolean enabled  = owner.moderationService().isNumberChainEnabled(guildId);
        Long channelId   = owner.moderationService().getNumberChainChannelId(guildId);
        long next        = owner.moderationService().getNumberChainNext(guildId);
        return StringSelectMenu.create(SETTINGS_NUMBER_CHAIN_SELECT_PREFIX + token)
                .setPlaceholder(owner.i18nService().t(lang, "settings.number_chain_menu_placeholder"))
                .addOptions(
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_key_number_chain_enabled"), "enable-toggle")
                                .withDescription(owner.i18nService().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, owner.boolText(lang, enabled)))),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_key_number_chain_channel"), "set-channel")
                                .withDescription(owner.i18nService().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, uiText.limitText(uiText.formatTextChannel(guild, channelId, lang), 60)))),
                        SelectOption.of(owner.i18nService().t(lang, "settings.info_key_number_chain_next"), OPTION_RESET)
                                .withDescription(owner.i18nService().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, String.valueOf(next))))
                )
                .build();
    }

    private EmbedBuilder numberChainMenuEmbed(Guild guild, String lang, String changedText) {
        long guildId = guild.getIdLong();
        String body = String.join("\n\n",
                uiText.quotedSettingLine(lang, "settings.info_key_number_chain_enabled", "settings.status_label",
                        owner.boolText(lang, owner.moderationService().isNumberChainEnabled(guildId))),
                uiText.quotedSettingLine(lang, "settings.info_key_number_chain_channel", "settings.value_label",
                        uiText.formatTextChannel(guild, owner.moderationService().getNumberChainChannelId(guildId), lang)),
                uiText.quotedSettingLine(lang, "settings.info_key_number_chain_next", "settings.value_label",
                        String.valueOf(owner.moderationService().getNumberChainNext(guildId))),
                "🏆 " + uiText.numberChainHighestLabel(lang) + "\n> "
                        + owner.i18nService().t(lang, "settings.value_label") + ": "
                        + owner.moderationService().getNumberChainHighestNumber(guildId),
                "👥 " + uiText.numberChainTopContributorsLabel(lang) + "\n> "
                        + uiText.formatNumberChainTopContributors(guild, lang)
        );
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(46, 204, 113))
                .setTitle("🔢 " + owner.i18nService().t(lang, "settings.number_chain_menu_title"))
                .setDescription(owner.i18nService().t(lang, "settings.number_chain_menu_desc"))
                .addField("🔢 " + owner.i18nService().t(lang, "settings.info_number_chain"), body, false);
        if (changedText != null && !changedText.isBlank()) {
            eb.addField(owner.i18nService().t(lang, "settings.template_updated"), changedText, false);
        }
        return eb;
    }

    private String registerMenuRequest(long requestUserId, long guildId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        numberChainMenuRequests.put(token, new MenuRequest(requestUserId, guildId,
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
