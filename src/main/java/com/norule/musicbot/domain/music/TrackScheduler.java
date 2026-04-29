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
    private volatile Runnable stateListener;
    private volatile Consumer<AudioTrack> queueExhaustedListener;
    private volatile Consumer<AudioTrack> trackStartListener;
    private volatile Consumer<AudioTrack> trackEndListener;
    private volatile BiConsumer<AudioTrack, FriendlyException> trackExceptionListener;

    public TrackScheduler(AudioPlayer player) {
        this.player = player;
    }

    public void queue(AudioTrack track) {
        if (!player.startTrack(track, true)) {
            queue.offer(track);
        }
        notifyStateChanged();
    }

    public void nextTrack() {
        player.startTrack(queue.poll(), false);
        notifyStateChanged();
    }

    public void clear() {
        queue.clear();
        notifyStateChanged();
    }

    public int shuffleQueue() {
        List<AudioTrack> tracks = new ArrayList<>(queue);
        if (tracks.size() <= 1) {
            return tracks.size();
        }
        Collections.shuffle(tracks);
        queue.clear();
        for (AudioTrack track : tracks) {
            queue.offer(track);
        }
        notifyStateChanged();
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
        notifyStateChanged();
    }

    public List<AudioTrack> snapshotQueue() {
        return new ArrayList<>(queue);
    }

    public void setStateListener(Runnable stateListener) {
        this.stateListener = stateListener;
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

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        Consumer<AudioTrack> startListener = trackStartListener;
        if (startListener != null && track != null) {
            startListener.accept(track);
        }
        notifyStateChanged();
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        Consumer<AudioTrack> endListener = trackEndListener;
        if (endListener != null && track != null) {
            endListener.accept(track);
        }
        if (!endReason.mayStartNext) {
            notifyStateChanged();
            return;
        }

        if (repeatMode == RepeatMode.SINGLE && track != null) {
            player.startTrack(track.makeClone(), false);
            notifyStateChanged();
            return;
        }

        if (repeatMode == RepeatMode.ALL && track != null) {
            queue.offer(track.makeClone());
        }

        AudioTrack next = queue.poll();
        if (next != null) {
            player.startTrack(next, false);
            notifyStateChanged();
            return;
        }

        player.startTrack(null, false);
        if (repeatMode == RepeatMode.OFF && track != null) {
            Consumer<AudioTrack> listener = queueExhaustedListener;
            if (listener != null) {
                listener.accept(track.makeClone());
            }
        }
        notifyStateChanged();
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        BiConsumer<AudioTrack, FriendlyException> listener = trackExceptionListener;
        if (listener != null) {
            listener.accept(track, exception);
        }
        nextTrack();
        notifyStateChanged();
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        BiConsumer<AudioTrack, FriendlyException> listener = trackExceptionListener;
        if (listener != null) {
            listener.accept(track, new FriendlyException("Track got stuck", FriendlyException.Severity.SUSPICIOUS, null));
        }
        nextTrack();
        notifyStateChanged();
    }

    private void notifyStateChanged() {
        Runnable listener = this.stateListener;
        if (listener != null) {
            listener.run();
        }
    }
}


