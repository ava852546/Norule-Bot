package com.norule.musicbot.web;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.discord.listeners.*;

import com.norule.musicbot.*;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
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
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.yaml.snakeyaml.Yaml;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final String SESSION_COOKIE = "norule_session";
    private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(5);
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;
    private static final int TICKET_HISTORY_RETENTION_DAYS = 90;
    private static final BigInteger ADMINISTRATOR_BIT = new BigInteger("8");
    private static final BigInteger MANAGE_GUILD_BIT = new BigInteger("32");

    private final JDA jda;
    private final MusicPlayerService musicService;
    private final GuildSettingsService settingsService;
    private final ModerationService moderationService;
    private final TicketService ticketService;
    private final Supplier<BotConfig> configSupplier;
    private final I18nService i18n;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS)).build();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NoRule-Web-Cleanup");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, OAuthState> oauthStates = new ConcurrentHashMap<>();
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private final WebAuthController webAuthController;
    private final GuildSettingsApiController guildSettingsApiController;
    private final TicketTranscriptApiController ticketTranscriptApiController;
    private final WelcomePreviewService welcomePreviewService;

    private volatile HttpServer server;
    private volatile String bindHost = "";
    private volatile int bindPort = -1;

    public WebControlServer(JDA jda,
                            MusicPlayerService musicService,
                            GuildSettingsService settingsService,
                            ModerationService moderationService,
                            TicketService ticketService,
                            Supplier<BotConfig> configSupplier,
                            I18nService i18n) {
        this.jda = jda;
        this.musicService = musicService;
        this.settingsService = settingsService;
        this.moderationService = moderationService;
        this.ticketService = ticketService;
        this.configSupplier = configSupplier;
        this.i18n = i18n;
        this.webAuthController = new WebAuthController(this);
        this.guildSettingsApiController = new GuildSettingsApiController(this);
        this.ticketTranscriptApiController = new TicketTranscriptApiController(this);
        this.welcomePreviewService = new WelcomePreviewService(this);
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpired, 5, 5, TimeUnit.MINUTES);
    }

    public synchronized void syncWithConfig() {
        BotConfig.Web web = configSupplier.get().getWeb();
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

    private void start(BotConfig.Web web) {
        if (web.getDiscordClientId().isBlank()
                || web.getDiscordClientSecret().isBlank()
                || web.getDiscordRedirectUri().isBlank()) {
            System.out.println("[NoRule] Web UI disabled: missing OAuth config (discordClientId/discordClientSecret/discordRedirectUri).");
            return;
        }

        try {
            HttpServer created = createWebServer(web);
            created.createContext("/auth/login", webAuthController::handleAuthLogin);
            created.createContext("/auth/callback", webAuthController::handleAuthCallback);
            created.createContext("/auth/logout", webAuthController::handleAuthLogout);
            created.createContext("/api/me", webAuthController::handleApiMe);
            created.createContext("/api/guilds", this::handleApiGuilds);
            created.createContext("/api/web/i18n", this::handleApiWebI18n);
            created.createContext("/api/guild/", guildSettingsApiController::handleApiGuildRoute);
            created.createContext("/web/", this::handleWebAsset);
            created.createContext("/", this::handleRoot);
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

    private HttpServer createWebServer(BotConfig.Web web) throws Exception {
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

    private SSLContext buildSslContext(BotConfig.Web web) throws Exception {
        BotConfig.Web.Ssl ssl = web.getSsl();
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

    private boolean canUsePemMode(BotConfig.Web.Ssl ssl, Path certDir) {
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

    private String effectiveKeyPassword(BotConfig.Web.Ssl ssl, boolean requireStorePassword) {
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

    private KeyStore buildKeyStoreFromPem(BotConfig.Web.Ssl ssl, Path certDir) throws Exception {
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

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
            sendText(exchange, 404, "Not Found");
            return;
        }
        sendHtml(exchange, 200, buildRootHtml());
    }

    private void handleWebAsset(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath == null || !requestPath.startsWith("/web/") || requestPath.contains("..")) {
            sendText(exchange, 404, "Not Found");
            return;
        }
        String resourcePath = requestPath;
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                sendText(exchange, 404, "Not Found");
                return;
            }
            byte[] body = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", webAssetContentType(resourcePath));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }
    }

    void handleApiWebI18n(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        Map<String, Map<String, String>> bundles = loadWebBundles();
        DataArray uiLanguages = DataArray.empty();
        for (Map.Entry<String, Map<String, String>> entry : bundles.entrySet()) {
            String code = entry.getKey();
            Map<String, String> bundle = entry.getValue();
            String label = bundle.getOrDefault("lang", bundle.getOrDefault("language.name", code));
            uiLanguages.add(DataObject.empty()
                    .put("code", code)
                    .put("label", label));
        }
        DataArray botLanguages = DataArray.empty();
        for (Map.Entry<String, String> entry : i18n.getAvailableLanguages().entrySet()) {
            botLanguages.add(DataObject.empty()
                    .put("code", entry.getKey())
                    .put("label", entry.getValue()));
        }
        DataObject bundlesJson = DataObject.empty();
        for (Map.Entry<String, Map<String, String>> entry : bundles.entrySet()) {
            DataObject one = DataObject.empty();
            for (Map.Entry<String, String> kv : entry.getValue().entrySet()) {
                one.put(kv.getKey(), kv.getValue());
            }
            bundlesJson.put(entry.getKey(), one);
        }
        String defaultUiLang = bundles.containsKey("zh-TW")
                ? "zh-TW"
                : (bundles.isEmpty() ? "en" : bundles.keySet().iterator().next());
        sendJson(exchange, 200, DataObject.empty()
                .put("defaultLanguage", defaultUiLang)
                .put("uiLanguages", uiLanguages)
                .put("botLanguages", botLanguages)
                .put("bundles", bundlesJson));
    }

    void handleApiGuilds(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        WebSession session = requireSession(exchange);
        if (session == null || session.accessToken == null || session.accessToken.isBlank()) {
            sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
            return;
        }

        try {
            DataArray userGuilds = fetchUserGuilds(session.accessToken);
            DataArray guilds = DataArray.empty();
            for (int i = 0; i < userGuilds.length(); i++) {
                DataObject rawGuild = userGuilds.getObject(i);
                String guildId = rawGuild.getString("id", "");
                if (guildId.isBlank()) {
                    continue;
                }
                String permissions = rawGuild.getString("permissions_new", rawGuild.getString("permissions", "0"));
                if (!hasManagePermissionInGuild(permissions)) {
                    continue;
                }
                String guildName = rawGuild.getString("name", "Unknown Guild");
                String icon = rawGuild.getString("icon", "");
                Guild botGuild = jda.getGuildById(guildId);
                boolean botInGuild = botGuild != null;
                boolean botCanManage = botInGuild && botGuild.getSelfMember().hasPermission(Permission.MANAGE_SERVER);

                guilds.add(DataObject.empty()
                        .put("id", guildId)
                        .put("name", guildName)
                        .put("iconUrl", buildGuildIconUrl(guildId, icon))
                        .put("botInGuild", botInGuild)
                        .put("botCanManage", botCanManage)
                        .put("manageUrl", "/?guild=" + guildId)
                        .put("inviteUrl", buildBotInviteUrl(guildId)));
            }
            sendJson(exchange, 200, DataObject.empty().put("guilds", guilds));
        } catch (Exception e) {
            sendJson(exchange, 401, DataObject.empty().put("error", "Failed to load user guilds. Please login again."));
        }
    }

    JDA jda() {
        return jda;
    }

    MusicPlayerService musicService() {
        return musicService;
    }

    GuildSettingsService settingsService() {
        return settingsService;
    }

    ModerationService moderationService() {
        return moderationService;
    }

    TicketService ticketService() {
        return ticketService;
    }

    Supplier<BotConfig> configSupplier() {
        return configSupplier;
    }

    I18nService i18n() {
        return i18n;
    }

    HttpClient httpClient() {
        return httpClient;
    }

    Map<String, OAuthState> oauthStates() {
        return oauthStates;
    }

    Map<String, WebSession> sessions() {
        return sessions;
    }

    TicketTranscriptApiController ticketTranscriptApiController() {
        return ticketTranscriptApiController;
    }

    WelcomePreviewService welcomePreviewService() {
        return welcomePreviewService;
    }

    private String webAssetContentType(String resourcePath) {
        if (resourcePath.endsWith(".js")) {
            return "text/javascript; charset=UTF-8";
        }
        if (resourcePath.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (resourcePath.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (resourcePath.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }
        return "text/plain; charset=UTF-8";
    }

    private String resolveBotAvatarUrl() {
        try {
            if (jda == null || jda.getSelfUser() == null) {
                return "";
            }
            String url = jda.getSelfUser().getEffectiveAvatarUrl();
            return url == null ? "" : url;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String escapeHtmlAttr(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    Member resolveMember(Guild guild, long userId) {
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

    boolean hasControlPermission(Member member) {
        return member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER);
    }

    WebSession requireSession(HttpExchange exchange) {
        cleanupExpired();
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || cookie.isBlank()) {
            return null;
        }
        String sessionId = null;
        for (String part : cookie.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && SESSION_COOKIE.equals(kv[0].trim())) {
                sessionId = kv[1].trim();
                break;
            }
        }
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        WebSession session = sessions.get(sessionId);
        if (session == null || session.expiresAtMillis < System.currentTimeMillis()) {
            sessions.remove(sessionId);
            return null;
        }
        return session;
    }

    void setSessionCookie(HttpExchange exchange, String sessionId, BotConfig.Web web) {
        boolean secure = web.getSsl().isEnabled()
                || (web.getBaseUrl() != null && web.getBaseUrl().toLowerCase().startsWith("https://"));
        String sameSite = secure ? "None" : "Lax";
        int maxAge = Math.max(300, web.getSessionExpireMinutes() * 60);
        String cookie = SESSION_COOKIE + "=" + sessionId
                + "; Path=/; Max-Age=" + maxAge
                + "; HttpOnly; SameSite=" + sameSite
                + (secure ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    void clearSessionCookie(HttpExchange exchange, BotConfig.Web web) {
        boolean secure = web.getSsl().isEnabled()
                || (web.getBaseUrl() != null && web.getBaseUrl().toLowerCase().startsWith("https://"));
        String sameSite = secure ? "None" : "Lax";
        String cookie = SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=" + sameSite + (secure ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    String resolveHomeUrl(BotConfig.Web web) {
        String base = web.getBaseUrl();
        if (base == null || base.isBlank()) {
            return "/";
        }
        String trimmed = base.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    void cleanupExpired() {
        long now = System.currentTimeMillis();
        oauthStates.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
        sessions.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
    }

    String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    Map<String, String> parseUrlEncoded(String raw) {
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
    Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private Map<String, Map<String, String>> loadWebBundles() {
        Map<String, Map<String, String>> bundles = new LinkedHashMap<>();
        String langDir = configSupplier.get().getLanguageDir();
        Path base = Path.of(langDir == null || langDir.isBlank() ? "lang" : langDir);
        Path webBase = base.resolve("web");
        if (Files.exists(webBase)) {
            try (var stream = Files.list(webBase)) {
                stream.filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.startsWith("web-"))
                        .filter(name -> name.endsWith(".yml") || name.endsWith(".yaml"))
                        .sorted()
                        .forEach(fileName -> {
                            String code = parseWebLanguageCode(fileName);
                            if (code.isBlank()) {
                                return;
                            }
                            Map<String, String> bundle = readFlatYaml(webBase.resolve(fileName));
                            if (!bundle.isEmpty()) {
                                bundles.put(code, bundle);
                            }
                        });
            } catch (IOException ignored) {
            }
        }

        if (!bundles.containsKey("zh-TW")) {
            Map<String, String> zh = readFlatYamlResource("defaults/lang/web/web-zh-TW.yml");
            if (!zh.isEmpty()) {
                bundles.put("zh-TW", zh);
            }
        }
        if (!bundles.containsKey("zh-CN")) {
            Map<String, String> zhCn = readFlatYamlResource("defaults/lang/web/web-zh-CN.yml");
            if (!zhCn.isEmpty()) {
                bundles.put("zh-CN", zhCn);
            }
        }
        if (!bundles.containsKey("en")) {
            Map<String, String> en = readFlatYamlResource("defaults/lang/web/web-en.yml");
            if (!en.isEmpty()) {
                bundles.put("en", en);
            }
        }
        if (bundles.isEmpty()) {
            bundles.put("zh-TW", Map.of());
            bundles.put("en", Map.of());
        }
        return bundles;
    }

    private String parseWebLanguageCode(String fileName) {
        if (fileName == null || !fileName.startsWith("web-")) {
            return "";
        }
        String name = fileName;
        if (name.endsWith(".yml")) {
            name = name.substring(0, name.length() - 4);
        } else if (name.endsWith(".yaml")) {
            name = name.substring(0, name.length() - 5);
        }
        if (name.length() <= 4) {
            return "";
        }
        return name.substring(4);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readFlatYaml(Path file) {
        if (file == null || !Files.exists(file)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            if (!(root instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            flattenMap("", (Map<String, Object>) map, out);
            return out;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readFlatYamlResource(String resourcePath) {
        try (InputStream in = WebControlServer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Map.of();
            }
            Object root = new Yaml().load(in);
            if (!(root instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            flattenMap("", (Map<String, Object>) map, out);
            return out;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenMap(String prefix, Map<String, Object> source, Map<String, String> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                flattenMap(key, (Map<String, Object>) child, target);
            } else {
                target.put(key, value == null ? "" : String.valueOf(value));
            }
        }
    }

    String toIdText(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    String stringOrDefault(Map<String, Object> map, String key, String fallback) {
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

    boolean boolOrDefault(Map<String, Object> map, String key, boolean fallback) {
        if (!map.containsKey(key)) {
            return fallback;
        }
        Object v = map.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    int intOrDefault(Map<String, Object> map, String key, int fallback, int min, int max) {
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

    Long idOrDefault(Map<String, Object> map, String key, Long fallback) {
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

    List<String> parseCsvOrDefault(Map<String, Object> map, String key, List<String> fallback) {
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

    List<Long> parseLongCsvOrDefault(Map<String, Object> map, String key, List<Long> fallback) {
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

    List<BotConfig.Ticket.TicketOption> parseTicketOptions(Map<String, Object> map, List<BotConfig.Ticket.TicketOption> fallback) {
        if (!map.containsKey("options")) {
            return fallback;
        }
        Object value = map.get("options");
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<BotConfig.Ticket.TicketOption> out = new ArrayList<>();
        int index = 0;
        for (Object item : iterable) {
            Map<String, Object> optionMap = asMap(item);
            if (optionMap.isEmpty()) {
                continue;
            }
            BotConfig.Ticket.TicketOption defaultOption = fallback != null && fallback.size() > index
                    ? fallback.get(index)
                    : BotConfig.Ticket.TicketOption.defaultValues();
            out.add(BotConfig.Ticket.TicketOption.fromMap(optionMap, defaultOption));
            index++;
        }
        return out;
    }

    int colorOrDefault(Map<String, Object> map, String key, int fallback) {
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

    BotConfig.Music.RepeatMode parseRepeatMode(String raw) {
        try {
            return BotConfig.Music.RepeatMode.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return BotConfig.Music.RepeatMode.OFF;
        }
    }

    BotConfig.Ticket.OpenUiMode parseTicketOpenUiMode(String raw, BotConfig.Ticket.OpenUiMode fallback) {
        return BotConfig.Ticket.OpenUiMode.parse(raw, fallback);
    }

    String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    String normalizeLang(String lang) {
        return i18n.normalizeLanguage(lang);
    }

    boolean shouldAutoApplyTemplate(Map<String, Object> inputMap, String key, String currentValue) {
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

    BotConfig.Notifications notificationDefaultsForLanguage(String language) {
        BotConfig.Notifications defaults = BotConfig.Notifications.defaultValues();
        return defaults
                .withMemberJoinMessage(i18n.t(language, "notifications.template.default.member_join"))
                .withMemberLeaveMessage(i18n.t(language, "notifications.template.default.member_leave"))
                .withVoiceJoinMessage(i18n.t(language, "notifications.template.default.voice_join"))
                .withVoiceLeaveMessage(i18n.t(language, "notifications.template.default.voice_leave"))
                .withVoiceMoveMessage(i18n.t(language, "notifications.template.default.voice_move"));
    }

    String exchangeToken(BotConfig.Web web, String code) throws IOException, InterruptedException {
        String body = "client_id=" + encode(web.getDiscordClientId())
                + "&client_secret=" + encode(web.getDiscordClientSecret())
                + "&grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(web.getDiscordRedirectUri());
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://discord.com/api/oauth2/token"))
                .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("token exchange failed: HTTP " + response.statusCode());
        }
        DataObject json = DataObject.fromJson(response.body());
        String token = json.getString("access_token", "");
        if (token.isBlank()) {
            throw new IllegalStateException("access_token missing");
        }
        return token;
    }

    DataObject fetchMe(String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://discord.com/api/users/@me"))
                .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("failed to load profile: HTTP " + response.statusCode());
        }
        return DataObject.fromJson(response.body());
    }

    DataArray fetchUserGuilds(String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://discord.com/api/users/@me/guilds"))
                .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("failed to load guilds: HTTP " + response.statusCode());
        }
        return DataArray.fromJson(response.body());
    }

    boolean hasManagePermissionInGuild(String raw) {
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

    String buildGuildIconUrl(String guildId, String iconHash) {
        if (guildId == null || guildId.isBlank() || iconHash == null || iconHash.isBlank()) {
            return "";
        }
        String ext = iconHash.startsWith("a_") ? "gif" : "png";
        return "https://cdn.discordapp.com/icons/" + guildId + "/" + iconHash + "." + ext + "?size=128";
    }

    String buildBotInviteUrl(String guildId) {
        String clientId = jda.getSelfUser().getId();
        return "https://discord.com/oauth2/authorize"
                + "?client_id=" + encode(clientId)
                + "&permissions=8"
                + "&integration_type=0"
                + "&scope=" + encode("bot applications.commands")
                + "&guild_id=" + encode(guildId == null ? "" : guildId)
                + "&disable_guild_select=true";
    }

    String buildAvatarUrl(DataObject me) {
        String userId = me.getString("id", "");
        String avatar = me.getString("avatar", "");
        if (userId.isBlank() || avatar.isBlank()) {
            return "";
        }
        String ext = avatar.startsWith("a_") ? "gif" : "png";
        return "https://cdn.discordapp.com/avatars/" + userId + "/" + avatar + "." + ext + "?size=128";
    }

    long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    String formatDuration(long millis) {
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

    String previewWelcomeInvalidPayload(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u6b61\u8fce\u8a0a\u606f\u9810\u89bd\u8cc7\u6599\u7121\u6548\u3002";
            case "zh-CN" -> "\u6b22\u8fce\u6d88\u606f\u9884\u89c8\u6570\u636e\u65e0\u6548\u3002";
            default -> "Invalid welcome preview payload.";
        };
    }

    String previewWelcomeChannelRequired(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u8acb\u5148\u8a2d\u5b9a\u6b61\u8fce\u8a0a\u606f\u983b\u9053\u3002";
            case "zh-CN" -> "\u8bf7\u5148\u8bbe\u7f6e\u6b22\u8fce\u6d88\u606f\u9891\u9053\u3002";
            default -> "Please configure a welcome message channel first.";
        };
    }

    String previewWelcomeChannelMissing(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u76ee\u524d\u8a2d\u5b9a\u7684\u6b61\u8fce\u8a0a\u606f\u983b\u9053\u4e0d\u5b58\u5728\u3002";
            case "zh-CN" -> "\u5f53\u524d\u8bbe\u7f6e\u7684\u6b22\u8fce\u6d88\u606f\u9891\u9053\u4e0d\u5b58\u5728\u3002";
            default -> "The configured welcome message channel could not be found.";
        };
    }

    String previewWelcomeMissingPermission(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "Bot \u7f3a\u5c11\u5728\u6b61\u8fce\u983b\u9053\u767c\u9001\u8a0a\u606f\u6216\u5d4c\u5165\u5167\u5bb9\u7684\u6b0a\u9650\u3002";
            case "zh-CN" -> "Bot \u7f3a\u5c11\u5728\u6b22\u8fce\u9891\u9053\u53d1\u9001\u6d88\u606f\u6216\u5d4c\u5165\u5185\u5bb9\u7684\u6743\u9650\u3002";
            default -> "The bot is missing permission to send messages or embeds in the welcome channel.";
        };
    }

    String previewWelcomeEmptyMessage(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u6b61\u8fce\u8a0a\u606f\u5167\u5bb9\u4e0d\u53ef\u70ba\u7a7a\u3002";
            case "zh-CN" -> "\u6b22\u8fce\u6d88\u606f\u5185\u5bb9\u4e0d\u80fd\u4e3a\u7a7a\u3002";
            default -> "Welcome message content cannot be empty.";
        };
    }

    String previewWelcomeSendFailed(String lang) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u767c\u9001\u6b61\u8fce\u8a0a\u606f\u9810\u89bd\u5931\u6557\u3002";
            case "zh-CN" -> "\u53d1\u9001\u6b22\u8fce\u6d88\u606f\u9884\u89c8\u5931\u8d25\u3002";
            default -> "Failed to send welcome preview.";
        };
    }

    String previewWelcomeSent(String lang, String channelMention) {
        return switch (normalizeLang(lang)) {
            case "zh-TW" -> "\u5df2\u5c07\u6b61\u8fce\u8a0a\u606f\u9810\u89bd\u767c\u9001\u5230 " + channelMention + "\u3002";
            case "zh-CN" -> "\u5df2\u5c06\u6b22\u8fce\u6d88\u606f\u9884\u89c8\u53d1\u9001\u5230 " + channelMention + "\u3002";
            default -> "Welcome preview sent to " + channelMention + ".";
        };
    }

    String resolveWelcomeTitle(BotConfig.Welcome welcome, String lang) {
        String title = welcome.getTitle();
        if (title == null || title.isBlank()) {
            return i18n.t(lang, "welcome.default_title");
        }
        return title;
    }

    String resolveWelcomeMessage(BotConfig.Welcome welcome, String lang) {
        String message = welcome.getMessage();
        if (message == null || message.isBlank()) {
            return i18n.t(lang, "welcome.default_message");
        }
        return message;
    }

    String formatWelcomeTemplate(String template, User user, Guild guild) {
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

    String sanitizeWelcomeUrl(String raw) {
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

    EmbedBuilder buildWelcomeEmbed(Guild guild, User user, String title, String message, String thumbnailUrl, String imageUrl) {
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

    String discordCreatedAt(Instant instant) {
        return "<t:" + instant.getEpochSecond() + ":F>";
    }

    void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    void sendJson(HttpExchange exchange, int statusCode, DataObject payload) throws IOException {
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

    private void sendHtml(HttpExchange exchange, int statusCode, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    void sendText(HttpExchange exchange, int statusCode, String text) throws IOException {
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
    private String buildRootHtml() {
        String botName = (jda != null && jda.getSelfUser() != null) ? jda.getSelfUser().getName() : "NoRule Bot";
        String botAvatarUrl = resolveBotAvatarUrl();
        String faviconUrl = botAvatarUrl.isBlank()
                ? "https://cdn.discordapp.com/embed/avatars/0.png"
                : botAvatarUrl;
        String botAvatarBlock = botAvatarUrl.isBlank()
                ? "<div class=\"bot-avatar-fallback\" aria-label=\"" + escapeHtmlAttr(botName) + "\">NR</div>"
                : "<img class=\"bot-avatar\" src=\"" + escapeHtmlAttr(botAvatarUrl) + "\" alt=\"" + escapeHtmlAttr(botName) + "\" loading=\"lazy\" referrerpolicy=\"no-referrer\" />";

        String html = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>NoRule Bot Web Console</title>
                  <link rel="icon" type="image/png" href="__FAVICON_URL__" />
                  <link rel="apple-touch-icon" href="__FAVICON_URL__" />
                  <link rel="stylesheet" href="/web/app.css">
                </head>
                <body>
                <div class="wrap app-shell">
                  <section class="card hero-card">
                    <div class="hero-copy">
                      <div class="brand-row">
                        <div class="brand-chip"><span class="brand-dot"></span> NoRule Control</div>
                        <div class="hero-badge">Discord Web Admin</div>
                      </div>
                      <h1 id="titleMain">NoRule Bot Web Console</h1>
                      <div id="subtitleMain" class="muted hero-subtitle">Sign in with Discord. Manage your guild settings from web.</div>
                      <div class="hero-intro-grid">
                        <div class="hero-intro-card">
                          <div class="hero-intro-label">Modules</div>
                          <div class="hero-intro-value">Notifications / Music / Tickets</div>
                        </div>
                        <div class="hero-intro-card">
                          <div class="hero-intro-label">Access</div>
                          <div class="hero-intro-value">Discord OAuth Secure Session</div>
                        </div>
                      </div>
                    </div>
                    <div class="hero-panel">
                      <div class="login-hero">__BOT_AVATAR_BLOCK__</div>
                      <div class="auth-actions">
                        <div class="row lang-switch">
                          <span id="langLabel" class="muted">Language</span>
                          <div id="uiLangButtons" class="row"></div>
                        </div>
                        <div id="authBlock" class="row"><button id="loginBtn" class="primary">Sign in with Discord</button></div>
                        <div id="userBlock" class="hidden">
                          <div class="user-profile user-profile-inline">
                            <img id="meAvatar" class="user-avatar hidden" alt="avatar" loading="lazy" referrerpolicy="no-referrer" />
                            <div class="user-name-block">
                              <div id="meLine"></div>
                            </div>
                            <button id="logoutBtn" class="logout-inline danger">Logout</button>
                          </div>
                        </div>
                      </div>
                    </div>
                  </section>

                  <div class="dashboard-grid">
                    <aside class="dashboard-side card sidebar-card hidden" id="guildsBlock">
                      <div class="sidebar-head">
                        <div class="section-kicker">Guilds</div>
                        <h2 id="guildsTitle">Your Manageable Guilds</h2>
                        <div id="guildsSubtitle" class="muted">Bot not in guild: Invite Bot. Bot in guild: click Manage.</div>
                      </div>
                      <div id="guildList" class="guild-grid"></div>
                    </aside>

                    <section class="dashboard-main card main-card hidden" id="settingsBlock">
                      <div class="section-kicker">Dashboard</div>
                      <h2 id="settingsTitle">Guild Settings</h2>
                      <div id="settingsSubtitle" class="muted">Use this page to configure this guild's features and notification settings.</div>
                      <div class="control-bar">
                        <select id="guildSelect"></select>
                        <button id="guildReloadBtn">Reload Current Guild</button>
                        <div id="status" class="row"></div>
                      </div>
                      <div class="row settings-actions">
                        <button id="loadSettingsBtn">Reload Form</button>
                        <button id="saveSettingsBtn" class="primary">Save Settings</button>
                      </div>

                      <div class="tabs">
                      <button class="tab-btn active" data-tab="general">General</button>
                      <button class="tab-btn" data-tab="notifications">Notifications</button>
                      <button class="tab-btn" data-tab="logs">Logs</button>
                      <button class="tab-btn" data-tab="music">Music</button>
                      <button class="tab-btn" data-tab="privateRoom">Private Room</button>
                      <button class="tab-btn" data-tab="welcome">Welcome</button>
                      <button class="tab-btn" data-tab="numberChain">Number Chain</button>
                      <button class="tab-btn" data-tab="ticket">Tickets</button>
                      <button class="tab-btn" data-tab="ticketHistory">Ticket History</button>
                      </div>

                      <div class="tab-pane active" data-pane="general">
                      <div class="row pane-head">
                        <h3>language</h3>
                        <button id="resetGeneralBtn" class="danger reset-btn" type="button">Reset Section</button>
                      </div>
                      <div class="grid2">
                        <div class="field">
                          <label id="label_s_language">Language</label>
                          <select id="s_language"></select>
                          <div id="hint_s_language" class="keyhint">Default: zh-TW. Saving this will sync guild locale settings.</div>
                        </div>
                      </div>
                      </div>

                      <div class="tab-pane" data-pane="notifications">
                      <div class="row pane-head">
                        <h3 id="notifications_group_title">notifications.*</h3>
                      </div>
                      <div class="welcome-shell">
                        <div class="settings-group">
                          <div id="notifications_message_card_title" class="settings-group-title">Notification Settings</div>
                          <div id="notifications_message_lead" class="welcome-lead">Configure member and voice notification channels, templates, and embed styles.</div>
                          <div class="notification-toggle-stack">
                            <div class="grid2">
                              <div class="toggle"><input type="checkbox" id="n_enabled"><label for="n_enabled">enabled</label></div>
                              <div class="toggle"><input type="checkbox" id="n_voiceLogEnabled"><label for="n_voiceLogEnabled">voiceLogEnabled</label></div>
                              <div class="toggle"><input type="checkbox" id="n_memberJoinEnabled"><label for="n_memberJoinEnabled">memberJoinEnabled</label></div>
                              <div class="toggle"><input type="checkbox" id="n_memberLeaveEnabled"><label for="n_memberLeaveEnabled">memberLeaveEnabled</label></div>
                            </div>
                            <div class="grid2">
                              <div class="field"><label>memberChannelId</label><select id="n_memberChannelId"></select></div>
                              <div class="field"><label>voiceChannelId</label><select id="n_voiceChannelId"></select></div>
                              <div class="field"><label>memberJoinChannelId</label><select id="n_memberJoinChannelId"></select></div>
                              <div class="field"><label>memberLeaveChannelId</label><select id="n_memberLeaveChannelId"></select></div>
                            </div>
                          </div>
                          <div class="welcome-compact-actions">
                            <button id="resetNotificationsBtn" class="danger" type="button">Reset Section</button>
                            <button id="openNotificationEditorBtn" class="warn" type="button">Configure Notification Embeds</button>
                          </div>
                        </div>
                      </div>
                      <input id="n_memberJoinThumbnailUrl" type="hidden">
                      <input id="n_memberJoinImageUrl" type="hidden">
                      <div id="notificationEditorModal" class="modal-backdrop hidden">
                        <div class="modal-card">
                          <div class="modal-head">
                            <div id="notificationEditorTitle" class="modal-title">Configure Notification Embeds</div>
                            <button id="closeNotificationEditorBtn" class="danger modal-close" type="button">Close</button>
                          </div>
                          <div class="modal-body">
                            <div class="settings-group">
                              <div id="notification_member_template_card_title" class="settings-group-title">Member Notification Embed</div>
                              <div class="grid2">
                                <div class="field">
                                  <label>memberJoinTitle</label>
                                  <input id="n_memberJoinTitle" type="text">
                                  <div id="hint_n_memberJoinTitle" class="keyhint">Available placeholders: {user}, {guild}</div>
                                </div>
                                <div class="field">
                                  <label>memberJoinMessage</label>
                                  <textarea id="n_memberJoinMessage"></textarea>
                                  <div id="hint_n_memberJoinMessage" class="keyhint">Available placeholders: {user}, {username}, {guild}, {id}, {tag}, {isBot}, {createdAt}, {accountAgeDays}</div>
                                </div>
                                <div class="field">
                                  <label>memberLeaveMessage</label>
                                  <textarea id="n_memberLeaveMessage"></textarea>
                                </div>
                                <div class="grid2">
                                  <div class="field"><label>memberJoinColor</label><input type="color" id="n_memberJoinColor"></div>
                                  <div class="field"><label>memberLeaveColor</label><input type="color" id="n_memberLeaveColor"></div>
                                </div>
                              </div>
                            </div>
                            <div class="settings-group">
                              <div id="notification_voice_template_card_title" class="settings-group-title">Voice Notification Embed</div>
                              <div class="grid2">
                                <div class="field">
                                  <label>voiceJoinMessage</label>
                                  <textarea id="n_voiceJoinMessage"></textarea>
                                  <div id="hint_n_voiceJoinMessage" class="keyhint">Available placeholders: {user}, {channel}, {from}, {to}</div>
                                </div>
                                <div class="field">
                                  <label>voiceLeaveMessage</label>
                                  <textarea id="n_voiceLeaveMessage"></textarea>
                                  <div id="hint_n_voiceLeaveMessage" class="keyhint">Available placeholders: {user}, {channel}, {from}, {to}</div>
                                </div>
                                <div class="field">
                                  <label>voiceMoveMessage</label>
                                  <textarea id="n_voiceMoveMessage"></textarea>
                                  <div id="hint_n_voiceMoveMessage" class="keyhint">Available placeholders: {user}, {channel}, {from}, {to}</div>
                                </div>
                              </div>
                              <div id="notification_voice_color_card_title" class="welcome-card-title">Voice Embed Colors</div>
                              <div class="notification-color-grid">
                                <div class="field"><label>voiceJoinColor</label><input type="color" id="n_voiceJoinColor"></div>
                                <div class="field"><label>voiceLeaveColor</label><input type="color" id="n_voiceLeaveColor"></div>
                                <div class="field"><label>voiceMoveColor</label><input type="color" id="n_voiceMoveColor"></div>
                              </div>
                            </div>
                            <div class="settings-group">
                              <div id="notification_voice_preview_card_title" class="settings-group-title">Voice Embed Preview</div>
                              <div id="notificationVoicePreviewCard" class="welcome-preview"></div>
                            </div>
                          </div>
                          <div class="modal-actions">
                            <button id="saveNotificationSettingsBtn" class="primary" type="button">Save Settings</button>
                          </div>
                        </div>
                      </div>
                      </div>

                      <div class="tab-pane" data-pane="logs">
                      <div class="row pane-head">
                        <h3>messageLogs.*</h3>
                        <button id="resetLogsBtn" class="danger reset-btn" type="button">Reset Section</button>
                      </div>
                      <div class="grid3">
                        <div class="toggle"><input type="checkbox" id="l_enabled"><label for="l_enabled">enabled</label></div>
                        <div class="toggle"><input type="checkbox" id="l_roleLogEnabled"><label for="l_roleLogEnabled">roleLogEnabled</label></div>
                        <div class="toggle"><input type="checkbox" id="l_channelLifecycleLogEnabled"><label for="l_channelLifecycleLogEnabled">channelLifecycleLogEnabled</label></div>
                        <div class="toggle"><input type="checkbox" id="l_moderationLogEnabled"><label for="l_moderationLogEnabled">moderationLogEnabled</label></div>
                        <div class="toggle"><input type="checkbox" id="l_commandUsageLogEnabled"><label for="l_commandUsageLogEnabled">commandUsageLogEnabled</label></div>
                      </div>
                      <div class="grid3">
                        <div class="field"><label>channelId (default)</label><select id="l_channelId"></select></div>
                        <div class="field"><label>messageLogChannelId</label><select id="l_messageLogChannelId"></select></div>
                        <div class="field"><label>commandUsageChannelId</label><select id="l_commandUsageChannelId"></select></div>
                        <div class="field"><label>channelLifecycleChannelId</label><select id="l_channelLifecycleChannelId"></select></div>
                        <div class="field"><label>roleLogChannelId</label><select id="l_roleLogChannelId"></select></div>
                        <div class="field"><label>moderationLogChannelId</label><select id="l_moderationLogChannelId"></select></div>
                      </div>
                      </div>

                      <div class="tab-pane" data-pane="music">
                      <div class="row pane-head">
                        <h3>music.*</h3>
                        <button id="resetMusicBtn" class="danger reset-btn" type="button">Reset Section</button>
                      </div>
                      <div class="welcome-shell module-shell">
                        <div class="settings-group">
                          <div id="music_settings_card_title" class="settings-group-title">Music Settings</div>
                          <div class="welcome-lead">Configure auto leave, autoplay, repeat mode, and music command channel from one place.</div>
                          <div class="notification-toggle-stack">
                            <div class="grid2 module-settings-grid">
                              <div class="toggle"><input type="checkbox" id="m_autoLeaveEnabled"><label for="m_autoLeaveEnabled">autoLeaveEnabled</label></div>
                              <div class="field"><label>autoLeaveMinutes (1-60)</label><input type="number" min="1" max="60" id="m_autoLeaveMinutes"></div>
                              <div class="toggle"><input type="checkbox" id="m_autoplayEnabled"><label for="m_autoplayEnabled">autoplayEnabled</label></div>
                              <div class="field"><label>defaultRepeatMode</label><select id="m_defaultRepeatMode"><option value="OFF">OFF</option><option value="SINGLE">SINGLE</option><option value="ALL">ALL</option></select></div>
                              <div class="field span-all"><label>commandChannelId</label><select id="m_commandChannelId"></select></div>
                            </div>
                          </div>
                        </div>
                        <div class="settings-group">
                          <div id="music_stats_title" class="settings-group-title">Music Stats</div>
                          <div id="musicStatsCards" class="stats-grid"></div>
                        </div>
                      </div>
                      </div>

                      <div class="tab-pane" data-pane="privateRoom">
                      <div class="row pane-head">
                        <h3>privateRoom.*</h3>
                        <button id="resetPrivateRoomBtn" class="danger reset-btn" type="button">Reset Section</button>
                      </div>
                      <div class="grid3">
                        <div class="toggle"><input type="checkbox" id="p_enabled"><label for="p_enabled">enabled</label></div>
                        <div class="field"><label>triggerVoiceChannelId</label><select id="p_triggerVoiceChannelId"></select></div>
                        <div class="field"><label>userLimit (0-99)</label><input type="number" min="0" max="99" id="p_userLimit"></div>
                      </div>
                      </div>
                      <div class="tab-pane" data-pane="welcome">
                      <div class="row pane-head">
                        <h3 id="welcome_group_title">welcome.*</h3>
                      </div>
                      <div class="welcome-shell">
                        <div class="settings-group">
                          <div id="welcome_message_card_title" class="settings-group-title">Welcome Message</div>
                          <div id="welcome_message_lead" class="welcome-lead">Set the public welcome message shown to new members, including the target channel, title, and content.</div>
                          <div class="welcome-toggle">
                            <div class="welcome-topline">
                              <div class="field"><label id="label_w_channelId">welcomeChannelId</label><select id="w_channelId"></select></div>
                              <div class="toggle"><input type="checkbox" id="w_enabled"><label for="w_enabled">enabled</label></div>
                            </div>
                          </div>
                        <div class="welcome-compact-actions">
                          <button id="sendWelcomePreviewBtn" class="primary" type="button">Send Preview Message</button>
                          <button id="openWelcomeEditorBtn" class="warn" type="button">Configure Welcome Message</button>
                        </div>
                      </div>
""";
        html += """
                      <div id="welcomeEditorModal" class="modal-backdrop hidden">
                        <div class="modal-card">
                          <div class="modal-head">
                            <div id="welcomeEditorTitle" class="modal-title">Configure Welcome Message</div>
                            <button id="closeWelcomeEditorBtn" class="danger modal-close" type="button">Close</button>
                            </div>
                            <div class="modal-body">
                              <div class="settings-group">
                                <div id="welcome_message_card_title_modal" class="settings-group-title">Welcome Message</div>
                                <div class="grid2">
                                  <div class="field">
                                    <label id="label_w_title">welcomeTitle</label>
                                    <input id="w_title" type="text">
                                    <div id="hint_w_title" class="keyhint">Available placeholders: {user}, {guild}</div>
                                  </div>
                                  <div class="field">
                                    <label id="label_w_message">welcomeMessage</label>
                                    <textarea id="w_message"></textarea>
                                    <div id="hint_w_message" class="keyhint">Available placeholders: {user}, {username}, {guild}, {id}, {tag}, {isBot}, {createdAt}, {accountAgeDays}</div>
                                  </div>
                                </div>
                              </div>
                              <div class="settings-group">
                                <div id="welcome_media_card_title" class="settings-group-title">Welcome Media</div>
                                <div class="welcome-media-grid">
                                  <div class="welcome-card">
                                    <div id="welcome_thumbnail_card_title" class="welcome-card-title">Thumbnail</div>
                                    <div class="field">
                                      <label id="label_w_thumbnailUrl">welcomeThumbnailUrl</label>
                                      <input id="w_thumbnailUrl" type="url">
                                      <div id="hint_w_thumbnailUrl" class="keyhint">Use a direct `https://...` image URL for the small top-right thumbnail.</div>
                                    </div>
                                  </div>
                                  <div class="welcome-card">
                                    <div id="welcome_image_card_title" class="welcome-card-title">Large Image</div>
                                    <div class="field">
                                      <label id="label_w_imageUrl">welcomeImageUrl</label>
                                      <input id="w_imageUrl" type="url">
                                      <div id="hint_w_imageUrl" class="keyhint">Use a direct `https://...` image URL for the large image below the message.</div>
                                    </div>
                                  </div>
                                </div>
                              </div>
                              <div class="settings-group">
                                <div id="welcome_preview_card_title" class="settings-group-title">Live Preview</div>
                                <div id="welcomePreviewCard" class="welcome-preview"></div>
                              </div>
                            </div>
                            <div class="modal-actions">
                              <button id="saveWelcomeSettingsBtn" class="primary" type="button">Save Settings</button>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>

                    <div class="tab-pane" data-pane="numberChain"> 
                      <div class="row pane-head">
                        <h3>numberChain.*</h3>
                        <button id="resetNumberChainBtn" class="danger reset-btn" type="button">Reset Section</button>
                      </div>
                      <div class="grid3">
                        <div class="toggle"><input type="checkbox" id="nc_enabled"><label for="nc_enabled">enabled</label></div>
                        <div class="field">
                          <label id="label_nc_channelId">channelId</label>
                          <select id="nc_channelId"></select>
                        </div>
                        <div class="field">
                          <label id="label_nc_nextNumber">nextNumber</label>
                          <div class="field-inline">
                            <input type="number" id="nc_nextNumber" readonly>
                            <button id="resetNumberChainProgressBtn" class="warn" type="button">Reset Progress</button>
                          </div>
                        </div>
                      </div>
                    </div>

                
                    <div class="tab-pane" data-pane="ticket">
                      <div class="row pane-head">
                        <h3>ticket.*</h3>
                        <button id="resetTicketBtn" class="danger reset-btn" type="button">Reset Section</button>
                      </div>
                      <div class="welcome-shell module-shell">
                        <div class="settings-group">
                          <div id="ticket_group_basic_title" class="settings-group-title">Basic Settings</div>
                          <div class="welcome-lead">Configure ticket module status, close policy, and open mode.</div>
                          <div class="notification-toggle-stack">
                            <div class="grid2 module-settings-grid">
                              <div class="toggle"><input type="checkbox" id="t_enabled"><label for="t_enabled">enabled</label></div>
                              <div class="field"><label>autoCloseDays (1-365)</label><input type="number" min="1" max="365" id="t_autoCloseDays"></div>
                              <div class="field"><label>maxOpenPerUser (1-20)</label><input type="number" min="1" max="20" id="t_maxOpenPerUser"></div>
                              <div class="field span-all">
                                <label>openUiMode</label>
                                <select id="t_openUiMode">
                                  <option value="BUTTONS">BUTTONS</option>
                                  <option value="SELECT">SELECT</option>
                                </select>
                                <div id="hint_t_openUiMode" class="keyhint">BUTTONS: display open buttons. SELECT: use dropdown menu.</div>
                              </div>
                            </div>
                          </div>
                        </div>

                        <div class="settings-group">
                          <div id="ticket_group_access_title" class="settings-group-title">Access & Blacklist</div>
                          <div class="grid2">
                            <div class="field role-field">
                              <label>supportRoleIds</label>
                              <div class="role-meta">
                                <div id="hint_t_supportRoleIds" class="keyhint">Hold Ctrl/Cmd to select multiple roles.</div>
                                <div id="t_supportRoleCount" class="role-count">0</div>
                              </div>
                              <select id="t_supportRoleIds" class="role-multi" multiple size="7"></select>
                            </div>
                            <div class="field">
                              <label>blacklistedUserIds (comma separated)</label>
                              <input type="text" id="t_blacklistedUserIds">
                              <div id="hint_t_blacklistedUserIds" class="keyhint">Enter user IDs separated by commas.</div>
                            </div>
                          </div>
                        </div>

                        <div class="settings-group">
                          <div id="ticket_group_panel_title" class="settings-group-title">Panel Settings</div>
                          <div class="grid2">
                            <div class="field span-all">
                              <label>panelChannelId</label>
                              <div class="field-inline">
                                <select id="t_panelChannelId"></select>
                                <button id="sendTicketPanelBtn" class="warn" type="button">Send Ticket Panel</button>
                              </div>
                            </div>
                            <div class="field span-all">
                              <label id="label_t_panelTitle">panelTitle</label>
                              <input type="text" id="t_panelTitle">
                            </div>
                            <div class="field span-all">
                              <label id="label_t_panelDescription">panelDescription</label>
                              <textarea id="t_panelDescription"></textarea>
                            </div>
                            <div class="field">
                              <label id="label_t_panelColor">panelColor</label>
                              <input type="color" id="t_panelColor">
                            </div>
                            <div class="field">
                              <label>panelButtonLimit (1-25)</label>
                              <input type="number" min="1" max="25" id="t_panelButtonLimit">
                            </div>
                          </div>
                        </div>

                        <div class="settings-group">
                          <div id="ticket_group_options_title" class="settings-group-title">Ticket Types</div>
                          <div class="welcome-compact-actions">
                            <button id="t_addOptionBtn" class="primary" type="button">Add Ticket Type</button>
                            <button id="t_deleteOptionBtn" class="warn" type="button">Delete Ticket Type</button>
                          </div>
                          <div id="hint_t_optionEditor" class="keyhint">Create each ticket type separately. Each one can have its own panel text, button style, welcome message, and pre-open form.</div>
                          <div id="ticketOptionList" class="history-list"></div>
                          <div class="grid2">
                            <div class="field">
                              <label id="label_t_optionLabel">Option label</label>
                              <input type="text" id="t_optionLabel">
                            </div>
                            <div class="field">
                              <label id="label_t_optionButtonStyle">Button style</label>
                              <select id="t_optionButtonStyle">
                                <option value="PRIMARY">PRIMARY</option>
                                <option value="SECONDARY">SECONDARY</option>
                                <option value="SUCCESS">SUCCESS</option>
                                <option value="DANGER">DANGER</option>
                              </select>
                            </div>
                            <div class="field span-all">
                              <label id="label_t_optionPanelTitle">Panel title</label>
                              <input type="text" id="t_optionPanelTitle">
                            </div>
                            <div class="field span-all">
                              <label id="label_t_optionPanelDescription">Panel description</label>
                              <textarea id="t_optionPanelDescription"></textarea>
                            </div>
                            <div class="field span-all">
                              <label id="label_t_optionWelcomeMessage">Welcome message</label>
                              <textarea id="t_optionWelcomeMessage"></textarea>
                              <div id="hint_t_optionWelcomeMessage" class="keyhint">Available placeholders: {user}, {type}, {summary}</div>
                            </div>
                            <div class="toggle"><input type="checkbox" id="t_optionPreOpenFormEnabled"><label for="t_optionPreOpenFormEnabled" id="label_t_optionPreOpenFormEnabled">Enable pre-open form</label></div>
                            <div></div>
                            <div class="field">
                              <label id="label_t_optionPreOpenFormTitle">Form title</label>
                              <input type="text" id="t_optionPreOpenFormTitle">
                            </div>
                            <div class="field">
                              <label id="label_t_optionPreOpenFormLabel">Form field label</label>
                              <input type="text" id="t_optionPreOpenFormLabel">
                            </div>
                            <div class="field span-all">
                              <label id="label_t_optionPreOpenFormPlaceholder">Form placeholder</label>
                              <input type="text" id="t_optionPreOpenFormPlaceholder">
                            </div>
                          </div>
                        </div>
                      </div>
                      </div>

                    </div>

                    <div class="tab-pane" data-pane="ticketHistory">
                      <h3>ticket.history</h3>
                      <div class="settings-group">
                        <div id="ticket_group_history_title" class="settings-group-title">Ticket History</div>
                        <div class="row">
                          <button id="loadTicketHistoryBtn" type="button">Load History</button>
                        </div>
                        <div id="ticketHistoryMeta" class="keyhint"></div>
                        <div id="ticketHistoryList" class="history-list"></div>
                      </div>
                    </div>

                    </section>
                  </div>
                </div>
                <script type="module" src="/web/app.js"></script>
                <div id="toastHost" class="toast-host" aria-live="polite" aria-atomic="true"></div>
                </body>
                </html>
                """;
        return html.replace("__BOT_AVATAR_BLOCK__", botAvatarBlock)
                .replace("__FAVICON_URL__", escapeHtmlAttr(faviconUrl));
    }

    static class OAuthState {
        final long expiresAtMillis;

        OAuthState(long expiresAtMillis) {
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    static class WebSession {
        final String userId;
        final String username;
        final String avatarUrl;
        final String accessToken;
        final long expiresAtMillis;

        WebSession(String userId, String username, String avatarUrl, String accessToken, long expiresAtMillis) {
            this.userId = userId;
            this.username = username;
            this.avatarUrl = avatarUrl;
            this.accessToken = accessToken;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}









