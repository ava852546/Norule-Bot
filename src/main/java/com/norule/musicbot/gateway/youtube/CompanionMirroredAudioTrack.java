package com.norule.musicbot.gateway.youtube;

import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrack;
import com.norule.musicbot.domain.music.*;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import java.util.concurrent.*;

/** Resolve only the head Spotify track, then prepare its mirror outside the audio executor. */
final class CompanionMirroredAudioTrack extends DelegatedAudioTrack implements PlaybackPreparation {
    private final MirroringAudioTrack original;
    private final ExecutorService workers;
    private volatile InternalAudioTrack selected;
    private CompletableFuture<Void> pending;
    private Future<?> task;
    private boolean cancelled;

    CompanionMirroredAudioTrack(MirroringAudioTrack original, ExecutorService workers) {
        super(original.getInfo());
        this.original = original;
        this.workers = workers;
        setUserData(original.getUserData());
    }

    @Override public synchronized CompletableFuture<Void> preparePlayback(long guildId) {
        pending = new CompletableFuture<>();
        cancelled = false;
        try { task = workers.submit(() -> resolveMirror(guildId)); }
        catch (RejectedExecutionException full) {
            pending.completeExceptionally(new YouTubePlaybackException(YoutubeFailureCategory.HTTP_RATE_LIMITED, "MIRROR_QUEUE_FULL"));
        }
        pending.orTimeout(30, TimeUnit.SECONDS).whenComplete((value, failure) -> {
            if (failure instanceof TimeoutException) cancelPreparation();
        });
        return pending;
    }

    private void resolveMirror(long guildId) {
        var context = getUserData() instanceof TrackLoadContext value ? value : null;
        CompanionRequestTrace.CURRENT.set(new CompanionRequestTrace(guildId,
                context == null ? java.util.UUID.randomUUID().toString() : context.requestId(),
                context == null ? "-" : context.batchId(), context == null ? "SPOTIFY" : context.requestSource()));
        try {
            AudioItem item = ((MirroringAudioSourceManager) original.getSourceManager()).getResolver().apply(original);
            if (item instanceof AudioPlaylist playlist) item = playlist.getTracks().isEmpty() ? null : playlist.getTracks().getFirst();
            if (!(item instanceof InternalAudioTrack track))
                throw new YouTubePlaybackException(YoutubeFailureCategory.VIDEO_UNAVAILABLE, "Spotify mirror unavailable.");
            CompletableFuture<Void> prepared;
            synchronized (this) {
                if (cancelled || pending.isDone()) return;
                selected = track;
                selected.setUserData(getUserData());
                prepared = track instanceof PlaybackPreparation preparation ? preparation.preparePlayback(guildId)
                        : CompletableFuture.completedFuture(null);
            }
            prepared.whenComplete((ignored, failure) -> {
                if (failure == null) pending.complete(null);
                else pending.completeExceptionally(failure);
            });
        } catch (YouTubePlaybackException | RuntimeException failure) {
            pending.completeExceptionally(failure);
        } finally { CompanionRequestTrace.CURRENT.remove(); }
    }

    @Override public void cancelPreparation() {
        CompletableFuture<Void> result;
        Future<?> work;
        InternalAudioTrack track;
        synchronized (this) { cancelled = true; result = pending; work = task; track = selected; }
        if (result != null) result.cancel(false);
        if (work != null) work.cancel(true);
        if (workers instanceof ThreadPoolExecutor pool && work instanceof Runnable runnable) pool.remove(runnable);
        if (track instanceof PlaybackPreparation preparation) preparation.cancelPreparation();
    }

    @Override public void process(LocalAudioTrackExecutor executor) throws Exception {
        if (selected == null) throw new IllegalStateException("Spotify mirror was not prepared");
        processDelegate(selected, executor);
    }
    @Override public AudioSourceManager getSourceManager() { return original.getSourceManager(); }
    @Override protected AudioTrack makeShallowClone() {
        return new CompanionMirroredAudioTrack((MirroringAudioTrack) original.makeClone(), workers);
    }
}
