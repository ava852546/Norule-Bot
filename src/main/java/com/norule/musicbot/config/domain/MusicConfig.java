package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.domain.music.YouTubePlaybackBackend;

public final class MusicConfig {
    public static final class Audio {
        public static final class DirectHttp {
            private final boolean enabled;
            private final int connectTimeoutMillis;
            private final int readTimeoutMillis;
            private final int maxRedirects;
            private final java.util.List<String> allowedHosts;

            public DirectHttp(boolean enabled,
                              int connectTimeoutMillis,
                              int readTimeoutMillis,
                              int maxRedirects,
                              java.util.List<String> allowedHosts) {
                this.enabled = enabled;
                this.connectTimeoutMillis = Math.max(1, connectTimeoutMillis);
                this.readTimeoutMillis = Math.max(1, readTimeoutMillis);
                this.maxRedirects = Math.max(0, maxRedirects);
                this.allowedHosts = allowedHosts == null ? java.util.List.of() : java.util.List.copyOf(allowedHosts);
            }

            static DirectHttp fromLegacy(BotConfig.Music.Audio.DirectHttp legacy) {
                BotConfig.Music.Audio.DirectHttp value = legacy == null
                        ? BotConfig.Music.Audio.DirectHttp.defaultValues()
                        : legacy;
                return new DirectHttp(
                        value.isEnabled(),
                        value.getConnectTimeoutMillis(),
                        value.getReadTimeoutMillis(),
                        value.getMaxRedirects(),
                        value.getAllowedHosts()
                );
            }

            public boolean isEnabled() { return enabled; }
            public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
            public int getReadTimeoutMillis() { return readTimeoutMillis; }
            public int getMaxRedirects() { return maxRedirects; }
            public java.util.List<String> getAllowedHosts() { return allowedHosts; }
        }

        public static final class Recovery {
            private final boolean enabled;
            private final int maxStuckRetries;
            private final int resumeRewindMillis;
            private final int stuckThresholdMillis;

            public Recovery(boolean enabled,
                            int maxStuckRetries,
                            int resumeRewindMillis,
                            int stuckThresholdMillis) {
                this.enabled = enabled;
                this.maxStuckRetries = Math.max(0, maxStuckRetries);
                this.resumeRewindMillis = Math.max(0, resumeRewindMillis);
                this.stuckThresholdMillis = Math.max(1, stuckThresholdMillis);
            }

            static Recovery fromLegacy(BotConfig.Music.Audio.Recovery legacy) {
                BotConfig.Music.Audio.Recovery value = legacy == null
                        ? BotConfig.Music.Audio.Recovery.defaultValues()
                        : legacy;
                return new Recovery(
                        value.isEnabled(),
                        value.getMaxStuckRetries(),
                        value.getResumeRewindMillis(),
                        value.getStuckThresholdMillis()
                );
            }

            public boolean isEnabled() { return enabled; }
            public int getMaxStuckRetries() { return maxStuckRetries; }
            public int getResumeRewindMillis() { return resumeRewindMillis; }
            public int getStuckThresholdMillis() { return stuckThresholdMillis; }
        }

        private final DirectHttp directHttp;
        private final Recovery recovery;

        public Audio(DirectHttp directHttp, Recovery recovery) {
            this.directHttp = directHttp == null ? DirectHttp.fromLegacy(null) : directHttp;
            this.recovery = recovery == null ? Recovery.fromLegacy(null) : recovery;
        }

        static Audio fromLegacy(BotConfig.Music.Audio legacy) {
            BotConfig.Music.Audio value = legacy == null ? BotConfig.Music.Audio.defaultValues() : legacy;
            return new Audio(
                    DirectHttp.fromLegacy(value.getDirectHttp()),
                    Recovery.fromLegacy(value.getRecovery())
            );
        }

        public DirectHttp getDirectHttp() { return directHttp; }
        public Recovery getRecovery() { return recovery; }
    }

    public static final class Oauth {
        private final boolean enabled;
        private final String refreshToken;

