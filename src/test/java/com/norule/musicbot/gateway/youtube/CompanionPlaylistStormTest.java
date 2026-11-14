package com.norule.musicbot.gateway.youtube;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.norule.musicbot.domain.music.TrackScheduler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanionPlaylistStormTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

    @Test void shutdownClosesSharedAdmissionEvenWithoutAnyGuildMusicManager() throws Exception {
        var calls = new AtomicInteger();
        try (var client = new CompanionPlaybackClient("http://fixture.invalid", "FixtureKey123456", 1000,
                new ObjectMapper(), request -> { calls.incrementAndGet(); throw new java.io.IOException("must not run"); })) {
            var factory = new CompanionYouTubePlaybackTrackFactory(new CompanionPlaybackResolver(client), new HttpAudioSourceManager(), client);
            var source = new BackendYoutubeAudioSourceManager(factory, new dev.lavalink.youtube.clients.Web());
            var config = com.norule.musicbot.config.domain.MusicConfig.defaultValues();
            var service = new com.norule.musicbot.domain.music.MusicPlayerService(temp, ignored -> 100, ignored -> 365, ignored -> 100,
                    config, temp.resolve("music.db"), com.norule.musicbot.domain.music.SpotifyPlaylistInspector.noOp(), factory,
                    new com.norule.musicbot.gateway.bilibili.BilibiliAudioSourceAdapter(config.getBilibili()),
                    new com.norule.musicbot.domain.music.CipherPolicy(false), auth -> source);
            try {
                service.shutdownLoads();
                assertTrue(client.resolveAsync("dQw4w9WgXcQ", 42).isCompletedExceptionally());
                assertEquals(0, calls.get());
            } finally {
                var field = com.norule.musicbot.domain.music.MusicPlayerService.class.getDeclaredField("playerManager");
                field.setAccessible(true);
                ((com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager) field.get(service)).shutdown();
            }
        }
    }

    @Test void nativePlaylistMetadataQueuesOneHundredTracksButBotFailureMakesOnlyOnePlayerRequest() throws Exception {
        var calls = new AtomicInteger();
        try (var client = new CompanionPlaybackClient("http://fixture.invalid", "FixtureKey123456", 1000,
                new ObjectMapper(), request -> {
                    calls.incrementAndGet();
                    return new CompanionPlaybackClient.HttpResponseData(200,
                            "{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\",\"reason\":\"Sign in to confirm you are not a bot\"}}");
                })) {
            var factory = new CompanionYouTubePlaybackTrackFactory(new CompanionPlaybackResolver(client), new HttpAudioSourceManager(), client);
            var source = new BackendYoutubeAudioSourceManager(factory, new dev.lavalink.youtube.clients.Web());
            var playing = new AtomicReference<AudioTrack>();
            var player = (AudioPlayer) Proxy.newProxyInstance(AudioPlayer.class.getClassLoader(), new Class<?>[]{AudioPlayer.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getPlayingTrack" -> playing.get();
                        case "startTrack" -> { playing.set((AudioTrack) args[0]); yield true; }
                        default -> null;
                    });
            var scheduler = new TrackScheduler(player);
            scheduler.setGuildId(42);
            var stopped = new CountDownLatch(1);
            var notices = new AtomicInteger();
            scheduler.setTrackExceptionListener((track, error) -> {
                notices.incrementAndGet();
                scheduler.holdSource(candidate -> true, 60_000);
                scheduler.stopIfCurrent(track, scheduler.getPlaybackGeneration());
                stopped.countDown();
            });
            for (int i = 0; i < 100; i++) {
                String id = String.format(java.util.Locale.ROOT, "%011d", i);
                scheduler.queue(source.buildAudioTrack(new AudioTrackInfo("Fixture " + i, "Artist", 1000,
                        id, false, "https://www.youtube.com/watch?v=" + id)), true);
            }
            assertTrue(stopped.await(3, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(1, notices.get());
            assertEquals(99, scheduler.previewQueue(10).total());
            assertEquals("00000000001", scheduler.previewQueue(1).tracks().getFirst().getIdentifier());
            assertNull(playing.get());
            scheduler.clear();
            source.shutdown();
        }
    }
}
