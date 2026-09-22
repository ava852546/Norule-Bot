package com.norule.musicbot.domain.music;

import java.time.Instant;

// attemptId fences callbacks belonging to an earlier guild membership, even within the same millisecond.
public record MusicCommandChannelProvisioning(long guildId, Status status, Instant attemptedAt,
                                              String failureReason, String attemptId) {
    public enum Status {
        ATTEMPTING, SUCCESS, FAILED
    }
}
