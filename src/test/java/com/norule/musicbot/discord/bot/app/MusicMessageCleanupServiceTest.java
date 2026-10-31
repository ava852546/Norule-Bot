package com.norule.musicbot.discord.bot.app;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MusicMessageCleanupServiceTest {
    @Test
    void legacyOrFallbackDoesNotEnqueueVisibleMessages() {
        Fixture fixture = new Fixture();
        fixture.cleanupAllowed = false;
        fixture.cleanup.enqueue(fixture.message(1), "music-channel");
        fixture.tick(10);
        fixture.cleanupAllowed = true;
        fixture.tick(20);
        assertTrue(fixture.submitted.isEmpty());
    }

    @Test
    void switchingToLegacyOrFallbackDiscardsPreviouslyQueuedCleanup() {
        Fixture fixture = new Fixture();
        fixture.cleanup.enqueue(fixture.message(1), "music-channel");
        fixture.cleanupAllowed = false;
        fixture.tick(10);
        fixture.cleanupAllowed = true;
        fixture.tick(20);
        assertTrue(fixture.submitted.isEmpty());
    }
    @Test
    void retainsDeduplicationThroughRestCompletionAndRecentSuccess() {
        Fixture fixture = new Fixture();
        Message message = fixture.message(1);
        fixture.cleanup.enqueue(message, "event");
        fixture.cleanup.enqueue(message, "reply-callback");
        fixture.tick(9);
        assertTrue(fixture.submitted.isEmpty());
        fixture.tick(10);
        assertEquals(List.of(1L), fixture.submitted);
        fixture.cleanup.enqueue(message, "while-in-flight");
        fixture.tick(20);
        assertEquals(1, fixture.submitted.size());
        fixture.succeed();
        fixture.cleanup.enqueue(message, "late-event");
        fixture.tick(30);
        assertEquals(1, fixture.submitted.size());
    }

    @Test
    void largeCleanupSubmitsOneRequestPerTickAndWaitsForCompletion() {
        Fixture fixture = new Fixture();
        for (int id = 1; id <= 200; id++) {
            fixture.cleanup.enqueue(fixture.message(id), "music-channel");
        }
        fixture.tick(10);
        assertEquals(List.of(1L), fixture.submitted);
        fixture.tick(11);
        assertEquals(1, fixture.submitted.size());
        fixture.succeed();
        assertEquals(1, fixture.submitted.size());
        fixture.tick(12);
        assertEquals(List.of(1L, 2L), fixture.submitted);
        assertEquals(1, fixture.scheduledWorkers);
    }

    @Test
    void deletionEventsCancelQueuedMessagesAndDoNotFreeAnInflightSlot() {
        Fixture fixture = new Fixture();
        fixture.cleanup.enqueue(fixture.message(1), "music-channel");
        fixture.cleanup.enqueue(fixture.message(2), "music-channel");
        fixture.cleanup.enqueue(fixture.message(3), "music-channel");
        fixture.cleanup.messageDeleted(1);
        fixture.tick(10);
        assertEquals(List.of(2L), fixture.submitted);
        fixture.cleanup.messageDeleted(2);
        fixture.tick(11);
        assertEquals(List.of(2L), fixture.submitted);
        fixture.succeed();
        fixture.tick(12);
        assertEquals(List.of(2L, 3L), fixture.submitted);
    }

    @Test
    void deletionEventBeforeEnqueuePreventsStaleCleanup() {
        Fixture fixture = new Fixture();
        fixture.cleanup.messageDeleted(1);
        fixture.cleanup.enqueue(fixture.message(1), "late-event");
        fixture.tick(10);
        assertTrue(fixture.submitted.isEmpty());
    }

    @Test
    void failuresDoNotRetryOrBlockTheNextMessage() {
        Fixture fixture = new Fixture();
        fixture.cleanup.enqueue(fixture.message(1), "music-channel");
        fixture.cleanup.enqueue(fixture.message(2), "music-channel");
        fixture.tick(10);
        fixture.failure.accept(new IllegalStateException("REST failure"));
        fixture.tick(11);
        assertEquals(List.of(1L, 2L), fixture.submitted);
        fixture.succeed();
        fixture.tick(100);
        assertEquals(2, fixture.submitted.size());
    }

    @Test
    void synchronousSubmissionFailureDoesNotStopWorker() {
        Fixture fixture = new Fixture();
        fixture.throwOnDelete = true;
        fixture.cleanup.enqueue(fixture.message(1), "music-channel");
        fixture.cleanup.enqueue(fixture.message(2), "music-channel");
        fixture.tick(10);
        fixture.throwOnDelete = false;
        fixture.tick(11);
        assertEquals(List.of(2L), fixture.submitted);
    }

    @Test
    void protectsActivePanelAndChecksPermissionsAtDispatchTime() {
        Fixture fixture = new Fixture();
        fixture.cleanup.enqueue(fixture.message(1), "music-channel");
        fixture.cleanup.enqueue(fixture.message(2), "music-channel");
        fixture.activePanel = true;
        fixture.tick(10);
        fixture.activePanel = false;
        fixture.manageMessages = false;
        fixture.tick(11);
        assertTrue(fixture.submitted.isEmpty());
    }

    @Test
    void ownMessagesCanBeDeletedWithoutManageMessages() {
        Fixture fixture = new Fixture();
        fixture.manageMessages = false;
        fixture.ownMessage = true;
        fixture.cleanup.enqueue(fixture.message(1), "reply");
        fixture.tick(10);
        assertEquals(List.of(1L), fixture.submitted);
    }

    @Test
    void closeCancelsWorkerAndDiscardsPendingMessages() {
        Fixture fixture = new Fixture();
        fixture.cleanup.enqueue(fixture.message(1), "music-channel");
        fixture.cleanup.close();
        fixture.cleanup.enqueue(fixture.message(2), "late-event");
        fixture.cleanup.messageDeleted(3);
        fixture.tick(10);
        assertTrue(fixture.cancelled);
        assertTrue(fixture.submitted.isEmpty());
    }

    private static class Fixture {
        long now;
        boolean activePanel;
        boolean manageMessages = true;
        boolean ownMessage;
        boolean throwOnDelete;
        boolean cancelled;
        boolean cleanupAllowed = true;
        int scheduledWorkers;
        Runnable worker;
        Consumer<Object> success;
        Consumer<Throwable> failure;
        final List<Long> submitted = new ArrayList<>();
        final MusicMessageCleanupService cleanup;

        Fixture() {
            ScheduledFuture<?> future = proxy(ScheduledFuture.class, (object, method, arguments) -> {
                if (method.getName().equals("cancel")) {
                    cancelled = true;
                    return true;
                }
                throw new UnsupportedOperationException(method.getName());
            });
            ScheduledExecutorService scheduler = proxy(ScheduledExecutorService.class, (object, method, arguments) -> {
                assertEquals("scheduleWithFixedDelay", method.getName());
                assertEquals(250L, arguments[1]);
                assertEquals(250L, arguments[2]);
                assertEquals(TimeUnit.MILLISECONDS, arguments[3]);
                scheduledWorkers++;
                worker = (Runnable) arguments[0];
                return future;
            });
            cleanup = new MusicMessageCleanupService(scheduler, message -> activePanel,
                    message -> cleanupAllowed, () -> now);
        }

        void tick(long seconds) {
            now = TimeUnit.SECONDS.toNanos(seconds);
            worker.run();
        }

        void succeed() {
            success.accept(null);
        }

        @SuppressWarnings("unchecked")
        Message message(long messageId) {
            SelfMember self = proxy(SelfMember.class, (object, method, arguments) -> switch (method.getName()) {
                case "getIdLong" -> 100L;
                case "hasPermission" -> manageMessages;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            Guild guild = proxy(Guild.class, (object, method, arguments) -> switch (method.getName()) {
                case "getIdLong" -> 200L;
                case "getSelfMember" -> self;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            Object channel = Proxy.newProxyInstance(MessageChannelUnion.class.getClassLoader(),
                    new Class<?>[]{MessageChannelUnion.class, GuildMessageChannelUnion.class},
                    (object, method, arguments) -> 300L);
            User author = proxy(User.class, (object, method, arguments) -> ownMessage ? 100L : 101L);
            AuditableRestAction<?> delete = proxy(AuditableRestAction.class, (object, method, arguments) -> {
                assertEquals("queue", method.getName());
                submitted.add(messageId);
                success = (Consumer<Object>) arguments[0];
                failure = (Consumer<Throwable>) arguments[1];
                return null;
            });
            return proxy(Message.class, (object, method, arguments) -> switch (method.getName()) {
                case "getIdLong" -> messageId;
                case "getGuildIdLong" -> 200L;
                case "getChannelIdLong" -> 300L;
                case "getGuild" -> guild;
                case "getChannel", "getGuildChannel" -> channel;
                case "getAuthor" -> author;
                case "delete" -> {
                    if (throwOnDelete) {
                        throw new IllegalStateException("Submission failed");
                    }
                    yield delete;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
