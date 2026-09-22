package com.norule.musicbot.domain.music;

import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.gateway.bilibili.BilibiliAudioSourceAdapter;
import com.norule.musicbot.gateway.youtube.YouTubePlaybackRuntimeFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicPlayerBackendPrecheckTest {
    @Test
    void companionSkipsExternalSourceExtractionPrecheck(@TempDir Path tempDir) throws Exception {
        var legacy = BotConfig.Music.fromMap(Map.of("youtube", Map.of("strictPrecheck", Map.of(
                "enabled", true, "lavalinkBaseUrl", "http://127.0.0.1:1"))), BotConfig.Music.defaultValues());
        var config = MusicConfig.fromLegacy(legacy, legacy);
        var policy = new CipherPolicy(false);
        var tracks = new YouTubePlaybackTrackFactory() {
            public AudioTrack prepare(String id, AudioTrack track) { return track; }
            public YouTubePlaybackBackend backend() { return YouTubePlaybackBackend.COMPANION; }
        };
        var service = new MusicPlayerService(tempDir, ignored -> 100, ignored -> 365, ignored -> 100,
                config, tempDir.resolve("music.db"), SpotifyPlaylistInspector.noOp(), tracks,
                new BilibiliAudioSourceAdapter(config.getBilibili()), policy,
                auth -> YouTubePlaybackRuntimeFactory.createSource(config.getCipher(), policy, auth, tracks));
        var managerField = MusicPlayerService.class.getDeclaredField("playerManager");
        managerField.setAccessible(true);
        var manager = (AudioPlayerManager) managerField.get(service);
        try {
            var method = MusicPlayerService.class.getDeclaredMethod("precheckTrack", AudioTrack.class, String.class);
            method.setAccessible(true);
            var source = (dev.lavalink.youtube.YoutubeAudioSourceManager) manager.getSourceManagers().stream()
                    .filter(value -> "youtube".equals(value.getSourceName())).findFirst().orElseThrow();
            var track = source.buildAudioTrack(new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                    "Fixture", "Fixture", 1000, "dQw4w9WgXcQ", false, "https://youtube.com/watch?v=dQw4w9WgXcQ"));
            var result = (YouTubePlaybackPrecheckResult) method.invoke(service, track, "youtube");
            assertEquals(YouTubePlaybackPrecheckStatus.CONFIG_DISABLED, result.status());
            assertEquals("Companion validates its own stream", result.reason());
        } finally {
            manager.shutdown();
        }
    }
}
