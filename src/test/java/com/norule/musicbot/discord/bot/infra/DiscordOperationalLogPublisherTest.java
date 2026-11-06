package com.norule.musicbot.discord.bot.infra;

import com.norule.musicbot.config.domain.OperationalLoggingConfig;
import com.norule.musicbot.domain.monitoring.JvmResourceSample;
import com.norule.musicbot.domain.monitoring.VoiceAutoLeaveEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DiscordOperationalLogPublisherTest {
    @TempDir Path directory;
    private int sends;
    private int edits;
    private boolean allowed = true;
    private boolean hold;
    private boolean connected = true;
    private boolean channelPresent = true;
    private Throwable nextFailure;
    private Message saved;
    private final List<Object> mentionPolicies = new ArrayList<>();

    @Test void formatsMetricsWithoutInventingUnavailableValues() {
        var embed = DiscordOperationalLogPublisher.jvmEmbed(sample());
        assertEquals("JVM Resource Monitor", embed.getTitle());
        assertEquals("NoRule Bot · JVM Monitoring", embed.getFooter().getText());
        assertEquals(sample().timestamp(), embed.getTimestamp().toInstant());
        assertTrue(embed.getFields().get(0).getValue().contains("0.76%"));
        assertTrue(embed.getFields().get(0).getValue().contains("N/A"));
        assertTrue(embed.getFields().get(1).getValue().contains("1.00 MiB"));
        assertTrue(embed.getFields().get(1).getValue().contains("Heap Max: N/A"));
        assertTrue(embed.getFields().get(2).getValue().contains("Pause Count: N/A"));
        assertTrue(embed.getFields().get(3).getValue().contains("RUNNABLE: 2"));
        assertTrue(embed.getFields().get(0).isInline());
        assertTrue(embed.getFields().get(2).isInline());
        assertTrue(embed.getFields().get(3).isInline());
    }

    @Test void omitsUnknownVoiceFieldsAndUsesEventColors() {
        var event = new VoiceAutoLeaveEvent(Instant.now(), "TIMER_STARTED", "EMPTY_CHANNEL", 1, "guild", 2, "voice", 0L, null, 10L);
        var embed = DiscordOperationalLogPublisher.voiceEmbed(event);
        assertEquals(0x3498DB, embed.getColorRaw());
        assertTrue(embed.getFields().stream().noneMatch(f -> f.getName().equals("Idle Seconds")));
        assertTrue(embed.getFields().stream().anyMatch(f -> f.getName().equals("Human Count") && f.getValue().equals("0")));
    }

    @Test void updateLatestPersistsAndRestoresOnlyItsOwnHeartbeat() throws Exception {
        var config = config(OperationalLoggingConfig.DeliveryMode.UPDATE_LATEST);
        try (var publisher = new DiscordOperationalLogPublisher(config, directory)) {
            publisher.connected(jda());
            publisher.publish(sample());
            tick(publisher);
            assertEquals(1, sends);
            assertEquals("999", Files.readString(directory.resolve("data/monitoring/jvm-heartbeat-123.id")));
            publisher.publish(sample());
            tick(publisher);
            assertEquals(1, edits);
        }
        try (var publisher = new DiscordOperationalLogPublisher(config, directory)) {
            publisher.connected(jda());
            publisher.publish(sample());
            tick(publisher);
            assertEquals(1, sends);
            assertEquals(2, edits);
        }
        assertFalse(mentionPolicies.isEmpty());
        assertTrue(mentionPolicies.stream().allMatch(policy -> policy instanceof List<?> list && list.isEmpty()));
    }

    @Test void neverEditsAnotherAuthorOrAnUnrelatedBotMessage() throws Exception {
        for (boolean otherAuthor : List.of(true, false)) {
            Path state = directory.resolve("data/monitoring/jvm-heartbeat-123.id");
            Files.createDirectories(state.getParent());
            Files.writeString(state, "999");
            saved = message(otherAuthor ? "other-bot" : "bot", otherAuthor ? DiscordOperationalLogPublisher.jvmEmbed(sample())
                    : new net.dv8tion.jda.api.EmbedBuilder().setTitle("unrelated").build());
            try (var publisher = new DiscordOperationalLogPublisher(config(OperationalLoggingConfig.DeliveryMode.UPDATE_LATEST), directory)) {
                publisher.connected(jda());
                publisher.publish(sample());
                tick(publisher);
                assertEquals(0, edits);
                publisher.publish(sample());
                tick(publisher);
            }
        }
        assertEquals(2, sends);
    }

    @Test void disabledOfflineAndMissingPermissionDoNotSendAndLaterSamplesStillWork() throws Exception {
        try (var publisher = new DiscordOperationalLogPublisher(OperationalLoggingConfig.disabled(), directory)) {
            publisher.connected(jda());
            publisher.publish(sample());
            publisher.publish(voice());
            tick(publisher);
            assertEquals(0, sends);
        }
        try (var publisher = new DiscordOperationalLogPublisher(config(OperationalLoggingConfig.DeliveryMode.NEW_MESSAGE), directory)) {
            publisher.connected(jda());
            connected = false;
            publisher.publish(sample());
            tick(publisher);
            connected = true;
            allowed = false;
            publisher.publish(sample());
            tick(publisher);
            assertEquals(0, sends);
            allowed = true;
            publisher.publish(sample());
            tick(publisher);
            publisher.publish(sample());
            tick(publisher);
            assertEquals(2, sends);
            assertEquals(0, edits);
        }
    }

    @Test void blockedRestRequestKeepsVoiceQueueBoundedAndCoalescesHeartbeats() throws Exception {
        try (var publisher = new DiscordOperationalLogPublisher(config(OperationalLoggingConfig.DeliveryMode.NEW_MESSAGE), directory)) {
            publisher.connected(jda());
            hold = true;
            publisher.publish(sample());
            tick(publisher);
            for (int i = 0; i < 1000; i++) { publisher.publish(voice()); publisher.publish(sample()); }
            tick(publisher);
            var field = DiscordOperationalLogPublisher.class.getDeclaredField("voiceEvents");
            field.setAccessible(true);
            assertEquals(64, ((ArrayBlockingQueue<?>) field.get(publisher)).size());
            assertEquals(1, sends);
        }
    }

    @Test void deletedMessageIsRecreatedAndMissingChannelDoesNotStopLaterDelivery() throws Exception {
        try (var publisher = new DiscordOperationalLogPublisher(config(OperationalLoggingConfig.DeliveryMode.UPDATE_LATEST), directory)) {
            publisher.connected(jda());
            publisher.publish(sample());
            tick(publisher);
            nextFailure = net.dv8tion.jda.api.exceptions.ErrorResponseException.create(
                    net.dv8tion.jda.api.requests.ErrorResponse.UNKNOWN_MESSAGE,
                    new net.dv8tion.jda.api.requests.Response(new java.io.IOException("synthetic 404"), java.util.Set.of()));
            publisher.publish(sample());
            tick(publisher);
            assertEquals(1, sends);
            publisher.publish(sample());
            tick(publisher);
            assertEquals(2, sends);
            channelPresent = false;
            publisher.publish(sample());
            tick(publisher);
            assertEquals(0, edits);
            channelPresent = true;
            publisher.publish(sample());
            tick(publisher);
            assertEquals(1, edits);
        }
    }

    @Test void failedVoiceSendDoesNotRetryOrBlockSubsequentEvents() throws Exception {
        try (var publisher = new DiscordOperationalLogPublisher(config(OperationalLoggingConfig.DeliveryMode.NEW_MESSAGE), directory)) {
            publisher.connected(jda());
            nextFailure = new java.io.IOException("synthetic failure");
            publisher.publish(voice());
            tick(publisher);
            assertEquals(1, sends);
            tick(publisher);
            assertEquals(1, sends);
            publisher.publish(voice());
            tick(publisher);
            assertEquals(2, sends);
        }
    }

    private static OperationalLoggingConfig config(OperationalLoggingConfig.DeliveryMode mode) {
        return new OperationalLoggingConfig("123", mode, "456");
    }

    private static JvmResourceSample sample() {
        return new JvmResourceSample(Instant.parse("2026-01-01T00:00:00Z"), .0076, -1,
                1048576, 2097152, -1, 1048576, 0, 0, false, 0, 0, 0, 2, 1, 3,
                Map.of(Thread.State.RUNNABLE, 2));
    }

    private static VoiceAutoLeaveEvent voice() {
        return new VoiceAutoLeaveEvent(Instant.now(), "DISCONNECTED", "EMPTY_CHANNEL", 1, "guild", 2, "voice", 0L, 0L, null);
    }

    private static void tick(DiscordOperationalLogPublisher publisher) throws Exception {
        var field = DiscordOperationalLogPublisher.class.getDeclaredField("worker");
        field.setAccessible(true);
        var worker = (ScheduledExecutorService) field.get(publisher);
        var drain = DiscordOperationalLogPublisher.class.getDeclaredMethod("drain");
        drain.setAccessible(true);
        worker.submit(() -> { try { drain.invoke(publisher); } catch (Exception failure) { throw new RuntimeException(failure); } }).get(3, TimeUnit.SECONDS);
        // REST callbacks are serialized back onto the same worker.
        for (int i = 0; i < 3; i++) worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
    }

    private JDA jda() {
        SelfMember self = proxy(SelfMember.class, (name, args) -> name.equals("hasPermission") ? allowed : null);
        Guild guild = proxy(Guild.class, (name, args) -> name.equals("getSelfMember") ? self : null);
        TextChannel channel = proxy(TextChannel.class, (name, args) -> switch (name) {
            case "getGuild" -> guild;
            case "sendMessageEmbeds" -> {
                sends++;
                saved = message("bot", (MessageEmbed) args[0]);
                yield action(MessageCreateAction.class);
            }
            case "retrieveMessageById" -> action(RestAction.class);
            case "editMessageEmbedsById" -> { edits++; yield action(MessageEditAction.class); }
            default -> null;
        });
        SelfUser user = proxy(SelfUser.class, (name, args) -> name.equals("getId") ? "bot" : null);
        return proxy(JDA.class, (name, args) -> switch (name) {
            case "getStatus" -> connected ? JDA.Status.CONNECTED : JDA.Status.RECONNECT_QUEUED;
            case "getTextChannelById" -> channelPresent ? channel : null;
            case "getSelfUser" -> user;
            default -> null;
        });
    }

    private static Message message(String author, MessageEmbed embed) {
        User user = proxy(User.class, (name, args) -> name.equals("getId") ? author : null);
        return proxy(Message.class, (name, args) -> switch (name) {
            case "getAuthor" -> user;
            case "getEmbeds" -> List.of(embed);
            case "getId" -> "999";
            default -> null;
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T action(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("setAllowedMentions")) mentionPolicies.add(args[0]);
            if (method.getName().equals("queue")) {
                if (nextFailure != null) {
                    Throwable failure = nextFailure;
                    nextFailure = null;
                    ((Consumer<Throwable>) args[1]).accept(failure);
                } else if (!hold) ((Consumer<Message>) args[0]).accept(saved);
                return null;
            }
            return self;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> {
                    try {
                        Object result = handler.apply(method.getName(), args);
                        if (result == null && method.getReturnType().isPrimitive()) {
                            throw new AssertionError("Missing stub: " + type.getSimpleName() + "." + method.getName());
                        }
                        return result;
                    } catch (RuntimeException failure) {
                        throw new AssertionError("Stub failed: " + type.getSimpleName() + "." + method.getName(), failure);
                    }
                });
    }
}
