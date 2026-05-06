package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;

public final class MinecraftStatusConfig {
    private static final String DEFAULT_USER_AGENT = "NoRuleBot/1.0 contact: admin@norule.me";
    private static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000;
    private static final int DEFAULT_INTERNAL_CACHE_SECONDS = 60;

    private final String userAgent;
    private final int requestTimeoutMillis;
    private final int internalCacheSeconds;

    public MinecraftStatusConfig(String userAgent, int requestTimeoutMillis, int internalCacheSeconds) {
        String normalizedUserAgent = userAgent == null ? "" : userAgent.trim();
        this.userAgent = normalizedUserAgent.isBlank() ? DEFAULT_USER_AGENT : normalizedUserAgent;
        this.requestTimeoutMillis = Math.max(1_000, requestTimeoutMillis);
        this.internalCacheSeconds = Math.max(0, internalCacheSeconds);
    }

    public MinecraftStatusConfig(BotConfig.MinecraftStatus config) {
        this(
                config == null ? DEFAULT_USER_AGENT : config.getUserAgent(),
                config == null ? DEFAULT_REQUEST_TIMEOUT_MILLIS : config.getRequestTimeoutMillis(),
                config == null ? DEFAULT_INTERNAL_CACHE_SECONDS : config.getInternalCacheSeconds()
        );
    }

    public String getUserAgent() {
        return userAgent;
    }

    public int getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public int getInternalCacheSeconds() {
        return internalCacheSeconds;
    }
}
