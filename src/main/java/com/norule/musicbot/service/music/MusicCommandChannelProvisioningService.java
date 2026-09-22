package com.norule.musicbot.service.music;

import com.norule.musicbot.domain.music.MusicCommandChannelProvisioning;
import com.norule.musicbot.domain.music.MusicCommandChannelProvisioning.Status;
import com.norule.musicbot.storage.sqlite.MusicCommandChannelProvisioningSqliteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

public final class MusicCommandChannelProvisioningService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicCommandChannelProvisioningService.class);
    private final MusicCommandChannelProvisioningSqliteRepository repository;

    public MusicCommandChannelProvisioningService(MusicCommandChannelProvisioningSqliteRepository repository) {
        this.repository = repository;
    }

    public MusicCommandChannelProvisioning tryStart(long guildId) {
        MusicCommandChannelProvisioning attempt = new MusicCommandChannelProvisioning(
                guildId, Status.ATTEMPTING, Instant.now(), null, UUID.randomUUID().toString());
        if (repository.tryInsert(attempt)) {
            LOGGER.info("Music command channel provisioning started: guildId={}", guildId);
            return attempt;
        }
        MusicCommandChannelProvisioning existing = repository.find(guildId);
        LOGGER.info("Music command channel provisioning skipped: guildId={} status={} reason=ALREADY_ATTEMPTED",
                guildId, existing == null ? Status.ATTEMPTING : existing.status());
        return null;
    }

    public boolean isAttempting(MusicCommandChannelProvisioning attempt) {
        MusicCommandChannelProvisioning current = repository.find(attempt.guildId());
        return current != null && current.status() == Status.ATTEMPTING
                && current.attemptId().equals(attempt.attemptId());
    }

    public boolean succeed(MusicCommandChannelProvisioning attempt) {
        if (!repository.complete(attempt, Status.SUCCESS, null)) {
            return false;
        }
        LOGGER.info("Music command channel provisioning succeeded: guildId={}", attempt.guildId());
        return true;
    }

    public void fail(MusicCommandChannelProvisioning attempt, Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        reason = reason.replaceAll("[\\r\\n\\t]", " ");
        reason = reason.substring(0, Math.min(reason.length(), 256));
        if (repository.complete(attempt, Status.FAILED, reason)) {
            LOGGER.warn("Music command channel provisioning failed: guildId={} reason={}", attempt.guildId(), reason);
        }
    }

    public void guildLeft(long guildId) {
        repository.delete(guildId);
        LOGGER.info("Music command channel provisioning state cleared: guildId={} reason=GUILD_LEFT", guildId);
    }
}
