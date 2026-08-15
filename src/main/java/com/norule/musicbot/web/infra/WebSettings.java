package com.norule.musicbot.web.infra;

public final class WebSettings {
    private final boolean enabled;
    private final int port;
    private final String baseUrl;
    private final int sessionExpireMinutes;
    private final String discordClientId;
    private final String discordClientSecret;
    private final String discordRedirectUri;

    public WebSettings(boolean enabled,
                       int port,
                       String baseUrl,
                       int sessionExpireMinutes,
                       String discordClientId,
                       String discordClientSecret,
                       String discordRedirectUri) {
        this.enabled = enabled;
        this.port = Math.max(1, port);
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.sessionExpireMinutes = Math.max(5, sessionExpireMinutes);
        this.discordClientId = discordClientId == null ? "" : discordClientId;
        this.discordClientSecret = discordClientSecret == null ? "" : discordClientSecret;
        this.discordRedirectUri = discordRedirectUri == null ? "" : discordRedirectUri;
    }

    public boolean isEnabled() { return enabled; }
    public int getPort() { return port; }
    public String getBaseUrl() { return baseUrl; }
    public int getSessionExpireMinutes() { return sessionExpireMinutes; }
    public String getDiscordClientId() { return discordClientId; }
    public String getDiscordClientSecret() { return discordClientSecret; }
    public String getDiscordRedirectUri() { return discordRedirectUri; }
}
