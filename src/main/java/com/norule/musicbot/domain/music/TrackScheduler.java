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

    public TrackScheduler(AudioPlayer player) {
        this.player = player;
    }

    public synchronized void queue(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            queue.offer(track);
        } else {
            playbackGeneration++;
            clearRecoveryMarker();
        }
        notifyStateChanged(MusicStateChange.QUEUE_CHANGED);
    }

    public synchronized void nextTrack() {
        startReplacement(queue.poll());
        notifyStateChanged(MusicStateChange.QUEUE_CHANGED);
    }

    public synchronized void clear() {
        queue.clear();
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
        for (AudioTrack track : tracks) {
            queue.offer(track);
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

    public synchronized boolean isActiveTrack(AudioTrack track, long expectedGeneration) {
        return track != null
                && (player.getPlayingTrack() == track || recoveringTrack == track)
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
        startReplacement(replacement);
        notifyStateChanged(MusicStateChange.RECOVERY);
        return true;
    }

    public synchronized void skipIfCurrent(AudioTrack expectedTrack, long expectedGeneration) {
        if (isActiveTrack(expectedTrack, expectedGeneration)) {
            failedTrackAwaitingEnd = expectedTrack;
            nextTrack();
        }
    }

    public synchronized void invalidatePlaybackGeneration() {
        playbackGeneration++;
        clearRecoveryMarker();
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        Consumer<AudioTrack> startListener = trackStartListener;
        if (startListener != null && track != null) {
            startListener.accept(track);
        }
        notifyStateChanged(MusicStateChange.TRACK_START);
    }

    @Override
    public synchronized void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        Consumer<AudioTrack> endListener = trackEndListener;
        if (endListener != null && track != null) {
            endListener.accept(track);
        }
        if (!endReason.mayStartNext) {
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
        }

        AudioTrack next = queue.poll();
        if (next != null) {
            startReplacement(next);
            notifyStateChanged(MusicStateChange.TRACK_END);
            return;
        }

        startReplacement(null);
        if (repeatMode == RepeatMode.OFF && track != null) {
            Consumer<AudioTrack> listener = queueExhaustedListener;
            if (listener != null) {
                listener.accept(track.makeClone());
            }
        }
        notifyStateChanged(MusicStateChange.TRACK_END);
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        BiConsumer<AudioTrack, FriendlyException> listener = trackExceptionListener;
        if (listener != null) {
            listener.accept(track, exception);
        } else {
            nextTrack();
        }
        notifyStateChanged(MusicStateChange.RECOVERY);
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        BiConsumer<AudioTrack, Long> listener = trackStuckListener;
        if (listener != null) {
            listener.accept(track, thresholdMs);
        } else {
            nextTrack();
        }
        notifyStateChanged(MusicStateChange.RECOVERY);
    }

    private void startReplacement(AudioTrack track) {
        playbackGeneration++;
        clearRecoveryMarker();
        player.setPaused(false);
        player.startTrack(track, false);
    }

    private void clearRecoveryMarker() {
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



