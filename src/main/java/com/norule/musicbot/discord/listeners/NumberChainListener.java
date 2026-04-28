package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.*;
import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.web.*;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class NumberChainListener extends ListenerAdapter {
    private static final String EMOJI_ACCEPTED_DEFAULT = "\u2705";
    private static final String EMOJI_ACCEPTED_100 = "\uD83D\uDCAF";
    private static final String EMOJI_ACCEPTED_101_200 = "\u2611\uFE0F";
    private static final String EMOJI_REJECTED = "\u274C";
    private static final String EMOJI_IGNORED_TEXT = "<:sus:1497064294720864349>";
    private final GuildSettingsService settingsService;
    private final ModerationService moderationService;
    private final I18nService i18n;
    private final Supplier<BotConfig> configSupplier;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public NumberChainListener(GuildSettingsService settingsService,
                               ModerationService moderationService,
                               I18nService i18n,
                               Supplier<BotConfig> configSupplier) {
        this.settingsService = settingsService;
        this.moderationService = moderationService;
        this.i18n = i18n;
        this.configSupplier = configSupplier;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }

        long guildId = event.getGuild().getIdLong();
        ModerationService.NumberChainResult result = moderationService.processNumberChainMessage(
                guildId,
                event.getChannel().getIdLong(),
                event.getAuthor().getIdLong(),
                event.getMessage().getContentRaw()
        );
        if (result.getType() == ModerationService.NumberChainType.IGNORED) {
            return;
        }

        if (result.getType() == ModerationService.NumberChainType.IGNORED_TEXT) {
            event.getMessage().addReaction(Emoji.fromFormatted(EMOJI_IGNORED_TEXT)).queue(ignored -> {
            }, error -> {
            });
            return;
        }

        if (result.getType() == ModerationService.NumberChainType.ACCEPTED) {
            scheduler.schedule(
                    () -> event.getMessage()
                            .addReaction(Emoji.fromUnicode(acceptedEmoji(result.getExpected())))
                            .queue(ignored -> {
                            }, error -> {
                            }),
                    getAcceptedReactionDelayMs(),
                    TimeUnit.MILLISECONDS
            );
            return;
        }

        String lang = settingsService.getLanguage(guildId);
        Member member = event.getMember();
        String user = member == null ? event.getAuthor().getAsTag() : member.getAsMention();
        if (result.getType() == ModerationService.NumberChainType.REJECT_SAME_USER) {
            event.getMessage().addReaction(Emoji.fromUnicode(EMOJI_REJECTED)).queue(ignored -> {
            }, error -> {
            });
            event.getChannel().sendMessage(i18n.t(lang, "number_chain.same_user_reset_notice",
                    Map.of(
                            "user", user,
                            "expected", String.valueOf(result.getExpected())
                    ))).queue();
            return;
        }

        String input = result.getParsedValue() == null ? i18n.t(lang, "number_chain.invalid_input")
                : String.valueOf(result.getParsedValue());
        event.getMessage().addReaction(Emoji.fromUnicode(EMOJI_REJECTED)).queue(ignored -> {
        }, error -> {
        });
        event.getChannel().sendMessage(i18n.t(lang, "number_chain.reset_notice",
                Map.of(
                        "user", user,
                        "expected", String.valueOf(result.getExpected()),
                        "input", input
                ))).queue();
    }

    private static String acceptedEmoji(long acceptedNumber) {
        if (acceptedNumber == 100L) {
            return EMOJI_ACCEPTED_100;
        }
        if (acceptedNumber >= 101L && acceptedNumber <= 200L) {
            return EMOJI_ACCEPTED_101_200;
        }
        return EMOJI_ACCEPTED_DEFAULT;
    }

    private long getAcceptedReactionDelayMs() {
        try {
            BotConfig config = configSupplier == null ? null : configSupplier.get();
            if (config == null) {
                return 500L;
            }
            return Math.max(0L, config.getNumberChainReactionDelayMillis());
        } catch (Exception ignored) {
            return 500L;
        }
    }
}
