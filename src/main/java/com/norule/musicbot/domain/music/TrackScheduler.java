package com.norule.musicbot.domain.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class TrackScheduler extends AudioEventAdapter {
    private enum RepeatMode {
        OFF, SINGLE, ALL
    }

    private final AudioPlayer player;
    private final Queue<AudioTrack> queue = new ConcurrentLinkedQueue<>();
    private int queuedCount; // Guarded by this scheduler, alongside every queue mutation.
    private volatile RepeatMode repeatMode = RepeatMode.OFF;
    private volatile Consumer<MusicStateChange> stateListener;
    private volatile Consumer<AudioTrack> queueExhaustedListener;
    private volatile Consumer<AudioTrack> trackStartListener;
    private volatile Consumer<AudioTrack> trackEndListener;
    private volatile BiConsumer<AudioTrack, FriendlyException> trackExceptionListener;
    private volatile BiConsumer<AudioTrack, Long> trackStuckListener;
    private long playbackGeneration;
    private AudioTrack recoveringTrack;
    private long recoveringGeneration = -1L;
    private AudioTrack failedTrackAwaitingEnd;
    private AudioTrack recoveryAwaitingFrame;
    private AudioTrack scheduledTrack;
    private Consumer<AudioTrack> recoveryFrameListener;
    private Consumer<AudioTrack> diagnosticFrameListener;
    private volatile AudioTrack diagnosticConfirmedTrack;
    private long guildId;
    private final java.time.Clock clock;
    private long sourceHeldUntil;
    private java.util.function.Predicate<AudioTrack> heldSource = ignored -> false;
    private int transientSourceFailures;
    private long lastSourceFailure;

    public synchronized boolean shouldHoldSource(YoutubeFailureCategory category) {
        if (category == YoutubeFailureCategory.BOT_DETECTED || category == YoutubeFailureCategory.HTTP_RATE_LIMITED
                || category == YoutubeFailureCategory.COMPANION_AUTH_FAILED || category == YoutubeFailureCategory.COMPANION_BAD_REQUEST) return true;
        if (category != YoutubeFailureCategory.COMPANION_TIMEOUT && category != YoutubeFailureCategory.COMPANION_UNAVAILABLE) return false;
        long now = clock.millis();
        transientSourceFailures = now - lastSourceFailure <= 30_000 ? transientSourceFailures + 1 : 1;
        lastSourceFailure = now;
        return transientSourceFailures >= 3;
    }

    public TrackScheduler(AudioPlayer player) {
        this(player, java.time.Clock.systemUTC());
    }

    TrackScheduler(AudioPlayer player, java.time.Clock clock) {
        this.player = player;
        this.clock = clock;
    }

    public synchronized void setGuildId(long guildId) { this.guildId = guildId; }
    public synchronized long getGuildId() { return guildId; }
    public synchronized boolean isPreparing() {
        return scheduledTrack instanceof PlaybackPreparation && player.getPlayingTrack() == null;
    }

    /** No automatic retry timer: a later user operation may probe after cooldown. */
    public synchronized void holdSource(java.util.function.Predicate<AudioTrack> source, long cooldownMillis) {
        heldSource = source;
        sourceHeldUntil = clock.millis() + cooldownMillis;
    }

    private boolean sourceHeld(AudioTrack track) {
        return clock.millis() < sourceHeldUntil && heldSource.test(track);
    }

    public synchronized void queue(AudioTrack track) {
        queue(track, false);
    }

    /** Only metadata commits belonging to a playlist job may defer their queue notification. */
    public synchronized void queue(AudioTrack track, boolean playlistEntry) {
        if (sourceHeld(track) || (scheduledTrack != null && player.getPlayingTrack() == null)
                || (track instanceof PlaybackPreparation && player.getPlayingTrack() != null)) {
            queue.offer(track);
            queuedCount++;
        } else if (track instanceof PlaybackPreparation) {
            startReplacement(track);
        } else if (!player.startTrack(track, true)) {
            queue.offer(track);
            queuedCount++;
        } else {
            scheduledTrack = track;
            playbackGeneration++;
            clearRecoveryMarker();
        }
        notifyStateChanged(playlistEntry ? MusicStateChange.BATCH_PROGRESS : MusicStateChange.QUEUE_CHANGED);
    }

    public synchronized void nextTrack() {
        nextTrack(true);
    }

    private void nextTrack(boolean recommendWhenEmpty) {
        AudioTrack previous = player.getPlayingTrack();
        if (previous == null) {
            previous = recoveringTrack;
        }
        AudioTrack next = pollPlayable();
        startReplacement(next);
        if (next == null && queue.isEmpty() && recommendWhenEmpty) {
            notifyQueueExhausted(previous);
        }
        notifyStateChanged(MusicStateChange.QUEUE_CHANGED);
    }

    public synchronized void clear() {
        cancelPendingPreparation();
        queue.clear();
        queuedCount = 0;
        scheduledTrack = null;
        playbackGeneration++;
        clearRecoveryMarker();
        failedTrackAwaitingEnd = null;
        notifyStateChanged(MusicStateChange.QUEUE_CHANGED);
    }

    public synchronized int shuffleQueue() {
        List<AudioTrack> tracks = new ArrayList<>(queue);
        if (tracks.size() <= 1) {
            return tracks.size();
        }
        Collections.shuffle(tracks);
        queue.clear();
        queuedCount = 0;
        for (AudioTrack track : tracks) {
            queue.offer(track);
            queuedCount++;
        }
        notifyStateChanged(MusicStateChange.SHUFFLE_CHANGED);
        return tracks.size();
    }

    public String getRepeatModeName() {
        return repeatMode.name();
    }

    public void setRepeatMode(String mode) {
        try {
            repeatMode = RepeatMode.valueOf(mode.toUpperCase());
        } catch (Exception ignored) {
            repeatMode = RepeatMode.OFF;
        }
        notifyStateChanged(MusicStateChange.LOOP_CHANGED);
    }

    public List<AudioTrack> snapshotQueue() {
        return new ArrayList<>(queue);
    }

    /** Copies only the visible prefix; total count follows the existing queue mutations. */
    public synchronized QueuePreview previewQueue(int limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative queue preview limit");
        List<AudioTrack> tracks = new ArrayList<>(Math.min(limit, queuedCount));
        var iterator = queue.iterator();
        while (tracks.size() < limit && iterator.hasNext()) tracks.add(iterator.next());
        return new QueuePreview(List.copyOf(tracks), queuedCount);
    }

    public record QueuePreview(List<AudioTrack> tracks, int total) { }

    public void setStateListener(Runnable stateListener) {
        this.stateListener = stateListener == null ? null : ignored -> stateListener.run();
    }

    public void setStateChangeListener(Consumer<MusicStateChange> listener) {
        this.stateListener = listener;
    }

    public void setQueueExhaustedListener(Consumer<AudioTrack> queueExhaustedListener) {
        this.queueExhaustedListener = queueExhaustedListener;
    }

    public void setTrackStartListener(Consumer<AudioTrack> trackStartListener) {
        this.trackStartListener = trackStartListener;
    }

    public void setTrackEndListener(Consumer<AudioTrack> trackEndListener) {
        this.trackEndListener = trackEndListener;
    }

    public void setTrackExceptionListener(BiConsumer<AudioTrack, FriendlyException> trackExceptionListener) {
        this.trackExceptionListener = trackExceptionListener;
    }

    public void setTrackStuckListener(BiConsumer<AudioTrack, Long> trackStuckListener) {
        this.trackStuckListener = trackStuckListener;
    }

    public synchronized long getPlaybackGeneration() {
        return playbackGeneration;
    }

    public synchronized boolean isIdle(long expectedGeneration) {
        return playbackGeneration == expectedGeneration && player.getPlayingTrack() == null
                && scheduledTrack == null && queue.isEmpty();
    }

    public synchronized boolean queueIfIdle(AudioTrack track, long expectedGeneration) {
        if (track == null || !isIdle(expectedGeneration)) {
            return false;
        }
        queue(track);
        return true;
    }

    public synchronized boolean isActiveTrack(AudioTrack track, long expectedGeneration) {
        return track != null
                && (player.getPlayingTrack() == track || recoveringTrack == track || scheduledTrack == track)
                && playbackGeneration == expectedGeneration;
    }

    public synchronized void pauseIfCurrent(AudioTrack track, long expectedGeneration) {
        if (isActiveTrack(track, expectedGeneration)) {
            recoveringTrack = track;
            recoveringGeneration = expectedGeneration;
            player.setPaused(true);
        }
    }

    public synchronized boolean replaceIfCurrent(AudioTrack expectedTrack,
                                                 AudioTrack replacement,
                                                 long expectedGeneration,
                                                 long resumePosition) {
        if (replacement == null || !isActiveTrack(expectedTrack, expectedGeneration)) {
            return false;
        }
        if (replacement.isSeekable() && resumePosition > 0L) {
            replacement.setPosition(resumePosition);
        }
        failedTrackAwaitingEnd = expectedTrack;
        startReplacement(replacement);
        recoveryAwaitingFrame = replacement;
        notifyStateChanged(MusicStateChange.RECOVERY);
        return true;
    }

    public synchronized boolean skipIfCurrent(AudioTrack expectedTrack, long expectedGeneration) {
        if (isActiveTrack(expectedTrack, expectedGeneration)) {
            failedTrackAwaitingEnd = expectedTrack;
            // Failed recommendations must not create an endless autoplay failure loop.
            nextTrack(false);
            return true;
        }
        return false;
    }

    public synchronized boolean stopIfCurrent(AudioTrack expectedTrack, long expectedGeneration) {
        if (!isActiveTrack(expectedTrack, expectedGeneration)) return false;
        failedTrackAwaitingEnd = expectedTrack;
        // Invalidate identity before stop callbacks; retain the queue and do not request autoplay.
        startReplacement(null);
        notifyStateChanged(MusicStateChange.RECOVERY);
        return true;
    }

    public synchronized void invalidatePlaybackGeneration() {
        cancelPendingPreparation();
        scheduledTrack = null;
        playbackGeneration++;
        clearRecoveryMarker();
    }

    public synchronized void setRecoveryFrameListener(Consumer<AudioTrack> listener) {
        recoveryFrameListener = listener;
    }

    public synchronized void setDiagnosticFrameListener(Consumer<AudioTrack> listener) { diagnosticFrameListener = listener; }

    /** Called only for a valid frame already obtained by the ordinary audio send flow. */
    synchronized void observeAudioFrame(AudioTrack track) {
        if (track != null && player.getPlayingTrack() == track && diagnosticConfirmedTrack != track) {
            transientSourceFailures = 0;
            diagnosticConfirmedTrack = track;
            if (diagnosticFrameListener != null) diagnosticFrameListener.accept(track);
        }
        if (track == null || track != recoveryAwaitingFrame || player.getPlayingTrack() != track) {
            return;
        }
        recoveryAwaitingFrame = null;
        if (recoveryFrameListener != null) recoveryFrameListener.accept(track);
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        diagnosticConfirmedTrack = null;
        Consumer<AudioTrack> startListener = trackStartListener;
        if (startListener != null && track != null) {
            startListener.accept(track);
        }
        notifyStateChanged(MusicStateChange.TRACK_START);
    }

    @Override
    public synchronized void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (recoveryAwaitingFrame == track) recoveryAwaitingFrame = null;
        Consumer<AudioTrack> endListener = trackEndListener;
        if (endListener != null && track != null) {
            endListener.accept(track);
        }
        if (!endReason.mayStartNext) {
            notifyStateChanged(MusicStateChange.TRACK_END);
            return;
        }

        // End events may arrive after more than one replacement/skip. Track identity
        // prevents an old event from advancing the latest queue, even when it is empty.
        if (track != scheduledTrack) {
            notifyStateChanged(MusicStateChange.TRACK_END);
            return;
        }

        if (endReason == AudioTrackEndReason.LOAD_FAILED && failedTrackAwaitingEnd == track) {
            failedTrackAwaitingEnd = null;
            notifyStateChanged(MusicStateChange.TRACK_END);
            return;
        }

        if (endReason == AudioTrackEndReason.LOAD_FAILED
                && recoveringTrack == track
                && recoveringGeneration == playbackGeneration) {
            notifyStateChanged(MusicStateChange.TRACK_END);
            return;
        }

        if (repeatMode == RepeatMode.SINGLE && track != null) {
            startReplacement(cloneWithUserData(track));
            notifyStateChanged(MusicStateChange.TRACK_END);
            return;
        }

        if (repeatMode == RepeatMode.ALL && track != null) {
            queue.offer(cloneWithUserData(track));
            queuedCount++;
        }

        AudioTrack next = pollPlayable();
        if (next != null) {
            startReplacement(next);
            notifyStateChanged(MusicStateChange.TRACK_END);
            return;
        }

        startReplacement(null);
        if (queue.isEmpty()) notifyQueueExhausted(track);
        notifyStateChanged(MusicStateChange.TRACK_END);
    }

    private void notifyQueueExhausted(AudioTrack track) {
        if (repeatMode == RepeatMode.OFF && track != null) {
            Consumer<AudioTrack> listener = queueExhaustedListener;
            if (listener != null) {
                listener.accept(track.makeClone());
            }
        }
    }

    @Override
    public synchronized void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        if (track == failedTrackAwaitingEnd || (player.getPlayingTrack() != track && recoveringTrack != track && scheduledTrack != track)) return;
        BiConsumer<AudioTrack, FriendlyException> listener = trackExceptionListener;
        if (listener != null) {
            listener.accept(track, exception);
        } else {
            nextTrack(false);
        }
        notifyStateChanged(MusicStateChange.RECOVERY);
    }

    @Override
    public synchronized void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        if (track == failedTrackAwaitingEnd || (player.getPlayingTrack() != track && recoveringTrack != track)) return;
        BiConsumer<AudioTrack, Long> listener = trackStuckListener;
        if (listener != null) {
            listener.accept(track, thresholdMs);
        } else {
            nextTrack(false);
        }
        notifyStateChanged(MusicStateChange.RECOVERY);
    }

    private void startReplacement(AudioTrack track) {
        cancelPendingPreparation();
        scheduledTrack = track;
        playbackGeneration++;
        clearRecoveryMarker();
        player.setPaused(false);
        if (track instanceof PlaybackPreparation preparation) {
            long generation = playbackGeneration;
            player.startTrack(null, false);
            preparation.preparePlayback(guildId).whenComplete((ignored, failure) -> {
                synchronized (TrackScheduler.this) {
                    if (scheduledTrack != track || playbackGeneration != generation) return;
                    if (failure == null) player.startTrack(track, false);
                    else {
                        Throwable cause = failure instanceof java.util.concurrent.CompletionException ? failure.getCause() : failure;
                        onTrackException(player, track, new FriendlyException("YouTube playback preparation failed.",
                                FriendlyException.Severity.COMMON, cause));
                    }
                }
            });
        } else player.startTrack(track, false);
    }

    private void cancelPendingPreparation() {
        if (scheduledTrack instanceof PlaybackPreparation preparation && player.getPlayingTrack() != scheduledTrack) {
            scheduledTrack = null;
            playbackGeneration++;
            preparation.cancelPreparation();
        }
    }

    private AudioTrack pollPlayable() {
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            AudioTrack track = iterator.next();
            if (!sourceHeld(track)) {
                iterator.remove();
                queuedCount--;
                return track;
            }
        }
        return null;
    }

    private void clearRecoveryMarker() {
        recoveryAwaitingFrame = null;
        recoveringTrack = null;
        recoveringGeneration = -1L;
    }

    private AudioTrack cloneWithUserData(AudioTrack track) {
        AudioTrack clone = track.makeClone();
        Object userData = track.getUserData();
        clone.setUserData(userData instanceof TrackLoadContext context ? context.resetRecovery() : userData);
        return clone;
    }

    private void notifyStateChanged(MusicStateChange reason) {
        Consumer<MusicStateChange> listener = this.stateListener;
        if (listener != null) {
            listener.accept(reason);
        }
    }
}
