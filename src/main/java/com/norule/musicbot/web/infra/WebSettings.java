package com.norule.musicbot.web.infra;

public final class WebSettings {
    public static final class WebSslSettings {
        private final boolean enabled;
        private final String certDir;
        private final String privateKeyFile;
        private final String fullChainFile;
        private final String keyStoreFile;
        private final String keyStorePassword;
        private final String keyStoreType;
        private final String keyPassword;

        public WebSslSettings(boolean enabled,
                              String certDir,
                              String privateKeyFile,
                              String fullChainFile,
                              String keyStoreFile,
                              String keyStorePassword,
                              String keyStoreType,
                              String keyPassword) {
            this.enabled = enabled;
            this.certDir = certDir == null ? "" : certDir;
            this.privateKeyFile = privateKeyFile == null ? "" : privateKeyFile;
            this.fullChainFile = fullChainFile == null ? "" : fullChainFile;
            this.keyStoreFile = keyStoreFile == null ? "" : keyStoreFile;
            this.keyStorePassword = keyStorePassword == null ? "" : keyStorePassword;
            this.keyStoreType = keyStoreType == null ? "PKCS12" : keyStoreType;
            this.keyPassword = keyPassword == null ? "" : keyPassword;
        }

        public boolean isEnabled() { return enabled; }
        public String getCertDir() { return certDir; }
        public String getPrivateKeyFile() { return privateKeyFile; }
        public String getFullChainFile() { return fullChainFile; }
        public String getKeyStoreFile() { return keyStoreFile; }
        public String getKeyStorePassword() { return keyStorePassword; }
        public String getKeyStoreType() { return keyStoreType; }
        public String getKeyPassword() { return keyPassword; }
    }

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String baseUrl;
    private final int sessionExpireMinutes;
    private final String discordClientId;
    private final String discordClientSecret;
    private final String discordRedirectUri;
    private final WebSslSettings ssl;

    public WebSettings(boolean enabled,
                       String host,
                       int port,
                       String baseUrl,
                       int sessionExpireMinutes,
                       String discordClientId,
                       String discordClientSecret,
                       String discordRedirectUri,
                       WebSslSettings ssl) {
        this.enabled = enabled;
        this.host = host == null || host.isBlank() ? "0.0.0.0" : host;
        this.port = Math.max(1, port);
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.sessionExpireMinutes = Math.max(5, sessionExpireMinutes);
        this.discordClientId = discordClientId == null ? "" : discordClientId;
        this.discordClientSecret = discordClientSecret == null ? "" : discordClientSecret;
        this.discordRedirectUri = discordRedirectUri == null ? "" : discordRedirectUri;
        this.ssl = ssl == null
                ? new WebSslSettings(false, "certs", "privkey.pem", "fullchain.pem", "web-keystore.p12", "", "PKCS12", "")
                : ssl;
    }

    public boolean isEnabled() { return enabled; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getBaseUrl() { return baseUrl; }
    public int getSessionExpireMinutes() { return sessionExpireMinutes; }
    public String getDiscordClientId() { return discordClientId; }
    public String getDiscordClientSecret() { return discordClientSecret; }
    public String getDiscordRedirectUri() { return discordRedirectUri; }
    public WebSslSettings getSsl() { return ssl; }
}
