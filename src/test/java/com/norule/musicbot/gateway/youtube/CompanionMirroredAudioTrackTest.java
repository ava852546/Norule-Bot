package com.norule.musicbot.gateway.youtube;

import com.github.topi314.lavasrc.mirror.*;
import com.norule.musicbot.domain.music.PlaybackPreparation;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CompanionMirroredAudioTrackTest {
    private static final AudioTrackInfo INFO = new AudioTrackInfo("fixture", "artist", 1000, "spotify-fixture", false, "https://open.spotify.com/track/fixture");

    @Test void hundredSpotifyEntriesDoNotResolveUntilHeadPreparationAndGuildTraceIsPreserved() throws Exception {
        var calls = new AtomicInteger();
        var delegate = new PreparedTrack();
        String callerThread = Thread.currentThread().getName();
        try (var source = new MirrorSource(track -> {
            calls.incrementAndGet();
            assertNotEquals(callerThread, Thread.currentThread().getName());
            assertEquals(42, CompanionRequestTrace.CURRENT.get().guildId());
            return delegate;
        }); var workers = workers()) {
            var tracks = new java.util.ArrayList<CompanionMirroredAudioTrack>();
            for (int i = 0; i < 100; i++) tracks.add(new CompanionMirroredAudioTrack(new MirrorTrack(source), workers));
            assertEquals(0, calls.get());
            var prepared = tracks.getFirst().preparePlayback(42);
            assertTrue(delegate.started.await(2, TimeUnit.SECONDS));
            assertFalse(prepared.isDone());
            delegate.ready.complete(null);
            prepared.get(2, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            assertEquals(42, delegate.guild.get());
        }
    }

    @Test void cancellationRemovesWaitingMirrorAndCancelsPreparedStream() throws Exception {
        var block = new CountDownLatch(1);
        var delegate = new PreparedTrack();
        try (var source = new MirrorSource(track -> delegate); var workers = workers()) {
            var occupied = workers.submit(() -> { try { block.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } });
            var queued = new CompanionMirroredAudioTrack(new MirrorTrack(source), workers);
            var pending = queued.preparePlayback(42);
            queued.cancelPreparation();
            assertTrue(pending.isCancelled());
            assertEquals(0, workers.getQueue().size());
            block.countDown(); occupied.get(2, TimeUnit.SECONDS);
            var active = new CompanionMirroredAudioTrack(new MirrorTrack(source), workers);
            var activePending = active.preparePlayback(42);
            assertTrue(delegate.started.await(2, TimeUnit.SECONDS));
            active.cancelPreparation();
            assertTrue(activePending.isCancelled());
            assertTrue(delegate.ready.isCancelled());
        } finally { block.countDown(); }
    }

    private static ThreadPoolExecutor workers() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
    }

    private static final class PreparedTrack extends BaseAudioTrack implements PlaybackPreparation {
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicLong guild = new AtomicLong();
        PreparedTrack() { super(INFO); }
        @Override public CompletableFuture<Void> preparePlayback(long id) { guild.set(id); started.countDown(); return ready; }
        @Override public void cancelPreparation() { ready.cancel(false); }
        @Override public void process(LocalAudioTrackExecutor executor) { fail("No LavaPlayer thread should start before preparation"); }
        @Override public AudioSourceManager getSourceManager() { return null; }
        @Override protected AudioTrack makeShallowClone() { return new PreparedTrack(); }
    }

    private static final class MirrorTrack extends MirroringAudioTrack {
        MirrorTrack(MirrorSource source) { super(INFO, "", "", "", "", "", false, source); }
        @Override protected InternalAudioTrack createAudioTrack(AudioTrackInfo info,
                com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream stream) { throw new AssertionError("preview"); }
        @Override protected AudioTrack makeShallowClone() { return new MirrorTrack((MirrorSource) getSourceManager()); }
    }

    private static final class MirrorSource extends MirroringAudioSourceManager implements AutoCloseable {
        MirrorSource(MirroringAudioTrackResolver resolver) { super(ignored -> null, resolver); }
        @Override public String getSourceName() { return "spotify"; }
        @Override public AudioItem loadItem(com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager manager, AudioReference reference) { return null; }
        @Override public boolean isTrackEncodable(AudioTrack track) { return false; }
        @Override public void encodeTrack(AudioTrack track, DataOutput output) { }
        @Override public AudioTrack decodeTrack(AudioTrackInfo info, DataInput input) { return null; }
        @Override public void close() { shutdown(); }
    }
}
