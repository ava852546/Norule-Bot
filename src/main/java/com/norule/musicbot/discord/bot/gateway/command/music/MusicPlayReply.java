package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelModeResolver;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Owns the /play response target, keeping fallback warnings separate from results. */
final class MusicPlayReply {
    private final InteractionHook hook;
    private final boolean followup;

    private MusicPlayReply(InteractionHook hook, boolean followup) {
        this.hook = hook;
        this.followup = followup;
    }

    static void acknowledge(IReplyCallback interaction, MusicPanelModeResolver.Selection mode,
                            Supplier<MessageEmbed> notice, Consumer<MusicPlayReply> continuation) {
        boolean fallback = mode.fallback();
        // A sendMessage immediately after deferReply can consume the original response.
        // Finish the warning ACK first, then allow playback to send independent follow-ups.
        var acknowledgement = fallback
                ? interaction.replyEmbeds(notice.get()).setEphemeral(true)
                : interaction.deferReply(true);
        acknowledgement.queue(hook -> continuation.accept(new MusicPlayReply(hook, fallback)),
                failure -> org.slf4j.LoggerFactory.getLogger(MusicPlayReply.class).debug(
                        "Music play acknowledgement failed: reason={}", MusicPanelModeResolver.failureReason(failure)));
    }

    static MessageEmbed fallbackNotice(Function<String, String> text) {
        return new EmbedBuilder()
                .setColor(0xF0B232)
                .setTitle(text.apply("panel_fallback_title"))
                .setDescription(text.apply("panel_fallback_description"))
                .setFooter(text.apply("panel_fallback_footer"))
                .build();
    }

    void sendText(String text) {
        if (followup) {
            hook.sendMessage(text).setEphemeral(true).queue();
        } else {
            hook.editOriginal(text).queue();
        }
    }

    void sendSearch(MessageEmbed embed, ActionRow menu, Consumer<Message> success) {
        if (followup) {
            hook.sendMessageEmbeds(embed).setComponents(menu).setEphemeral(true).queue(success);
        } else {
            hook.editOriginalEmbeds(embed).setComponents(menu).queue(success);
        }
    }

    void editSearch(String messageId, MessageEmbed embed, ActionRow menu) {
        hook.editMessageEmbedsById(messageId, embed).setComponents(menu).queue(success -> {
        }, failure -> {
            // The ephemeral search message may already have been dismissed.
        });
    }
}
