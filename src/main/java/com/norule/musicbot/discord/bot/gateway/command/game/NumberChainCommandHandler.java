package com.norule.musicbot.discord.bot.gateway.command.game;

import com.norule.musicbot.ModerationService;
import com.norule.musicbot.discord.bot.gateway.command.settings.view.SettingsUiText;
import com.norule.musicbot.i18n.I18nService;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

public final class NumberChainCommandHandler {
    private final ModerationService moderationService;
    private final Supplier<I18nService> i18n;
    private final SettingsUiText uiText;

    public NumberChainCommandHandler(ModerationService moderationService,
                                     Supplier<I18nService> i18n,
                                     SettingsUiText uiText) {
        this.moderationService = Objects.requireNonNull(moderationService, "moderationService");
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        this.uiText = Objects.requireNonNull(uiText, "uiText");
    }

    public void handleNumberChainSlash(SlashCommandInteractionEvent event, String lang) {
        event.replyEmbeds(numberChainStatusEmbed(event.getGuild(), lang).build())
                .setEphemeral(false)
                .queue();
    }

    private EmbedBuilder numberChainStatusEmbed(Guild guild, String lang) {
        long guildId = guild.getIdLong();
        boolean enabled = moderationService.isNumberChainEnabled(guildId);
        Long channelId = moderationService.getNumberChainChannelId(guildId);
        long next = moderationService.getNumberChainNext(guildId);
        long highest = moderationService.getNumberChainHighestNumber(guildId);

        String enabledText = enabled
                ? i18n.get().t(lang, "settings.info_bool_on")
                : i18n.get().t(lang, "settings.info_bool_off");
        String channelText = uiText.formatTextChannel(guild, channelId, lang);

        Color color = enabled ? new Color(46, 204, 113) : new Color(149, 165, 166);

        return new EmbedBuilder()
                .setColor(color)
                .setTitle("🔢 " + i18n.get().t(lang, "number_chain.status_title"))
                .setDescription(i18n.get().t(lang, "number_chain.status_desc"))
                .addField("🔢 " + i18n.get().t(lang, "settings.info_key_number_chain_enabled"),
                        enabledText, true)
                .addField("📍 " + i18n.get().t(lang, "settings.info_key_number_chain_channel"),
                        channelText, true)
                .addField("🔢 " + i18n.get().t(lang, "settings.info_key_number_chain_next"),
                        "`" + next + "`", true)
                .addField("🏆 " + uiText.numberChainHighestLabel(lang),
                        "`" + highest + "`", true)
                .addField("👥 " + uiText.numberChainTopContributorsLabel(lang),
                        uiText.formatNumberChainTopContributors(guild, lang), false)
                .addField("📖 " + rulesLabel(lang),
                        i18n.get().t(lang, "number_chain.rules_summary"), false)
                .setTimestamp(Instant.now());
    }

    private String rulesLabel(String lang) {
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "游戏规则";
        }
        if (lang != null && lang.toLowerCase().startsWith("zh")) {
            return "遊戲規則";
        }
        return "Rules";
    }
}