        public Oauth(boolean enabled, String refreshToken) {
            this.enabled = enabled;
            this.refreshToken = refreshToken == null ? "" : refreshToken;
        }

        static Oauth fromLegacy(BotConfig.Music.Oauth legacy) {
            BotConfig.Music.Oauth value = legacy == null
                    ? BotConfig.Music.defaultValues().getOauth()
                    : legacy;
            return new Oauth(value.isEnabled(), value.getRefreshToken());
        }

        public boolean isEnabled() { return enabled; }
        public String getRefreshToken() { return refreshToken; }
    }

    public static final class Cipher {
        private final boolean enabled;
        private final String server;
        private final String password;
        private final String userAgent;

        public Cipher(boolean enabled, String server, String password, String userAgent) {
            this.enabled = enabled;
            this.server = server == null ? "" : server;
            this.password = password == null ? "" : password;
            this.userAgent = userAgent == null ? "" : userAgent;
        }

        static Cipher fromLegacy(BotConfig.Music.Cipher legacy) {
            BotConfig.Music.Cipher value = legacy == null
                    ? BotConfig.Music.defaultValues().getCipher()
                    : legacy;
            return new Cipher(
                    value.isEnabled(),
                    value.getServer(),
                    value.getPassword(),
                    value.getUserAgent()
            );
        }

        public boolean isEnabled() { return enabled; }
        public String getServer() { return server; }
        public String getPassword() { return password; }
        public String getUserAgent() { return userAgent; }
    }

    public static final class Youtube {
        private final YouTubePlaybackBackend playbackBackend;
        private final String configuredPlaybackBackend;
        private final Companion companion;
        private final StrictPrecheck strictPrecheck;

        private Youtube(YouTubePlaybackBackend playbackBackend,
                       String configuredPlaybackBackend,
                       Companion companion,
                       StrictPrecheck strictPrecheck) {
            this.playbackBackend = playbackBackend == null
                    ? YouTubePlaybackBackend.YOUTUBE_SOURCE
                    : playbackBackend;
            this.configuredPlaybackBackend = configuredPlaybackBackend == null ? "" : configuredPlaybackBackend;
            this.companion = companion == null ? Companion.fromLegacy(null) : companion;
            this.strictPrecheck = strictPrecheck == null ? StrictPrecheck.fromLegacy(null) : strictPrecheck;
        }

        public static Youtube fromLegacy(BotConfig.Music.Youtube legacy) {
            BotConfig.Music.Youtube value = legacy == null ? BotConfig.Music.Youtube.defaultValues() : legacy;
            String configuredBackend = value.getPlaybackBackend();
            return new Youtube(
                    YouTubePlaybackBackend.parse(configuredBackend),
                    configuredBackend,
                    Companion.fromLegacy(value.getCompanion()),
                    StrictPrecheck.fromLegacy(value.getStrictPrecheck())
            );
        }

        public YouTubePlaybackBackend getPlaybackBackend() { return playbackBackend; }
        public String getConfiguredPlaybackBackend() { return configuredPlaybackBackend; }
        public Companion getCompanion() { return companion; }
        public StrictPrecheck getStrictPrecheck() { return strictPrecheck; }

        public static final class Companion {
            private final boolean enabled;
            private final String url;
            private final String secret;
            private final boolean fallbackToSource;
            private final int connectTimeoutMillis;
            private final int requestTimeoutMillis;

            public Companion(boolean enabled,
                             String url,
                             String secret,
                             boolean fallbackToSource,
                             int connectTimeoutMillis,
                             int requestTimeoutMillis) {
                this.enabled = enabled;
                this.url = url == null ? "" : url;
                this.secret = secret == null ? "" : secret;
                this.fallbackToSource = fallbackToSource;
                this.connectTimeoutMillis = Math.max(1, connectTimeoutMillis);
                this.requestTimeoutMillis = Math.max(1, requestTimeoutMillis);
            }

