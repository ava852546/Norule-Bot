package com.norule.musicbot.gateway.youtube;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.norule.musicbot.domain.music.*;
import com.sedmelluq.discord.lavaplayer.player.*;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.WebWithThumbnail;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CompanionMetadataRoutingTest {
    private static final String ID = "xfuIlmywvXI";
    private static final AudioTrackInfo INFO = new AudioTrackInfo("Fixture", "Artist", 120000, ID,
            false, "https://www.youtube.com/watch?v=" + ID);
    private static final String METADATA = """
            {"playabilityStatus":{"status":"OK"},"videoDetails":{
            "videoId":"xfuIlmywvXI","title":"Fixture","author":"Artist","lengthSeconds":"120"}}
            """;

    @Test
    void directUrlsUseCompanionMetadataAndPlaybackWithCipherDisabledOrEnabled() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            AtomicInteger metadata = new AtomicInteger(), streams = new AtomicInteger(), frames = new AtomicInteger();
            CompanionPlaybackClient client = client(metadata, 200, METADATA);
            var factory = factory(client, streams, frames);
            var source = new BackendYoutubeAudioSourceManager(factory, fixtureClient());
            source.setCipherManager(new PolicyCipherManager(new CipherPolicy(enabled), () -> {
                fail("Companion must never use standalone Cipher, even when it is enabled");
                return null;
            }));
            try {
                for (String url : List.of(INFO.uri, "https://youtu.be/" + ID, ID, INFO.uri + "&list=WL")) {
                    var track = assertInstanceOf(InternalAudioTrack.class, source.loadItem(null, new AudioReference(url, null)));
                    assertEquals("Fixture", track.getInfo().title);
                    assertEquals(120000, track.getDuration());
                    track.process(new LocalAudioTrackExecutor(track, new AudioConfiguration(), new AudioPlayerOptions(), false, 5000));
                }
                assertEquals(4, metadata.get());
                assertEquals(4, streams.get());
                assertEquals(4, frames.get()); // Fixture delegate invocation, not a real voice frame.
            } finally { source.shutdown(); }
        }
    }

    @Test
    void companion400IsAttemptedOnceAndKeepsTypedCause() {
        AtomicInteger calls = new AtomicInteger();
        var factory = factory(client(calls, 400, "{\"error\":\"bad request\"}"), new AtomicInteger(), new AtomicInteger());
        var source = new BackendYoutubeAudioSourceManager(factory, fixtureClient(), fixtureClient(), fixtureClient());
        try {
            var failure = assertThrows(RuntimeException.class,
                    () -> source.loadItem(null, new AudioReference(INFO.uri, null)));
            var report = new YoutubeFailureClassifier().classify(failure);
            assertEquals(YoutubeFailureCategory.COMPANION_BAD_REQUEST, report.category());
            assertEquals(400, report.httpStatus());
            assertEquals(1, calls.get());
        } finally { source.shutdown(); }
    }

    @Test
    void searchPlaylistAndMixKeepCandidateDiscoveryAndCompanionPlayback() throws Exception {
        AtomicInteger metadata = new AtomicInteger(), streams = new AtomicInteger(), audio = new AtomicInteger();
        var source = new BackendYoutubeAudioSourceManager(factory(client(metadata, 200, METADATA), streams, audio), fixtureClient());
        source.setCipherManager(new PolicyCipherManager(new CipherPolicy(false), () -> { throw new AssertionError("Cipher"); }));
        try {
            for (String identifier : List.of("ytsearch:fixture", "ytmsearch:fixture",
                    INFO.uri + "&list=PLfixture", INFO.uri + "&list=RD" + ID)) {
                AudioPlaylist playlist = assertInstanceOf(AudioPlaylist.class,
                        source.loadItem(null, new AudioReference(identifier, null)));
                var track = (InternalAudioTrack) playlist.getTracks().getFirst();
                track.process(new LocalAudioTrackExecutor(track, new AudioConfiguration(), new AudioPlayerOptions(), false, 5000));
            }
            assertEquals(0, metadata.get());
            assertEquals(4, streams.get());
            assertEquals(4, audio.get());
        } finally { source.shutdown(); }
    }

    private static CompanionPlaybackClient client(AtomicInteger calls, int status, String body) {
        return new CompanionPlaybackClient("http://127.0.0.1:8282/companion", "ChangeMe12345678", 1000,
                new ObjectMapper(), request -> {
                    calls.incrementAndGet();
                    assertEquals("/companion/youtubei/v1/player", request.uri().getPath());
                    return new CompanionPlaybackClient.HttpResponseData(status, "application/json", body);
                });
    }

    private static CompanionYouTubePlaybackTrackFactory factory(CompanionPlaybackClient client,
                                                                 AtomicInteger streams, AtomicInteger audio) {
        return new CompanionYouTubePlaybackTrackFactory(id -> {
            streams.incrementAndGet();
            return new ResolvedYouTubePlayback(id, YouTubePlaybackBackend.COMPANION,
                    URI.create("http://127.0.0.1/fixture"), "audio/webm", "opus", 251, 1000L, 1000L,
                    Instant.now().plusSeconds(60), null);
        }, new HttpAudioSourceManager() {
            @Override public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
                return new DelegatedAudioTrack(INFO) {
                    @Override public void process(LocalAudioTrackExecutor executor) { audio.incrementAndGet(); }
                    @Override protected AudioTrack makeShallowClone() { throw new UnsupportedOperationException(); }
                    @Override public com.sedmelluq.discord.lavaplayer.source.AudioSourceManager getSourceManager() { return null; }
                };
            }
        }, client);
    }

    private static WebWithThumbnail fixtureClient() {
        return new WebWithThumbnail() {
            @Override public boolean canHandleRequest(String identifier) { return true; }
            @Override public AudioItem loadVideo(YoutubeAudioSourceManager source, HttpInterface http, String id) {
                throw new AssertionError("Direct Companion videos must not enter source player metadata");
            }
            @Override public AudioItem loadSearch(YoutubeAudioSourceManager source, HttpInterface http, String query) {
                return new BasicAudioPlaylist("Fixture", List.of(source.buildAudioTrack(INFO)), null, true);
            }
            @Override public AudioItem loadSearchMusic(YoutubeAudioSourceManager source, HttpInterface http, String query) {
                return loadSearch(source, http, query);
            }
            @Override public AudioItem loadPlaylist(YoutubeAudioSourceManager source, HttpInterface http, String list, String selected) {
                return loadSearch(source, http, list);
            }
            @Override public AudioItem loadMix(YoutubeAudioSourceManager source, HttpInterface http, String list, String selected) {
                return loadSearch(source, http, list);
            }
        };
    }
}
