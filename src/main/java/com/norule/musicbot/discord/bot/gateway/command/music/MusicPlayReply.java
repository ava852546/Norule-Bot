package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelModeResolver;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import com.norule.musicbot.discord.bot.gateway.DeferredInteractionResponse;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns the /play response target, keeping fallback warnings separate from results. */
final class MusicPlayReply {
    private final DeferredInteractionResponse response;
    private final boolean followup;

    private MusicPlayReply(DeferredInteractionResponse response, boolean followup) {
        this.response = response;
        this.followup = followup;
    }

    static void acknowledge(IReplyCallback interaction, MusicPanelModeResolver.Selection mode,
                            Supplier<String> notice, ScheduledExecutorService scheduler, String failureText,
                            Consumer<MusicPlayReply> continuation) {
        boolean fallback = mode.fallback();
        // The warning is the original response; playback/search remain independent private results.
        DeferredInteractionResponse.acknowledge(interaction,
                () -> fallback ? interaction.reply(notice.get()).setEphemeral(true) : interaction.deferReply(true),
                "EPHEMERAL", "play", scheduler, Duration.ofSeconds(60),
                hook -> fallback ? hook.sendMessage(failureText).setEphemeral(true) : hook.editOriginal(failureText),
                response -> continuation.accept(new MusicPlayReply(response, fallback)));
    }

    void sendText(String text) {
        response.complete(hook -> followup ? hook.sendMessage(text).setEphemeral(true) : hook.editOriginal(text));
    }

    void sendPanelError(String text) {
        response.auxiliary(hook -> hook.sendMessage(text).setEphemeral(true));
    }

    void sendSearch(MessageEmbed embed, ActionRow menu, Consumer<Message> success) {
        response.complete(hook -> followup
                ? hook.sendMessageEmbeds(embed).setComponents(menu).setEphemeral(true)
                : hook.editOriginalEmbeds(embed).setComponents(menu), success);
    }

    void editSearch(String messageId, MessageEmbed embed, ActionRow menu) {
        response.auxiliary(hook -> hook.editMessageEmbedsById(messageId, embed).setComponents(menu));
    }
}
