package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.domain.RuntimeConfigSnapshot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

class CommandCooldownService {
    private final Map<String, Long> commandCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> panelButtonCooldowns = new ConcurrentHashMap<>();
    private final Supplier<RuntimeConfigSnapshot> configSupplier;

    CommandCooldownService(Supplier<RuntimeConfigSnapshot> configSupplier) {
        this.configSupplier = configSupplier;
    }

    long acquireCooldown(long userId) {
        int cooldownSeconds = Math.max(0, configSupplier.get().getCommandCooldownSeconds());
        if (cooldownSeconds <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        String key = String.valueOf(userId);
        Long nextAllowed = commandCooldowns.get(key);
        if (nextAllowed != null && nextAllowed > now) {
            return nextAllowed - now;
        }
        commandCooldowns.put(key, now + cooldownSeconds * 1000L);
        return 0;
    }

    long acquirePanelButtonCooldown(long userId) {
        int cooldownSeconds = Math.max(0, configSupplier.get().getCommandCooldownSeconds());
        if (cooldownSeconds <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        String key = String.valueOf(userId);
        Long nextAllowed = panelButtonCooldowns.get(key);
        if (nextAllowed != null && nextAllowed > now) {
            return nextAllowed - now;
        }
        panelButtonCooldowns.put(key, now + cooldownSeconds * 1000L);
        return 0;
    }

    long toCooldownSeconds(long remainingMillis) {
        return Math.max(1L, (remainingMillis + 999L) / 1000L);
    }

    void cleanupExpired(long nowMillis) {
        commandCooldowns.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= nowMillis);
        panelButtonCooldowns.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= nowMillis);
    }
}
