package com.norule.musicbot.service.music;

import com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureClassifier;
import com.norule.musicbot.domain.music.bilibili.BilibiliFailureStage;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.BiConsumer;

/** Admission/retry at the load boundary. Waiting never occupies a LavaPlayer worker.
 * The source's existing bucket, not this queue, charges every HTTP request. */
public final class BilibiliLoadScheduler implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BilibiliLoadScheduler.class);
    private static final int GLOBAL_LIMIT = 1000;
    private static final int GUILD_LIMIT = 100;
    private static final long DEADLINE_MS = 10 * 60_000L;
    private static final long ATTEMPT_TIMEOUT_MS = 90_000L;
    private final Map<Long, ArrayDeque<Task>> guilds = new LinkedHashMap<>();
    private final ScheduledThreadPoolExecutor timer;
    private final LongSupplier permitDelay;
    private final LongSupplier playbackDelay;
    private final Clock clock;
    private final BilibiliFailureClassifier classifier = new BilibiliFailureClassifier();
    private Task active;
    private ScheduledFuture<?> wakeup;
    private int size;
    private boolean closed;

    public BilibiliLoadScheduler(LongSupplier permitDelay) {
        this(permitDelay, permitDelay);
    }

    public BilibiliLoadScheduler(LongSupplier permitDelay, LongSupplier playbackDelay) {
        this(permitDelay, playbackDelay, Clock.systemUTC(), newTimer());
    }

    BilibiliLoadScheduler(LongSupplier permitDelay, Clock clock, ScheduledThreadPoolExecutor timer) {
        this(permitDelay, permitDelay, clock, timer);
    }

    BilibiliLoadScheduler(LongSupplier permitDelay, LongSupplier playbackDelay, Clock clock, ScheduledThreadPoolExecutor timer) {
        this.permitDelay = permitDelay;
        this.playbackDelay = playbackDelay;
        this.clock = clock;
        this.timer = timer;
        timer.setRemoveOnCancelPolicy(true);
    }

    private static ScheduledThreadPoolExecutor newTimer() {
        return new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "bilibili-load-admission");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void submit(long guildId, String source, boolean background,
                       Consumer<AudioLoadResultHandler> start, AudioLoadResultHandler result) {
        submitTraced(guildId, source, background, () -> 0L, (requestId, callback) -> start.accept(callback), result);
    }

    public void submitTraced(long guildId, String source, boolean background,
                       LongSupplier additionalDelay,
                       BiConsumer<String, AudioLoadResultHandler> start, AudioLoadResultHandler result) {
        Task task = new Task(guildId, source, background, additionalDelay, start, result, clock.millis());
        boolean rejected;
        synchronized (this) {
            ArrayDeque<Task> queue = guilds.get(guildId);
            int guildSize = (queue == null ? 0 : queue.size())
                    + (active != null && active.guildId == guildId ? 1 : 0);
            rejected = closed || size >= GLOBAL_LIMIT || guildSize >= GUILD_LIMIT;
            if (!rejected) {
                guilds.computeIfAbsent(guildId, ignored -> new ArrayDeque<>()).add(task);
                size++;
            }
        }
        if (rejected) result.loadFailed(error("BILIBILI_LOAD_QUEUE_FULL"));
        else pump();
    }

    private void pump() {
        Task selected = null;
        synchronized (this) {
            if (closed || active != null || guilds.isEmpty()) return;
            if (wakeup != null) wakeup.cancel(false);
            long now = clock.millis();
            long earliest = Long.MAX_VALUE;
            // Guild insertion order rotates after every attempt. Within a guild,
            // foreground can overtake background; aging prevents starvation.
            for (ArrayDeque<Task> queue : guilds.values()) {
                for (Task task : queue) {
                    long ready = Math.min(task.created + DEADLINE_MS,
                            Math.max(task.readyAt, now + Math.min(DEADLINE_MS, taskDelay(task))));
                    earliest = Math.min(earliest, ready);
                    if (ready > now) continue;
                    if (selected == null || (selected.background && !task.background
                            && now - selected.created < 5_000L)) selected = task;
                    if (!task.background || now - task.created >= 5_000L) break;
                }
            }
            if (selected == null) {
                wakeup = timer.schedule(this::pump, Math.max(1L, earliest - now), TimeUnit.MILLISECONDS);
                return;
            }
            ArrayDeque<Task> queue = guilds.remove(selected.guildId);
            queue.remove(selected);
            if (!queue.isEmpty()) guilds.put(selected.guildId, queue);
            active = selected;
        }
        start(selected);
    }

    private void start(Task task) {
        synchronized (task) {
            if (task.cancelled) { release(task, false); return; }
            if (clock.millis() - task.created >= DEADLINE_MS) {
                finish(task, () -> task.result.loadFailed(error("BILIBILI_LOAD_WAIT_TIMEOUT")));
                return;
            }
            int attempt = ++task.attempts;
            task.watchdog = timer.schedule(() -> complete(task, attempt,
                    () -> task.result.loadFailed(error("BILIBILI_LOAD_ATTEMPT_TIMEOUT"))),
                    ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            try {
                task.start.accept(task.requestId, new AudioLoadResultHandler() {
                    @Override public void trackLoaded(AudioTrack track) {
                        complete(task, attempt, () -> task.result.trackLoaded(track));
                    }
                    @Override public void playlistLoaded(AudioPlaylist playlist) {
                        complete(task, attempt, () -> task.result.playlistLoaded(playlist));
                    }
                    @Override public void noMatches() {
                        complete(task, attempt, task.result::noMatches);
                    }
                    @Override public void loadFailed(FriendlyException failure) {
                        failed(task, attempt, failure);
                    }
                });
            } catch (RuntimeException failure) {
                failed(task, attempt, new FriendlyException("Bilibili load submission failed",
                        FriendlyException.Severity.SUSPICIOUS, failure));
            }
        }
    }

    private void failed(Task task, int attempt, FriendlyException failure) {
        boolean retry = false;
        synchronized (task) {
            if (task.cancelled) { release(task, false); return; }
            if (task.completedAttempt >= attempt) return;
            var report = classifier.classify(failure, BilibiliFailureStage.METADATA);
            boolean local = report.category() == BilibiliFailureCategory.BILIBILI_RATE_LIMITED
                    && report.httpStatus() == 0;
            boolean transientFailure = report.httpStatus() == 429 || report.httpStatus() >= 500 || report.retryable();
            boolean withinRetryLimit = local ? ++task.localWaits <= 120 : transientFailure && ++task.remoteRetries <= 3;
            if (withinRetryLimit
                    && clock.millis() - task.created < DEADLINE_MS) {
                task.completedAttempt = attempt;
                task.watchdog.cancel(false);
                long backoff = local ? Math.max(25L, taskDelay(task))
                        : Math.max(1_000L << Math.min(3, task.remoteRetries - 1), taskDelay(task));
                task.readyAt = clock.millis() + backoff;
                if (task.localWaits == 1 || !local) LOG.info(
                        "[BilibiliBatch] event=RATE_LIMIT_WAIT requestId={} guildId={} source={} reason={} httpStatus={} attempt={}",
                        task.requestId, task.guildId, task.source, local ? "LOCAL_LIMIT" : "REMOTE_BACKOFF",
                        report.httpStatus(), attempt);
                retry = true;
            }
        }
        if (retry) release(task, true);
        else complete(task, attempt, () -> task.result.loadFailed(failure));
    }

    private long taskDelay(Task task) {
        return Math.max(task.additionalDelay.getAsLong(),
                task.source.startsWith("TRACK_RECOVERY") ? playbackDelay.getAsLong() : permitDelay.getAsLong());
    }

    private void complete(Task task, int attempt, Runnable deliver) {
        synchronized (task) {
            if (task.cancelled) { release(task, false); return; }
            if (task.completedAttempt >= attempt) return;
            task.completedAttempt = attempt;
            finish(task, deliver);
        }
    }

    private void finish(Task task, Runnable deliver) {
        if (task.watchdog != null) task.watchdog.cancel(false);
        try { deliver.run(); }
        catch (RuntimeException failure) {
            LOG.warn("Bilibili load callback failed: requestId={} type={}", task.requestId, failure.getClass().getSimpleName());
        } finally { release(task, false); }
    }

    private void release(Task task, boolean retry) {
        synchronized (this) {
            if (active != task) return;
            active = null;
            ArrayDeque<Task> waiting = guilds.remove(task.guildId);
            if (waiting != null) guilds.put(task.guildId, waiting);
            if (retry && !closed && !task.cancelled) {
                guilds.computeIfAbsent(task.guildId, ignored -> new ArrayDeque<>()).add(task);
            } else size--;
        }
        // Avoid recursive callback chains when a cache hit completes synchronously.
        if (!timer.isShutdown()) timer.execute(this::pump);
    }

    public void cancelGuild(long guildId) {
        ArrayList<Task> removed = new ArrayList<>();
        synchronized (this) {
            var queue = guilds.remove(guildId);
            if (queue != null) { removed.addAll(queue); size -= queue.size(); }
            if (active != null && active.guildId == guildId) removed.add(active);
        }
        for (Task task : removed) synchronized (task) {
            task.cancelled = true;
        }
        // An executing HTTP attempt retains the global slot until its callback or
        // watchdog arrives; cancellation must not create overlapping requests.
    }

    @Override public void close() {
        ArrayList<Long> ids;
        synchronized (this) {
            closed = true;
            ids = new ArrayList<>(guilds.keySet());
            if (active != null) ids.add(active.guildId);
        }
        ids.forEach(this::cancelGuild);
        timer.shutdownNow();
    }

    private static FriendlyException error(String code) {
        return new FriendlyException(code, FriendlyException.Severity.COMMON, null);
    }

    private static final class Task {
        final long guildId;
        final String source;
        final String requestId = UUID.randomUUID().toString();
        final boolean background;
        final BiConsumer<String, AudioLoadResultHandler> start;
        final LongSupplier additionalDelay;
        final AudioLoadResultHandler result;
        final long created;
        long readyAt;
        int attempts;
        int completedAttempt;
        int localWaits;
        int remoteRetries;
        volatile boolean cancelled;
        ScheduledFuture<?> watchdog;
        Task(long guildId, String source, boolean background, LongSupplier additionalDelay, BiConsumer<String, AudioLoadResultHandler> start,
             AudioLoadResultHandler result, long created) {
            this.guildId = guildId; this.source = source; this.background = background;
            this.start = start; this.result = result; this.created = created;
            this.additionalDelay = additionalDelay;
        }
    }
}
