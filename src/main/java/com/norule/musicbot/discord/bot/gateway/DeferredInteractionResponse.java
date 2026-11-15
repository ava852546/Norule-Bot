package com.norule.musicbot.discord.bot.gateway;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.requests.RestAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Gateway response lifecycle: one ACK, one terminal response, no application REST retries. */
public final class DeferredInteractionResponse {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeferredInteractionResponse.class);
    private static final Map<Long, Long> CLAIMED = new HashMap<>();
    private final InteractionHook hook;
    private final String command;
    private final String guildId;
    private final Clock clock;
    private final long startedAt;
    private final Function<InteractionHook, RestAction<Message>> failureReply;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean tokenInvalidated = new AtomicBoolean();
    private volatile ScheduledFuture<?> timeout;

    private DeferredInteractionResponse(InteractionHook hook, String command, String guildId, Clock clock,
                                        Function<InteractionHook, RestAction<Message>> failureReply) {
        this.hook = hook;
        this.command = command;
        this.guildId = guildId;
        this.clock = clock;
        this.startedAt = clock.millis();
        this.failureReply = failureReply;
    }

    public static void reply(IReplyCallback event, boolean ephemeral, String command,
                             ScheduledExecutorService scheduler, String failureText,
                             Consumer<DeferredInteractionResponse> work) {
        acknowledge(event, () -> event.deferReply(ephemeral), ephemeral ? "EPHEMERAL" : "PUBLIC",
                command, scheduler, Duration.ofSeconds(60),
                hook -> hook.editOriginal(failureText).setEmbeds(java.util.List.of()).setComponents(java.util.List.of()), work);
    }

    public static void edit(IMessageEditCallback event, String command, ScheduledExecutorService scheduler,
                            String failureText, Consumer<DeferredInteractionResponse> work) {
        acknowledge(event, event::deferEdit, "EDIT_ORIGINAL", command, scheduler, Duration.ofSeconds(60),
                hook -> hook.editOriginal(failureText).setEmbeds(java.util.List.of()).setComponents(java.util.List.of()), work);
    }

    public static void acknowledge(Interaction event, Supplier<? extends RestAction<InteractionHook>> ack,
                                    String mode, String command, ScheduledExecutorService scheduler, Duration budget,
                                    Function<InteractionHook, RestAction<Message>> failureReply,
                                    Consumer<DeferredInteractionResponse> work) {
        acknowledge(event, ack, mode, command, scheduler, budget, failureReply, work, Clock.systemUTC());
    }

    static void acknowledge(Interaction event, Supplier<? extends RestAction<InteractionHook>> ack,
                            String mode, String command, ScheduledExecutorService scheduler, Duration budget,
                            Function<InteractionHook, RestAction<Message>> failureReply,
                            Consumer<DeferredInteractionResponse> work, Clock clock) {
        acknowledgeOnly(event, ack, mode, command, hook -> {
            String guildId = event.getGuild() == null ? "DM" : Long.toString(event.getGuild().getIdLong());
            var response = new DeferredInteractionResponse(hook, command, guildId, clock, failureReply);
            response.run(() -> {
                response.timeout = scheduler.schedule(() -> response.fail(new TimeoutException()),
                        budget.toMillis(), TimeUnit.MILLISECONDS);
                work.accept(response);
            });
        }, clock);
    }

    /** Panel operations refresh through the panel coordinator, never through the interaction hook. */
    public static void acknowledgeEdit(IMessageEditCallback event, String command, String failureText,
                                        Consumer<InteractionHook> work) {
        acknowledgeOnly(event, event::deferEdit, "EDIT_ORIGINAL", command, hook -> {
            String guildId = event.getGuild() == null ? "DM" : Long.toString(event.getGuild().getIdLong());
            var response = new DeferredInteractionResponse(hook, command, guildId, Clock.systemUTC(),
                    feedback -> feedback.sendMessage(failureText).setEphemeral(true));
            response.run(() -> work.accept(hook));
        }, Clock.systemUTC());
    }

    private static void acknowledgeOnly(Interaction event, Supplier<? extends RestAction<InteractionHook>> ack,
                                         String mode, String command, Consumer<InteractionHook> work, Clock clock) {
        long createdAt = event.getTimeCreated().toInstant().toEpochMilli();
        String guildId = event.getGuild() == null ? "DM" : Long.toString(event.getGuild().getIdLong());
        synchronized (CLAIMED) {
            long now = clock.millis();
            CLAIMED.values().removeIf(expiry -> expiry <= now);
            if (event.isAcknowledged() || CLAIMED.containsKey(event.getIdLong())) {
                logFailure("ACK_FAILED", command, guildId, "ACK_DUPLICATE");
                return;
            }
            if (now - createdAt >= 2800 || CLAIMED.size() >= 4096) {
                logFailure("ACK_FAILED", command, guildId, now - createdAt >= 2800 ? "ACK_TIMEOUT" : "ACK_CAPACITY");
                return;
            }
            CLAIMED.put(event.getIdLong(), createdAt + 3000);
        }
        try {
            ack.get().deadline(createdAt + 2800).queue(hook -> {
                LOGGER.debug("[Interaction] event=ACK_COMPLETED command={} guildId={} ackLatencyMs={} responseMode={}",
                        command, guildId, clock.millis() - createdAt, mode);
                try {
                    work.accept(hook);
                } catch (RuntimeException failure) {
                    logFailure("BACKEND_FAILED", command, guildId, "PROCESSING_FAILED");
                }
            }, failure -> logFailure("ACK_FAILED", command, guildId, failureReason(failure, true)));
        } catch (RuntimeException failure) {
            logFailure("ACK_FAILED", command, guildId, failureReason(failure, true));
        }
    }

    /** For callbacks dispatched to a worker, where exceptions cannot reach the ACK callback. */
    public void run(Runnable work) {
        if (finished.get()) return;
        try {
            work.run();
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    public void text(String text) {
        complete(hook -> hook.editOriginal(text).setEmbeds(java.util.List.of()).setComponents(java.util.List.of()));
    }

    public void complete(Function<InteractionHook, ? extends RestAction<Message>> edit) {
        complete(edit, ignored -> {});
    }

    public void complete(Function<InteractionHook, ? extends RestAction<Message>> edit, Consumer<Message> success) {
        finish(edit, success, "SUCCESS");
    }

    public void fail(Throwable failure) {
        if (finished.get()) return;
        logFailure("BACKEND_FAILED", command, guildId,
                failure instanceof TimeoutException ? "PROCESSING_TIMEOUT" : "PROCESSING_FAILED");
        finish(failureReply, ignored -> {}, "FAILURE");
    }

    private void finish(Function<InteractionHook, ? extends RestAction<Message>> edit,
                        Consumer<Message> success, String result) {
        if (!finished.compareAndSet(false, true)) return;
        if (timeout != null) timeout.cancel(false);
        submit(edit, success, result);
    }

    /** Existing independent panel feedback and search-menu expiry use the same token guard. */
    public void auxiliary(Function<InteractionHook, ? extends RestAction<Message>> edit) {
        submit(edit, ignored -> {}, "AUXILIARY");
    }

    private void submit(Function<InteractionHook, ? extends RestAction<Message>> edit,
                        Consumer<Message> success, String result) {
        if (tokenInvalidated.get() || hook.isExpired() || clock.millis() >= hook.getExpirationTimestamp()) {
            logFailure("RESPONSE_EDIT_FAILED", command, guildId, "TOKEN_EXPIRED");
            return;
        }
        try {
            edit.apply(hook).deadline(Math.min(hook.getExpirationTimestamp(), clock.millis() + 15_000))
                    .queue(message -> {
                        LOGGER.debug("[Interaction] event=RESPONSE_EDIT_COMPLETED command={} guildId={} durationMs={} result={}",
                                command, guildId, clock.millis() - startedAt, result);
                        runSuccess(success, message);
                    }, this::editFailed);
        } catch (RuntimeException failure) {
            editFailed(failure);
        }
    }

    private void editFailed(Throwable failure) {
        String reason = failureReason(failure, false);
        if ("TOKEN_EXPIRED".equals(reason)) tokenInvalidated.set(true);
        logFailure("RESPONSE_EDIT_FAILED", command, guildId, reason);
    }

    private void runSuccess(Consumer<Message> success, Message message) {
        try {
            success.accept(message);
        } catch (RuntimeException failure) {
            logFailure("RESPONSE_CALLBACK_FAILED", command, guildId, "PROCESSING_FAILED");
        }
    }

    public static String failureReason(Throwable failure, boolean ack) {
        while ((failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof ErrorResponseException discord) {
            int code = discord.getErrorCode();
            if (code < 0) code = discord.getErrorResponse().getCode();
            return switch (code) {
                case 10062 -> ack ? "ACK_TIMEOUT" : "TOKEN_EXPIRED";
                case 40060 -> "ACK_DUPLICATE";
                case 10015, 50027 -> "TOKEN_EXPIRED";
                default -> "DISCORD_" + code;
            };
        }
        return failure instanceof TimeoutException ? (ack ? "ACK_TIMEOUT" : "EDIT_TIMEOUT")
                : failure.getClass().getSimpleName();
    }

    private static void logFailure(String event, String command, String guildId, String reason) {
        LOGGER.warn("[Interaction] event={} command={} guildId={} reason={}", event, command, guildId, reason);
    }
}
