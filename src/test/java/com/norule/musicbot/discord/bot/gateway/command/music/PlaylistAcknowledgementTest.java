package com.norule.musicbot.discord.bot.gateway.command.music;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PlaylistAcknowledgementTest {
    private static final java.util.concurrent.atomic.AtomicLong IDS = new java.util.concurrent.atomic.AtomicLong();
    @Test
    void autocompleteFiltersAndNeverReturnsMoreThanTwentyFiveChoices() {
        var summaries = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> new com.norule.musicbot.domain.music.MusicDataService.PlaylistSummary(
                        "Playlist " + i, i, 0L, 123L, "owner")).toList();
        assertEquals(25, PlaylistCommandHandler.playlistChoices(summaries, "playlist").size());
        var choices = PlaylistCommandHandler.playlistChoices(summaries, "playlist 39");
        assertEquals(1, choices.size());
        assertEquals("Playlist 39", choices.getFirst().getAsString());
        assertTrue(PlaylistCommandHandler.playlistChoices(summaries, "missing").isEmpty());
    }
    @Test
    void slashDefersPrivatelyBeforeAnyOwnerOrDatabaseWorkAndNeverQueuesTwoAcks() {
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var work = new PlaylistInteractionWork(timer, Clock.systemUTC());
             var handler = new PlaylistCommandHandler(null, null, work)) {
            var submitted = new AtomicInteger();
            var defers = new AtomicInteger();
            var privateReplies = new AtomicInteger();
            ReplyCallbackAction action = proxy(ReplyCallbackAction.class, (method, args) -> {
                if (method.equals("setEphemeral")) {
                    assertEquals(true, args[0], "Private playlist data must remain ephemeral");
                    privateReplies.incrementAndGet();
                }
                if (method.equals("queue")) {
                    submitted.incrementAndGet();
                    assertEquals(2, args.length, "Both ACK success and failure must be handled");
                    // REST delay: do not invoke success. No database work may start yet.
                }
                return null;
            });
            long id = ((System.currentTimeMillis() - 1420070400000L) << 22) | IDS.incrementAndGet();
            SlashCommandInteraction interaction = proxy(SlashCommandInteraction.class, (method, args) -> {
                if (method.equals("getGuild")) return null;
                if (method.equals("getSubcommandName")) return "load";
                if (method.equals("getIdLong")) return id;
                if (method.equals("isAcknowledged")) return false;
                if (method.equals("deferReply")) {
                    defers.incrementAndGet();
                    return action;
                }
                throw new AssertionError("No pre-ACK work allowed: " + method);
            });
            var event = new SlashCommandInteractionEvent(proxy(JDA.class, (m, a) -> null), 0, interaction);
            handler.handlePlaylistSlash(event, "en");
            handler.handlePlaylistSlash(event, "en");
            assertEquals(1, defers.get());
            assertEquals(1, submitted.get());
            assertEquals(1, privateReplies.get());
        }
    }

    @Test
    void failedInitialAckDoesNotDispatchOrRetry() {
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var work = new PlaylistInteractionWork(timer, Clock.systemUTC());
             var handler = new PlaylistCommandHandler(null, null, work)) {
            var submitted = new AtomicInteger();
            ReplyCallbackAction action = proxy(ReplyCallbackAction.class, (method, args) -> {
                if (method.equals("queue")) {
                    submitted.incrementAndGet();
                    @SuppressWarnings("unchecked") Consumer<Throwable> failure = (Consumer<Throwable>) args[1];
                    failure.accept(new java.util.concurrent.TimeoutException());
                }
                return null;
            });
            long id = ((System.currentTimeMillis() - 1420070400000L) << 22) | IDS.incrementAndGet();
            var interaction = proxy(SlashCommandInteraction.class, (method, args) -> switch (method) {
                case "getGuild" -> null;
                case "getSubcommandName" -> "load";
                case "getIdLong" -> id;
                case "isAcknowledged" -> false;
                case "deferReply" -> action;
                default -> throw new AssertionError(method);
            });
            handler.handlePlaylistSlash(new SlashCommandInteractionEvent(proxy(JDA.class, (m, a) -> null), 0, interaction), "en");
            assertEquals(1, submitted.get());
        }
    }

    private interface Call { Object invoke(String method, Object[] args); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Object result = call.invoke(method.getName(), args);
            return result == null && method.getReturnType().isInstance(proxy) ? proxy : result;
        });
    }
}
