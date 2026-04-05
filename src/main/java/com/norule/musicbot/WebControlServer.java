package com.norule.musicbot;

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
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.yaml.snakeyaml.Yaml;

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
            created.createContext("/auth/login", this::handleAuthLogin);
            created.createContext("/auth/callback", this::handleAuthCallback);
            created.createContext("/auth/logout", this::handleAuthLogout);
            created.createContext("/api/me", this::handleApiMe);
            created.createContext("/api/guilds", this::handleApiGuilds);
            created.createContext("/api/web/i18n", this::handleApiWebI18n);
            created.createContext("/api/guild/", this::handleApiGuildRoute);
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

    private void handleAuthLogin(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        BotConfig.Web web = configSupplier.get().getWeb();
        String state = UUID.randomUUID().toString().replace("-", "");
        oauthStates.put(state, new OAuthState(System.currentTimeMillis() + OAUTH_STATE_TTL.toMillis()));

        String authorizeUrl = "https://discord.com/oauth2/authorize"
                + "?response_type=code"
                + "&client_id=" + encode(web.getDiscordClientId())
                + "&scope=" + encode("identify guilds")
                + "&redirect_uri=" + encode(web.getDiscordRedirectUri())
                + "&state=" + encode(state);

        redirect(exchange, authorizeUrl);
    }

    private void handleAuthCallback(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        Map<String, String> query = parseUrlEncoded(exchange.getRequestURI().getRawQuery());
        String state = query.getOrDefault("state", "");
        String code = query.getOrDefault("code", "");
        if (code.isBlank() || state.isBlank()) {
            sendText(exchange, 400, "Missing code/state");
            return;
        }

        OAuthState stateData = oauthStates.remove(state);
        if (stateData == null || stateData.expiresAtMillis < System.currentTimeMillis()) {
            sendText(exchange, 401, "OAuth state expired");
            return;
        }

        BotConfig.Web web = configSupplier.get().getWeb();
        try {
            String accessToken = exchangeToken(web, code);
            DataObject me = fetchMe(accessToken);
            String userId = me.getString("id", "");
            String username = me.getString("username", "");
            String avatarUrl = buildAvatarUrl(me);
            if (userId.isBlank()) {
                sendText(exchange, 401, "Failed to get user profile");
                return;
            }

            long ttlMillis = Math.max(5, web.getSessionExpireMinutes()) * 60_000L;
            String sessionId = UUID.randomUUID().toString().replace("-", "");
            sessions.put(sessionId, new WebSession(userId, username, avatarUrl, accessToken, System.currentTimeMillis() + ttlMillis));
            setSessionCookie(exchange, sessionId, web);
            redirect(exchange, resolveHomeUrl(web));
        } catch (Exception e) {
            sendText(exchange, 401, "OAuth failed: " + e.getMessage());
        }
    }

    private void handleAuthLogout(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        clearSessionCookie(exchange, configSupplier.get().getWeb());
        redirect(exchange, "/");
    }

    private void handleApiMe(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        WebSession session = requireSession(exchange);
        if (session == null) {
            sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
            return;
        }
        sendJson(exchange, 200, DataObject.empty()
                .put("id", session.userId)
                .put("username", session.username)
                .put("avatarUrl", session.avatarUrl));
    }

    private void handleApiWebI18n(HttpExchange exchange) throws IOException {
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
    private void handleApiGuilds(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        WebSession session = requireSession(exchange);
        if (session == null) {
            sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
            return;
        }
        if (session.accessToken == null || session.accessToken.isBlank()) {
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

    private void handleApiGuildRoute(HttpExchange exchange) throws IOException {
        WebSession session = requireSession(exchange);
        if (session == null) {
            sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/api/guild/".length());
        String[] segments = suffix.split("/");
        if (segments.length < 2) {
            sendJson(exchange, 404, DataObject.empty().put("error", "Not Found"));
            return;
        }

        long guildId = parseLong(segments[0], -1L);
        if (guildId <= 0L) {
            sendJson(exchange, 400, DataObject.empty().put("error", "Invalid guild id"));
            return;
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            sendJson(exchange, 404, DataObject.empty().put("error", "Guild not found"));
            return;
        }

        Member member = resolveMember(guild, parseLong(session.userId, -1L));
        if (member == null) {
            sendJson(exchange, 403, DataObject.empty().put("error", "You are not in this guild"));
            return;
        }
        if (!hasControlPermission(member)) {
            sendJson(exchange, 403, DataObject.empty().put("error", "Missing permission: Manage Server"));
            return;
        }

        String section = segments[1];
        if ("music".equals(section)) {
            if (segments.length < 3) {
                sendJson(exchange, 404, DataObject.empty().put("error", "Unknown action"));
                return;
            }
            String action = segments[2];
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && "state".equals(action)) {
                handleMusicState(exchange, guild, member);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
                return;
            }

            Map<String, String> form = parseUrlEncoded(readBody(exchange));
            switch (action) {
                case "play" -> handleMusicPlay(exchange, guild, member, form);
                case "toggle-pause" -> {
                    boolean paused = musicService.togglePause(guild);
                    sendJson(exchange, 200, DataObject.empty().put("ok", true).put("paused", paused));
                }
                case "skip" -> {
                    musicService.skip(guild);
                    sendJson(exchange, 200, DataObject.empty().put("ok", true));
                }
                case "stop" -> {
                    musicService.stop(guild);
                    sendJson(exchange, 200, DataObject.empty().put("ok", true));
                }
                case "leave" -> {
                    musicService.leaveChannel(guild);
                    sendJson(exchange, 200, DataObject.empty().put("ok", true));
                }
                case "repeat" -> handleMusicRepeat(exchange, guild, form);
                case "autoplay" -> handleAutoplayToggle(exchange, guild, form);
                default -> sendJson(exchange, 404, DataObject.empty().put("error", "Unknown action"));
            }
            return;
        }

        if ("settings".equals(section)) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleSettingsGet(exchange, guild);
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleSettingsSave(exchange, guild);
                return;
            }
            sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }

            if ("ticket".equals(section)) {
            if (segments.length < 3) {
                sendJson(exchange, 404, DataObject.empty().put("error", "Unknown ticket action"));
                return;
            }
            String action = segments[2];
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && "transcripts".equals(action)) {
                handleTicketTranscriptList(exchange, guild);
                return;
            }
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && "transcript".equals(action)) {
                if (segments.length < 4) {
                    sendJson(exchange, 404, DataObject.empty().put("error", "Missing transcript name"));
                    return;
                }
                handleTicketTranscriptDownload(exchange, guild, segments[3]);
                return;
            }
            if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod()) && "transcript".equals(action)) {
                if (segments.length < 4) {
                    sendJson(exchange, 404, DataObject.empty().put("error", "Missing transcript name"));
                    return;
                }
                handleTicketTranscriptDelete(exchange, guild, segments[3]);
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && "panel".equals(action)) {
                handleTicketPanelSend(exchange, guild);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
                    && !"POST".equalsIgnoreCase(exchange.getRequestMethod())
                    && !"DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
                return;
            }
            sendJson(exchange, 404, DataObject.empty().put("error", "Unknown ticket action"));
            return;
        }

        if ("number-chain".equals(section)) {
            if (segments.length < 3) {
                sendJson(exchange, 404, DataObject.empty().put("error", "Unknown number chain action"));
                return;
            }
            String action = segments[2];
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && "reset".equals(action)) {
                moderationService.resetNumberChain(guild.getIdLong());
                sendJson(exchange, 200, DataObject.empty()
                        .put("ok", true)
                        .put("nextNumber", moderationService.getNumberChainNext(guild.getIdLong())));
                return;
            }
            sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }

        if ("channels".equals(section)) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleGuildChannelsGet(exchange, guild);
                return;
            }
            sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }

        if ("roles".equals(section)) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleGuildRolesGet(exchange, guild);
                return;
            }
            sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }

        sendJson(exchange, 404, DataObject.empty().put("error", "Unknown section"));
    }

    private void handleMusicState(HttpExchange exchange, Guild guild, Member member) throws IOException {
        AudioTrack current = musicService.getCurrentTrack(guild);
        var botVoiceState = guild.getSelfMember().getVoiceState();
        var userVoiceState = member.getVoiceState();

        DataArray queue = DataArray.empty();
        for (AudioTrack track : musicService.getQueueSnapshot(guild).stream().limit(8).toList()) {
            queue.add(DataObject.empty()
                    .put("title", track.getInfo().title == null ? "-" : track.getInfo().title)
                    .put("duration", formatDuration(track.getDuration())));
        }

        String autoplayNotice = musicService.getAutoplayNotice(guild.getIdLong());
        sendJson(exchange, 200, DataObject.empty()
                .put("guildId", guild.getId())
                .put("guildName", guild.getName())
                .put("connected", botVoiceState != null && botVoiceState.inAudioChannel())
                .put("botChannel", botVoiceState != null && botVoiceState.inAudioChannel() ? botVoiceState.getChannel().getAsMention() : "-")
                .put("yourVoice", userVoiceState != null && userVoiceState.inAudioChannel() ? userVoiceState.getChannel().getAsMention() : "-")
                .put("title", current == null ? "-" : current.getInfo().title)
                .put("source", musicService.getCurrentSource(guild))
                .put("requester", musicService.getCurrentRequesterDisplay(guild))
                .put("position", formatDuration(musicService.getCurrentPositionMillis(guild)))
                .put("duration", formatDuration(musicService.getCurrentDurationMillis(guild)))
                .put("paused", musicService.isPaused(guild))
                .put("repeatMode", musicService.getRepeatMode(guild))
                .put("autoplayEnabled", settingsService.getMusic(guild.getIdLong()).isAutoplayEnabled())
                .put("autoplayNotice", autoplayNotice == null ? "" : autoplayNotice)
                .put("queue", queue));
    }

    private void handleMusicPlay(HttpExchange exchange, Guild guild, Member member, Map<String, String> form) throws IOException {
        String query = form.getOrDefault("query", "").trim();
        if (query.isBlank()) {
            sendJson(exchange, 400, DataObject.empty().put("error", "Missing query"));
            return;
        }

        var memberVoiceState = member.getVoiceState();
        if (memberVoiceState == null || !memberVoiceState.inAudioChannel()) {
            sendJson(exchange, 400, DataObject.empty().put("error", "You must join a voice channel first"));
            return;
        }

        AudioChannel memberChannel = memberVoiceState.getChannel();
        var botVoiceState = guild.getSelfMember().getVoiceState();
        if (botVoiceState != null && botVoiceState.inAudioChannel() && !botVoiceState.getChannel().getId().equals(memberChannel.getId())) {
            sendJson(exchange, 400, DataObject.empty().put("error", "Bot is in " + botVoiceState.getChannel().getAsMention() + ". Join that channel first."));
            return;
        }

        if (botVoiceState == null || !botVoiceState.inAudioChannel()) {
            musicService.joinChannel(guild, memberChannel);
        }
        musicService.loadAndPlay(guild, msg -> { }, query, member.getIdLong(), member.getEffectiveName());
        sendJson(exchange, 200, DataObject.empty().put("ok", true).put("message", "Queued: " + query));
    }

    private void handleMusicRepeat(HttpExchange exchange, Guild guild, Map<String, String> form) throws IOException {
        String mode = form.getOrDefault("mode", "").trim().toLowerCase();
        if (!mode.equals("off") && !mode.equals("single") && !mode.equals("all")) {
            sendJson(exchange, 400, DataObject.empty().put("error", "mode must be off/single/all"));
            return;
        }
        musicService.setRepeatMode(guild, mode);
        sendJson(exchange, 200, DataObject.empty().put("ok", true).put("repeatMode", musicService.getRepeatMode(guild)));
    }

    private void handleAutoplayToggle(HttpExchange exchange, Guild guild, Map<String, String> form) throws IOException {
        String raw = form.getOrDefault("enabled", "").trim().toLowerCase();
        if (!raw.equals("true") && !raw.equals("false")) {
            sendJson(exchange, 400, DataObject.empty().put("error", "enabled must be true/false"));
            return;
        }
        boolean enabled = Boolean.parseBoolean(raw);
        settingsService.updateSettings(guild.getIdLong(), s -> s.withMusic(s.getMusic().withAutoplayEnabled(enabled)));
        sendJson(exchange, 200, DataObject.empty().put("ok", true).put("autoplayEnabled", enabled));
    }

    private void handleTicketPanelSend(HttpExchange exchange, Guild guild) throws IOException {
        String lang = settingsService.getLanguage(guild.getIdLong());
        BotConfig.Ticket ticket = settingsService.getTicket(guild.getIdLong());
        if (!ticket.isEnabled()) {
            sendJson(exchange, 400, DataObject.empty().put("error", i18n.t(lang, "ticket.disabled")));
            return;
        }
        Long panelChannelId = ticket.getPanelChannelId();
        if (panelChannelId == null || panelChannelId <= 0L) {
            sendJson(exchange, 400, DataObject.empty().put("error", i18n.t(lang, "settings.validation_expected_text_channel")));
            return;
        }
        TextChannel target = guild.getTextChannelById(panelChannelId);
        if (target == null) {
            sendJson(exchange, 404, DataObject.empty().put("error", i18n.t(lang, "ticket.panel_send_failed")));
            return;
        }

        List<BotConfig.Ticket.TicketOption> options = resolveTicketOptions(ticket, lang);
        EmbedBuilder panel = new EmbedBuilder()
                .setColor(new java.awt.Color(ticket.getPanelColor()))
                .setTitle(resolvePublicPanelTitle(ticket, lang))
                .setDescription(resolvePublicPanelDescription(ticket, lang))
                .setTimestamp(java.time.Instant.now());
        if (options.size() > 1) {
            for (BotConfig.Ticket.TicketOption option : options) {
                panel.addField(option.getLabel(), resolvePanelDescription(ticket, option, lang), false);
            }
        }

        target.sendMessageEmbeds(panel.build())
                .setComponents(buildTicketPanelOpenComponents(ticket, lang))
                .queue(
                        ok -> {
                            try {
                                sendJson(exchange, 200, DataObject.empty()
                                        .put("ok", true)
                                        .put("message", i18n.t(lang, "ticket.panel_sent", Map.of("channel", target.getAsMention()))));
                            } catch (IOException ignored) {
                            }
                        },
                        err -> {
                            try {
                                sendJson(exchange, 500, DataObject.empty()
                                        .put("error", i18n.t(lang, "ticket.panel_send_failed")));
                            } catch (IOException ignored) {
                            }
                        }
                );
    }

    private List<BotConfig.Ticket.TicketOption> resolveTicketOptions(BotConfig.Ticket ticket, String lang) {
        List<BotConfig.Ticket.TicketOption> options = new ArrayList<>(ticket.getOptions());
        if (options.isEmpty()) {
            options = List.of(new BotConfig.Ticket.TicketOption(
                    "general",
                    i18n.t(lang, "ticket.default_type_label"),
                    ticket.getPanelTitle(),
                    ticket.getPanelDescription(),
                    ticket.getPanelButtonStyle(),
                    ticket.getWelcomeMessage(),
                    ticket.isPreOpenFormEnabled(),
                    ticket.getPreOpenFormTitle(),
                    ticket.getPreOpenFormLabel(),
                    ticket.getPreOpenFormPlaceholder()
            ));
        }
        int limit = Math.max(1, Math.min(25, ticket.getPanelButtonLimit()));
        if (options.size() <= limit) {
            return options;
        }
        return new ArrayList<>(options.subList(0, limit));
    }

    private List<ActionRow> buildTicketPanelOpenComponents(BotConfig.Ticket ticket, String lang) {
        List<BotConfig.Ticket.TicketOption> options = resolveTicketOptions(ticket, lang);
        if (ticket.getOpenUiMode() == BotConfig.Ticket.OpenUiMode.SELECT) {
            StringSelectMenu.Builder menu = StringSelectMenu.create("ticket:open:panel-select")
                    .setPlaceholder(i18n.t(lang, "ticket.select_placeholder"));
            for (BotConfig.Ticket.TicketOption option : options) {
                menu.addOptions(SelectOption.of(option.getLabel(), option.getId()));
            }
            return List.of(ActionRow.of(menu.build()));
        }

        List<Button> buttons = new ArrayList<>();
        if (options.size() == 1) {
            BotConfig.Ticket.TicketOption option = options.get(0);
            buttons.add(createOpenButton(option.getPanelButtonStyle(), "ticket:open", option.getLabel()));
        } else {
            for (BotConfig.Ticket.TicketOption option : options) {
                buttons.add(createOpenButton(option.getPanelButtonStyle(), "ticket:open:option:" + option.getId(), option.getLabel()));
            }
        }
        List<ActionRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += 5) {
            rows.add(ActionRow.of(buttons.subList(i, Math.min(i + 5, buttons.size()))));
        }
        if (rows.isEmpty()) {
            rows.add(ActionRow.of(createOpenButton(ticket.getPanelButtonStyle(), "ticket:open", i18n.t(lang, "ticket.panel_open_button"))));
        }
        return rows;
    }

    private String resolvePublicPanelTitle(BotConfig.Ticket ticket, String lang) {
        String custom = ticket.getPanelTitle() == null ? "" : ticket.getPanelTitle().trim();
        return custom.isBlank() ? i18n.t(lang, "ticket.panel_title") : custom;
    }

    private String resolvePublicPanelDescription(BotConfig.Ticket ticket, String lang) {
        String custom = ticket.getPanelDescription() == null ? "" : ticket.getPanelDescription().trim();
        return custom.isBlank() ? i18n.t(lang, "ticket.panel_desc") : custom;
    }

    private String resolvePanelTitle(BotConfig.Ticket ticket, BotConfig.Ticket.TicketOption option, String lang) {
        String custom = option == null ? "" : option.getPanelTitle().trim();
        if (custom.isBlank()) {
            custom = ticket.getPanelTitle() == null ? "" : ticket.getPanelTitle().trim();
        }
        return custom.isBlank() ? i18n.t(lang, "ticket.panel_title") : custom;
    }

    private String resolvePanelDescription(BotConfig.Ticket ticket, BotConfig.Ticket.TicketOption option, String lang) {
        String custom = option == null ? "" : option.getPanelDescription().trim();
        if (custom.isBlank()) {
            custom = ticket.getPanelDescription() == null ? "" : ticket.getPanelDescription().trim();
        }
        return custom.isBlank() ? i18n.t(lang, "ticket.panel_desc") : custom;
    }

    private Button createOpenButton(String style, String id, String label) {
        String normalized = style == null ? "PRIMARY" : style.trim().toUpperCase();
        return switch (normalized) {
            case "SECONDARY" -> Button.secondary(id, label);
            case "SUCCESS" -> Button.success(id, label);
            case "DANGER" -> Button.danger(id, label);
            default -> Button.primary(id, label);
        };
    }

    private void handleTicketTranscriptList(HttpExchange exchange, Guild guild) throws IOException {
        long guildId = guild.getIdLong();
        int removed = ticketService.cleanupOldTranscripts(guildId, TICKET_HISTORY_RETENTION_DAYS);
        List<TicketService.TranscriptFile> files = ticketService.listTranscripts(guildId, 500);

        DataArray rows = DataArray.empty();
        for (TicketService.TranscriptFile file : files) {
            rows.add(DataObject.empty()
                    .put("name", file.getFileName())
                    .put("size", file.getSize())
                    .put("lastModifiedAt", file.getLastModifiedAt())
                    .put("channelId", file.getChannelId())
                    .put("url", "/api/guild/" + guild.getId() + "/ticket/transcript/" + encode(file.getFileName())));
        }

        sendJson(exchange, 200, DataObject.empty()
                .put("retentionDays", TICKET_HISTORY_RETENTION_DAYS)
                .put("cleaned", removed)
                .put("files", rows));
    }

    private void handleTicketTranscriptDownload(HttpExchange exchange, Guild guild, String encodedFileName) throws IOException {
        String fileName = decode(encodedFileName);
        Path file = ticketService.resolveTranscriptFile(guild.getIdLong(), fileName);
        if (file == null || !Files.exists(file) || !Files.isRegularFile(file)) {
            sendJson(exchange, 404, DataObject.empty().put("error", "Transcript not found"));
            return;
        }
        byte[] content = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + file.getFileName().toString().replace("\"", "") + "\"");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    private void handleTicketTranscriptDelete(HttpExchange exchange, Guild guild, String encodedFileName) throws IOException {
        String lang = settingsService.getLanguage(guild.getIdLong());
        String fileName = decode(encodedFileName);
        if (fileName == null || fileName.isBlank()) {
            sendJson(exchange, 404, DataObject.empty().put("error", i18n.t(lang, "ticket.history_delete_failed")));
            return;
        }
        boolean deleted = ticketService.deleteTranscript(guild.getIdLong(), fileName);
        if (!deleted) {
            sendJson(exchange, 404, DataObject.empty().put("error", i18n.t(lang, "ticket.history_delete_failed")));
            return;
        }
        sendJson(exchange, 200, DataObject.empty()
                .put("ok", true)
                .put("message", i18n.t(lang, "ticket.history_delete_success", Map.of("name", fileName))));
    }

    private void handleSettingsGet(HttpExchange exchange, Guild guild) throws IOException {
        GuildSettingsService.GuildSettings settings = settingsService.getSettings(guild.getIdLong());
        BotConfig.Notifications n = settings.getNotifications();
        BotConfig.MessageLogs l = settings.getMessageLogs();
        BotConfig.Music m = settings.getMusic();
        BotConfig.PrivateRoom p = settings.getPrivateRoom();
        BotConfig.Ticket t = settings.getTicket();
        MusicDataService.MusicStatsSnapshot musicStats = musicService.getStats(guild.getIdLong());
        String topRequesterDisplay = "";
        if (musicStats.topRequesterId() != null) {
            Member topRequester = guild.getMemberById(musicStats.topRequesterId());
            topRequesterDisplay = topRequester != null ? topRequester.getEffectiveName() : musicStats.topRequesterId().toString();
        }

        DataObject payload = DataObject.empty()
                .put("language", settings.getLanguage())
                .put("notifications", DataObject.empty()
                        .put("enabled", n.isEnabled())
                        .put("memberJoinEnabled", n.isMemberJoinEnabled())
                        .put("memberLeaveEnabled", n.isMemberLeaveEnabled())
                        .put("voiceLogEnabled", n.isVoiceLogEnabled())
                        .put("memberChannelId", toIdText(n.getMemberChannelId()))
                        .put("memberJoinChannelId", toIdText(n.getMemberJoinChannelId()))
                        .put("memberLeaveChannelId", toIdText(n.getMemberLeaveChannelId()))
                        .put("memberJoinMessage", n.getMemberJoinMessage())
                        .put("memberLeaveMessage", n.getMemberLeaveMessage())
                        .put("memberJoinColor", String.format("#%06X", n.getMemberJoinColor()))
                        .put("memberLeaveColor", String.format("#%06X", n.getMemberLeaveColor()))
                        .put("voiceChannelId", toIdText(n.getVoiceChannelId()))
                        .put("voiceJoinMessage", n.getVoiceJoinMessage())
                        .put("voiceLeaveMessage", n.getVoiceLeaveMessage())
                        .put("voiceMoveMessage", n.getVoiceMoveMessage()))
                .put("messageLogs", DataObject.empty()
                        .put("enabled", l.isEnabled())
                        .put("channelId", toIdText(l.getChannelId()))
                        .put("messageLogChannelId", toIdText(l.getMessageLogChannelId()))
                        .put("commandUsageChannelId", toIdText(l.getCommandUsageChannelId()))
                        .put("channelLifecycleChannelId", toIdText(l.getChannelLifecycleChannelId()))
                        .put("roleLogChannelId", toIdText(l.getRoleLogChannelId()))
                        .put("moderationLogChannelId", toIdText(l.getModerationLogChannelId()))
                        .put("roleLogEnabled", l.isRoleLogEnabled())
                        .put("channelLifecycleLogEnabled", l.isChannelLifecycleLogEnabled())
                        .put("moderationLogEnabled", l.isModerationLogEnabled())
                        .put("commandUsageLogEnabled", l.isCommandUsageLogEnabled()))
                .put("music", DataObject.empty()
                        .put("autoLeaveEnabled", m.isAutoLeaveEnabled())
                        .put("autoLeaveMinutes", m.getAutoLeaveMinutes())
                        .put("autoplayEnabled", m.isAutoplayEnabled())
                        .put("defaultRepeatMode", m.getDefaultRepeatMode().name())
                        .put("commandChannelId", toIdText(m.getCommandChannelId())))
                .put("musicStats", DataObject.empty()
                        .put("topSongLabel", musicStats.topSongLabel() == null ? "" : musicStats.topSongLabel())
                        .put("topSongCount", musicStats.topSongCount())
                        .put("topRequesterDisplay", topRequesterDisplay)
                        .put("topRequesterCount", musicStats.topRequesterCount())
                        .put("todayPlaybackMillis", musicStats.todayPlaybackMillis())
                        .put("todayPlaybackDisplay", formatDuration(musicStats.todayPlaybackMillis()))
                        .put("historyCount", musicStats.historyCount()))
                .put("privateRoom", DataObject.empty()
                        .put("enabled", p.isEnabled())
                        .put("triggerVoiceChannelId", toIdText(p.getTriggerVoiceChannelId()))
                        .put("userLimit", p.getUserLimit()))
                .put("numberChain", DataObject.empty()
                        .put("enabled", moderationService.isNumberChainEnabled(guild.getIdLong()))
                        .put("channelId", toIdText(moderationService.getNumberChainChannelId(guild.getIdLong())))
                        .put("nextNumber", moderationService.getNumberChainNext(guild.getIdLong())))
                .put("ticket", DataObject.empty()
                        .put("enabled", t.isEnabled())
                        .put("panelChannelId", toIdText(t.getPanelChannelId()))
                        .put("autoCloseDays", t.getAutoCloseDays())
                        .put("maxOpenPerUser", t.getMaxOpenPerUser())
                        .put("openUiMode", t.getOpenUiMode().name())
                        .put("panelTitle", t.getPanelTitle())
                        .put("panelDescription", t.getPanelDescription())
                        .put("panelColor", String.format("#%06X", t.getPanelColor() & 0xFFFFFF))
                        .put("panelButtonStyle", t.getPanelButtonStyle())
                        .put("panelButtonLimit", t.getPanelButtonLimit())
                        .put("preOpenFormEnabled", t.isPreOpenFormEnabled())
                        .put("preOpenFormTitle", t.getPreOpenFormTitle())
                        .put("preOpenFormLabel", t.getPreOpenFormLabel())
                        .put("preOpenFormPlaceholder", t.getPreOpenFormPlaceholder())
                        .put("welcomeMessage", t.getWelcomeMessage())
                        .put("optionLabels", String.join(", ", t.getOptionLabels()))
                        .put("options", DataArray.fromCollection(t.getOptions().stream().map(option -> DataObject.empty()
                                .put("id", option.getId())
                                .put("label", option.getLabel())
                                .put("panelTitle", option.getPanelTitle())
                                .put("panelDescription", option.getPanelDescription())
                                .put("panelButtonStyle", option.getPanelButtonStyle())
                                .put("welcomeMessage", option.getWelcomeMessage())
                                .put("preOpenFormEnabled", option.isPreOpenFormEnabled())
                                .put("preOpenFormTitle", option.getPreOpenFormTitle())
                                .put("preOpenFormLabel", option.getPreOpenFormLabel())
                                .put("preOpenFormPlaceholder", option.getPreOpenFormPlaceholder()))
                                .toList()))
                        .put("supportRoleIds", t.getSupportRoleIds().stream().map(String::valueOf).toList())
                        .put("blacklistedUserIds", t.getBlacklistedUserIds().stream().map(String::valueOf).toList()));
        sendJson(exchange, 200, payload);
    }

    private void handleSettingsSave(HttpExchange exchange, Guild guild) throws IOException {
        String body = readBody(exchange);
        Map<String, Object> root;
        try {
            root = asMap(new Yaml().load(body));
        } catch (Exception e) {
            sendJson(exchange, 400, DataObject.empty().put("error", "Invalid settings JSON"));
            return;
        }
        if (root.isEmpty()) {
            sendJson(exchange, 400, DataObject.empty().put("error", "Empty settings payload"));
            return;
        }

        GuildSettingsService.GuildSettings updated = settingsService.updateSettings(guild.getIdLong(), current -> {
            String oldLanguage = current.getLanguage();
            String language = stringOrDefault(root, "language", current.getLanguage());
            boolean languageChanged = !normalizeLang(oldLanguage).equalsIgnoreCase(normalizeLang(language));

            BotConfig.Notifications n = current.getNotifications();
            Map<String, Object> nMap = asMap(root.get("notifications"));
            if (!nMap.isEmpty()) {
                n = n.withEnabled(boolOrDefault(nMap, "enabled", n.isEnabled()))
                        .withMemberJoinEnabled(boolOrDefault(nMap, "memberJoinEnabled", n.isMemberJoinEnabled()))
                        .withMemberLeaveEnabled(boolOrDefault(nMap, "memberLeaveEnabled", n.isMemberLeaveEnabled()))
                        .withVoiceLogEnabled(boolOrDefault(nMap, "voiceLogEnabled", n.isVoiceLogEnabled()))
                        .withMemberChannelId(idOrDefault(nMap, "memberChannelId", n.getMemberChannelId()))
                        .withMemberJoinChannelId(idOrDefault(nMap, "memberJoinChannelId", n.getMemberJoinChannelId()))
                        .withMemberLeaveChannelId(idOrDefault(nMap, "memberLeaveChannelId", n.getMemberLeaveChannelId()))
                        .withMemberJoinMessage(stringOrDefault(nMap, "memberJoinMessage", n.getMemberJoinMessage()))
                        .withMemberLeaveMessage(stringOrDefault(nMap, "memberLeaveMessage", n.getMemberLeaveMessage()))
                        .withMemberJoinColor(colorOrDefault(nMap, "memberJoinColor", n.getMemberJoinColor()))
                        .withMemberLeaveColor(colorOrDefault(nMap, "memberLeaveColor", n.getMemberLeaveColor()))
                        .withVoiceChannelId(idOrDefault(nMap, "voiceChannelId", n.getVoiceChannelId()))
                        .withVoiceJoinMessage(stringOrDefault(nMap, "voiceJoinMessage", n.getVoiceJoinMessage()))
                        .withVoiceLeaveMessage(stringOrDefault(nMap, "voiceLeaveMessage", n.getVoiceLeaveMessage()))
                        .withVoiceMoveMessage(stringOrDefault(nMap, "voiceMoveMessage", n.getVoiceMoveMessage()));
            }

            if (languageChanged) {
                BotConfig.Notifications localized = notificationDefaultsForLanguage(language);
                if (shouldAutoApplyTemplate(nMap, "memberJoinMessage", current.getNotifications().getMemberJoinMessage())) {
                    n = n.withMemberJoinMessage(localized.getMemberJoinMessage());
                }
                if (shouldAutoApplyTemplate(nMap, "memberLeaveMessage", current.getNotifications().getMemberLeaveMessage())) {
                    n = n.withMemberLeaveMessage(localized.getMemberLeaveMessage());
                }
                if (shouldAutoApplyTemplate(nMap, "voiceJoinMessage", current.getNotifications().getVoiceJoinMessage())) {
                    n = n.withVoiceJoinMessage(localized.getVoiceJoinMessage());
                }
                if (shouldAutoApplyTemplate(nMap, "voiceLeaveMessage", current.getNotifications().getVoiceLeaveMessage())) {
                    n = n.withVoiceLeaveMessage(localized.getVoiceLeaveMessage());
                }
                if (shouldAutoApplyTemplate(nMap, "voiceMoveMessage", current.getNotifications().getVoiceMoveMessage())) {
                    n = n.withVoiceMoveMessage(localized.getVoiceMoveMessage());
                }
            }

            BotConfig.MessageLogs l = current.getMessageLogs();
            Map<String, Object> lMap = asMap(root.get("messageLogs"));
            if (!lMap.isEmpty()) {
                l = l.withEnabled(boolOrDefault(lMap, "enabled", l.isEnabled()))
                        .withChannelId(idOrDefault(lMap, "channelId", l.getChannelId()))
                        .withMessageLogChannelId(idOrDefault(lMap, "messageLogChannelId", l.getMessageLogChannelId()))
                        .withCommandUsageChannelId(idOrDefault(lMap, "commandUsageChannelId", l.getCommandUsageChannelId()))
                        .withChannelLifecycleChannelId(idOrDefault(lMap, "channelLifecycleChannelId", l.getChannelLifecycleChannelId()))
                        .withRoleLogChannelId(idOrDefault(lMap, "roleLogChannelId", l.getRoleLogChannelId()))
                        .withModerationLogChannelId(idOrDefault(lMap, "moderationLogChannelId", l.getModerationLogChannelId()))
                        .withRoleLogEnabled(boolOrDefault(lMap, "roleLogEnabled", l.isRoleLogEnabled()))
                        .withChannelLifecycleLogEnabled(boolOrDefault(lMap, "channelLifecycleLogEnabled", l.isChannelLifecycleLogEnabled()))
                        .withModerationLogEnabled(boolOrDefault(lMap, "moderationLogEnabled", l.isModerationLogEnabled()))
                        .withCommandUsageLogEnabled(boolOrDefault(lMap, "commandUsageLogEnabled", l.isCommandUsageLogEnabled()));
            }

            BotConfig.Music m = current.getMusic();
            Map<String, Object> mMap = asMap(root.get("music"));
            if (!mMap.isEmpty()) {
                m = m.withAutoLeaveEnabled(boolOrDefault(mMap, "autoLeaveEnabled", m.isAutoLeaveEnabled()))
                        .withAutoLeaveMinutes(intOrDefault(mMap, "autoLeaveMinutes", m.getAutoLeaveMinutes(), 1, 60))
                        .withAutoplayEnabled(boolOrDefault(mMap, "autoplayEnabled", m.isAutoplayEnabled()))
                        .withDefaultRepeatMode(parseRepeatMode(stringOrDefault(mMap, "defaultRepeatMode", m.getDefaultRepeatMode().name())))
                        .withCommandChannelId(idOrDefault(mMap, "commandChannelId", m.getCommandChannelId()));
            }

            BotConfig.PrivateRoom p = current.getPrivateRoom();
            Map<String, Object> pMap = asMap(root.get("privateRoom"));
            if (!pMap.isEmpty()) {
                p = p.withEnabled(boolOrDefault(pMap, "enabled", p.isEnabled()))
                        .withTriggerVoiceChannelId(idOrDefault(pMap, "triggerVoiceChannelId", p.getTriggerVoiceChannelId()))
                        .withUserLimit(intOrDefault(pMap, "userLimit", p.getUserLimit(), 0, 99));
            }

            Map<String, Object> ncMap = asMap(root.get("numberChain"));
            if (!ncMap.isEmpty()) {
                moderationService.setNumberChainEnabled(
                        guild.getIdLong(),
                        boolOrDefault(ncMap, "enabled", moderationService.isNumberChainEnabled(guild.getIdLong()))
                );
                Long currentChannelId = moderationService.getNumberChainChannelId(guild.getIdLong());
                Long nextChannelId = idOrDefault(ncMap, "channelId", currentChannelId);
                if (!Objects.equals(currentChannelId, nextChannelId)) {
                    moderationService.setNumberChainChannelId(guild.getIdLong(), nextChannelId);
                }
            }

            BotConfig.Ticket t = current.getTicket();
            Map<String, Object> tMap = asMap(root.get("ticket"));
            if (!tMap.isEmpty()) {
                List<BotConfig.Ticket.TicketOption> options = parseTicketOptions(tMap, t.getOptions());
                t = t.withEnabled(boolOrDefault(tMap, "enabled", t.isEnabled()))
                        .withPanelChannelId(idOrDefault(tMap, "panelChannelId", t.getPanelChannelId()))
                        .withAutoCloseDays(intOrDefault(tMap, "autoCloseDays", t.getAutoCloseDays(), 1, 365))
                        .withMaxOpenPerUser(intOrDefault(tMap, "maxOpenPerUser", t.getMaxOpenPerUser(), 1, 20))
                        .withOpenUiMode(parseTicketOpenUiMode(stringOrDefault(tMap, "openUiMode", t.getOpenUiMode().name()), t.getOpenUiMode()))
                        .withPanelTitle(stringOrDefault(tMap, "panelTitle", t.getPanelTitle()))
                        .withPanelDescription(stringOrDefault(tMap, "panelDescription", t.getPanelDescription()))
                        .withPanelColor(colorOrDefault(tMap, "panelColor", t.getPanelColor()))
                        .withPanelButtonStyle(stringOrDefault(tMap, "panelButtonStyle", t.getPanelButtonStyle()))
                        .withPanelButtonLimit(intOrDefault(tMap, "panelButtonLimit", t.getPanelButtonLimit(), 1, 25))
                        .withPreOpenFormEnabled(boolOrDefault(tMap, "preOpenFormEnabled", t.isPreOpenFormEnabled()))
                        .withPreOpenFormTitle(stringOrDefault(tMap, "preOpenFormTitle", t.getPreOpenFormTitle()))
                        .withPreOpenFormLabel(stringOrDefault(tMap, "preOpenFormLabel", t.getPreOpenFormLabel()))
                        .withPreOpenFormPlaceholder(stringOrDefault(tMap, "preOpenFormPlaceholder", t.getPreOpenFormPlaceholder()))
                        .withWelcomeMessage(stringOrDefault(tMap, "welcomeMessage", t.getWelcomeMessage()))
                        .withOptionLabels(parseCsvOrDefault(tMap, "optionLabels", t.getOptionLabels()))
                        .withOptions(options)
                        .withSupportRoleIds(parseLongCsvOrDefault(tMap, "supportRoleIds", t.getSupportRoleIds()))
                        .withBlacklistedUserIds(parseLongCsvOrDefault(tMap, "blacklistedUserIds", t.getBlacklistedUserIds()));
            }

            return current.withLanguage(language)
                    .withNotifications(n)
                    .withMessageLogs(l)
                    .withMusic(m)
                    .withPrivateRoom(p)
                    .withTicket(t);
        });

        sendJson(exchange, 200, DataObject.empty().put("ok", true).put("language", updated.getLanguage()));
    }

    private String normalizeLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return "en";
        }
        return lang.trim();
    }

    private boolean shouldAutoApplyTemplate(Map<String, Object> inputMap, String key, String currentValue) {
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

    private BotConfig.Notifications notificationDefaultsForLanguage(String language) {
        BotConfig.Notifications defaults = BotConfig.Notifications.defaultValues();
        if (!"zh-TW".equalsIgnoreCase(normalizeLang(language))) {
            return defaults;
        }
        return defaults
                .withMemberJoinMessage(i18n.t(language, "notifications.template.default.member_join"))
                .withMemberLeaveMessage(i18n.t(language, "notifications.template.default.member_leave"))
                .withVoiceJoinMessage(i18n.t(language, "notifications.template.default.voice_join"))
                .withVoiceLeaveMessage(i18n.t(language, "notifications.template.default.voice_leave"))
                .withVoiceMoveMessage(i18n.t(language, "notifications.template.default.voice_move"));
    }
    private void handleGuildChannelsGet(HttpExchange exchange, Guild guild) throws IOException {
        DataArray textChannels = DataArray.empty();
        guild.getTextChannels().forEach(ch -> textChannels.add(
                DataObject.empty()
                        .put("id", ch.getId())
                        .put("name", ch.getName())
                        .put("mention", ch.getAsMention())
        ));

        DataArray voiceChannels = DataArray.empty();
        guild.getVoiceChannels().forEach(ch -> voiceChannels.add(
                DataObject.empty()
                        .put("id", ch.getId())
                        .put("name", ch.getName())
                        .put("mention", ch.getAsMention())
        ));

        sendJson(exchange, 200, DataObject.empty()
                .put("textChannels", textChannels)
                .put("voiceChannels", voiceChannels));
    }

    private void handleGuildRolesGet(HttpExchange exchange, Guild guild) throws IOException {
        DataArray roles = DataArray.empty();
        guild.getRoles().stream()
                .filter(role -> !role.isPublicRole())
                .sorted(Comparator.comparingInt(Role::getPositionRaw).reversed())
                .forEach(role -> roles.add(DataObject.empty()
                        .put("id", role.getId())
                        .put("name", role.getName())));
        sendJson(exchange, 200, DataObject.empty().put("roles", roles));
    }

    private String exchangeToken(BotConfig.Web web, String code) throws IOException, InterruptedException {
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

    private DataObject fetchMe(String accessToken) throws IOException, InterruptedException {
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

    private DataArray fetchUserGuilds(String accessToken) throws IOException, InterruptedException {
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

    private boolean hasManagePermissionInGuild(String raw) {
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

    private String buildGuildIconUrl(String guildId, String iconHash) {
        if (guildId == null || guildId.isBlank() || iconHash == null || iconHash.isBlank()) {
            return "";
        }
        String ext = iconHash.startsWith("a_") ? "gif" : "png";
        return "https://cdn.discordapp.com/icons/" + guildId + "/" + iconHash + "." + ext + "?size=128";
    }

    private String buildBotInviteUrl(String guildId) {
        String clientId = jda.getSelfUser().getId();
        return "https://discord.com/oauth2/authorize"
                + "?client_id=" + encode(clientId)
                + "&permissions=8"
                + "&integration_type=0"
                + "&scope=" + encode("bot applications.commands")
                + "&guild_id=" + encode(guildId == null ? "" : guildId)
                + "&disable_guild_select=true";
    }
    private String buildAvatarUrl(DataObject me) {
        String userId = me.getString("id", "");
        String avatar = me.getString("avatar", "");
        if (userId.isBlank() || avatar.isBlank()) {
            return "";
        }
        String ext = avatar.startsWith("a_") ? "gif" : "png";
        return "https://cdn.discordapp.com/avatars/" + userId + "/" + avatar + "." + ext + "?size=128";
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

    private Member resolveMember(Guild guild, long userId) {
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

    private boolean hasControlPermission(Member member) {
        return member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER);
    }

    private WebSession requireSession(HttpExchange exchange) {
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

    private void setSessionCookie(HttpExchange exchange, String sessionId, BotConfig.Web web) {
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

    private void clearSessionCookie(HttpExchange exchange, BotConfig.Web web) {
        boolean secure = web.getSsl().isEnabled()
                || (web.getBaseUrl() != null && web.getBaseUrl().toLowerCase().startsWith("https://"));
        String sameSite = secure ? "None" : "Lax";
        String cookie = SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=" + sameSite + (secure ? "; Secure" : "");
        exchange.getResponseHeaders().add("Set-Cookie", cookie);
    }

    private String resolveHomeUrl(BotConfig.Web web) {
        String base = web.getBaseUrl();
        if (base == null || base.isBlank()) {
            return "/";
        }
        String trimmed = base.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        oauthStates.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
        sessions.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private Map<String, String> parseUrlEncoded(String raw) {
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
    private Map<String, Object> asMap(Object obj) {
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

    private String toIdText(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String stringOrDefault(Map<String, Object> map, String key, String fallback) {
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

    private boolean boolOrDefault(Map<String, Object> map, String key, boolean fallback) {
        if (!map.containsKey(key)) {
            return fallback;
        }
        Object v = map.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private int intOrDefault(Map<String, Object> map, String key, int fallback, int min, int max) {
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

    private Long idOrDefault(Map<String, Object> map, String key, Long fallback) {
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

    private List<String> parseCsvOrDefault(Map<String, Object> map, String key, List<String> fallback) {
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

    private List<Long> parseLongCsvOrDefault(Map<String, Object> map, String key, List<Long> fallback) {
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

    private List<BotConfig.Ticket.TicketOption> parseTicketOptions(Map<String, Object> map, List<BotConfig.Ticket.TicketOption> fallback) {
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

    private int colorOrDefault(Map<String, Object> map, String key, int fallback) {
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

    private BotConfig.Music.RepeatMode parseRepeatMode(String raw) {
        try {
            return BotConfig.Music.RepeatMode.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return BotConfig.Music.RepeatMode.OFF;
        }
    }

    private BotConfig.Ticket.OpenUiMode parseTicketOpenUiMode(String raw, BotConfig.Ticket.OpenUiMode fallback) {
        return BotConfig.Ticket.OpenUiMode.parse(raw, fallback);
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String formatDuration(long millis) {
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

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void sendJson(HttpExchange exchange, int statusCode, DataObject payload) throws IOException {
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

    private void sendText(HttpExchange exchange, int statusCode, String text) throws IOException {
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
                  <style>
                    :root {
                      --bg:#07111f;
                      --panel:#0f172a;
                      --panel-2:#111c33;
                      --panel-3:#0b1426;
                      --text:#e5e7eb;
                      --muted:#94a3b8;
                      --accent:#22c55e;
                      --accent2:#3b82f6;
                      --warn:#f59e0b;
                      --danger:#ef4444;
                      --danger-2:#b91c1c;
                      --border:rgba(255,255,255,.09);
                      --shadow:0 18px 40px rgba(0,0,0,.28);
                    }
                    body {
                      margin:0;
                      background:
                        radial-gradient(circle at 15% 10%, rgba(59,130,246,.16), transparent 28%),
                        radial-gradient(circle at 85% 12%, rgba(34,197,94,.10), transparent 22%),
                        linear-gradient(180deg, #0a1221 0%, #07111f 100%);
                      color:var(--text);
                      font-family:Segoe UI, sans-serif;
                    }
                    .wrap { max-width:1220px; margin:34px auto 52px; padding:0 18px; }
                    .card {
                      background:linear-gradient(160deg, rgba(15,23,42,.96), rgba(11,20,38,.95));
                      border:1px solid var(--border);
                      border-radius:18px;
                      padding:18px;
                      margin-bottom:16px;
                      box-shadow:var(--shadow);
                      backdrop-filter:blur(10px);
                    }
                    h1 { margin:0 0 8px; font-size:28px; letter-spacing:.01em; }
                    h2 { margin:0 0 8px; font-size:18px; }
                    h3 { margin:0; font-size:15px; color:#f8fafc; letter-spacing:.01em; }
                    .muted { color:var(--muted); font-size:13px; line-height:1.55; }
                    .row { display:flex; gap:10px; flex-wrap:wrap; margin-top:12px; align-items:center; }
                    .grid2 { display:grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap:10px; }
                    .grid3 { display:grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap:10px; }
                    @media (max-width:900px){ .grid2,.grid3{ grid-template-columns:1fr; } }
                    input,select,button,textarea{
                      border:1px solid rgba(255,255,255,.12);
                      background:#111827;
                      color:#f9fafb;
                      border-radius:12px;
                      padding:10px 12px;
                      transition:border-color .16s ease, box-shadow .16s ease, transform .16s ease, background .16s ease, opacity .16s ease;
                    }
                    input,select,textarea{ width:100%; box-sizing:border-box; }
                    input:focus,select:focus,textarea:focus{
                      outline:none;
                      border-color:rgba(96,165,250,.65);
                      box-shadow:0 0 0 3px rgba(59,130,246,.18);
                    }
                    button{
                      cursor:pointer;
                      font-weight:600;
                      letter-spacing:.01em;
                    }
                    button:hover{ transform:translateY(-1px); }
                    button:active{ transform:translateY(0); }
                    button.primary{ background:linear-gradient(90deg,var(--accent2),var(--accent)); border:none; }
                    button.warn{ background:linear-gradient(90deg,var(--danger),var(--warn)); border:none; }
                    button.danger{
                      background:linear-gradient(135deg, var(--danger), var(--danger-2));
                      border:none;
                      color:#fff;
                    }
                    button.reset-btn{
                      min-width:118px;
                      box-shadow:0 10px 24px rgba(127,29,29,.28);
                    }
                    .hidden{display:none;}
                    .guild-grid{ margin-top:10px; display:grid; grid-template-columns:repeat(auto-fill,minmax(280px,1fr)); gap:10px; }
                    .guild-item{
                      border:1px solid rgba(255,255,255,.08);
                      border-radius:14px;
                      padding:12px;
                      background:linear-gradient(180deg, rgba(255,255,255,.03), rgba(255,255,255,.018));
                    }
                    .guild-head{ display:flex; gap:10px; align-items:center; margin-bottom:6px; }
                    .guild-icon{ width:40px; height:40px; border-radius:10px; object-fit:cover; background:#0b1220; flex:0 0 40px; }
                    .guild-icon-fallback{ display:flex; align-items:center; justify-content:center; font-weight:700; color:#e5e7eb; border:1px solid rgba(255,255,255,.14); }
                    .guild-name{ font-weight:700; margin-bottom:6px; }
                    .badge{ font-size:12px; color:#d1d5db; margin-bottom:8px; }
                    .user-profile{ display:flex; align-items:center; gap:10px; margin-top:10px; }
                    .user-avatar{ width:36px; height:36px; border-radius:50%; object-fit:cover; border:1px solid rgba(255,255,255,.16); background:#0b1220; }
                    #status{
                      white-space:pre-wrap;
                      line-height:1.6;
                      font-family:Consolas,monospace;
                      background:rgba(15,23,42,.72);
                      border:1px solid rgba(255,255,255,.08);
                      border-radius:12px;
                      padding:10px 12px;
                      min-height:20px;
                    }
                    .tabs{
                      display:flex;
                      gap:10px;
                      flex-wrap:wrap;
                      margin:14px 0 12px;
                      padding:10px;
                      border-radius:16px;
                      background:rgba(255,255,255,.03);
                      border:1px solid rgba(255,255,255,.06);
                    }
                    .tab-btn{
                      padding:10px 14px;
                      width:auto;
                      background:rgba(255,255,255,.03);
                      border:1px solid rgba(255,255,255,.08);
                    }
                    .tab-btn.active{
                      background:linear-gradient(90deg,var(--accent2),var(--accent));
                      border:none;
                      box-shadow:0 12px 24px rgba(34,197,94,.15);
                    }
                    .tab-pane{ display:none; }
                    .tab-pane.active{
                      display:block;
                      padding:16px;
                      border:1px solid rgba(255,255,255,.08);
                      border-radius:16px;
                      background:linear-gradient(180deg, rgba(255,255,255,.03), rgba(255,255,255,.018));
                    }
                    .pane-head{
                      justify-content:space-between;
                      align-items:center;
                      margin-top:0;
                      margin-bottom:14px;
                      padding-bottom:12px;
                      border-bottom:1px solid rgba(255,255,255,.08);
                    }
                    .field{ display:flex; flex-direction:column; gap:6px; }
                    .field label{ font-size:12px; color:#cbd5e1; font-weight:600; letter-spacing:.01em; }
                    .field-inline{ display:flex; gap:8px; align-items:center; }
                    .field-inline > select,
                    .field-inline > input,
                    .field-inline > textarea{ flex:1 1 auto; }
                    .field-inline > button{ width:auto; white-space:nowrap; }
                    .settings-group{
                      margin-top:14px;
                      padding:14px;
                      border:1px solid rgba(255,255,255,.08);
                      border-radius:14px;
                      background:linear-gradient(180deg, rgba(15,23,42,.62), rgba(15,23,42,.42));
                    }
                    .settings-group-title{
                      font-size:13px;
                      font-weight:700;
                      color:#e2e8f0;
                      margin-bottom:10px;
                      letter-spacing:.02em;
                    }
                    .settings-group .grid2,
                    .settings-group .grid3{ margin-top:6px; }
                    .span-all{ grid-column:1 / -1; }
                    .history-list{ display:flex; flex-direction:column; gap:8px; margin-top:8px; }
                    .history-item{
                      display:flex; gap:10px; align-items:flex-start; justify-content:space-between;
                      padding:12px; border:1px solid rgba(255,255,255,.08); border-radius:12px; background:rgba(15,23,42,.45);
                    }
                    .history-actions{ display:flex; align-items:center; gap:8px; flex-wrap:wrap; justify-content:flex-end; }
                    .history-actions button{ width:auto; }
                    .history-item a{ color:#93c5fd; text-decoration:none; word-break:break-all; }
                    .history-item a:hover{ text-decoration:underline; }
                    .history-meta{ color:#9ca3af; font-size:12px; margin-top:4px; }
                    .option-card{
                      padding:13px;
                      border:1px solid rgba(255,255,255,.08);
                      border-radius:14px;
                      background:linear-gradient(180deg, rgba(15,23,42,.62), rgba(15,23,42,.45));
                      cursor:pointer;
                      transition:transform .15s ease, border-color .15s ease, background .15s ease;
                    }
                    .option-card:hover{ transform:translateY(-1px); border-color:rgba(96,165,250,.35); }
                    .option-card.active{ border-color:rgba(34,197,94,.55); background:rgba(16,185,129,.08); }
                    .option-card-title{ font-weight:700; color:#f8fafc; }
                    .option-card-sub{ color:#93c5fd; font-size:12px; margin-top:4px; }
                    .option-card-grid{ display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px 12px; margin-top:10px; }
                    .option-card-meta{ padding:8px 10px; border-radius:10px; background:rgba(255,255,255,.04); border:1px solid rgba(255,255,255,.06); }
                    .option-card-meta span{ display:block; font-size:11px; color:#94a3b8; margin-bottom:3px; }
                    .option-card-meta strong{ display:block; font-size:13px; color:#e2e8f0; }
                    .stats-grid{
                      display:grid;
                      grid-template-columns:repeat(4,minmax(0,1fr));
                      gap:10px;
                      margin-top:10px;
                    }
                    .stat-card{
                      padding:14px;
                      border-radius:14px;
                      border:1px solid rgba(255,255,255,.08);
                      background:linear-gradient(180deg, rgba(255,255,255,.04), rgba(255,255,255,.02));
                    }
                    .stat-card-label{ color:#94a3b8; font-size:12px; font-weight:600; letter-spacing:.02em; }
                    .stat-card-value{ color:#f8fafc; font-size:18px; font-weight:700; margin-top:6px; word-break:break-word; }
                    .stat-card-meta{ color:#93c5fd; font-size:12px; margin-top:8px; min-height:16px; }
                    @media (max-width:1100px){ .stats-grid{ grid-template-columns:repeat(2,minmax(0,1fr)); } }
                    @media (max-width:700px){ .stats-grid{ grid-template-columns:1fr; } }
                    .toggle{ display:flex; align-items:center; gap:8px; }
                    .toggle input{ width:auto; }
                    textarea{ min-height:72px; resize:vertical; }
                    .keyhint{ color:#94a3b8; font-size:12px; }
                    .lang-switch { align-items:center; }
                    .lang-switch .lang-btn { width:auto; min-width:64px; }
                    .lang-switch .lang-btn.active { background:linear-gradient(90deg,var(--accent2),var(--accent)); border:none; }
                    .role-field{
                      grid-column:1 / -1;
                      padding:12px;
                      border:1px solid rgba(255,255,255,.08);
                      border-radius:14px;
                      background:linear-gradient(180deg, rgba(255,255,255,.03), rgba(255,255,255,.02));
                    }
                    .role-meta{ display:flex; align-items:center; justify-content:space-between; gap:8px; margin-bottom:6px; }
                    .role-count{ font-size:12px; color:#bfdbfe; background:rgba(59,130,246,.16); border:1px solid rgba(59,130,246,.35); border-radius:999px; padding:2px 10px; }
                    select[multiple].role-multi{
                      min-height:190px;
                      border:1px solid rgba(96,165,250,.35);
                      background:linear-gradient(180deg, rgba(17,24,39,.96), rgba(15,23,42,.96));
                    }
                    select[multiple].role-multi:focus{ outline:none; box-shadow:0 0 0 2px rgba(59,130,246,.35); }
                    .toast-host{ position:fixed; top:18px; right:18px; z-index:9999; display:flex; flex-direction:column; gap:8px; pointer-events:none; }
                    .toast{
                      min-width:220px;
                      max-width:360px;
                      padding:10px 12px;
                      border-radius:10px;
                      border:1px solid rgba(255,255,255,.18);
                      background:rgba(17,24,39,.95);
                      color:#f9fafb;
                      box-shadow:0 10px 24px rgba(0,0,0,.35);
                      transform:translateY(-8px) scale(.98);
                      opacity:0;
                    }
                    .toast.show{ animation:toastInOut 2.6s ease forwards; }
                    .toast.success{ border-color:rgba(34,197,94,.45); background:rgba(6,78,59,.92); }
                    .toast.error{ border-color:rgba(239,68,68,.45); background:rgba(127,29,29,.92); }
                    button.saving{ opacity:.78; cursor:progress; }
                    @keyframes toastInOut{
                      0%{ opacity:0; transform:translateY(-8px) scale(.98); }
                      12%{ opacity:1; transform:translateY(0) scale(1); }
                      85%{ opacity:1; transform:translateY(0) scale(1); }
                      100%{ opacity:0; transform:translateY(-6px) scale(.98); }
                    }
                    .login-hero{
                      margin-top:14px;
                      padding:14px;
                      border-radius:14px;
                      border:1px solid rgba(255,255,255,.10);
                      background:
                        radial-gradient(circle at 15% 20%, rgba(59,130,246,.18), transparent 45%),
                        radial-gradient(circle at 80% 80%, rgba(34,197,94,.14), transparent 40%),
                        rgba(255,255,255,.02);
                      display:flex;
                      align-items:center;
                      justify-content:center;
                    }
                    .bot-avatar{
                      width:84px;
                      height:84px;
                      border-radius:26px;
                      object-fit:cover;
                      border:2px solid rgba(255,255,255,.24);
                      box-shadow: 0 10px 24px rgba(0,0,0,.35);
                    }
                    .bot-avatar-fallback{
                      width:84px;
                      height:84px;
                      border-radius:26px;
                      display:flex;
                      align-items:center;
                      justify-content:center;
                      font-weight:800;
                      letter-spacing:1px;
                      color:#e5e7eb;
                      border:2px solid rgba(255,255,255,.24);
                      background:linear-gradient(135deg, rgba(59,130,246,.35), rgba(34,197,94,.32));
                      box-shadow: 0 10px 24px rgba(0,0,0,.35);
                    }
                  </style>
                </head>
                <body>
                <div class="wrap">
                  <div class="card">
                    <h1 id="titleMain">NoRule Bot Web Console</h1>
                    <div id="subtitleMain" class="muted">Sign in with Discord. Manage your guild settings from web.</div>
                    <div class="login-hero">__BOT_AVATAR_BLOCK__</div>
                    <div class="row lang-switch">
                      <span id="langLabel" class="muted">Language</span>
                      <div id="uiLangButtons" class="row"></div>
                    </div>
                    <div id="authBlock" class="row"><button id="loginBtn" class="primary">Sign in with Discord</button></div>
                    <div id="userBlock" class="hidden">
                      <div class="user-profile">
                        <img id="meAvatar" class="user-avatar hidden" alt="avatar" loading="lazy" referrerpolicy="no-referrer" />
                        <div id="meLine"></div>
                      </div>
                      <div class="row"><button id="logoutBtn">Logout</button></div>
                    </div>
                  </div>

                  <div class="card hidden" id="guildsBlock">
                    <h2 id="guildsTitle">Your Manageable Guilds</h2>
                    <div id="guildsSubtitle" class="muted">Bot not in guild: Invite Bot. Bot in guild: click Manage.</div>
                    <div id="guildList" class="guild-grid"></div>
                  </div>

                  <div class="card hidden" id="settingsBlock">
                    <h2 id="settingsTitle">Guild Settings</h2>
                    <div id="settingsSubtitle" class="muted">Use this page to configure this guild's features and notification settings.</div>
                    <div class="row"><select id="guildSelect"></select><button id="guildReloadBtn">Reload Current Guild</button></div>
                    <div id="status" class="row"></div>

                    <div class="tabs">
                      <button class="tab-btn active" data-tab="general">General</button>
                      <button class="tab-btn" data-tab="notifications">Notifications</button>
                      <button class="tab-btn" data-tab="logs">Logs</button>
                      <button class="tab-btn" data-tab="music">Music</button>
                      <button class="tab-btn" data-tab="privateRoom">Private Room</button>
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
                        <h3>notifications.*</h3>
                        <button id="resetNotificationsBtn" class="danger reset-btn" type="button">Reset Section</button>
                      </div>
                      <div class="grid3">
                        <div class="toggle"><input type="checkbox" id="n_enabled"><label for="n_enabled">enabled</label></div>
                        <div class="toggle"><input type="checkbox" id="n_memberJoinEnabled"><label for="n_memberJoinEnabled">memberJoinEnabled</label></div>
                        <div class="toggle"><input type="checkbox" id="n_memberLeaveEnabled"><label for="n_memberLeaveEnabled">memberLeaveEnabled</label></div>
                        <div class="toggle"><input type="checkbox" id="n_voiceLogEnabled"><label for="n_voiceLogEnabled">voiceLogEnabled</label></div>
                      </div>
                      <div class="grid3">
                        <div class="field"><label>memberChannelId</label><select id="n_memberChannelId"></select></div>
                        <div class="field"><label>memberJoinChannelId</label><select id="n_memberJoinChannelId"></select></div>
                        <div class="field"><label>memberLeaveChannelId</label><select id="n_memberLeaveChannelId"></select></div>
                        <div class="field"><label>voiceChannelId</label><select id="n_voiceChannelId"></select></div>
                      </div>
                      <div class="grid2">
                        <div class="field">
                          <label>memberJoinMessage</label>
                          <textarea id="n_memberJoinMessage"></textarea>
                          <div id="hint_n_memberJoinMessage" class="keyhint">Available placeholders: {user}, {username}, {guild}, {id}, {tag}, {isBot}, {createdAt}, {accountAgeDays}</div>
                        </div>
                        <div class="field"><label>memberLeaveMessage</label><textarea id="n_memberLeaveMessage"></textarea></div>
                        <div class="field"><label>voiceJoinMessage</label><textarea id="n_voiceJoinMessage"></textarea></div>
                        <div class="field"><label>voiceLeaveMessage</label><textarea id="n_voiceLeaveMessage"></textarea></div>
                        <div class="field"><label>voiceMoveMessage</label><textarea id="n_voiceMoveMessage"></textarea></div>
                        <div class="grid2">
                          <div class="field"><label>memberJoinColor</label><input type="color" id="n_memberJoinColor"></div>
                          <div class="field"><label>memberLeaveColor</label><input type="color" id="n_memberLeaveColor"></div>
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
                      <div class="grid3">
                        <div class="toggle"><input type="checkbox" id="m_autoLeaveEnabled"><label for="m_autoLeaveEnabled">autoLeaveEnabled</label></div>
                        <div class="field"><label>autoLeaveMinutes (1-60)</label><input type="number" min="1" max="60" id="m_autoLeaveMinutes"></div>
                        <div class="toggle"><input type="checkbox" id="m_autoplayEnabled"><label for="m_autoplayEnabled">autoplayEnabled</label></div>
                        <div class="field"><label>defaultRepeatMode</label><select id="m_defaultRepeatMode"><option value="OFF">OFF</option><option value="SINGLE">SINGLE</option><option value="ALL">ALL</option></select></div>
                        <div class="field"><label>commandChannelId</label><select id="m_commandChannelId"></select></div>
                      </div>
                      <div class="settings-group">
                        <div id="music_stats_title" class="settings-group-title">Music Stats</div>
                        <div id="musicStatsCards" class="stats-grid"></div>
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

                """;
        html += """
                    <div class="tab-pane" data-pane="ticket">
                      <div class="row pane-head">
                        <h3>ticket.*</h3>
                        <button id="resetTicketBtn" class="danger reset-btn" type="button">Reset Section</button>
                      </div>
                      <div class="settings-group">
                        <div id="ticket_group_basic_title" class="settings-group-title">Basic Settings</div>
                        <div class="grid3">
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
                        <div class="row">
                          <button id="t_addOptionBtn" class="primary" type="button">Add Ticket Type</button>
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
                        <div class="row">
                          <button id="t_deleteOptionBtn" class="warn" type="button">Delete Ticket Type</button>
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

                    <div class="row">
                      <button id="loadSettingsBtn">Reload Form</button>
                      <button id="saveSettingsBtn" class="primary">Save Settings</button>
                    </div>
                  </div>
                </div>

                <script>
                  const byId = (id) => document.getElementById(id);
                  const authBlock = byId('authBlock');
                  const userBlock = byId('userBlock');
                  const guildsBlock = byId('guildsBlock');
                  const settingsBlock = byId('settingsBlock');
                  const statusEl = byId('status');
                  const guildSelect = byId('guildSelect');
                  const guildList = byId('guildList');
                  let channelsCache = { textChannels: [], voiceChannels: [] };
                  let rolesCache = [];
                  let uiLanguages = [];
                  let botLanguages = [];
                  let defaultUiLanguage = 'zh-TW';
                  let uiLang = 'zh-TW';
                  let ticketOptionsState = [];
                  let selectedTicketOptionId = '';
                  let lastMusicStats = {};
                  let ticketHistoryFilesState = [];
                  let pendingDeleteTranscriptName = '';
                  let ticketHistoryRetentionDays = 90;
                  let ticketHistoryCleanedCount = 0;

                  const I18N = {
                    'zh-TW': {},
                    'en': {}
                  };
function t(key){
                    const dict = I18N[uiLang] || I18N['zh-TW'];
                    return dict[key] || I18N.en[key] || key;
                  }

                  function setText(id, key){
                    const el = byId(id);
                    if (el) el.textContent = t(key);
                  }

                  function updateMeLine(){
                    const meLine = byId('meLine');
                    if (!meLine || !meLine.dataset.userId) return;
                    const username = meLine.dataset.username || '';
                    const userId = meLine.dataset.userId || '';
                    const avatarUrl = meLine.dataset.avatarUrl || '';
                    const meAvatar = byId('meAvatar');
                    if (meAvatar) {
                      if (avatarUrl) {
                        meAvatar.src = avatarUrl;
                        meAvatar.alt = username || 'avatar';
                        meAvatar.classList.remove('hidden');
                      } else {
                        meAvatar.removeAttribute('src');
                        meAvatar.classList.add('hidden');
                      }
                    }
                    meLine.textContent = `${t('signedInPrefix')}: ${username} (${userId})`;
                  }

                  function guildInitial(name){
                    const trimmed = String(name || '').trim();
                    if (!trimmed) return '?';
                    return Array.from(trimmed)[0].toUpperCase();
                  }

                  function setTabLabel(tab, key){
                    const btn = document.querySelector(`.tab-btn[data-tab=\"${tab}\"]`);
                    if (btn) btn.textContent = t(key);
                  }

                  function setSectionLabel(pane, key){
                    const el = document.querySelector(`.tab-pane[data-pane=\"${pane}\"] h3`);
                    if (el) el.textContent = t(key);
                  }

                  function setCheckboxLabel(forId, key){
                    const el = document.querySelector(`label[for=\"${forId}\"]`);
                    if (el) el.textContent = t(key);
                  }

                  function setFieldLabel(controlId, key){
                    const control = byId(controlId);
                    const label = control?.closest('.field')?.querySelector('label');
                    if (label) label.textContent = t(key);
                  }

                  function setSelectOptionLabel(selectId, optionValue, key){
                    const select = byId(selectId);
                    if (!select) return;
                    const option = [...select.options].find(op => op.value === optionValue);
                    if (option) option.textContent = t(key);
                  }

                  function localizeLanguageOptions(){
                    const select = byId('s_language');
                    if (!select) return;
                    const current = select.value;
                    select.innerHTML = '';
                    botLanguages.forEach(item => {
                      const op = document.createElement('option');
                      op.value = item.code;
                      const key = `language_option_${String(item.code || '').toLowerCase().replace(/[^a-z0-9]/g, '_')}`;
                      const translated = t(key);
                      op.textContent = translated !== key
                        ? translated
                        : `${item.code} - ${item.label || item.code}`;
                      select.appendChild(op);
                    });
                    if (current && [...select.options].some(op => op.value === current)) {
                      select.value = current;
                    } else if (select.options.length > 0) {
                      select.value = select.options[0].value;
                    }
                  }

                  function localizeSettingsForm(){
                    setText('label_s_language', 'label_s_language');
                    setText('hint_s_language', 'hint_s_language');
                    localizeLanguageOptions();

                    setTabLabel('general', 'tabs_general');
                    setTabLabel('notifications', 'tabs_notifications');
                    setTabLabel('logs', 'tabs_logs');
                    setTabLabel('music', 'tabs_music');
                    setTabLabel('privateRoom', 'tabs_privateRoom');
                    setTabLabel('numberChain', 'tabs_numberChain');
                    setTabLabel('ticket', 'tabs_ticket');
                    setTabLabel('ticketHistory', 'tabs_ticket_history');

                    setSectionLabel('general', 'section_language');
                    setSectionLabel('notifications', 'section_notifications');
                    setSectionLabel('logs', 'section_logs');
                    setSectionLabel('music', 'section_music');
                    setSectionLabel('privateRoom', 'section_privateRoom');
                    setSectionLabel('numberChain', 'section_numberChain');
                    setSectionLabel('ticket', 'section_ticket');
                    setSectionLabel('ticketHistory', 'section_ticket_history');

                    setCheckboxLabel('n_enabled', 'n_enabled');
                    setCheckboxLabel('n_memberJoinEnabled', 'n_memberJoinEnabled');
                    setCheckboxLabel('n_memberLeaveEnabled', 'n_memberLeaveEnabled');
                    setCheckboxLabel('n_voiceLogEnabled', 'n_voiceLogEnabled');
                    setFieldLabel('n_memberChannelId', 'n_memberChannelId');
                    setFieldLabel('n_memberJoinChannelId', 'n_memberJoinChannelId');
                    setFieldLabel('n_memberLeaveChannelId', 'n_memberLeaveChannelId');
                    setFieldLabel('n_voiceChannelId', 'n_voiceChannelId');
                    setFieldLabel('n_memberJoinMessage', 'n_memberJoinMessage');
                    setText('hint_n_memberJoinMessage', 'n_memberJoinMessage_hint');
                    setFieldLabel('n_memberLeaveMessage', 'n_memberLeaveMessage');
                    setFieldLabel('n_voiceJoinMessage', 'n_voiceJoinMessage');
                    setFieldLabel('n_voiceLeaveMessage', 'n_voiceLeaveMessage');
                    setFieldLabel('n_voiceMoveMessage', 'n_voiceMoveMessage');
                    setFieldLabel('n_memberJoinColor', 'n_memberJoinColor');
                    setFieldLabel('n_memberLeaveColor', 'n_memberLeaveColor');

                    setCheckboxLabel('l_enabled', 'l_enabled');
                    setCheckboxLabel('l_roleLogEnabled', 'l_roleLogEnabled');
                    setCheckboxLabel('l_channelLifecycleLogEnabled', 'l_channelLifecycleLogEnabled');
                    setCheckboxLabel('l_moderationLogEnabled', 'l_moderationLogEnabled');
                    setCheckboxLabel('l_commandUsageLogEnabled', 'l_commandUsageLogEnabled');
                    setFieldLabel('l_channelId', 'l_channelId');
                    setFieldLabel('l_messageLogChannelId', 'l_messageLogChannelId');
                    setFieldLabel('l_commandUsageChannelId', 'l_commandUsageChannelId');
                    setFieldLabel('l_channelLifecycleChannelId', 'l_channelLifecycleChannelId');
                    setFieldLabel('l_roleLogChannelId', 'l_roleLogChannelId');
                    setFieldLabel('l_moderationLogChannelId', 'l_moderationLogChannelId');

                    setCheckboxLabel('m_autoLeaveEnabled', 'm_autoLeaveEnabled');
                    setFieldLabel('m_autoLeaveMinutes', 'm_autoLeaveMinutes');
                    setCheckboxLabel('m_autoplayEnabled', 'm_autoplayEnabled');
                    setFieldLabel('m_defaultRepeatMode', 'm_defaultRepeatMode');
                    setFieldLabel('m_commandChannelId', 'm_commandChannelId');
                    setText('music_stats_title', 'music_stats_title');

                    setCheckboxLabel('p_enabled', 'p_enabled');
                    setFieldLabel('p_triggerVoiceChannelId', 'p_triggerVoiceChannelId');
                    setFieldLabel('p_userLimit', 'p_userLimit');

                    setCheckboxLabel('nc_enabled', 'nc_enabled');
                    setFieldLabel('nc_channelId', 'nc_channelId');
                    setFieldLabel('nc_nextNumber', 'nc_nextNumber');
                    setText('resetNumberChainProgressBtn', 'resetNumberChainProgressBtn');

                    setCheckboxLabel('t_enabled', 't_enabled');
                    setText('ticket_group_basic_title', 'ticket_group_basic_title');
                    setText('ticket_group_access_title', 'ticket_group_access_title');
                    setText('ticket_group_panel_title', 'ticket_group_panel_title');
                    setText('ticket_group_options_title', 'ticket_group_options_title');
                    setText('ticket_group_history_title', 'ticket_group_history_title');
                    setFieldLabel('t_panelChannelId', 't_panelChannelId');
                    setFieldLabel('t_panelTitle', 't_panelTitle');
                    setFieldLabel('t_panelDescription', 't_panelDescription');
                    setFieldLabel('t_panelColor', 't_panelColor');
                    setFieldLabel('t_panelButtonLimit', 't_panelButtonLimit');
                    setFieldLabel('t_autoCloseDays', 't_autoCloseDays');
                    setFieldLabel('t_maxOpenPerUser', 't_maxOpenPerUser');
                    setFieldLabel('t_openUiMode', 't_openUiMode');
                    setText('hint_t_openUiMode', 't_openUiMode_hint');
                    setSelectOptionLabel('t_openUiMode', 'BUTTONS', 't_openUiMode_buttons');
                    setSelectOptionLabel('t_openUiMode', 'SELECT', 't_openUiMode_select');
                    setFieldLabel('t_supportRoleIds', 't_supportRoleIds');
                    setText('hint_t_supportRoleIds', 't_supportRoleIds_hint');
                    setFieldLabel('t_blacklistedUserIds', 't_blacklistedUserIds');
                    setText('hint_t_blacklistedUserIds', 't_blacklistedUserIds_hint');
                    setText('hint_t_optionEditor', 't_optionEditor_hint');
                    setText('label_t_optionLabel', 't_optionLabel');
                    setText('label_t_optionButtonStyle', 't_optionButtonStyle');
                    setSelectOptionLabel('t_optionButtonStyle', 'PRIMARY', 't_panelButtonStyle_primary');
                    setSelectOptionLabel('t_optionButtonStyle', 'SECONDARY', 't_panelButtonStyle_secondary');
                    setSelectOptionLabel('t_optionButtonStyle', 'SUCCESS', 't_panelButtonStyle_success');
                    setSelectOptionLabel('t_optionButtonStyle', 'DANGER', 't_panelButtonStyle_danger');
                    setText('label_t_optionPanelTitle', 't_optionPanelTitle');
                    setText('label_t_optionPanelDescription', 't_optionPanelDescription');
                    setText('label_t_optionWelcomeMessage', 't_optionWelcomeMessage');
                    setText('hint_t_optionWelcomeMessage', 't_welcomeMessage_hint');
                    setText('label_t_optionPreOpenFormEnabled', 't_optionPreOpenFormEnabled');
                    setText('label_t_optionPreOpenFormTitle', 't_optionPreOpenFormTitle');
                    setText('label_t_optionPreOpenFormLabel', 't_optionPreOpenFormLabel');
                    setText('label_t_optionPreOpenFormPlaceholder', 't_optionPreOpenFormPlaceholder');
                    setText('t_addOptionBtn', 't_addOptionBtn');
                    setText('t_deleteOptionBtn', 't_deleteOptionBtn');
                    setText('loadTicketHistoryBtn', 'loadTicketHistoryBtn');
                    applyNotificationTemplateDefaults();
                    applyTicketPanelDefaultsIfEmpty();
                    renderTicketOptions();
                    renderMusicStats(lastMusicStats);
                  }

                  function resolveNotificationTemplateKey(controlId){
                    const mapping = {
                      n_memberJoinMessage: 'notifications_default_member_join',
                      n_memberLeaveMessage: 'notifications_default_member_leave',
                      n_voiceJoinMessage: 'notifications_default_voice_join',
                      n_voiceLeaveMessage: 'notifications_default_voice_leave',
                      n_voiceMoveMessage: 'notifications_default_voice_move'
                    };
                    return mapping[controlId] || '';
                  }

                  function getBundleTextByLanguage(code, key){
                    const bundle = I18N[code] || {};
                    return bundle[key] ? String(bundle[key]) : '';
                  }

                  function getNotificationTemplateDefault(templateKey){
                    const preferredLang = getValue('s_language') || uiLang || 'zh-TW';
                    return getBundleTextByLanguage(preferredLang, templateKey)
                      || getBundleTextByLanguage(uiLang, templateKey)
                      || getBundleTextByLanguage('zh-TW', templateKey)
                      || '';
                  }

                  function isNotificationTemplateStillDefault(value, templateKey){
                    const current = String(value || '').trim();
                    if (!current) return true;
                    const knownDefaults = new Set();
                    Object.keys(I18N).forEach(code => {
                      const text = getBundleTextByLanguage(code, templateKey);
                      if (text) knownDefaults.add(text.trim());
                    });
                    return knownDefaults.has(current);
                  }

                  function applyNotificationTemplateDefaults(){
                    [
                      'n_memberJoinMessage',
                      'n_memberLeaveMessage',
                      'n_voiceJoinMessage',
                      'n_voiceLeaveMessage',
                      'n_voiceMoveMessage'
                    ].forEach(controlId => {
                      const el = byId(controlId);
                      if (!el) return;
                      const templateKey = resolveNotificationTemplateKey(controlId);
                      if (!templateKey) return;
                      if (!isNotificationTemplateStillDefault(el.value, templateKey)) return;
                      const next = getNotificationTemplateDefault(templateKey);
                      if (next) el.value = next;
                    });
                  }

                  function getTicketPanelDefault(key){
                    const preferredLang = getValue('s_language') || uiLang || 'zh-TW';
                    return getBundleTextByLanguage(preferredLang, key)
                      || getBundleTextByLanguage(uiLang, key)
                      || getBundleTextByLanguage('zh-TW', key)
                      || '';
                  }

                  function isTicketPanelTextStillDefault(value, key){
                    const current = String(value || '').trim();
                    if (!current) return true;
                    const knownDefaults = new Set();
                    Object.keys(I18N).forEach(code => {
                      const text = getBundleTextByLanguage(code, key);
                      if (text) knownDefaults.add(text.trim());
                    });
                    return knownDefaults.has(current);
                  }

                  function applyTicketPanelDefaultsIfEmpty(){
                    const title = byId('t_panelTitle');
                    const desc = byId('t_panelDescription');
                    if (title && isTicketPanelTextStillDefault(title.value, 'ticket_default_panel_title')) {
                      title.value = getTicketPanelDefault('ticket_default_panel_title');
                    }
                    if (desc && isTicketPanelTextStillDefault(desc.value, 'ticket_default_panel_desc')) {
                      desc.value = getTicketPanelDefault('ticket_default_panel_desc');
                    }
                  }

                  function renderMusicStats(stats){
                    lastMusicStats = stats || {};
                    const root = byId('musicStatsCards');
                    if (!root) return;
                    const topSongLabel = String(lastMusicStats.topSongLabel || '').trim();
                    const topSongCount = Number(lastMusicStats.topSongCount || 0);
                    const topRequesterDisplay = String(lastMusicStats.topRequesterDisplay || '').trim();
                    const topRequesterCount = Number(lastMusicStats.topRequesterCount || 0);
                    const todayPlaybackDisplay = String(lastMusicStats.todayPlaybackDisplay || '00:00');
                    const historyCount = Number(lastMusicStats.historyCount || 0);
                    const noneText = t('music_stats_none');
                    const cards = [
                      {
                        label: t('music_stats_top_song'),
                        value: topSongLabel || noneText,
                        meta: topSongCount > 0 ? `${t('music_stats_play_count')}: ${topSongCount}` : ''
                      },
                      {
                        label: t('music_stats_top_requester'),
                        value: topRequesterDisplay || noneText,
                        meta: topRequesterCount > 0 ? `${t('music_stats_request_count')}: ${topRequesterCount}` : ''
                      },
                      {
                        label: t('music_stats_today_time'),
                        value: todayPlaybackDisplay || '00:00',
                        meta: ''
                      },
                      {
                        label: t('music_stats_history_count'),
                        value: String(historyCount),
                        meta: ''
                      }
                    ];
                    root.innerHTML = cards.map(card => `
                      <div class="stat-card">
                        <div class="stat-card-label">${esc(card.label)}</div>
                        <div class="stat-card-value">${esc(card.value)}</div>
                        <div class="stat-card-meta">${esc(card.meta || '')}</div>
                      </div>
                    `).join('');
                  }

                  function renderUiLanguageButtons(){
                    const root = byId('uiLangButtons');
                    if (!root) return;
                    root.innerHTML = '';
                    uiLanguages.forEach(item => {
                      const code = item.code || '';
                      const label = item.label || code;
                      if (!code) return;
                      const btn = document.createElement('button');
                      btn.type = 'button';
                      btn.className = 'lang-btn';
                      btn.textContent = label;
                      btn.dataset.lang = code;
                      btn.classList.toggle('active', uiLang === code);
                      btn.onclick = () => setUiLanguage(code);
                      root.appendChild(btn);
                    });
                  }

                  function applyUiLanguage(){
                    setText('titleMain', 'titleMain');
                    setText('subtitleMain', 'subtitleMain');
                    setText('langLabel', 'langLabel');
                    renderUiLanguageButtons();
                    setText('loginBtn', 'loginBtn');
                    setText('logoutBtn', 'logoutBtn');
                    setText('guildsTitle', 'guildsTitle');
                    setText('guildsSubtitle', 'guildsSubtitle');
                    setText('settingsTitle', 'settingsTitle');
                    setText('settingsSubtitle', 'settingsSubtitle');
                    setText('guildReloadBtn', 'guildReloadBtn');
                    setText('loadSettingsBtn', 'loadSettingsBtn');
                    setText('sendTicketPanelBtn', 'sendTicketPanelBtn');
                    setText('saveSettingsBtn', 'saveSettingsBtn');
                    setText('resetGeneralBtn', 'resetSectionBtn');
                    setText('resetNotificationsBtn', 'resetSectionBtn');
                    setText('resetLogsBtn', 'resetSectionBtn');
                    setText('resetMusicBtn', 'resetSectionBtn');
                    setText('resetPrivateRoomBtn', 'resetSectionBtn');
                    setText('resetNumberChainBtn', 'resetSectionBtn');
                    setText('resetTicketBtn', 'resetSectionBtn');
                    localizeSettingsForm();
                    updateMeLine();
                  }

                  function setUiLanguage(lang){
                    const codes = uiLanguages.map(item => item.code);
                    uiLang = codes.includes(lang) ? lang : (codes.includes(defaultUiLanguage) ? defaultUiLanguage : 'zh-TW');
                    localStorage.setItem('norule.web.ui.lang', uiLang);
                    applyUiLanguage();
                    if (!guildsBlock.classList.contains('hidden')) {
                      loadGuilds().catch(() => {});
                    }
                  }

                  async function loadWebI18n(){
                    try {
                      const payload = await api('/api/web/i18n');
                      defaultUiLanguage = payload?.defaultLanguage || 'zh-TW';
                      uiLanguages = Array.isArray(payload?.uiLanguages) ? payload.uiLanguages : [];
                      botLanguages = Array.isArray(payload?.botLanguages) ? payload.botLanguages : [];
                      const bundles = payload?.bundles || {};
                      Object.keys(bundles).forEach(lang => {
                        I18N[lang] = Object.assign({}, I18N[lang] || {}, bundles[lang] || {});
                      });
                      if (uiLanguages.length === 0) {
                        uiLanguages = Object.keys(bundles).map(code => ({ code, label: code }));
                      }
                      if (botLanguages.length === 0) {
                        botLanguages = [{ code: 'zh-TW', label: 'Traditional Chinese' }, { code: 'en', label: 'English' }];
                      }
                    } catch (_) {
                      uiLanguages = [{ code: 'zh-TW', label: '繁中' }, { code: 'en', label: 'ENG' }];
                      botLanguages = [{ code: 'zh-TW', label: 'Traditional Chinese' }, { code: 'en', label: 'English' }];
                    }
                  }

                  function showStatus(text){ statusEl.textContent = text || ''; }
                  function showToast(text, type = 'success'){
                    const toastHost = byId('toastHost');
                    if(!toastHost || !text) return;
                    const el = document.createElement('div');
                    el.className = `toast ${type}`;
                    el.textContent = text;
                    toastHost.appendChild(el);
                    requestAnimationFrame(() => el.classList.add('show'));
                    setTimeout(() => el.remove(), 2800);
                  }
                  async function api(path, opt = {}) {
                    const res = await fetch(path, opt);
                    const data = await res.json().catch(() => ({}));
                    if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
                    return data;
                  }
                  function selectedGuild(){ return guildSelect.value || ''; }
                  function esc(v){ return String(v || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;'); }

                  function setSelectOptions(selectId, list){
                    const el = byId(selectId);
                    if(!el) return;
                    const current = el.value;
                    el.innerHTML = '';
                    const empty = document.createElement('option');
                    empty.value = '';
                    empty.textContent = t('noneOption');
                    el.appendChild(empty);
                    list.forEach(ch => {
                      const op = document.createElement('option');
                      op.value = ch.id;
                      op.textContent = `${ch.name} (${ch.id})`;
                      el.appendChild(op);
                    });
                    if (current && [...el.options].some(o => o.value === current)) {
                      el.value = current;
                    }
                  }

                  function setMultiSelectOptions(selectId, list){
                    const el = byId(selectId);
                    if(!el) return;
                    const currentValues = Array.from(el.selectedOptions || []).map(o => o.value);
                    el.innerHTML = '';
                    list.forEach(item => {
                      const op = document.createElement('option');
                      op.value = item.id;
                      op.textContent = `${item.name} (${item.id})`;
                      el.appendChild(op);
                    });
                    if (currentValues.length > 0) {
                      Array.from(el.options).forEach(op => {
                        op.selected = currentValues.includes(op.value);
                      });
                    }
                    updateSupportRoleCount();
                  }

                  function setMultiSelectValues(selectId, values){
                    const el = byId(selectId);
                    if(!el) return;
                    const normalized = (values || []).map(v => String(v));
                    Array.from(el.options).forEach(op => {
                      op.selected = normalized.includes(op.value);
                    });
                    updateSupportRoleCount();
                  }

                  function getMultiSelectValues(selectId){
                    const el = byId(selectId);
                    if(!el) return [];
                    return Array.from(el.selectedOptions || []).map(op => op.value);
                  }

                  function updateSupportRoleCount(){
                    const el = byId('t_supportRoleIds');
                    const countEl = byId('t_supportRoleCount');
                    if(!el || !countEl) return;
                    const selected = Array.from(el.selectedOptions || []).length;
                    const total = Array.from(el.options || []).length;
                    countEl.textContent = `${t('t_supportRoleIds_selected')}: ${selected}/${total}`;
                  }

                  function createTicketOptionDraft(){
                    const stamp = Date.now().toString(36);
                    return {
                      id: `option-${stamp}`,
                      label: '',
                      panelTitle: '',
                      panelDescription: '',
                      panelButtonStyle: 'PRIMARY',
                      welcomeMessage: t('ticket_default_welcome_message'),
                      preOpenFormEnabled: false,
                      preOpenFormTitle: '',
                      preOpenFormLabel: '',
                      preOpenFormPlaceholder: ''
                    };
                  }

                  function ensureSelectedTicketOption(){
                    if (ticketOptionsState.length === 0) {
                      const draft = createTicketOptionDraft();
                      ticketOptionsState = [draft];
                      selectedTicketOptionId = draft.id;
                      return;
                    }
                    if (!selectedTicketOptionId || !ticketOptionsState.some(item => item.id === selectedTicketOptionId)) {
                      selectedTicketOptionId = ticketOptionsState[0].id;
                    }
                  }

                  function getSelectedTicketOption(){
                    ensureSelectedTicketOption();
                    return ticketOptionsState.find(item => item.id === selectedTicketOptionId) || null;
                  }

                  function populateTicketOptionEditor(option){
                    const current = option || createTicketOptionDraft();
                    setValue('t_optionLabel', current.label || '');
                    setValue('t_optionButtonStyle', (current.panelButtonStyle || 'PRIMARY').toUpperCase());
                    setValue('t_optionPanelTitle', current.panelTitle || '');
                    setValue('t_optionPanelDescription', current.panelDescription || '');
                    setValue('t_optionWelcomeMessage', current.welcomeMessage || '');
                    setChecked('t_optionPreOpenFormEnabled', !!current.preOpenFormEnabled);
                    setValue('t_optionPreOpenFormTitle', current.preOpenFormTitle || '');
                    setValue('t_optionPreOpenFormLabel', current.preOpenFormLabel || '');
                    setValue('t_optionPreOpenFormPlaceholder', current.preOpenFormPlaceholder || '');
                  }

                  function renderTicketOptions(){
                    const root = byId('ticketOptionList');
                    if (!root) return;
                    ensureSelectedTicketOption();
                    root.innerHTML = '';
                    ticketOptionsState.forEach(option => {
                      const card = document.createElement('div');
                      card.className = 'option-card';
                      if (option.id === selectedTicketOptionId) {
                        card.classList.add('active');
                      }
                      const formState = option.preOpenFormEnabled ? t('enabledOption') : t('disabledOption');
                      const styleKey = 't_panelButtonStyle_' + String(option.panelButtonStyle || 'PRIMARY').toLowerCase();
                      const styleLabel = t(styleKey) !== styleKey ? t(styleKey) : String(option.panelButtonStyle || 'PRIMARY');
                      const welcomeLabel = option.welcomeMessage || t('ticket_default_welcome_message');
                      card.innerHTML = `
                        <div class="option-card-title">${esc(option.label || t('ticket_option_unnamed'))}</div>
                        <div class="option-card-sub">${esc(option.panelTitle || t('ticket_default_panel_title'))}</div>
                        <div class="option-card-grid">
                          <div class="option-card-meta"><span>${esc(t('ticket_option_meta_name'))}</span><strong>${esc(option.label || t('ticket_option_unnamed'))}</strong></div>
                          <div class="option-card-meta"><span>${esc(t('ticket_option_meta_style'))}</span><strong>${esc(styleLabel)}</strong></div>
                          <div class="option-card-meta"><span>${esc(t('ticket_option_meta_form'))}</span><strong>${esc(formState)}</strong></div>
                          <div class="option-card-meta"><span>${esc(t('ticket_option_meta_welcome'))}</span><strong>${esc(welcomeLabel.length > 40 ? welcomeLabel.slice(0, 40) + '…' : welcomeLabel)}</strong></div>
                        </div>
                      `;
                      card.onclick = () => {
                        selectedTicketOptionId = option.id;
                        populateTicketOptionEditor(option);
                        renderTicketOptions();
                      };
                      root.appendChild(card);
                    });
                    populateTicketOptionEditor(getSelectedTicketOption());
                  }

                  function syncTicketOptionDraftFromEditor(){
                    ensureSelectedTicketOption();
                    const index = ticketOptionsState.findIndex(item => item.id === selectedTicketOptionId);
                    if (index < 0) return;
                    ticketOptionsState[index] = {
                      id: selectedTicketOptionId,
                      label: getValue('t_optionLabel'),
                      panelButtonStyle: getValue('t_optionButtonStyle') || 'PRIMARY',
                      panelTitle: getValue('t_optionPanelTitle'),
                      panelDescription: getValue('t_optionPanelDescription'),
                      welcomeMessage: getValue('t_optionWelcomeMessage'),
                      preOpenFormEnabled: getChecked('t_optionPreOpenFormEnabled'),
                      preOpenFormTitle: getValue('t_optionPreOpenFormTitle'),
                      preOpenFormLabel: getValue('t_optionPreOpenFormLabel'),
                      preOpenFormPlaceholder: getValue('t_optionPreOpenFormPlaceholder')
                    };
                  }

                  function bindTicketOptionEditorAutoSync(){
                    const ids = [
                      't_optionLabel',
                      't_optionButtonStyle',
                      't_optionPanelTitle',
                      't_optionPanelDescription',
                      't_optionWelcomeMessage',
                      't_optionPreOpenFormEnabled',
                      't_optionPreOpenFormTitle',
                      't_optionPreOpenFormLabel',
                      't_optionPreOpenFormPlaceholder'
                    ];
                    ids.forEach(id => {
                      const el = byId(id);
                      if (!el || el.dataset.autosyncBound === '1') return;
                      el.dataset.autosyncBound = '1';
                      const syncOnly = () => syncTicketOptionDraftFromEditor();
                      const syncAndRefresh = () => {
                        syncTicketOptionDraftFromEditor();
                        renderTicketOptions();
                      };
                      el.addEventListener('input', syncOnly);
                      el.addEventListener('change', syncAndRefresh);
                    });
                  }

                  function addTicketOption(){
                    const draft = createTicketOptionDraft();
                    ticketOptionsState = [...ticketOptionsState, draft];
                    selectedTicketOptionId = draft.id;
                    renderTicketOptions();
                  }

                  function deleteCurrentTicketOption(){
                    if (ticketOptionsState.length <= 1) {
                      showToast(t('ticket_option_delete_blocked'), 'error');
                      return;
                    }
                    ticketOptionsState = ticketOptionsState.filter(item => item.id !== selectedTicketOptionId);
                    selectedTicketOptionId = ticketOptionsState[0]?.id || '';
                    renderTicketOptions();
                    showToast(t('ticket_option_deleted'), 'success');
                  }

                  async function loadChannels(){
                    const guildId = selectedGuild();
                    if(!guildId) return;
                    channelsCache = await api(`/api/guild/${guildId}/channels`);
                    const textIds = ['n_memberChannelId','n_memberJoinChannelId','n_memberLeaveChannelId','l_channelId','l_messageLogChannelId','l_commandUsageChannelId','l_channelLifecycleChannelId','l_roleLogChannelId','l_moderationLogChannelId','m_commandChannelId','nc_channelId','t_panelChannelId'];
                    const voiceIds = ['n_voiceChannelId','p_triggerVoiceChannelId'];
                    textIds.forEach(id => setSelectOptions(id, channelsCache.textChannels || []));
                    voiceIds.forEach(id => setSelectOptions(id, channelsCache.voiceChannels || []));
                  }

                  async function loadRoles(){
                    const guildId = selectedGuild();
                    if(!guildId) return;
                    const data = await api(`/api/guild/${guildId}/roles`);
                    rolesCache = Array.isArray(data.roles) ? data.roles : [];
                    setMultiSelectOptions('t_supportRoleIds', rolesCache);
                  }

                  function setChecked(id, value){ const el = byId(id); if(el) el.checked = !!value; }
                  function setValue(id, value){ const el = byId(id); if(el) el.value = value ?? ''; }
                  function getChecked(id){ return !!byId(id)?.checked; }
                  function getValue(id){ return (byId(id)?.value ?? '').trim(); }

                  function defaultBotLanguageCode(){
                    const codes = botLanguages.map(item => item.code);
                    if (codes.includes('zh-TW')) return 'zh-TW';
                    return codes[0] || 'zh-TW';
                  }

                  function defaultTicketOptionLabel(){
                    const key = 'ticket_option_default_label';
                    const translated = t(key);
                    return translated !== key ? translated : 'General';
                  }

                  function resetGeneralSettings(){
                    setValue('s_language', defaultBotLanguageCode());
                    applyNotificationTemplateDefaults();
                    applyTicketPanelDefaultsIfEmpty();
                  }

                  function resetNotificationSettings(){
                    setChecked('n_enabled', true);
                    setChecked('n_memberJoinEnabled', true);
                    setChecked('n_memberLeaveEnabled', true);
                    setChecked('n_voiceLogEnabled', true);
                    setValue('n_memberChannelId', '');
                    setValue('n_memberJoinChannelId', '');
                    setValue('n_memberLeaveChannelId', '');
                    setValue('n_voiceChannelId', '');
                    setValue('n_memberJoinMessage', '');
                    setValue('n_memberLeaveMessage', '');
                    setValue('n_voiceJoinMessage', '');
                    setValue('n_voiceLeaveMessage', '');
                    setValue('n_voiceMoveMessage', '');
                    setValue('n_memberJoinColor', '#2ECC71');
                    setValue('n_memberLeaveColor', '#E74C3C');
                    applyNotificationTemplateDefaults();
                  }

                  function resetLogSettings(){
                    setChecked('l_enabled', true);
                    setChecked('l_roleLogEnabled', true);
                    setChecked('l_channelLifecycleLogEnabled', true);
                    setChecked('l_moderationLogEnabled', true);
                    setChecked('l_commandUsageLogEnabled', true);
                    setValue('l_channelId', '');
                    setValue('l_messageLogChannelId', '');
                    setValue('l_commandUsageChannelId', '');
                    setValue('l_channelLifecycleChannelId', '');
                    setValue('l_roleLogChannelId', '');
                    setValue('l_moderationLogChannelId', '');
                  }

                  function resetMusicSettings(){
                    setChecked('m_autoLeaveEnabled', true);
                    setValue('m_autoLeaveMinutes', '5');
                    setChecked('m_autoplayEnabled', true);
                    setValue('m_defaultRepeatMode', 'OFF');
                    setValue('m_commandChannelId', '');
                  }

                  function resetPrivateRoomSettings(){
                    setChecked('p_enabled', true);
                    setValue('p_triggerVoiceChannelId', '');
                    setValue('p_userLimit', '0');
                  }

                  function resetNumberChainSettings(){
                    setChecked('nc_enabled', false);
                    setValue('nc_channelId', '');
                    setValue('nc_nextNumber', '1');
                  }

                  function resetTicketSettings(){
                    setChecked('t_enabled', false);
                    setValue('t_panelChannelId', '');
                    setValue('t_panelTitle', '');
                    setValue('t_panelDescription', '');
                    setValue('t_panelColor', '#5865F2');
                    setValue('t_panelButtonLimit', '3');
                    setValue('t_autoCloseDays', '3');
                    setValue('t_maxOpenPerUser', '1');
                    setValue('t_openUiMode', 'BUTTONS');
                    setMultiSelectValues('t_supportRoleIds', []);
                    setValue('t_blacklistedUserIds', '');
                    ticketOptionsState = [{
                      id: 'general',
                      label: defaultTicketOptionLabel(),
                      panelTitle: '',
                      panelDescription: '',
                      panelButtonStyle: 'PRIMARY',
                      welcomeMessage: '',
                      preOpenFormEnabled: false,
                      preOpenFormTitle: '',
                      preOpenFormLabel: '',
                      preOpenFormPlaceholder: ''
                    }];
                    selectedTicketOptionId = 'general';
                    renderTicketOptions();
                    applyTicketPanelDefaultsIfEmpty();
                  }

                  function resetSection(section){
                    switch(section){
                      case 'general':
                        resetGeneralSettings();
                        break;
                      case 'notifications':
                        resetNotificationSettings();
                        break;
                      case 'logs':
                        resetLogSettings();
                        break;
                      case 'music':
                        resetMusicSettings();
                        break;
                      case 'privateRoom':
                        resetPrivateRoomSettings();
                        break;
                      case 'numberChain':
                        resetNumberChainSettings();
                        break;
                      case 'ticket':
                        resetTicketSettings();
                        break;
                      default:
                        return;
                    }
                    const sectionName = t(`tabs_${section}`) || section;
                    const message = t('sectionResetDone').replace('{section}', sectionName);
                    showStatus(message);
                    showToast(message, 'success');
                  }

                """;
        html += """
                  function populateSettings(s){
                    setValue('s_language', s.language || 'zh-TW');

                    const n = s.notifications || {};
                    setChecked('n_enabled', n.enabled);
                    setChecked('n_memberJoinEnabled', n.memberJoinEnabled);
                    setChecked('n_memberLeaveEnabled', n.memberLeaveEnabled);
                    setChecked('n_voiceLogEnabled', n.voiceLogEnabled);
                    setValue('n_memberChannelId', n.memberChannelId || '');
                    setValue('n_memberJoinChannelId', n.memberJoinChannelId || '');
                    setValue('n_memberLeaveChannelId', n.memberLeaveChannelId || '');
                    setValue('n_voiceChannelId', n.voiceChannelId || '');
                    setValue('n_memberJoinMessage', n.memberJoinMessage || '');
                    setValue('n_memberLeaveMessage', n.memberLeaveMessage || '');
                    setValue('n_voiceJoinMessage', n.voiceJoinMessage || '');
                    setValue('n_voiceLeaveMessage', n.voiceLeaveMessage || '');
                    setValue('n_voiceMoveMessage', n.voiceMoveMessage || '');
                    setValue('n_memberJoinColor', n.memberJoinColor || '#2ECC71');
                    setValue('n_memberLeaveColor', n.memberLeaveColor || '#E74C3C');
                    applyNotificationTemplateDefaults();

                    const l = s.messageLogs || {};
                    setChecked('l_enabled', l.enabled);
                    setChecked('l_roleLogEnabled', l.roleLogEnabled);
                    setChecked('l_channelLifecycleLogEnabled', l.channelLifecycleLogEnabled);
                    setChecked('l_moderationLogEnabled', l.moderationLogEnabled);
                    setChecked('l_commandUsageLogEnabled', l.commandUsageLogEnabled);
                    setValue('l_channelId', l.channelId || '');
                    setValue('l_messageLogChannelId', l.messageLogChannelId || '');
                    setValue('l_commandUsageChannelId', l.commandUsageChannelId || '');
                    setValue('l_channelLifecycleChannelId', l.channelLifecycleChannelId || '');
                    setValue('l_roleLogChannelId', l.roleLogChannelId || '');
                    setValue('l_moderationLogChannelId', l.moderationLogChannelId || '');

                    const m = s.music || {};
                    setChecked('m_autoLeaveEnabled', m.autoLeaveEnabled);
                    setValue('m_autoLeaveMinutes', String(m.autoLeaveMinutes ?? 5));
                    setChecked('m_autoplayEnabled', m.autoplayEnabled);
                    setValue('m_defaultRepeatMode', m.defaultRepeatMode || 'OFF');
                    setValue('m_commandChannelId', m.commandChannelId || '');
                    renderMusicStats(s.musicStats || {});

                    const p = s.privateRoom || {};
                    setChecked('p_enabled', p.enabled);
                    setValue('p_triggerVoiceChannelId', p.triggerVoiceChannelId || '');
                    setValue('p_userLimit', String(p.userLimit ?? 0));

                    const nc = s.numberChain || {};
                    setChecked('nc_enabled', nc.enabled);
                    setValue('nc_channelId', nc.channelId || '');
                    setValue('nc_nextNumber', String(nc.nextNumber ?? 1));

                    const ticketCfg = s.ticket || {};
                    setChecked('t_enabled', ticketCfg.enabled);
                    setValue('t_panelChannelId', ticketCfg.panelChannelId || '');
                    setValue('t_panelTitle', ticketCfg.panelTitle || t('ticket_default_panel_title'));
                    setValue('t_panelDescription', ticketCfg.panelDescription || t('ticket_default_panel_desc'));
                    setValue('t_panelColor', ticketCfg.panelColor || '#5865F2');
                    setValue('t_panelButtonLimit', String(ticketCfg.panelButtonLimit ?? 3));
                    setValue('t_autoCloseDays', String(ticketCfg.autoCloseDays ?? 3));
                    setValue('t_maxOpenPerUser', String(ticketCfg.maxOpenPerUser ?? 1));
                    setValue('t_openUiMode', (ticketCfg.openUiMode || 'BUTTONS').toUpperCase());
                    ticketOptionsState = Array.isArray(ticketCfg.options)
                      ? ticketCfg.options.map(item => ({
                          id: String(item.id || `option-${Date.now().toString(36)}`),
                          label: String(item.label || ''),
                          panelTitle: String(item.panelTitle || ''),
                          panelDescription: String(item.panelDescription || ''),
                          panelButtonStyle: String(item.panelButtonStyle || 'PRIMARY').toUpperCase(),
                          welcomeMessage: String(item.welcomeMessage || t('ticket_default_welcome_message')),
                          preOpenFormEnabled: !!item.preOpenFormEnabled,
                          preOpenFormTitle: String(item.preOpenFormTitle || ''),
                          preOpenFormLabel: String(item.preOpenFormLabel || ''),
                          preOpenFormPlaceholder: String(item.preOpenFormPlaceholder || '')
                        }))
                      : [];
                    selectedTicketOptionId = ticketOptionsState[0]?.id || '';
                    renderTicketOptions();
                    const supportRoleIds = Array.isArray(ticketCfg.supportRoleIds)
                      ? ticketCfg.supportRoleIds
                      : (String(ticketCfg.supportRoleIds || '')
                          .split(',')
                          .map(s => s.trim())
                          .filter(Boolean));
                    setMultiSelectValues('t_supportRoleIds', supportRoleIds);
                    const blacklistedUserIds = Array.isArray(ticketCfg.blacklistedUserIds)
                      ? ticketCfg.blacklistedUserIds
                      : (String(ticketCfg.blacklistedUserIds || '')
                          .split(',')
                          .map(s => s.trim())
                          .filter(Boolean));
                    setValue('t_blacklistedUserIds', blacklistedUserIds.join(', '));
                    applyTicketPanelDefaultsIfEmpty();
                  }

                  function collectSettings(){
                    syncTicketOptionDraftFromEditor();
                    return {
                      language: getValue('s_language') || 'zh-TW',
                      notifications: {
                        enabled: getChecked('n_enabled'),
                        memberJoinEnabled: getChecked('n_memberJoinEnabled'),
                        memberLeaveEnabled: getChecked('n_memberLeaveEnabled'),
                        voiceLogEnabled: getChecked('n_voiceLogEnabled'),
                        memberChannelId: getValue('n_memberChannelId'),
                        memberJoinChannelId: getValue('n_memberJoinChannelId'),
                        memberLeaveChannelId: getValue('n_memberLeaveChannelId'),
                        memberJoinMessage: getValue('n_memberJoinMessage'),
                        memberLeaveMessage: getValue('n_memberLeaveMessage'),
                        memberJoinColor: getValue('n_memberJoinColor') || '#2ECC71',
                        memberLeaveColor: getValue('n_memberLeaveColor') || '#E74C3C',
                        voiceChannelId: getValue('n_voiceChannelId'),
                        voiceJoinMessage: getValue('n_voiceJoinMessage'),
                        voiceLeaveMessage: getValue('n_voiceLeaveMessage'),
                        voiceMoveMessage: getValue('n_voiceMoveMessage')
                      },
                      messageLogs: {
                        enabled: getChecked('l_enabled'),
                        channelId: getValue('l_channelId'),
                        messageLogChannelId: getValue('l_messageLogChannelId'),
                        commandUsageChannelId: getValue('l_commandUsageChannelId'),
                        channelLifecycleChannelId: getValue('l_channelLifecycleChannelId'),
                        roleLogChannelId: getValue('l_roleLogChannelId'),
                        moderationLogChannelId: getValue('l_moderationLogChannelId'),
                        roleLogEnabled: getChecked('l_roleLogEnabled'),
                        channelLifecycleLogEnabled: getChecked('l_channelLifecycleLogEnabled'),
                        moderationLogEnabled: getChecked('l_moderationLogEnabled'),
                        commandUsageLogEnabled: getChecked('l_commandUsageLogEnabled')
                      },
                      music: {
                        autoLeaveEnabled: getChecked('m_autoLeaveEnabled'),
                        autoLeaveMinutes: Number(getValue('m_autoLeaveMinutes') || '5'),
                        autoplayEnabled: getChecked('m_autoplayEnabled'),
                        defaultRepeatMode: getValue('m_defaultRepeatMode') || 'OFF',
                        commandChannelId: getValue('m_commandChannelId')
                      },
                      privateRoom: {
                        enabled: getChecked('p_enabled'),
                        triggerVoiceChannelId: getValue('p_triggerVoiceChannelId'),
                        userLimit: Number(getValue('p_userLimit') || '0')
                      },
                      numberChain: {
                        enabled: getChecked('nc_enabled'),
                        channelId: getValue('nc_channelId')
                      },
                      ticket: {
                        enabled: getChecked('t_enabled'),
                        panelChannelId: getValue('t_panelChannelId'),
                        panelTitle: getValue('t_panelTitle'),
                        panelDescription: getValue('t_panelDescription'),
                        panelColor: getValue('t_panelColor') || '#5865F2',
                        panelButtonLimit: Number(getValue('t_panelButtonLimit') || '3'),
                        autoCloseDays: Number(getValue('t_autoCloseDays') || '3'),
                        maxOpenPerUser: Number(getValue('t_maxOpenPerUser') || '1'),
                        openUiMode: getValue('t_openUiMode') || 'BUTTONS',
                        options: ticketOptionsState.map(item => ({
                          id: item.id,
                          label: item.label,
                          panelTitle: item.panelTitle,
                          panelDescription: item.panelDescription,
                          panelButtonStyle: item.panelButtonStyle,
                          welcomeMessage: item.welcomeMessage,
                          preOpenFormEnabled: !!item.preOpenFormEnabled,
                          preOpenFormTitle: item.preOpenFormTitle,
                          preOpenFormLabel: item.preOpenFormLabel,
                          preOpenFormPlaceholder: item.preOpenFormPlaceholder
                        })),
                        supportRoleIds: getMultiSelectValues('t_supportRoleIds'),
                        blacklistedUserIds: getValue('t_blacklistedUserIds')
                      }
                    };
                  }

                  async function loadSettings(){
                    const guildId = selectedGuild();
                    if(!guildId) return;
                    const data = await api(`/api/guild/${guildId}/settings`);
                    populateSettings(data);
                    showStatus(t('settingsLoaded'));
                  }

                  async function saveSettings(){
                    const guildId = selectedGuild();
                    if(!guildId) return;
                    const payload = collectSettings();
                    const saveBtn = byId('saveSettingsBtn');
                    if (saveBtn) {
                      saveBtn.disabled = true;
                      saveBtn.classList.add('saving');
                    }
                    try {
                      await api(`/api/guild/${guildId}/settings`, {
                        method:'POST',
                        headers:{'Content-Type':'application/json'},
                        body: JSON.stringify(payload)
                      });
                      await loadSettings();
                      showStatus(t('settingsSaved'));
                      showToast(t('settingsSaved'), 'success');
                    } catch (e) {
                      showStatus(e.message || 'Save failed');
                      showToast(e.message || 'Save failed', 'error');
                      throw e;
                    } finally {
                      if (saveBtn) {
                        saveBtn.disabled = false;
                        saveBtn.classList.remove('saving');
                      }
                    }
                  }

                  async function sendTicketPanel(){
                    const guildId = selectedGuild();
                    if(!guildId) return;
                    try {
                      const data = await api(`/api/guild/${guildId}/ticket/panel`, { method:'POST' });
                      showStatus(data?.message || t('ticketPanelSent'));
                      showToast(data?.message || t('ticketPanelSent'), 'success');
                    } catch (e) {
                      showStatus(e.message || t('ticketPanelSendFailed'));
                      showToast(e.message || t('ticketPanelSendFailed'), 'error');
                    }
                  }

                  async function resetNumberChainProgress(){
                    const guildId = selectedGuild();
                    if(!guildId) return;
                    try {
                      const data = await api(`/api/guild/${guildId}/number-chain/reset`, { method:'POST' });
                      setValue('nc_nextNumber', String(data?.nextNumber ?? 1));
                      showStatus(t('numberChainResetSuccess'));
                      showToast(t('numberChainResetSuccess'), 'success');
                    } catch (e) {
                      showStatus(e.message || t('numberChainResetFailed'));
                      showToast(e.message || t('numberChainResetFailed'), 'error');
                    }
                  }

                  function formatBytes(size){
                    const n = Number(size || 0);
                    if (n < 1024) return `${n} B`;
                    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
                    return `${(n / (1024 * 1024)).toFixed(1)} MB`;
                  }

                  function formatDateTime(ts){
                    const value = Number(ts || 0);
                    if (!value) return '-';
                    const d = new Date(value);
                    if (Number.isNaN(d.getTime())) return '-';
                    return d.toLocaleString();
                  }

                  async function loadTicketHistory(){
                    const guildId = selectedGuild();
                    if(!guildId) return;
                    const listEl = byId('ticketHistoryList');
                    const metaEl = byId('ticketHistoryMeta');
                    if (listEl) listEl.innerHTML = '';
                    if (metaEl) metaEl.textContent = t('ticket_history_loading');
                    try {
                      const data = await api(`/api/guild/${guildId}/ticket/transcripts`);
                      const files = Array.isArray(data?.files) ? data.files : [];
                      ticketHistoryFilesState = files;
                      ticketHistoryRetentionDays = Number(data?.retentionDays || 90);
                      ticketHistoryCleanedCount = Number(data?.cleaned || 0);
                      if (metaEl) {
                        metaEl.textContent = t('ticket_history_meta')
                          .replace('{count}', String(files.length))
                          .replace('{days}', String(ticketHistoryRetentionDays))
                          .replace('{cleaned}', String(ticketHistoryCleanedCount));
                      }
                      renderTicketHistoryList();
                    } catch (e) {
                      if (metaEl) metaEl.textContent = e.message || t('ticket_history_load_failed');
                    }
                  }

                  function renderTicketHistoryList(){
                    const listEl = byId('ticketHistoryList');
                    if (!listEl) return;
                    listEl.innerHTML = '';
                    const files = Array.isArray(ticketHistoryFilesState) ? ticketHistoryFilesState : [];
                    if (files.length === 0) {
                      const empty = document.createElement('div');
                      empty.className = 'keyhint';
                      empty.textContent = t('ticket_history_empty');
                      listEl.appendChild(empty);
                      return;
                    }
                    files.forEach(file => {
                      const item = document.createElement('div');
                      item.className = 'history-item';
                      const mention = file.channelId && Number(file.channelId) > 0 ? `<#${file.channelId}>` : '-';
                      const encodedName = encodeURIComponent(String(file.name || ''));
                      const displayName = String(file.name || '');
                      const isConfirming = pendingDeleteTranscriptName === String(file.name || '');
                      item.innerHTML = `
                        <div>
                          <div><a href="${esc(file.url || '#')}" target="_blank" rel="noopener">${esc(file.name || '-')}</a></div>
                          <div class="history-meta">${esc(t('ticket_history_channel'))}: ${esc(mention)} | ${esc(t('ticket_history_time'))}: ${esc(formatDateTime(file.lastModifiedAt))}</div>
                        </div>
                        <div class="history-actions">
                          <div class="history-meta">${esc(formatBytes(file.size))}</div>
                          ${isConfirming
                            ? `<button type="button" class="warn" data-action="confirm-delete-transcript" data-file="${encodedName}" data-name="${encodedName}">${esc(t('ticket_history_delete_confirm'))}</button>
                               <button type="button" data-action="cancel-delete-transcript">${esc(t('ticket_history_delete_cancel'))}</button>`
                            : `<button type="button" class="warn" data-action="delete-transcript" data-file="${encodedName}" data-name="${encodedName}">${esc(t('ticket_history_delete'))}</button>`}
                        </div>
                      `;
                      listEl.appendChild(item);
                    });
                  }

                  async function deleteTicketTranscript(encodedName, displayName){
                    const guildId = selectedGuild();
                    if(!guildId || !encodedName) return;
                    try {
                      const data = await api(`/api/guild/${guildId}/ticket/transcript/${encodedName}`, { method:'DELETE' });
                      pendingDeleteTranscriptName = '';
                      ticketHistoryFilesState = ticketHistoryFilesState.filter(item => String(item.name || '') !== String(displayName || ''));
                      renderTicketHistoryList();
                      const message = data?.message || t('ticket_history_delete_success').replace('{name}', String(displayName || ''));
                      showToast(message, 'success');
                      const metaEl = byId('ticketHistoryMeta');
                      if (metaEl) {
                        metaEl.textContent = t('ticket_history_meta')
                          .replace('{count}', String(ticketHistoryFilesState.length))
                          .replace('{days}', String(ticketHistoryRetentionDays))
                          .replace('{cleaned}', String(ticketHistoryCleanedCount));
                      }
                    } catch (e) {
                      showToast(e.message || t('ticket_history_delete_failed'), 'error');
                    }
                  }

                  async function onGuildSelected(gid){
                    if (!gid) return;
                    guildSelect.value = gid;
                    await loadChannels().catch(() => {});
                    await loadRoles().catch(() => {});
                    await loadSettings().catch(e => showStatus(e.message));
                    await loadTicketHistory().catch(() => {});
                  }

                  async function loadGuilds(){
                    const g = await api('/api/guilds');
                    const allGuilds = g.guilds || [];
                    const manageable = allGuilds.filter(x => x.botInGuild);
                    const invitables = allGuilds.filter(x => !x.botInGuild);

                    guildList.innerHTML = '';
                    allGuilds.forEach(item => {
                      const buttonHtml = item.botInGuild
                        ? `<button class="primary" data-manage="${esc(item.id)}">${esc(t('manage'))}</button>`
                        : `<a href="${esc(item.inviteUrl)}" target="_blank" rel="noopener"><button class="warn">${esc(t('inviteBot'))}</button></a>`;
                      const badge = item.botInGuild
                        ? (item.botCanManage ? t('badgeManageable') : t('badgeMissingPerm'))
                        : t('badgeBotMissing');
                      const iconHtml = item.iconUrl
                        ? `<img class="guild-icon" src="${esc(item.iconUrl)}" alt="${esc(item.name)}" loading="lazy" referrerpolicy="no-referrer" />`
                        : `<div class="guild-icon guild-icon-fallback" aria-hidden="true">${esc(guildInitial(item.name))}</div>`;
                      const block = document.createElement('div');
                      block.className = 'guild-item';
                      block.innerHTML = `<div class="guild-head">${iconHtml}<div><div class="guild-name">${esc(item.name)}</div><div class="badge">${esc(badge)}</div></div></div>${buttonHtml}`;
                      guildList.appendChild(block);
                    });

                    guildList.querySelectorAll('[data-manage]').forEach(btn => {
                      btn.onclick = async () => {
                        const gid = btn.getAttribute('data-manage');
                        await onGuildSelected(gid);
                      };
                    });

                    guildSelect.innerHTML = '';
                    manageable.forEach(item => {
                      const op = document.createElement('option');
                      op.value = item.id;
                      op.textContent = item.name;
                      guildSelect.appendChild(op);
                    });

                    if(!guildSelect.value){
                      showStatus(invitables.length > 0
                        ? t('noGuildWithBot')
                        : t('noManageableGuild'));
                      return;
                    }
                    await onGuildSelected(guildSelect.value);
                  }

                  function initTabs(){
                    const btns = document.querySelectorAll('.tab-btn');
                    const panes = document.querySelectorAll('.tab-pane');
                    btns.forEach(btn => {
                      btn.onclick = () => {
                        const tab = btn.getAttribute('data-tab');
                        btns.forEach(b => b.classList.toggle('active', b === btn));
                        panes.forEach(p => p.classList.toggle('active', p.getAttribute('data-pane') === tab));
                      };
                    });
                  }

                  async function init(){
                    initTabs();
                    uiLang = localStorage.getItem('norule.web.ui.lang') || 'zh-TW';
                    await loadWebI18n();
                    setUiLanguage(uiLang);
                    try {
                      const me = await api('/api/me');
                      authBlock.classList.add('hidden');
                      userBlock.classList.remove('hidden');
                      guildsBlock.classList.remove('hidden');
                      settingsBlock.classList.remove('hidden');
                      byId('meLine').dataset.username = me.username;
                      byId('meLine').dataset.userId = me.id;
                      byId('meLine').dataset.avatarUrl = me.avatarUrl || '';
                      updateMeLine();
                      await loadGuilds();
                    } catch (_) {
                      authBlock.classList.remove('hidden');
                      userBlock.classList.add('hidden');
                      guildsBlock.classList.add('hidden');
                      settingsBlock.classList.add('hidden');
                    }
                  }

                  if (byId('loginBtn')) byId('loginBtn').onclick = () => location.href = '/auth/login';
                  if (byId('logoutBtn')) byId('logoutBtn').onclick = () => location.href = '/auth/logout';
                  if (byId('guildReloadBtn')) byId('guildReloadBtn').onclick = () => onGuildSelected(guildSelect.value).catch(e => showStatus(e.message));
                  if (byId('loadSettingsBtn')) byId('loadSettingsBtn').onclick = () => loadSettings().catch(e => alert(e.message));
                  if (byId('resetGeneralBtn')) byId('resetGeneralBtn').onclick = () => resetSection('general');
                  if (byId('resetNotificationsBtn')) byId('resetNotificationsBtn').onclick = () => resetSection('notifications');
                  if (byId('resetLogsBtn')) byId('resetLogsBtn').onclick = () => resetSection('logs');
                  if (byId('resetMusicBtn')) byId('resetMusicBtn').onclick = () => resetSection('music');
                  if (byId('resetPrivateRoomBtn')) byId('resetPrivateRoomBtn').onclick = () => resetSection('privateRoom');
                  if (byId('resetNumberChainBtn')) byId('resetNumberChainBtn').onclick = () => resetSection('numberChain');
                  if (byId('resetTicketBtn')) byId('resetTicketBtn').onclick = () => resetSection('ticket');
                  if (byId('s_language')) byId('s_language').addEventListener('change', () => {
                    applyNotificationTemplateDefaults();
                    applyTicketPanelDefaultsIfEmpty();
                  });
                  if (byId('resetNumberChainProgressBtn')) byId('resetNumberChainProgressBtn').onclick = () => resetNumberChainProgress();
                  if (byId('sendTicketPanelBtn')) byId('sendTicketPanelBtn').onclick = () => sendTicketPanel();
                  if (byId('t_addOptionBtn')) byId('t_addOptionBtn').onclick = () => addTicketOption();
                  if (byId('t_deleteOptionBtn')) byId('t_deleteOptionBtn').onclick = () => deleteCurrentTicketOption();
                  if (byId('loadTicketHistoryBtn')) byId('loadTicketHistoryBtn').onclick = () => loadTicketHistory().catch(() => {});
                  if (byId('ticketHistoryList')) byId('ticketHistoryList').onclick = async (event) => {
                    const btn = event.target.closest('button[data-action]');
                    if (!btn) return;
                    const action = btn.dataset.action || '';
                    if (action === 'delete-transcript') {
                      pendingDeleteTranscriptName = decodeURIComponent(btn.dataset.name || '');
                      renderTicketHistoryList();
                      return;
                    }
                    if (action === 'cancel-delete-transcript') {
                      pendingDeleteTranscriptName = '';
                      renderTicketHistoryList();
                      return;
                    }
                    if (action === 'confirm-delete-transcript') {
                      const encodedName = btn.dataset.file || '';
                      const displayName = decodeURIComponent(btn.dataset.name || '');
                      await deleteTicketTranscript(encodedName, displayName);
                    }
                  };
                  if (byId('saveSettingsBtn')) byId('saveSettingsBtn').onclick = () => saveSettings().catch(() => {});
                  if (byId('t_supportRoleIds')) byId('t_supportRoleIds').addEventListener('change', updateSupportRoleCount);
                  bindTicketOptionEditorAutoSync();
                  guildSelect.onchange = () => onGuildSelected(guildSelect.value).catch(e => showStatus(e.message));
                  init();
                </script>
                <div id="toastHost" class="toast-host" aria-live="polite" aria-atomic="true"></div>
                </body>
                </html>
                """;
        return html.replace("__BOT_AVATAR_BLOCK__", botAvatarBlock)
                .replace("__FAVICON_URL__", escapeHtmlAttr(faviconUrl));
    }

    private static class OAuthState {
        private final long expiresAtMillis;

        private OAuthState(long expiresAtMillis) {
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static class WebSession {
        private final String userId;
        private final String username;
        private final String avatarUrl;
        private final String accessToken;
        private final long expiresAtMillis;

        private WebSession(String userId, String username, String avatarUrl, String accessToken, long expiresAtMillis) {
            this.userId = userId;
            this.username = username;
            this.avatarUrl = avatarUrl;
            this.accessToken = accessToken;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}




