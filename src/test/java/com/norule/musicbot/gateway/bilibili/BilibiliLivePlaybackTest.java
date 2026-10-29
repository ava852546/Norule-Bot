package com.norule.musicbot.gateway.bilibili;

import com.norule.musicbot.config.domain.MusicConfig;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real network/decoder probe. This does not establish Discord voice delivery. */
@EnabledIfSystemProperty(named = "bilibili.live", matches = "true")
class BilibiliLivePlaybackTest {
    @Test
    void simultaneousPlayersAndNextTrackRemainIndependent() throws Exception {
        var manager = new DefaultAudioPlayerManager();
        var source = new BilibiliAudioSourceAdapter(MusicConfig.defaultValues().getBilibili());
        manager.registerSourceManager(source);
        var first = manager.createPlayer();
        var second = manager.createPlayer();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var listener = new AudioEventAdapter() {
            @Override public void onTrackException(AudioPlayer p, AudioTrack t, FriendlyException e) { failure.set(e); }
        };
        first.addListener(listener);
        second.addListener(listener);
        var scheduler = new com.norule.musicbot.domain.music.TrackScheduler(first);
        first.addListener(scheduler);
        try {
            AudioItem item = source.loadItem(manager, new AudioReference("https://www.bilibili.com/video/BV1VqsQ6KEoy/", null));
            AudioTrack track = item instanceof AudioPlaylist list ? list.getTracks().get(0) : (AudioTrack) item;
            var next = track.makeClone();
            scheduler.queue(track);
            scheduler.queue(next);
            // Respect the default shared 1 request/sec control-plane limit (nav + view + playurl).
            Thread.sleep(1100);
            second.playTrack(track.makeClone());
            long firstFrames = 0, secondFrames = 0, nextFrames = 0;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            boolean switched = false;
            while (System.nanoTime() < deadline && failure.get() == null) {
                var a = first.provide();
                var b = second.provide();
                if (a != null && !a.isTerminator()) {
                    if (switched) nextFrames++; else firstFrames++;
                }
                if (b != null && !b.isTerminator()) secondFrames++;
                if (!switched && firstFrames >= 500 && secondFrames >= 500) {
                    scheduler.nextTrack();
                    switched = true;
                    assertSame(next, first.getPlayingTrack());
                }
                if (switched && nextFrames >= 500 && secondFrames >= 1500) break;
                Thread.sleep(2);
            }
            if (failure.get() != null) throw new AssertionError("Concurrent real decoder failed", failure.get());
            assertTrue(firstFrames >= 500 && nextFrames >= 500 && secondFrames >= 1500);
            assertNotSame(first.getPlayingTrack(), second.getPlayingTrack());
            System.out.println("LIVE_CONCURRENT firstMillis=" + firstFrames * 20 + " nextMillis=" + nextFrames * 20
                    + " secondMillis=" + secondFrames * 20);
        } finally {
            first.destroy(); second.destroy(); manager.shutdown();
        }
    }

    @Test
    void decodesRequestedVideo() throws Exception {
        var manager = new DefaultAudioPlayerManager();
        var source = new BilibiliAudioSourceAdapter(MusicConfig.defaultValues().getBilibili());
        manager.registerSourceManager(source);
        var player = manager.createPlayer();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<AudioTrackEndReason> end = new AtomicReference<>();
        player.addListener(new AudioEventAdapter() {
            @Override public void onTrackException(AudioPlayer p, AudioTrack t, FriendlyException e) {
                failure.set(e);
                e.printStackTrace();
            }
            @Override public void onTrackEnd(AudioPlayer p, AudioTrack t, AudioTrackEndReason reason) {
                end.set(reason);
            }
        });
        try {
            AudioItem item = source.loadItem(manager, new AudioReference(
                    "https://www.bilibili.com/video/BV1VqsQ6KEoy/", null));
            AudioTrack track = item instanceof AudioPlaylist list ? list.getTracks().get(0) : (AudioTrack) item;
            System.out.println("LIVE_METADATA title=" + track.getInfo().title + " duration=" + track.getDuration());
            player.playTrack(track);
            long required = Long.getLong("bilibili.live.millis", 30_000L);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    required < 0 ? track.getDuration() + 60_000 : required + 60_000);
            long frames = 0;
            while (System.nanoTime() < deadline && failure.get() == null) {
                var frame = player.provide();
                if (frame != null && !frame.isTerminator()) frames++;
                if (frame != null && !frame.isTerminator() && (frames == 500 || frames == 1500)) {
                    System.out.println("LIVE_CHECKPOINT audioMillis=" + frames * 20);
                }
                if (required >= 0 && frames * 20 >= required) break;
                if (end.get() != null) break;
                Thread.sleep(2);
            }
            if (failure.get() != null) throw new AssertionError("Real Lavaplayer decode failed", failure.get());
            System.out.println("LIVE_DECODE frames=" + frames + " audioMillis=" + frames * 20 + " end=" + end.get());
            if (required < 0) assertEquals(AudioTrackEndReason.FINISHED, end.get());
            else assertTrue(frames * 20 >= required, "Insufficient decoded audio");
            assertTrue(frames > 0);
        } finally {
            player.destroy();
            manager.shutdown();
        }
    }
}
