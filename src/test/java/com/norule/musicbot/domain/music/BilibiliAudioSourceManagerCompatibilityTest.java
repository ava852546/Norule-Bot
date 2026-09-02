package com.norule.musicbot.domain.music;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.gateway.bilibili.BilibiliAudioSourceAdapter;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import dev.lavalink.bilibili.BilibiliAudioSourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BilibiliAudioSourceManagerCompatibilityTest {
    @Test
    void initializesWithCurrentLavaplayerRuntime() {
        BilibiliAudioSourceManager sourceManager = new BilibiliAudioSourceManager();
        try {
            assertEquals("bilibili", sourceManager.getSourceName());
        } finally {
            sourceManager.shutdown();
        }
    }

    @Test
    void guardedAdapterConfiguresCurrentBilibiliHttpRuntime() {
        BilibiliAudioSourceAdapter sourceManager = new BilibiliAudioSourceAdapter(
                MusicConfig.defaultValues().getBilibili()
        );
        try {
            assertEquals("bilibili", sourceManager.getSourceName());
        } finally {
            sourceManager.shutdown();
        }
    }

    @Test
    void musicPlayerRegistersExactlyOneBilibiliSource(@TempDir Path tempDir) throws ReflectiveOperationException {
        BilibiliAudioSourceAdapter bilibiliSource = new BilibiliAudioSourceAdapter(
                MusicConfig.defaultValues().getBilibili()
        );
        MusicPlayerService service = new MusicPlayerService(
                tempDir,
                ignored -> 100,
                ignored -> 365,
                ignored -> 100,
                MusicConfig.defaultValues(),
                tempDir.resolve("music.db"),
                SpotifyPlaylistInspector.noOp(),
                YouTubePlaybackTrackFactory.youtubeSource(),
                bilibiliSource
        );
        AudioPlayerManager playerManager = playerManager(service);
        try {
            var registeredBilibiliSources = playerManager.getSourceManagers().stream()
                    .filter(source -> "bilibili".equalsIgnoreCase(source.getSourceName()))
                    .toList();
            assertEquals(1, registeredBilibiliSources.size());
            assertSame(bilibiliSource, registeredBilibiliSources.get(0));
        } finally {
            playerManager.shutdown();
        }
    }

    private AudioPlayerManager playerManager(MusicPlayerService service) throws ReflectiveOperationException {
        Field field = MusicPlayerService.class.getDeclaredField("playerManager");
        field.setAccessible(true);
        return (AudioPlayerManager) field.get(service);
    }
}
