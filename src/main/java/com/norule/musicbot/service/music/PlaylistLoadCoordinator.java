package com.norule.musicbot.service.music;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.time.Clock;

/** One outstanding entry per batch; bounds include entries not yet submitted to LavaPlayer. */
public final class PlaylistLoadCoordinator implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PlaylistLoadCoordinator.class);
    private static final int GLOBAL_LIMIT = 1000;
    private static final int GUILD_LIMIT = 100;
    private final Map<Key, Job> jobs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Clock clock;
    private final ScheduledThreadPoolExecutor executor;
    private static ScheduledThreadPoolExecutor newExecutor() { return new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "playlist-load-coordinator");
        thread.setDaemon(true);
        return thread;
    }); }
    private int pending;
    private boolean closed;

    public enum Status { ACCEPTED, DUPLICATE, FULL, EMPTY }
    public record TrackResult(int loaded, int queued, boolean failed, boolean skipped) { }
    public record Snapshot(String batchId, String requestId, long guildId, Long userId, String source,
                           String playlistId, int totalTracks, int submitted, int loaded, int queued,
                           int failed, int skipped, int cancelled, int pending, long durationMs) { }
    public record Submission(Status status, Snapshot initial, CompletionStage<Snapshot> completion) { }
    public record EntryContext(String batchId, String requestId, Consumer<Runnable> commit, BooleanSupplier isCurrent,
                               java.util.function.BiConsumer<Integer, Integer> progress) { }
    private record Key(long guildId, String playlist) { }

    public PlaylistLoadCoordinator() { this(Clock.systemUTC(), newExecutor()); }
    PlaylistLoadCoordinator(Clock clock, ScheduledThreadPoolExecutor executor) {
        this.clock = clock;
        this.executor = executor;
        executor.setRemoveOnCancelPolicy(true);
    }

    public Submission submit(long guildId, Long userId, String playlist,
                             List<Function<EntryContext, CompletionStage<TrackResult>>> tracks) {
        Key key = new Key(guildId, playlist == null ? "" : playlist.trim().toLowerCase(Locale.ROOT));
        Job job;
        synchronized (this) {
            Job existing = jobs.get(key);
            if (existing != null) {
                LOG.info("[BilibiliBatch] event=DUPLICATE_SUPPRESSED guildId={} batchId={} source=PLAYLIST_LOAD playlistId={}",
                        guildId, existing.id, existing.playlistId);
                return new Submission(Status.DUPLICATE, snapshot(existing), existing.completion);
            }
            if (tracks.isEmpty()) return new Submission(Status.EMPTY, null, CompletableFuture.completedFuture(null));
            int guildPending = jobs.values().stream().filter(j -> j.key.guildId == guildId)
                    .mapToInt(j -> j.tracks.size() - j.finished).sum();
            if (closed || pending + tracks.size() > GLOBAL_LIMIT || guildPending + tracks.size() > GUILD_LIMIT)
                return new Submission(Status.FULL, null, CompletableFuture.completedFuture(null));
            job = new Job(key, userId, tracks, clock.millis());
            jobs.put(key, job);
            pending += tracks.size();
            job.timeout = executor.schedule(() -> {
                LOG.info("[BilibiliBatch] event=WAIT_TIMEOUT batchId={} guildId={} source=PLAYLIST_LOAD", job.id, guildId);
                cancel(job);
            }, 10, TimeUnit.MINUTES);
            log("START", job);
        }
        Snapshot initial = snapshot(job);
        executor.execute(() -> next(job));
        return new Submission(Status.ACCEPTED, initial, job.completion);
    }

    private void next(Job job) {
        Function<EntryContext, CompletionStage<TrackResult>> track;
        synchronized (this) {
            if (jobs.get(job.key) != job) return;
            if (job.finished == job.tracks.size()) { finish(job); return; }
            track = job.tracks.get(job.finished);
            LOG.debug("[BilibiliBatch] event=ENTRY_SUBMITTED batchId={} requestId={} guildId={} userId={} source=PLAYLIST_LOAD playlistId={} index={}",
                    job.id, job.requestId, job.key.guildId, job.userId, job.playlistId, job.finished);
        }
        try {
            track.apply(new EntryContext(job.id, job.requestId, action -> {
                synchronized (this) { if (jobs.get(job.key) == job) action.run(); }
            }, () -> jobs.get(job.key) == job, (loaded, queued) -> {
                synchronized (this) {
                    if (jobs.get(job.key) == job) {
                        job.loaded += loaded; job.entryLoaded += loaded;
                        job.queued += queued; job.entryQueued += queued;
                    }
                }
            })).whenComplete((result, error) -> entryCompleted(job, result, error));
        } catch (RuntimeException failure) { entryCompleted(job, null, failure); }
    }

    private void entryCompleted(Job job, TrackResult result, Throwable error) {
        synchronized (this) {
            if (jobs.get(job.key) != job) return;
            LOG.debug("[BilibiliBatch] event=ENTRY_COMPLETE batchId={} requestId={} guildId={} index={} result={} errorType={}",
                    job.id, job.requestId, job.key.guildId, job.finished, result,
                    error == null ? "-" : error.getClass().getSimpleName());
            job.finished++;
            pending--;
            if (error != null || result == null) job.failed++;
            else {
                job.loaded += Math.max(0, result.loaded - job.entryLoaded);
                job.queued += Math.max(0, result.queued - job.entryQueued);
                if (result.failed) job.failed++;
                if (result.skipped) job.skipped++;
            }
            job.entryLoaded = 0;
            job.entryQueued = 0;
        }
        if (!executor.isShutdown()) executor.execute(() -> next(job));
    }

    private void cancel(Job job) {
        synchronized (this) {
            if (jobs.get(job.key) != job) return;
            int unfinished = job.tracks.size() - job.finished;
            // A queue commit can precede its completion-stage callback. Retain
            // that success rather than also labelling the same entry cancelled.
            job.cancelled = Math.max(0, unfinished - (job.entryQueued > 0 ? 1 : 0));
            pending -= unfinished;
            job.finished = job.tracks.size();
            finish(job);
        }
    }

    public void cancelGuild(long guildId) {
        List<Job> removed;
        synchronized (this) { removed = jobs.values().stream().filter(j -> j.key.guildId == guildId).toList(); }
        removed.forEach(this::cancel);
    }

    /** Nonblocking observation, including gaps between consecutive entries. */
    public boolean hasPending(long guildId) {
        return jobs.keySet().stream().anyMatch(key -> key.guildId == guildId);
    }

    private void finish(Job job) {
        jobs.remove(job.key, job);
        job.timeout.cancel(false);
        log("COMPLETE", job);
        // User callbacks run off the coordinator lock and cannot prevent cleanup.
        Snapshot result = snapshot(job);
        executor.execute(() -> job.completion.complete(result));
    }

    private synchronized Snapshot snapshot(Job job) {
        return new Snapshot(job.id, job.requestId, job.key.guildId, job.userId, "PLAYLIST_LOAD", job.playlistId,
                job.tracks.size(), job.submitted, job.loaded, job.queued, job.failed, job.skipped,
                job.cancelled, job.tracks.size() - job.finished, Math.max(0L, clock.millis() - job.started));
    }

    private void log(String event, Job job) {
        LOG.info("[BilibiliBatch] event={} state={}", event, snapshot(job));
    }

    @Override public void close() {
        List<Job> removed;
        synchronized (this) { closed = true; removed = new ArrayList<>(jobs.values()); }
        removed.forEach(this::cancel);
        executor.shutdown();
    }

    private static final class Job {
        final Key key;
        final String id = UUID.randomUUID().toString();
        final String requestId = UUID.randomUUID().toString();
        // Do not log user-controlled playlist names/URLs; a stable digest identifies duplicates.
        final String playlistId;
        final Long userId;
        final long started;
        final List<Function<EntryContext, CompletionStage<TrackResult>>> tracks;
        final CompletableFuture<Snapshot> completion = new CompletableFuture<>();
        java.util.concurrent.ScheduledFuture<?> timeout;
        int submitted;
        int finished;
        int loaded;
        int queued;
        int entryLoaded;
        int entryQueued;
        int failed;
        int skipped;
        int cancelled;
        Job(Key key, Long userId, List<Function<EntryContext, CompletionStage<TrackResult>>> tracks, long started) {
            this.key = key; this.userId = userId; this.tracks = List.copyOf(tracks);
            this.started = started;
            this.submitted = tracks.size();
            this.playlistId = UUID.nameUUIDFromBytes(key.playlist.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        }
    }
}
