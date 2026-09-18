package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

final class PanelRefreshFailurePolicy {
    private static final Duration DEFAULT_LOG_COOLDOWN = Duration.ofMinutes(10);
    private static final Permission[] REQUIRED_REFRESH_PERMISSIONS = {
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_EMBED_LINKS
    };

    private final long logCooldownMillis;
    private final Map<PanelFailureKey, Long> lastWarningByFailure = new ConcurrentHashMap<>();

    PanelRefreshFailurePolicy() {
        this(DEFAULT_LOG_COOLDOWN);
    }

    PanelRefreshFailurePolicy(Duration logCooldown) {
        this.logCooldownMillis = Math.max(0L, logCooldown.toMillis());
    }

    Permission firstMissingPermission(Predicate<Permission> permissionCheck) {
        for (Permission permission : REQUIRED_REFRESH_PERMISSIONS) {
            if (!permissionCheck.test(permission)) {
                return permission;
            }
        }
        return null;
    }

    PanelFailure classify(Throwable failure) {
        if (failure instanceof InsufficientPermissionException insufficientPermission) {
            return new PanelFailure(
                    FailureDisposition.CLEAR_STATE,
                    "MISSING_PERMISSION",
                    insufficientPermission.getPermission()
            );
        }
        if (failure instanceof ErrorResponseException responseException) {
            ErrorResponse response = responseException.getErrorResponse();
            if (response == ErrorResponse.UNKNOWN_MESSAGE) {
                return new PanelFailure(FailureDisposition.CLEAR_STATE, "UNKNOWN_MESSAGE", null);
            }
            if (response == ErrorResponse.UNKNOWN_CHANNEL) {
                return new PanelFailure(FailureDisposition.CLEAR_STATE, "UNKNOWN_CHANNEL", null);
            }
            if (response == ErrorResponse.MISSING_ACCESS) {
                return new PanelFailure(FailureDisposition.CLEAR_STATE, "MISSING_ACCESS", null);
            }
            if (response == ErrorResponse.MISSING_PERMISSIONS) {
                return new PanelFailure(FailureDisposition.CLEAR_STATE, "MISSING_PERMISSIONS", null);
            }
        }
        return new PanelFailure(FailureDisposition.UNEXPECTED, "UNEXPECTED_FAILURE", null);
    }

    boolean shouldLog(long guildId, long channelId, long messageId, String reason, long nowMillis) {
        PanelFailureKey key = new PanelFailureKey(guildId, channelId, messageId, reason);
        Long previous = lastWarningByFailure.putIfAbsent(key, nowMillis);
        if (previous == null) {
            return true;
        }
        if (nowMillis - previous < logCooldownMillis) {
            return false;
        }
        return lastWarningByFailure.replace(key, previous, nowMillis);
    }

    void clearChannel(long guildId, long channelId) {
        lastWarningByFailure.keySet().removeIf(
                key -> key.guildId() == guildId && key.channelId() == channelId
        );
    }

    enum FailureDisposition {
        CLEAR_STATE,
        UNEXPECTED
    }

    record PanelFailure(FailureDisposition disposition, String reason, Permission permission) {
    }

    private record PanelFailureKey(long guildId, long channelId, long messageId, String reason) {
    }
}
