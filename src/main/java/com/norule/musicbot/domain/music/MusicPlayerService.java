package com.norule.musicbot.domain.music;

import com.norule.musicbot.config.*;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
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

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MusicPlayerService {
    private static final String YT_SEARCH_PREFIX = "ytsearch:";
    private static final Pattern JSON_FIELD_PATTERN_TEMPLATE = Pattern.compile("\"%s\"\\s*:\\s*\"(.*?)\"");
    private static final long SPOTIFY_RATE_LIMIT_COOLDOWN_MS = 60_000L;
    private static final long[] SPOTIFY_RATE_LIMIT_RETRY_DELAYS_MS = new long[] {3000L, 8000L};

    private final AudioPlayerManager playerManager;
    private final MusicDataService musicDataService;
    private final Map<Long, GuildMusicManager> musicManagers = new ConcurrentHashMap<>();
    private final Map<Long, Runnable> guildStateListeners = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastCommandChannelByGuild = new ConcurrentHashMap<>();
    private final Map<Long, String> autoplayNoticeByGuild = new ConcurrentHashMap<>();
    private final Map<String, Long> spotifyRateLimitUntilByKey = new ConcurrentHashMap<>();
    private final BotConfig.Music.Youtube youtubeConfig;
    private final BotConfig.Music.Spotify spotifyConfig;
    private final boolean spotifySourceEnabled;
    private volatile BiConsumer<Long, PlaybackFailure> playbackFailureListener;
    private volatile LongPredicate autoplayEnabledChecker = guildId -> true;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @SuppressWarnings("deprecation")
    public MusicPlayerService(Path dataDir,
                              LongFunction<BotConfig.Music> musicConfigResolver,
                              BotConfig.Music.Youtube youtubeConfig,
                              BotConfig.Music.Spotify spotifyConfig) {
        this.musicDataService = new MusicDataService(dataDir, musicConfigResolver);
        this.youtubeConfig = youtubeConfig == null ? BotConfig.Music.Youtube.defaultValues() : youtubeConfig;
        this.spotifyConfig = spotifyConfig == null ? BotConfig.Music.Spotify.defaultValues() : spotifyConfig;
        playerManager = new DefaultAudioPlayerManager();
        configureYouTubePoToken();
        YoutubeAudioSourceManager youtubeSourceManager = createYoutubeSourceManager();
        configureYouTubeOauth(youtubeSourceManager);
        playerManager.registerSourceManager(youtubeSourceManager);
        this.spotifySourceEnabled = registerSpotifySourceIfConfigured();
        AudioSourceManagers.registerLocalSource(playerManager);
        AudioSourceManagers.registerRemoteSources(playerManager,
                com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class);
    }

    private boolean registerSpotifySourceIfConfigured() {
        if (!spotifyConfig.isEnabled()) {
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
                    firstNonBlank(System.getenv("SPOTIFY_CUSTOM_TOKEN_ENDPOINT"), spotifyConfig.getCustomTokenEndpoint())
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
        String customEndpoint = firstNonBlank(System.getenv("SPOTIFY_CUSTOM_TOKEN_ENDPOINT"), spotifyConfig.getCustomTokenEndpoint());
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
                        .newInstance(clientId, clientSecret, preferAnonymousToken, spDc, countryCode, customTokenEndpoint, managerFunction, resolver);
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

    private YoutubeAudioSourceManager createYoutubeSourceManager() {
        List<dev.lavalink.youtube.clients.skeleton.Client> clients = new ArrayList<>();
        clients.add(new MusicWithThumbnail());
        if (isYouTubeOauthConfigured()) {
            clients.add(new Tv());
        }
        clients.add(new WebWithThumbnail());
        clients.add(new MWebWithThumbnail());
        clients.add(new WebEmbeddedWithThumbnail());
        clients.add(new TvHtml5SimplyWithThumbnail());
        clients.add(new AndroidVrWithThumbnail());
        clients.add(new AndroidMusicWithThumbnail());
        clients.add(new IosWithThumbnail());
        String remoteCipherUrl = firstNonBlank(
                System.getenv("YOUTUBE_CIPHER_SERVER"),
                System.getenv("YOUTUBE_REMOTE_CIPHER_URL"),
                youtubeConfig.getCipherServer()
        );
        dev.lavalink.youtube.clients.skeleton.Client[] clientArray =
                clients.toArray(dev.lavalink.youtube.clients.skeleton.Client[]::new);
        if (remoteCipherUrl == null) {
            return new YoutubeAudioSourceManager(clientArray);
        }
        String remoteCipherPassword = firstNonBlank(
                System.getenv("YOUTUBE_CIPHER_PASSWORD"),
                System.getenv("YOUTUBE_REMOTE_CIPHER_PASSWORD"),
                youtubeConfig.getCipherPassword()
        );
        String remoteCipherUserAgent = firstNonBlank(
                System.getenv("YOUTUBE_CIPHER_USER_AGENT"),
                System.getenv("YOUTUBE_REMOTE_CIPHER_USER_AGENT"),
                youtubeConfig.getCipherUserAgent()
        );
        YoutubeSourceOptions options = new YoutubeSourceOptions()
                .setRemoteCipher(remoteCipherUrl, remoteCipherPassword, remoteCipherUserAgent);
        System.out.println("[NoRule] YouTube remote cipher server configured: " + remoteCipherUrl);
        return new YoutubeAudioSourceManager(options, clientArray);
    }

    private void configureYouTubePoToken() {
        String poToken = firstNonBlank(System.getenv("YOUTUBE_POTOKEN"), System.getenv("YOUTUBE_PO_TOKEN"));
        String visitorData = firstNonBlank(System.getenv("YOUTUBE_VISITOR_DATA"), System.getenv("YOUTUBE_VISITORDATA"));
        if (poToken == null || visitorData == null) {
            return;
        }
        Web.setPoTokenAndVisitorData(poToken, visitorData);
        System.out.println("[NoRule] YouTube poToken configured for WEB clients.");
    }

    private void configureYouTubeOauth(YoutubeAudioSourceManager youtubeSourceManager) {
        String refreshToken = getYouTubeOauthRefreshToken();
        if (refreshToken != null) {
            youtubeSourceManager.useOauth2(refreshToken, true);
            System.out.println("[NoRule] YouTube OAuth refresh token configured.");
            return;
        }
        if (isYouTubeOauthInitializationEnabled()) {
            youtubeSourceManager.useOauth2(null, false);
            System.out.println("[NoRule] YouTube OAuth initialization enabled. Follow the youtube-source login prompt and persist the refresh token.");
            watchYouTubeOauthRefreshToken(youtubeSourceManager);
        }
    }

    private boolean isYouTubeOauthConfigured() {
        return getYouTubeOauthRefreshToken() != null || isYouTubeOauthInitializationEnabled();
    }

    private String getYouTubeOauthRefreshToken() {
        return firstNonBlank(
                System.getenv("YOUTUBE_OAUTH_REFRESH_TOKEN"),
                System.getenv("YOUTUBE_REFRESH_TOKEN"),
                youtubeConfig.getOauthRefreshToken()
        );
    }

    private boolean isYouTubeOauthInitializationEnabled() {
        String envValue = firstNonBlank(System.getenv("YOUTUBE_OAUTH"));
        if (envValue != null) {
            return isTruthy(envValue);
        }
        return youtubeConfig.isOauthEnabled();
    }

    private void watchYouTubeOauthRefreshToken(YoutubeAudioSourceManager youtubeSourceManager) {
        Thread watcher = new Thread(() -> {
            String lastPrinted = "";
            long deadline = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
            while (System.currentTimeMillis() < deadline) {
                String token = youtubeSourceManager.getOauth2RefreshToken();
                if (token != null && !token.isBlank() && !token.equals(lastPrinted)) {
                    System.out.println("[NoRule] Set YOUTUBE_OAUTH_REFRESH_TOKEN=" + token + " for future starts.");
                    lastPrinted = token;
                    return;
                }
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            System.out.println("[NoRule] YouTube OAuth refresh token was not received within 10 minutes.");
        }, "youtube-oauth-token-watcher");
        watcher.setDaemon(true);
        watcher.start();
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
            }
        }
    }

    public GuildMusicManager getGuildMusicManager(Guild guild) {
        return musicManagers.computeIfAbsent(guild.getIdLong(), id -> {
            GuildMusicManager manager = new GuildMusicManager(
                    playerManager,
                    () -> notifyStateChanged(id),
                    endedTrack -> handleQueueExhausted(id, endedTrack),
                    startedTrack -> handleTrackStarted(id, startedTrack),
                    finishedTrack -> handleTrackFinished(id, finishedTrack),
                    (track, exception) -> handleTrackException(id, track, exception)
            );
            manager.getPlayer().setVolume(musicDataService.getVolume(id));
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
        ResolvedInput resolvedInput = resolveInput(input);
        String identifier = resolvedInput.isUrl ? resolvedInput.identifier : YT_SEARCH_PREFIX + resolvedInput.identifier;
        load(guildMusicManager, messageSender, input, identifier, resolvedInput.sourceLabel, true, requesterId, requesterName, 0);
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
                onError.accept(exception.getMessage());
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
        load(guildMusicManager, messageSender, identifier, identifier, sourceLabel, false, requesterId, requesterName, 0);
    }

    private void load(GuildMusicManager guildMusicManager,
                      Consumer<String> messageSender,
                      String userInput,
                      String identifier,
                      String sourceLabel,
                      boolean allowFallback,
                      Long requesterId,
                      String requesterName,
                      int spotifyRateLimitRetryAttempt) {
        String spotifyRateLimitKey = spotifyRateLimitKey(userInput);
        if (spotifyRateLimitKey != null) {
            Long limitedUntil = spotifyRateLimitUntilByKey.get(spotifyRateLimitKey);
            long now = System.currentTimeMillis();
            if (limitedUntil != null && limitedUntil > now) {
                messageSender.accept("LOAD_FAILED:Spotify API rate-limited. Please retry shortly.");
                return;
            }
        }
        playerManager.loadItemOrdered(guildMusicManager, identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                applyTrackMetadata(track, sourceLabel, requesterId, requesterName);
                guildMusicManager.getScheduler().queue(track);
                messageSender.accept(track.getInfo().title);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (playlist.getTracks().isEmpty()) {
                    messageSender.accept("NO_MATCH");
                    return;
                }
                AudioTrack firstTrack = playlist.getSelectedTrack() != null
                        ? playlist.getSelectedTrack()
                        : playlist.getTracks().get(0);
                applyTrackMetadata(firstTrack, sourceLabel, requesterId, requesterName);
                guildMusicManager.getScheduler().queue(firstTrack);
                messageSender.accept(firstTrack.getInfo().title);
            }

            @Override
            public void noMatches() {
                messageSender.accept("NO_MATCH");
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                logLoadFailureDetails("queue/load", userInput, identifier, exception);
                if (spotifyRateLimitKey != null && isSpotifyRateLimited(exception)) {
                    spotifyRateLimitUntilByKey.put(spotifyRateLimitKey, System.currentTimeMillis() + SPOTIFY_RATE_LIMIT_COOLDOWN_MS);
                    if (spotifyRateLimitRetryAttempt < SPOTIFY_RATE_LIMIT_RETRY_DELAYS_MS.length) {
                        long delayMs = SPOTIFY_RATE_LIMIT_RETRY_DELAYS_MS[spotifyRateLimitRetryAttempt];
                        CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS).execute(() ->
                                load(guildMusicManager, messageSender, userInput, identifier, sourceLabel, allowFallback, requesterId, requesterName, spotifyRateLimitRetryAttempt + 1)
                        );
                        return;
                    }
                }
                if (allowFallback && looksLikeYouTubeUrl(userInput)) {
                    String fallbackIdentifier = YT_SEARCH_PREFIX + userInput;
                    load(guildMusicManager, messageSender, userInput, fallbackIdentifier, sourceLabel, false, requesterId, requesterName, 0);
                    return;
                }
                messageSender.accept("LOAD_FAILED:" + exception.getMessage());
            }
        });
    }

    private void logLoadFailureDetails(String context, String userInput, String identifier, FriendlyException exception) {
        if (exception == null) {
            System.out.println("[NoRule] Load failed: context=" + context + " input=" + userInput + " identifier=" + identifier + " (no exception)");
            return;
        }
        String root = exception.getMessage();
        Throwable cause = exception.getCause();
        while (cause != null) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                root = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            } else {
                root = cause.getClass().getSimpleName();
            }
            cause = cause.getCause();
        }
        System.out.println("[NoRule] Load failed: context=" + context
                + " input=" + userInput
                + " identifier=" + identifier
                + " message=" + exception.getMessage()
                + " rootCause=" + root);
    }

    private String spotifyRateLimitKey(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (!looksLikeSpotifyUrl(trimmed)) {
            return null;
        }
        int queryIndex = trimmed.indexOf('?');
        return queryIndex >= 0 ? trimmed.substring(0, queryIndex) : trimmed;
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

    public void skip(Guild guild) {
        getGuildMusicManager(guild).getScheduler().nextTrack();
    }

    public void stop(Guild guild) {
        GuildMusicManager manager = getGuildMusicManager(guild);
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
        TrackRequestContext context = readContext(track);
        return context == null ? "youtube" : context.sourceLabel;
    }

    public String getCurrentRequesterDisplay(Guild guild) {
        AudioTrack track = getCurrentTrack(guild);
        if (track == null) {
            return "-";
        }
        TrackRequestContext context = readContext(track);
        if (context == null) {
            return "-";
        }
        if (context.requesterId != null) {
            return "<@" + context.requesterId + ">";
        }
        return context.requesterName == null || context.requesterName.isBlank() ? "-" : context.requesterName;
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
        ResolvedInput resolvedInput = resolveInput(trimmed);
        String identifier = resolvedInput.isUrl ? resolvedInput.identifier : YT_SEARCH_PREFIX + resolvedInput.identifier;
        playerManager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                applyTrackMetadata(track, resolvedInput.sourceLabel, requesterId, requesterName);
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
                applyTrackMetadata(track, resolvedInput.sourceLabel, requesterId, requesterName);
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
                onError.accept(exception == null || exception.getMessage() == null ? "-" : exception.getMessage().trim());
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
        long playedMillis = duration > 0L ? Math.min(duration, position) : position;
        musicDataService.recordTrackFinished(guildId, playedMillis);
    }

    private void handleTrackException(long guildId, AudioTrack track, Throwable exception) {
        String msg = exception == null || exception.getMessage() == null ? "-" : exception.getMessage().trim();
        setAutoplayNotice(guildId, "LOAD_FAILED:" + msg);
        BiConsumer<Long, PlaybackFailure> listener = playbackFailureListener;
        if (listener != null) {
            String title = track == null || track.getInfo() == null || track.getInfo().title == null ? "-" : track.getInfo().title;
            listener.accept(guildId, new PlaybackFailure(title, msg));
        }
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
                String msg = exception == null || exception.getMessage() == null ? "-" : exception.getMessage().trim();
                setAutoplayNotice(guildId, "LOAD_FAILED:" + msg);
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
        applyTrackMetadata(track, "autoplay", null, "AutoPlay");
        clearAutoplayNotice(guildId);
        guildMusicManager.getScheduler().queue(track);
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
        if (track == null) {
            return;
        }
        track.setUserData(new TrackRequestContext(
                normalizeSourceLabel(sourceLabel),
                requesterId,
                requesterName == null ? "" : requesterName.trim()
        ));
    }

    private TrackRequestContext readContext(AudioTrack track) {
        Object userData = track.getUserData();
        if (userData instanceof TrackRequestContext) {
            return (TrackRequestContext) userData;
        }
        if (userData instanceof String legacySource) {
            return new TrackRequestContext(normalizeSourceLabel(legacySource), null, "");
        }
        return null;
    }

    private String normalizeSourceLabel(String sourceLabel) {
        return sourceLabel == null || sourceLabel.isBlank() ? "youtube" : sourceLabel;
    }

    private MusicDataService.PlaybackEntry snapshotTrack(AudioTrack track) {
        if (track == null || track.getInfo() == null) {
            return null;
        }
        AudioTrackInfo info = track.getInfo();
        TrackRequestContext context = readContext(track);
        String source = context == null ? normalizeSourceLabel(null) : context.sourceLabel;
        Long requesterId = context == null ? null : context.requesterId;
        String requesterName = context == null ? "" : context.requesterName;
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

    private ResolvedInput resolveInput(String input) {
        String trimmed = input.trim();
        if (!looksLikeUrl(trimmed)) {
            return new ResolvedInput(trimmed, false, "youtube");
        }
        if (looksLikeSpotifyUrl(trimmed)) {
            if (spotifySourceEnabled) {
                return new ResolvedInput(trimmed, true, "spotify");
            }
            if (looksLikeSpotifyTrackUrl(trimmed)) {
                String keyword = resolveSpotifyToSearch(trimmed);
                if (!keyword.isBlank()) {
                    return new ResolvedInput(keyword, false, "spotify");
                }
            }
            // Avoid mismatched songs for playlists/albums/artists when Spotify source is unavailable.
            return new ResolvedInput(trimmed, true, "spotify");
        }
        if (looksLikeSoundCloudUrl(trimmed)) {
            return new ResolvedInput(trimmed, true, "soundcloud");
        }
        if (looksLikeYouTubeUrl(trimmed)) {
            return new ResolvedInput(normalizeYouTubePlaybackUrl(trimmed), true, "youtube");
        }
        return new ResolvedInput(trimmed, true, "url");
    }

    private String normalizeYouTubePlaybackUrl(String url) {
        if (url == null || url.isBlank()) {
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

    private boolean looksLikeUrl(String text) {
        return text.startsWith("http://") || text.startsWith("https://");
    }

    private boolean looksLikeYouTubeUrl(String text) {
        String lower = text.toLowerCase();
        return lower.contains("youtube.com") || lower.contains("youtu.be");
    }

    private boolean looksLikeSpotifyUrl(String text) {
        String lower = text.toLowerCase();
        return lower.contains("open.spotify.com/") || lower.startsWith("spotify:");
    }

    private boolean looksLikeSpotifyTrackUrl(String text) {
        String lower = text.toLowerCase();
        return lower.contains("open.spotify.com/track/")
                || lower.contains("open.spotify.com/intl-") && lower.contains("/track/")
                || lower.startsWith("spotify:track:");
    }

    private boolean looksLikeSoundCloudUrl(String text) {
        String lower = text.toLowerCase();
        return lower.contains("soundcloud.com/");
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

    private static class TrackRequestContext {
        private final String sourceLabel;
        private final Long requesterId;
        private final String requesterName;

        private TrackRequestContext(String sourceLabel, Long requesterId, String requesterName) {
            this.sourceLabel = sourceLabel;
            this.requesterId = requesterId;
            this.requesterName = requesterName;
        }
    }
}


