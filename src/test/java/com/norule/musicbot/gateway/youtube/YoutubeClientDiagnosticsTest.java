package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.CipherPolicy;
import com.norule.musicbot.domain.music.YouTubePlaybackTrackFactory;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.WebWithThumbnail;
import dev.lavalink.youtube.clients.skeleton.Client;
import dev.lavalink.youtube.track.format.TrackFormats;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class YoutubeClientDiagnosticsTest {
    @Test
    void statesSeparateSkippedSuccessfulFailedAndUnreachedClientsAndRestoreNestedScope() throws Throwable {
        var calls = new AtomicInteger();
        var failed = client("WEB", true, true, calls);
        var skipped = client("MWEB", false, false, calls);
        var success = client("IOS", true, false, calls);
        var unreached = client("ANDROID_VR", true, false, calls);
        try (var outer = new YoutubeClientDiagnostics(new Client[]{failed, skipped, success, unreached}, "TEST", "fixture")) {
            assertThrows(IOException.class, () -> failed.loadFormats(null, null, "fixture"));
            assertFalse(skipped.canHandleRequest("fixture"));
            success.loadVideo(null, null, "fixture");
            String summary = outer.summary();
            assertTrue(summary.contains("attemptedClients=[WEB, IOS]"));
            assertTrue(summary.contains("WEB=FAILED:"));
            assertTrue(summary.contains("MWEB=SKIPPED"));
            assertTrue(summary.contains("IOS=SUCCESS"));
            assertTrue(summary.contains("ANDROID_VR=CONFIGURED"));
            try (var nested = new YoutubeClientDiagnostics(new Client[]{skipped}, "TEST", "nested")) {
                skipped.loadVideo(null, null, "fixture");
                assertTrue(nested.summary().contains("MWEB=SUCCESS"));
            }
            assertEquals(summary, outer.summary());
        }
        // No retained scope and no extra observer calls/retries.
        success.loadVideo(null, null, "fixture");
        assertEquals(4, calls.get());
    }

    @Test
    void cipherFilteredClientIsSkippedWithoutAnAttemptOrDelegateCall() {
        var calls = new AtomicInteger();
        var scripted = client("WEB", true, false, calls);
        var source = new BackendYoutubeAudioSourceManager(YouTubePlaybackTrackFactory.youtubeSource(), scripted);
        source.setCipherManager(new PolicyCipherManager(new CipherPolicy(false), () -> { throw new AssertionError(); }));
        try (var diagnostics = new YoutubeClientDiagnostics(source.getClients(), "TEST", "fixture");
             var scope = PolicyCipherManager.streamScope()) {
            assertThrows(com.norule.musicbot.domain.music.CipherDisabledException.class, source::getClients);
            assertTrue(diagnostics.summary().contains("attemptedClients=[]"));
            assertTrue(diagnostics.summary().contains("WEB=SKIPPED"));
            assertEquals(0, calls.get());
        } finally { source.shutdown(); }
    }

    private static Client client(String name, boolean capable, boolean fail, AtomicInteger calls) {
        return new ObservedYoutubeClient(new WebWithThumbnail() {
            @Override public String getIdentifier() { return name; }
            @Override public boolean canHandleRequest(String identifier) { return capable; }
            @Override public AudioItem loadVideo(YoutubeAudioSourceManager source, HttpInterface http, String id) {
                calls.incrementAndGet();
                return new AudioReference(id, "fixture");
            }
            @Override public TrackFormats loadFormats(YoutubeAudioSourceManager source, HttpInterface http, String id)
                    throws IOException {
                calls.incrementAndGet();
                if (fail) throw new IOException("HTTP 403");
                return null;
            }
        });
    }
}
