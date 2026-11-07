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
                if (state.pending.isEmpty()) state.requestedAt = nanoTime.getAsLong();
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
                ManualWork work = new ManualWork(prepareManual, new CompletableFuture<>());
                state.manual.add(work);
                schedule(guildId, state);
                return work.result();
            }
        }
        return CompletableFuture.failedFuture(new IllegalStateException("Music panel scheduler is closed."));
    }

    private void schedule(long guildId, RefreshState state) {
        if (!closed && !state.inFlight && state.task == null && (!state.pending.isEmpty() || !state.manual.isEmpty())) {
            try {
                long remaining = state.submitted
                        ? TimeUnit.NANOSECONDS.toMillis(Math.max(0, state.nextSubmission - nanoTime.getAsLong())
                                + TimeUnit.MILLISECONDS.toNanos(1) - 1) : 0;
                // Stable per-guild staggering; foreground events retain the short debounce.
                state.backgroundTask = state.manual.isEmpty()
                        && state.pending.equals(EnumSet.of(RefreshReason.PERIODIC_REFRESH));
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
            state.activeRequestedAt = state.activeManual == null ? state.requestedAt : state.startedAt;
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
                LOGGER.debug("[NoRule] Music panel PATCH: guildId={} channelId={} messageId={} reason={}",
                        guildId, update.channelId(), update.messageId(), reasons);
                state.submitted = true;
                state.sentCount++;
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

    public String diagnostics(long guildId) {
        RefreshState state = states.get(guildId);
        if (state == null) return "pendingRefresh=false inFlight=false coalescedCount=0";
        synchronized (state) {
            return "pendingRefresh=" + (!state.pending.isEmpty() || !state.manual.isEmpty())
                    + " inFlight=" + state.inFlight + " coalescedCount=" + state.coalescedCount
                    + " sentCount=" + state.sentCount + " unchangedCount=" + state.unchangedCount
                    + " route429Count=" + state.rateLimitedCount + " reasons=" + state.activeReasons
                    + " scheduleWaitMs=" + state.lastScheduleWaitMs + " admissionWaitMs=" + state.lastAdmissionWaitMs
                    + " restMs=" + state.lastRestMs + " totalMs=" + state.lastTotalMs;
        }
    }

    boolean sameContent(long guildId, Update update) {
        RefreshState state = states.get(guildId);
        if (state == null) return false;
        synchronized (state) {
            return update.sameContent(state.lastSent);
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
                    && snapshot.sameContent(other.snapshot);
        }
    }

    private static final class RefreshState {
        private final ArrayDeque<ManualWork> manual = new ArrayDeque<>();
        private ManualWork activeManual;
        private final EnumSet<RefreshReason> pending = EnumSet.noneOf(RefreshReason.class);
        private ScheduledFuture<?> task;
        private long taskVersion;
        private boolean backgroundTask;
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
        private long unchangedCount;
        private long rateLimitedCount;
        private Set<RefreshReason> activeReasons = Set.of();
        private boolean slowLogged;
        private long lastSlowLogAt;
        private Update lastSent;
    }

    private record ManualWork(Supplier<CompletableFuture<Update>> prepare, CompletableFuture<Void> result) {
    }
}
