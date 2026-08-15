package com.norule.musicbot.domain.music;

import com.norule.musicbot.config.domain.MusicConfig;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.ResamplingPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import dev.lavalink.bilibili.BilibiliAudioSourceManager;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.AndroidMusicWithThumbnail;
import dev.lavalink.youtube.clients.AndroidVrWithThumbnail;
import dev.lavalink.youtube.clients.MWebWithThumbnail;
import dev.lavalink.youtube.clients.IosWithThumbnail;
import dev.lavalink.youtube.clients.MusicWithThumbnail;
import dev.lavalink.youtube.clients.Tv;
import dev.lavalink.youtube.clients.TvHtml5SimplyWithThumbnail;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbeddedWithThumbnail;
import dev.lavalink.youtube.clients.WebWithThumbnail;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

import org.apache.http.ProtocolException;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongToIntFunction;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MusicPlayerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicPlayerService.class);
    private static final String YT_SEARCH_PREFIX = "ytsearch:";
    private static final Pattern JSON_FIELD_PATTERN_TEMPLATE = Pattern.compile("\"%s\"\\s*:\\s*\"(.*?)\"");
    private static final long SPOTIFY_RATE_LIMIT_COOLDOWN_MS = 10 * 60_000L;
    private static final int SPOTIFY_TIMEOUT_RETRY_MAX_ATTEMPTS = 2;
    private static final String SPOTIFY_RATE_LIMIT_ERROR_KEY = "SPOTIFY_RATE_LIMITED";
    private static final String SPOTIFY_PLAYLIST_COOLDOWN_ERROR_KEY = "SPOTIFY_PLAYLIST_COOLDOWN";
    private static final String SPOTIFY_RESTRICTED_PLAYLIST_ERROR_KEY = "SPOTIFY_RESTRICTED_OR_PERSONALIZED";
    private static final String SPOTIFY_EMPTY_PLAYLIST_ERROR_KEY = "SPOTIFY_PLAYLIST_EMPTY";
    private static final String SPOTIFY_AUTH_FAILED_ERROR_KEY = "SPOTIFY_AUTH_FAILED";
    private static final String SPOTIFY_UNSUPPORTED_LINK_ERROR_KEY = "SPOTIFY_UNSUPPORTED_LINK";
    private static final String SPOTIFY_JAM_UNSUPPORTED_ERROR_KEY = "SPOTIFY_JAM_UNSUPPORTED";
    private static final String SPOTIFY_SHOW_UNSUPPORTED_ERROR_KEY = "SPOTIFY_SHOW_UNSUPPORTED";
    private static final String SPOTIFY_EPISODE_UNSUPPORTED_ERROR_KEY = "SPOTIFY_EPISODE_UNSUPPORTED";
    private static final String DIRECT_HTTP_DISABLED_ERROR_KEY = "AUDIO_DIRECT_HTTP_DISABLED";
    private static final String TRACK_RECOVERING_ERROR_KEY = "AUDIO_TRACK_RECOVERING";
    private static final String TRACK_RECOVERY_EXHAUSTED_ERROR_KEY = "AUDIO_TRACK_RECOVERY_EXHAUSTED";
    private static final String TRACK_RECOVERY_FAILED_ERROR_KEY = "AUDIO_TRACK_RECOVERY_FAILED";
    private static final AudioLoadFailureClassifier FAILURE_CLASSIFIER = new AudioLoadFailureClassifier();
    private static final YoutubeFailureClassifier YOUTUBE_FAILURE_CLASSIFIER = new YoutubeFailureClassifier();
    private static final Pattern SPOTIFY_URL_START_PATTERN = Pattern.compile(
            "(?i)https?://(?:www\\.)?open\\.spotify\\.com/"
    );
    private static final Pattern SPOTIFY_RESOURCE_PATTERN = Pattern.compile(
            "(?i)^https?://(?:www\\.)?open\\.spotify\\.com/(?:intl-[a-z]{2}/)?"
                    + "(track|album|playlist|artist)/([a-zA-Z0-9_-]+)"
    );
    private static final String YOUTUBE_PRECHECK_BLOCKED_ERROR_KEY = "YOUTUBE_PRECHECK_BLOCKED";
    private static final String YOUTUBE_PRECHECK_TIMEOUT_ERROR_KEY = "YOUTUBE_PRECHECK_TIMEOUT";
    private static final String YOUTUBE_PRECHECK_UNAVAILABLE_ERROR_KEY = "YOUTUBE_PRECHECK_UNAVAILABLE";
    private static final String YOUTUBE_PRECHECK_INVALID_ERROR_KEY = "YOUTUBE_PRECHECK_INVALID";
    private static final String YOUTUBE_PRECHECK_UNKNOWN_ERROR_KEY = "YOUTUBE_PRECHECK_UNKNOWN";
    private static final String STRICT_YOUTUBE_PLAYLIST_PREFIX = "https://www.youtube.com/playlist?list=";
    private static final long YOUTUBE_PLAYLIST_CACHE_TTL_MS = 30 * 60_000L;
    private static final int YOUTUBE_PLAYLIST_BATCH_SIZE = 25;

    private final AudioPlayerManager playerManager;
    private final BilibiliAudioSourceManager bilibiliSourceManager;
    private final MusicDataService musicDataService;
    private final SpotifyPlaylistInspector spotifyPlaylistInspector;
    private final YouTubePlaybackTrackFactory youtubePlaybackTrackFactory;
    private final AudioInputClassifier inputClassifier = new AudioInputClassifier();
    private final TrackRecoveryService trackRecoveryService;
    private final MusicConfig.Youtube.AuthMode effectiveYoutubeAuthMode;
    private final Map<YoutubeFailureCategory, LongAdder> youtubePlaybackFailureCounters =
            createYoutubeFailureCounters();
    private final Map<Long, GuildMusicManager> musicManagers = new ConcurrentHashMap<>();
    private final Map<Long, Runnable> guildStateListeners = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastCommandChannelByGuild = new ConcurrentHashMap<>();
    private final Map<Long, String> autoplayNoticeByGuild = new ConcurrentHashMap<>();
    private final Map<Long, Long> spotifyRateLimitGuildCooldownUntil = new ConcurrentHashMap<>();
    private final Map<Long, Long> spotifyRateLimitUserCooldownUntil = new ConcurrentHashMap<>();
    private final Map<Long, Long> spotifyPlaylistCooldownByGuild = new ConcurrentHashMap<>();
    private final Map<String, CachedPlaylistTracks> youtubePlaylistCache = new ConcurrentHashMap<>();
    private volatile MusicConfig.Youtube youtubeConfig;
    private volatile MusicConfig.Oauth oauthConfig;
    private volatile MusicConfig.Cipher cipherConfig;
    private volatile MusicConfig.Spotify spotifyConfig;
    private volatile MusicConfig.Audio audioConfig;
    private volatile AudioUrlSafetyValidator directHttpValidator;
    private volatile int playlistTrackLimit;
    private volatile int spotifyPlaylistMaxTracks;
    private volatile long spotifyPlaylistLoadCooldownMs;
    private final boolean spotifySourceEnabled;
    private final boolean directHttpSourceEnabled;
    private volatile BiConsumer<Long, PlaybackFailure> playbackFailureListener;
    private volatile LongPredicate autoplayEnabledChecker = guildId -> true;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final YouTubePlaybackPrecheckService youtubePrecheckService =
            new YouTubePlaybackPrecheckService(() -> {
                MusicConfig.Youtube config = youtubeConfig;
                return config == null ? MusicConfig.Youtube.StrictPrecheck.fromLegacy(null) : config.getStrictPrecheck();
            });

    public MusicPlayerService(Path dataDir,
                              LongToIntFunction historyLimitProvider,
                              LongToIntFunction statsRetentionDaysProvider,
                              LongToIntFunction playlistTrackLimitProvider,
                              MusicConfig globalMusicConfig) {
        this(dataDir,
                historyLimitProvider,
                statsRetentionDaysProvider,
                playlistTrackLimitProvider,
                globalMusicConfig,
                null);
    }

    @SuppressWarnings("deprecation")
    public MusicPlayerService(Path dataDir,
                              LongToIntFunction historyLimitProvider,
                              LongToIntFunction statsRetentionDaysProvider,
                              LongToIntFunction playlistTrackLimitProvider,
                              MusicConfig globalMusicConfig,
                              Path sqliteDbPath) {
        this(dataDir,
                historyLimitProvider,
                statsRetentionDaysProvider,
                playlistTrackLimitProvider,
                globalMusicConfig,
                sqliteDbPath,
                SpotifyPlaylistInspector.noOp());
    }

    @SuppressWarnings("deprecation")
    public MusicPlayerService(Path dataDir,
                              LongToIntFunction historyLimitProvider,
                              LongToIntFunction statsRetentionDaysProvider,
                              LongToIntFunction playlistTrackLimitProvider,
                              MusicConfig globalMusicConfig,
                              Path sqliteDbPath,
                              SpotifyPlaylistInspector spotifyPlaylistInspector) {
        this(
                dataDir,
                historyLimitProvider,
                statsRetentionDaysProvider,
                playlistTrackLimitProvider,
                globalMusicConfig,
                sqliteDbPath,
                spotifyPlaylistInspector,
                YouTubePlaybackTrackFactory.youtubeSource()
        );
    }

    @SuppressWarnings("deprecation")
    public MusicPlayerService(Path dataDir,
                              LongToIntFunction historyLimitProvider,
                              LongToIntFunction statsRetentionDaysProvider,
                              LongToIntFunction playlistTrackLimitProvider,
                              MusicConfig globalMusicConfig,
                              Path sqliteDbPath,
                              SpotifyPlaylistInspector spotifyPlaylistInspector,
                              YouTubePlaybackTrackFactory youtubePlaybackTrackFactory) {
        this.musicDataService = new MusicDataService(
                dataDir,
                historyLimitProvider,
                statsRetentionDaysProvider,
                playlistTrackLimitProvider,
                sqliteDbPath
        );
        this.spotifyPlaylistInspector = spotifyPlaylistInspector == null
                ? SpotifyPlaylistInspector.noOp()
                : spotifyPlaylistInspector;
        this.youtubePlaybackTrackFactory = youtubePlaybackTrackFactory == null
                ? YouTubePlaybackTrackFactory.youtubeSource()
                : youtubePlaybackTrackFactory;
        applyGlobalMusicConfig(globalMusicConfig == null ? MusicConfig.defaultValues() : globalMusicConfig);
        MusicConfig.Audio.Recovery recoveryConfig = audioConfig.getRecovery();
        this.trackRecoveryService = new TrackRecoveryService(
                recoveryConfig.isEnabled(),
                recoveryConfig.getMaxStuckRetries(),
                recoveryConfig.getResumeRewindMillis()
        );
        playerManager = new DefaultAudioPlayerManager();
        playerManager.setTrackStuckThreshold(recoveryConfig.getStuckThresholdMillis());
        this.bilibiliSourceManager = new BilibiliAudioSourceManager();
        updateBilibiliPlaylistLimit();
        playerManager.registerSourceManager(bilibiliSourceManager);
        LOGGER.info("[NoRule] Bilibili audio source registered.");
        YoutubeAuthRuntime youtubeAuth = configureYouTubePoToken(resolveYoutubeAuthentication());
        YoutubeAudioSourceManager youtubeSourceManager = createYoutubeSourceManager(youtubeAuth.mode());
        youtubeAuth = configureYouTubeOauth(youtubeSourceManager, youtubeAuth);
        this.effectiveYoutubeAuthMode = youtubeAuth.mode();
        playerManager.registerSourceManager(youtubeSourceManager);
        this.spotifySourceEnabled = registerSpotifySourceIfConfigured();
        AudioSourceManagers.registerLocalSource(playerManager);
        AudioSourceManagers.registerRemoteSources(playerManager,
                com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class,
                HttpAudioSourceManager.class);
        this.directHttpSourceEnabled = registerDirectHttpSourceIfConfigured();
    }

    public void replaceGuildLimits(LongToIntFunction historyLimitProvider,
                                   LongToIntFunction statsRetentionDaysProvider,
                                   LongToIntFunction playlistTrackLimitProvider) {
        musicDataService.replaceGuildLimits(historyLimitProvider, statsRetentionDaysProvider, playlistTrackLimitProvider);
    }

    public void replaceGlobalMusicConfig(MusicConfig globalMusicConfig) {
        applyGlobalMusicConfig(globalMusicConfig == null ? MusicConfig.defaultValues() : globalMusicConfig);
        MusicConfig.Audio.Recovery recoveryConfig = audioConfig.getRecovery();
        trackRecoveryService.updateConfig(
                recoveryConfig.isEnabled(),
                recoveryConfig.getMaxStuckRetries(),
                recoveryConfig.getResumeRewindMillis()
        );
        playerManager.setTrackStuckThreshold(recoveryConfig.getStuckThresholdMillis());
        updateBilibiliPlaylistLimit();
    }

    private void updateBilibiliPlaylistLimit() {
        bilibiliSourceManager.setPlaylistPageCount(Math.max(1, Math.ceilDiv(playlistTrackLimit, 100)));
    }

    public void cleanupTransientCaches(long nowMillis) {
        long now = nowMillis <= 0L ? System.currentTimeMillis() : nowMillis;
        spotifyRateLimitGuildCooldownUntil.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= now);
        spotifyRateLimitUserCooldownUntil.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= now);
        spotifyPlaylistCooldownByGuild.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= now);
        youtubePlaylistCache.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().expiresAtMs <= now);
        youtubePrecheckService.cleanupExpired(Instant.ofEpochMilli(now));
        musicDataService.cleanupTransientCaches();
    }

    private void applyGlobalMusicConfig(MusicConfig config) {
        this.youtubeConfig = config.getYoutube();
        this.oauthConfig = config.getOauth();
        this.cipherConfig = config.getCipher();
        this.spotifyConfig = config.getSpotify();
        this.audioConfig = config.getAudio();
        this.directHttpValidator = new AudioUrlSafetyValidator(
                Set.copyOf(this.audioConfig.getDirectHttp().getAllowedHosts()),
                AudioUrlSafetyValidator.systemResolver()
        );
        this.playlistTrackLimit = Math.max(1, config.getPlaylistTrackLimit());
        this.spotifyPlaylistMaxTracks = Math.max(1, this.spotifyConfig.getPlaylistMaxTracks());
        this.spotifyPlaylistLoadCooldownMs = Math.max(0L, this.spotifyConfig.getPlaylistLoadCooldownSeconds()) * 1000L;
    }

    private boolean registerDirectHttpSourceIfConfigured() {
        MusicConfig.Audio.DirectHttp config = audioConfig.getDirectHttp();
        if (!config.isEnabled()) {
            LOGGER.info("[NoRule] Direct HTTP audio source disabled.");
            return false;
        }
        if (config.getAllowedHosts().isEmpty()) {
            LOGGER.warn("[NoRule] Direct HTTP audio source requested but allowedHosts is empty; source remains disabled.");
            return false;
        }
        HttpAudioSourceManager sourceManager = new HttpAudioSourceManager();
        sourceManager.configureRequests(existing -> org.apache.http.client.config.RequestConfig.copy(existing)
                .setConnectTimeout(config.getConnectTimeoutMillis())
                .setConnectionRequestTimeout(config.getConnectTimeoutMillis())
                .setSocketTimeout(config.getReadTimeoutMillis())
                .setMaxRedirects(config.getMaxRedirects())
                .build());
        sourceManager.configureBuilder(builder -> builder
                .setDnsResolver(this::resolveSafeDirectHttpHost)
                .setRedirectStrategy(new DefaultRedirectStrategy() {
                    @Override
                    public URI getLocationURI(org.apache.http.HttpRequest request,
                                              org.apache.http.HttpResponse response,
                                              org.apache.http.protocol.HttpContext context) throws ProtocolException {
                        URI destination = super.getLocationURI(request, response, context);
                        AudioUrlSafetyValidator.Validation validation = directHttpValidator.validateStructure(destination);
                        if (!validation.allowed()) {
                            throw new ProtocolException("Unsafe audio redirect blocked: " + validation.status());
                        }
                        return destination;
                    }
                }));
        playerManager.registerSourceManager(sourceManager);
        LOGGER.info("[NoRule] Direct HTTP audio source enabled for {} allowlisted hosts.", config.getAllowedHosts().size());
        return true;
    }

    private InetAddress[] resolveSafeDirectHttpHost(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        AudioUrlSafetyValidator.Validation validation = directHttpValidator.validateResolvedHost(host, Arrays.asList(addresses));
        if (!validation.allowed()) {
            throw new UnknownHostException("Unsafe audio destination blocked: " + validation.status());
        }
        return addresses;
    }

    private boolean registerSpotifySourceIfConfigured() {
        if (!isSpotifySourceInitializationEnabled()) {
            System.out.println("[NoRule] Spotify source initialization disabled.");
            return false;
        }
        String clientId = firstNonBlank(System.getenv("SPOTIFY_CLIENT_ID"), spotifyConfig.getClientId());
        String clientSecret = firstNonBlank(System.getenv("SPOTIFY_CLIENT_SECRET"), spotifyConfig.getClientSecret());
        String spDc = firstNonBlank(System.getenv("SPOTIFY_SP_DC"), spotifyConfig.getSpDc());
        System.out.println("[NoRule] Spotify credentials loaded: clientId="
                + (clientId != null && !clientId.isBlank())
                + ", clientSecret="
                + (clientSecret != null && !clientSecret.isBlank())
                + ", spDc="
                + (spDc != null && !spDc.isBlank()));
        if (clientId == null || clientSecret == null) {
            System.out.println("[NoRule] Spotify source is enabled but missing clientId/clientSecret. Falling back to oEmbed resolver.");
            return false;
        }
        try {
            String countryCode = firstNonBlank(System.getenv("SPOTIFY_COUNTRY_CODE"), spotifyConfig.getCountryCode());
            if (countryCode == null) {
                countryCode = "TW";
            }
            String[] providers = new String[] {"ytsearch:\"%ISRC%\"", "ytsearch:%QUERY%"};
            Class<?> resolverClass = Class.forName("com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver");
            Class<?> resolverType = Class.forName("com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver");
            Object resolver = resolverClass.getConstructor(String[].class).newInstance((Object) providers);
            Class<?> sourceClass = Class.forName("com.github.topi314.lavasrc.spotify.SpotifySourceManager");
            boolean preferAnonymousToken = getBooleanEnvOverride("SPOTIFY_PREFER_ANONYMOUS_TOKEN", spotifyConfig.isPreferAnonymousToken());
            String customTokenEndpoint = normalizeCustomTokenEndpoint(
                    firstNonBlank(System.getenv("SPOTIFY_CUSTOM_TOKEN_ENDPOINT"))
            );
            Object source = createSpotifySourceManager(
                    sourceClass,
                    resolverType,
                    resolver,
                    clientId,
                    clientSecret,
                    spDc == null ? "" : spDc,
                    countryCode,
                    preferAnonymousToken,
                    customTokenEndpoint
            );
            applySpotifyOptions(sourceClass, source);
            playerManager.registerSourceManager((AudioSourceManager) source);
            System.out.println("[NoRule] LavaSrc Spotify source registered.");
            return true;
        } catch (Exception ex) {
            System.out.println("[NoRule] Failed to initialize LavaSrc Spotify source, fallback enabled: " + ex.getMessage());
            return false;
        }
    }

    private String normalizeCustomTokenEndpoint(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        String value = endpoint.trim();
        if (value.isBlank()) {
            return null;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        System.out.println("[NoRule] Ignoring invalid Spotify customTokenEndpoint (must start with http/https).");
        return null;
    }

    private void applySpotifyOptions(Class<?> sourceClass, Object source) {
        tryInvokeBooleanSetter(sourceClass, source, "setPreferAnonymousToken",
                getBooleanEnvOverride("SPOTIFY_PREFER_ANONYMOUS_TOKEN", spotifyConfig.isPreferAnonymousToken()));
        String customEndpoint = firstNonBlank(System.getenv("SPOTIFY_CUSTOM_TOKEN_ENDPOINT"));
        if (customEndpoint != null) {
            tryInvokeStringSetter(sourceClass, source, "setCustomTokenEndpoint", customEndpoint);
        }
    }

    private Object createSpotifySourceManager(Class<?> sourceClass,
                                              Class<?> resolverType,
                                              Object resolver,
                                              String clientId,
                                              String clientSecret,
                                              String spDc,
                                              String countryCode,
                                              boolean preferAnonymousToken,
                                              String customTokenEndpoint) throws Exception {
        Function<Void, AudioPlayerManager> managerFunction = ignored -> playerManager;
        if (customTokenEndpoint != null && !customTokenEndpoint.isBlank()) {
            try {
                return sourceClass
                        .getConstructor(String.class, String.class, boolean.class, String.class, String.class, String.class, Function.class, resolverType)
                        .newInstance(clientId, clientSecret, preferAnonymousToken, customTokenEndpoint, spDc, countryCode,
                                managerFunction, resolver);
            } catch (NoSuchMethodException ignored) {
            }
        }
        try {
            return sourceClass
                    .getConstructor(String.class, String.class, boolean.class, String.class, String.class, Function.class, resolverType)
                    .newInstance(clientId, clientSecret, preferAnonymousToken, spDc, countryCode, managerFunction, resolver);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Supplier<AudioPlayerManager> managerSupplier = () -> playerManager;
            return sourceClass
                    .getConstructor(String.class, String.class, boolean.class, String.class, String.class, Supplier.class, resolverType)
                    .newInstance(clientId, clientSecret, preferAnonymousToken, spDc, countryCode, managerSupplier, resolver);
        } catch (NoSuchMethodException ignored) {
        }
        try {
            return sourceClass
                    .getConstructor(String.class, String.class, String.class, String.class, Function.class, resolverType)
                    .newInstance(clientId, clientSecret, spDc, countryCode, managerFunction, resolver);
        } catch (NoSuchMethodException ignored) {
            Supplier<AudioPlayerManager> managerSupplier = () -> playerManager;
            return sourceClass
                    .getConstructor(String.class, String.class, String.class, String.class, Supplier.class, resolverType)
                    .newInstance(clientId, clientSecret, spDc, countryCode, managerSupplier, resolver);
        }
    }

    private boolean getBooleanEnvOverride(String envName, boolean fallback) {
        String value = firstNonBlank(System.getenv(envName));
        return value == null ? fallback : isTruthy(value);
    }

    private boolean isSpotifySourceInitializationEnabled() {
        return getBooleanEnvOverride("SPOTIFY_ENABLED", spotifyConfig.isEnabled());
    }

    private void tryInvokeBooleanSetter(Class<?> type, Object instance, String methodName, boolean value) {
        try {
            type.getMethod(methodName, boolean.class).invoke(instance, value);
        } catch (Exception ignored) {
        }
    }

    private void tryInvokeStringSetter(Class<?> type, Object instance, String methodName, String value) {
        try {
            type.getMethod(methodName, String.class).invoke(instance, value);
        } catch (Exception ignored) {
        }
    }

    private YoutubeAudioSourceManager createYoutubeSourceManager(MusicConfig.Youtube.AuthMode authMode) {
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
        boolean remoteCipherEnabled = isYouTubeCipherEnabled();
        String remoteCipherUrl = remoteCipherEnabled
                ? firstNonBlank(
                System.getenv("YOUTUBE_CIPHER_SERVER"),
                System.getenv("YOUTUBE_REMOTE_CIPHER_URL"),
                cipherConfig.getServer()
        )
                : null;
        dev.lavalink.youtube.clients.skeleton.Client[] clientArray =
                clients.toArray(dev.lavalink.youtube.clients.skeleton.Client[]::new);
        if (remoteCipherUrl == null) {
            return new YoutubeAudioSourceManager(clientArray);
        }
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
        YoutubeSourceOptions options = new YoutubeSourceOptions()
                .setRemoteCipher(remoteCipherUrl, remoteCipherPassword, remoteCipherUserAgent);
        LOGGER.info("[NoRule] YouTube remote cipher server configured.");
        return new YoutubeAudioSourceManager(options, clientArray);
    }

    private boolean isYouTubeCipherEnabled() {
        return getBooleanEnvOverride("YOUTUBE_CIPHER_ENABLED", cipherConfig.isEnabled());
    }

    private YoutubeAuthRuntime resolveYoutubeAuthentication() {
        String configuredMode = firstNonBlank(System.getenv("YOUTUBE_AUTH_MODE"));
        MusicConfig.Youtube.AuthMode mode = configuredMode == null
                ? (getBooleanEnvOverride("YOUTUBE_OAUTH_ENABLED", oauthConfig.isEnabled())
                        ? MusicConfig.Youtube.AuthMode.OAUTH
                        : MusicConfig.Youtube.AuthMode.NONE)
                : parseYoutubeAuthMode(configuredMode);
        boolean strict = getBooleanEnvOverride("YOUTUBE_STRICT_AUTH_CONFIG", false);
        String poToken = firstNonBlank(
                System.getenv("YOUTUBE_PO_TOKEN"),
                System.getenv("YOUTUBE_POTOKEN")
        );
        String visitorData = firstNonBlank(
                System.getenv("YOUTUBE_VISITOR_DATA"),
                System.getenv("YOUTUBE_VISITORDATA")
        );
        String oauthRefreshToken = firstNonBlank(
                System.getenv("YOUTUBE_OAUTH_REFRESH_TOKEN"),
                System.getenv("YOUTUBE_REFRESH_TOKEN"),
                oauthConfig.getRefreshToken()
        );
        if (mode == MusicConfig.Youtube.AuthMode.POT && (poToken == null || visitorData == null)) {
            return invalidYoutubeAuthentication(
                    strict,
                    "YouTube POT authentication disabled: required credentials are missing."
            );
        }
        if (mode == MusicConfig.Youtube.AuthMode.OAUTH && oauthRefreshToken == null) {
            return invalidYoutubeAuthentication(
                    strict,
                    "YouTube OAuth authentication disabled: refresh token is missing."
            );
        }
        YoutubeAuthRuntime runtime = new YoutubeAuthRuntime(mode, strict, poToken, visitorData, oauthRefreshToken);
        logYoutubeAuthentication(runtime);
        return runtime;
    }

    private YoutubeAuthRuntime configureYouTubePoToken(YoutubeAuthRuntime auth) {
        if (auth.mode() != MusicConfig.Youtube.AuthMode.POT) {
            return auth;
        }
        try {
            Web.setPoTokenAndVisitorData(auth.poToken(), auth.visitorData());
            return auth;
        } catch (RuntimeException failure) {
            if (auth.strict()) {
                throw new IllegalStateException("YouTube POT authentication initialization failed.", failure);
            }
            LOGGER.warn("[NoRule] YouTube POT authentication disabled: initialization failed.");
            LOGGER.debug("YouTube POT initialization failure", failure);
            YoutubeAuthRuntime fallback = YoutubeAuthRuntime.none(false);
            logYoutubeAuthentication(fallback);
            return fallback;
        }
    }

    private YoutubeAuthRuntime configureYouTubeOauth(YoutubeAudioSourceManager youtubeSourceManager,
                                                     YoutubeAuthRuntime auth) {
        if (auth.mode() != MusicConfig.Youtube.AuthMode.OAUTH) {
            return auth;
        }
        try {
            youtubeSourceManager.useOauth2(auth.oauthRefreshToken(), true);
            return auth;
        } catch (RuntimeException failure) {
            if (auth.strict()) {
                throw new IllegalStateException("YouTube OAuth authentication initialization failed.", failure);
            }
            LOGGER.warn("[NoRule] YouTube OAuth authentication disabled: initialization failed.");
            LOGGER.debug("YouTube OAuth initialization failure", failure);
            YoutubeAuthRuntime fallback = YoutubeAuthRuntime.none(false);
            logYoutubeAuthentication(fallback);
            return fallback;
        }
    }

    private YoutubeAuthRuntime invalidYoutubeAuthentication(boolean strict, String message) {
        if (strict) {
            throw new IllegalStateException(message);
        }
        LOGGER.warn("[NoRule] {}", message);
        YoutubeAuthRuntime fallback = YoutubeAuthRuntime.none(false);
        logYoutubeAuthentication(fallback);
        return fallback;
    }

    private MusicConfig.Youtube.AuthMode parseYoutubeAuthMode(String value) {
        if (value == null || value.isBlank()) {
            return MusicConfig.Youtube.AuthMode.NONE;
        }
        try {
            return MusicConfig.Youtube.AuthMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            LOGGER.warn("[NoRule] Invalid YouTube authentication mode; falling back to NONE.");
            return MusicConfig.Youtube.AuthMode.NONE;
        }
    }

    private void logYoutubeAuthentication(YoutubeAuthRuntime auth) {
        LOGGER.info(
                "[NoRule] YouTube authentication: mode={} tokenConfigured={} visitorDataConfigured={}",
                auth.mode(),
                auth.mode() == MusicConfig.Youtube.AuthMode.POT
                        ? auth.poToken() != null
                        : auth.oauthRefreshToken() != null,
                auth.visitorData() != null
        );
    }

    private String firstNonBlank(String... values) {
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

    private boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    public void reloadData() {
        musicDataService.reloadAll();
        for (Map.Entry<Long, GuildMusicManager> entry : musicManagers.entrySet()) {
            GuildMusicManager manager = entry.getValue();
            if (manager != null) {
                manager.getPlayer().setVolume(musicDataService.getVolume(entry.getKey()));
                applyPlaybackSpeedFilter(manager, musicDataService.getPlaybackSpeed(entry.getKey()));
            }
        }
    }

    public void reloadGuildData(long guildId) {
        musicDataService.reload(guildId);
    }

    public int clearPlayHistoryByRetentionMillis(long retentionMillis) {
        return musicDataService.clearPlayHistoryByRetentionMillis(retentionMillis);
    }

    public GuildMusicManager getGuildMusicManager(Guild guild) {
        return musicManagers.computeIfAbsent(guild.getIdLong(), id -> {
            GuildMusicManager manager = new GuildMusicManager(
                    playerManager,
                    () -> notifyStateChanged(id),
                    endedTrack -> handleQueueExhausted(id, endedTrack),
                    startedTrack -> handleTrackStarted(id, startedTrack),
                    finishedTrack -> handleTrackFinished(id, finishedTrack),
                    (track, exception) -> handleTrackException(id, track, exception),
                    (track, thresholdMs) -> handleTrackStuck(id, track, thresholdMs),
                    () -> guild.getAudioManager().getConnectedChannel() != null
            );
            manager.getPlayer().setVolume(musicDataService.getVolume(id));
            applyPlaybackSpeedFilter(manager, musicDataService.getPlaybackSpeed(id));
            guild.getAudioManager().setSendingHandler(manager.getSendHandler());
            return manager;
        });
    }

    public void setAutoplayEnabledChecker(LongPredicate autoplayEnabledChecker) {
        this.autoplayEnabledChecker = autoplayEnabledChecker == null ? (id -> true) : autoplayEnabledChecker;
    }

    public void setPlaybackFailureListener(BiConsumer<Long, PlaybackFailure> playbackFailureListener) {
        this.playbackFailureListener = playbackFailureListener;
    }

    public void setGuildStateListener(long guildId, Runnable listener) {
        if (listener == null) {
            guildStateListeners.remove(guildId);
        } else {
            guildStateListeners.put(guildId, listener);
        }
    }

    public void rememberCommandChannel(long guildId, long channelId) {
        lastCommandChannelByGuild.put(guildId, channelId);
    }

    public Long getLastCommandChannelId(long guildId) {
        return lastCommandChannelByGuild.get(guildId);
    }

    public String getAutoplayNotice(long guildId) {
        return autoplayNoticeByGuild.get(guildId);
    }

    public void clearAutoplayNotice(long guildId) {
        autoplayNoticeByGuild.remove(guildId);
    }

    public void joinChannel(Guild guild, AudioChannel channel) {
        guild.getAudioManager().openAudioConnection(channel);
        notifyStateChanged(guild.getIdLong());
    }

    public void leaveChannel(Guild guild) {
        GuildMusicManager manager = musicManagers.get(guild.getIdLong());
        trackRecoveryService.cancel(guild.getIdLong());
        if (manager != null) {
            manager.getScheduler().invalidatePlaybackGeneration();
        }
        musicDataService.resetPlaybackSpeed(guild.getIdLong());
        guild.getAudioManager().closeAudioConnection();
        notifyStateChanged(guild.getIdLong());
    }

    public void loadAndPlay(Guild guild, MessageChannel channel, String input) {
        loadAndPlay(guild, message -> channel.sendMessage(message).queue(), input, null, null);
    }

    public void loadAndPlay(Guild guild, Consumer<String> messageSender, String input) {
        loadAndPlay(guild, messageSender, input, null, null);
    }

    public void loadAndPlay(Guild guild, Consumer<String> messageSender, String input, Long requesterId, String requesterName) {
        GuildMusicManager guildMusicManager = getGuildMusicManager(guild);
        clearAutoplayNotice(guild.getIdLong());
        resumeIfPaused(guildMusicManager.getPlayer(), guild.getIdLong());
        String normalizedInput = normalizeRepeatedSpotifyUrl(input);
        AudioInputClassifier.Classification classification = inputClassifier.classify(normalizedInput);
        ResolvedInput resolvedInput = resolveInput(normalizedInput, classification);
        String identifier = resolvedInput.isUrl ? resolvedInput.identifier : YT_SEARCH_PREFIX + resolvedInput.identifier;
        load(guild.getIdLong(), guildMusicManager, messageSender, normalizedInput, identifier,
                resolvedInput.sourceLabel, true, requesterId, requesterName, 0);
    }

    public void searchTopTracks(String query, int limit, Consumer<List<AudioTrack>> onSuccess, Consumer<String> onError) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            onSuccess.accept(List.of());
            return;
        }
        String identifier = YT_SEARCH_PREFIX + trimmed;
        playerManager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                onSuccess.accept(List.of(track));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    onSuccess.accept(List.of());
                    return;
                }
                int max = Math.max(1, Math.min(10, limit));
                onSuccess.accept(playlist.getTracks().stream().limit(max).toList());
            }

            @Override
            public void noMatches() {
                onSuccess.accept(List.of());
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                YoutubeFailureReport youtubeFailure = YOUTUBE_FAILURE_CLASSIFIER.classify(exception);
                logYoutubeFailure("search-load", 0L, null, "-", youtubeFailure, exception);
                onError.accept(youtubeFailure.errorKey());
            }
        });
    }

    public void queueTrackByIdentifier(Guild guild, String identifier, String sourceLabel, Consumer<String> messageSender) {
        queueTrackByIdentifier(guild, identifier, sourceLabel, messageSender, null, null);
    }

    public void queueTrackByIdentifier(Guild guild,
                                       String identifier,
                                       String sourceLabel,
                                       Consumer<String> messageSender,
                                       Long requesterId,
                                       String requesterName) {
        GuildMusicManager guildMusicManager = getGuildMusicManager(guild);
        clearAutoplayNotice(guild.getIdLong());
        resumeIfPaused(guildMusicManager.getPlayer(), guild.getIdLong());
        load(guild.getIdLong(), guildMusicManager, messageSender, identifier, identifier, sourceLabel, false, requesterId, requesterName, 0);
    }

    public boolean isUrlLikeInput(String input) {
        return inputClassifier.classify(input).isUrlLike();
    }

    private void load(long guildId,
                      GuildMusicManager guildMusicManager,
                      Consumer<String> messageSender,
                      String userInput,
                      String identifier,
                      String sourceLabel,
                      boolean allowFallback,
                      Long requesterId,
                      String requesterName,
                      int spotifyRateLimitRetryAttempt) {
        load(guildId, guildMusicManager, messageSender, userInput, identifier, sourceLabel, allowFallback,
                requesterId, requesterName, spotifyRateLimitRetryAttempt, null);
    }

    private void load(long guildId,
                      GuildMusicManager guildMusicManager,
                      Consumer<String> messageSender,
                      String userInput,
                      String identifier,
                      String sourceLabel,
                      boolean allowFallback,
                      Long requesterId,
                      String requesterName,
                      int spotifyRateLimitRetryAttempt,
                      SpotifyPlaylistInspector.Inspection spotifyPlaylistInspection) {
        String inputError = validateInputForLoad(userInput);
        if (inputError != null) {
            messageSender.accept("LOAD_FAILED:" + inputError);
            return;
        }
        if (isSpotifyPlaylistUrl(userInput) && spotifyPlaylistLoadCooldownMs > 0L) {
            long now = System.currentTimeMillis();
            Long cooldownUntil = spotifyPlaylistCooldownByGuild.get(guildId);
            if (cooldownUntil != null && cooldownUntil > now) {
                messageSender.accept("LOAD_FAILED:" + SPOTIFY_PLAYLIST_COOLDOWN_ERROR_KEY);
                return;
            }
        }
        if (looksLikeSpotifyUrl(userInput)) {
            long now = System.currentTimeMillis();
            Long guildLimitedUntil = spotifyRateLimitGuildCooldownUntil.get(guildId);
            if (guildLimitedUntil != null && guildLimitedUntil > now) {
                messageSender.accept("LOAD_FAILED:" + SPOTIFY_RATE_LIMIT_ERROR_KEY);
                return;
            }
            if (requesterId != null) {
                Long userLimitedUntil = spotifyRateLimitUserCooldownUntil.get(requesterId);
                if (userLimitedUntil != null && userLimitedUntil > now) {
                    messageSender.accept("LOAD_FAILED:" + SPOTIFY_RATE_LIMIT_ERROR_KEY);
                    return;
                }
            }
        }
        if (spotifySourceEnabled && isSpotifyPlaylistUrl(userInput) && spotifyPlaylistInspection == null) {
            inspectSpotifyPlaylistThenLoad(
                    guildId,
                    guildMusicManager,
                    messageSender,
                    userInput,
                    identifier,
                    sourceLabel,
                    allowFallback,
                    requesterId,
                    requesterName,
                    spotifyRateLimitRetryAttempt
            );
            return;
        }
        if (isYouTubePlaylistUrl(userInput)) {
            List<AudioTrack> cachedTracks = getCachedYoutubePlaylistTracks(userInput);
            if (!cachedTracks.isEmpty()) {
                List<AudioTrack> limited = cachedTracks.stream().limit(playlistTrackLimit).toList();
                AudioTrack firstQueued = enqueuePlaylistTracksBatched(
                        guildMusicManager, limited, sourceLabel, requesterId, requesterName, userInput, identifier);
                messageSender.accept(firstQueued == null
                        ? "LOAD_FAILED:" + YOUTUBE_PRECHECK_BLOCKED_ERROR_KEY
                        : firstQueued.getInfo().title);
                return;
            }
        }
        playerManager.loadItemOrdered(guildMusicManager, identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                YouTubePlaybackPrecheckResult precheck = precheckTrack(track, sourceLabel);
                if (!precheck.allowsQueue()) {
                    messageSender.accept("LOAD_FAILED:" + mapYouTubePrecheckFailure(precheck));
                    return;
                }
                applyTrackMetadata(track, sourceLabel, requesterId, requesterName, userInput, identifier);
                queuePlaybackTrack(guildMusicManager, track);
                messageSender.accept(track.getInfo().title);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    messageSender.accept("NO_MATCH");
                    return;
                }
                if (isSpotifyPlaylistUrl(userInput) && spotifyPlaylistLoadCooldownMs > 0L) {
                    spotifyPlaylistCooldownByGuild.put(guildId, System.currentTimeMillis() + spotifyPlaylistLoadCooldownMs);
                }
                if (isSpotifyPlaylistUrl(userInput)) {
                    int limit = Math.min(spotifyPlaylistMaxTracks, playlist.getTracks().size());
                    for (int i = 0; i < limit; i++) {
                        AudioTrack track = playlist.getTracks().get(i);
                        applyTrackMetadata(track, sourceLabel, requesterId, requesterName, userInput, identifier);
                        queuePlaybackTrack(guildMusicManager, track);
                    }
                    messageSender.accept(playlist.getTracks().get(0).getInfo().title);
                    return;
                }
                if (isYouTubePlaylistUrl(userInput)) {
                    List<AudioTrack> tracksToQueue = playlist.getTracks().stream()
                            .limit(playlistTrackLimit)
                            .map(AudioTrack::makeClone)
                            .toList();
                    cacheYoutubePlaylistTracks(userInput, tracksToQueue);
                    AudioTrack firstQueued = enqueuePlaylistTracksBatched(
                            guildMusicManager,
                            tracksToQueue,
                            sourceLabel,
                            requesterId,
                            requesterName,
                            userInput,
                            identifier
                    );
                    messageSender.accept(firstQueued == null
                            ? "LOAD_FAILED:" + YOUTUBE_PRECHECK_BLOCKED_ERROR_KEY
                            : firstQueued.getInfo().title);
                    return;
                }
                AudioTrack firstTrack = playlist.getSelectedTrack() != null
                        ? playlist.getSelectedTrack()
                        : playlist.getTracks().get(0);
                YouTubePlaybackPrecheckResult precheck = precheckTrack(firstTrack, sourceLabel);
                if (!precheck.allowsQueue()) {
                    messageSender.accept("LOAD_FAILED:" + mapYouTubePrecheckFailure(precheck));
                    return;
                }
                applyTrackMetadata(firstTrack, sourceLabel, requesterId, requesterName, userInput, identifier);
                queuePlaybackTrack(guildMusicManager, firstTrack);
                messageSender.accept(firstTrack.getInfo().title);
            }

            @Override
            public void noMatches() {
                messageSender.accept("NO_MATCH");
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                if (isYoutubeLoadAttempt(userInput, identifier, sourceLabel, exception)) {
                    YoutubeFailureReport youtubeFailure = YOUTUBE_FAILURE_CLASSIFIER.classify(exception);
                    logYoutubeFailure(
                            "load",
                            guildId,
                            extractYouTubeVideoId(firstNonBlank(userInput, identifier)),
                            "-",
                            youtubeFailure,
                            exception
                    );
                    messageSender.accept("LOAD_FAILED:" + youtubeFailure.errorKey());
                    return;
                }
                AudioLoadFailureClassifier.Category category = resolveLoadFailureCategory(
                        exception,
                        spotifyPlaylistInspection
                );
                logLoadFailureDetails(
                        "queue/load",
                        guildId,
                        userInput,
                        identifier,
                        spotifyPlaylistInspection,
                        category,
                        exception
                );
                if (isSpotifyPlaylistUrl(userInput)
                        && category == AudioLoadFailureClassifier.Category.SPOTIFY_GENERATED_PLAYLIST_UNAVAILABLE) {
                    messageSender.accept("LOAD_FAILED:" + FAILURE_CLASSIFIER.errorKey(category));
                    return;
                }
                if (isSpotifyJamLink(userInput)) {
                    messageSender.accept("LOAD_FAILED:" + SPOTIFY_JAM_UNSUPPORTED_ERROR_KEY);
                    return;
                }
                if (isSpotifyProfileLink(userInput)) {
                    messageSender.accept("LOAD_FAILED:" + SPOTIFY_UNSUPPORTED_LINK_ERROR_KEY);
                    return;
                }
                if (looksLikeSpotifyUrl(userInput)
                        && (category == AudioLoadFailureClassifier.Category.SPOTIFY_AUTH_FAILED
                        || isSpotifyAuthFailure(exception))) {
                    messageSender.accept("LOAD_FAILED:" + SPOTIFY_AUTH_FAILED_ERROR_KEY);
                    return;
                }
                if (looksLikeSpotifyUrl(userInput)
                        && (category == AudioLoadFailureClassifier.Category.SPOTIFY_RATE_LIMITED
                        || isSpotifyRateLimited(exception))) {
                    applySpotifyRateLimitCooldown(guildId, requesterId);
                    messageSender.accept("LOAD_FAILED:" + SPOTIFY_RATE_LIMIT_ERROR_KEY);
                    return;
                }
                if (isSpotifyPlaylistUrl(userInput)
                        && category == AudioLoadFailureClassifier.Category.SPOTIFY_PLAYLIST_EMPTY) {
                    messageSender.accept("LOAD_FAILED:" + SPOTIFY_EMPTY_PLAYLIST_ERROR_KEY);
                    return;
                }
                if (isSpotifyPlaylistUrl(userInput)
                        && (category == AudioLoadFailureClassifier.Category.SPOTIFY_RESTRICTED_OR_PERSONALIZED
                        || isSpotifySecretFailure(exception))) {
                    messageSender.accept("LOAD_FAILED:" + SPOTIFY_RESTRICTED_PLAYLIST_ERROR_KEY);
                    return;
                }
                if (looksLikeSpotifyUrl(userInput)
                        && isSpotifyTimeout(exception)
                        && spotifyRateLimitRetryAttempt < SPOTIFY_TIMEOUT_RETRY_MAX_ATTEMPTS) {
                    int nextAttempt = spotifyRateLimitRetryAttempt + 1;
                    LOGGER.warn(
                            "[NoRule] Spotify load timeout, retrying attempt {}/{} for input={}",
                            nextAttempt,
                            SPOTIFY_TIMEOUT_RETRY_MAX_ATTEMPTS,
                            sanitizeInputForLog(userInput)
                    );
                    load(guildId, guildMusicManager, messageSender, userInput, identifier, sourceLabel,
                            allowFallback, requesterId, requesterName, nextAttempt, spotifyPlaylistInspection);
                    return;
                }
                if (looksLikeSpotifyOrShareUrl(userInput) && isUnknownFileFormat(exception)) {
                    messageSender.accept("LOAD_FAILED:" + SPOTIFY_UNSUPPORTED_LINK_ERROR_KEY);
                    return;
                }
                if (allowFallback && looksLikeYouTubeUrl(userInput)) {
                    String fallbackIdentifier = YT_SEARCH_PREFIX + userInput;
                    load(guildId, guildMusicManager, messageSender, userInput, fallbackIdentifier, sourceLabel, false, requesterId, requesterName, 0);
                    return;
                }
                if (category != AudioLoadFailureClassifier.Category.UNKNOWN
                        && !looksLikeYouTubeUrl(userInput)
                        && !looksLikeSpotifyUrl(userInput)) {
                    messageSender.accept("LOAD_FAILED:" + FAILURE_CLASSIFIER.errorKey(category));
                } else {
                    messageSender.accept("LOAD_FAILED:" + exception.getMessage());
                }
            }
        });
    }

    private void inspectSpotifyPlaylistThenLoad(long guildId,
                                                GuildMusicManager guildMusicManager,
                                                Consumer<String> messageSender,
                                                String userInput,
                                                String identifier,
                                                String sourceLabel,
                                                boolean allowFallback,
                                                Long requesterId,
                                                String requesterName,
                                                int spotifyRateLimitRetryAttempt) {
        try {
            spotifyPlaylistInspector.inspect(userInput, "queue/load guild=" + guildId, spotifyConfig)
                    .whenComplete((inspection, error) -> {
                        if (error != null) {
                            LOGGER.warn(
                                    "[NoRule] Spotify playlist preflight failed unexpectedly: context=queue/load guild={} input={} reason={}",
                                    guildId,
                                    sanitizeInputForLog(userInput),
                                    rootCauseDetails(error)
                            );
                        } else if (handleSpotifyPlaylistInspection(
                                inspection,
                                guildId,
                                requesterId,
                                messageSender
                        )) {
                            return;
                        }
                        load(guildId, guildMusicManager, messageSender, userInput, identifier, sourceLabel,
                                allowFallback, requesterId, requesterName, spotifyRateLimitRetryAttempt,
                                inspection == null ? SpotifyPlaylistInspector.Inspection.unavailable() : inspection);
                    });
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "[NoRule] Spotify playlist preflight could not start: context=queue/load guild={} input={} reason={}",
                    guildId,
                    sanitizeInputForLog(userInput),
                    rootCauseDetails(exception)
            );
            load(guildId, guildMusicManager, messageSender, userInput, identifier, sourceLabel,
                    allowFallback, requesterId, requesterName, spotifyRateLimitRetryAttempt,
                    SpotifyPlaylistInspector.Inspection.unavailable());
        }
    }

    private boolean handleSpotifyPlaylistInspection(SpotifyPlaylistInspector.Inspection inspection,
                                                    long guildId,
                                                    Long requesterId,
                                                    Consumer<String> messageSender) {
        if (inspection == null || inspection.outcome() == SpotifyPlaylistInspector.Outcome.UNAVAILABLE) {
            return false;
        }
        switch (inspection.outcome()) {
            case READABLE -> {
                return false;
            }
            case SPOTIFY_PLAYLIST_EMPTY ->
                    messageSender.accept("LOAD_FAILED:" + SPOTIFY_EMPTY_PLAYLIST_ERROR_KEY);
            case SPOTIFY_RESTRICTED_OR_PERSONALIZED ->
                    messageSender.accept("LOAD_FAILED:" + SPOTIFY_RESTRICTED_PLAYLIST_ERROR_KEY);
            case SPOTIFY_AUTH_FAILED ->
                    messageSender.accept("LOAD_FAILED:" + SPOTIFY_AUTH_FAILED_ERROR_KEY);
            case SPOTIFY_RATE_LIMITED -> {
                applySpotifyRateLimitCooldown(guildId, requesterId);
                messageSender.accept("LOAD_FAILED:" + SPOTIFY_RATE_LIMIT_ERROR_KEY);
            }
            case UNAVAILABLE -> {
                return false;
            }
        }
        return true;
    }

    static AudioLoadFailureClassifier.Category resolveLoadFailureCategory(
            Throwable failure,
            SpotifyPlaylistInspector.Inspection inspection) {
        AudioLoadFailureClassifier.Category throwableCategory = FAILURE_CLASSIFIER.classify(failure);
        if (throwableCategory != AudioLoadFailureClassifier.Category.UNKNOWN) {
            return throwableCategory;
        }
        if (inspection == null || inspection.outcome() == null) {
            return AudioLoadFailureClassifier.Category.UNKNOWN;
        }
        return switch (inspection.outcome()) {
            case SPOTIFY_PLAYLIST_EMPTY -> AudioLoadFailureClassifier.Category.SPOTIFY_PLAYLIST_EMPTY;
            case SPOTIFY_RESTRICTED_OR_PERSONALIZED ->
                    AudioLoadFailureClassifier.Category.SPOTIFY_RESTRICTED_OR_PERSONALIZED;
            case SPOTIFY_AUTH_FAILED -> AudioLoadFailureClassifier.Category.SPOTIFY_AUTH_FAILED;
            case SPOTIFY_RATE_LIMITED -> AudioLoadFailureClassifier.Category.SPOTIFY_RATE_LIMITED;
            case READABLE, UNAVAILABLE -> AudioLoadFailureClassifier.Category.UNKNOWN;
        };
    }

    private void applySpotifyRateLimitCooldown(long guildId, Long requesterId) {
        long cooldownUntil = System.currentTimeMillis() + SPOTIFY_RATE_LIMIT_COOLDOWN_MS;
        spotifyRateLimitGuildCooldownUntil.put(guildId, cooldownUntil);
        if (requesterId != null) {
            spotifyRateLimitUserCooldownUntil.put(requesterId, cooldownUntil);
        }
    }

    private YouTubePlaybackPrecheckResult precheckTrack(AudioTrack track, String sourceLabel) {
        if (track == null || track.getInfo() == null) {
            return youtubePrecheckService.check(null);
        }
        AudioTrackInfo info = track.getInfo();
        YouTubePlaybackPrecheckResult uriResult = youtubePrecheckService.check(info.uri);
        if (uriResult.status() != YouTubePlaybackPrecheckStatus.SKIPPED) {
            return uriResult;
        }
        if ("youtube".equalsIgnoreCase(normalizeSourceLabel(sourceLabel))
                && YouTubePlaybackPrecheckService.isValidVideoId(info.identifier)) {
            return youtubePrecheckService.check(info.identifier);
        }
        return uriResult;
    }

    private String mapYouTubePrecheckFailure(YouTubePlaybackPrecheckResult result) {
        if (result == null || result.status() == null) {
            return YOUTUBE_PRECHECK_UNKNOWN_ERROR_KEY;
        }
        return switch (result.status()) {
            case BLOCKED, PERMANENT_FAILURE, AUTH_REQUIRED, TEMPORARY_FAILURE ->
                    YOUTUBE_PRECHECK_BLOCKED_ERROR_KEY;
            case TIMEOUT -> YOUTUBE_PRECHECK_TIMEOUT_ERROR_KEY;
            case LAVALINK_UNAVAILABLE -> YOUTUBE_PRECHECK_UNAVAILABLE_ERROR_KEY;
            case INVALID_YOUTUBE_ID -> YOUTUBE_PRECHECK_INVALID_ERROR_KEY;
            default -> YOUTUBE_PRECHECK_UNKNOWN_ERROR_KEY;
        };
    }

    private String validateInputForLoad(String input) {
        AudioInputClassifier.Classification classification = inputClassifier.classify(input);
        String errorKey = switch (classification.type()) {
            case SPOTIFY_SHOW -> SPOTIFY_SHOW_UNSUPPORTED_ERROR_KEY;
            case SPOTIFY_EPISODE -> SPOTIFY_EPISODE_UNSUPPORTED_ERROR_KEY;
            case INVALID_URL -> FAILURE_CLASSIFIER.errorKey(AudioLoadFailureClassifier.Category.INVALID_INPUT);
            case UNSUPPORTED_URL -> validateUnsupportedUrl(input, classification);
            case DIRECT_HTTP_AUDIO -> validateDirectHttpInput(classification);
            default -> null;
        };
        if (errorKey != null) {
            LOGGER.warn(
                    "[NoRule] Load rejected: context=queue/load input={} category={}",
                    sanitizeInputForLog(input),
                    errorKey
            );
        }
        return errorKey;
    }

    private String validateUnsupportedUrl(String input, AudioInputClassifier.Classification classification) {
        if (isSpotifyJamLink(input)) {
            return SPOTIFY_JAM_UNSUPPORTED_ERROR_KEY;
        }
        AudioUrlSafetyValidator.Validation boundary = directHttpValidator.validateNetworkBoundary(classification.uri());
        if (!boundary.allowed()) {
            LOGGER.warn(
                    "[NoRule] URL safety rejection: input={} safetyStatus={}",
                    sanitizeInputForLog(input),
                    boundary.status()
            );
        }
        return FAILURE_CLASSIFIER.errorKey(AudioLoadFailureClassifier.Category.UNSUPPORTED_SOURCE);
    }

    private String validateDirectHttpInput(AudioInputClassifier.Classification classification) {
        MusicConfig.Audio.DirectHttp directHttp = audioConfig.getDirectHttp();
        if (!directHttp.isEnabled() || !directHttpSourceEnabled) {
            return DIRECT_HTTP_DISABLED_ERROR_KEY;
        }
        AudioUrlSafetyValidator.Validation validation = directHttpValidator.validate(classification.uri());
        if (validation.allowed()) {
            return null;
        }
        LOGGER.warn(
                "[NoRule] Direct HTTP safety rejection: input={} safetyStatus={}",
                sanitizeInputForLog(classification.normalizedInput()),
                validation.status()
        );
        if (validation.status() == AudioUrlSafetyValidator.Status.DNS_FAILURE) {
            return FAILURE_CLASSIFIER.errorKey(AudioLoadFailureClassifier.Category.DNS_FAILURE);
        }
        if (validation.status() == AudioUrlSafetyValidator.Status.INVALID_URL
                || validation.status() == AudioUrlSafetyValidator.Status.INVALID_HOST
                || validation.status() == AudioUrlSafetyValidator.Status.UNSUPPORTED_SCHEME) {
            return FAILURE_CLASSIFIER.errorKey(AudioLoadFailureClassifier.Category.INVALID_INPUT);
        }
        return FAILURE_CLASSIFIER.errorKey(AudioLoadFailureClassifier.Category.UNSUPPORTED_SOURCE);
    }

    private String sanitizeInputForLog(String input) {
        if (input == null || input.isBlank()) {
            return "-";
        }
        String sanitized = input.trim()
                .replaceAll("(?i)(access_token|token|key|signature|sig|auth|authorization)=([^&\\s]*)", "$1=<redacted>")
                .replaceAll("(?i)([?&])(si|utm_source)=([^&\\s]*)", "$1$2=<removed>");
        return sanitized.length() <= 300 ? sanitized : sanitized.substring(0, 300);
    }

    private void logLoadFailureDetails(String context,
                                       long guildId,
                                       String userInput,
                                       String identifier,
                                       SpotifyPlaylistInspector.Inspection inspection,
                                       AudioLoadFailureClassifier.Category category,
                                       FriendlyException exception) {
        String inspectionOutcome = inspection == null || inspection.outcome() == null
                ? "-"
                : inspection.outcome().name();
        String inspectionClassification = inspection == null || inspection.classification() == null
                ? "-"
                : inspection.classification().name();
        int inspectionStatus = inspection == null ? 0 : inspection.statusCode();
        if (exception == null) {
            LOGGER.warn(
                    "[NoRule] Load failed: context={} guildId={} input={} identifier={} playlistId={} "
                            + "inspectionOutcome={} inspectionClassification={} inspectionStatus={} category={} message=-",
                    context,
                    guildId,
                    sanitizeInputForLog(userInput),
                    sanitizeInputForLog(identifier),
                    spotifyPlaylistId(userInput),
                    inspectionOutcome,
                    inspectionClassification,
                    inspectionStatus,
                    category
            );
            return;
        }
        String root = safeExceptionMessage(exception);
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                root = cause.getClass().getSimpleName() + ": " + safeExceptionMessage(cause);
            } else {
                root = cause.getClass().getSimpleName();
            }
            cause = cause.getCause();
        }
        String prefix = category == AudioLoadFailureClassifier.Category.SPOTIFY_GENERATED_PLAYLIST_UNAVAILABLE
                ? "[NoRule] Load rejected: context="
                : "[NoRule] Load failed: context=";
        String summary = prefix + context
                + " guildId=" + guildId
                + " input=" + sanitizeInputForLog(userInput)
                + " identifier=" + sanitizeInputForLog(identifier)
                + " playlistId=" + spotifyPlaylistId(userInput)
                + " inspectionOutcome=" + inspectionOutcome
                + " inspectionClassification=" + inspectionClassification
                + " inspectionStatus=" + inspectionStatus
                + " category=" + category
                + " message=" + safeExceptionMessage(exception)
                + " rootCause=" + root;
        if (FAILURE_CLASSIFIER.isExpectedInputFailure(category)) {
            LOGGER.warn(summary);
        } else {
            LOGGER.error(summary, exception);
        }
    }

    private String spotifyPlaylistId(String input) {
        if (input == null || input.isBlank()) {
            return "-";
        }
        Matcher matcher = SPOTIFY_RESOURCE_PATTERN.matcher(input.trim());
        if (!matcher.find() || !"playlist".equalsIgnoreCase(matcher.group(1))) {
            return "-";
        }
        return sanitizeInputForLog(matcher.group(2));
    }

    private String rootCauseDetails(Throwable exception) {
        if (exception == null) {
            return "-";
        }
        String root = exception.getClass().getSimpleName() + ": " + safeExceptionMessage(exception);
        Throwable cause = exception.getCause();
        while (cause != null) {
            root = cause.getClass().getSimpleName() + ": " + safeExceptionMessage(cause);
            cause = cause.getCause();
        }
        return root;
    }

    private String safeExceptionMessage(Throwable exception) {
        String message = exception == null ? null : exception.getMessage();
        return message == null || message.isBlank() ? "-" : sanitizeInputForLog(message);
    }

    private boolean isSpotifyRateLimited(FriendlyException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("too many requests")
                        || lower.contains("response code from channel info is 429")
                        || lower.contains(" 429")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isSpotifySecretFailure(FriendlyException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("failed to retrieve secret")
                        || lower.contains("no secret found")
                        || lower.contains("no secret array found")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isSpotifyAuthFailure(FriendlyException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("unauthorized")
                        || lower.contains("invalid_client")
                        || lower.contains("response code from channel info is 401")
                        || lower.contains(" 401")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isSpotifyTimeout(FriendlyException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("read timed out") || lower.contains("connect timed out")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isUnknownFileFormat(FriendlyException exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("unknown file format")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isSpotifyPlaylistUrl(String text) {
        return inputClassifier.classify(text).type() == AudioInputClassifier.AudioInputType.SPOTIFY_PLAYLIST;
    }

    public void skip(Guild guild) {
        trackRecoveryService.cancel(guild.getIdLong());
        getGuildMusicManager(guild).getScheduler().nextTrack();
    }

    public void stop(Guild guild) {
        GuildMusicManager manager = getGuildMusicManager(guild);
        trackRecoveryService.cancel(guild.getIdLong());
        manager.getScheduler().clear();
        manager.getPlayer().stopTrack();
        clearAutoplayNotice(guild.getIdLong());
        resumeIfPaused(manager.getPlayer(), guild.getIdLong());
        notifyStateChanged(guild.getIdLong());
    }

    public void setRepeatMode(Guild guild, String mode) {
        getGuildMusicManager(guild).getScheduler().setRepeatMode(mode);
    }

    public String getRepeatMode(Guild guild) {
        return getGuildMusicManager(guild).getScheduler().getRepeatModeName();
    }

    public String getRepeatModeByGuildId(long guildId) {
        GuildMusicManager manager = musicManagers.get(guildId);
        if (manager == null) {
            return "OFF";
        }
        return manager.getScheduler().getRepeatModeName();
    }

    public String getCurrentTitle(Guild guild) {
        AudioTrack track = getCurrentTrack(guild);
        return track == null ? null : track.getInfo().title;
    }

    public String getCurrentSource(Guild guild) {
        AudioTrack track = getCurrentTrack(guild);
        if (track == null) {
            return "-";
        }
        TrackLoadContext context = readContext(track);
        return context == null ? "youtube" : context.sourceName();
    }

    public String getCurrentRequesterDisplay(Guild guild) {
        AudioTrack track = getCurrentTrack(guild);
        if (track == null) {
            return "-";
        }
        TrackLoadContext context = readContext(track);
        if (context == null) {
            return "-";
        }
        if (context.requesterId() != null) {
            return "<@" + context.requesterId() + ">";
        }
        return context.requesterName().isBlank() ? "-" : context.requesterName();
    }

    public long getCurrentPositionMillis(Guild guild) {
        AudioTrack track = getCurrentTrack(guild);
        return track == null ? 0L : Math.max(0L, track.getPosition());
    }

    public long getCurrentDurationMillis(Guild guild) {
        AudioTrack track = getCurrentTrack(guild);
        return track == null ? 0L : Math.max(0L, track.getDuration());
    }

    public String getCurrentArtworkUrl(Guild guild) {
        AudioTrack track = getCurrentTrack(guild);
        return resolveArtworkUrl(track);
    }

    public AudioTrack getCurrentTrack(Guild guild) {
        return getGuildMusicManager(guild).getPlayer().getPlayingTrack();
    }

    public boolean togglePause(Guild guild) {
        AudioPlayer player = getGuildMusicManager(guild).getPlayer();
        boolean target = !player.isPaused();
        player.setPaused(target);
        notifyStateChanged(guild.getIdLong());
        return target;
    }

    public boolean isPaused(Guild guild) {
        return getGuildMusicManager(guild).getPlayer().isPaused();
    }

    public int getActivePlaybackGuildCount() {
        return (int) musicManagers.values().stream()
                .filter(manager -> manager != null
                        && manager.getPlayer().getPlayingTrack() != null
                        && !manager.getPlayer().isPaused())
                .count();
    }

    public List<AudioTrack> getQueueSnapshot(Guild guild) {
        return getGuildMusicManager(guild).getScheduler().snapshotQueue();
    }

    public int shuffleQueue(Guild guild) {
        return getGuildMusicManager(guild).getScheduler().shuffleQueue();
    }

    public int getVolume(Guild guild) {
        return getGuildMusicManager(guild).getPlayer().getVolume();
    }

    public int setVolume(Guild guild, int volume) {
        int applied = musicDataService.setVolume(guild.getIdLong(), volume);
        getGuildMusicManager(guild).getPlayer().setVolume(applied);
        notifyStateChanged(guild.getIdLong());
        return applied;
    }

    public double getPlaybackSpeed(Guild guild) {
        return musicDataService.getPlaybackSpeed(guild.getIdLong());
    }

    public double setPlaybackSpeed(Guild guild, double speed) {
        double applied = musicDataService.setPlaybackSpeed(guild.getIdLong(), speed);
        applyPlaybackSpeedFilter(getGuildMusicManager(guild), applied);
        notifyStateChanged(guild.getIdLong());
        return applied;
    }

    private void applyPlaybackSpeedFilter(GuildMusicManager manager, double speed) {
        if (manager == null) {
            return;
        }
        double applied = Math.max(0.5d, Math.min(2.0d, speed));
        if (Math.abs(applied - 1.0d) < 0.0001d) {
            manager.getPlayer().setFilterFactory(null);
            return;
        }
        manager.getPlayer().setFilterFactory((track, format, output) -> {
            int sourceRate = format.sampleRate;
            int targetRate = Math.max(8000, (int) Math.round(sourceRate * applied));
            AudioFilter filter = new ResamplingPcmAudioFilter(
                    playerManager.getConfiguration(),
                    format.channelCount,
                    output,
                    sourceRate,
                    targetRate
            );
            return List.of(filter);
        });
    }

    public String getCurrentAuthor(Guild guild) {
        AudioTrack track = getCurrentTrack(guild);
        if (track == null || track.getInfo() == null || track.getInfo().author == null || track.getInfo().author.isBlank()) {
            return "-";
        }
        return track.getInfo().author;
    }

    public List<MusicDataService.PlaybackEntry> getRecentHistory(long guildId, int limit) {
        return musicDataService.getRecentHistory(guildId, limit);
    }

    public MusicDataService.MusicStatsSnapshot getStats(long guildId) {
        return musicDataService.getStats(guildId);
    }

    public List<MusicDataService.PlaylistSummary> listPlaylists(long guildId) {
        return musicDataService.listPlaylists(guildId);
    }

    public List<MusicDataService.PlaylistSummary> listPlaylists(long guildId, Long ownerIdFilter) {
        return musicDataService.listPlaylists(guildId, ownerIdFilter);
    }

    public MusicDataService.PlaylistSummary getPlaylistSummary(long guildId, String playlistName) {
        return musicDataService.getPlaylistSummary(guildId, playlistName);
    }

    public MusicDataService.PlaylistShareCode exportPlaylist(long guildId, String playlistName) {
        return musicDataService.exportPlaylist(guildId, playlistName);
    }

    public MusicDataService.PlaylistImportResult importPlaylist(long guildId, String code, String playlistName, Long requesterId, String requesterName) {
        return musicDataService.importPlaylist(guildId, code, playlistName, requesterId, requesterName);
    }

    public MusicDataService.PlaylistSaveResult saveCurrentPlaylist(Guild guild, String playlistName, Long requesterId, String requesterName) {
        List<MusicDataService.PlaybackEntry> snapshot = new ArrayList<>();
        MusicDataService.PlaybackEntry current = snapshotTrack(getCurrentTrack(guild));
        if (current != null) {
            snapshot.add(current);
        }
        for (AudioTrack track : getQueueSnapshot(guild)) {
            MusicDataService.PlaybackEntry entry = snapshotTrack(track);
            if (entry != null) {
                snapshot.add(entry);
            }
        }
        return musicDataService.savePlaylist(guild.getIdLong(), playlistName, snapshot, requesterId, requesterName);
    }

    public MusicDataService.PlaylistDeleteResult deletePlaylist(long guildId, String playlistName, Long requesterId) {
        return musicDataService.deletePlaylist(guildId, playlistName, requesterId);
    }

    public MusicDataService.PlaylistDeleteResult deletePlaylist(long guildId, String playlistName, Long requesterId, boolean allowManageOverride) {
        return musicDataService.deletePlaylist(guildId, playlistName, requesterId, allowManageOverride);
    }

    public MusicDataService.PlaylistTrackAddResult addCurrentTrackToPlaylist(Guild guild, String playlistName, Long requesterId) {
        return musicDataService.addPlaylistTrack(guild.getIdLong(), playlistName, snapshotTrack(getCurrentTrack(guild)), requesterId);
    }

    public void addTrackToPlaylistByInput(Guild guild,
                                          String playlistName,
                                          String input,
                                          Long requesterId,
                                          String requesterName,
                                          Consumer<MusicDataService.PlaylistTrackAddResult> onSuccess,
                                          Consumer<String> onError) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isBlank()) {
            onSuccess.accept(new MusicDataService.PlaylistTrackAddResult(
                    MusicDataService.PlaylistMutationStatus.EMPTY,
                    playlistName,
                    "",
                    0,
                    null,
                    ""
            ));
            return;
        }
        String inputError = validateInputForLoad(trimmed);
        if (inputError != null) {
            onError.accept(inputError);
            return;
        }
        ResolvedInput resolvedInput = resolveInput(trimmed, inputClassifier.classify(trimmed));
        String identifier = resolvedInput.isUrl ? resolvedInput.identifier : YT_SEARCH_PREFIX + resolvedInput.identifier;
        playerManager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                applyTrackMetadata(track, resolvedInput.sourceLabel, requesterId, requesterName, trimmed, identifier);
                onSuccess.accept(musicDataService.addPlaylistTrack(
                        guild.getIdLong(),
                        playlistName,
                        snapshotTrack(track),
                        requesterId
                ));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                AudioTrack track = playlist == null ? null
                        : (playlist.getSelectedTrack() != null ? playlist.getSelectedTrack()
                        : (playlist.getTracks().isEmpty() ? null : playlist.getTracks().get(0)));
                if (track == null) {
                    onSuccess.accept(new MusicDataService.PlaylistTrackAddResult(
                            MusicDataService.PlaylistMutationStatus.EMPTY,
                            playlistName,
                            "",
                            0,
                            null,
                            ""
                    ));
                    return;
                }
                applyTrackMetadata(track, resolvedInput.sourceLabel, requesterId, requesterName, trimmed, identifier);
                onSuccess.accept(musicDataService.addPlaylistTrack(
                        guild.getIdLong(),
                        playlistName,
                        snapshotTrack(track),
                        requesterId
                ));
            }

            @Override
            public void noMatches() {
                onSuccess.accept(new MusicDataService.PlaylistTrackAddResult(
                        MusicDataService.PlaylistMutationStatus.EMPTY,
                        playlistName,
                        "",
                        0,
                        null,
                        ""
                ));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                if (isYoutubeLoadAttempt(trimmed, identifier, resolvedInput.sourceLabel, exception)) {
                    YoutubeFailureReport youtubeFailure = YOUTUBE_FAILURE_CLASSIFIER.classify(exception);
                    logYoutubeFailure(
                            "playlist-add-load",
                            guild.getIdLong(),
                            extractYouTubeVideoId(trimmed),
                            "-",
                            youtubeFailure,
                            exception
                    );
                    onError.accept(youtubeFailure.errorKey());
                    return;
                }
                AudioLoadFailureClassifier.Category category = FAILURE_CLASSIFIER.classify(exception);
                if (category != AudioLoadFailureClassifier.Category.UNKNOWN
                        && !looksLikeYouTubeUrl(trimmed)
                        && !looksLikeSpotifyUrl(trimmed)) {
                    onError.accept(FAILURE_CLASSIFIER.errorKey(category));
                } else {
                    onError.accept(exception == null || exception.getMessage() == null ? "-" : exception.getMessage().trim());
                }
            }
        });
    }

    public MusicDataService.PlaylistTrackRemoveResult removePlaylistTrack(long guildId, String playlistName, int index, Long requesterId) {
        return musicDataService.removePlaylistTrack(guildId, playlistName, index, requesterId);
    }

    public List<MusicDataService.PlaybackEntry> getPlaylistTracks(long guildId, String playlistName) {
        return musicDataService.getPlaylistTracks(guildId, playlistName);
    }

    public int loadPlaylist(Guild guild,
                            String playlistName,
                            Consumer<String> messageSender,
                            Long requesterId,
                            String requesterName) {
        return loadPlaylist(guild, playlistName, messageSender, requesterId, requesterName, null);
    }

    public int loadPlaylist(Guild guild,
                            String playlistName,
                            Consumer<String> messageSender,
                            Long requesterId,
                            String requesterName,
                            String sourceLabelOverride) {
        List<MusicDataService.PlaybackEntry> entries = musicDataService.getPlaylistTracks(guild.getIdLong(), playlistName);
        if (entries.isEmpty()) {
            return 0;
        }
        GuildMusicManager guildMusicManager = getGuildMusicManager(guild);
        clearAutoplayNotice(guild.getIdLong());
        resumeIfPaused(guildMusicManager.getPlayer(), guild.getIdLong());
        int queued = 0;
        for (MusicDataService.PlaybackEntry entry : entries) {
            String identifier = playlistIdentifier(entry);
            if (identifier == null || identifier.isBlank()) {
                continue;
            }
            load(
                    guild.getIdLong(),
                    guildMusicManager,
                    messageSender == null ? ignored -> { } : messageSender,
                    identifier,
                    identifier,
                    sourceLabelOverride == null || sourceLabelOverride.isBlank() ? entry.source() : sourceLabelOverride,
                    true,
                    requesterId,
                    requesterName,
                    0
            );
            queued++;
        }
        return queued;
    }

    private void notifyStateChanged(long guildId) {
        Runnable listener = guildStateListeners.get(guildId);
        if (listener != null) {
            listener.run();
        }
    }

    private void handleTrackStarted(long guildId, AudioTrack track) {
        TrackLoadContext context = readContext(track);
        if (context != null && context.recoveryAttempts() > 0) {
            return;
        }
        MusicDataService.PlaybackEntry entry = snapshotTrack(track);
        if (entry != null) {
            musicDataService.recordTrackStarted(guildId, entry);
        }
    }

    private void handleTrackFinished(long guildId, AudioTrack track) {
        if (track == null) {
            return;
        }
        long duration = Math.max(0L, track.getDuration());
        long position = Math.max(0L, track.getPosition());
        TrackLoadContext context = readContext(track);
        long segmentStart = context == null ? 0L : Math.min(position, context.recoveryResumePosition());
        long playedPosition = Math.max(0L, position - segmentStart);
        long playableDuration = duration > 0L ? Math.max(0L, duration - segmentStart) : playedPosition;
        long playedMillis = duration > 0L ? Math.min(playableDuration, playedPosition) : playedPosition;
        musicDataService.recordTrackFinished(guildId, playedMillis);
    }

    private void handleTrackException(long guildId, AudioTrack track, Throwable exception) {
        if (isYoutubeTrack(track) || YOUTUBE_FAILURE_CLASSIFIER.isYoutubeSourceFailure(exception)) {
            YoutubeFailureReport youtubeFailure = YOUTUBE_FAILURE_CLASSIFIER.classify(exception);
            recordYoutubePlaybackFailure(youtubeFailure);
            recordYoutubePrecheckFailure(track, youtubeFailure);
            logYoutubeFailure(
                    "playback",
                    guildId,
                    youtubeVideoId(track),
                    trackTitle(track),
                    youtubeFailure,
                    exception
            );
            if (youtubeFailure.allowsPlaybackRecovery(effectiveYoutubeAuthMode)) {
                TrackRecoveryService.StartResult recovery = recoverTrack(
                        guildId,
                        track,
                        youtubeFailure.category().name(),
                        youtubeFailure.errorKey()
                );
                if (recovery == TrackRecoveryService.StartResult.STARTED
                        || recovery == TrackRecoveryService.StartResult.ALREADY_IN_PROGRESS
                        || recovery == TrackRecoveryService.StartResult.EXHAUSTED
                        || recovery == TrackRecoveryService.StartResult.STALE) {
                    return;
                }
            }
            notifyPlaybackFailure(guildId, trackTitle(track), youtubeFailure.errorKey());
            skipFailedTrack(guildId, track);
            return;
        }
        AudioLoadFailureClassifier.Category category = FAILURE_CLASSIFIER.classify(exception);
        logPlaybackFailure(guildId, track, category, exception);
        if (FAILURE_CLASSIFIER.isRecoverable(category)) {
            TrackRecoveryService.StartResult recovery = recoverTrack(
                    guildId,
                    track,
                    category.name(),
                    FAILURE_CLASSIFIER.errorKey(category)
            );
            if (recovery == TrackRecoveryService.StartResult.STARTED
                    || recovery == TrackRecoveryService.StartResult.ALREADY_IN_PROGRESS
                    || recovery == TrackRecoveryService.StartResult.EXHAUSTED
                    || recovery == TrackRecoveryService.StartResult.STALE) {
                return;
            }
        }
        notifyPlaybackFailure(guildId, trackTitle(track), FAILURE_CLASSIFIER.errorKey(category));
        skipFailedTrack(guildId, track);
    }

    private void handleTrackStuck(long guildId, AudioTrack track, long thresholdMs) {
        LOGGER.warn(
                "[NoRule] Track stuck: guildId={} identifier={} thresholdMs={} positionMs={}",
                guildId,
                sanitizeInputForLog(track == null ? null : track.getIdentifier()),
                thresholdMs,
                track == null ? 0L : Math.max(0L, track.getPosition())
        );
        TrackRecoveryService.StartResult recovery = recoverTrack(
                guildId,
                track,
                AudioLoadFailureClassifier.Category.TRACK_STUCK.name(),
                FAILURE_CLASSIFIER.errorKey(AudioLoadFailureClassifier.Category.TRACK_STUCK)
        );
        if (recovery == TrackRecoveryService.StartResult.STARTED
                || recovery == TrackRecoveryService.StartResult.ALREADY_IN_PROGRESS
                || recovery == TrackRecoveryService.StartResult.EXHAUSTED
                || recovery == TrackRecoveryService.StartResult.STALE) {
            return;
        }
        notifyPlaybackFailure(guildId, trackTitle(track), FAILURE_CLASSIFIER.errorKey(AudioLoadFailureClassifier.Category.TRACK_STUCK));
        skipFailedTrack(guildId, track);
    }

    private TrackRecoveryService.StartResult recoverTrack(long guildId,
                                                          AudioTrack track,
                                                          String category,
                                                          String finalErrorKey) {
        GuildMusicManager manager = musicManagers.get(guildId);
        if (manager == null || track == null) {
            return TrackRecoveryService.StartResult.STALE;
        }
        TrackLoadContext context = readContext(track);
        if (context == null) {
            String identifier = resolveRecoveryIdentifier(track, track.getIdentifier());
            context = new TrackLoadContext(identifier, identifier, "youtube", null, "", 0);
        }
        String title = trackTitle(track);
        return trackRecoveryService.recover(
                guildId,
                track,
                context,
                Math.max(0L, track.getPosition()),
                track.isSeekable(),
                recoveryGateway(manager),
                new TrackRecoveryService.Listener() {
                    @Override
                    public void recovering(int attempt, int maxAttempts) {
                        if (attempt == 1) {
                            notifyPlaybackFailure(guildId, title, TRACK_RECOVERING_ERROR_KEY);
                        }
                        LOGGER.warn(
                                "[NoRule] Track recovery started: guildId={} identifier={} category={} attempt={}/{}",
                                guildId,
                                sanitizeInputForLog(track.getIdentifier()),
                                category,
                                attempt,
                                maxAttempts
                        );
                    }

                    @Override
                    public void recovered(int attempt) {
                        clearAutoplayNotice(guildId);
                        notifyStateChanged(guildId);
                        LOGGER.info(
                                "[NoRule] Track recovery completed: guildId={} identifier={} attempt={}",
                                guildId,
                                sanitizeInputForLog(track.getIdentifier()),
                                attempt
                        );
                    }

                    @Override
                    public void recoveryFailed(Throwable failure) {
                        if (isYoutubeTrack(track) || YOUTUBE_FAILURE_CLASSIFIER.isYoutubeSourceFailure(failure)) {
                            YoutubeFailureReport youtubeFailure = YOUTUBE_FAILURE_CLASSIFIER.classify(failure);
                            recordYoutubePlaybackFailure(youtubeFailure);
                            recordYoutubePrecheckFailure(track, youtubeFailure);
                            logYoutubeFailure(
                                    "recovery-load",
                                    guildId,
                                    youtubeVideoId(track),
                                    title,
                                    youtubeFailure,
                                    failure
                            );
                            notifyPlaybackFailure(guildId, title, youtubeFailure.errorKey());
                            return;
                        }
                        logPlaybackFailure(guildId, track, FAILURE_CLASSIFIER.classify(failure), failure);
                        notifyPlaybackFailure(guildId, title,
                                finalErrorKey == null ? TRACK_RECOVERY_FAILED_ERROR_KEY : finalErrorKey);
                    }

                    @Override
                    public void exhausted(int maxAttempts) {
                        LOGGER.warn(
                                "[NoRule] Track recovery exhausted: guildId={} identifier={} maxAttempts={}",
                                guildId,
                                sanitizeInputForLog(track.getIdentifier()),
                                maxAttempts
                        );
                        notifyPlaybackFailure(guildId, title,
                                finalErrorKey == null ? TRACK_RECOVERY_EXHAUSTED_ERROR_KEY : finalErrorKey);
                    }
                }
        );
    }

    private TrackRecoveryService.RecoveryGateway recoveryGateway(GuildMusicManager manager) {
        return new TrackRecoveryService.RecoveryGateway() {
            @Override
            public long playbackGeneration() {
                return manager.getScheduler().getPlaybackGeneration();
            }

            @Override
            public boolean isActive(Object track, long expectedGeneration) {
                return manager.isConnected()
                        && track instanceof AudioTrack audioTrack
                        && manager.getScheduler().isActiveTrack(audioTrack, expectedGeneration);
            }

            @Override
            public void pause(Object track, long expectedGeneration) {
                if (track instanceof AudioTrack audioTrack) {
                    manager.getScheduler().pauseIfCurrent(audioTrack, expectedGeneration);
                }
            }

            @Override
            public void reload(String identifier, TrackRecoveryService.RecoveryLoadHandler handler) {
                String inputError = validateInputForLoad(identifier);
                if (inputError != null) {
                    handler.failed(new IllegalArgumentException(inputError));
                    return;
                }
                playerManager.loadItemOrdered(manager, identifier, new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack loadedTrack) {
                        AudioTrack playbackTrack = preparePlaybackTrack(loadedTrack);
                        handler.loaded(playbackTrack, playbackTrack.isSeekable());
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        AudioTrack loadedTrack = playlist == null ? null
                                : (playlist.getSelectedTrack() != null
                                ? playlist.getSelectedTrack()
                                : (playlist.getTracks().isEmpty() ? null : playlist.getTracks().get(0)));
                        if (loadedTrack == null) {
                            handler.noMatches();
                        } else {
                            AudioTrack playbackTrack = preparePlaybackTrack(loadedTrack);
                            handler.loaded(playbackTrack, playbackTrack.isSeekable());
                        }
                    }

                    @Override
                    public void noMatches() {
                        handler.noMatches();
                    }

                    @Override
                    public void loadFailed(FriendlyException exception) {
                        handler.failed(exception);
                    }
                });
            }

            @Override
            public boolean replace(Object expectedTrack,
                                   Object replacement,
                                   long expectedGeneration,
                                   long resumePosition,
                                   TrackLoadContext context) {
                if (!(expectedTrack instanceof AudioTrack oldTrack) || !(replacement instanceof AudioTrack newTrack)) {
                    return false;
                }
                newTrack.setUserData(context);
                return manager.getScheduler().replaceIfCurrent(oldTrack, newTrack, expectedGeneration, resumePosition);
            }

            @Override
            public void skip(Object expectedTrack, long expectedGeneration) {
                if (expectedTrack instanceof AudioTrack audioTrack) {
                    manager.getScheduler().skipIfCurrent(audioTrack, expectedGeneration);
                }
            }
        };
    }

    private void skipFailedTrack(long guildId, AudioTrack track) {
        GuildMusicManager manager = musicManagers.get(guildId);
        if (manager == null || track == null) {
            return;
        }
        long generation = manager.getScheduler().getPlaybackGeneration();
        manager.getScheduler().skipIfCurrent(track, generation);
    }

    private void notifyPlaybackFailure(long guildId, String title, String rawError) {
        setAutoplayNotice(guildId, "LOAD_FAILED:" + rawError);
        BiConsumer<Long, PlaybackFailure> listener = playbackFailureListener;
        if (listener != null) {
            listener.accept(guildId, new PlaybackFailure(title, rawError));
        }
    }

    private void logPlaybackFailure(long guildId,
                                    AudioTrack track,
                                    AudioLoadFailureClassifier.Category category,
                                    Throwable exception) {
        String summary = "[NoRule] Playback failed: guildId=" + guildId
                + " identifier=" + sanitizeInputForLog(track == null ? null : track.getIdentifier())
                + " title=" + trackTitle(track)
                + " category=" + category
                + " message=" + safeExceptionMessage(exception)
                + " rootCause=" + rootCauseDetails(exception);
        if (category == AudioLoadFailureClassifier.Category.UNKNOWN) {
            LOGGER.error(summary, exception);
        } else {
            LOGGER.warn(summary);
        }
    }

    private boolean isYoutubeLoadAttempt(String userInput,
                                         String identifier,
                                         String sourceLabel,
                                         Throwable failure) {
        if (YOUTUBE_FAILURE_CLASSIFIER.isYoutubeSourceFailure(failure)) {
            return true;
        }
        if (looksLikeYouTubeUrl(userInput) || looksLikeYouTubeUrl(identifier)) {
            return true;
        }
        String resolved = identifier == null ? "" : identifier.trim();
        return resolved.regionMatches(true, 0, YT_SEARCH_PREFIX, 0, YT_SEARCH_PREFIX.length())
                || "youtube".equalsIgnoreCase(normalizeSourceLabel(sourceLabel));
    }

    private boolean isYoutubeTrack(AudioTrack track) {
        if (track == null) {
            return false;
        }
        AudioSourceManager sourceManager = track.getSourceManager();
        if (sourceManager != null && "youtube".equalsIgnoreCase(sourceManager.getSourceName())) {
            return true;
        }
        AudioTrackInfo info = track.getInfo();
        if (info != null && (looksLikeYouTubeUrl(info.uri)
                || YouTubePlaybackPrecheckService.isValidVideoId(info.identifier))) {
            return true;
        }
        TrackLoadContext context = readContext(track);
        return context != null
                && ("youtube".equalsIgnoreCase(context.sourceName())
                || looksLikeYouTubeUrl(context.resolvedIdentifier()));
    }

    private String youtubeVideoId(AudioTrack track) {
        if (track == null) {
            return null;
        }
        AudioTrackInfo info = track.getInfo();
        if (info != null) {
            if (YouTubePlaybackPrecheckService.isValidVideoId(info.identifier)) {
                return info.identifier.trim();
            }
            String uriVideoId = extractYouTubeVideoId(info.uri);
            if (uriVideoId != null) {
                return uriVideoId;
            }
        }
        TrackLoadContext context = readContext(track);
        if (context == null) {
            return null;
        }
        if (YouTubePlaybackPrecheckService.isValidVideoId(context.resolvedIdentifier())) {
            return context.resolvedIdentifier();
        }
        return extractYouTubeVideoId(context.resolvedIdentifier());
    }

    private void recordYoutubePrecheckFailure(AudioTrack track, YoutubeFailureReport failure) {
        String videoId = youtubeVideoId(track);
        if (videoId != null) {
            youtubePrecheckService.recordPlaybackFailure(videoId, failure);
        }
    }

    private void recordYoutubePlaybackFailure(YoutubeFailureReport failure) {
        if (failure != null) {
            youtubePlaybackFailureCounters.get(failure.category()).increment();
        }
    }

    public Map<YoutubeFailureCategory, Long> getYoutubePlaybackFailureCounts() {
        Map<YoutubeFailureCategory, Long> snapshot = new EnumMap<>(YoutubeFailureCategory.class);
        youtubePlaybackFailureCounters.forEach((category, counter) -> snapshot.put(category, counter.sum()));
        return Map.copyOf(snapshot);
    }

    private void logYoutubeFailure(String stage,
                                   long guildId,
                                   String videoId,
                                   String title,
                                   YoutubeFailureReport failure,
                                   Throwable exception) {
        YoutubeFailureReport report = failure == null
                ? YOUTUBE_FAILURE_CLASSIFIER.classify(exception)
                : failure;
        String summary = "[NoRule] YouTube " + stage + " failed:"
                + " guildId=" + guildId
                + " videoId=" + sanitizeInputForLog(videoId)
                + " title=" + sanitizeInputForLog(title)
                + " category=" + report.category()
                + " recoveryClass=" + report.recoveryClass()
                + " clients=" + report.clientsSummary();
        if (report.category() == YoutubeFailureCategory.UNKNOWN) {
            LOGGER.error(summary, exception);
            return;
        }
        LOGGER.warn(summary);
        if (LOGGER.isDebugEnabled() && exception != null) {
            LOGGER.debug("YouTube {} failure details: guildId={} videoId={}",
                    stage, guildId, sanitizeInputForLog(videoId), exception);
        }
    }

    private String trackTitle(AudioTrack track) {
        return track == null || track.getInfo() == null || track.getInfo().title == null
                ? "-"
                : track.getInfo().title;
    }

    public record PlaybackFailure(String title, String rawError) {
    }

    private void handleQueueExhausted(long guildId, AudioTrack endedTrack) {
        if (endedTrack == null || !autoplayEnabledChecker.test(guildId)) {
            clearAutoplayNotice(guildId);
            return;
        }
        GuildMusicManager guildMusicManager = musicManagers.get(guildId);
        if (guildMusicManager == null) {
            return;
        }
        String fallbackQuery = buildAutoplayQuery(endedTrack);
        if (fallbackQuery.isBlank()) {
            setAutoplayNotice(guildId, "NO_MATCH");
            return;
        }
        loadAutoplayCandidate(guildId, guildMusicManager, endedTrack, YT_SEARCH_PREFIX + fallbackQuery, null, false);
    }

    private void loadAutoplayCandidate(long guildId,
                                       GuildMusicManager guildMusicManager,
                                       AudioTrack seedTrack,
                                       String identifier,
                                       String fallbackQuery,
                                       boolean allowFallbackToQuery) {
        playerManager.loadItemOrdered(guildMusicManager, identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                if (isLikelySameTrack(seedTrack, track)) {
                    if (tryFallback()) {
                        return;
                    }
                    setAutoplayNotice(guildId, "NO_MATCH");
                    return;
                }
                if (wasRecentlyPlayed(guildId, track) && tryFallback()) {
                    return;
                }
                queueAutoplayTrack(guildId, guildMusicManager, track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    if (tryFallback()) {
                        return;
                    }
                    setAutoplayNotice(guildId, "NO_MATCH");
                    return;
                }
                AudioTrack candidate = playlist.getTracks().stream()
                        .filter(track -> !isLikelySameTrack(seedTrack, track))
                        .filter(track -> !wasRecentlyPlayed(guildId, track))
                        .max(Comparator.comparingInt(track -> scoreAutoplayCandidate(seedTrack, track)))
                        .orElse(null);
                if (candidate == null) {
                    candidate = playlist.getTracks().stream()
                            .filter(track -> !isLikelySameTrack(seedTrack, track))
                            .max(Comparator.comparingInt(track -> scoreAutoplayCandidate(seedTrack, track)))
                            .orElse(null);
                    if (candidate == null) {
                        if (tryFallback()) {
                            return;
                        }
                        setAutoplayNotice(guildId, "NO_MATCH");
                        return;
                    }
                }
                queueAutoplayTrack(guildId, guildMusicManager, candidate);
            }

            @Override
            public void noMatches() {
                if (tryFallback()) {
                    return;
                }
                setAutoplayNotice(guildId, "NO_MATCH");
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                if (tryFallback()) {
                    return;
                }
                YoutubeFailureReport youtubeFailure = YOUTUBE_FAILURE_CLASSIFIER.classify(exception);
                logYoutubeFailure(
                        "autoplay-load",
                        guildId,
                        extractYouTubeVideoId(identifier),
                        "-",
                        youtubeFailure,
                        exception
                );
                setAutoplayNotice(guildId, "LOAD_FAILED:" + youtubeFailure.errorKey());
            }

            private boolean tryFallback() {
                if (!allowFallbackToQuery || fallbackQuery == null || fallbackQuery.isBlank()) {
                    return false;
                }
                loadAutoplayCandidate(
                        guildId,
                        guildMusicManager,
                        seedTrack,
                        YT_SEARCH_PREFIX + fallbackQuery,
                        null,
                        false
                );
                return true;
            }
        });
    }

    private void queueAutoplayTrack(long guildId, GuildMusicManager guildMusicManager, AudioTrack track) {
        YouTubePlaybackPrecheckResult precheck = precheckTrack(track, "youtube");
        if (!precheck.allowsQueue()) {
            setAutoplayNotice(guildId, "LOAD_FAILED:" + mapYouTubePrecheckFailure(precheck));
            return;
        }
        applyTrackMetadata(track, "autoplay", null, "AutoPlay");
        clearAutoplayNotice(guildId);
        queuePlaybackTrack(guildMusicManager, track);
    }

    private void setAutoplayNotice(long guildId, String message) {
        if (message == null || message.isBlank()) {
            autoplayNoticeByGuild.remove(guildId);
        } else {
            autoplayNoticeByGuild.put(guildId, message);
        }
        notifyStateChanged(guildId);
    }

    private boolean isLikelySameTrack(AudioTrack leftTrack, AudioTrack rightTrack) {
        if (leftTrack == null || rightTrack == null || leftTrack.getInfo() == null || rightTrack.getInfo() == null) {
            return false;
        }
        AudioTrackInfo left = leftTrack.getInfo();
        AudioTrackInfo right = rightTrack.getInfo();
        if (left.identifier != null && !left.identifier.isBlank() && left.identifier.equalsIgnoreCase(right.identifier)) {
            return true;
        }
        if (left.uri != null && !left.uri.isBlank() && left.uri.equalsIgnoreCase(right.uri)) {
            return true;
        }
        String leftTitle = left.title == null ? "" : left.title.trim();
        String rightTitle = right.title == null ? "" : right.title.trim();
        String leftAuthor = left.author == null ? "" : left.author.trim();
        String rightAuthor = right.author == null ? "" : right.author.trim();
        return !leftTitle.isBlank()
                && leftTitle.equalsIgnoreCase(rightTitle)
                && !leftAuthor.isBlank()
                && leftAuthor.equalsIgnoreCase(rightAuthor);
    }

    private boolean wasRecentlyPlayed(long guildId, AudioTrack candidateTrack) {
        MusicDataService.PlaybackEntry snapshot = snapshotTrack(candidateTrack);
        return musicDataService.wasRecentlyPlayed(guildId, snapshot, 10);
    }

    private int scoreAutoplayCandidate(AudioTrack seedTrack, AudioTrack candidateTrack) {
        if (seedTrack == null || candidateTrack == null || seedTrack.getInfo() == null || candidateTrack.getInfo() == null) {
            return Integer.MIN_VALUE;
        }
        AudioTrackInfo seed = seedTrack.getInfo();
        AudioTrackInfo candidate = candidateTrack.getInfo();
        int score = 0;

        String seedAuthor = normalizeComparableText(seed.author);
        String candidateAuthor = normalizeComparableText(candidate.author);
        if (!seedAuthor.isBlank() && !candidateAuthor.isBlank()) {
            if (seedAuthor.equals(candidateAuthor)) {
                score += 60;
            } else if (seedAuthor.contains(candidateAuthor) || candidateAuthor.contains(seedAuthor)) {
                score += 35;
            }
        }

        Set<String> seedTokens = comparableTokens(seed.title);
        Set<String> candidateTokens = comparableTokens(candidate.title);
        int overlap = 0;
        for (String token : seedTokens) {
            if (candidateTokens.contains(token)) {
                overlap++;
            }
        }
        score += Math.min(30, overlap * 6);

        String seedSource = normalizeComparableText(seed.uri);
        String candidateSource = normalizeComparableText(candidate.uri);
        if (!seedSource.isBlank() && !candidateSource.isBlank()) {
            if ((seedSource.contains("youtube") || seedSource.contains("youtu be"))
                    && (candidateSource.contains("youtube") || candidateSource.contains("youtu be"))) {
                score += 8;
            } else if (seedSource.contains("soundcloud") && candidateSource.contains("soundcloud")) {
                score += 8;
            }
        }

        long durationGap = Math.abs(Math.max(0L, seedTrack.getDuration()) - Math.max(0L, candidateTrack.getDuration()));
        if (durationGap <= 30_000L) {
            score += 8;
        } else if (durationGap <= 90_000L) {
            score += 4;
        }
        return score;
    }

    private String extractYouTubeVideoId(String uriText) {
        if (uriText == null || uriText.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(uriText.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            if (host.contains("youtube.com")) {
                String query = uri.getRawQuery();
                if (query == null || query.isBlank()) {
                    return null;
                }
                for (String pair : query.split("&")) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2 && "v".equalsIgnoreCase(keyValue[0]) && !keyValue[1].isBlank()) {
                        return keyValue[1];
                    }
                }
                return null;
            }
            if (host.contains("youtu.be")) {
                String path = uri.getPath();
                if (path == null || path.isBlank()) {
                    return null;
                }
                String value = path.startsWith("/") ? path.substring(1) : path;
                int slash = value.indexOf('/');
                return slash >= 0 ? value.substring(0, slash) : value;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void resumeIfPaused(AudioPlayer player, long guildId) {
        if (player.isPaused()) {
            player.setPaused(false);
            notifyStateChanged(guildId);
        }
    }

    private String buildAutoplayQuery(AudioTrack endedTrack) {
        AudioTrackInfo info = endedTrack.getInfo();
        if (info == null) {
            return "";
        }
        String title = stripNoise(info.title);
        String author = stripNoise(info.author);
        String query = (author + " " + title).trim();
        return query.length() > 180 ? query.substring(0, 180) : query;
    }

    private void applyTrackMetadata(AudioTrack track, String sourceLabel, Long requesterId, String requesterName) {
        String inferredInput = track == null || track.getInfo() == null ? "" : track.getInfo().uri;
        applyTrackMetadata(track, sourceLabel, requesterId, requesterName, inferredInput, inferredInput);
    }

    private void applyTrackMetadata(AudioTrack track,
                                    String sourceLabel,
                                    Long requesterId,
                                    String requesterName,
                                    String originalInput,
                                    String loadIdentifier) {
        if (track == null) {
            return;
        }
        track.setUserData(new TrackLoadContext(
                originalInput,
                resolveRecoveryIdentifier(track, loadIdentifier),
                normalizeSourceLabel(sourceLabel),
                requesterId,
                requesterName,
                0
        ));
    }

    private TrackLoadContext readContext(AudioTrack track) {
        Object userData = track.getUserData();
        if (userData instanceof TrackLoadContext context) {
            return context;
        }
        if (userData instanceof String legacySource) {
            String identifier = resolveRecoveryIdentifier(track, track.getIdentifier());
            return new TrackLoadContext(identifier, identifier, normalizeSourceLabel(legacySource), null, "", 0);
        }
        return null;
    }

    private String resolveRecoveryIdentifier(AudioTrack track, String fallback) {
        AudioSourceManager sourceManager = track == null ? null : track.getSourceManager();
        if (sourceManager != null
                && "bilibili".equalsIgnoreCase(sourceManager.getSourceName())
                && fallback != null
                && !fallback.isBlank()) {
            return fallback;
        }
        if (track != null && track.getIdentifier() != null && !track.getIdentifier().isBlank()) {
            return track.getIdentifier();
        }
        if (track != null && track.getInfo() != null && track.getInfo().uri != null && !track.getInfo().uri.isBlank()) {
            return track.getInfo().uri;
        }
        return fallback == null ? "" : fallback;
    }

    private String normalizeSourceLabel(String sourceLabel) {
        return sourceLabel == null || sourceLabel.isBlank() ? "youtube" : sourceLabel;
    }

    private MusicDataService.PlaybackEntry snapshotTrack(AudioTrack track) {
        if (track == null || track.getInfo() == null) {
            return null;
        }
        AudioTrackInfo info = track.getInfo();
        TrackLoadContext context = readContext(track);
        String source = context == null ? normalizeSourceLabel(null) : context.sourceName();
        Long requesterId = context == null ? null : context.requesterId();
        String requesterName = context == null ? "" : context.requesterName();
        return new MusicDataService.PlaybackEntry(
                Instant.now().toEpochMilli(),
                info.title,
                info.author,
                source,
                info.uri,
                resolveArtworkUrl(track),
                Math.max(0L, track.getDuration()),
                requesterId,
                requesterName
        );
    }

    private String resolveArtworkUrl(AudioTrack track) {
        if (track == null) {
            return null;
        }
        AudioTrackInfo info = track.getInfo();
        if (info != null && info.artworkUrl != null && !info.artworkUrl.isBlank()) {
            return info.artworkUrl;
        }
        if (info == null || info.uri == null || info.uri.isBlank()) {
            return null;
        }
        String videoId = extractYouTubeVideoId(info.uri);
        if (videoId != null && !videoId.isBlank()) {
            return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        }
        return null;
    }

    private String playlistIdentifier(MusicDataService.PlaybackEntry entry) {
        if (entry == null) {
            return null;
        }
        String uri = entry.uri() == null ? "" : entry.uri().trim();
        if (!uri.isBlank()) {
            if (looksLikeYouTubeUrl(uri)) {
                return normalizeYouTubePlaybackUrl(uri);
            }
            return uri;
        }
        String query = (stripNoise(entry.author()) + " " + stripNoise(entry.title())).trim();
        if (query.isBlank()) {
            return null;
        }
        return YT_SEARCH_PREFIX + query;
    }

    private String normalizeComparableText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ").trim();
    }

    private Set<String> comparableTokens(String value) {
        String normalized = normalizeComparableText(stripNoise(value));
        if (normalized.isBlank()) {
            return Set.of();
        }
        return List.of(normalized.split("\\s+")).stream()
                .filter(token -> token.length() >= 2)
                .limit(12)
                .collect(Collectors.toSet());
    }

    private String stripNoise(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)\\b(official|audio|video|lyrics|lyric video|mv|hd|4k|visualizer)\\b", " ")
                .replaceAll("[\\[\\](){}|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String normalizeRepeatedSpotifyUrl(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        Matcher urlStarts = SPOTIFY_URL_START_PATTERN.matcher(trimmed);
        if (!urlStarts.find() || urlStarts.start() != 0 || !urlStarts.find()) {
            return trimmed;
        }

        String firstUrl = trimmed.substring(0, urlStarts.start()).trim();
        String repeatedUrl = trimmed.substring(urlStarts.start()).trim();
        return isSameSpotifyResource(firstUrl, repeatedUrl) ? firstUrl : trimmed;
    }

    private static boolean isSameSpotifyResource(String firstUrl, String secondUrl) {
        Matcher firstResource = SPOTIFY_RESOURCE_PATTERN.matcher(firstUrl);
        Matcher secondResource = SPOTIFY_RESOURCE_PATTERN.matcher(secondUrl);
        return firstResource.find()
                && secondResource.find()
                && firstResource.group(1).equalsIgnoreCase(secondResource.group(1))
                && firstResource.group(2).equals(secondResource.group(2));
    }

    private ResolvedInput resolveInput(String input, AudioInputClassifier.Classification classification) {
        String trimmed = input.trim();
        if (YouTubePlaybackPrecheckService.isValidVideoId(trimmed)) {
            return new ResolvedInput("https://www.youtube.com/watch?v=" + trimmed, true, "youtube");
        }
        if (classification == null || classification.type() == AudioInputClassifier.AudioInputType.SEARCH_QUERY) {
            return new ResolvedInput(trimmed, false, "youtube");
        }
        if (isSpotifyClassification(classification.type())) {
            if (spotifySourceEnabled) {
                return new ResolvedInput(trimmed, true, "spotify");
            }
            if (classification.type() == AudioInputClassifier.AudioInputType.SPOTIFY_TRACK) {
                String keyword = resolveSpotifyToSearch(trimmed);
                if (!keyword.isBlank()) {
                    return new ResolvedInput(keyword, false, "spotify");
                }
            }
            // Avoid mismatched songs for playlists/albums/artists when Spotify source is unavailable.
            return new ResolvedInput(trimmed, true, "spotify");
        }
        if (classification.type() == AudioInputClassifier.AudioInputType.YOUTUBE_URL) {
            String normalized = normalizeYouTubePlaybackUrl(trimmed);
            if (isYouTubePlaylistUrl(normalized)) {
                return new ResolvedInput(normalized, true, "youtube");
            }
            String strictPlaylistUrl = toStrictYouTubePlaylistUrl(normalized);
            if (strictPlaylistUrl != null) {
                return new ResolvedInput(strictPlaylistUrl, true, "youtube");
            }
            return new ResolvedInput(normalized, true, "youtube");
        }
        if (classification.type() == AudioInputClassifier.AudioInputType.BILIBILI_URL) {
            return new ResolvedInput(trimmed, true, "bilibili");
        }
        if (classification.type() == AudioInputClassifier.AudioInputType.KNOWN_AUDIO_SERVICE_URL) {
            return new ResolvedInput(trimmed, true, detectKnownServiceSource(classification.uri()));
        }
        return new ResolvedInput(trimmed, true, "url");
    }

    private String normalizeYouTubePlaybackUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        if (isYouTubePlaylistUrl(url)) {
            return url;
        }
        try {
            String videoId = extractYouTubeVideoId(url);
            if (videoId == null || videoId.isBlank()) {
                return url;
            }
            return "https://www.youtube.com/watch?v=" + videoId;
        } catch (Exception ignored) {
            return url;
        }
    }

    private String resolveSpotifyToSearch(String spotifyUrl) {
        try {
            String encoded = URLEncoder.encode(spotifyUrl, StandardCharsets.UTF_8);
            URI uri = URI.create("https://open.spotify.com/oembed?url=" + encoded);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .build();
            HttpResponse<String> response = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }
            String body = response.body();
            String title = readJsonString(body, "title");
            String author = readJsonString(body, "author_name");
            String query = (title + " " + author).trim();
            return query.replace("Spotify", "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String readJsonString(String json, String field) {
        Pattern pattern = Pattern.compile(String.format(JSON_FIELD_PATTERN_TEMPLATE.pattern(), Pattern.quote(field)));
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1)
                .replace("\\\"", "\"")
                .replace("\\n", " ")
                .trim();
    }

    private boolean looksLikeYouTubeUrl(String text) {
        return inputClassifier.classify(text).type() == AudioInputClassifier.AudioInputType.YOUTUBE_URL;
    }

    private boolean isYouTubePlaylistUrl(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String raw = text.trim();
        if (!raw.startsWith(STRICT_YOUTUBE_PLAYLIST_PREFIX)) {
            return false;
        }
        String listPart = raw.substring(STRICT_YOUTUBE_PLAYLIST_PREFIX.length());
        if (listPart.isBlank()) {
            return false;
        }
        int amp = listPart.indexOf('&');
        String listId = amp >= 0 ? listPart.substring(0, amp) : listPart;
        return !listId.isBlank();
    }

    private String toStrictYouTubePlaylistUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase();
        if (!lower.contains("youtube.com") || !lower.contains("list=") || lower.contains("/watch?")) {
            return null;
        }
        int listIndex = lower.indexOf("list=");
        if (listIndex < 0) {
            return null;
        }
        String listPart = text.substring(listIndex + 5);
        int amp = listPart.indexOf('&');
        String listId = amp >= 0 ? listPart.substring(0, amp) : listPart;
        if (listId.isBlank()) {
            return null;
        }
        return STRICT_YOUTUBE_PLAYLIST_PREFIX + listId;
    }

    private AudioTrack enqueuePlaylistTracksBatched(GuildMusicManager guildMusicManager,
                                                    List<AudioTrack> tracks,
                                                    String sourceLabel,
                                                    Long requesterId,
                                                    String requesterName,
                                                    String originalInput,
                                                    String loadIdentifier) {
        if (tracks == null || tracks.isEmpty()) {
            return null;
        }
        AudioTrack firstQueued = null;
        for (int i = 0; i < tracks.size(); i += YOUTUBE_PLAYLIST_BATCH_SIZE) {
            int end = Math.min(i + YOUTUBE_PLAYLIST_BATCH_SIZE, tracks.size());
            for (int j = i; j < end; j++) {
                AudioTrack track = tracks.get(j);
                if (track == null) {
                    continue;
                }
                YouTubePlaybackPrecheckResult precheck = precheckTrack(track, sourceLabel);
                if (!precheck.allowsQueue()) {
                    continue;
                }
                applyTrackMetadata(track, sourceLabel, requesterId, requesterName, originalInput, loadIdentifier);
                AudioTrack queuedTrack = queuePlaybackTrack(guildMusicManager, track);
                if (firstQueued == null) {
                    firstQueued = queuedTrack;
                }
            }
        }
        return firstQueued;
    }

    private AudioTrack queuePlaybackTrack(GuildMusicManager guildMusicManager, AudioTrack track) {
        AudioTrack playbackTrack = preparePlaybackTrack(track);
        guildMusicManager.getScheduler().queue(playbackTrack);
        return playbackTrack;
    }

    private AudioTrack preparePlaybackTrack(AudioTrack track) {
        if (track == null) {
            return null;
        }
        String videoId = youtubeVideoId(track);
        if (videoId == null || videoId.isBlank()) {
            return track;
        }
        return youtubePlaybackTrackFactory.prepare(videoId, track);
    }

    private void cacheYoutubePlaylistTracks(String sourceUrl, List<AudioTrack> tracks) {
        String key = normalizeYouTubePlaylistCacheKey(sourceUrl);
        if (key == null || tracks == null || tracks.isEmpty()) {
            return;
        }
        List<AudioTrack> clones = tracks.stream()
                .map(AudioTrack::makeClone)
                .toList();
        youtubePlaylistCache.put(key, new CachedPlaylistTracks(System.currentTimeMillis() + YOUTUBE_PLAYLIST_CACHE_TTL_MS, clones));
    }

    private List<AudioTrack> getCachedYoutubePlaylistTracks(String sourceUrl) {
        String key = normalizeYouTubePlaylistCacheKey(sourceUrl);
        if (key == null) {
            return List.of();
        }
        CachedPlaylistTracks cached = youtubePlaylistCache.get(key);
        if (cached == null) {
            return List.of();
        }
        if (cached.expiresAtMs < System.currentTimeMillis()) {
            youtubePlaylistCache.remove(key);
            return List.of();
        }
        return cached.tracks.stream().map(AudioTrack::makeClone).toList();
    }

    private String normalizeYouTubePlaylistCacheKey(String url) {
        if (!isYouTubePlaylistUrl(url)) {
            return null;
        }
        String raw = url.trim();
        String listPart = raw.substring(STRICT_YOUTUBE_PLAYLIST_PREFIX.length());
        int amp = listPart.indexOf('&');
        String listId = amp >= 0 ? listPart.substring(0, amp) : listPart;
        return listId.isBlank() ? null : listId;
    }

    private static class CachedPlaylistTracks {
        private final long expiresAtMs;
        private final List<AudioTrack> tracks;

        private CachedPlaylistTracks(long expiresAtMs, List<AudioTrack> tracks) {
            this.expiresAtMs = expiresAtMs;
            this.tracks = tracks == null ? Collections.emptyList() : tracks;
        }
    }

    private boolean looksLikeSpotifyUrl(String text) {
        return isSpotifyClassification(inputClassifier.classify(text).type());
    }

    private boolean looksLikeSpotifyOrShareUrl(String text) {
        if (text == null) {
            return false;
        }
        return looksLikeSpotifyUrl(text) || hasExactHost(text, "spotify.link", "spotify.app.link");
    }

    private boolean isSpotifyJamLink(String text) {
        if (text == null) {
            return false;
        }
        if (hasExactHost(text, "spotify.link", "spotify.app.link")) {
            return true;
        }
        URI uri = parseHttpUri(text);
        return uri != null
                && hasExactHost(uri, "open.spotify.com", "www.open.spotify.com")
                && uri.getPath() != null
                && uri.getPath().toLowerCase().contains("/socialsession/");
    }

    private boolean isSpotifyProfileLink(String text) {
        if (text == null) {
            return false;
        }
        URI uri = parseHttpUri(text);
        if (uri == null || !hasExactHost(uri, "open.spotify.com", "www.open.spotify.com")) {
            return false;
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase();
        return path.contains("/account/profile") || path.contains("/user/");
    }

    private boolean isSpotifyClassification(AudioInputClassifier.AudioInputType type) {
        return type == AudioInputClassifier.AudioInputType.SPOTIFY_TRACK
                || type == AudioInputClassifier.AudioInputType.SPOTIFY_ALBUM
                || type == AudioInputClassifier.AudioInputType.SPOTIFY_PLAYLIST
                || type == AudioInputClassifier.AudioInputType.SPOTIFY_ARTIST
                || type == AudioInputClassifier.AudioInputType.SPOTIFY_EPISODE
                || type == AudioInputClassifier.AudioInputType.SPOTIFY_SHOW;
    }

    private String detectKnownServiceSource(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return "url";
        }
        String host = uri.getHost().toLowerCase();
        if (host.equals("soundcloud.com") || host.endsWith(".soundcloud.com")) {
            return "soundcloud";
        }
        if (host.equals("bandcamp.com") || host.endsWith(".bandcamp.com")) {
            return "bandcamp";
        }
        return host;
    }

    private boolean hasExactHost(String text, String... hosts) {
        URI uri = parseHttpUri(text);
        return uri != null && hasExactHost(uri, hosts);
    }

    private boolean hasExactHost(URI uri, String... hosts) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        String actual = uri.getHost().toLowerCase();
        return Arrays.stream(hosts).anyMatch(host -> actual.equals(host));
    }

    private URI parseHttpUri(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(text.trim());
            String scheme = uri.getScheme();
            return scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    ? uri
                    : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Map<YoutubeFailureCategory, LongAdder> createYoutubeFailureCounters() {
        Map<YoutubeFailureCategory, LongAdder> counters = new EnumMap<>(YoutubeFailureCategory.class);
        for (YoutubeFailureCategory category : YoutubeFailureCategory.values()) {
            counters.put(category, new LongAdder());
        }
        return counters;
    }

    private record YoutubeAuthRuntime(
            MusicConfig.Youtube.AuthMode mode,
            boolean strict,
            String poToken,
            String visitorData,
            String oauthRefreshToken
    ) {
        private static YoutubeAuthRuntime none(boolean strict) {
            return new YoutubeAuthRuntime(MusicConfig.Youtube.AuthMode.NONE, strict, null, null, null);
        }
    }

    private static class ResolvedInput {
        private final String identifier;
        private final boolean isUrl;
        private final String sourceLabel;

        private ResolvedInput(String identifier, boolean isUrl, String sourceLabel) {
            this.identifier = identifier;
            this.isUrl = isUrl;
            this.sourceLabel = sourceLabel;
        }
    }

}



