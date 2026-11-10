package com.norule.musicbot.discord.bot.gateway.panel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

/** Per-guild bounded pending work. JDA owns rate limiting for the single submitted edit. */
public final class MusicPanelRefreshCoordinator implements AutoCloseable {
    public static final long DEBOUNCE_MILLIS = 750L;
    public static final long MIN_UPDATE_INTERVAL_MILLIS = 1_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicPanelRefreshCoordinator.class);
    private final Map<Long, RefreshState> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final LongSupplier nanoTime;
    private final BiFunction<Long, Set<RefreshReason>, CompletableFuture<Update>> prepare;
    private volatile boolean closed;

    public MusicPanelRefreshCoordinator(ScheduledExecutorService scheduler,
            BiFunction<Long, Set<RefreshReason>, CompletableFuture<Update>> prepare) {
        this(scheduler, prepare, System::nanoTime);
    }

    MusicPanelRefreshCoordinator(ScheduledExecutorService scheduler,
            BiFunction<Long, Set<RefreshReason>, CompletableFuture<Update>> prepare, LongSupplier nanoTime) {
        this.scheduler = scheduler;
        this.prepare = prepare;
        this.nanoTime = nanoTime;
    }

    public void requestRefresh(long guildId, RefreshReason reason) {
        requestRefresh(guildId, reason, false);
    }

    /** Batch-derived changes remain dirty without a timer until completion or a real operation. */
    public void requestRefresh(long guildId, RefreshReason reason, boolean batchQueueChange) {
        while (!closed) {
            RefreshState state = states.computeIfAbsent(guildId, ignored -> new RefreshState());
            synchronized (state) {
                if (states.get(guildId) != state) {
                    continue;
                }
                if (closed) {
                    states.remove(guildId, state);
                    return;
                }
                LOGGER.debug("[NoRule] panel.refresh.requested guildId={} reason={}", guildId, reason);
                boolean coalesced = state.inFlight || !state.pending.isEmpty();
                if (coalesced) state.coalescedCount++;
                if (reason.highPriority()) {
                    state.pending.removeIf(pending -> !pending.highPriority());
                    if (state.backgroundTask && state.task != null) {
                        state.task.cancel(false);
                        state.task = null;
                    }
                } else if (state.pending.stream().anyMatch(RefreshReason::highPriority)) {
                    return;
                }
                if (state.pending.isEmpty()) {
                    state.requestedAt = nanoTime.getAsLong();
                    state.batchOnly = batchQueueChange;
                } else {
                    state.batchOnly &= batchQueueChange;
                }
                state.pending.add(reason);
                if (coalesced) {
                    LOGGER.debug("[NoRule] Music panel refresh coalesced: guildId={} reason={} pendingReasons={}",
                            guildId, reason, state.pending);
                }
                schedule(guildId, state);
                return;
            }
        }
    }

    /** Manual operations share the same flight barrier as automatic refreshes. */
    public CompletableFuture<Void> submit(long guildId, Supplier<CompletableFuture<Update>> prepareManual) {
        while (!closed) {
            RefreshState state = states.computeIfAbsent(guildId, ignored -> new RefreshState());
            synchronized (state) {
                if (states.get(guildId) != state) {
                    continue;
                }
                if (closed) {
                    break;
                }
                if (state.manual.size() >= 20) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Music panel command queue is full; try again later."));
                }
                ManualWork work = new ManualWork(prepareManual, new CompletableFuture<>(), nanoTime.getAsLong());
                state.manual.add(work);
                schedule(guildId, state);
                return work.result();
            }
        }
        return CompletableFuture.failedFuture(new IllegalStateException("Music panel scheduler is closed."));
    }

    private void schedule(long guildId, RefreshState state) {
        if (!closed && !state.inFlight && state.task == null
                && ((!state.pending.isEmpty() && !state.batchOnly) || !state.manual.isEmpty())) {
            try {
                long remaining = state.submitted
                        ? TimeUnit.NANOSECONDS.toMillis(Math.max(0, state.nextSubmission - nanoTime.getAsLong())
                                + TimeUnit.MILLISECONDS.toNanos(1) - 1) : 0;
                // Stable per-guild staggering; foreground events retain the short debounce.
                state.backgroundTask = state.manual.isEmpty()
                        && state.pending.stream().noneMatch(RefreshReason::highPriority);
                long debounce = DEBOUNCE_MILLIS + (state.backgroundTask
                        ? Math.floorMod(Long.hashCode(guildId), 251) : 0);
                long taskVersion = ++state.taskVersion;
                state.task = scheduler.schedule(() -> drain(guildId, state, taskVersion),
                        Math.max(debounce, remaining), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException failure) {
                state.manual.forEach(work -> work.result().completeExceptionally(failure));
                state.manual.clear();
                state.pending.clear();
                states.remove(guildId, state);
                LOGGER.debug("[NoRule] Music panel refresh scheduler unavailable: guildId={}", guildId, failure);
            }
        }
    }

    private void drain(long guildId, RefreshState state, long taskVersion) {
        Set<RefreshReason> reasons;
        long generation;
        synchronized (state) {
            if (closed || states.get(guildId) != state || state.taskVersion != taskVersion) {
                return;
            }
            state.task = null;
            state.inFlight = true;
            state.activeManual = state.manual.poll();
            reasons = state.activeManual == null ? Set.copyOf(state.pending) : Set.of(RefreshReason.MANUAL);
            state.activeReasons = reasons;
            state.startedAt = nanoTime.getAsLong();
            state.activeRequestedAt = state.activeManual == null ? state.requestedAt : state.activeManual.requestedAt();
            state.lastScheduleWaitMs = TimeUnit.NANOSECONDS.toMillis(state.startedAt - state.activeRequestedAt);
            state.admitted = false;
            state.lastAdmissionWaitMs = 0;
            if (state.activeManual == null) {
                state.pending.clear();
            }
            generation = state.generation;
        }
        // Rendering may read player locks: never hold the refresh lock while rendering.
        try {
            (state.activeManual == null ? prepare.apply(guildId, reasons) : state.activeManual.prepare().get())
                    .whenComplete((update, failure) -> {
                if (failure != null) {
                    finish(guildId, state, generation, null, failure);
                } else {
                    deliver(guildId, state, generation, reasons, update);
                }
            });
        } catch (RuntimeException failure) {
            finish(guildId, state, generation, null, failure);
        }
    }

    private void deliver(long guildId, RefreshState state, long generation, Set<RefreshReason> reasons, Update update) {
        CompletableFuture<Void> delivery;
        synchronized (state) {
            if (closed || generation != state.generation || update == null) {
                finish(guildId, state, generation, null, null);
                return;
            }
            try {
                if (update.sameContent(state.lastSent)) {
                    state.unchangedCount++;
                    LOGGER.debug("[NoRule] Music panel refresh skipped: guildId={} reason=UNCHANGED", guildId);
                    finish(guildId, state, generation, null, null);
                    return;
                }
                LOGGER.debug("[NoRule] panel.refresh.prepared guildId={} channelId={} messageId={} reason={}",
                        guildId, update.channelId(), update.messageId(), reasons);
                state.submitted = true;
                state.preparedCount++;
                state.nextSubmission = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(MIN_UPDATE_INTERVAL_MILLIS);
                state.deliveryVersion = state.contentVersion;
                delivery = update.send().get();
            } catch (RuntimeException failure) {
                finish(guildId, state, generation, null, failure);
                return;
            }
        }
        delivery.whenComplete((ignored, failure) -> finish(guildId, state, generation, update, failure));
    }

    private void finish(long guildId, RefreshState state, long generation, Update update, Throwable failure) {
        synchronized (state) {
            long now = nanoTime.getAsLong();
            state.lastTotalMs = TimeUnit.NANOSECONDS.toMillis(now - state.activeRequestedAt);
            state.lastRestMs = state.admitted ? TimeUnit.NANOSECONDS.toMillis(now - state.admittedAt) : 0;
            Throwable cause = failure;
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            String result;
            if (closed || generation != state.generation || cause instanceof java.util.concurrent.CancellationException) {
                state.cancelledCount++;
                result = "CANCELLED";
            } else if (failure != null) {
                state.failedCount++;
                result = "FAILED";
            } else if (state.admitted) {
                state.successCount++;
                result = "SUCCESS";
            } else {
                state.skippedCount++;
                result = "SKIPPED";
            }
            state.totalDurations[state.durationCursor++ % state.totalDurations.length] = state.lastTotalMs;
            state.durationCursor %= state.totalDurations.length;
            state.durationSamples = Math.min(state.totalDurations.length, state.durationSamples + 1);
            LOGGER.debug("event=MUSIC_PANEL_REFRESH_METRIC guildId={} reasons={} result={} queueDepth={} "
                            + "scheduleWaitMs={} admissionWaitMs={} restMs={} totalMs={}",
                    guildId, state.activeReasons, result, state.manual.size() + (state.pending.isEmpty() ? 0 : 1),
                    state.lastScheduleWaitMs, state.lastAdmissionWaitMs, state.lastRestMs, state.lastTotalMs);
            if (state.lastTotalMs > 5_000 && (!state.slowLogged
                    || now - state.lastSlowLogAt >= TimeUnit.MINUTES.toNanos(1))) {
                LOGGER.warn("[NoRule] panel.refresh.slow guildId={} reasons={} scheduleWaitMs={} admissionWaitMs={} restMs={} totalMs={}",
                        guildId, state.activeReasons, state.lastScheduleWaitMs, state.lastAdmissionWaitMs,
                        state.lastRestMs, state.lastTotalMs);
                state.slowLogged = true;
                state.lastSlowLogAt = now;
            }
            if (failure != null) {
                LOGGER.debug("[NoRule] panel.refresh.failed guildId={}", guildId, failure);
            } else if (update != null && generation == state.generation && !closed
                    && state.deliveryVersion == state.contentVersion) {
                state.lastSent = update;
                LOGGER.debug("[NoRule] panel.refresh.sent guildId={}", guildId);
            }
            state.inFlight = false;
            ManualWork completed = state.activeManual;
            state.activeManual = null;
            // A failure alone never queues a retry. Only requests arriving during the flight remain.
            if (closed || (!state.submitted && state.lastSent == null && state.pending.isEmpty() && state.manual.isEmpty())) {
                states.remove(guildId, state);
            } else {
                schedule(guildId, state);
            }
            if (completed != null) {
                if (failure != null) {
                    completed.result().completeExceptionally(failure);
                } else if (closed || generation != state.generation) {
                    completed.result().completeExceptionally(new IllegalStateException("Music panel operation was cancelled."));
                } else {
                    completed.result().complete(null);
                }
            }
        }
    }

    public void recordCreated(long guildId, Update update) {
        while (!closed) {
            RefreshState state = states.computeIfAbsent(guildId, ignored -> new RefreshState());
            synchronized (state) {
                if (states.get(guildId) != state) {
                    continue;
                }
                if (closed) {
                    states.remove(guildId, state);
                    return;
                }
                if (update != null && (state.lastSent == null
                        || state.lastSent.channelId() != update.channelId()
                        || state.lastSent.messageId() != update.messageId())) {
                    state.submitted = true;
                    state.nextSubmission = nanoTime.getAsLong()
                            + TimeUnit.MILLISECONDS.toNanos(MIN_UPDATE_INTERVAL_MILLIS);
                }
                state.lastSent = update;
                state.contentVersion++;
                return;
            }
        }
    }

    public void clear(long guildId) {
        RefreshState state = states.get(guildId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.generation++;
            state.pending.clear();
            state.lastSent = null;
            state.manual.forEach(work -> work.result().cancel(false));
            state.manual.clear();
            if (state.activeManual != null) {
                state.activeManual.result().cancel(false);
            }
            if (state.task != null) {
                state.task.cancel(false);
                state.task = null;
                state.taskVersion++;
            }
            // Retain the flight barrier until JDA completes, even across panel replacement.
            if (!state.inFlight) {
                states.remove(guildId, state);
            }
        }
    }

    public boolean hasPending(long guildId) {
        RefreshState state = states.get(guildId);
        if (state == null) return false;
        synchronized (state) {
            return state.inFlight || !state.pending.isEmpty() || !state.manual.isEmpty();
        }
    }

    /** Dirty batch data alone must not suppress the ordinary progress cadence. */
    public boolean hasScheduledWork(long guildId) {
        RefreshState state = states.get(guildId);
        if (state == null) return false;
        synchronized (state) {
            return state.inFlight || state.task != null || !state.manual.isEmpty();
        }
    }

    public String diagnostics(long guildId) {
        RefreshState state = states.get(guildId);
        if (state == null) return "pendingRefresh=false inFlight=false coalescedCount=0";
        synchronized (state) {
            return "pendingRefresh=" + (!state.pending.isEmpty() || !state.manual.isEmpty())
                    + " inFlight=" + state.inFlight + " coalescedCount=" + state.coalescedCount
                    + " sentCount=" + state.sentCount + " unchangedCount=" + state.unchangedCount
                    + " preparedCount=" + state.preparedCount
                    + " route429Count=" + state.rateLimitedCount + " reasons=" + state.activeReasons
                    + " scheduleWaitMs=" + state.lastScheduleWaitMs + " admissionWaitMs=" + state.lastAdmissionWaitMs
                    + " restMs=" + state.lastRestMs + " totalMs=" + state.lastTotalMs
                    + " completionMetrics=" + metrics(guildId);
        }
    }

    /** Completion durations cover the most recent 128 operations, with bounded memory per guild. */
    public Metrics metrics(long guildId) {
        RefreshState state = states.get(guildId);
        if (state == null) return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0);
        synchronized (state) {
            long[] values = java.util.Arrays.copyOf(state.totalDurations, state.durationSamples);
            java.util.Arrays.sort(values);
            double mean = java.util.Arrays.stream(values).average().orElse(0);
            return new Metrics(state.successCount, state.failedCount, state.cancelledCount, state.skippedCount,
                    state.coalescedCount, state.unchangedCount, mean,
                    values.length == 0 ? 0 : values[(int) Math.ceil(values.length * .95) - 1],
                    values.length == 0 ? 0 : values[(int) Math.ceil(values.length * .99) - 1]);
        }
    }

    public record Metrics(long succeeded, long failed, long cancelled, long skipped, long coalesced,
                          long unchanged, double meanMs, long p95Ms, long p99Ms) {}

    boolean sameContent(long guildId, Update update) {
        RefreshState state = states.get(guildId);
        if (state == null) return false;
        synchronized (state) {
            return update.sameContent(state.lastSent);
        }
    }

    void recordUnchanged(long guildId) {
        RefreshState state = states.get(guildId);
        if (state != null) {
            synchronized (state) { state.unchangedCount++; }
        }
    }

    void submitted(long guildId) {
        RefreshState state = states.get(guildId);
        if (state != null) {
            synchronized (state) {
                state.submitted = true;
                state.admitted = true;
                state.admittedAt = nanoTime.getAsLong();
                state.lastAdmissionWaitMs = TimeUnit.NANOSECONDS.toMillis(state.admittedAt - state.startedAt);
                state.nextSubmission = nanoTime.getAsLong()
                        + TimeUnit.MILLISECONDS.toNanos(MIN_UPDATE_INTERVAL_MILLIS);
            }
        }
    }

    /** Counts PATCH actions handed to JDA, not renders, GET/POST work or HTTP retry attempts. */
    void patchSubmitted(long guildId) {
        RefreshState state = states.get(guildId);
        if (state == null) return;
        synchronized (state) {
            state.sentCount++;
            submitted(guildId);
        }
    }

    void observeRateLimit(long guildId) {
        RefreshState state = states.get(guildId);
        if (state != null) {
            synchronized (state) {
                state.rateLimitedCount++;
            }
        }
    }

    void discardPrepared(long guildId) {
        RefreshState state = states.get(guildId);
        if (state != null) {
            synchronized (state) {
                state.contentVersion++;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        states.keySet().forEach(this::clear);
    }

    public record Update(long channelId, long messageId, MusicPanelSnapshot snapshot,
                         Supplier<CompletableFuture<Void>> send) {
        boolean sameContent(Update other) {
            return other != null && channelId == other.channelId && messageId == other.messageId
                    && snapshot != null && other.snapshot != null && snapshot.sameContent(other.snapshot);
        }
    }

    private static final class RefreshState {
        private final ArrayDeque<ManualWork> manual = new ArrayDeque<>();
        private ManualWork activeManual;
        private final EnumSet<RefreshReason> pending = EnumSet.noneOf(RefreshReason.class);
        private ScheduledFuture<?> task;
        private long taskVersion;
        private boolean backgroundTask;
        private boolean batchOnly;
        private boolean inFlight;
        private long generation;
        private boolean submitted;
        private long nextSubmission;
        private long contentVersion;
        private long deliveryVersion;
        private long coalescedCount;
        private long requestedAt;
        private long activeRequestedAt;
        private long startedAt;
        private long admittedAt;
        private boolean admitted;
        private long lastScheduleWaitMs;
        private long lastAdmissionWaitMs;
        private long lastRestMs;
        private long lastTotalMs;
        private long sentCount;
        private long preparedCount;
        private long unchangedCount;
        private long rateLimitedCount;
        private long successCount;
        private long failedCount;
        private long cancelledCount;
        private long skippedCount;
        private final long[] totalDurations = new long[128];
        private int durationCursor;
        private int durationSamples;
        private Set<RefreshReason> activeReasons = Set.of();
        private boolean slowLogged;
        private long lastSlowLogAt;
        private Update lastSent;
    }

    private record ManualWork(Supplier<CompletableFuture<Update>> prepare, CompletableFuture<Void> result, long requestedAt) {
    }
}
