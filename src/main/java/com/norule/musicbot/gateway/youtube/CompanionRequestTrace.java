package com.norule.musicbot.gateway.youtube;

record CompanionRequestTrace(long guildId, String requestId, String batchId, String source) {
    static final ThreadLocal<CompanionRequestTrace> CURRENT = new ThreadLocal<>();
    static CompanionRequestTrace current(long guildId) {
        var trace = CURRENT.get();
        return trace == null ? new CompanionRequestTrace(guildId, java.util.UUID.randomUUID().toString(), "-", "UNKNOWN") : trace;
    }
}
