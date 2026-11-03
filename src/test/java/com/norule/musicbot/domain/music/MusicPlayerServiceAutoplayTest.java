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
        if (realManager != null) realManager.shutdown();
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
