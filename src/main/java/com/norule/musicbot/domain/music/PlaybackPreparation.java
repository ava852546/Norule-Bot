package com.norule.musicbot.domain.music;

import java.util.concurrent.CompletableFuture;

/** Network preparation before allocating a LavaPlayer playback executor. */
public interface PlaybackPreparation {
    CompletableFuture<Void> preparePlayback(long guildId);
    void cancelPreparation();
}
