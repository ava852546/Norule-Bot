package com.norule.musicbot.discord.bot.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Admission control for optional maintenance only. JDA still owns every HTTP retry. */
public final class DiscordBackgroundRestQueue implements AutoCloseable {
    public static final int MAX_PENDING = 256;
    public static final int MAX_IN_FLIGHT = 2;
    public static final long INTERVAL_MS = 250;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoTime;
    private final Map<String, Work> pending = new LinkedHashMap<>();
    private ScheduledFuture<?> task;
    private int inFlight;
    private long pausedUntil;
    private long nextStart;
    private boolean closed;

    public DiscordBackgroundRestQueue(ScheduledExecutorService scheduler) {
        this(scheduler, System::nanoTime);
    }

    public DiscordBackgroundRestQueue(ScheduledExecutorService scheduler, LongSupplier nanoTime) {
        this.scheduler = scheduler;
        this.nanoTime = nanoTime;
        this.nextStart = nanoTime.getAsLong();
        this.pausedUntil = nextStart;
    }

    public synchronized CompletableFuture<Void> submit(String key, Supplier<CompletableFuture<Void>> operation) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Background REST queue closed"));
        }
        Work existing = pending.get(key);
        if (existing != null) {
            existing.operation = operation;
            return existing.result;
        }
        if (pending.size() >= MAX_PENDING) {
            return CompletableFuture.failedFuture(new IllegalStateException("Background REST queue full; maintenance skipped"));
        }
        Work work = new Work(operation);
        pending.put(key, work);
        schedule();
        return work.result;
    }

    /** Conservatively pause optional maintenance for any scope; foreground REST remains with JDA. */
    public synchronized void pause(long retryAfterMs) {
        pausedUntil = Math.max(pausedUntil, nanoTime.getAsLong()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1000, retryAfterMs)));
    }

    public synchronized int pendingCount() { return pending.size(); }
    public synchronized int inFlightCount() { return inFlight; }

    public void cancelPending(String key) {
        Work work;
        synchronized (this) {
            work = pending.remove(key);
        }
        if (work != null) {
            work.result.cancel(false);
        }
    }

    private void schedule() {
        if (closed || task != null || pending.isEmpty() || inFlight >= MAX_IN_FLIGHT) {
            return;
        }
        long delay = Math.max(0, Math.max(pausedUntil, nextStart) - nanoTime.getAsLong());
        task = scheduler.schedule(this::drain, delay, TimeUnit.NANOSECONDS);
    }

    private void drain() {
        Work work;
        synchronized (this) {
            task = null;
            if (closed || pending.isEmpty()) {
                return;
            }
            if (nanoTime.getAsLong() < Math.max(pausedUntil, nextStart)) {
                schedule();
                return;
            }
            var iterator = pending.values().iterator();
            work = iterator.next();
            iterator.remove();
            inFlight++;
            nextStart = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(INTERVAL_MS);
            schedule();
        }
        try {
            work.operation.get().whenComplete((ignored, failure) -> finish(work, failure));
        } catch (RuntimeException failure) {
            finish(work, failure);
        }
    }

    private void finish(Work work, Throwable failure) {
        synchronized (this) {
            inFlight--;
            schedule();
        }
        if (failure == null) {
            work.result.complete(null);
        } else {
            work.result.completeExceptionally(failure);
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
        }
        cancelled.forEach(work -> work.result.cancel(false));
    }

    private static final class Work {
        private Supplier<CompletableFuture<Void>> operation;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private Work(Supplier<CompletableFuture<Void>> operation) { this.operation = operation; }
    }
}
