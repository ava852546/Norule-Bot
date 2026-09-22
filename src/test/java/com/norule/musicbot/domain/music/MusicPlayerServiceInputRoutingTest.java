package com.norule.musicbot.domain.music;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.gateway.bilibili.BilibiliAudioSourceAdapter;
import com.norule.musicbot.gateway.youtube.YouTubePlaybackRuntimeFactory;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicPlayerServiceInputRoutingTest {
    @TempDir
    Path tempDir;
    private MusicPlayerService service;
    private AudioPlayerManager realManager;
    private Guild guild;
    private final List<String> identifiers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        MusicConfig config = MusicConfig.defaultValues();
        CipherPolicy policy = new CipherPolicy(false);
        YouTubePlaybackTrackFactory tracks = YouTubePlaybackTrackFactory.youtubeSource();
        service = new MusicPlayerService(tempDir, ignored -> 100, ignored -> 365, ignored -> 100,
                config, tempDir.resolve("music.db"), SpotifyPlaylistInspector.noOp(), tracks,
                new BilibiliAudioSourceAdapter(config.getBilibili()), policy,
                auth -> YouTubePlaybackRuntimeFactory.createSource(config.getCipher(), policy, auth, tracks));
        var managerField = MusicPlayerService.class.getDeclaredField("playerManager");
        managerField.setAccessible(true);
        realManager = (AudioPlayerManager) managerField.get(service);
        // Intercept the final load boundary: exercise real routing/validation without platform requests.
        AudioPlayerManager capturingManager = (AudioPlayerManager) Proxy.newProxyInstance(
                AudioPlayerManager.class.getClassLoader(), new Class<?>[] {AudioPlayerManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("loadItemOrdered")) {
                        identifiers.add((String) args[1]);
                        return CompletableFuture.completedFuture(null);
                    }
                    if (method.getName().equals("loadItem")) {
                        identifiers.add((String) args[0]);
                        return CompletableFuture.completedFuture(null);
                    }
                    return method.invoke(realManager, args);
                });
        managerField.set(service, capturingManager);
        AudioManager audio = (AudioManager) Proxy.newProxyInstance(
                AudioManager.class.getClassLoader(), new Class<?>[] {AudioManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setSendingHandler") || method.getName().equals("getConnectedChannel")) {
                        return null;
                    }
                    throw new AssertionError("Unexpected audio call: " + method.getName());
                });
        guild = (Guild) Proxy.newProxyInstance(Guild.class.getClassLoader(), new Class<?>[] {Guild.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getIdLong" -> 123L;
                    case "getAudioManager" -> audio;
                    default -> throw new AssertionError("Unexpected guild call: " + method.getName());
                });
    }

    @AfterEach
    void tearDown() {
        if (realManager != null) {
            realManager.shutdown();
        }
    }

    @Test
    void sharedPlaybackEntryLoadsOnlyFirstUrlAndKeepsSearchRouting() {
        String share = "\u5206\u4EAB\n[\u6B4C\u66F2](https://b23.tv/1DvRnBr) https://example.com/b";
        assertTrue(service.isUrlLikeInput(share));
        service.loadAndPlay(guild, response -> { }, share);
        assertEquals(List.of("https://b23.tv/1DvRnBr"), identifiers);

        String query = "\u5B8B\u51AC\u91CE \u8463\u5C0F\u59D0";
        assertFalse(service.isUrlLikeInput("  " + query + "  "));
        service.loadAndPlay(guild, response -> { }, "  " + query + "  ");
        service.searchTopTracks("  " + query + "  ", 10, result -> { }, error -> { });
        assertEquals(List.of("https://b23.tv/1DvRnBr", "ytsearch:" + query, "ytsearch:" + query), identifiers);
    }

    @Test
    void playlistInputUsesSameNormalizationBeforeLoading() {
        service.addTrackToPlaylistByInput(guild, "fixture", "share <https://b23.tv/1DvRnBr>",
                1L, "fixture", result -> { }, error -> { });
        assertEquals(List.of("https://b23.tv/1DvRnBr"), identifiers);
    }

    @Test
    void preservesSchemeCaseAndRawParametersAtPlaybackBoundary() {
        String url = "HTTP://b23.tv/1DvRnBr?z=x%26y&a=1&a=2#part%202";
        service.loadAndPlay(guild, response -> { }, "share <" + url + ">");
        assertEquals(List.of(url), identifiers);
    }

    @Test
    void extractedMalformedUrlAndDisabledDirectHttpStillFailValidation() {
        List<String> responses = new ArrayList<>();
        service.loadAndPlay(guild, responses::add, "share https://example.com/a?q=%ZZ https://b23.tv/1DvRnBr");
        service.loadAndPlay(guild, responses::add, "share https://127.0.0.1/song.mp3");
        assertEquals(2, responses.size());
        assertTrue(responses.stream().allMatch(response -> response.startsWith("LOAD_FAILED:")));
        assertTrue(identifiers.isEmpty());
    }

    @Test
    void emptyPlaylistInputAndSearchRetainEmptyResults() {
        for (String input : new String[] {null, "", " \t\n"}) {
            assertFalse(service.isUrlLikeInput(input));
            service.searchTopTracks(input, 10, results -> assertTrue(results.isEmpty()), error -> {
                throw new AssertionError(error);
            });
            service.addTrackToPlaylistByInput(guild, "fixture", input, 1L, "fixture",
                    result -> assertEquals(MusicDataService.PlaylistMutationStatus.EMPTY, result.status()),
                    error -> { throw new AssertionError(error); });
        }
        assertTrue(identifiers.isEmpty());
    }
}
