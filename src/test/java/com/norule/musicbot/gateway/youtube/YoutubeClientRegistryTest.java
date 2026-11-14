package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.CipherPolicy;
import com.norule.musicbot.domain.music.YouTubePlaybackTrackFactory;
import com.norule.musicbot.domain.music.YoutubeClientType;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.WebWithThumbnail;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class YoutubeClientRegistryTest {
    @Test
    void defaultsPreserveAllImplementationsAndOauthTvPosition() {
        for (var mode : MusicConfig.Youtube.AuthMode.values()) {
            var clients = YoutubeClientRegistry.create(MusicConfig.defaultValues().getYoutube(), mode);
            List<String> expected = new ArrayList<>(List.of("MusicWithThumbnail", "WebWithThumbnail", "MWebWithThumbnail",
                    "WebEmbeddedWithThumbnail", "TvHtml5SimplyWithThumbnail", "AndroidVrWithThumbnail",
                    "AndroidMusicWithThumbnail", "IosWithThumbnail"));
            if (mode == MusicConfig.Youtube.AuthMode.OAUTH) expected.add(1, "Tv");
            assertEquals(expected, Arrays.stream(clients).map(c -> c.getClass().getSimpleName()).toList());
        }
    }

    @Test
    void tvRequiresEffectiveOauthBeforeAnyClientIsCreated() {
        for (var mode : List.of(MusicConfig.Youtube.AuthMode.NONE, MusicConfig.Youtube.AuthMode.POT)) {
            assertThrows(IllegalArgumentException.class, () -> YoutubeClientRegistry.create(config("WEB", "TV"), mode,
                    type -> { throw new AssertionError("Must validate before construction"); }));
        }
        assertInstanceOf(dev.lavalink.youtube.clients.Tv.class,
                YoutubeClientRegistry.create(config("TV"), MusicConfig.Youtube.AuthMode.OAUTH)[0]);
    }

    @Test
    void configuredSubsetControlsProductionSourceAndConcreteLookup() {
        var source = YouTubePlaybackRuntimeFactory.createSource(config("MWEB", "WEB"),
                MusicConfig.defaultValues().getCipher(), new CipherPolicy(true), MusicConfig.Youtube.AuthMode.NONE,
                YouTubePlaybackTrackFactory.youtubeSource());
        try {
            assertEquals(List.of("MWEB", "WEB"), Arrays.stream(source.getClients()).map(c -> c.getIdentifier()).toList());
            assertNotNull(source.getClient(WebWithThumbnail.class));
            assertNull(source.getClient(dev.lavalink.youtube.clients.IosWithThumbnail.class));
        } finally { source.shutdown(); }
    }

    @Test
    void playbackFormatFailuresTryOnlyTheConfiguredClientsOnceInOrder() {
        var attempted = new ArrayList<String>();
        var clients = YoutubeClientRegistry.create(config("WEB", "MWEB"), MusicConfig.Youtube.AuthMode.NONE,
                type -> new WebWithThumbnail() {
                    @Override public String getIdentifier() { return type.name(); }
                    @Override public boolean requirePlayerScript() { return false; }
                    @Override public dev.lavalink.youtube.track.format.TrackFormats loadFormats(
                            YoutubeAudioSourceManager source, HttpInterface http, String id) throws java.io.IOException {
                        attempted.add(type.name());
                        throw new java.io.IOException("This video requires login");
                    }
                });
        var source = new BackendYoutubeAudioSourceManager(YouTubePlaybackTrackFactory.youtubeSource(), clients);
        var info = new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                "Fixture", "Fixture", 1000, "xfuIlmywvXI", false, "https://youtube.com/watch?v=xfuIlmywvXI");
        var track = source.buildAudioTrack(info);
        var executor = new com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor(track,
                new com.sedmelluq.discord.lavaplayer.player.AudioConfiguration(),
                new com.sedmelluq.discord.lavaplayer.player.AudioPlayerOptions(), false, 5000);
        try {
            assertThrows(Exception.class, () -> track.process(executor));
            assertEquals(List.of("WEB", "MWEB"), attempted);
        } finally { source.shutdown(); }
    }

    @Test
    void fakeClientsAreCreatedAndAttemptedOnlyInConfiguredOrderWithoutAnotherDefaultRound() {
        var created = new ArrayList<YoutubeClientType>();
        var attempted = new ArrayList<String>();
        var clients = YoutubeClientRegistry.create(config("MWEB", "WEB"), MusicConfig.Youtube.AuthMode.NONE, type -> {
            created.add(type);
            return new WebWithThumbnail() {
                @Override public String getIdentifier() { return type.name(); }
                @Override public boolean requirePlayerScript() { return false; }
                @Override public AudioItem loadVideo(YoutubeAudioSourceManager source, HttpInterface http, String id) {
                    attempted.add(type.name());
                    throw new IllegalStateException("This video requires login");
                }
            };
        });
        var source = new BackendYoutubeAudioSourceManager(YouTubePlaybackTrackFactory.youtubeSource(), clients);
        try {
            var failure = assertThrows(RuntimeException.class, () -> source.loadItem(null,
                    new AudioReference("https://www.youtube.com/watch?v=xfuIlmywvXI", null)));
            assertEquals(List.of(YoutubeClientType.MWEB, YoutubeClientType.WEB), created);
            assertEquals(List.of("MWEB", "WEB"), attempted);
            var report = new com.norule.musicbot.domain.music.YoutubeFailureClassifier().classify(failure);
            assertEquals(List.of("MWEB", "WEB"), report.clientFailures().stream().map(c -> c.clientName()).toList());
        } finally { source.shutdown(); }
    }

    private static MusicConfig.Youtube config(String... names) {
        return MusicConfig.Youtube.fromLegacy(BotConfig.Music.Youtube.fromMap(Map.of("clients", List.of(names)), null));
    }
}
