package com.norule.musicbot.domain.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.nio.ByteBuffer;

public class AudioPlayerSendHandler implements AudioSendHandler {
    private final AudioPlayer audioPlayer;
    private AudioFrame lastFrame;
    private final TrackScheduler scheduler;

    public AudioPlayerSendHandler(AudioPlayer audioPlayer) {
        this(audioPlayer, null);
    }

    public AudioPlayerSendHandler(AudioPlayer audioPlayer, TrackScheduler scheduler) {
        this.audioPlayer = audioPlayer;
        this.scheduler = scheduler;
    }

    @Override
    public boolean canProvide() {
        if (scheduler == null) {
            lastFrame = audioPlayer.provide();
        } else {
            // Serialise the ordinary nonblocking provide with track replacement so that
            // an old buffered frame cannot confirm recovery of a different track.
            synchronized (scheduler) {
                var track = audioPlayer.getPlayingTrack();
                lastFrame = audioPlayer.provide();
                if (lastFrame != null && !lastFrame.isTerminator() && lastFrame.getDataLength() > 0) {
                    scheduler.observeAudioFrame(track);
                }
            }
        }
        return lastFrame != null;
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        return ByteBuffer.wrap(lastFrame.getData());
    }

    @Override
    public boolean isOpus() {
        return true;
    }
}



