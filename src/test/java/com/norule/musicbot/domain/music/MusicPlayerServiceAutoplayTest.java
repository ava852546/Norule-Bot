package com.norule.musicbot.domain.music;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.gateway.bilibili.BilibiliAudioSourceAdapter;
import com.norule.musicbot.gateway.youtube.YouTubePlaybackRuntimeFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicPlayerServiceAutoplayTest {
    private static final long GUILD_ID = 123L;
    @TempDir
    Path tempDir;
    private MusicPlayerService service;
    private AudioPlayerManager realManager;
    private YoutubeAudioSourceManager source;
    private Guild guild;
    private GuildMusicManager manager;
    private AudioTrack current;
    private boolean connected = true;
    private boolean autoplay = true;
    private final List<String> searches = new ArrayList<>();
    private final List<AudioLoadResultHandler> loads = new ArrayList<>();
    private final java.util.concurrent.BlockingQueue<AudioLoadResultHandler> submittedLoads = new java.util.concurrent.LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        MusicConfig config = MusicConfig.defaultValues();
        CipherPolicy policy = new CipherPolicy(false);
        YouTubePlaybackTrackFactory tracks = YouTubePlaybackTrackFactory.youtubeSource();
        service = new MusicPlayerService(tempDir, ignored -> 100, ignored -> 365, ignored -> 100,
                config, tempDir.resolve("music.db"), SpotifyPlaylistInspector.noOp(), tracks,
                new BilibiliAudioSourceAdapter(config.getBilibili()), policy,
                auth -> YouTubePlaybackRuntimeFactory.createSource(config.getCipher(), policy, auth, tracks));
        service.setAutoplayEnabledChecker(ignored -> autoplay);
        var managerField = MusicPlayerService.class.getDeclaredField("playerManager");
        managerField.setAccessible(true);
        realManager = (AudioPlayerManager) managerField.get(service);
        source = (YoutubeAudioSourceManager) realManager.getSourceManagers().stream()
                .filter(value -> "youtube".equals(value.getSourceName())).findFirst().orElseThrow();
        AudioPlayer player = (AudioPlayer) Proxy.newProxyInstance(AudioPlayer.class.getClassLoader(),
                new Class<?>[] {AudioPlayer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "startTrack" -> {
                        if ((boolean) args[1] && current != null) yield false;
                        current = (AudioTrack) args[0];
                        yield true;
                    }
                    case "getPlayingTrack" -> current;
                    case "stopTrack" -> { current = null; yield null; }
                    case "isPaused" -> false;
                    case "setVolume", "setFilterFactory", "addListener", "setPaused" -> null;
                    default -> throw new AssertionError("Unexpected player call: " + method.getName());
                });
        AudioPlayerManager capturingManager = (AudioPlayerManager) Proxy.newProxyInstance(
                AudioPlayerManager.class.getClassLoader(), new Class<?>[] {AudioPlayerManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("createPlayer")) return player;
                    if (method.getName().equals("loadItemOrdered")) {
                        searches.add((String) args[1]);
                        loads.add((AudioLoadResultHandler) args[2]);
                        submittedLoads.add((AudioLoadResultHandler) args[2]);
                        return CompletableFuture.completedFuture(null);
                    }
                    if (method.getName().equals("loadItem")) {
                        searches.add((String) args[0]);
                        loads.add((AudioLoadResultHandler) args[1]);
                        submittedLoads.add((AudioLoadResultHandler) args[1]);
                        return CompletableFuture.completedFuture(null);
                    }
                    return method.invoke(realManager, args);
                });
        managerField.set(service, capturingManager);
        AudioChannelUnion voice = (AudioChannelUnion) Proxy.newProxyInstance(AudioChannelUnion.class.getClassLoader(),
                new Class<?>[] {AudioChannelUnion.class}, (proxy, method, args) -> null);
        AudioManager audio = (AudioManager) Proxy.newProxyInstance(AudioManager.class.getClassLoader(),
                new Class<?>[] {AudioManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getConnectedChannel" -> connected ? voice : null;
                    case "closeAudioConnection" -> { connected = false; yield null; }
                    case "setSendingHandler" -> null;
                    default -> throw new AssertionError("Unexpected audio call: " + method.getName());
                });
        guild = (Guild) Proxy.newProxyInstance(Guild.class.getClassLoader(), new Class<?>[] {Guild.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getIdLong" -> GUILD_ID;
                    case "getAudioManager" -> audio;
                    default -> throw new AssertionError("Unexpected guild call: " + method.getName());
                });
        manager = service.getGuildMusicManager(guild);
    }

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdownLoads();
        if (realManager != null) realManager.shutdown();
    }

    @Test
    void bilibiliPlaylistActuallyQueuesTwentyFiveTracksInOrderWhileCurrentTrackContinues() throws Exception {
        AudioTrack seed = startSeed();
        saveBilibiliPlaylist(25);
        var idleNotification = new CompletableFuture<Void>();
        service.setGuildStateListener(123, () -> {
            if (!service.isPlaybackTransitioning(guild)) idleNotification.complete(null);
        });
        var submission = service.loadPlaylistAsync(guild, "Batch", 1L, "fixture", null);
        assertEquals(com.norule.musicbot.service.music.PlaylistLoadCoordinator.Status.ACCEPTED, submission.status());
        assertEquals(com.norule.musicbot.service.music.PlaylistLoadCoordinator.Status.DUPLICATE,
                service.loadPlaylistAsync(guild, "batch", 1L, "fixture", null).status());
        for (int i = 0; i < 25; i++) {
            AudioLoadResultHandler callback = submittedLoads.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.assertNotNull(callback);
            assertTrue(submittedLoads.isEmpty(), "Only one entry may be outstanding per batch");
            callback.trackLoaded(track(String.format("song%07d", i), "Song " + i));
        }
        var result = submission.completion().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        idleNotification.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(25, result.loaded()); assertEquals(25, result.queued()); assertEquals(0, result.failed());
        assertSame(seed, current);
        assertEquals(java.util.stream.IntStream.range(0, 25).mapToObj(i -> "Song " + i).toList(),
                manager.getScheduler().snapshotQueue().stream().map(t -> t.getInfo().title).toList());
        assertTrue(!service.isPlaybackTransitioning(guild));
    }

    @Test
    void batchPendingSuppressesAutoplayAndStopsFenceLateBilibiliCallbacks() throws Exception {
        AudioTrack seed = startSeed();
        saveBilibiliPlaylist(25);
        var submission = service.loadPlaylistAsync(guild, "Batch", 1L, "fixture", null);
        AudioLoadResultHandler callback = submittedLoads.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertNotNull(callback);
        finish(seed);
        assertTrue(submittedLoads.isEmpty(), "Pending playlist must not launch a recommendation");
        service.stop(guild);
        callback.trackLoaded(track("late_______", "Late song"));
        var result = submission.completion().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(25, result.cancelled());
        assertEquals(0, result.queued());
        org.junit.jupiter.api.Assertions.assertNull(current);
        assertTrue(manager.getScheduler().snapshotQueue().isEmpty());
        assertTrue(!service.isPlaybackTransitioning(guild));
    }

    @Test
    void batchCountersSeparateMetadataLoadedFromFailedPrecheckOrNoMatch() throws Exception {
        saveBilibiliPlaylist(3);
        var submission = service.loadPlaylistAsync(guild, "Batch", 1L, "fixture", null);
        submittedLoads.poll(5, java.util.concurrent.TimeUnit.SECONDS).trackLoaded(track("good_______", "LOAD_FAILED:title is still a song"));
        submittedLoads.poll(5, java.util.concurrent.TimeUnit.SECONDS).noMatches();
        submittedLoads.poll(5, java.util.concurrent.TimeUnit.SECONDS).loadFailed(
                new FriendlyException("unavailable", FriendlyException.Severity.COMMON,
                        new com.norule.musicbot.domain.music.bilibili.BilibiliRequestException(
                                com.norule.musicbot.domain.music.bilibili.BilibiliFailureCategory.BILIBILI_ACCESS_DENIED,
                                com.norule.musicbot.domain.music.bilibili.BilibiliFailureStage.METADATA, 403, "unavailable")));
        var result = submission.completion().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, result.loaded()); assertEquals(1, result.queued()); assertEquals(2, result.failed());
    }

    private void saveBilibiliPlaylist(int count) throws Exception {
        var field = MusicPlayerService.class.getDeclaredField("musicDataService");
        field.setAccessible(true);
        var data = (MusicDataService) field.get(service);
        var entries = java.util.stream.IntStream.range(0, count).mapToObj(i -> new MusicDataService.PlaybackEntry(
                0, "Song " + i, "fixture", "bilibili", "https://www.bilibili.com/video/BV1Na4Q64Eos/?p=" + (i + 1),
                "", 180_000, 1L, "fixture")).toList();
        assertEquals(MusicDataService.PlaylistMutationStatus.SUCCESS, data.savePlaylist(123, "Batch", entries, 1L, "fixture").status());
    }

    @Test
    void failedBatchResumesDeferredRecommendationAfterAllEntriesFinish() throws Exception {
        AudioTrack seed = startSeed();
        saveBilibiliPlaylist(2);
        var submission = service.loadPlaylistAsync(guild, "Batch", 1L, "fixture", null);
        AudioLoadResultHandler first = submittedLoads.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertNotNull(first);
        finish(seed);
        first.noMatches();
        submittedLoads.poll(5, java.util.concurrent.TimeUnit.SECONDS).noMatches();
        var result = submission.completion().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(2, result.failed());
        AudioLoadResultHandler recommendation = submittedLoads.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertNotNull(recommendation);
        assertTrue(searches.getLast().startsWith("ytsearch:"));
        recommendation.trackLoaded(track("new________", "New recommendation"));
        assertEquals("New recommendation", current.getInfo().title);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finishedLastTrackStartsADifferentRecommendation(boolean withHistory) {
        AudioTrack seed = startSeed();
        if (withHistory) manager.getScheduler().onTrackStart(manager.getPlayer(), seed);
        finish(seed);
        AudioTrack recommendation = track("next_______", "Another song");
        loads.getFirst().playlistLoaded(playlist(seed.makeClone(), recommendation));

        assertSame(recommendation, current);
        assertEquals("autoplay", current.getUserData(TrackLoadContext.class).sourceName());
        assertNull(service.getAutoplayNotice(GUILD_ID));
    }

    @Test
    void skippingLastTrackRequestsRecommendationOnceEvenWithLateEndEvent() {
        AudioTrack seed = startSeed();
        service.skip(guild);
        manager.getScheduler().onTrackEnd(manager.getPlayer(), seed, AudioTrackEndReason.FINISHED);

        assertEquals(1, loads.size());
        AudioTrack recommendation = track("next_______", "Another song");
        loads.getFirst().trackLoaded(recommendation);
        assertSame(recommendation, current);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-track", "same-playlist", "empty-playlist", "no-matches", "load-failed"})
    void unusableSearchFallsBackToArtistOnce(String result) {
        AudioTrack seed = startSeed();
        finish(seed);
        AudioLoadResultHandler load = loads.getFirst();
        switch (result) {
            case "same-track" -> load.trackLoaded(seed.makeClone());
            case "same-playlist" -> load.playlistLoaded(playlist(seed.makeClone()));
            case "empty-playlist" -> load.playlistLoaded(playlist());
            case "no-matches" -> load.noMatches();
            case "load-failed" -> load.loadFailed(new FriendlyException("fixture", FriendlyException.Severity.COMMON, null));
            default -> throw new AssertionError(result);
        }

        assertEquals(List.of("ytsearch:Fixture Artist Seed song", "ytsearch:Fixture Artist"), searches);
        loads.getLast().noMatches();
        assertEquals(2, loads.size(), "Fallback must be bounded");
        assertEquals("NO_MATCH", service.getAutoplayNotice(GUILD_ID));
    }

    @Test
    void artistFallbackCanStartAnotherSong() {
        AudioTrack seed = startSeed();
        finish(seed);
        loads.getFirst().playlistLoaded(playlist(seed.makeClone()));
        assertEquals(2, loads.size());
        AudioTrack recommendation = track("next_______", "Another song");
        loads.getLast().playlistLoaded(playlist(seed.makeClone(), recommendation));
        assertSame(recommendation, current);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stop", "leave", "disconnect", "disable", "manual-track", "new-playback-finished", "skip-pending"})
    void staleRecommendationCannotStartOrJoinQueue(String action) {
        AudioTrack seed = startSeed();
        finish(seed);
        AudioLoadResultHandler pending = loads.getFirst();
        switch (action) {
            case "stop" -> service.stop(guild);
            case "leave" -> service.leaveChannel(guild);
            case "disconnect" -> connected = false;
            case "disable" -> autoplay = false;
            case "manual-track" -> manager.getScheduler().queue(track("manual_____", "Manual song"));
            case "new-playback-finished" -> {
                AudioTrack manual = track("manual_____", "Manual song");
                manager.getScheduler().queue(manual);
                finish(manual);
            }
            case "skip-pending" -> service.skip(guild);
            default -> throw new AssertionError(action);
        }
        AudioTrack expected = current;
        int expectedLoads = loads.size();
        pending.playlistLoaded(playlist(track("next_______", "Another song")));
        pending.trackLoaded(track("next_______", "Another song"));
        pending.noMatches();
        pending.loadFailed(new FriendlyException("stale", FriendlyException.Severity.COMMON, null));

        assertSame(expected, current);
        assertTrue(manager.getScheduler().snapshotQueue().isEmpty());
        assertEquals(expectedLoads, loads.size());
        assertNull(service.getAutoplayNotice(GUILD_ID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"finish", "skip"})
    void existingQueueTakesPriority(String action) {
        AudioTrack seed = startSeed();
        AudioTrack queued = track("queued_____", "Queued song");
        manager.getScheduler().queue(queued);
        if (action.equals("finish")) finish(seed);
        else service.skip(guild);
        assertSame(queued, current);
        assertTrue(loads.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "disconnected", "stop", "single-repeat", "queue-repeat", "failed-recovery"})
    void doesNotRequestAutoplayOutsideNormalQueueExhaustion(String action) {
        AudioTrack seed = startSeed();
        switch (action) {
            case "disabled" -> { autoplay = false; finish(seed); }
            case "disconnected" -> { connected = false; finish(seed); }
            case "stop" -> {
                service.stop(guild);
                manager.getScheduler().onTrackEnd(manager.getPlayer(), seed, AudioTrackEndReason.STOPPED);
            }
            case "single-repeat" -> { manager.getScheduler().setRepeatMode("SINGLE"); finish(seed); }
            case "queue-repeat" -> { manager.getScheduler().setRepeatMode("ALL"); finish(seed); }
            case "failed-recovery" -> manager.getScheduler().skipIfCurrent(seed, manager.getScheduler().getPlaybackGeneration());
            default -> throw new AssertionError(action);
        }
        assertTrue(loads.isEmpty());
    }

    @Test
    void idleSubscriberDoesNotReplacePanelSubscriberAndCanUnsubscribe() {
        var panel = new ArrayList<MusicStateChange>();
        var idle = new ArrayList<MusicStateChange>();
        service.setGuildStateChangeListener(GUILD_ID, panel::add);
        Runnable unsubscribe = service.addStateChangeListener((id, change) -> idle.add(change));
        service.togglePause(guild);
        assertEquals(List.of(MusicStateChange.PAUSE), panel);
        assertEquals(panel, idle);
        unsubscribe.run();
        service.togglePause(guild);
        assertEquals(2, panel.size());
        assertEquals(1, idle.size());
    }

    @Test
    void pendingOrdinaryLoadsProtectIdleUntilEveryCallbackCompletes() {
        service.queueTrackByIdentifier(guild, "ytsearch:first", "youtube", ignored -> {});
        service.queueTrackByIdentifier(guild, "ytsearch:second", "youtube", ignored -> {});
        assertTrue(service.isPlaybackTransitioning(guild));
        loads.get(0).noMatches();
        assertTrue(service.isPlaybackTransitioning(guild));
        loads.get(1).loadFailed(new FriendlyException("fixture", FriendlyException.Severity.COMMON, null));
        assertTrue(!service.isPlaybackTransitioning(guild));
    }

    @Test
    void stopInvalidatesPendingLoadsSoLateResultsCannotRestartPlayback() {
        service.queueTrackByIdentifier(guild, "ytsearch:first", "youtube", ignored -> {});
        assertTrue(service.isPlaybackTransitioning(guild));
        service.stop(guild);
        assertTrue(!service.isPlaybackTransitioning(guild));
        loads.getFirst().trackLoaded(track("late_______", "Late result"));
        assertNull(current);
        assertTrue(service.getQueueSnapshot(guild).isEmpty());
    }

    @Test
    void autoplayFallbackRemainsProtectedUntilItFinishes() {
        finish(startSeed());
        assertTrue(service.isPlaybackTransitioning(guild));
        loads.getFirst().noMatches();
        assertTrue(service.isPlaybackTransitioning(guild));
        loads.getLast().noMatches();
        assertTrue(!service.isPlaybackTransitioning(guild));
    }

    @Test
    void permanentFailureIsSummarizedOnceWithoutRecoveryOrDuplicateEndNotifications() {
        autoplay = false;
        var seed = startSeed();
        var context = new TrackLoadContext(seed.getIdentifier(), seed.getIdentifier(), "youtube", 1L, "fixture", 0);
        seed.setUserData(context);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MusicPlayerService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var failure = new FriendlyException("restricted", FriendlyException.Severity.COMMON,
                    new YouTubePlaybackException(YoutubeFailureCategory.VIDEO_PRIVATE, "private"));
            manager.getScheduler().onTrackException(manager.getPlayer(), seed, failure);
            manager.getScheduler().onTrackException(manager.getPlayer(), seed, failure);
            var summaries = appender.list.stream().map(event -> event.getFormattedMessage())
                    .filter(message -> message.startsWith("event=YOUTUBE_PLAYBACK_FAILED")).toList();
            assertEquals(1, summaries.size());
            assertTrue(summaries.getFirst().contains("correlationId=" + context.correlationId()));
            assertTrue(summaries.getFirst().contains("failureCategory=VIDEO_PRIVATE"));
            assertTrue(appender.list.stream().filter(event -> event.getFormattedMessage()
                    .startsWith("event=YOUTUBE_PLAYBACK_FAILED"))
                    .allMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO));
            assertTrue(loads.isEmpty());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void recoverableAttemptIsNotFinalFailureUntilBudgetIsExhausted() throws Exception {
        autoplay = false;
        var recoveryField = MusicPlayerService.class.getDeclaredField("trackRecoveryService");
        recoveryField.setAccessible(true);
        ((TrackRecoveryService) recoveryField.get(service)).updateConfig(true, 1, 0);
        var seed = startSeed();
        var context = new TrackLoadContext(seed.getIdentifier(), seed.getIdentifier(), "youtube", 1L, "fixture", 0);
        seed.setUserData(context);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MusicPlayerService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            var failure = new FriendlyException("timeout", FriendlyException.Severity.SUSPICIOUS,
                    new YouTubePlaybackException(YoutubeFailureCategory.COMPANION_TIMEOUT, "timeout"));
            manager.getScheduler().onTrackException(manager.getPlayer(), seed, failure);
            assertEquals(0, appender.list.stream().filter(event -> event.getFormattedMessage()
                    .startsWith("event=YOUTUBE_PLAYBACK_FAILED")).count());
            assertEquals(1, loads.size());
            var replacement = track("next_______", "Replacement");
            loads.getFirst().trackLoaded(replacement);
            manager.getScheduler().onTrackException(manager.getPlayer(), replacement, failure);
            var summaries = appender.list.stream().map(event -> event.getFormattedMessage())
                    .filter(message -> message.startsWith("event=YOUTUBE_PLAYBACK_FAILED")).toList();
            assertEquals(1, summaries.size());
            assertTrue(summaries.getFirst().contains("retryCount=1"));
            assertTrue(summaries.getFirst().contains("correlationId=" + context.correlationId()));
            assertEquals(1, loads.size());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @ParameterizedTest
    @EnumSource(value = YoutubeFailureCategory.class, names = {
            "VIDEO_AGE_RESTRICTED", "VIDEO_PRIVATE", "VIDEO_UNAVAILABLE", "REGION_RESTRICTED"})
    void permanentCandidatesAreExcludedAcrossRequestsAndExpire(YoutubeFailureCategory category) {
        AudioTrack failed = track("blocked____", "Blocked song");
        manager.getScheduler().queue(failed);
        failTrack(failed, category);
        assertTrue(loads.isEmpty(), "Permanent failures must not recover or start another autoplay loop");

        finish(startSeed());
        loads.getLast().playlistLoaded(playlist(failed.makeClone()));
        assertEquals(2, loads.size(), "Excluded playlist must use the bounded artist fallback");
        loads.getLast().trackLoaded(failed.makeClone());
        assertNull(current, "Single-track fallback must also exclude the failed video");
        assertEquals("NO_MATCH", service.getAutoplayNotice(GUILD_ID));

        service.cleanupTransientCaches(System.currentTimeMillis() + 31 * 60_000L);
        finish(startSeed());
        AudioTrack availableAgain = failed.makeClone();
        loads.getLast().playlistLoaded(playlist(availableAgain));
        assertSame(availableAgain, current);
    }

    @Test
    void permanentCandidateDoesNotBlockManualRequestsOrTheirFailureReason() {
        AudioTrack failed = track("blocked____", "Blocked song");
        manager.getScheduler().queue(failed);
        failTrack(failed, YoutubeFailureCategory.VIDEO_AGE_RESTRICTED);
        var messages = new ArrayList<String>();
        service.queueTrackByIdentifier(guild, failed.getInfo().uri, "youtube", messages::add);
        assertEquals(1, loads.size(), "An explicit request must still reach the backend");
        loads.getLast().loadFailed(new FriendlyException("restricted", FriendlyException.Severity.COMMON,
                new YouTubePlaybackException(YoutubeFailureCategory.VIDEO_AGE_RESTRICTED, "restricted")));
        assertEquals(List.of("LOAD_FAILED:YOUTUBE_VIDEO_AGE_RESTRICTED"), messages);
    }

    @Test
    void excludedTopCandidateDoesNotHidePlayableAlternative() {
        AudioTrack failed = track("blocked____", "Seed song alternative");
        manager.getScheduler().queue(failed);
        failTrack(failed, YoutubeFailureCategory.VIDEO_PRIVATE);
        finish(startSeed());
        AudioTrack playable = track("next_______", "Another song");
        loads.getLast().playlistLoaded(playlist(failed.makeClone(), playable));
        assertSame(playable, current);
    }

    @Test
    void transientFailureDoesNotExcludeCandidate() throws Exception {
        var field = MusicPlayerService.class.getDeclaredField("trackRecoveryService");
        field.setAccessible(true);
        ((TrackRecoveryService) field.get(service)).updateConfig(false, 0, 0);
        AudioTrack failed = track("blocked____", "Another song");
        manager.getScheduler().queue(failed);
        failTrack(failed, YoutubeFailureCategory.COMPANION_TIMEOUT);
        finish(startSeed());
        AudioTrack candidate = failed.makeClone();
        loads.getLast().trackLoaded(candidate);
        assertSame(candidate, current);
    }

    @Test
    void searchCandidatesExcludeKnownFailuresBeforeApplyingLimit() {
        AudioTrack failed = track("blocked____", "Blocked song");
        manager.getScheduler().queue(failed);
        failTrack(failed, YoutubeFailureCategory.REGION_RESTRICTED);
        var results = new ArrayList<List<AudioTrack>>();
        service.searchTopTracks("fixture", 1, results::add, error -> { throw new AssertionError(error); });
        AudioTrack playable = track("next_______", "Playable song");
        loads.getLast().playlistLoaded(playlist(failed.makeClone(), playable));
        assertEquals(List.of(List.of(playable)), results);
        service.searchTopTracks("fixture", 1, results::add, error -> { throw new AssertionError(error); });
        loads.getLast().trackLoaded(failed.makeClone());
        assertEquals(List.of(), results.getLast());
    }

    @ParameterizedTest
    @ValueSource(strings = {"blocked____", "https://youtu.be/blocked____", "https://www.youtube.com/watch?v=blocked____"})
    void metadataFailureAlsoExcludesLaterRecommendations(String identifier) {
        var messages = new ArrayList<String>();
        service.queueTrackByIdentifier(guild, identifier, "youtube", messages::add);
        loads.getLast().loadFailed(new FriendlyException("private", FriendlyException.Severity.COMMON,
                new YouTubePlaybackException(YoutubeFailureCategory.VIDEO_PRIVATE, "private")));
        assertEquals(List.of("LOAD_FAILED:YOUTUBE_VIDEO_PRIVATE"), messages);
        finish(startSeed());
        loads.getLast().trackLoaded(track("blocked____", "Blocked song"));
        loads.getLast().noMatches();
        assertNull(current);
        assertEquals("NO_MATCH", service.getAutoplayNotice(GUILD_ID));
    }

    private void failTrack(AudioTrack track, YoutubeFailureCategory category) {
        manager.getScheduler().onTrackException(manager.getPlayer(), track,
                new FriendlyException("fixture", FriendlyException.Severity.COMMON,
                        new YouTubePlaybackException(category, "fixture")));
    }

    private AudioTrack startSeed() {
        AudioTrack seed = track("seed_______", "Seed song");
        manager.getScheduler().queue(seed);
        return seed;
    }

    private void finish(AudioTrack track) {
        current = null;
        manager.getScheduler().onTrackEnd(manager.getPlayer(), track, AudioTrackEndReason.FINISHED);
    }

    private AudioTrack track(String id, String title) {
        return source.buildAudioTrack(new AudioTrackInfo(title, "Fixture Artist", 180_000L,
                id, false, "https://www.youtube.com/watch?v=" + id));
    }

    private BasicAudioPlaylist playlist(AudioTrack... tracks) {
        return new BasicAudioPlaylist("fixture", List.of(tracks), null, true);
    }
}
