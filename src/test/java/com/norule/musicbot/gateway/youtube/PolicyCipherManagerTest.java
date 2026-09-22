package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.*;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.cipher.CipherManager;
import dev.lavalink.youtube.track.format.StreamFormat;
import org.apache.http.entity.ContentType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PolicyCipherManagerTest {
    @Test
    void disabledNeverConstructsOrCallsDelegateAndAllowsPlainUrl() throws Exception {
        AtomicInteger constructed = new AtomicInteger();
        var guard = new PolicyCipherManager(new CipherPolicy(false), () -> {
            constructed.incrementAndGet();
            throw new AssertionError("Must remain lazy");
        });
        assertEquals(URI.create("https://fixture.googlevideo.com/audio"), guard.resolveFormatUrl(null, null, format(null, null)));
        assertThrows(CipherDisabledException.class, () -> guard.resolveFormatUrl(null, null, format("signature", null)));
        assertThrows(CipherDisabledException.class, () -> guard.resolveFormatUrl(null, null, format(null, "n")));
        assertThrows(CipherDisabledException.class, () -> guard.getCachedPlayerScript(null));
        assertThrows(CipherDisabledException.class, () -> guard.getPlayerScript(null));
        assertThrows(CipherDisabledException.class, () -> guard.getTimestamp(null, null));
        assertEquals(0, constructed.get());
    }

    @Test
    void enabledDelegatesAndReloadDisabledBlocksExistingDelegate() throws Exception {
        AtomicInteger constructed = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        CipherPolicy policy = new CipherPolicy(true);
        var guard = new PolicyCipherManager(policy, () -> {
            constructed.incrementAndGet();
            return new CipherManager() {
                public URI resolveFormatUrl(HttpInterface http, String script, StreamFormat format) {
                    calls.incrementAndGet();
                    return format.getUrl();
                }
                public CachedPlayerScript getCachedPlayerScript(HttpInterface http) { calls.incrementAndGet(); return null; }
                public String getTimestamp(HttpInterface http, String script) { calls.incrementAndGet(); return "123"; }
            };
        });
        guard.resolveFormatUrl(null, null, format("signature", "n"));
        assertEquals("123", guard.getTimestamp(null, null));
        policy.update(false);
        assertThrows(CipherDisabledException.class, () -> guard.resolveFormatUrl(null, null, format("signature", "n")));
        assertThrows(CipherDisabledException.class, () -> guard.getTimestamp(null, null));
        assertEquals(1, constructed.get());
        assertEquals(2, calls.get());
        var failure = new YoutubeFailureClassifier().classify(new CipherDisabledException());
        assertEquals("CIPHER_REQUIRED_BUT_DISABLED", failure.errorKey());
        assertFalse(failure.allowsPlaybackRecovery(MusicConfig.Youtube.AuthMode.NONE));
    }

    @Test
    void productionSourceInstallsGuardAndNoRemoteCipherWhileDisabled() {
        var source = YouTubePlaybackRuntimeFactory.createSource(
                new MusicConfig.Cipher(false, "http://127.0.0.1:1", "", "test"), new CipherPolicy(false),
                MusicConfig.Youtube.AuthMode.NONE, YouTubePlaybackTrackFactory.youtubeSource());
        try {
            assertInstanceOf(PolicyCipherManager.class, source.getCipherManager());
            assertNull(source.getRemoteCipherManager());
            assertThrows(CipherDisabledException.class, () -> source.getCipherManager().getTimestamp(null, null));
        } finally {
            source.shutdown();
        }
    }

    private static StreamFormat format(String signature, String n) {
        return new StreamFormat(ContentType.parse("audio/webm; codecs=opus"), 251, 1000, 1000, 2,
                "https://fixture.googlevideo.com/audio", n, signature, "sig", true, false);
    }
}
