package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.config.domain.MusicConfig;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** One guarded PlayURL request; never prints URLs, cookies or response bodies. */
@EnabledIfSystemProperty(named = "bilibili.selection.live", matches = "true")
class BilibiliAudioSelectionLiveTest {
    @Test void inspectRequestedVideo() throws Exception {
        var manager = new DefaultAudioPlayerManager();
        var source = new BilibiliAudioSourceAdapter(MusicConfig.defaultValues().getBilibili());
        manager.registerSourceManager(source);
        try {
            String id = System.getProperty("bilibili.live.video", "BV1BM4m1S7w1");
            assertTrue(id.matches("BV[0-9A-Za-z]{10}"));
            AudioItem item = source.loadItem(manager, new AudioReference("https://www.bilibili.com/video/" + id + "/", null));
            var track = (BilibiliDashAudioTrack) (item instanceof AudioPlaylist list ? list.getTracks().get(0) : item);
            Thread.sleep(Math.max(1100L, source.nextPlaybackDelayMillis()));
            try (var http = source.httpInterface();
                 var response = http.execute(new HttpGet("https://api.bilibili.com/x/player/playurl?bvid="
                         + id + "&cid=" + track.getCid() + "&fnval=16&fnver=0&fourk=0"))) {
                int status = response.getStatusLine().getStatusCode();
                System.out.println("LIVE_SELECTION videoId=" + id + " cid=" + track.getCid() + " httpStatus=" + status);
                assertEquals(200, status, "Stop on access restrictions; do not retry");
                var root = JsonBrowser.parse(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
                System.out.println("LIVE_SELECTION apiCode=" + root.get("code").asInt(Integer.MIN_VALUE));
                var data = root.get("data");
                System.out.println("LIVE_SELECTION dashPresent=" + !data.get("dash").isNull()
                        + " audioCount=" + data.get("dash").get("audio").values().size());
                for (var audio : data.get("dash").get("audio").values()) {
                    System.out.println("LIVE_FORMAT id=" + audio.get("id").asLong(-1L)
                            + " codecs=" + safe(audio.get("codecs").safeText())
                            + " mimeType=" + safe(audio.get("mimeType").safeText())
                            + " bandwidth=" + audio.get("bandwidth").asLong(-1L)
                            + " baseUrl=" + !audio.get("baseUrl").safeText().isBlank()
                            + " base_url=" + !audio.get("base_url").safeText().isBlank()
                            + " backupUrlCount=" + audio.get("backupUrl").values().size()
                            + " backup_urlCount=" + audio.get("backup_url").values().size());
                }
                System.out.println("LIVE_SELECTION flacPresent=" + !data.get("dash").get("flac").isNull()
                        + " dolbyPresent=" + !data.get("dash").get("dolby").isNull());
                assertEquals(0, root.get("code").asInt(Integer.MIN_VALUE));
                if (Boolean.getBoolean("bilibili.selection.decode")) {
                    var audio = data.get("dash").get("audio").index(0);
                    assertEquals("mp4a.40.5", audio.get("codecs").safeText());
                    var urls = new java.util.LinkedHashSet<URI>();
                    for (String key : List.of("baseUrl", "base_url")) {
                        if (!audio.get(key).safeText().isBlank()) urls.add(URI.create(audio.get(key).safeText()));
                    }
                    for (String key : List.of("backupUrl", "backup_url")) {
                        for (var backup : audio.get(key).values()) urls.add(URI.create(backup.safeText()));
                    }
                    var candidate = new DelegatedAudioTrack(track.getInfo()) {
                        @Override public void process(LocalAudioTrackExecutor executor) throws Exception {
                            try (var decoderHttp = source.httpInterface();
                                 var stream = track.new DashStream(decoderHttp, List.copyOf(urls))) {
                                processDelegate(new MpegAudioTrack(track.getInfo(), stream), executor);
                            }
                        }
                        @Override protected AudioTrack makeShallowClone() { throw new UnsupportedOperationException(); }
                        @Override public BilibiliAudioSourceAdapter getSourceManager() { return source; }
                    };
                    var player = manager.createPlayer();
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    AtomicReference<AudioTrackEndReason> end = new AtomicReference<>();
                    player.addListener(new AudioEventAdapter() {
                        @Override public void onTrackException(AudioPlayer p, AudioTrack t, FriendlyException e) { failure.set(e); }
                        @Override public void onTrackEnd(AudioPlayer p, AudioTrack t, AudioTrackEndReason reason) { end.set(reason); }
                    });
                    try {
                        player.playTrack(candidate);
                        int frames = 0;
                        boolean sought = false;
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(50);
                        while (System.nanoTime() < deadline && failure.get() == null && end.get() == null) {
                            var frame = player.provide();
                            if (frame != null && !frame.isTerminator()) frames++;
                            if (!sought && frames >= 150) {
                                player.setPaused(true);
                                assertNull(player.provide());
                                candidate.setPosition(Math.max(0, candidate.getDuration() - 2000));
                                player.setPaused(false);
                                sought = true;
                            }
                            Thread.sleep(2);
                        }
                        if (failure.get() != null) throw new AssertionError("HE-AAC probe failed", failure.get());
                        assertTrue(sought && frames > 150, "Frames required before and after seek");
                        assertEquals(AudioTrackEndReason.FINISHED, end.get());
                        System.out.println("LIVE_HE_AAC frames=" + frames + " pauseResume=true seek=true end=" + end.get());
                    } finally { player.destroy(); }
                }
            }
        } finally { manager.shutdown(); }
    }

    private static String safe(String value) {
        return value.matches("[A-Za-z0-9./_ ;=\"-]{0,80}") ? value : "REDACTED";
    }
}
