package com.norule.musicbot.discord.bot.gateway;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Response;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DeferredInteractionResponseTest {
    private static final AtomicLong IDS = new AtomicLong(10_000);

    @Test
    void slowFutureStartsOnlyAfterAckAndEditsOriginalExactlyOnce() {
        for (boolean ephemeral : List.of(true, false)) {
            try (var discord = new Discord()) {
                var future = new CompletableFuture<String>();
                var response = new AtomicReference<DeferredInteractionResponse>();
                discord.start(ephemeral, value -> {
                    response.set(value);
                    future.thenAccept(value::text);
                });
                assertNull(response.get());
                assertEquals(1, discord.acks);
                assertEquals(ephemeral, discord.ephemeral);
                assertEquals(discord.createdAt + 2800, discord.ackDeadline);
                discord.ackSuccess.accept(discord.hook);
                discord.now += 5000;
                assertEquals(0, discord.edits);
                future.complete("done");
                response.get().text("duplicate callback");
                discord.timer.expire(); // Simulates a timeout racing after cancellation.
                assertEquals(1, discord.edits);
                assertEquals("done", discord.content);
                assertEquals(1, discord.acks);
                assertTrue(discord.timer.future.isCancelled());
            }
        }
    }

    @Test
    void failedFutureAndSynchronousBackendFailureResolveTheOriginal() {
        try (var discord = new Discord()) {
            var future = new CompletableFuture<String>();
            discord.start(true, response -> future.whenComplete((value, error) -> {
                if (error != null) response.fail(error);
                else response.text(value);
            }));
            discord.ackSuccess.accept(discord.hook);
            future.completeExceptionally(new IllegalStateException("must not log secret"));
            assertEquals("localized failure", discord.content);
            assertEquals(1, discord.edits);
        }
        try (var discord = new Discord()) {
            discord.start(true, response -> { throw new IllegalStateException(); });
            discord.ackSuccess.accept(discord.hook);
            assertEquals("localized failure", discord.content);
            assertEquals(1, discord.edits);
        }
    }

    @Test
    void timeoutProducesFinalErrorAndIgnoresLateSuccess() {
        try (var discord = new Discord()) {
            var future = new CompletableFuture<String>();
            discord.start(true, response -> future.thenAccept(response::text));
            discord.ackSuccess.accept(discord.hook);
            discord.now += 60_000;
            discord.timer.expire();
            assertEquals("localized failure", discord.content);
            assertEquals(1, discord.edits);
            future.complete("late success");
            assertEquals(1, discord.edits);
            assertEquals("localized failure", discord.content);
        }
    }

    @Test
    void ackFailuresNeverStartBackendOrUseHookAndDoNotRetry() {
        for (var error : List.of(ErrorResponse.UNKNOWN_INTERACTION, ErrorResponse.INTERACTION_ALREADY_ACKNOWLEDGED)) {
            try (var discord = new Discord()) {
                discord.start(true, response -> fail("ACK failure must not execute backend"));
                discord.ackFailure.accept(discordFailure(error));
                assertEquals(1, discord.acks);
                assertEquals(0, discord.edits);
                assertNull(discord.timer.task);
            }
        }
    }

    @Test
    void duplicateDeliveryAndAlreadyAcknowledgedEventsNeverAckTwice() {
        try (var discord = new Discord()) {
            discord.start(true, response -> {});
            discord.start(true, response -> fail("duplicate"));
            assertEquals(1, discord.acks);
        }
        try (var discord = new Discord()) {
            discord.acknowledged = true;
            discord.start(true, response -> fail("already acknowledged"));
            assertEquals(0, discord.acks);
        }
    }

    @Test
    void oldEventNeverStartsAnExpiredAck() {
        try (var discord = new Discord()) {
            discord.now += 3000;
            discord.start(true, response -> fail("expired event"));
            assertEquals(0, discord.acks);
        }
    }

    @Test
    void expiredHookSkipsBothFinalAndAuxiliaryRestRequests() {
        try (var discord = new Discord()) {
            var response = new AtomicReference<DeferredInteractionResponse>();
            discord.start(true, response::set);
            discord.ackSuccess.accept(discord.hook);
            discord.now += 900_001;
            response.get().text("late");
            response.get().auxiliary(hook -> { fail("expired auxiliary"); return null; });
            assertEquals(0, discord.edits);
            assertTrue(discord.timer.future.isCancelled());
        }
    }

    @Test
    void editFailureDoesNotAckOrSendAnotherMessageAndHasBoundedDeadline() {
        try (var discord = new Discord()) {
            var response = new AtomicReference<DeferredInteractionResponse>();
            discord.editFailure = discordFailure(ErrorResponse.UNKNOWN_WEBHOOK);
            discord.start(true, value -> { response.set(value); value.text("done"); });
            discord.ackSuccess.accept(discord.hook);
            response.get().auxiliary(hook -> { fail("invalid token must not be reused"); return null; });
            assertEquals(1, discord.acks);
            assertEquals(1, discord.edits);
            assertEquals(discord.now + 15_000, discord.editDeadline);
        }
    }

    @Test
    void concurrentCompletionAndTimeoutHaveOnlyOneTerminalEdit() throws Exception {
        try (var discord = new Discord(); var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var response = new AtomicReference<DeferredInteractionResponse>();
            discord.start(true, response::set);
            discord.ackSuccess.accept(discord.hook);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 40; i++) {
                int index = i;
                tasks.add(workers.submit(() -> {
                    if (index % 2 == 0) discord.timer.expire();
                    else response.get().text("success");
                }));
            }
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            assertEquals(1, discord.edits);
            assertEquals(1, discord.acks);
        }
    }

    @Test
    void panelAckWaitsForConfirmationAndNeverEditsPanelThroughHook() {
        var success = new AtomicReference<Consumer<InteractionHook>>();
        var failure = new AtomicReference<Consumer<Throwable>>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var id = IDS.incrementAndGet();
        var event = proxy(net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback.class, (o, method, args) -> switch (method.getName()) {
            case "getTimeCreated" -> OffsetDateTime.now();
            case "getIdLong" -> id;
            case "getGuild" -> null;
            case "isAcknowledged" -> false;
            case "deferEdit" -> proxy(net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction.class,
                    (action, m, a) -> {
                        if (m.getName().equals("deadline")) return action;
                        assertEquals("queue", m.getName());
                        success.set((Consumer<InteractionHook>) a[0]);
                        failure.set((Consumer<Throwable>) a[1]);
                        return null;
                    });
            default -> throw new AssertionError(method.getName());
        });
        DeferredInteractionResponse.acknowledgeEdit(event, "panel", "error", hook -> calls.incrementAndGet());
        assertEquals(0, calls.get());
        var hook = proxy(InteractionHook.class, (o, m, a) -> { throw new AssertionError("Panel hook overwritten: " + m.getName()); });
        success.get().accept(hook);
        assertEquals(1, calls.get());
        DeferredInteractionResponse.acknowledgeEdit(event, "panel", "error", ignored -> fail("duplicate panel operation"));
        assertEquals(1, calls.get());
    }

    @Test
    void errorClassificationSeparatesAckEditAndTokenFailures() {
        assertEquals("ACK_TIMEOUT", DeferredInteractionResponse.failureReason(discordFailure(ErrorResponse.UNKNOWN_INTERACTION), true));
        assertEquals("ACK_DUPLICATE", DeferredInteractionResponse.failureReason(discordFailure(ErrorResponse.INTERACTION_ALREADY_ACKNOWLEDGED), true));
        assertEquals("TOKEN_EXPIRED", DeferredInteractionResponse.failureReason(discordFailure(ErrorResponse.UNKNOWN_WEBHOOK), false));
        assertEquals("TOKEN_EXPIRED", DeferredInteractionResponse.failureReason(discordFailure(ErrorResponse.INVALID_WEBHOOK_TOKEN), false));
        assertEquals("DISCORD_50013", DeferredInteractionResponse.failureReason(discordFailure(ErrorResponse.MISSING_PERMISSIONS), false));
    }

    @Test
    void schedulerShutdownStillResolvesAnAcknowledgedReply() {
        try (var discord = new Discord()) {
            discord.timer.shutdownNow();
            discord.start(true, response -> fail("scheduler unavailable"));
            discord.ackSuccess.accept(discord.hook);
            assertEquals("localized failure", discord.content);
            assertEquals(1, discord.edits);
        }
    }

    @Test
    void diagnosticsIncludeLatencyAndResultsWithoutTokensOrStackTraces() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DeferredInteractionResponse.class);
        var previous = logger.getLevel();
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try (var discord = new Discord()) {
            discord.start(true, response -> response.text("result"));
            discord.now += 25;
            discord.ackSuccess.accept(discord.hook);
            try (var failed = new Discord()) {
                failed.start(true, response -> fail("ACK failed"));
                failed.ackFailure.accept(new IllegalStateException("private-interaction-token"));
            }
            var messages = logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertTrue(messages.stream().anyMatch(value -> value.contains("event=ACK_COMPLETED")
                    && value.contains("ackLatencyMs=25") && value.contains("responseMode=EPHEMERAL")));
            assertTrue(messages.stream().anyMatch(value -> value.contains("event=RESPONSE_EDIT_COMPLETED") && value.contains("result=SUCCESS")));
            assertFalse(messages.stream().anyMatch(value -> value.contains("private-interaction-token")));
            assertTrue(logs.list.stream().allMatch(value -> value.getThrowableProxy() == null));
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(previous);
            logs.stop();
        }
    }

    private static ErrorResponseException discordFailure(ErrorResponse error) {
        return ErrorResponseException.create(error, new Response(new IOException("simulated"), Set.of()));
    }

    private static final class ManualDeadline extends ScheduledThreadPoolExecutor {
        Runnable task;
        ScheduledFuture<?> future;
        ManualDeadline() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            this.task = task;
            future = super.schedule(() -> {}, 1, TimeUnit.DAYS);
            return future;
        }
        void expire() { task.run(); }
    }

    @SuppressWarnings("unchecked")
    private static final class Discord implements AutoCloseable {
        final long id = IDS.incrementAndGet();
        final long createdAt = 1_800_000_000_000L;
        long now = createdAt;
        final ManualDeadline timer = new ManualDeadline();
        boolean acknowledged;
        boolean ephemeral;
        int acks;
        int edits;
        long ackDeadline;
        long editDeadline;
        String content;
        Throwable editFailure;
        Consumer<InteractionHook> ackSuccess;
        Consumer<Throwable> ackFailure;
        final Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        };
        final InteractionHook hook = proxy(InteractionHook.class, (o, method, args) -> switch (method.getName()) {
            case "isExpired" -> now >= createdAt + 900_000;
            case "getExpirationTimestamp" -> createdAt + 900_000;
            case "editOriginal" -> {
                content = (String) args[0];
                yield proxy(WebhookMessageEditAction.class, (action, m, a) -> {
                    switch (m.getName()) {
                        case "deadline" -> editDeadline = (long) a[0];
                        case "setEmbeds", "setComponents" -> { }
                        case "queue" -> {
                            edits++;
                            if (editFailure != null) ((Consumer<Throwable>) a[1]).accept(editFailure);
                            else ((Consumer<Message>) a[0]).accept(null);
                            return null;
                        }
                        default -> fail("Unexpected edit: " + m.getName());
                    }
                    return action;
                });
            }
            default -> throw new AssertionError("Unexpected hook operation (follow-up or duplicate?): " + method.getName());
        });
        final IReplyCallback event = proxy(IReplyCallback.class, (o, method, args) -> switch (method.getName()) {
            case "getTimeCreated" -> OffsetDateTime.ofInstant(Instant.ofEpochMilli(createdAt), ZoneOffset.UTC);
            case "getIdLong" -> id;
            case "getGuild" -> null;
            case "isAcknowledged" -> acknowledged;
            case "deferReply" -> {
                acks++;
                ephemeral = (boolean) args[0];
                yield proxy(ReplyCallbackAction.class, (action, m, a) -> {
                    if (m.getName().equals("deadline")) { ackDeadline = (long) a[0]; return action; }
                    assertEquals("queue", m.getName());
                    ackSuccess = (Consumer<InteractionHook>) a[0];
                    ackFailure = (Consumer<Throwable>) a[1];
                    return null;
                });
            }
            default -> throw new AssertionError("Unexpected ACK: " + method.getName());
        });

        void start(boolean ephemeral, Consumer<DeferredInteractionResponse> work) {
            DeferredInteractionResponse.acknowledge(event, () -> event.deferReply(ephemeral),
                    ephemeral ? "EPHEMERAL" : "PUBLIC", "test", timer, Duration.ofSeconds(60),
                    hook -> hook.editOriginal("localized failure"), work, clock);
        }
        @Override public void close() { timer.shutdownNow(); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
