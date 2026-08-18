package com.norule.musicbot.domain.music;

import com.norule.musicbot.config.domain.MusicConfig;

import java.util.List;
import java.util.stream.Collectors;

public record YoutubeFailureReport(
        YoutubeFailureCategory category,
        YoutubeRecoveryClass recoveryClass,
        Integer httpStatus,
        List<YoutubeClientFailure> clientFailures,
        boolean allClientsFailed
) {
    public YoutubeFailureReport {
        category = category == null ? YoutubeFailureCategory.UNKNOWN : category;
        recoveryClass = recoveryClass == null ? YoutubeRecoveryClass.UNKNOWN : recoveryClass;
        clientFailures = clientFailures == null ? List.of() : List.copyOf(clientFailures);
    }

    public String errorKey() {
        return "YOUTUBE_" + category.name();
    }

    public String clientsSummary() {
        if (clientFailures.isEmpty()) {
            return "{}";
        }
        return clientFailures.stream()
                .map(failure -> failure.clientName() + ":" + failure.category().name())
                .collect(Collectors.joining(", ", "{", "}"));
    }

    public boolean allowsPlaybackRecovery(MusicConfig.Youtube.AuthMode authMode) {
        return switch (recoveryClass) {
            case RETRYABLE, CLIENT_FALLBACK_MAY_HELP, DECODER_FALLBACK_MAY_HELP -> true;
            case AUTH_MAY_HELP -> authMode != null && authMode != MusicConfig.Youtube.AuthMode.NONE;
            case CONFIGURATION_ERROR, PERMANENT, UNKNOWN -> false;
        };
    }
}