            static Companion fromLegacy(BotConfig.Music.Youtube.Companion legacy) {
                BotConfig.Music.Youtube.Companion value = legacy == null
                        ? BotConfig.Music.Youtube.defaultValues().getCompanion()
                        : legacy;
                return new Companion(
                        value.isEnabled(),
                        value.getUrl(),
                        value.getSecret(),
                        value.isFallbackToSource(),
                        value.getConnectTimeoutMillis(),
                        value.getRequestTimeoutMillis()
                );
            }

            public boolean isEnabled() { return enabled; }
            public String getUrl() { return url; }
            public String getSecret() { return secret; }
            public boolean isFallbackToSource() { return fallbackToSource; }
            public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
            public int getRequestTimeoutMillis() { return requestTimeoutMillis; }
        }

        public enum AuthMode {
            NONE,
            POT,
            OAUTH;

            static AuthMode from(String value) {
                if (value == null || value.isBlank()) {
                    return NONE;
                }
                try {
                    return AuthMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    return NONE;
                }
            }
        }

        public static final class StrictPrecheck {
            private final boolean enabled;
            private final int cacheTtlHours;
            private final int playableTtlHours;
            private final int temporaryFailureTtlMinutes;
            private final int permanentFailureTtlHours;
            private final int timeoutMillis;
            private final String lavalinkBaseUrl;
            private final String lavalinkPassword;

            public StrictPrecheck(boolean enabled,
                                  int cacheTtlHours,
                                  int timeoutMillis,
                                  String lavalinkBaseUrl,
                                  String lavalinkPassword) {
                this(enabled, cacheTtlHours, cacheTtlHours, 10, cacheTtlHours,
                        timeoutMillis, lavalinkBaseUrl, lavalinkPassword);
            }

            public StrictPrecheck(boolean enabled,
                                  int cacheTtlHours,
                                  int playableTtlHours,
                                  int temporaryFailureTtlMinutes,
                                  int permanentFailureTtlHours,
                                  int timeoutMillis,
                                  String lavalinkBaseUrl,
                                  String lavalinkPassword) {
                this.enabled = enabled;
                this.cacheTtlHours = Math.max(1, cacheTtlHours);
                this.playableTtlHours = Math.max(1, playableTtlHours);
                this.temporaryFailureTtlMinutes = Math.max(1, temporaryFailureTtlMinutes);
                this.permanentFailureTtlHours = Math.max(1, permanentFailureTtlHours);
                this.timeoutMillis = Math.max(1, timeoutMillis);
                this.lavalinkBaseUrl = lavalinkBaseUrl == null ? "" : lavalinkBaseUrl;
                this.lavalinkPassword = lavalinkPassword == null ? "" : lavalinkPassword;
            }

            public static StrictPrecheck fromLegacy(BotConfig.Music.Youtube.StrictPrecheck legacy) {
                BotConfig.Music.Youtube.StrictPrecheck value =
                        legacy == null ? BotConfig.Music.Youtube.StrictPrecheck.defaultValues() : legacy;
                return new StrictPrecheck(
                        value.isEnabled(),
                        value.getCacheTtlHours(),
                        value.getPlayableTtlHours(),
                        value.getTemporaryFailureTtlMinutes(),
                        value.getPermanentFailureTtlHours(),
                        value.getTimeoutMillis(),
                        value.getLavalinkBaseUrl(),
                        value.getLavalinkPassword()
                );
            }

            public boolean isEnabled() { return enabled; }
            public int getCacheTtlHours() { return cacheTtlHours; }
            public int getPlayableTtlHours() { return playableTtlHours; }
            public int getTemporaryFailureTtlMinutes() { return temporaryFailureTtlMinutes; }
            public int getPermanentFailureTtlHours() { return permanentFailureTtlHours; }
            public int getTimeoutMillis() { return timeoutMillis; }
            public String getLavalinkBaseUrl() { return lavalinkBaseUrl; }
            public String getLavalinkPassword() { return lavalinkPassword; }
        }
    }

