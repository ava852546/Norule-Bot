package com.norule.musicbot;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.Map;

public class NumberChainListener extends ListenerAdapter {
    private final GuildSettingsService settingsService;
    private final ModerationService moderationService;
    private final I18nService i18n;

    public NumberChainListener(GuildSettingsService settingsService,
                               ModerationService moderationService,
                               I18nService i18n) {
        this.settingsService = settingsService;
        this.moderationService = moderationService;
        this.i18n = i18n;
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

        if (result.getType() == ModerationService.NumberChainType.ACCEPTED) {
            event.getMessage().addReaction(Emoji.fromUnicode("\u2705")).queue(ignored -> {
            }, error -> {
            });
            return;
        }

        String lang = settingsService.getLanguage(guildId);
        Member member = event.getMember();
        String user = member == null ? event.getAuthor().getAsTag() : member.getAsMention();
        if (result.getType() == ModerationService.NumberChainType.REJECT_SAME_USER) {
            event.getMessage().addReaction(Emoji.fromUnicode("\u274C")).queue(ignored -> {
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
        event.getMessage().addReaction(Emoji.fromUnicode("\u274C")).queue(ignored -> {
        }, error -> {
        });
        event.getChannel().sendMessage(i18n.t(lang, "number_chain.reset_notice",
                Map.of(
                        "user", user,
                        "expected", String.valueOf(result.getExpected()),
                        "input", input
                ))).queue();
    }
}
