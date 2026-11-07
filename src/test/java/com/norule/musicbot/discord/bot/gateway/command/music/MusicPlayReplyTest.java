package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelModeResolver;
import com.norule.musicbot.domain.music.MusicPanelMode;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MusicPlayReplyTest {
    private static final MusicPanelModeResolver.Selection FALLBACK = new MusicPanelModeResolver.Selection(
            MusicPanelMode.MODERN, MusicPanelMode.LEGACY, null, null, "NOT_CONFIGURED");
    private static final MessageEmbed WARNING = new EmbedBuilder().setTitle("Configuration warning").build();

    @Test
    void successAndAllFailureResultsLeaveAnIndependentEphemeralWarning() {
        for (String result : List.of("Queued", "Started", "No match", "Load failed", "Join first", "Missing permissions")) {
            var discord = new Discord();
            var reply = discord.start(FALLBACK);
            assertNull(reply.get(), "Playback must wait for the ACK callback");
            assertEquals(1, discord.acknowledgements);
            assertFalse(discord.deferred);
            discord.finishAck();
            reply.get().sendText(result);
            assertEquals(Map.of("original", WARNING, "followup-1", result), discord.messages);
            assertTrue(discord.ephemeral.values().stream().allMatch(Boolean::booleanValue));
            assertEquals(0, discord.originalEdits);
            assertEquals(1, discord.acknowledgements);
        }
    }

    @Test
    void panelFailureIsAnIndependentEphemeralFollowupAfterPlaybackSuccess() {
        var discord = new Discord();
        var reply = discord.start(FALLBACK);
        discord.finishAck();
        reply.get().sendText("Started");
        reply.get().sendPanelError("Panel unavailable");
        assertEquals(Map.of("original", WARNING, "followup-1", "Started", "followup-2", "Panel unavailable"),
                discord.messages);
        assertTrue(discord.ephemeral.values().stream().allMatch(Boolean::booleanValue));
        assertEquals(0, discord.originalEdits);
    }

    @Test
    void searchAndExpiryEditTheFollowupIdAndRetainTheWarning() {
        var discord = new Discord();
        var reply = discord.start(FALLBACK);
        discord.finishAck();
        var search = new EmbedBuilder().setTitle("Search results").build();
        var expired = new EmbedBuilder().setTitle("Search expired").build();
        var menu = ActionRow.of(StringSelectMenu.create("play:pick:test").addOption("Track", "0").build());
        AtomicReference<String> messageId = new AtomicReference<>();
        reply.get().sendSearch(search, menu, message -> messageId.set(message.getId()));
        assertEquals("followup-1", messageId.get());
        assertEquals(search, discord.messages.get(messageId.get()));
        reply.get().editSearch(messageId.get(), expired, menu.asDisabled());
        assertEquals(Map.of("original", WARNING, "followup-1", expired), discord.messages);
        assertTrue(discord.ephemeral.get(messageId.get()));
        assertEquals(0, discord.originalEdits);
    }

    @Test
    void everyPlayWarnsUntilValidModernOrExplicitLegacyAndThenUsesOriginalResponse() {
        var modern = new MusicPanelModeResolver.Selection(
                MusicPanelMode.MODERN, MusicPanelMode.MODERN, 10L, null, "AVAILABLE");
        var legacy = new MusicPanelModeResolver.Selection(
                MusicPanelMode.LEGACY, MusicPanelMode.LEGACY, null, null, "LEGACY_MODE");
        for (var mode : List.of(FALLBACK, FALLBACK, modern, legacy, FALLBACK)) {
            var discord = new Discord();
            var reply = discord.start(mode);
            discord.finishAck();
            reply.get().sendText("Result");
            assertEquals(mode.fallback() ? 2 : 1, discord.messages.size());
            assertEquals(!mode.fallback(), discord.deferred);
            assertEquals(mode.fallback() ? WARNING : "Result", discord.messages.get("original"));
            assertEquals(mode.fallback() ? 0 : 1, discord.originalEdits);
            assertEquals(1, discord.acknowledgements);
        }
    }

    @Test
    void normalSearchKeepsTheDeferredOriginalResponse() {
        var discord = new Discord();
        var legacy = new MusicPanelModeResolver.Selection(
                MusicPanelMode.LEGACY, MusicPanelMode.LEGACY, null, null, "LEGACY_MODE");
        var reply = discord.start(legacy);
        discord.finishAck();
        var menu = ActionRow.of(StringSelectMenu.create("play:pick:test").addOption("Track", "0").build());
        reply.get().sendSearch(WARNING, menu, message -> assertEquals("original", message.getId()));
        assertEquals(Map.of("original", WARNING), discord.messages);
        assertTrue(discord.ephemeral.get("original"));
        assertEquals(1, discord.originalEdits);
    }

    @Test
    void failedAckDoesNotStartPlaybackOrAttemptAnotherAck() {
        var discord = new Discord();
        var reply = discord.start(FALLBACK);
        discord.ackFailure.accept(new IllegalStateException("Unknown Interaction"));
        assertNull(reply.get());
        assertTrue(discord.messages.isEmpty());
        assertEquals(1, discord.acknowledgements);
    }

    /** Models Discord's deferred first-send compatibility behavior, not just method invocation counts. */
    @SuppressWarnings("unchecked")
    private static final class Discord {
        final Map<String, Object> messages = new LinkedHashMap<>();
        final Map<String, Boolean> ephemeral = new LinkedHashMap<>();
        int acknowledgements;
        int originalEdits;
        boolean deferred;
        boolean acknowledged;
        Consumer<InteractionHook> ackSuccess;
        Consumer<Throwable> ackFailure;
        Object warning;
        boolean ackEphemeral;
        final InteractionHook hook = proxy(InteractionHook.class, (o, method, args) -> {
            assertTrue(acknowledged, "Unknown Interaction: hook used before ACK completed");
            String name = method.getName();
            boolean original = name.equals("editOriginal") || name.equals("editOriginalEmbeds");
            boolean byId = name.equals("editMessageEmbedsById");
            assertTrue(original || byId || name.equals("sendMessage") || name.equals("sendMessageEmbeds"), name);
            String id = original ? "original" : byId ? (String) args[0]
                    : deferred && !messages.containsKey("original") ? "original" : "followup-" + messages.size();
            Object value = byId ? args[1] : args[0];
            if (value instanceof MessageEmbed[] embeds) value = embeds[0];
            Object content = value;
            boolean[] privateMessage = {original || byId ? ephemeral.getOrDefault(id, ackEphemeral) : false};
            return Proxy.newProxyInstance(method.getReturnType().getClassLoader(), new Class<?>[]{method.getReturnType()},
                    (action, m, a) -> {
                        switch (m.getName()) {
                            case "setEphemeral" -> privateMessage[0] = (boolean) a[0];
                            case "setComponents" -> { }
                            case "queue" -> {
                                assertTrue(privateMessage[0], "Unexpected public message");
                                messages.put(id, content);
                                ephemeral.put(id, privateMessage[0]);
                                if (original) originalEdits++;
                                if (a != null && a.length > 0 && a[0] != null) {
                                    Consumer<Message> success = (Consumer<Message>) a[0];
                                    success.accept(proxy(Message.class, (msg, accessor, ignored) -> {
                                        assertEquals("getId", accessor.getName());
                                        return id;
                                    }));
                                }
                                return null;
                            }
                            default -> fail("Unexpected action: " + m.getName());
                        }
                        return action;
                    });
        });

        @SuppressWarnings("unchecked")
        AtomicReference<MusicPlayReply> start(MusicPanelModeResolver.Selection mode) {
            var result = new AtomicReference<MusicPlayReply>();
            IReplyCallback interaction = proxy(IReplyCallback.class, (o, method, args) -> {
                assertEquals(0, acknowledgements++, "Interaction Already Acknowledged");
                deferred = method.getName().equals("deferReply");
                assertTrue(deferred || method.getName().equals("replyEmbeds"));
                if (deferred) ackEphemeral = (boolean) args[0];
                else warning = args[0];
                return proxy(ReplyCallbackAction.class, (action, m, a) -> {
                    if (m.getName().equals("setEphemeral")) {
                        ackEphemeral = (boolean) a[0];
                        return action;
                    }
                    assertEquals("queue", m.getName());
                    ackSuccess = (Consumer<InteractionHook>) a[0];
                    ackFailure = (Consumer<Throwable>) a[1];
                    return null;
                });
            });
            MusicPlayReply.acknowledge(interaction, mode, () -> {
                assertTrue(mode.fallback(), "Normal modes must not build a notice");
                return WARNING;
            }, result::set);
            return result;
        }

        void finishAck() {
            assertTrue(ackEphemeral);
            acknowledged = true;
            if (!deferred) {
                messages.put("original", warning);
                ephemeral.put("original", true);
            }
            ackSuccess.accept(hook);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
