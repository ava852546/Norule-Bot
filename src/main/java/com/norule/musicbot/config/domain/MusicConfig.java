package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;

public final class MusicConfig {
    public static final class Youtube {
        private final boolean oauthEnabled;
        private final boolean cipherEnabled;
        private final String oauthRefreshToken;
        private final String cipherServer;
        private final String cipherPassword;
        private final String cipherUserAgent;

        public Youtube(boolean oauthEnabled,
                       boolean cipherEnabled,
                       String oauthRefreshToken,
                       String cipherServer,
                       String cipherPassword,
                       String cipherUserAgent) {
            this.oauthEnabled = oauthEnabled;
            this.cipherEnabled = cipherEnabled;
            this.oauthRefreshToken = oauthRefreshToken == null ? "" : oauthRefreshToken;
            this.cipherServer = cipherServer == null ? "" : cipherServer;
            this.cipherPassword = cipherPassword == null ? "" : cipherPassword;
            this.cipherUserAgent = cipherUserAgent == null ? "" : cipherUserAgent;
        }

        public static Youtube fromLegacy(BotConfig.Music.Youtube legacy) {
            BotConfig.Music.Youtube value = legacy == null ? BotConfig.Music.Youtube.defaultValues() : legacy;
            return new Youtube(
                    value.isOauthEnabled(),
                    value.isCipherEnabled(),
                    value.getOauthRefreshToken(),
                    value.getCipherServer(),
                    value.getCipherPassword(),
                    value.getCipherUserAgent()
            );
        }

        public boolean isOauthEnabled() { return oauthEnabled; }
        public boolean isCipherEnabled() { return cipherEnabled; }
        public String getOauthRefreshToken() { return oauthRefreshToken; }
        public String getCipherServer() { return cipherServer; }
        public String getCipherPassword() { return cipherPassword; }
        public String getCipherUserAgent() { return cipherUserAgent; }
    }

    public static final class Spotify {
        private final boolean enabled;
        private final String clientId;
        private final String clientSecret;
        private final String spDc;
        private final String countryCode;
        private final boolean preferAnonymousToken;
        private final String customTokenEndpoint;
        private final int playlistMaxTracks;
        private final int playlistLoadCooldownSeconds;

        public Spotify(boolean enabled,
                       String clientId,
                       String clientSecret,
                       String spDc,
                       String countryCode,
                       boolean preferAnonymousToken,
                       String customTokenEndpoint,
                       int playlistMaxTracks,
                       int playlistLoadCooldownSeconds) {
            this.enabled = enabled;
            this.clientId = clientId == null ? "" : clientId;
            this.clientSecret = clientSecret == null ? "" : clientSecret;
            this.spDc = spDc == null ? "" : spDc;
            this.countryCode = countryCode == null ? "" : countryCode;
            this.preferAnonymousToken = preferAnonymousToken;
            this.customTokenEndpoint = customTokenEndpoint == null ? "" : customTokenEndpoint;
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
                    value.getCustomTokenEndpoint(),
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
        public String getCustomTokenEndpoint() { return customTokenEndpoint; }
        public int getPlaylistMaxTracks() { return playlistMaxTracks; }
        public int getPlaylistLoadCooldownSeconds() { return playlistLoadCooldownSeconds; }
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
    private final Spotify spotify;

    public MusicConfig(boolean autoLeaveEnabled,
                       int autoLeaveMinutes,
                       boolean autoplayEnabled,
                       BotConfig.Music.RepeatMode defaultRepeatMode,
                       Long commandChannelId,
                       int historyLimit,
                       int statsRetentionDays,
                       int playlistTrackLimit,
                       Youtube youtube,
                       Spotify spotify) {
        this.autoLeaveEnabled = autoLeaveEnabled;
        this.autoLeaveMinutes = Math.max(0, autoLeaveMinutes);
        this.autoplayEnabled = autoplayEnabled;
        this.defaultRepeatMode = defaultRepeatMode == null ? BotConfig.Music.RepeatMode.OFF : defaultRepeatMode;
        this.commandChannelId = commandChannelId;
        this.historyLimit = Math.max(1, historyLimit);
        this.statsRetentionDays = Math.max(0, statsRetentionDays);
        this.playlistTrackLimit = Math.max(1, playlistTrackLimit);
        this.youtube = youtube == null ? Youtube.fromLegacy(null) : youtube;
        this.spotify = spotify == null ? Spotify.fromLegacy(null) : spotify;
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
                value.spotify
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
                Spotify.fromLegacy(globalValue.getSpotify())
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
    public Spotify getSpotify() { return spotify; }
}
