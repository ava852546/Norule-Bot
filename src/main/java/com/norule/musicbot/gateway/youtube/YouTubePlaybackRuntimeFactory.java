package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.FallbackYouTubePlaybackResolver;
import com.norule.musicbot.domain.music.YouTubePlaybackBackend;
import com.norule.musicbot.domain.music.YouTubePlaybackException;
import com.norule.musicbot.domain.music.YouTubePlaybackResolver;
import com.norule.musicbot.domain.music.YouTubePlaybackTrackFactory;
import com.norule.musicbot.domain.music.YoutubeFailureCategory;
import com.norule.musicbot.domain.music.YoutubeSourcePlaybackResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

public final class YouTubePlaybackRuntimeFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(YouTubePlaybackRuntimeFactory.class);

    private YouTubePlaybackRuntimeFactory() {
    }

    public static YouTubePlaybackTrackFactory create(MusicConfig.Youtube config) {
        return create(config, System::getenv);
    }

    static YouTubePlaybackTrackFactory create(MusicConfig.Youtube config,
                                              Function<String, String> environment) {
        MusicConfig.Youtube effectiveConfig = config == null
                ? MusicConfig.defaultValues().getYoutube()
                : config;
        Function<String, String> env = environment == null ? ignored -> null : environment;
        String backendValue = firstNonBlank(
                env.apply("YOUTUBE_PLAYBACK_BACKEND"),
                effectiveConfig.getConfiguredPlaybackBackend()
        );
        if (!YouTubePlaybackBackend.isRecognized(backendValue)) {
            LOGGER.warn(
                    "[NoRule] Unknown YouTube playback backend '{}'; falling back to YOUTUBE_SOURCE.",
                    backendValue
            );
        }
        YouTubePlaybackBackend backend = YouTubePlaybackBackend.parse(backendValue);
        LOGGER.info("[NoRule] YouTube playback backend: {}", backend);
        if (backend == YouTubePlaybackBackend.YOUTUBE_SOURCE) {
            return YouTubePlaybackTrackFactory.youtubeSource();
        }

        MusicConfig.Youtube.Companion companion = effectiveConfig.getCompanion();
        boolean enabled = booleanOverride(env.apply("YOUTUBE_COMPANION_ENABLED"), companion.isEnabled());
        boolean fallbackToSource = booleanOverride(
                env.apply("YOUTUBE_COMPANION_FALLBACK_TO_SOURCE"),
                companion.isFallbackToSource()
        );
        String url = firstNonBlank(env.apply("YOUTUBE_COMPANION_URL"), companion.getUrl());
        String secret = firstNonBlank(env.apply("YOUTUBE_COMPANION_SECRET"), companion.getSecret());
        int connectTimeoutMillis = intOverride(
                env.apply("YOUTUBE_COMPANION_CONNECT_TIMEOUT_MILLIS"),
                companion.getConnectTimeoutMillis()
        );
        int requestTimeoutMillis = intOverride(
                env.apply("YOUTUBE_COMPANION_REQUEST_TIMEOUT_MILLIS"),
                companion.getRequestTimeoutMillis()
        );

        YouTubePlaybackResolver companionResolver;
        CompanionPlaybackClient client = null;
        String configurationFailure = null;
        if (!enabled) {
            configurationFailure = "disabled by configuration";
        } else if (!CompanionPlaybackClient.isValidSecret(secret)) {
            configurationFailure = "secret must contain exactly 16 alphanumeric characters";
        } else {
            try {
                client = new CompanionPlaybackClient(
                        url,
                        secret,
                        connectTimeoutMillis,
                        requestTimeoutMillis
                );
            } catch (IllegalArgumentException invalidUrl) {
                configurationFailure = "invalid Companion URL";
            }
        }

        boolean configured = client != null;
        LOGGER.info("[NoRule] Invidious Companion configured: {}", configured);
        if (client == null) {
            String failureDetail = configurationFailure == null ? "configuration unavailable" : configurationFailure;
            LOGGER.warn("[NoRule] Invidious Companion unavailable: {}", failureDetail);
            companionResolver = videoId -> {
                throw new YouTubePlaybackException(
                        YoutubeFailureCategory.COMPANION_UNAVAILABLE,
                        "Invidious Companion is unavailable: " + failureDetail
                );
            };
        } else {
            CompanionPlaybackClient.HealthResult health = client.healthCheck();
            if (health.healthy()) {
                LOGGER.info("[NoRule] Invidious Companion connected: true");
                LOGGER.info("[NoRule] Invidious Companion health: OK");
            } else {
                LOGGER.warn("[NoRule] Invidious Companion unavailable: {}", health.detail());
            }
            companionResolver = new CompanionPlaybackResolver(client);
        }

        YouTubePlaybackResolver selectedResolver = fallbackToSource
                ? new FallbackYouTubePlaybackResolver(companionResolver, new YoutubeSourcePlaybackResolver())
                : companionResolver;
        return new CompanionYouTubePlaybackTrackFactory(
                selectedResolver,
                connectTimeoutMillis,
                requestTimeoutMillis
        );
    }

    private static boolean booleanOverride(String rawValue, boolean fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }
        String normalized = rawValue.trim();
        return "true".equalsIgnoreCase(normalized)
                || "1".equals(normalized)
                || "yes".equalsIgnoreCase(normalized)
                || "on".equalsIgnoreCase(normalized);
    }

    private static int intOverride(String rawValue, int fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return Math.max(1, fallback);
        }
        try {
            return Math.max(1, Integer.parseInt(rawValue.trim()));
        } catch (NumberFormatException ignored) {
            return Math.max(1, fallback);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
