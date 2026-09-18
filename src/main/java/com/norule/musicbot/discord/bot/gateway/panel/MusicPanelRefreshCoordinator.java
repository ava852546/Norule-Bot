package com.norule.musicbot.discord.bot.gateway.panel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
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

/** Per-guild bounded pending work. JDA owns rate limiting for the single submitted edit. */
public final class MusicPanelRefreshCoordinator implements AutoCloseable {
    public static final long DEBOUNCE_MILLIS = 750L;
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicPanelRefreshCoordinator.class);
    private final Map<Long, RefreshState> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final BiFunction<Long, Set<RefreshReason>, CompletableFuture<Update>> prepare;
    private volatile boolean closed;

    public MusicPanelRefreshCoordinator(ScheduledExecutorService scheduler,
            BiFunction<Long, Set<RefreshReason>, CompletableFuture<Update>> prepare) {
        this.scheduler = scheduler;
        this.prepare = prepare;
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

    private void schedule(long guildId, RefreshState state) {
        if (!closed && !state.inFlight && state.task == null && !state.pending.isEmpty()) {
            try {
                state.task = scheduler.schedule(() -> drain(guildId, state), DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException failure) {
                state.pending.clear();
                states.remove(guildId, state);
                LOGGER.debug("[NoRule] Music panel refresh scheduler unavailable: guildId={}", guildId, failure);
            }
        }
    }

    private void drain(long guildId, RefreshState state) {
        Set<RefreshReason> reasons;
        long generation;
        synchronized (state) {
            if (closed || states.get(guildId) != state) {
                return;
            }
            state.task = null;
            state.inFlight = true;
            reasons = Set.copyOf(state.pending);
            state.pending.clear();
            generation = state.generation;
        }
        // Rendering may read player locks: never hold the refresh lock while rendering.
        try {
            prepare.apply(guildId, reasons).whenComplete((update, failure) -> {
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
                    LOGGER.debug("[NoRule] Music panel refresh skipped: guildId={} reason=UNCHANGED", guildId);
                    finish(guildId, state, generation, null, null);
                    return;
                }
                LOGGER.debug("[NoRule] Music panel PATCH: guildId={} channelId={} messageId={} reason={}",
                        guildId, update.channelId(), update.messageId(), reasons);
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
            if (failure != null) {
                LOGGER.debug("[NoRule] panel.refresh.failed guildId={}", guildId, failure);
            } else if (update != null && generation == state.generation && !closed) {
                state.lastSent = update;
                LOGGER.debug("[NoRule] panel.refresh.sent guildId={}", guildId);
            }
            state.inFlight = false;
            // A failure alone never queues a retry. Only requests arriving during the flight remain.
            if (closed || (state.lastSent == null && state.pending.isEmpty())) {
                states.remove(guildId, state);
            } else {
                schedule(guildId, state);
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
                state.lastSent = update;
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
            if (state.task != null) {
                state.task.cancel(false);
                state.task = null;
            }
            // Retain the flight barrier until JDA completes, even across panel replacement.
            if (!state.inFlight) {
                states.remove(guildId, state);
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
        private final EnumSet<RefreshReason> pending = EnumSet.noneOf(RefreshReason.class);
        private ScheduledFuture<?> task;
        private boolean inFlight;
        private long generation;
        private Update lastSent;
    }
}
