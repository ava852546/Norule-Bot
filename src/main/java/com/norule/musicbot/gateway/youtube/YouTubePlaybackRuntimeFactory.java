package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.CipherPolicy;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.cipher.LocalSignatureCipherManager;
import dev.lavalink.youtube.cipher.RemoteCipherManager;
import dev.lavalink.youtube.clients.AndroidMusicWithThumbnail;
import dev.lavalink.youtube.clients.AndroidVrWithThumbnail;
import dev.lavalink.youtube.clients.IosWithThumbnail;
import dev.lavalink.youtube.clients.MWebWithThumbnail;
import dev.lavalink.youtube.clients.MusicWithThumbnail;
import dev.lavalink.youtube.clients.Tv;
import dev.lavalink.youtube.clients.TvHtml5SimplyWithThumbnail;
import dev.lavalink.youtube.clients.WebEmbeddedWithThumbnail;
import dev.lavalink.youtube.clients.WebWithThumbnail;
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
import java.util.ArrayList;
import java.util.List;

public final class YouTubePlaybackRuntimeFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(YouTubePlaybackRuntimeFactory.class);

    private YouTubePlaybackRuntimeFactory() {
    }

    public static YouTubePlaybackTrackFactory create(MusicConfig.Youtube config, CipherPolicy policy) {
        return create(config, policy, System::getenv);
    }

    static YouTubePlaybackTrackFactory create(MusicConfig.Youtube config, CipherPolicy policy,
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
        LOGGER.info("[NoRule] YouTube playback configuration: configuredBackend={} youtubeSourceCipher={} cipherPolicy={} configurationSource=music.cipher.enabled",
                backend, policy.isAllowed(), policy.isAllowed() ? "ALLOWED" : "HARD_DISABLED");
        if (backend == YouTubePlaybackBackend.YOUTUBE_SOURCE) {
            return YouTubePlaybackTrackFactory.youtubeSource();
        }

        MusicConfig.Youtube.Companion companion = effectiveConfig.getCompanion();
        boolean enabled = booleanOverride(env.apply("YOUTUBE_COMPANION_ENABLED"), companion.isEnabled());
        boolean fallbackToSource = booleanOverride(
                env.apply("YOUTUBE_COMPANION_FALLBACK_TO_SOURCE"),
                companion.isFallbackToSource()
        );
        LOGGER.info("[NoRule] YouTube playback fallback: configuredBackend={} fallbackBackend={}",
                backend, fallbackToSource ? "YOUTUBE_SOURCE" : "NONE");
        String url = firstNonBlank(env.apply("YOUTUBE_COMPANION_URL"), companion.getUrl());
        SecretSelection secretSelection = selectSecret(
                env.apply("YOUTUBE_COMPANION_SECRET"),
                companion.getSecret()
        );
        String secret = secretSelection.value();
        LOGGER.info(
                "[NoRule] Invidious Companion authentication: secretConfigured={} secretLength={} source={}",
                !secret.isBlank(),
                secret.length(),
                secretSelection.source()
        );
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
            LOGGER.info("[NoRule] Invidious Companion player authentication: not probed");
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

    public static YoutubeAudioSourceManager createSource(MusicConfig.Cipher cipherConfig, CipherPolicy policy,
                                                         MusicConfig.Youtube.AuthMode authMode,
                                                         YouTubePlaybackTrackFactory trackFactory) {
        List<dev.lavalink.youtube.clients.skeleton.Client> clients = new ArrayList<>();
        clients.add(new MusicWithThumbnail());
        if (authMode == MusicConfig.Youtube.AuthMode.OAUTH) {
            clients.add(new Tv());
        }
        clients.add(new WebWithThumbnail());
        clients.add(new MWebWithThumbnail());
        clients.add(new WebEmbeddedWithThumbnail());
        clients.add(new TvHtml5SimplyWithThumbnail());
        clients.add(new AndroidVrWithThumbnail());
        clients.add(new AndroidMusicWithThumbnail());
        clients.add(new IosWithThumbnail());
        // Capture endpoint settings without creating a client. Runtime policy controls use,
        // including a later disabled -> enabled reload.
        String remoteCipherUrl = firstNonBlank(
                System.getenv("YOUTUBE_CIPHER_SERVER"),
                System.getenv("YOUTUBE_REMOTE_CIPHER_URL"),
                cipherConfig.getServer()
        );
        dev.lavalink.youtube.clients.skeleton.Client[] clientArray =
                clients.toArray(dev.lavalink.youtube.clients.skeleton.Client[]::new);
        String remoteCipherPassword = firstNonBlank(
                System.getenv("YOUTUBE_CIPHER_PASSWORD"),
                System.getenv("YOUTUBE_REMOTE_CIPHER_PASSWORD"),
                cipherConfig.getPassword()
        );
        String remoteCipherUserAgent = firstNonBlank(
                System.getenv("YOUTUBE_CIPHER_USER_AGENT"),
                System.getenv("YOUTUBE_REMOTE_CIPHER_USER_AGENT"),
                cipherConfig.getUserAgent()
        );
        // Version 1.18.2 always constructs a local cipher; replace it before publication.
        // No remote client is constructed here, and the unused local instance performs no I/O.
        YoutubeAudioSourceManager source = new BackendYoutubeAudioSourceManager(trackFactory, clientArray);
        source.getContextFilter().setCipherConfig(remoteCipherPassword, remoteCipherUserAgent,
                dev.lavalink.youtube.YoutubeSource.VERSION);
        source.setCipherManager(new PolicyCipherManager(policy, () -> remoteCipherUrl == null
                ? new LocalSignatureCipherManager() : new RemoteCipherManager(remoteCipherUrl)));
        return source;
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

    static SecretSelection selectSecret(String environmentSecret, String configSecret) {
        if (environmentSecret != null && !environmentSecret.isBlank()) {
            return new SecretSelection(environmentSecret.trim(), SecretSource.ENVIRONMENT);
        }
        if (configSecret != null && !configSecret.isBlank()) {
            return new SecretSelection(configSecret.trim(), SecretSource.CONFIG);
        }
        return new SecretSelection("", SecretSource.NONE);
    }

    enum SecretSource {
        ENVIRONMENT,
        CONFIG,
        NONE
    }

    record SecretSelection(String value, SecretSource source) {
    }
}
