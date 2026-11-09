package com.norule.musicbot.domain.music.bilibili;

import com.norule.musicbot.config.domain.MusicConfig;

public interface BilibiliSourceLifecycle {
    void updateConfig(MusicConfig.Bilibili config);

    void setPlaylistPageCount(int pageCount);

    String breakerState();

    void cleanupExpiredMetadata();

    default long nextMetadataDelayMillis() { return 0L; }

    default long nextPlaybackDelayMillis() { return nextMetadataDelayMillis(); }

    default long nextRedirectDelayMillis(String identifier) { return 0L; }

    default void traceNextLoad(String identifier, String trace) {
        // Optional diagnostics hook; compatibility sources do not expose HTTP tracing.
    }
}
