package com.norule.musicbot.discord.bot.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

class TransientStateCleanupService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransientStateCleanupService.class);

    private final MusicCommandService service;
    private final CommandCooldownService cooldownService;

    TransientStateCleanupService(MusicCommandService service, CommandCooldownService cooldownService) {
        this.service = service;
        this.cooldownService = cooldownService;
    }

    void runSafely() {
        try {
            run();
        } catch (RuntimeException ignored) {
            // Keep scheduled cleanup alive even if a transient repository state throws.
            LOGGER.debug("cleanupTransientState failed", ignored);
        }
    }

    private void run() {
        Instant now = Instant.now();
        long nowMillis = System.currentTimeMillis();
        service.deleteMessagesCommandHandler().cleanupExpiredRequests(now);
        service.warningCommandHandler().cleanupExpiredRequests(now);
        service.privateRoomSettingsCommandHandler().cleanupExpiredRequests(now);
        service.languageMenuHandler().cleanupExpiredRequests(now);
        service.numberChainMenuHandler().cleanupExpiredRequests(now);
        service.wordChainMenuHandler().cleanupExpiredRequests(now);
        cooldownService.cleanupExpired(nowMillis);
        service.playbackCommandHandler().cleanupExpiredRequests(now);
        service.playlistCommandHandler().cleanupExpiredRequests(now);
        service.settingsCommandHandler().cleanupExpiredRequests(now);
        service.musicService().cleanupTransientCaches(nowMillis);
    }
}
