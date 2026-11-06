package com.norule.musicbot.domain.monitoring;

import java.time.Instant;

public record VoiceAutoLeaveEvent(Instant timestamp, String event, String reason,
        long guildId, String guildName, long channelId, String channelName,
        Long humanCount, Long idleSeconds, Long delaySeconds) {}
