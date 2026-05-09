package com.norule.musicbot.discord.bot.gateway.command.game;

import com.norule.musicbot.discord.bot.ops.wordchain.WordChainOps;
import com.norule.musicbot.domain.wordchain.WordChainStatusSnapshot;
import com.norule.musicbot.i18n.I18nService;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

public final class WordChainCommandHandler {
    private final WordChainOps wordChainOps;
    private final Supplier<I18nService> i18n;

    public WordChainCommandHandler(WordChainOps wordChainOps, Supplier<I18nService> i18n) {
        this.wordChainOps = Objects.requireNonNull(wordChainOps, "wordChainOps");
        this.i18n = Objects.requireNonNull(i18n, "i18n");
    }

    public void handleWordChainSlash(SlashCommandInteractionEvent event, String lang) {
        Guild guild = event.getGuild();
        event.deferReply(false).queue(hook ->
            wordChainOps.status(guild.getIdLong())
                .thenAccept(status -> hook.editOriginalEmbeds(statusEmbed(guild, status, lang).build()).queue())
                .exceptionally(error -> {
                    hook.editOriginal(i18n.get().t(lang, "general.unknown_command")).queue();
                    return null;
                })
        );
    }

    private EmbedBuilder statusEmbed(Guild guild, WordChainStatusSnapshot status, String lang) {
        String channelText = status.channelId() == null
                ? i18n.get().t(lang, "settings.info_channels_none")
                : "<#" + status.channelId() + ">";
        String lastWord = (status.lastWord() == null || status.lastWord().isBlank())
                ? "-" : "`" + status.lastWord() + "`";
        String nextLetter = status.nextRequiredStartLetter() == null
                ? "-" : "`" + status.nextRequiredStartLetter() + "`";
        String enabledText = status.enabled()
                ? i18n.get().t(lang, "settings.info_bool_on")
                : i18n.get().t(lang, "settings.info_bool_off");

        Color color = status.enabled() ? new Color(52, 152, 219) : new Color(149, 165, 166);

        return new EmbedBuilder()
                .setColor(color)
                .setTitle("🔠 " + i18n.get().t(lang, "word_chain.status_title"))
                .setDescription(i18n.get().t(lang, "word_chain.status_desc"))
                .addField("🔠 " + i18n.get().t(lang, "settings.info_key_word_chain_enabled"),
                        enabledText, true)
                .addField("📍 " + i18n.get().t(lang, "settings.info_key_word_chain_channel"),
                        channelText, true)
                .addField("🔤 " + i18n.get().t(lang, "settings.info_key_word_chain_last_word"),
                        lastWord, true)
                .addField("🔡 " + i18n.get().t(lang, "settings.info_key_word_chain_next"),
                        nextLetter, true)
                .addField("🔢 " + i18n.get().t(lang, "settings.info_key_word_chain_chain_count"),
                        "`" + status.chainCount() + "`", true)
                .addField("📖 " + rulesLabel(lang),
                        i18n.get().t(lang, "word_chain.rules_summary"), false)
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
