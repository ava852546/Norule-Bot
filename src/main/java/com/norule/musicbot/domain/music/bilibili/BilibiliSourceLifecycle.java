package com.norule.musicbot.domain.music.bilibili;

import com.norule.musicbot.config.domain.MusicConfig;

public interface BilibiliSourceLifecycle {
    void updateConfig(MusicConfig.Bilibili config);

    void setPlaylistPageCount(int pageCount);

    String breakerState();

    void cleanupExpiredMetadata();
}
