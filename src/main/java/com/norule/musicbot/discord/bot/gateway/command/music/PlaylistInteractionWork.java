package com.norule.musicbot.discord.bot.gateway.command.music;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded database work; autocomplete deadlines never wait for a blocked JDBC call. */
final class PlaylistInteractionWork implements AutoCloseable {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(PlaylistInteractionWork.class);
    private final ExecutorService queries = pool("playlist-autocomplete", 2, 32);
    private final ExecutorService commands = pool("playlist-command", 4, 64);
    private final ScheduledExecutorService deadlines;
    private final Clock clock;
    private final Map<Long, Long> claimed = new HashMap<>();

    PlaylistInteractionWork(ScheduledExecutorService deadlines, Clock clock) {
        this.deadlines = deadlines;
        this.clock = clock;
    }

    synchronized boolean claim(long id, long createdAt, boolean acknowledged) {
        long now = clock.millis();
        claimed.values().removeIf(expiry -> expiry <= now);
        if (acknowledged || now - createdAt >= 2500 || claimed.containsKey(id) || claimed.size() >= 4096) {
            return false;
        }
        // An older duplicate is rejected by its interaction timestamp, so retain IDs
        // only for the ACK window rather than keeping one minute of typing events.
        claimed.put(id, now + 3_000);
        return true;
    }

    <T> CompletableFuture<List<T>> autocomplete(Supplier<List<T>> query, long budgetMillis) {
        CompletableFuture<List<T>> result = new CompletableFuture<>();
        if (budgetMillis <= 0) {
            result.complete(List.of());
            return result;
        }
        ScheduledFuture<?> timeout = deadlines.schedule(
                () -> { result.complete(List.of()); }, budgetMillis, TimeUnit.MILLISECONDS);
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (!result.isDone()) {
                try {
                    result.complete(List.copyOf(query.get()));
                } catch (RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            }
            return null;
        });
        result.whenComplete((value, failure) -> {
            timeout.cancel(false);
            // Remove expired queued tasks. A running JDBC operation may ignore interruption,
            // so it retains its bounded worker until it exits; never create replacement workers.
            task.cancel(false);
            ((ThreadPoolExecutor) queries).remove(task);
        });
        try {
            queries.execute(task);
        } catch (RejectedExecutionException overloaded) {
            LOGGER.warn("event=DISCORD_AUTOCOMPLETE_DEGRADED command=playlist reason=QUERY_POOL_SATURATED");
            result.complete(List.of());
        }
        return result;
    }

    void command(Runnable command) {
        commands.execute(command);
    }

    private static ExecutorService pool(String name, int size, int capacity) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void close() {
        queries.shutdownNow();
        commands.shutdownNow();
    }
}