    public static final class Spotify {
        private final boolean enabled;
        private final String clientId;
        private final String clientSecret;
        private final String spDc;
        private final String countryCode;
        private final boolean preferAnonymousToken;
        private final int playlistMaxTracks;
        private final int playlistLoadCooldownSeconds;

        public Spotify(boolean enabled,
                       String clientId,
                       String clientSecret,
                       String spDc,
                       String countryCode,
                       boolean preferAnonymousToken,
                       int playlistMaxTracks,
                       int playlistLoadCooldownSeconds) {
            this.enabled = enabled;
            this.clientId = clientId == null ? "" : clientId;
            this.clientSecret = clientSecret == null ? "" : clientSecret;
            this.spDc = spDc == null ? "" : spDc;
            this.countryCode = countryCode == null ? "" : countryCode;
            this.preferAnonymousToken = preferAnonymousToken;
            this.playlistMaxTracks = Math.max(1, playlistMaxTracks);
            this.playlistLoadCooldownSeconds = Math.max(0, playlistLoadCooldownSeconds);
        }

        public static Spotify fromLegacy(BotConfig.Music.Spotify legacy) {
            BotConfig.Music.Spotify value = legacy == null ? BotConfig.Music.Spotify.defaultValues() : legacy;
            return new Spotify(
                    value.isEnabled(),
                    value.getClientId(),
                    value.getClientSecret(),
                    value.getSpDc(),
                    value.getCountryCode(),
                    value.isPreferAnonymousToken(),
                    value.getPlaylistMaxTracks(),
                    value.getPlaylistLoadCooldownSeconds()
            );
        }

        public boolean isEnabled() { return enabled; }
        public String getClientId() { return clientId; }
        public String getClientSecret() { return clientSecret; }
        public String getSpDc() { return spDc; }
        public String getCountryCode() { return countryCode; }
        public boolean isPreferAnonymousToken() { return preferAnonymousToken; }
        public int getPlaylistMaxTracks() { return playlistMaxTracks; }
        public int getPlaylistLoadCooldownSeconds() { return playlistLoadCooldownSeconds; }
    }

    public static final class Bilibili {
        private final boolean enabled;
        private final String cookie;
        private final MetadataCache metadataCache;
        private final RateLimit rateLimit;
        private final CircuitBreaker circuitBreaker;

        public Bilibili(boolean enabled,
                         String cookie,
                         MetadataCache metadataCache,
                         RateLimit rateLimit,
                         CircuitBreaker circuitBreaker) {
            this.enabled = enabled;
            this.cookie = cookie == null ? "" : cookie;
            this.metadataCache = metadataCache == null ? MetadataCache.defaultValues() : metadataCache;
            this.rateLimit = rateLimit == null ? RateLimit.defaultValues() : rateLimit;
            this.circuitBreaker = circuitBreaker == null ? CircuitBreaker.defaultValues() : circuitBreaker;
        }

        public static Bilibili fromLegacy(BotConfig.Music.Bilibili legacy) {
            BotConfig.Music.Bilibili value = legacy == null
                    ? BotConfig.Music.Bilibili.defaultValues()
                    : legacy;
            return new Bilibili(
                    value.isEnabled(),
                    value.getCookie(),
                    new MetadataCache(
                            value.getMetadataCache().isEnabled(),
                            value.getMetadataCache().getTtlHours(),
                            value.getMetadataCache().getMaxEntries()
                    ),
                    new RateLimit(
                            value.getRateLimit().isEnabled(),
                            value.getRateLimit().getRequestsPerSecond(),
                            value.getRateLimit().getBurst()
                    ),
                    new CircuitBreaker(
                            value.getCircuitBreaker().isEnabled(),
                            value.getCircuitBreaker().getFailureThreshold(),
                            value.getCircuitBreaker().getWindowSeconds(),
                            value.getCircuitBreaker().getCooldownSeconds()
                    )
            );
        }

