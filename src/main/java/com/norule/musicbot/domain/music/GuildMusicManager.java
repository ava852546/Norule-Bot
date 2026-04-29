package com.norule.musicbot.domain.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class GuildMusicManager {
    private final AudioPlayer player;
    private final TrackScheduler scheduler;
    private final AudioPlayerSendHandler sendHandler;

    public GuildMusicManager(AudioPlayerManager manager,
                             Runnable stateListener,
                             Consumer<AudioTrack> queueExhaustedListener,
                             Consumer<AudioTrack> trackStartListener,
                             Consumer<AudioTrack> trackEndListener,
                             BiConsumer<AudioTrack, FriendlyException> trackExceptionListener) {
        this.player = manager.createPlayer();
        this.scheduler = new TrackScheduler(player);
        this.scheduler.setStateListener(stateListener);
        this.scheduler.setQueueExhaustedListener(queueExhaustedListener);
        this.scheduler.setTrackStartListener(trackStartListener);
        this.scheduler.setTrackEndListener(trackEndListener);
        this.scheduler.setTrackExceptionListener(trackExceptionListener);
        this.sendHandler = new AudioPlayerSendHandler(player);
        player.addListener(scheduler);
    }

    public AudioPlayer getPlayer() {
        return player;
    }

    public TrackScheduler getScheduler() {
        return scheduler;
    }

    public AudioPlayerSendHandler getSendHandler() {
        return sendHandler;
    }
}


