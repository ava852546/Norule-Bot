package com.norule.musicbot.domain.music;

/** Carries request identity through LavaPlayer's asynchronous metadata loader. */
public interface SourceLoadTracing {
    String traceLoad(String identifier, long guildId, String requestId, String source);
    void cancelGuildLoads(long guildId);
    void closeLoads();
}
