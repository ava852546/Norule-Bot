package com.norule.musicbot;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpServer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
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
    private static final BigInteger ADMINISTRATOR_BIT = new BigInteger("8");
    private static final BigInteger MANAGE_GUILD_BIT = new BigInteger("32");

    private final JDA jda;
    private final MusicPlayerService musicService;
    private final GuildSettingsService settingsService;
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
                            Supplier<BotConfig> configSupplier,
                            I18nService i18n) {
        this.jda = jda;
        this.musicService = musicService;
        this.settingsService = settingsService;
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

        if ("channels".equals(section)) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleGuildChannelsGet(exchange, guild);
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

    private void handleSettingsGet(HttpExchange exchange, Guild guild) throws IOException {
        GuildSettingsService.GuildSettings settings = settingsService.getSettings(guild.getIdLong());
        BotConfig.Notifications n = settings.getNotifications();
        BotConfig.MessageLogs l = settings.getMessageLogs();
        BotConfig.Music m = settings.getMusic();
        BotConfig.PrivateRoom p = settings.getPrivateRoom();

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
                .put("privateRoom", DataObject.empty()
                        .put("enabled", p.isEnabled())
                        .put("triggerVoiceChannelId", toIdText(p.getTriggerVoiceChannelId()))
                        .put("userLimit", p.getUserLimit()));
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

            return current.withLanguage(language)
                    .withNotifications(n)
                    .withMessageLogs(l)
                    .withMusic(m)
                    .withPrivateRoom(p);
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
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void sendHtml(HttpExchange exchange, int statusCode, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void sendText(HttpExchange exchange, int statusCode, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
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

        return """
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
                      --bg:#0f172a;
                      --panel:#111827;
                      --text:#e5e7eb;
                      --muted:#9ca3af;
                      --accent:#22c55e;
                      --accent2:#3b82f6;
                      --warn:#f59e0b;
                    }
                    body { margin:0; background: radial-gradient(circle at 20% 10%, #1f2937, #0b1020 60%); color:var(--text); font-family:Segoe UI, sans-serif; }
                    .wrap { max-width:1180px; margin:40px auto; padding:0 16px; }
                    .card { background:linear-gradient(150deg, rgba(17,24,39,.95), rgba(15,23,42,.95)); border:1px solid rgba(255,255,255,.08); border-radius:14px; padding:16px; margin-bottom:14px; }
                    h1 { margin:0 0 8px; font-size:24px; }
                    h2 { margin:0 0 8px; font-size:17px; }
                    h3 { margin:12px 0 6px; font-size:14px; color:#d1d5db; }
                    .muted { color:var(--muted); font-size:13px; }
                    .row { display:flex; gap:8px; flex-wrap:wrap; margin-top:10px; }
                    .grid2 { display:grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap:10px; }
                    .grid3 { display:grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap:10px; }
                    @media (max-width:900px){ .grid2,.grid3{ grid-template-columns:1fr; } }
                    input,select,button,textarea{ border:1px solid rgba(255,255,255,.12); background:#111827; color:#f9fafb; border-radius:10px; padding:9px 12px; }
                    input,select,textarea{ width:100%; box-sizing:border-box; }
                    button{ cursor:pointer; }
                    button.primary{ background:linear-gradient(90deg,var(--accent2),var(--accent)); border:none; }
                    button.warn{ background:linear-gradient(90deg,#ef4444,var(--warn)); border:none; }
                    .hidden{display:none;}
                    .guild-grid{ margin-top:10px; display:grid; grid-template-columns:repeat(auto-fill,minmax(280px,1fr)); gap:10px; }
                    .guild-item{ border:1px solid rgba(255,255,255,.08); border-radius:12px; padding:10px; background:rgba(255,255,255,.02); }
                    .guild-head{ display:flex; gap:10px; align-items:center; margin-bottom:6px; }
                    .guild-icon{ width:40px; height:40px; border-radius:10px; object-fit:cover; background:#0b1220; flex:0 0 40px; }
                    .guild-icon-fallback{ display:flex; align-items:center; justify-content:center; font-weight:700; color:#e5e7eb; border:1px solid rgba(255,255,255,.14); }
                    .guild-name{ font-weight:600; margin-bottom:6px; }
                    .badge{ font-size:12px; color:#d1d5db; margin-bottom:8px; }
                    .user-profile{ display:flex; align-items:center; gap:10px; margin-top:10px; }
                    .user-avatar{ width:36px; height:36px; border-radius:50%; object-fit:cover; border:1px solid rgba(255,255,255,.16); background:#0b1220; }
                    #status{ white-space:pre-wrap; line-height:1.5; font-family:Consolas,monospace; }
                    .tabs{ display:flex; gap:8px; flex-wrap:wrap; margin:10px 0; }
                    .tab-btn{ padding:8px 10px; width:auto; }
                    .tab-btn.active{ background:linear-gradient(90deg,var(--accent2),var(--accent)); border:none; }
                    .tab-pane{ display:none; }
                    .tab-pane.active{ display:block; }
                    .field{ display:flex; flex-direction:column; gap:6px; }
                    .field label{ font-size:12px; color:#cbd5e1; }
                    .toggle{ display:flex; align-items:center; gap:8px; }
                    .toggle input{ width:auto; }
                    textarea{ min-height:72px; resize:vertical; }
                    .keyhint{ color:#94a3b8; font-size:12px; }
                    .lang-switch { align-items:center; }
                    .lang-switch .lang-btn { width:auto; min-width:64px; }
                    .lang-switch .lang-btn.active { background:linear-gradient(90deg,var(--accent2),var(--accent)); border:none; }
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
                    </div>

                    <div class="tab-pane active" data-pane="general">
                      <h3>language</h3>
                      <div class="grid2">
                        <div class="field">
                          <label id="label_s_language">Language</label>
                          <select id="s_language"></select>
                          <div id="hint_s_language" class="keyhint">Default: zh-TW. Saving this will sync guild locale settings.</div>
                        </div>
                      </div>
                    </div>

                    <div class="tab-pane" data-pane="notifications">
                      <h3>notifications.*</h3>
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
                      <h3>messageLogs.*</h3>
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
                      <h3>music.*</h3>
                      <div class="grid3">
                        <div class="toggle"><input type="checkbox" id="m_autoLeaveEnabled"><label for="m_autoLeaveEnabled">autoLeaveEnabled</label></div>
                        <div class="field"><label>autoLeaveMinutes (1-60)</label><input type="number" min="1" max="60" id="m_autoLeaveMinutes"></div>
                        <div class="toggle"><input type="checkbox" id="m_autoplayEnabled"><label for="m_autoplayEnabled">autoplayEnabled</label></div>
                        <div class="field"><label>defaultRepeatMode</label><select id="m_defaultRepeatMode"><option value="OFF">OFF</option><option value="SINGLE">SINGLE</option><option value="ALL">ALL</option></select></div>
                        <div class="field"><label>commandChannelId</label><select id="m_commandChannelId"></select></div>
                      </div>
                    </div>

                    <div class="tab-pane" data-pane="privateRoom">
                      <h3>privateRoom.*</h3>
                      <div class="grid3">
                        <div class="toggle"><input type="checkbox" id="p_enabled"><label for="p_enabled">enabled</label></div>
                        <div class="field"><label>triggerVoiceChannelId</label><select id="p_triggerVoiceChannelId"></select></div>
                        <div class="field"><label>userLimit (0-99)</label><input type="number" min="0" max="99" id="p_userLimit"></div>
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
                  let uiLanguages = [];
                  let botLanguages = [];
                  let defaultUiLanguage = 'zh-TW';
                  let uiLang = 'zh-TW';

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

                    setSectionLabel('general', 'section_language');
                    setSectionLabel('notifications', 'section_notifications');
                    setSectionLabel('logs', 'section_logs');
                    setSectionLabel('music', 'section_music');
                    setSectionLabel('privateRoom', 'section_privateRoom');

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

                    setCheckboxLabel('p_enabled', 'p_enabled');
                    setFieldLabel('p_triggerVoiceChannelId', 'p_triggerVoiceChannelId');
                    setFieldLabel('p_userLimit', 'p_userLimit');
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
                    setText('saveSettingsBtn', 'saveSettingsBtn');
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
                        botLanguages = [{ code: 'zh-TW', label: '繁體中文' }, { code: 'en', label: 'English' }];
                      }
                    } catch (_) {
                      uiLanguages = [{ code: 'zh-TW', label: '繁中' }, { code: 'en', label: 'ENG' }];
                      botLanguages = [{ code: 'zh-TW', label: '繁體中文' }, { code: 'en', label: 'English' }];
                    }
                  }

                  function showStatus(text){ statusEl.textContent = text || ''; }
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

                  async function loadChannels(){
                    const guildId = selectedGuild();
                    if(!guildId) return;
                    channelsCache = await api(`/api/guild/${guildId}/channels`);
                    const textIds = ['n_memberChannelId','n_memberJoinChannelId','n_memberLeaveChannelId','l_channelId','l_messageLogChannelId','l_commandUsageChannelId','l_channelLifecycleChannelId','l_roleLogChannelId','l_moderationLogChannelId','m_commandChannelId'];
                    const voiceIds = ['n_voiceChannelId','p_triggerVoiceChannelId'];
                    textIds.forEach(id => setSelectOptions(id, channelsCache.textChannels || []));
                    voiceIds.forEach(id => setSelectOptions(id, channelsCache.voiceChannels || []));
                  }

                  function setChecked(id, value){ const el = byId(id); if(el) el.checked = !!value; }
                  function setValue(id, value){ const el = byId(id); if(el) el.value = value ?? ''; }
                  function getChecked(id){ return !!byId(id)?.checked; }
                  function getValue(id){ return (byId(id)?.value ?? '').trim(); }

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

                    const p = s.privateRoom || {};
                    setChecked('p_enabled', p.enabled);
                    setValue('p_triggerVoiceChannelId', p.triggerVoiceChannelId || '');
                    setValue('p_userLimit', String(p.userLimit ?? 0));
                  }

                  function collectSettings(){
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
                    await api(`/api/guild/${guildId}/settings`, {
                      method:'POST',
                      headers:{'Content-Type':'application/json'},
                      body: JSON.stringify(payload)
                    });
                    await loadSettings();
                    showStatus(t('settingsSaved'));
                  }

                  async function onGuildSelected(gid){
                    if (!gid) return;
                    guildSelect.value = gid;
                    await loadChannels().catch(() => {});
                    await loadSettings().catch(e => showStatus(e.message));
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
                  if (byId('saveSettingsBtn')) byId('saveSettingsBtn').onclick = () => saveSettings().catch(e => alert(e.message));
                  guildSelect.onchange = () => onGuildSelected(guildSelect.value).catch(e => showStatus(e.message));
                  init();
                </script>
                </body>
                </html>
                """.replace("__BOT_AVATAR_BLOCK__", botAvatarBlock)
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