        public boolean isEnabled() { return enabled; }
        public String getCookie() { return cookie; }
        public MetadataCache getMetadataCache() { return metadataCache; }
        public RateLimit getRateLimit() { return rateLimit; }
        public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }

        public static final class MetadataCache {
            private final boolean enabled;
            private final int ttlHours;
            private final int maxEntries;

            public MetadataCache(boolean enabled, int ttlHours, int maxEntries) {
                this.enabled = enabled;
                this.ttlHours = Math.max(1, ttlHours);
                this.maxEntries = Math.max(1, maxEntries);
            }

            private static MetadataCache defaultValues() {
                return new MetadataCache(true, 12, 1000);
            }

            public boolean isEnabled() { return enabled; }
            public int getTtlHours() { return ttlHours; }
            public int getMaxEntries() { return maxEntries; }
        }

        public static final class RateLimit {
            private final boolean enabled;
            private final int requestsPerSecond;
            private final int burst;

            public RateLimit(boolean enabled, int requestsPerSecond, int burst) {
                this.enabled = enabled;
                this.requestsPerSecond = Math.max(1, requestsPerSecond);
                this.burst = Math.max(1, burst);
            }

            private static RateLimit defaultValues() {
                return new RateLimit(true, 1, 3);
            }

            public boolean isEnabled() { return enabled; }
            public int getRequestsPerSecond() { return requestsPerSecond; }
            public int getBurst() { return burst; }
        }

        public static final class CircuitBreaker {
            private final boolean enabled;
            private final int failureThreshold;
            private final int windowSeconds;
            private final int cooldownSeconds;

            public CircuitBreaker(boolean enabled,
                                  int failureThreshold,
                                  int windowSeconds,
                                  int cooldownSeconds) {
                this.enabled = enabled;
                this.failureThreshold = Math.max(1, failureThreshold);
                this.windowSeconds = Math.max(1, windowSeconds);
                this.cooldownSeconds = Math.max(1, cooldownSeconds);
            }

            private static CircuitBreaker defaultValues() {
                return new CircuitBreaker(true, 3, 60, 300);
            }

