package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.YouTubePlaybackException;
import com.norule.musicbot.domain.music.YoutubeFailureCategory;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** One shared player API budget for metadata and playback. No signed URL cache. */
final class CompanionRequestScheduler implements AutoCloseable {
    static final int CAPACITY = 256;
    static final int GUILD_CAPACITY = 32;
    static final long INTERVAL_MS = 750;
    static final long WAIT_MS = 30_000;
    static final long COOLDOWN_MS = 60_000;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor timer;
    private final ExecutorService worker;
    private final List<Job<?>> waiting = new ArrayList<>();
    private final Map<Long, Breaker> breakers = new HashMap<>();
    private final Map<Long, Long> failedGuilds = new HashMap<>();
    private final Breaker global = new Breaker();
    private Job<?> active;
    private long nextStart;
    private long lastGuild = Long.MIN_VALUE;
    private boolean closed;

    CompanionRequestScheduler() { this(Clock.systemUTC(), true); }

    CompanionRequestScheduler(Clock clock, boolean automatic) {
        this.clock = clock;
        timer = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "companion-admission"));
        timer.setRemoveOnCancelPolicy(true);
        worker = Executors.newSingleThreadExecutor(r -> daemon(r, "companion-player-request"));
        if (automatic) timer.scheduleWithFixedDelay(this::tick, 0, 50, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }

    @FunctionalInterface interface Request<T> { T run() throws YouTubePlaybackException; }

    /** Lazy third-party track delegates cannot await quota on an audio thread. Fail fast instead. */
    synchronized <T> CompletableFuture<T> submitImmediate(String video, Request<T> request) {
        if (active != null || !waiting.isEmpty() || clock.millis() < nextStart)
            return reject(CompanionRequestTrace.current(0), video, true, "PLAYBACK_ADMISSION_BUSY");
        var result = submit(0L, video, true, request);
        tick();
        return result;
    }

    synchronized <T> CompletableFuture<T> submit(long guild, String video, boolean playback, Request<T> request) {
        CompanionRequestTrace trace = CompanionRequestTrace.current(guild);
        guild = trace.guildId();
        final long targetGuild = guild;
        long now = clock.millis();
        Breaker breaker = breakers.get(guild);
        if (closed || global.until > now || (breaker != null && breaker.until > now))
            return reject(trace, video, playback, "SOURCE_COOLDOWN");
        // Merge only simultaneous metadata in the same guild. Never share stream URLs.
        if (!playback) {
            List<Job<?>> jobs = new ArrayList<>(waiting);
            if (active != null) jobs.add(active);
            for (Job<?> job : jobs) {
                if (!job.playback && job.guild == guild && job.video.equals(video) && !job.result.isDone()) {
                    @SuppressWarnings("unchecked") Job<T> shared = (Job<T>) job;
                    log(trace, video, false, 0, "METADATA_MERGED");
                    return observe(shared);
                }
            }
        }
        if (waiting.size() >= CAPACITY || waiting.stream().filter(j -> j.guild == targetGuild).count() >= GUILD_CAPACITY)
            return reject(trace, video, playback, "REQUEST_QUEUE_FULL");
        Job<T> job = new Job<>(guild, video, playback, request, now, trace);
        waiting.add(job);
        job.result.whenComplete((value, failure) -> {
            if (job.result.isCancelled()) synchronized (CompanionRequestScheduler.this) { waiting.remove(job); }
        });
        return playback ? job.result : observe(job);
    }

    private <T> CompletableFuture<T> observe(Job<T> job) {
        job.observers++;
        var observer = new CompletableFuture<T>();
        job.result.whenComplete((value, failure) -> {
            if (job.result.isCancelled()) observer.cancel(false);
            else if (failure == null) observer.complete(value);
            else observer.completeExceptionally(failure);
        });
        observer.whenComplete((value, failure) -> {
            if (!observer.isCancelled()) return;
            boolean last;
            synchronized (this) { last = --job.observers == 0; }
            if (last) job.result.cancel(false);
        });
        return observer;
    }

    void tick() {
        List<Job<?>> expired = new ArrayList<>();
        Job<?> cooldownRejected = null;
        synchronized (this) {
            if (closed) return;
            long now = clock.millis();
            var iterator = waiting.iterator();
            while (iterator.hasNext()) {
                Job<?> job = iterator.next();
                if (job.result.isDone() || now - job.enqueued >= WAIT_MS) {
                    iterator.remove();
                    expired.add(job);
                }
            }
            if (active == null && now >= nextStart && !waiting.isEmpty()) {
                boolean playback = waiting.stream().anyMatch(j -> j.playback);
                Job<?> selected = waiting.stream().filter(j -> j.playback == playback && j.guild != lastGuild)
                        .findFirst().orElseGet(() -> waiting.stream().filter(j -> j.playback == playback).findFirst().orElseThrow());
                waiting.remove(selected);
                var sameGuild = waiting.stream().filter(job -> job.guild == selected.guild).toList();
                waiting.removeAll(sameGuild);
                waiting.addAll(sameGuild);
                Breaker breaker = breakers.get(selected.guild);
                if (global.until > now || (breaker != null && breaker.until > now)) cooldownRejected = selected;
                else {
                    active = selected;
                    lastGuild = selected.guild;
                    nextStart = now + INTERVAL_MS;
                    worker.execute(() -> execute(selected));
                }
            }
        }
        // Completion callbacks may acquire a guild scheduler lock. Never run them under this lock.
        expired.forEach(job -> rejectJob(job, "REQUEST_WAIT_TIMEOUT"));
        if (cooldownRejected != null) rejectJob(cooldownRejected, "SOURCE_COOLDOWN");
    }

    private <T> void execute(Job<T> job) {
        T value = null;
        Throwable failure = null;
        try { value = job.request.run(); }
        catch (YouTubePlaybackException | RuntimeException e) { failure = e; }
        synchronized (this) {
            long now = clock.millis();
            if (failure instanceof YouTubePlaybackException e && sourceFailure(e.category())) {
                // Bound dormant guild state as well as waiting work.
                breakers.entrySet().removeIf(entry -> entry.getValue().until <= now && now - entry.getValue().last > COOLDOWN_MS);
                if (breakers.size() < 4096 || breakers.containsKey(job.guild)) {
                    Breaker breaker = breakers.computeIfAbsent(job.guild, ignored -> new Breaker());
                    breaker.failures = now - breaker.last <= 30_000 ? breaker.failures + 1 : 1;
                    breaker.last = now;
                    if (e.category() == YoutubeFailureCategory.BOT_DETECTED
                            || e.category() == YoutubeFailureCategory.HTTP_RATE_LIMITED || breaker.failures >= 3)
                        breaker.until = now + COOLDOWN_MS;
                }
                failedGuilds.entrySet().removeIf(entry -> now - entry.getValue() > 30_000);
                if (failedGuilds.size() < 4096) failedGuilds.put(job.guild, now);
                if (failedGuilds.size() >= 3 || global.until > 0) global.until = now + COOLDOWN_MS;
            } else if (failure == null) {
                breakers.remove(job.guild);
                failedGuilds.remove(job.guild);
                if (global.until <= now) global.until = 0;
            }
            active = null;
        }
        log(job.trace, job.video, job.playback, 1,
                failure instanceof YouTubePlaybackException e ? e.category() : failure == null ? "OK" : "FAILED");
        if (failure == null) job.result.complete(value);
        else job.result.completeExceptionally(failure);
    }

    static boolean sourceFailure(YoutubeFailureCategory category) {
        return category == YoutubeFailureCategory.BOT_DETECTED || category == YoutubeFailureCategory.HTTP_RATE_LIMITED
                || category == YoutubeFailureCategory.COMPANION_TIMEOUT || category == YoutubeFailureCategory.COMPANION_UNAVAILABLE;
    }

    private static YouTubePlaybackException rejected(String reason) {
        return new YouTubePlaybackException(YoutubeFailureCategory.HTTP_RATE_LIMITED, reason);
    }

    private static <T> CompletableFuture<T> reject(CompanionRequestTrace trace, String video, boolean playback, String reason) {
        log(trace, video, playback, 0, reason);
        return CompletableFuture.failedFuture(rejected(reason));
    }

    private static void rejectJob(Job<?> job, String reason) {
        if (job.result.isDone()) return;
        log(job.trace, job.video, job.playback, 0, reason);
        job.result.completeExceptionally(rejected(reason));
    }

    private static void log(CompanionRequestTrace trace, String video, boolean playback, int requests, Object result) {
        LoggerFactory.getLogger(CompanionRequestScheduler.class).debug(
                "event=COMPANION_PLAYER guildId={} requestId={} batchId={} videoId={} source={} stage={} backend=COMPANION requestCount={} result={}",
                trace.guildId(), trace.requestId(), trace.batchId(), video, trace.source(),
                playback ? "STREAM_RESOLVE" : "METADATA_LOAD", requests, result);
    }

    synchronized int pending() { return waiting.size(); }

    void cancelGuild(long guild) {
        List<Job<?>> cancelled;
        synchronized (this) {
            cancelled = new ArrayList<>(waiting.stream().filter(job -> job.guild == guild).toList());
            waiting.removeAll(cancelled);
            if (active != null && active.guild == guild) cancelled.add(active);
        }
        cancelled.forEach(job -> job.result.cancel(false));
    }

    @Override public void close() {
        List<Job<?>> cancelled;
        synchronized (this) {
            closed = true;
            cancelled = new ArrayList<>(waiting);
            waiting.clear();
            if (active != null) cancelled.add(active);
            breakers.clear();
            failedGuilds.clear();
        }
        cancelled.forEach(job -> job.result.cancel(false));
        timer.shutdownNow();
        worker.shutdownNow();
    }

    private static final class Breaker { long until; long last; int failures; }
    private static final class Job<T> {
        final long guild;
        final String video;
        final boolean playback;
        final Request<T> request;
        final long enqueued;
        final CompanionRequestTrace trace;
        final CompletableFuture<T> result = new CompletableFuture<>();
        int observers;
        Job(long guild, String video, boolean playback, Request<T> request, long enqueued, CompanionRequestTrace trace) {
            this.guild = guild; this.video = video; this.playback = playback; this.request = request; this.enqueued = enqueued;
            this.trace = trace;
        }
    }
}
