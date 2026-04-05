package com.norule.musicbot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private void notifyStateChanged() {
        Runnable listener = this.stateListener;
        if (listener != null) {
            listener.run();
        }
    }
}
