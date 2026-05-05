package com.norule.musicbot.web.infra;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.*;
import com.norule.musicbot.web.adapter.DiscordOAuthClient;
import com.norule.musicbot.web.controller.GuildSettingsController;
import com.norule.musicbot.web.controller.ShortUrlController;
import com.norule.musicbot.web.controller.TicketTranscriptController;
import com.norule.musicbot.web.controller.WebAuthController;
import com.norule.musicbot.web.controller.WebMetadataController;
import com.norule.musicbot.web.controller.WebStaticAssetController;
import com.norule.musicbot.web.service.WebLanguageService;
import com.norule.musicbot.web.service.WelcomePreviewService;
import com.norule.musicbot.web.session.WebSessionManager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
    public class WebControlServer {
    private static final BigInteger ADMINISTRATOR_BIT = new BigInteger("8");
    private static final BigInteger MANAGE_GUILD_BIT = new BigInteger("32");

    private final JDA jda;
    private final MusicPlayerService musicService;
    private final GuildSettingsService settingsService;
    private final ModerationService moderationService;
    private final TicketService ticketService;
    private final Supplier<WebSettings> settingsSupplier;
    private final Supplier<String> languageDirSupplier;
    private final I18nService i18n;
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NoRule-Web-Cleanup");
        t.setDaemon(true);
        return t;
    });
    private final WebSessionManager sessionManager = new WebSessionManager();
    private final WebAuthController webAuthController;
    private final WebMetadataController webMetadataController;
    private final WebStaticAssetController webStaticAssetController;
    private final WebLanguageService webLanguageService;
    private final DiscordOAuthClient discordOAuthClient = new DiscordOAuthClient();
    private final GuildSettingsController guildSettingsController;
    private final TicketTranscriptController ticketTranscriptController;
    private final ShortUrlController shortUrlController;
    private final WelcomePreviewService welcomePreviewService;
    private final WebRouteBinder webRouteBinder;
    private final ShortUrlService shortUrlService;
    private final String webAssetVersion = String.valueOf(System.currentTimeMillis());

    private volatile HttpServer server;
    private volatile String bindHost = "";
    private volatile int bindPort = -1;
    public WebControlServer(JDA jda,
                            MusicPlayerService musicService,
                            GuildSettingsService settingsService,
                            ModerationService moderationService,
                            TicketService ticketService,
                            ShortUrlService shortUrlService,
                            Supplier<WebSettings> settingsSupplier,
                            Supplier<String> languageDirSupplier,
                            I18nService i18n) {
        this.jda = jda;
        this.musicService = musicService;
        this.settingsService = settingsService;
        this.moderationService = moderationService;
        this.ticketService = ticketService;
        if (shortUrlService == null) {
            throw new IllegalArgumentException("shortUrlService cannot be null");
        }
        this.shortUrlService = shortUrlService;
        this.settingsSupplier = settingsSupplier;
        this.languageDirSupplier = languageDirSupplier;
        this.i18n = i18n;
        this.webAuthController = new WebAuthController(this);
        this.webMetadataController = new WebMetadataController(this);
        this.webStaticAssetController = new WebStaticAssetController(this);
        this.webLanguageService = new WebLanguageService(this::languageDir);
        this.guildSettingsController = new GuildSettingsController(this);
        this.ticketTranscriptController = new TicketTranscriptController(this);
        this.shortUrlController = new ShortUrlController(this);
        this.welcomePreviewService = new WelcomePreviewService(this);
        this.webRouteBinder = new WebRouteBinder(
                webAuthController,
                webMetadataController,
                guildSettingsController,
                shortUrlController,
                webStaticAssetController
        );
        cleanupExecutor.scheduleAtFixedRate(sessionManager::cleanupExpired, 5, 5, TimeUnit.MINUTES);
    }
    public synchronized void syncWithConfig() {
        WebSettings web = webSettings();
        if (!web.isEnabled()) {
            stop();
            return;
        }

        if (server != null && Objects.equals(bindHost, web.getHost()) && bindPort == web.getPort()) {
            return;
        }

        stop();
        start(web);
    }
    public synchronized void stop() {
        HttpServer current = this.server;
        if (current != null) {
            current.stop(0);
            this.server = null;
            System.out.println("[NoRule] Web UI stopped.");
        }
    }
    public synchronized void shutdown() {
        stop();
        cleanupExecutor.shutdownNow();
    }

  private void start(WebSettings web) {
        if (web.getDiscordClientId().isBlank()
                || web.getDiscordClientSecret().isBlank()
                || web.getDiscordRedirectUri().isBlank()) {
            System.out.println("[NoRule] Web UI disabled: missing OAuth config (discordClientId/discordClientSecret/discordRedirectUri).");
            return;
        }

        try {
            HttpServer created = createWebServer(web);
            webRouteBinder.bind(created);
            created.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "NoRule-Web");
                t.setDaemon(true);
                return t;
            }));
            created.start();
            this.server = created;
            this.bindHost = web.getHost();
            this.bindPort = web.getPort();
            String scheme = web.getSsl().isEnabled() ? "https" : "http";
            System.out.println("[NoRule] Web UI started on " + scheme + "://" + web.getHost() + ":" + web.getPort());
        } catch (Exception e) {
            System.out.println("[NoRule] Failed to start Web UI: " + e.getMessage());
        }
    }

    private HttpServer createWebServer(WebSettings web) throws Exception {
        InetSocketAddress address = new InetSocketAddress(web.getHost(), web.getPort());
        if (!web.getSsl().isEnabled()) {
            return HttpServer.create(address, 0);
        }

        SSLContext sslContext = buildSslContext(web);
        HttpsServer httpsServer = HttpsServer.create(address, 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
    public void configure(HttpsParameters params) {
                SSLParameters sslParameters = sslContext.getDefaultSSLParameters();
                sslParameters.setNeedClientAuth(false);
                params.setSSLParameters(sslParameters);
            }
        });
        return httpsServer;
    }

    private SSLContext buildSslContext(WebSettings web) throws Exception {
        WebSettings.WebSslSettings ssl = web.getSsl();
        Path certDir = resolveCertDir(ssl.getCertDir());
        KeyStore keyStore;
        char[] keyPassword;
        if (canUsePemMode(ssl, certDir)) {
            keyStore = buildKeyStoreFromPem(ssl, certDir);
            String pwd = effectiveKeyPassword(ssl, false);
            keyPassword = pwd.toCharArray();
        } else {
            String storePassword = ssl.getKeyStorePassword();
            if (storePassword == null || storePassword.isBlank()) {
                throw new IllegalStateException("web.ssl.keyStorePassword is required when keystore mode is used.");
            }
            String keyPwd = effectiveKeyPassword(ssl, true);
            Path keyStorePath = certDir.resolve(ssl.getKeyStoreFile()).normalize();
            if (!Files.exists(keyStorePath)) {
                throw new IllegalStateException("SSL keystore not found: " + keyStorePath.toAbsolutePath()
                        + ". Put privkey/fullchain in certDir or configure web.ssl.keyStoreFile.");
            }

            keyStore = KeyStore.getInstance(
                    (ssl.getKeyStoreType() == null || ssl.getKeyStoreType().isBlank()) ? "PKCS12" : ssl.getKeyStoreType()
            );
            try (InputStream in = Files.newInputStream(keyStorePath)) {
                keyStore.load(in, storePassword.toCharArray());
            }
            keyPassword = keyPwd.toCharArray();
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPassword);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext;
    }

  private boolean canUsePemMode(WebSettings.WebSslSettings ssl, Path certDir) {
        if (ssl == null) {
            return false;
        }
        if (ssl.getPrivateKeyFile() == null || ssl.getPrivateKeyFile().isBlank()
                || ssl.getFullChainFile() == null || ssl.getFullChainFile().isBlank()) {
            return false;
        }
        Path key = certDir.resolve(ssl.getPrivateKeyFile()).normalize();
        Path cert = certDir.resolve(ssl.getFullChainFile()).normalize();
        return Files.exists(key) && Files.exists(cert);
    }

  private String effectiveKeyPassword(WebSettings.WebSslSettings ssl, boolean requireStorePassword) {
        String keyPassword = ssl.getKeyPassword();
        if (keyPassword != null && !keyPassword.isBlank()) {
            return keyPassword;
        }
        String storePassword = ssl.getKeyStorePassword();
        if (storePassword != null && !storePassword.isBlank()) {
            return storePassword;
        }
        return requireStorePassword ? "" : "";
    }

    private KeyStore buildKeyStoreFromPem(WebSettings.WebSslSettings ssl, Path certDir) throws Exception {
        Path keyPath = certDir.resolve(ssl.getPrivateKeyFile()).normalize();
        Path chainPath = certDir.resolve(ssl.getFullChainFile()).normalize();
        PrivateKey privateKey = loadPrivateKeyFromPem(keyPath);
        X509Certificate[] chain = loadCertificateChainFromPem(chainPath);
        if (chain.length == 0) {
            throw new IllegalStateException("No certificates found in " + chainPath.toAbsolutePath());
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        char[] entryPassword = effectiveKeyPassword(ssl, false).toCharArray();
        keyStore.setKeyEntry("web", privateKey, entryPassword, chain);
        return keyStore;
    }

    private PrivateKey loadPrivateKeyFromPem(Path keyPath) throws Exception {
        String pem = Files.readString(keyPath, StandardCharsets.UTF_8);
        byte[] pkcs8 = extractPemBlock(pem, "PRIVATE KEY");
        if (pkcs8 == null) {
            throw new IllegalStateException("Unsupported private key format: " + keyPath.toAbsolutePath()
                    + " (expected -----BEGIN PRIVATE KEY-----).");
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
        Exception last = null;
        for (String algorithm : List.of("RSA", "EC", "DSA")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("Failed to parse private key: " + keyPath.toAbsolutePath(), last);
    }

    private X509Certificate[] loadCertificateChainFromPem(Path chainPath) throws Exception {
        String pem = Files.readString(chainPath, StandardCharsets.UTF_8);
        Pattern pattern = Pattern.compile(
                "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(pem);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certs = new ArrayList<>();
        while (matcher.find()) {
            String body = matcher.group(1).replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(body);
            try (ByteArrayInputStream in = new ByteArrayInputStream(der)) {
                Certificate certificate = cf.generateCertificate(in);
                certs.add((X509Certificate) certificate);
            }
        }
        return certs.toArray(new X509Certificate[0]);
    }

  private byte[] extractPemBlock(String pem, String type) {
        if (pem == null || pem.isBlank()) {
            return null;
        }
        Pattern pattern = Pattern.compile(
                "-----BEGIN " + Pattern.quote(type) + "-----(.*?)-----END " + Pattern.quote(type) + "-----",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(pem);
        if (!matcher.find()) {
            return null;
        }
        String base64 = matcher.group(1).replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

  private Path resolveCertDir(String certDirRaw) {
        if (certDirRaw == null || certDirRaw.isBlank()) {
            return Path.of("certs").toAbsolutePath().normalize();
        }
        Path certDir = Path.of(certDirRaw);
        if (certDir.isAbsolute()) {
            return certDir.normalize();
        }
        return Path.of("").toAbsolutePath().resolve(certDir).normalize();
    }
    public JDA jda() {
        return jda;
    }
    public MusicPlayerService musicService() {
        return musicService;
    }
    public GuildSettingsService settingsService() {
        return settingsService;
    }
    public ModerationService moderationService() {
        return moderationService;
    }
    public TicketService ticketService() {
        return ticketService;
    }
    public Supplier<WebSettings> settingsSupplier() {
        return settingsSupplier;
    }
    public WebSettings webSettings() {
        WebSettings settings = settingsSupplier.get();
        if (settings == null) {
            return new WebSettings(false, "0.0.0.0", 60000, "https://dash.example.com", 720, "", "", "",
                    new WebSettings.WebSslSettings(false, "certs", "privkey.pem", "fullchain.pem", "web-keystore.p12", "", "PKCS12", ""));
        }
        return settings;
    }

    public String languageDir() {
        String value = languageDirSupplier == null ? "lang" : languageDirSupplier.get();
        return value == null || value.isBlank() ? "lang" : value;
    }
    public I18nService i18n() {
        return i18n;
    }
    public String webAssetVersion() {
        return webAssetVersion;
    }
    public WebLanguageService webLanguageService() {
        return webLanguageService;
    }
    public WebSessionManager sessionManager() {
        return sessionManager;
    }
    public DiscordOAuthClient discordOAuthClient() {
        return discordOAuthClient;
    }
    public TicketTranscriptController ticketTranscriptController() {
        return ticketTranscriptController;
    }
    public ShortUrlService shortUrlService() {
        return shortUrlService;
    }
    public WelcomePreviewService welcomePreviewService() {
        return welcomePreviewService;
    }
    public Member resolveMember(Guild guild, long userId) {
        if (userId <= 0L) {
            return null;
        }
        Member cached = guild.getMemberById(userId);
        if (cached != null) {
            return cached;
        }
        try {
            return guild.retrieveMemberById(userId).complete();
        } catch (Exception ignored) {
            return null;
        }
    }
    public boolean hasControlPermission(Member member) {
        return member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER);
    }
    public String resolveHomeUrl(WebSettings web) {
        String base = web.getBaseUrl();
        if (base == null || base.isBlank()) {
            return "/";
        }
        String trimmed = base.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
    public boolean isSecureCookie(WebSettings web) {
        if (web == null) {
            return false;
        }
        String baseUrl = web.getBaseUrl();
        return web.getSsl().isEnabled() || (baseUrl != null && baseUrl.toLowerCase().startsWith("https://"));
    }
    public String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }
    public Map<String, String> parseUrlEncoded(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            String k = decode(kv[0]);
            String v = kv.length > 1 ? decode(kv[1]) : "";
            if (!k.isBlank()) {
                map.put(k, v);
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }
    public String toIdText(Long value) {
        return value == null ? "" : String.valueOf(value);
    }
    public String stringOrDefault(Map<String, Object> map, String key, String fallback) {
        if (!map.containsKey(key)) {
            return fallback;
        }
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        String text = String.valueOf(v).trim();
        return text.isEmpty() ? fallback : text;
    }
    public boolean boolOrDefault(Map<String, Object> map, String key, boolean fallback) {
        if (!map.containsKey(key)) {
            return fallback;
        }
        Object v = map.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }
    public int intOrDefault(Map<String, Object> map, String key, int fallback, int min, int max) {
        if (!map.containsKey(key)) {
            return fallback;
        }
        Object v = map.get(key);
        int parsed = fallback;
        try {
            if (v instanceof Number n) {
                parsed = n.intValue();
            } else {
                parsed = Integer.parseInt(String.valueOf(v).trim());
            }
        } catch (Exception ignored) {
            parsed = fallback;
        }
        if (parsed < min) {
            return min;
        }
        if (parsed > max) {
            return max;
        }
        return parsed;
    }
    public Long idOrDefault(Map<String, Object> map, String key, Long fallback) {
        if (!map.containsKey(key)) {
            return fallback;
        }
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        String text = String.valueOf(v).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return fallback;
        }
    }
    public List<String> parseCsvOrDefault(Map<String, Object> map, String key, List<String> fallback) {
        if (!map.containsKey(key)) {
            return fallback;
        }
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : text.split(",")) {
            String one = part == null ? "" : part.trim();
            if (!one.isBlank()) {
                out.add(one);
            }
        }
        return out.isEmpty() ? fallback : out;
    }
    public List<Long> parseLongCsvOrDefault(Map<String, Object> map, String key, List<Long> fallback) {
        if (!map.containsKey(key)) {
            return fallback;
        }
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();

        if (value instanceof Iterable<?> iterable) {
            for (Object oneValue : iterable) {
                String one = oneValue == null ? "" : String.valueOf(oneValue).trim();
                if (one.isBlank()) {
                    continue;
                }
                try {
                    long roleId = Long.parseLong(one);
                    if (roleId > 0L) {
                        out.add(roleId);
                    }
                } catch (Exception ignored) {
                }
            }
            return out.stream().distinct().toList();
        }

        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return List.of();
        }
        for (String part : text.split(",")) {
            String one = part == null ? "" : part.trim();
            if (one.isBlank()) {
                continue;
            }
            try {
                long roleId = Long.parseLong(one);
                if (roleId > 0L) {
                    out.add(roleId);
                }
            } catch (Exception ignored) {
            }
        }
        return out.stream().distinct().toList();
    }
    public int colorOrDefault(Map<String, Object> map, String key, int fallback) {
        if (!map.containsKey(key)) {
            return fallback;
        }
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        String text = String.valueOf(v).trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
        }
        try {
            return Integer.parseInt(text, 16) & 0xFFFFFF;
        } catch (Exception ignored) {
            return fallback;
        }
    }
    public String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
    public String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
    public String normalizeLang(String lang) {
        return i18n.normalizeLanguage(lang);
    }
    public boolean shouldAutoApplyTemplate(Map<String, Object> inputMap, String key, String currentValue) {
        if (inputMap == null || inputMap.isEmpty()) {
            return true;
        }
        if (!inputMap.containsKey(key)) {
            return true;
        }
        Object value = inputMap.get(key);
        String incoming = value == null ? "" : String.valueOf(value);
        return incoming.equals(currentValue);
    }
    public boolean hasManagePermissionInGuild(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            BigInteger bits = new BigInteger(raw.trim());
            return bits.and(ADMINISTRATOR_BIT).compareTo(BigInteger.ZERO) > 0
                    || bits.and(MANAGE_GUILD_BIT).compareTo(BigInteger.ZERO) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }
    public String buildGuildIconUrl(String guildId, String iconHash) {
        if (guildId == null || guildId.isBlank() || iconHash == null || iconHash.isBlank()) {
            return "";
        }
        String ext = iconHash.startsWith("a_") ? "gif" : "png";
        return "https://cdn.discordapp.com/icons/" + guildId + "/" + iconHash + "." + ext + "?size=128";
    }
    public String buildBotInviteUrl(String guildId) {
        String clientId = jda.getSelfUser().getId();
        return "https://discord.com/oauth2/authorize"
                + "?client_id=" + encode(clientId)
                + "&permissions=8"
                + "&integration_type=0"
                + "&scope=" + encode("bot applications.commands")
                + "&guild_id=" + encode(guildId == null ? "" : guildId)
                + "&disable_guild_select=true";
    }
    public String buildAvatarUrl(DataObject me) {
        String userId = me.getString("id", "");
        String avatar = me.getString("avatar", "");
        if (userId.isBlank() || avatar.isBlank()) {
            return "";
        }
        String ext = avatar.startsWith("a_") ? "gif" : "png";
        return "https://cdn.discordapp.com/avatars/" + userId + "/" + avatar + "." + ext + "?size=128";
    }
    public long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
    public String formatDuration(long millis) {
        if (millis <= 0L) {
            return "00:00";
        }
        long totalSeconds = millis / 1000L;
        long seconds = totalSeconds % 60L;
        long totalMinutes = totalSeconds / 60L;
        long minutes = totalMinutes % 60L;
        long hours = totalMinutes / 60L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }
    public String previewWelcomeInvalidPayload(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u6b61\u8fce\u8a0a\u606f\u9810\u89bd\u8cc7\u6599\u7121\u6548\u3002";
            case "zh-CN" -> "\u6b22\u8fce\u6d88\u606f\u9884\u89c8\u6570\u636e\u65e0\u6548\u3002";
            default -> "Invalid welcome preview payload.";
        };
    }
    public String previewWelcomeChannelRequired(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u8acb\u5148\u8a2d\u5b9a\u6b61\u8fce\u8a0a\u606f\u983b\u9053\u3002";
            case "zh-CN" -> "\u8bf7\u5148\u8bbe\u7f6e\u6b22\u8fce\u6d88\u606f\u9891\u9053\u3002";
            default -> "Please configure a welcome message channel first.";
        };
    }
    public String previewWelcomeChannelMissing(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u76ee\u524d\u8a2d\u5b9a\u7684\u6b61\u8fce\u8a0a\u606f\u983b\u9053\u4e0d\u5b58\u5728\u3002";
            case "zh-CN" -> "\u5f53\u524d\u8bbe\u7f6e\u7684\u6b22\u8fce\u6d88\u606f\u9891\u9053\u4e0d\u5b58\u5728\u3002";
            default -> "The configured welcome message channel could not be found.";
        };
    }
    public String previewWelcomeMissingPermission(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "Bot \u7f3a\u5c11\u5728\u6b61\u8fce\u983b\u9053\u767c\u9001\u8a0a\u606f\u6216\u5d4c\u5165\u5167\u5bb9\u7684\u6b0a\u9650\u3002";
            case "zh-CN" -> "Bot \u7f3a\u5c11\u5728\u6b22\u8fce\u9891\u9053\u53d1\u9001\u6d88\u606f\u6216\u5d4c\u5165\u5185\u5bb9\u7684\u6743\u9650\u3002";
            default -> "The bot is missing permission to send messages or embeds in the welcome channel.";
        };
    }
    public String previewWelcomeEmptyMessage(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u6b61\u8fce\u8a0a\u606f\u5167\u5bb9\u4e0d\u53ef\u70ba\u7a7a\u3002";
            case "zh-CN" -> "\u6b22\u8fce\u6d88\u606f\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a\u3002";
            default -> "Welcome message content cannot be empty.";
        };
    }
    public String previewWelcomeSendFailed(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u767c\u9001\u6b61\u8fce\u8a0a\u606f\u9810\u89bd\u5931\u6557\u3002";
            case "zh-CN" -> "\u53d1\u9001\u6b22\u8fce\u6d88\u606f\u9884\u89c8\u5931\u8d25\u3002";
            default -> "Failed to send welcome preview.";
        };
    }
    public String previewWelcomeSent(String lang, String channelMention) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u5df2\u5c07\u6b61\u8fce\u8a0a\u606f\u9810\u89bd\u767c\u9001\u5230 " + channelMention + "\u3002";
            case "zh-CN" -> "\u5df2\u5c06\u6b22\u8fce\u6d88\u606f\u9884\u89c8\u53d1\u9001\u5230 " + channelMention + "\u3002";
            default -> "Welcome preview sent to " + channelMention + ".";
        };
    }
    public String formatWelcomeTemplate(String template, User user, Guild guild) {
        if (template == null) {
            return "";
        }
        Instant createdAt = user.getTimeCreated().toInstant();
        long accountAgeDays = ChronoUnit.DAYS.between(createdAt, Instant.now());
        return template
                .replace("{user}", user.getAsMention())
                // Legacy corrupted placeholders kept for older saved templates.
                .replace("\u007b\u96ff\u8f3b\ue705\u003f\uf148\u007d", user.getAsMention())
                .replace("{username}", user.getName())
                .replace("\u007b\u96ff\u8f3b\ue705\u003f\uf1af\u003f\u8754\u5d24\u007d", user.getName())
                .replace("{guild}", guild.getName())
                .replace("\u007b\u8762\u65a4\u003f\u003f\uf699\u8fc2\u007d", guild.getName())
                .replace("{id}", user.getId())
                .replace("{tag}", user.getAsTag())
                .replace("{isBot}", String.valueOf(user.isBot()))
                .replace("{createdAt}", discordCreatedAt(createdAt))
                .replace("{accountAgeDays}", String.valueOf(Math.max(0L, accountAgeDays)));
    }
    public String sanitizeWelcomeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase();
        if (!(lower.startsWith("https://") || lower.startsWith("http://"))) {
            return null;
        }
        return text;
    }
    public EmbedBuilder buildWelcomeEmbed(Guild guild, User user, String title, String message, String thumbnailUrl, String imageUrl) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(0x2ECC71))
                .setTitle(title)
                .setDescription(message)
                .setTimestamp(Instant.now())
                .setFooter(guild.getName(), guild.getIconUrl());
        eb.setThumbnail(thumbnailUrl != null ? thumbnailUrl : user.getEffectiveAvatarUrl());
        if (imageUrl != null) {
            eb.setImage(imageUrl);
        }
        return eb;
    }
    public String discordCreatedAt(Instant instant) {
        return "<t:" + instant.getEpochSecond() + ":F>";
    }
    public void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }
    public void sendJson(HttpExchange exchange, int statusCode, DataObject payload) throws IOException {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
    public void sendText(HttpExchange exchange, int statusCode, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

}


















