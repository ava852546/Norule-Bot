package com.norule.musicbot.service.music;

import com.norule.musicbot.domain.music.bilibili.*;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class BilibiliLoadSchedulerTest {
    private static class Result implements AudioLoadResultHandler {
        int succeeded;
        int failed;
        @Override public void trackLoaded(AudioTrack track) { succeeded++; }
        @Override public void playlistLoaded(AudioPlaylist playlist) { succeeded++; }
        @Override public void noMatches() { succeeded++; }
        @Override public void loadFailed(FriendlyException failure) { failed++; }
    }

    @Test void sixtyTracksAcrossThreeGuildsRespectOneGlobalBucketAndFairness() {
        ManualLoadTimer timer = new ManualLoadTimer();
        var bucket = new BilibiliRequestRateLimiter(true, 1, 3, timer.clock);
        var result = new Result();
        List<Long> order = new ArrayList<>();
        try (var scheduler = new BilibiliLoadScheduler(bucket::nextPermitDelayMillis, timer.clock, timer)) {
            for (int guild = 1; guild <= 3; guild++) {
                long id = guild;
                for (int i = 0; i < 30 - guild * 5; i++) scheduler.submit(id, "PLAYLIST_LOAD", true, callback -> {
                    assertTrue(bucket.tryAcquire());
                    order.add(id);
                    callback.noMatches();
                }, result);
            }
            timer.runReady();
            assertEquals(3, order.size());
            for (int i = 0; i < 60; i++) timer.advance(1000);
            assertEquals(60, result.succeeded);
            assertEquals(0, result.failed);
            assertTrue(order.indexOf(3L) < 7, order.toString());
            assertEquals(25, order.stream().filter(id -> id == 1).count());
            assertEquals(20, order.stream().filter(id -> id == 2).count());
            assertEquals(15, order.stream().filter(id -> id == 3).count());
        }
    }

    @Test void oneSongWithTwoHttpRequestsWaitsOutsideWorkerAndKeepsSuccessfulNav() {
        ManualLoadTimer timer = new ManualLoadTimer();
        var bucket = new BilibiliRequestRateLimiter(true, 1, 1, timer.clock);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        Result result = new Result();
        try (var scheduler = new BilibiliLoadScheduler(bucket::nextPermitDelayMillis, timer.clock, timer)) {
            scheduler.submit(1, "USER_PLAY", false, callback -> {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) { assertTrue(bucket.tryAcquire()); requests.incrementAndGet(); }
                if (!bucket.tryAcquire()) callback.loadFailed(failure(0));
                else { requests.incrementAndGet(); callback.noMatches(); }
            }, result);
            assertEquals(0, result.failed);
            timer.advance(999);
            assertEquals(1, attempts.get());
            timer.advance(1);
            assertEquals(2, attempts.get());
            assertEquals(2, requests.get());
            assertEquals(1, result.succeeded);
        }
    }

    @Test void independentRedirectBucketDefersBeforeEnteringALoader() {
        ManualLoadTimer timer = new ManualLoadTimer();
        var redirects = new BilibiliRequestRateLimiter(true, 2, 1, timer.clock);
        assertTrue(redirects.tryAcquire());
        AtomicInteger attempts = new AtomicInteger();
        Result result = new Result();
        try (var scheduler = new BilibiliLoadScheduler(() -> 0L, timer.clock, timer)) {
            scheduler.submitTraced(1, "USER_PLAY", false, redirects::nextPermitDelayMillis, (requestId, callback) -> {
                attempts.incrementAndGet(); assertTrue(redirects.tryAcquire()); callback.noMatches();
            }, result);
            timer.advance(499); assertEquals(0, attempts.get());
            timer.advance(1); assertEquals(1, attempts.get());
            assertEquals(0, result.failed);
        }
    }

    @Test void remoteRetriesHonorDelayAndAreBoundedWhile403And412FailImmediately() {
        for (int status : new int[]{429, 503, 403, 412, -1}) {
            ManualLoadTimer timer = new ManualLoadTimer();
            AtomicInteger attempts = new AtomicInteger();
            Result result = new Result();
            try (var scheduler = new BilibiliLoadScheduler(() -> 0, timer.clock, timer)) {
                scheduler.submit(1, "USER_PLAY", false, callback -> {
                    attempts.incrementAndGet(); callback.loadFailed(failure(status));
                }, result);
                timer.runReady();
                for (int i = 0; i < 8; i++) timer.advance(1000);
                assertEquals(status == 429 || status == 503 || status == -1 ? 4 : 1, attempts.get());
                assertEquals(1, result.failed);
            }
        }
    }

    @Test void queueIsBoundedAndCancelledInFlightDoesNotDeliverOrReleaseEarly() {
        ManualLoadTimer timer = new ManualLoadTimer();
        List<AudioLoadResultHandler> inFlight = new ArrayList<>();
        Result result = new Result();
        try (var scheduler = new BilibiliLoadScheduler(() -> 0, timer.clock, timer)) {
            for (int i = 0; i < 101; i++) scheduler.submit(1, "PLAYLIST_LOAD", true, inFlight::add, result);
            assertEquals(1, inFlight.size());
            assertEquals(1, result.failed);
            scheduler.cancelGuild(1);
            scheduler.submit(2, "USER_PLAY", false, inFlight::add, result);
            assertEquals(1, inFlight.size());
            inFlight.get(0).noMatches();
            timer.runReady();
            assertEquals(2, inFlight.size());
            assertEquals(0, result.succeeded);
            inFlight.get(0).trackLoaded(null);
            inFlight.get(1).noMatches();
            assertEquals(1, result.succeeded);
        }
    }

    @Test void foregroundOvertakesBatchButAgedBackgroundCannotStarve() {
        ManualLoadTimer timer = new ManualLoadTimer();
        List<AudioLoadResultHandler> callbacks = new ArrayList<>();
        List<String> order = new ArrayList<>();
        Result result = new Result();
        try (var scheduler = new BilibiliLoadScheduler(() -> 0, timer.clock, timer)) {
            Consumer<String> add = source -> scheduler.submit(1, source, !source.equals("USER_PLAY"), callback -> {
                order.add(source); callbacks.add(callback);
            }, result);
            add.accept("PLAYLIST_LOAD"); add.accept("AUTOPLAY"); add.accept("USER_PLAY");
            callbacks.get(0).noMatches(); timer.runReady();
            assertEquals(List.of("PLAYLIST_LOAD", "USER_PLAY"), order);
            timer.advance(5000); add.accept("USER_PLAY");
            callbacks.get(1).noMatches(); timer.runReady();
            assertEquals("AUTOPLAY", order.get(2));
        }
    }

    @Test void deadlineAndMissingCallbackCannotKeepQueueForever() {
        ManualLoadTimer timer = new ManualLoadTimer();
        Result result = new Result();
        try (var scheduler = new BilibiliLoadScheduler(() -> 0, timer.clock, timer)) {
            scheduler.submit(1, "USER_PLAY", false, ignored -> {}, result);
            timer.advance(90_000);
            assertEquals(1, result.failed);
        }
        timer = new ManualLoadTimer();
        try (var scheduler = new BilibiliLoadScheduler(() -> 900_000, timer.clock, timer)) {
            scheduler.submit(1, "USER_PLAY", false, ignored -> fail("expired job started"), result);
            timer.advance(600_000);
            assertEquals(2, result.failed);
        }
    }

    @Test void playbackRecoveryUsesSharedBucketWhileMetadataYieldsAndRetryAfterIsRespected() {
        ManualLoadTimer timer = new ManualLoadTimer();
        var bucket = new BilibiliRequestRateLimiter(true, 1, 1, timer.clock);
        Result result = new Result();
        AtomicInteger starts = new AtomicInteger();
        try (var scheduler = new BilibiliLoadScheduler(() -> 1000L, bucket::nextPermitDelayMillis, timer.clock, timer)) {
            scheduler.submit(1, "PLAYLIST_LOAD", true, ignored -> fail("metadata must yield"), result);
            scheduler.submit(2, "TRACK_RECOVERY", false, callback -> {
                assertTrue(bucket.tryAcquire()); callback.noMatches();
            }, result);
            assertEquals(1, result.succeeded);
            scheduler.cancelGuild(1);
        }
        timer = new ManualLoadTimer();
        ManualLoadTimer currentTimer = timer;
        try (var scheduler = new BilibiliLoadScheduler(() -> Math.max(0, 60_000 - currentTimer.now), timer.clock, timer)) {
            scheduler.submit(1, "USER_PLAY", false, callback -> { starts.incrementAndGet(); callback.noMatches(); }, result);
            timer.advance(59_999); assertEquals(0, starts.get());
            timer.advance(1); assertEquals(1, starts.get());
        }
    }

    private static FriendlyException failure(int status) {
        if (status == -1) return new FriendlyException("test", FriendlyException.Severity.COMMON,
                new java.net.SocketTimeoutException("timed out"));
        var category = status == 0 || status == 429 ? BilibiliFailureCategory.BILIBILI_RATE_LIMITED
                : status == 412 ? BilibiliFailureCategory.BILIBILI_RISK_CONTROL
                : BilibiliFailureCategory.BILIBILI_METADATA_FAILED;
        return new FriendlyException("test", FriendlyException.Severity.COMMON,
                new BilibiliRequestException(category, BilibiliFailureStage.METADATA, status, "test"));
    }
}
