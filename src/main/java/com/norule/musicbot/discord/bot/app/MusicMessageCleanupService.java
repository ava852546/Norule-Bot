package com.norule.musicbot.discord.bot.app;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Coordinates transient music cleanup without filling JDA's REST queue in a burst. */
final class MusicMessageCleanupService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicMessageCleanupService.class);
    private static final long DISPLAY_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long RECENT_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int MAX_TRACKED_MESSAGES = 10_000;

    private record Pending(Message message, String source, long dueAt) { }

    private final Map<Long, Pending> pending = new LinkedHashMap<>();
    private final Map<Long, Long> recent = new LinkedHashMap<>();
    private final Predicate<Message> activePanel;
    private final Predicate<Message> cleanupAllowed;
    private final LongSupplier nanoTime;
    private final ScheduledFuture<?> worker;
    private boolean inFlight;
    private boolean closed;

    MusicMessageCleanupService(ScheduledExecutorService scheduler, Predicate<Message> activePanel) {
        this(scheduler, activePanel, message -> true, System::nanoTime);
    }

    MusicMessageCleanupService(ScheduledExecutorService scheduler, Predicate<Message> activePanel,
                               Predicate<Message> cleanupAllowed) {
        this(scheduler, activePanel, cleanupAllowed, System::nanoTime);
    }

    MusicMessageCleanupService(ScheduledExecutorService scheduler, Predicate<Message> activePanel,
                               LongSupplier nanoTime) {
        this(scheduler, activePanel, message -> true, nanoTime);
    }

    MusicMessageCleanupService(ScheduledExecutorService scheduler, Predicate<Message> activePanel,
                               Predicate<Message> cleanupAllowed, LongSupplier nanoTime) {
        this.activePanel = activePanel;
        this.cleanupAllowed = cleanupAllowed;
        this.nanoTime = nanoTime;
        this.worker = scheduler.scheduleWithFixedDelay(this::drain, 250, 250, TimeUnit.MILLISECONDS);
    }

    synchronized void enqueue(Message message, String source) {
        if (closed || !cleanupAllowed.test(message)) {
            return;
        }
        pruneRecent();
        long messageId = message.getIdLong();
        if (pending.containsKey(messageId) || recent.containsKey(messageId)) {
            log(message, source, "DUPLICATE_SKIPPED");
            return;
        }
        if (pending.size() >= MAX_TRACKED_MESSAGES) {
            LOGGER.warn("[NoRule] Music cleanup queue full: messageId={} source={} queueDepth={}",
                    messageId, source, pending.size());
            return;
        }
        pending.put(messageId, new Pending(message, source, nanoTime.getAsLong() + DISPLAY_NANOS));
        log(message, source, "QUEUED");
    }

    synchronized void messageDeleted(long messageId) {
        if (closed) {
            return;
        }
        Pending removed = pending.remove(messageId);
        remember(messageId);
        if (removed != null) {
            log(removed.message(), removed.source(), "DELETED_EVENT");
        }
        // An in-flight REST action still owns its slot until its callback completes.
    }

    synchronized void drain() {
        if (closed) {
            return;
        }
        pruneRecent();
        if (inFlight || pending.isEmpty()) {
            return;
        }
        Pending next = pending.values().iterator().next();
        if (nanoTime.getAsLong() - next.dueAt() < 0) {
            return;
        }
        Message message = next.message();
        try {
            if (!cleanupAllowed.test(message)) {
                finish(next, "MODE_CLEANUP_DISABLED");
                return;
            }
            if (activePanel.test(message)) {
                finish(next, "ACTIVE_PANEL_SKIPPED");
                return;
            }
            var self = message.getGuild().getSelfMember();
            if (message.getAuthor().getIdLong() != self.getIdLong()
                    && !self.hasPermission(message.getGuildChannel(), Permission.MESSAGE_MANAGE)) {
                finish(next, "MISSING_MANAGE_MESSAGES");
                return;
            }
            inFlight = true;
            log(message, next.source(), "SUBMITTED");
            // JDA retains its own rate-limit/retry handling. Only one action is outstanding here.
            message.delete().queue(ignored -> finish(next, "SUCCESS"), error -> failed(next, error));
        } catch (RuntimeException error) {
            failed(next, error);
        }
    }

    private synchronized void failed(Pending request, Throwable error) {
        if (error instanceof ErrorResponseException response
                && response.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
            finish(request, "ALREADY_DELETED");
            return;
        }
        LOGGER.warn("[NoRule] Music cleanup failed: guildId={} channelId={} messageId={} source={} queueDepth={}",
                request.message().getGuildIdLong(), request.message().getChannelIdLong(),
                request.message().getIdLong(), request.source(), pending.size(), error);
        finish(request, "FAILED");
    }

    private synchronized void finish(Pending request, String result) {
        pending.remove(request.message().getIdLong(), request);
        if (!closed) {
            remember(request.message().getIdLong());
        }
        inFlight = false;
        log(request.message(), request.source(), result);
    }

    private void remember(long messageId) {
        recent.remove(messageId);
        recent.put(messageId, nanoTime.getAsLong());
        if (recent.size() > MAX_TRACKED_MESSAGES) {
            recent.remove(recent.keySet().iterator().next());
        }
    }

    private void pruneRecent() {
        long now = nanoTime.getAsLong();
        Iterator<Long> times = recent.values().iterator();
        while (times.hasNext()) {
            if (now - times.next() < RECENT_NANOS) {
                break;
            }
            times.remove();
        }
    }

    private void log(Message message, String source, String result) {
        LOGGER.debug("[NoRule] Music cleanup: guildId={} channelId={} messageId={} source={} queueDepth={} result={}",
                message.getGuildIdLong(), message.getChannelIdLong(), message.getIdLong(),
                source, pending.size(), result);
    }

    @Override
    public synchronized void close() {
        closed = true;
        worker.cancel(false);
        pending.clear();
        recent.clear();
    }
}