            public boolean isEnabled() { return enabled; }
            public int getFailureThreshold() { return failureThreshold; }
            public int getWindowSeconds() { return windowSeconds; }
            public int getCooldownSeconds() { return cooldownSeconds; }
        }
    }

    private final boolean autoLeaveEnabled;
    private final int autoLeaveMinutes;
    private final boolean autoplayEnabled;
    private final BotConfig.Music.RepeatMode defaultRepeatMode;
    private final Long commandChannelId;
    private final int historyLimit;
    private final int statsRetentionDays;
    private final int playlistTrackLimit;
    private final Youtube youtube;
    private final Oauth oauth;
    private final Cipher cipher;
    private final Spotify spotify;
    private final Audio audio;
    private final Bilibili bilibili;

    public MusicConfig(boolean autoLeaveEnabled,
                       int autoLeaveMinutes,
                       boolean autoplayEnabled,
                       BotConfig.Music.RepeatMode defaultRepeatMode,
                       Long commandChannelId,
                       int historyLimit,
                       int statsRetentionDays,
                       int playlistTrackLimit,
                       Youtube youtube,
                       Oauth oauth,
                       Cipher cipher,
                       Spotify spotify,
                       Audio audio) {
        this(autoLeaveEnabled, autoLeaveMinutes, autoplayEnabled, defaultRepeatMode, commandChannelId,
                historyLimit, statsRetentionDays, playlistTrackLimit, youtube, oauth, cipher, spotify, audio,
                Bilibili.fromLegacy(null));
    }

    public MusicConfig(boolean autoLeaveEnabled,
                       int autoLeaveMinutes,
                       boolean autoplayEnabled,
                       BotConfig.Music.RepeatMode defaultRepeatMode,
                       Long commandChannelId,
                       int historyLimit,
                       int statsRetentionDays,
                       int playlistTrackLimit,
                       Youtube youtube,
                       Oauth oauth,
                       Cipher cipher,
                       Spotify spotify,
                       Audio audio,
                       Bilibili bilibili) {
        this.autoLeaveEnabled = autoLeaveEnabled;
        this.autoLeaveMinutes = Math.max(0, autoLeaveMinutes);
        this.autoplayEnabled = autoplayEnabled;
        this.defaultRepeatMode = defaultRepeatMode == null ? BotConfig.Music.RepeatMode.OFF : defaultRepeatMode;
        this.commandChannelId = commandChannelId;
        this.historyLimit = Math.max(1, historyLimit);
        this.statsRetentionDays = Math.max(0, statsRetentionDays);
        this.playlistTrackLimit = Math.max(1, playlistTrackLimit);
        this.youtube = youtube == null ? Youtube.fromLegacy(null) : youtube;
        this.oauth = oauth == null ? Oauth.fromLegacy(null) : oauth;
        this.cipher = cipher == null ? Cipher.fromLegacy(null) : cipher;
        this.spotify = spotify == null ? Spotify.fromLegacy(null) : spotify;
        this.audio = audio == null ? Audio.fromLegacy(null) : audio;
        this.bilibili = bilibili == null ? Bilibili.fromLegacy(null) : bilibili;
    }

    public static MusicConfig defaultValues() {
        return fromLegacy(BotConfig.Music.defaultValues(), BotConfig.Music.defaultValues());
    }

    public MusicConfig(BotConfig.Music scoped, BotConfig.Music global) {
        this(fromLegacy(scoped, global));
    }

    private MusicConfig(MusicConfig value) {
        this(
                value.autoLeaveEnabled,
                value.autoLeaveMinutes,
                value.autoplayEnabled,
                value.defaultRepeatMode,
                value.commandChannelId,
                value.historyLimit,
                value.statsRetentionDays,
                value.playlistTrackLimit,
                value.youtube,
                value.oauth,
                value.cipher,
                value.spotify,
                value.audio,
                value.bilibili
        );
    }

    public static MusicConfig fromLegacy(BotConfig.Music scoped, BotConfig.Music global) {
        BotConfig.Music scopedValue = scoped == null ? BotConfig.Music.defaultValues() : scoped;
        BotConfig.Music globalValue = global == null ? BotConfig.Music.defaultValues() : global;
        return new MusicConfig(
                scopedValue.isAutoLeaveEnabled(),
                scopedValue.getAutoLeaveMinutes(),
                scopedValue.isAutoplayEnabled(),
                scopedValue.getDefaultRepeatMode(),
                scopedValue.getCommandChannelId(),
                scopedValue.getHistoryLimit(),
                scopedValue.getStatsRetentionDays(),
                scopedValue.getPlaylistTrackLimit(),
                Youtube.fromLegacy(globalValue.getYoutube()),
                Oauth.fromLegacy(globalValue.getOauth()),
                Cipher.fromLegacy(globalValue.getCipher()),
                Spotify.fromLegacy(globalValue.getSpotify()),
                Audio.fromLegacy(globalValue.getAudio()),
                Bilibili.fromLegacy(globalValue.getBilibili())
        );
    }

    public boolean isAutoLeaveEnabled() { return autoLeaveEnabled; }
    public int getAutoLeaveMinutes() { return autoLeaveMinutes; }
    public boolean isAutoplayEnabled() { return autoplayEnabled; }
    public BotConfig.Music.RepeatMode getDefaultRepeatMode() { return defaultRepeatMode; }
    public Long getCommandChannelId() { return commandChannelId; }
    public int getHistoryLimit() { return historyLimit; }
    public int getStatsRetentionDays() { return statsRetentionDays; }
    public int getPlaylistTrackLimit() { return playlistTrackLimit; }
    public Youtube getYoutube() { return youtube; }
    public Oauth getOauth() { return oauth; }
    public Cipher getCipher() { return cipher; }
    public Spotify getSpotify() { return spotify; }
    public Audio getAudio() { return audio; }
    public Bilibili getBilibili() { return bilibili; }
}
