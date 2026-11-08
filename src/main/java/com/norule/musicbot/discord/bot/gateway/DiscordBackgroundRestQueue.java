package com.norule.musicbot.discord.bot.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Admission control for optional maintenance only. JDA still owns every HTTP retry. */
public final class DiscordBackgroundRestQueue implements AutoCloseable {
    public static final int MAX_PENDING = 1024;
    // Safety bound on retained JDA futures, not a shared two-request completion barrier.
    public static final int MAX_IN_FLIGHT = 64;
    // Optional work admits at most 40/s, leaving headroom for foreground traffic.
    // This is not a Discord bucket implementation: JDA still owns all actual HTTP limits.
    public static final long INTERVAL_MS = 25;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoTime;
    private final Map<String, Work> pending = new LinkedHashMap<>();
    private final Set<String> activeKeys = new HashSet<>();
    private ScheduledFuture<?> task;
    private int inFlight;
    private long pausedUntil;
    private long nextStart;
    private boolean closed;
    private long coalesced;
    private long rejected;
    private long completed;
    private long failed;
    private long cancelled;
    private int maximumPending;

    public DiscordBackgroundRestQueue(ScheduledExecutorService scheduler) {
        this(scheduler, System::nanoTime);
    }

    public DiscordBackgroundRestQueue(ScheduledExecutorService scheduler, LongSupplier nanoTime) {
        this.scheduler = scheduler;
        this.nanoTime = nanoTime;
        this.nextStart = nanoTime.getAsLong();
        this.pausedUntil = nextStart;
    }

    public CompletableFuture<Void> submit(String key, Supplier<CompletableFuture<Void>> operation) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(operation);
        Work work;
        Rejection rejection;
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Background REST queue closed"));
            }
            Work existing = pending.get(key);
            if (existing != null) {
                existing.operation = operation;
                coalesced++;
                return existing.result;
            }
            if (pending.size() >= MAX_PENDING) {
                rejected++;
                return CompletableFuture.failedFuture(new IllegalStateException("Background REST queue full; maintenance skipped"));
            }
            work = new Work(key, operation);
            pending.put(key, work);
            maximumPending = Math.max(maximumPending, pending.size());
            rejection = schedule();
        }
        if (rejection != null) rejection.complete();
        return work.result;
    }

    /** Pause optional maintenance for global limits; foreground REST remains with JDA. */
    public synchronized void pause(long retryAfterMs) {
        pausedUntil = Math.max(pausedUntil, nanoTime.getAsLong()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1000, retryAfterMs)));
    }

    public synchronized int pendingCount() { return pending.size(); }
    public synchronized int inFlightCount() { return inFlight; }

    public synchronized Metrics metrics() {
        return new Metrics(pending.size(), inFlight, maximumPending, coalesced, rejected, completed, failed, cancelled);
    }

    public record Metrics(int pending, int inFlight, int maximumPending, long coalesced,
                          long rejected, long completed, long failed, long cancelled) {}

    public void cancelPending(String key) {
        Work work;
        synchronized (this) {
            work = pending.remove(key);
            if (work != null) cancelled++;
        }
        if (work != null) {
            work.result.cancel(false);
        }
    }

    private Rejection schedule() {
        if (closed || task != null || pending.isEmpty() || inFlight >= MAX_IN_FLIGHT
                || pending.keySet().stream().allMatch(activeKeys::contains)) {
            return null;
        }
        long delay = Math.max(0, Math.max(pausedUntil, nextStart) - nanoTime.getAsLong());
        try {
            task = scheduler.schedule(this::drain, delay, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            // Never leave callers holding a pending future after scheduler shutdown.
            var abandoned = java.util.List.copyOf(pending.values());
            pending.clear();
            closed = true;
            rejected += abandoned.size();
            return new Rejection(abandoned, failure);
        }
        return null;
    }

    private void drain() {
        Work work = null;
        Rejection rejection;
        synchronized (this) {
            task = null;
            if (closed || pending.isEmpty() || inFlight >= MAX_IN_FLIGHT) {
                return;
            }
            if (nanoTime.getAsLong() < Math.max(pausedUntil, nextStart)) {
                rejection = schedule();
            } else {
                // FIFO among eligible keys. A stalled bucket never holds the head of the queue.
                work = pending.values().stream().filter(candidate -> !activeKeys.contains(candidate.key))
                        .findFirst().orElse(null);
                if (work == null) return;
                pending.remove(work.key);
                activeKeys.add(work.key);
                inFlight++;
                nextStart = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(INTERVAL_MS);
                rejection = schedule();
            }
        }
        if (rejection != null) rejection.complete();
        if (work == null) return;
        try {
            Work admitted = work;
            work.operation.get().whenComplete((ignored, failure) -> finish(admitted, failure));
        } catch (RuntimeException failure) {
            finish(work, failure);
        }
    }

    private void finish(Work work, Throwable failure) {
        Rejection rejection;
        synchronized (this) {
            inFlight--;
            activeKeys.remove(work.key);
            if (failure == null) completed++; else failed++;
            rejection = schedule();
        }
        if (rejection != null) rejection.complete();
        if (failure == null) {
            work.result.complete(null);
        } else {
            work.result.completeExceptionally(failure);
        }
    }

    private record Rejection(java.util.List<Work> abandoned, RuntimeException failure) {
        void complete() {
            // Callbacks may acquire per-guild locks: never invoke them under the queue lock.
            abandoned.forEach(work -> work.result.completeExceptionally(failure));
        }
    }

    @Override
    public void close() {
        java.util.List<Work> cancelled;
        synchronized (this) {
            closed = true;
            if (task != null) {
                task.cancel(false);
            }
            cancelled = java.util.List.copyOf(pending.values());
            pending.clear();
            this.cancelled += cancelled.size();
        }
        cancelled.forEach(work -> work.result.cancel(false));
    }

    private static final class Work {
        private final String key;
        private Supplier<CompletableFuture<Void>> operation;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private Work(String key, Supplier<CompletableFuture<Void>> operation) {
            this.key = key;
            this.operation = operation;
        }
    }
}
