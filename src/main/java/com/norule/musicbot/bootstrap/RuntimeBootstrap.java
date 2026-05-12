package com.norule.musicbot.bootstrap;

import com.norule.musicbot.config.*;
import com.norule.musicbot.config.domain.GuildDomainConfigAdapter;
import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.config.domain.MinecraftStatusConfig;
import com.norule.musicbot.config.domain.RuntimeConfigSnapshot;
import com.norule.musicbot.config.domain.ShortUrlConfig;
import com.norule.musicbot.config.domain.StatsConfig;
import com.norule.musicbot.config.loader.ConfigLoader;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.discord.bot.gateway.WordChainMessageListener;
import com.norule.musicbot.discord.bot.gateway.listener.*;
import com.norule.musicbot.discord.bot.gateway.wordchain.DictionaryApiHttpGateway;
import com.norule.musicbot.discord.bot.ops.wordchain.WordChainOps;
import com.norule.musicbot.discord.bot.service.cache.*;
import com.norule.musicbot.discord.bot.service.wordchain.DictionaryApiService;
import com.norule.musicbot.discord.bot.service.wordchain.WordChainService;
import com.norule.musicbot.discord.bot.service.wordchain.WordChainStateRepository;
import com.norule.musicbot.discord.gateway.InMemorySignals;
import com.norule.musicbot.discord.gateway.Signals;
import com.norule.musicbot.discord.gateway.signals.TicketClosedSignal;
import com.norule.musicbot.discord.gateway.signals.TrackLoadFailedSignal;
import com.norule.musicbot.domain.stats.MessageStatsService;
import com.norule.musicbot.domain.stats.MySqlMessageStatsRepository;
import com.norule.musicbot.domain.stats.SqliteMessageStatsRepository;
import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.infra.WebSettings;
import com.norule.musicbot.shorturl.MySqlShortUrlRepository;
import com.norule.musicbot.shorturl.ShortUrlRepository;
import com.norule.musicbot.shorturl.SqliteShortUrlRepository;
import com.norule.musicbot.shorturl.infra.ShortUrlGatewayServer;
import com.norule.musicbot.storage.sqlite.GuildSettingsSqliteRepository;
import com.norule.musicbot.storage.sqlite.HoneypotSqliteRepository;
import com.norule.musicbot.storage.sqlite.ModerationSqliteRepository;
import com.norule.musicbot.storage.sqlite.SqliteDatabase;
import com.norule.musicbot.storage.sqlite.TicketSqliteRepository;
import com.norule.musicbot.storage.sqlite.SqliteDuplicateMessageCacheRepository;
import com.norule.musicbot.storage.sqlite.SqliteMessageLogCacheRepository;
import com.norule.musicbot.storage.sqlite.SqlitePrivateRoomCacheRepository;
import com.norule.musicbot.storage.mysql.MySqlDuplicateMessageCacheRepository;
import com.norule.musicbot.storage.mysql.MySqlMessageLogCacheRepository;
import com.norule.musicbot.storage.mysql.MySqlPrivateRoomCacheRepository;

import com.norule.musicbot.*;

import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;
import org.yaml.snakeyaml.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RuntimeBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeBootstrap.class);
    private static final DateTimeFormatter LOG_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter CONSOLE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String STORAGE_SQLITE = "sqlite";
    private static final String COMMAND_CLEAR = "clear";
    private static final String CLEAR_TARGET_MESSAGE_LOG = "message_log";
    private static final String CLEAR_TARGET_MESSAGE_LOG_CACHE = "message_log_cache";
    private static final String CLEAR_TARGET_PLAY_HISTORY = "play_history";
    private static final String CLEAR_TARGET_GUILD_COMMANDS = "guild_commands";
    private static final String REGEX_CONTROL_WITHOUT_NEWLINES = "[\\p{Cntrl}&&[^\\r\\n\\t]]";
    private static final String REGEX_ANSI_ESCAPE = "\u001B\\[[;\\d]*[ -/]*[@-~]";
    private static final String ENV_MYSQL_JDBC_URL = "MYSQL_JDBC_URL";
    private static final String ENV_MYSQL_USER = "MYSQL_USER";
    private static final String ENV_MYSQL_PASSWORD = "MYSQL_PASSWORD";
    private static final String ENV_MYSQL_POOL_SIZE = "MYSQL_POOL_SIZE";
    private static final Set<String> SHUTDOWN_COMMANDS = Set.of("stop", "end");
    private static final Set<String> RELOAD_COMMANDS = Set.of("reload");
    private static final Set<String> HELP_COMMANDS = Set.of("help", "?", "h");
    private static final long DEFAULT_CLEAR_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final Pattern RETENTION_SEGMENT_PATTERN = Pattern.compile("(\\d+)([dhms])");
    private static final AtomicBoolean SHUTDOWN_STARTED = new AtomicBoolean(false);
    private static final AtomicReference<ScheduledExecutorService> PRESENCE_ROTATION = new AtomicReference<>();
    private static final AtomicReference<BotConfig> RUNTIME_CONFIG = new AtomicReference<>();
    private static final AtomicReference<RuntimeConfigSnapshot> RUNTIME_SNAPSHOT = new AtomicReference<>();
    private static final AtomicReference<WebControlServer> WEB_SERVER = new AtomicReference<>();
    private static final AtomicReference<ShortUrlGatewayServer> SHORT_URL_GATEWAY_SERVER = new AtomicReference<>();
    private static final AtomicReference<PrintStream> CONSOLE_FILE_STREAM = new AtomicReference<>();
    private static final AtomicReference<I18nService> I18N_SERVICE = new AtomicReference<>();
    private static final AtomicReference<TicketService> TICKET_SERVICE = new AtomicReference<>();
    private static final AtomicReference<ShortUrlService> SHORT_URL_SERVICE = new AtomicReference<>();
    private static final AtomicReference<MessageLogCacheRepository> MESSAGE_LOG_CACHE_REPOSITORY = new AtomicReference<>();
    private static final AtomicReference<DuplicateMessageCacheRepository> DUPLICATE_MESSAGE_CACHE_REPOSITORY = new AtomicReference<>();
    private static final AtomicReference<PrivateRoomCacheRepository> PRIVATE_ROOM_CACHE_REPOSITORY = new AtomicReference<>();
    private record ActivityTemplate(String type, String text) {}
    private record ClearCommand(String target, long retentionMillis) {}
    private record ConsoleRuntimeContext(
            JDA jda,
            I18nService i18nService,
            String language,
            Path configPath,
            MusicCommandListener musicCommandListener,
            MusicPlayerService playerService,
            GuildSettingsService guildSettingsService,
            GuildDomainConfigAdapter guildConfigAdapter,
            ModerationService moderationService,
            HoneypotService honeypotService,
            TicketService ticketService
    ) {}

    public void run() {
        Path configPath = resolveConfigPath();
        Path baseDir = resolveBaseDir(configPath);
        ConfigLoader configLoader = new ConfigLoader();
        BotConfig config = configLoader.load(configPath);
        installConsoleFileLogging(baseDir, config.getLogDir());
        RUNTIME_CONFIG.set(config);
        RuntimeConfigSnapshot runtimeSnapshot = RuntimeConfigSnapshot.from(config, baseDir);
        RUNTIME_SNAPSHOT.set(runtimeSnapshot);
        String token = config.getToken();
        Path guildSettingsPath = resolveDataPath(baseDir, config.getGuildSettingsDir());
        Path languagePath = resolveDataPath(baseDir, config.getLanguageDir());
        Path musicDataPath = resolveDataPath(baseDir, config.getMusicDataDir());
        Path ticketDataPath = resolveDataPath(baseDir, config.getTicketDataDir());
        Path ticketTranscriptPath = resolveDataPath(baseDir, config.getTicketTranscriptDir());
        Path honeypotDataPath = resolveDataPath(baseDir, config.getHoneypotDataDir());
        BotConfig.DataPaths.DataDatabase dataDatabaseConfig = config.getDataDatabase();
        Path sharedSqlitePath = resolveSharedSqlitePath(baseDir, dataDatabaseConfig);
        SqliteDatabase sharedSqliteDatabase = createSharedSqliteDatabase(sharedSqlitePath);
        StatsConfig statsConfig = new StatsConfig(config.getStats());
        Path musicSqlitePath = resolveMusicSqlitePath(sharedSqlitePath, statsConfig, baseDir);

        GuildSettingsSqliteRepository guildSettingsSqliteRepository = sharedSqliteDatabase == null ? null : new GuildSettingsSqliteRepository(sharedSqliteDatabase);
        GuildSettingsService guildSettingsService =
                new GuildSettingsService(guildSettingsPath, config, guildSettingsSqliteRepository);
        GuildDomainConfigAdapter guildConfigAdapter = new GuildDomainConfigAdapter(guildSettingsService, config.getMusic());
        MusicConfig globalMusicConfig = new MusicConfig(config.getMusic(), config.getMusic());
        MusicPlayerService playerService = new MusicPlayerService(
                musicDataPath,
                guildConfigAdapter::getMusicHistoryLimit,
                guildConfigAdapter::getMusicStatsRetentionDays,
                guildConfigAdapter::getMusicPlaylistTrackLimit,
                globalMusicConfig,
                musicSqlitePath
        );
        Path moderationDataPath = resolveDataPath(baseDir, config.getModerationDataDir());
        ModerationSqliteRepository moderationSqliteRepository = sharedSqliteDatabase == null ? null : new ModerationSqliteRepository(sharedSqliteDatabase);
        ModerationService moderationService = new ModerationService(moderationDataPath, moderationSqliteRepository);
        WordChainService wordChainService = new WordChainService(
                new WordChainStateRepository(moderationDataPath.resolve("wordchain")),
                new DictionaryApiService(new DictionaryApiHttpGateway())
        );
        WordChainOps wordChainOps = new WordChainOps(wordChainService);
        HoneypotSqliteRepository honeypotSqliteRepository = sharedSqliteDatabase == null ? null : new HoneypotSqliteRepository(sharedSqliteDatabase);
        TicketSqliteRepository ticketSqliteRepository = sharedSqliteDatabase == null ? null : new TicketSqliteRepository(sharedSqliteDatabase);
        HoneypotService honeypotService = new HoneypotService(honeypotDataPath, honeypotSqliteRepository);
        TicketService ticketService = new TicketService(ticketDataPath, ticketTranscriptPath, ticketSqliteRepository);
        ShortUrlService shortUrlService = createShortUrlService(config, baseDir);
        TICKET_SERVICE.set(ticketService);
        SHORT_URL_SERVICE.set(shortUrlService);
        MessageStatsListener messageStatsListener = createMessageStatsListener(statsConfig, config, baseDir);
        MessageLogCacheRepository messageLogCacheRepository = createMessageLogCacheRepository(statsConfig, baseDir);
        DuplicateMessageCacheRepository duplicateMessageCacheRepository = createDuplicateMessageCacheRepository(statsConfig, baseDir);
        PrivateRoomCacheRepository privateRoomCacheRepository = createPrivateRoomCacheRepository(statsConfig, baseDir);
        MESSAGE_LOG_CACHE_REPOSITORY.set(messageLogCacheRepository);
        DUPLICATE_MESSAGE_CACHE_REPOSITORY.set(duplicateMessageCacheRepository);
        PRIVATE_ROOM_CACHE_REPOSITORY.set(privateRoomCacheRepository);
        I18nService i18nService = I18nService.load(languagePath, config.getDefaultLanguage());
        I18N_SERVICE.set(i18nService);
        Signals signals = new InMemorySignals();
        signals.on(TrackLoadFailedSignal.class, event -> logInfo(
                "[NoRule] signal.track-load-failed guild=" + event.guildId() + " title=" + event.title()
        ));
        signals.on(TicketClosedSignal.class, event -> logInfo(
                "[NoRule] signal.ticket-closed guild=" + event.guildId() + " channel=" + event.channelId()
                        + " auto=" + event.autoClosed()
        ));
        MusicCommandListener musicCommandListener = new MusicCommandListener(
                playerService,
                runtimeSnapshot,
                guildSettingsService,
                moderationService,
                honeypotService,
                signals,
                shortUrlService,
                ticketService,
                messageStatsListener == null ? null : messageStatsListener.service(),
                wordChainOps
        );

        AudioModuleConfig audioConfig = new AudioModuleConfig()
                .withDaveSessionFactory(new LDJDADaveSessionFactory(new NativeDaveFactory()));

        JDABuilder builder = JDABuilder.createDefault(
                        token,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.DIRECT_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.GUILD_MODERATION
                )
                .setAudioModuleConfig(audioConfig)
                .setMemberCachePolicy(MemberCachePolicy.VOICE.or(MemberCachePolicy.OWNER))
                .setChunkingFilter(ChunkingFilter.NONE)
                .disableCache(CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                .setStatus(parseOnlineStatus(config.getBotProfile().getPresenceStatus()))
                .setActivity(null)
                .addEventListeners(
                        musicCommandListener,
                        new NotificationListener(guildSettingsService, i18nService),
                        new MessageLogListener(guildSettingsService, i18nService, messageLogCacheRepository),
                        new ServerLogListener(guildSettingsService, i18nService, moderationService),
                        new DuplicateMessageListener(guildSettingsService, moderationService, i18nService, duplicateMessageCacheRepository),
                        new HoneypotListener(honeypotService, guildSettingsService),
                        new NumberChainListener(guildSettingsService, moderationService, i18nService,
                                () -> {
                                    RuntimeConfigSnapshot snapshot = RUNTIME_SNAPSHOT.get();
                                    return snapshot == null ? 500L : snapshot.getNumberChainReactionDelayMillis();
                                }),
                        new WordChainMessageListener(wordChainOps),
                        new VoiceAutoLeaveListener(guildSettingsService, playerService, i18nService),
                        new PrivateRoomListener(guildSettingsService, i18nService, privateRoomCacheRepository)
                );
        if (messageStatsListener != null) {
            builder.addEventListeners(messageStatsListener);
        }

        JDA jda = builder.build();
        installConsoleShutdownListener(new ConsoleRuntimeContext(
                jda,
                i18nService,
                config.getDefaultLanguage(),
                configPath,
                musicCommandListener,
                playerService,
                guildSettingsService,
                guildConfigAdapter,
                moderationService,
                honeypotService,
                ticketService
        ));
        installShutdownHook(jda, i18nService, config.getDefaultLanguage());

        try {
            jda.awaitReady();
            startActivityRotation(jda, config.getBotProfile());
            syncWebServer(jda, playerService, guildSettingsService, moderationService, TICKET_SERVICE.get(), SHORT_URL_SERVICE.get(), I18N_SERVICE.get());
            syncShortUrlGateway();
            logLifecycleMessage(i18nService, config.getDefaultLanguage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static OnlineStatus parseOnlineStatus(String raw) {
        if (raw == null) {
            return OnlineStatus.ONLINE;
        }
        return switch (raw.trim().toUpperCase()) {
            case "IDLE" -> OnlineStatus.IDLE;
            case "DND", "DO_NOT_DISTURB" -> OnlineStatus.DO_NOT_DISTURB;
            case "INVISIBLE", "OFFLINE" -> OnlineStatus.INVISIBLE;
            default -> OnlineStatus.ONLINE;
        };
    }

    private static Activity buildActivity(String typeRaw, String textRaw) {
        String text = textRaw == null ? "" : textRaw.trim();
        if (text.isBlank()) {
            return null;
        }
        String type = typeRaw == null ? "PLAYING" : typeRaw.trim().toUpperCase();
        return switch (type) {
            case "LISTENING" -> Activity.listening(text);
            case "WATCHING" -> Activity.watching(text);
            case "COMPETING" -> Activity.competing(text);
            case "CUSTOM", "CUSTOM_STATUS" -> Activity.customStatus(text);
            default -> Activity.playing(text);
        };
    }

    private static List<ActivityTemplate> resolveActivityTemplates(BotConfig.BotProfile profile) {
        List<ActivityTemplate> templates = new ArrayList<>();

        List<String> configuredList = profile.getActivities();
        if (configuredList != null && !configuredList.isEmpty()) {
            for (String entry : configuredList) {
                addActivityTemplatesFromEntry(templates, profile.getActivityType(), entry);
            }
        }

        if (!templates.isEmpty()) {
            return templates;
        }

        String raw = profile.getActivityText();
        if (raw == null || raw.isBlank()) {
            return templates;
        }
        addActivityTemplatesFromEntry(templates, profile.getActivityType(), raw);
        return templates;
    }

    private static void addActivityTemplatesFromEntry(List<ActivityTemplate> templates, String defaultType, String rawEntry) {
        if (rawEntry == null || rawEntry.isBlank()) {
            return;
        }
        for (String part : rawEntry.split("\\|\\|")) {
            String entry = part.trim();
            if (entry.isBlank()) {
                continue;
            }
            String type = defaultType;
            String text = entry;
            int sep = entry.indexOf('|');
            if (sep > 0 && sep < entry.length() - 1) {
                type = entry.substring(0, sep).trim();
                text = entry.substring(sep + 1).trim();
            }
            if (!text.isBlank()) {
                templates.add(new ActivityTemplate(type, text));
            }
        }
    }

    private static Activity buildActivityFromTemplate(JDA jda, ActivityTemplate template) {
        if (template == null) {
            return null;
        }
        String resolvedText = applyPresencePlaceholders(jda, template.text());
        return buildActivity(template.type(), resolvedText);
    }

    private static String applyPresencePlaceholders(JDA jda, String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int guildCount = 0;
        long totalMemberCount = 0L;
        if (jda != null) {
            guildCount = jda.getGuilds().size();
            for (var guild : jda.getGuilds()) {
                totalMemberCount += Math.max(0, guild.getMemberCount());
            }
        }
        return text
                .replace("{guildCount}", String.valueOf(guildCount))
                .replace("{serverCount}", String.valueOf(guildCount))
                .replace("{memberCount}", String.valueOf(totalMemberCount))
                .replace("{totalMemberCount}", String.valueOf(totalMemberCount));
    }

    private static void startActivityRotation(JDA jda, BotConfig.BotProfile profile) {
        ScheduledExecutorService existing = PRESENCE_ROTATION.getAndSet(null);
        if (existing != null) {
            existing.shutdownNow();
        }

        jda.getPresence().setStatus(parseOnlineStatus(profile.getPresenceStatus()));
        List<ActivityTemplate> templates = resolveActivityTemplates(profile);

        if (templates.isEmpty()) {
            jda.getPresence().setActivity(null);
            return;
        }
        jda.getPresence().setActivity(buildActivityFromTemplate(jda, templates.get(0)));
        if (templates.size() < 2) {
            return;
        }

        ScheduledExecutorService rotation = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PresenceRotation");
            t.setDaemon(true);
            return t;
        });
        PRESENCE_ROTATION.set(rotation);
        int rotationSeconds = Math.max(5, profile.getActivityRotationSeconds());
        rotation.scheduleAtFixedRate(new Runnable() {
            private int index = 1;

            @Override
            public void run() {
                try {
                    Activity activity = buildActivityFromTemplate(jda, templates.get(index));
                    jda.getPresence().setActivity(activity);
                    index = (index + 1) % templates.size();
                } catch (Exception ignored) {
                    logDebug("[NoRule] Presence rotation update skipped due to transient error.");
                }
            }
        }, rotationSeconds, rotationSeconds, TimeUnit.SECONDS);
    }

    private static void installConsoleShutdownListener(ConsoleRuntimeContext context) {
        Thread listener = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                logInfo("[NoRule] Console commands ready: help | reload | stop | clear message_log [t:7d] | clear play_history [t:7d] | clear guild_commands");
                String line;
                while ((line = reader.readLine()) != null) {
                    String command = normalizeConsoleCommand(line);
                    if (!command.isBlank()) {
                        boolean shutdownRequested = SHUTDOWN_COMMANDS.contains(command);
                        handleConsoleCommand(command, context);
                        if (shutdownRequested) {
                            break;
                        }
                    }
                }
                logInfo("[NoRule] Console command listener stopped (stdin closed).");
            } catch (Exception exception) {
                logWarn("[NoRule] Console command listener failed: " + exception.getMessage(), exception);
            }
        }, "ConsoleShutdownListener");
        listener.setDaemon(true);
        listener.start();
    }

    private static void handleConsoleCommand(String command, ConsoleRuntimeContext context) {
        logInfo("[NoRule] Console command received: " + command);
        if (SHUTDOWN_COMMANDS.contains(command)) {
            logInfo("[NoRule] Shutdown command received: " + command);
            requestShutdown(context.jda(), context.i18nService(), context.language(), true);
            return;
        }
        if (RELOAD_COMMANDS.contains(command)) {
            reloadRuntimeConfig(context);
            return;
        }
        if (HELP_COMMANDS.contains(command)) {
            printConsoleHelp();
            return;
        }
        if (handleClearCommand(command, context)) {
            return;
        }
        logInfo("[NoRule] Unknown console command: " + command);
    }

    private static String normalizeConsoleCommand(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace('\uFEFF', ' ')
                .replaceAll(REGEX_CONTROL_WITHOUT_NEWLINES, " ")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private static void printConsoleHelp() {
        logInfo("[NoRule] Console command help:");
        logInfo("  help                              Show this help");
        logInfo("  reload                            Reload config/runtime state");
        logInfo("  stop                              Shutdown bot process");
        logInfo("  clear message_log [t:12d34m56s]   Prune old message_log_cache rows (default t:7d)");
        logInfo("  clear play_history [t:12d34m56s]  Prune old play_history rows (default t:7d)");
        logInfo("  clear guild_commands              Clear stale guild slash commands throttled");
    }

    private static boolean handleClearCommand(String command, ConsoleRuntimeContext context) {
        ClearCommand parsed = parseClearCommand(command);
        if (parsed == null) {
            return false;
        }
        if (parsed.target().startsWith("__")) {
            return true;
        }
        if (CLEAR_TARGET_GUILD_COMMANDS.equals(parsed.target())) {
            context.musicCommandListener().clearGuildCommandsThrottled();
            return true;
        }
        long cutoffMillis = System.currentTimeMillis() - parsed.retentionMillis();
        if (CLEAR_TARGET_MESSAGE_LOG.equals(parsed.target())) {
            MessageLogCacheRepository repository = MESSAGE_LOG_CACHE_REPOSITORY.get();
            if (repository == null) {
                logInfo("[NoRule] clear message_log skipped: repository unavailable");
                return true;
            }
            try {
                int removed = repository.pruneExpired(cutoffMillis);
                logInfo("[NoRule] clear message_log completed: removed=" + removed + ", retention=" + formatRetention(parsed.retentionMillis()));
            } catch (Exception e) {
                logWarn("[NoRule] clear message_log failed: " + e.getMessage(), e);
            }
            return true;
        }
        if (CLEAR_TARGET_PLAY_HISTORY.equals(parsed.target())) {
            try {
                int removed = context.playerService().clearPlayHistoryByRetentionMillis(parsed.retentionMillis());
                logInfo("[NoRule] clear play_history completed: removed=" + removed + ", retention=" + formatRetention(parsed.retentionMillis()));
            } catch (Exception e) {
                logWarn("[NoRule] clear play_history failed: " + e.getMessage(), e);
            }
            return true;
        }
        return false;
    }

    private static ClearCommand parseClearCommand(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String[] tokens = command.split(" ");
        if (tokens.length < 2 || !COMMAND_CLEAR.equals(tokens[0])) {
            return null;
        }
        String rawTarget = tokens[1];
        String target;
        if (CLEAR_TARGET_MESSAGE_LOG.equals(rawTarget) || CLEAR_TARGET_MESSAGE_LOG_CACHE.equals(rawTarget)) {
            target = CLEAR_TARGET_MESSAGE_LOG;
        } else if (CLEAR_TARGET_PLAY_HISTORY.equals(rawTarget)) {
            target = CLEAR_TARGET_PLAY_HISTORY;
        } else if (CLEAR_TARGET_GUILD_COMMANDS.equals(rawTarget)) {
            target = CLEAR_TARGET_GUILD_COMMANDS;
            return new ClearCommand(target, 0L);
        } else {
            logInfo("[NoRule] clear target unsupported: " + rawTarget + " (supported: message_log, play_history, guild_commands)");
            return new ClearCommand("__unsupported__", DEFAULT_CLEAR_RETENTION_MILLIS);
        }

        long retentionMillis = DEFAULT_CLEAR_RETENTION_MILLIS;
        for (int i = 2; i < tokens.length; i++) {
            String token = tokens[i];
            if (!token.startsWith("t:")) {
                logInfo("[NoRule] clear usage: clear message_log|play_history [t:12d34m56s]");
                return new ClearCommand("__invalid__", DEFAULT_CLEAR_RETENTION_MILLIS);
            }
            String spec = token.substring(2).trim();
            Long parsed = parseRetentionMillis(spec);
            if (parsed == null) {
                logInfo("[NoRule] invalid retention: " + spec + " (example: t:12d34m56s)");
                return new ClearCommand("__invalid__", DEFAULT_CLEAR_RETENTION_MILLIS);
            }
            retentionMillis = parsed;
        }
        return new ClearCommand(target, retentionMillis);
    }

    private static Long parseRetentionMillis(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        Matcher matcher = RETENTION_SEGMENT_PATTERN.matcher(normalized);
        long total = 0L;
        int consumed = 0;
        while (matcher.find()) {
            if (matcher.start() != consumed) {
                return null;
            }
            consumed = matcher.end();
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            long unitMillis = switch (matcher.group(2)) {
                case "d" -> 86_400_000L;
                case "h" -> 3_600_000L;
                case "m" -> 60_000L;
                case "s" -> 1_000L;
                default -> -1L;
            };
            if (unitMillis <= 0L) {
                return null;
            }
            try {
                total = Math.addExact(total, Math.multiplyExact(amount, unitMillis));
            } catch (ArithmeticException e) {
                return null;
            }
        }
        if (consumed != normalized.length()) {
            return null;
        }
        return Math.max(0L, total);
    }

    private static String formatRetention(long retentionMillis) {
        long seconds = Math.max(0L, retentionMillis / 1000L);
        long days = seconds / 86_400L;
        seconds %= 86_400L;
        long hours = seconds / 3_600L;
        seconds %= 3_600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        return days + "d" + hours + "h" + minutes + "m" + seconds + "s";
    }

    private static void reloadRuntimeConfig(ConsoleRuntimeContext context) {
        if (SHUTDOWN_STARTED.get()) {
            return;
        }
        try {
            Path baseDir = resolveBaseDir(context.configPath());
            BotConfig reloaded = new ConfigLoader().reload(context.configPath());
            RUNTIME_CONFIG.set(reloaded);
            RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.from(reloaded, baseDir);
            RUNTIME_SNAPSHOT.set(snapshot);
            context.guildConfigAdapter().replaceGlobalMusic(reloaded.getMusic());
            context.guildSettingsService().reloadAll(reloaded);
            context.moderationService().reloadAll();
            context.playerService().replaceGuildLimits(
                    context.guildConfigAdapter()::getMusicHistoryLimit,
                    context.guildConfigAdapter()::getMusicStatsRetentionDays,
                    context.guildConfigAdapter()::getMusicPlaylistTrackLimit
            );
            context.playerService().replaceGlobalMusicConfig(new MusicConfig(reloaded.getMusic(), reloaded.getMusic()));
            context.playerService().reloadData();
            context.honeypotService().reloadAll();
            if (context.ticketService() != null) {
                context.ticketService().reloadAll();
            }
            ShortUrlService shortUrlService = SHORT_URL_SERVICE.get();
            if (shortUrlService != null) {
                shortUrlService.updateOptions(new ShortUrlConfig(reloaded.getShortUrl()).toOptions());
            }
            context.musicCommandListener().reloadRuntimeConfig(snapshot);
            startActivityRotation(context.jda(), reloaded.getBotProfile());
            syncWebServer(
                    context.jda(),
                    context.playerService(),
                    context.guildSettingsService(),
                    context.moderationService(),
                    TICKET_SERVICE.get(),
                    SHORT_URL_SERVICE.get(),
                    I18N_SERVICE.get()
            );
            syncShortUrlGateway();
            logInfo("[NoRule] Config reloaded successfully.");
        } catch (Exception e) {
            logWarn("[NoRule] Config reload failed: " + e.getMessage(), e);
        }
    }

    private static void installShutdownHook(JDA jda, I18nService i18nService, String language) {
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> requestShutdown(jda, i18nService, language, false),
                "NoRuleShutdownHook"
        ));
    }

    private static void requestShutdown(JDA jda,
                                        I18nService i18nService,
                                        String language,
                                        boolean exitProcess) {
        if (!SHUTDOWN_STARTED.compareAndSet(false, true)) {
            if (exitProcess) {
                System.exit(0);
            }
            return;
        }

        logLifecycleMessage(i18nService, language, false);
        WebControlServer webServer = WEB_SERVER.getAndSet(null);
        if (webServer != null) {
            webServer.shutdown();
        }
        ShortUrlGatewayServer shortUrlGatewayServer = SHORT_URL_GATEWAY_SERVER.getAndSet(null);
        if (shortUrlGatewayServer != null) {
            shortUrlGatewayServer.shutdown();
        }
        ScheduledExecutorService rotation = PRESENCE_ROTATION.getAndSet(null);
        if (rotation != null) {
            rotation.shutdownNow();
        }
        closeQuietly(MESSAGE_LOG_CACHE_REPOSITORY.getAndSet(null), "message-log cache repository");
        closeQuietly(DUPLICATE_MESSAGE_CACHE_REPOSITORY.getAndSet(null), "duplicate-message cache repository");
        closeQuietly(PRIVATE_ROOM_CACHE_REPOSITORY.getAndSet(null), "private-room cache repository");
        closeQuietly(CONSOLE_FILE_STREAM.getAndSet(null), "console-file stream");
        jda.shutdown();

        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (jda.getStatus() == JDA.Status.SHUTDOWN) {
                if (exitProcess) {
                    System.exit(0);
                }
                return;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        jda.shutdownNow();
        if (exitProcess) {
            System.exit(0);
        }
    }

    private static void syncWebServer(JDA jda,
                                      MusicPlayerService playerService,
                                      GuildSettingsService guildSettingsService,
                                      ModerationService moderationService,
                                      TicketService ticketService,
                                      ShortUrlService shortUrlService,
                                      I18nService i18nService) {
        BotConfig runtime = RUNTIME_CONFIG.get();
        if (runtime == null || i18nService == null || ticketService == null || shortUrlService == null) {
            return;
        }
        if (!runtime.getWeb().isEnabled()) {
            WebControlServer existing = WEB_SERVER.getAndSet(null);
            if (existing != null) {
                existing.shutdown();
            }
            return;
        }
        WebControlServer server = WEB_SERVER.get();
        if (server == null) {
            server = new WebControlServer(
                    jda,
                    playerService,
                    guildSettingsService,
                    moderationService,
                    ticketService,
                    shortUrlService,
                    () -> {
                        BotConfig cfg = RUNTIME_CONFIG.get();
                        if (cfg == null) {
                            return new WebSettings(false, "0.0.0.0", 60000, "https://dash.example.com", 720, "", "", "",
                                    new WebSettings.WebSslSettings(false, "certs", "privkey.pem", "fullchain.pem", "web-keystore.p12", "", "PKCS12", ""));
                        }
                        BotConfig.Web web = cfg.getWeb();
                        BotConfig.Web.Ssl ssl = web.getSsl();
                        return new WebSettings(
                                web.isEnabled(),
                                web.getBindHost(),
                                web.getBindPort(),
                                web.getPublicBaseUrl(),
                                web.getSessionExpireMinutes(),
                                web.getDiscordClientId(),
                                web.getDiscordClientSecret(),
                                web.getDiscordRedirectUri(),
                                new WebSettings.WebSslSettings(
                                        ssl.isEnabled(),
                                        ssl.getCertDir(),
                                        ssl.getPrivateKeyFile(),
                                        ssl.getFullChainFile(),
                                        ssl.getKeyStoreFile(),
                                        ssl.getKeyStorePassword(),
                                        ssl.getKeyStoreType(),
                                        ssl.getKeyPassword()
                                )
                        );
                    },
                    () -> {
                        BotConfig cfg = RUNTIME_CONFIG.get();
                        return cfg == null
                                ? new MinecraftStatusConfig("NoRuleBot/1.0 contact: admin@norule.me", 15_000, 60)
                                : new MinecraftStatusConfig(cfg.getMinecraftStatus());
                    },
                    () -> {
                        BotConfig cfg = RUNTIME_CONFIG.get();
                        return cfg == null ? "lang" : cfg.getLanguageDir();
                    },
                    i18nService
            );
            WEB_SERVER.set(server);
        }
        server.syncWithConfig();
    }

    private static void logLifecycleMessage(I18nService i18nService, String language, boolean startup) {
        String key = startup ? "system.startup" : "system.shutdown";
        String fallback = startup ? "Bot started and is now online." : "Bot is shutting down.";
        String lang = language == null || language.isBlank() ? "en" : language;
        String message = i18nService.t(lang, key, Map.of());
        if (message == null || message.isBlank() || key.equals(message)) {
            message = fallback;
        }
        String output = "[NoRule] " + sanitizeConsoleMessage(message);
        logInfo(output);
    }

    private static String sanitizeConsoleMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        String sanitized = message
                .replace("\uFEFF", "")
                .replace("\u001B", "")
                .replace("\u009B", "")
                .replaceAll(REGEX_ANSI_ESCAPE, "")
                .replaceAll(REGEX_CONTROL_WITHOUT_NEWLINES, "");
        if (sanitized.isBlank()) {
            return "Bot lifecycle event.";
        }
        return sanitized.trim();
    }

    private static Path resolveConfigPath() {
        String customPath = System.getenv("BOT_CONFIG_PATH");
        if (customPath != null && !customPath.isBlank()) {
            return Path.of(customPath.trim()).toAbsolutePath().normalize();
        }

        Path currentDir = Path.of(".").toAbsolutePath().normalize();
        Path cwdConfig = currentDir.resolve("config.yml").toAbsolutePath().normalize();
        Path parentConfig = null;
        if (isTargetDir(currentDir) && currentDir.getParent() != null) {
            parentConfig = currentDir.getParent().resolve("config.yml").toAbsolutePath().normalize();
        }

        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(cwdConfig);
        if (parentConfig != null) {
            candidates.add(parentConfig);
        }

        Path withToken = firstConfigWithToken(new ArrayList<>(candidates));
        if (withToken != null) {
            return withToken;
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        return cwdConfig;
    }

    private static MessageStatsListener createMessageStatsListener(StatsConfig stats, BotConfig config, Path baseDir) {
        try {
            MessageStatsService service;
            if (STORAGE_SQLITE.equalsIgnoreCase(stats.getStorage())) {
                Path sqlitePath = resolveDataPath(baseDir, stats.getSqlite().getPath());
                SqliteMessageStatsRepository repository = new SqliteMessageStatsRepository(sqlitePath);
                service = new MessageStatsService(repository);
                logInfo("[NoRule] Message stats storage: sqlite (" + sqlitePath + ")");
            } else {
                StatsConfig.Mysql mysql = stats.getMysql();
                String jdbcUrl = getEnvOrDefault(ENV_MYSQL_JDBC_URL, mysql.getJdbcUrl());
                String username = getEnvOrDefault(ENV_MYSQL_USER, mysql.getUsername());
                String password = getEnvOrDefault(ENV_MYSQL_PASSWORD, mysql.getPassword());
                int poolSize = parseIntEnv(ENV_MYSQL_POOL_SIZE, mysql.getPoolSize());
                MySqlMessageStatsRepository repository = new MySqlMessageStatsRepository(jdbcUrl, username, password, poolSize);
                service = new MessageStatsService(repository);
                logInfo("[NoRule] Message stats storage: mysql");
            }
            return new MessageStatsListener(service);
        } catch (Exception e) {
            logWarn("[NoRule] Message stats disabled: " + e.getMessage(), e);
            if (config.isDebug()) {
                logDebug("[NoRule] Message stats bootstrap error stacktrace", e);
            }
            return null;
        }
    }

    private static MessageLogCacheRepository createMessageLogCacheRepository(StatsConfig stats, Path baseDir) {
        if (STORAGE_SQLITE.equalsIgnoreCase(stats.getStorage())) {
            Path sqlitePath = resolveDataPath(baseDir, stats.getSqlite().getPath());
            logInfo("[NoRule] Message log cache storage: sqlite (" + sqlitePath + ")");
            return new SqliteMessageLogCacheRepository(sqlitePath);
        }
        StatsConfig.Mysql mysql = stats.getMysql();
        String jdbcUrl = getEnvOrDefault(ENV_MYSQL_JDBC_URL, mysql.getJdbcUrl());
        String username = getEnvOrDefault(ENV_MYSQL_USER, mysql.getUsername());
        String password = getEnvOrDefault(ENV_MYSQL_PASSWORD, mysql.getPassword());
        int poolSize = parseIntEnv(ENV_MYSQL_POOL_SIZE, mysql.getPoolSize());
        logInfo("[NoRule] Message log cache storage: mysql");
        return new MySqlMessageLogCacheRepository(jdbcUrl, username, password, poolSize);
    }

    private static DuplicateMessageCacheRepository createDuplicateMessageCacheRepository(StatsConfig stats, Path baseDir) {
        if (STORAGE_SQLITE.equalsIgnoreCase(stats.getStorage())) {
            Path sqlitePath = resolveDataPath(baseDir, stats.getSqlite().getPath());
            logInfo("[NoRule] Duplicate message cache storage: sqlite (" + sqlitePath + ")");
            return new SqliteDuplicateMessageCacheRepository(sqlitePath);
        }
        StatsConfig.Mysql mysql = stats.getMysql();
        String jdbcUrl = getEnvOrDefault(ENV_MYSQL_JDBC_URL, mysql.getJdbcUrl());
        String username = getEnvOrDefault(ENV_MYSQL_USER, mysql.getUsername());
        String password = getEnvOrDefault(ENV_MYSQL_PASSWORD, mysql.getPassword());
        int poolSize = parseIntEnv(ENV_MYSQL_POOL_SIZE, mysql.getPoolSize());
        logInfo("[NoRule] Duplicate message cache storage: mysql");
        return new MySqlDuplicateMessageCacheRepository(jdbcUrl, username, password, poolSize);
    }

    private static PrivateRoomCacheRepository createPrivateRoomCacheRepository(StatsConfig stats, Path baseDir) {
        if (STORAGE_SQLITE.equalsIgnoreCase(stats.getStorage())) {
            Path sqlitePath = resolveDataPath(baseDir, stats.getSqlite().getPath());
            logInfo("[NoRule] Private room cache storage: sqlite (" + sqlitePath + ")");
            return new SqlitePrivateRoomCacheRepository(sqlitePath);
        }
        StatsConfig.Mysql mysql = stats.getMysql();
        String jdbcUrl = getEnvOrDefault(ENV_MYSQL_JDBC_URL, mysql.getJdbcUrl());
        String username = getEnvOrDefault(ENV_MYSQL_USER, mysql.getUsername());
        String password = getEnvOrDefault(ENV_MYSQL_PASSWORD, mysql.getPassword());
        int poolSize = parseIntEnv(ENV_MYSQL_POOL_SIZE, mysql.getPoolSize());
        logInfo("[NoRule] Private room cache storage: mysql");
        return new MySqlPrivateRoomCacheRepository(jdbcUrl, username, password, poolSize);
    }

    private static void closeQuietly(AutoCloseable closeable, String name) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            logWarn("[NoRule] Failed to close " + name + ": " + e.getMessage(), e);
        }
    }

    private static ShortUrlService createShortUrlService(BotConfig config, Path baseDir) {
        ShortUrlConfig shortUrlConfig = new ShortUrlConfig(config.getShortUrl());
        ShortUrlRepository repository = createShortUrlRepository(shortUrlConfig, baseDir);
        return new ShortUrlService(repository, shortUrlConfig.toOptions());
    }

    private static ShortUrlRepository createShortUrlRepository(ShortUrlConfig config, Path baseDir) {
        String storage = config.getStorage() == null ? STORAGE_SQLITE : config.getStorage().trim().toLowerCase(Locale.ROOT);
        if ("mysql".equals(storage)) {
            ShortUrlConfig.Mysql mysql = config.getMysql();
            return new MySqlShortUrlRepository(
                    mysql.getJdbcUrl(),
                    mysql.getUsername(),
                    mysql.getPassword(),
                    mysql.getPoolSize()
            );
        }
        Path dbPath = resolveDataPath(baseDir, config.getSqlite().getPath());
        return new SqliteShortUrlRepository(dbPath);
    }

    private static void syncShortUrlGateway() {
        BotConfig runtime = RUNTIME_CONFIG.get();
        ShortUrlService shortUrlService = SHORT_URL_SERVICE.get();
        if (runtime == null || shortUrlService == null) {
            return;
        }
        ShortUrlGatewayServer gatewayServer = SHORT_URL_GATEWAY_SERVER.get();
        if (gatewayServer == null) {
            gatewayServer = new ShortUrlGatewayServer(shortUrlService, () -> {
                BotConfig cfg = RUNTIME_CONFIG.get();
                return cfg == null ? BotConfig.ShortUrl.defaultValues() : cfg.getShortUrl();
            });
            SHORT_URL_GATEWAY_SERVER.set(gatewayServer);
        }
        gatewayServer.syncWithConfig();
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static Path resolveBaseDir(Path configPath) {
        Path parent = configPath.toAbsolutePath().getParent();
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent.normalize();
    }

    private static Path resolveMusicSqlitePath(Path sharedSqlitePath, StatsConfig statsConfig, Path baseDir) {
        if (sharedSqlitePath != null) {
            return sharedSqlitePath;
        }
        if (STORAGE_SQLITE.equalsIgnoreCase(statsConfig.getStorage())) {
            return resolveDataPath(baseDir, statsConfig.getSqlite().getPath());
        }
        return null;
    }

    private static int parseIntEnv(String key, int defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean isTargetDir(Path path) {
        Path name = path.getFileName();
        return name != null && "target".equalsIgnoreCase(name.toString());
    }

    private static Path resolveDataPath(Path baseDir, String configuredPath) {
        Path raw = Path.of(configuredPath == null || configuredPath.isBlank() ? "." : configuredPath.trim());
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        return baseDir.resolve(raw).normalize();
    }

    private static Path resolveSharedSqlitePath(Path baseDir, BotConfig.DataPaths.DataDatabase database) {
        if (database == null) {
            return null;
        }
        if (!STORAGE_SQLITE.equalsIgnoreCase(database.getType())) {
            return null;
        }
        return resolveDataPath(baseDir, database.getPath());
    }

    private static SqliteDatabase createSharedSqliteDatabase(Path sqlitePath) {
        if (sqlitePath == null) {
            return null;
        }
        try {
            SqliteDatabase database = new SqliteDatabase(sqlitePath);
            logInfo("[NoRule] Shared data storage: sqlite (" + sqlitePath.toAbsolutePath().normalize() + ")");
            return database;
        } catch (Exception e) {
            logWarn("[NoRule] Shared sqlite storage disabled: " + e.getMessage(), e);
            return null;
        }
    }

    @SuppressWarnings("java:S106")
    private static void installConsoleFileLogging(Path baseDir, String logDirPath) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        closeQuietly(CONSOLE_FILE_STREAM.getAndSet(null), "console-file stream");
        try {
            Path logDir = resolveDataPath(baseDir, logDirPath);
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("console-" + LocalDateTime.now().format(LOG_FILE_FORMAT) + ".log");
            PrintStream fileStream = new PrintStream(Files.newOutputStream(logFile), true, StandardCharsets.UTF_8);
            CONSOLE_FILE_STREAM.set(fileStream);
            System.setOut(new PrintStream(new TeeOutputStream(originalOut, fileStream, StandardCharsets.UTF_8), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new TeeOutputStream(originalErr, fileStream, StandardCharsets.UTF_8), true, StandardCharsets.UTF_8));
            System.out.println("[NoRule] Console log file: " + logFile.toAbsolutePath());
        } catch (Exception e) {
            originalErr.println("[NoRule] Failed to initialize LOG console output: " + e.getMessage());
        }
    }

    private static void logInfo(String message) {
        LOGGER.info(message);
    }

    private static void logWarn(String message, Throwable throwable) {
        LOGGER.warn(message, throwable);
    }

    private static void logDebug(String message) {
        LOGGER.debug(message);
    }

    private static void logDebug(String message, Throwable throwable) {
        LOGGER.debug(message, throwable);
    }

    @SuppressWarnings("unchecked")
    private static Path firstConfigWithToken(List<Path> candidates) {
        Yaml yaml = new Yaml();
        for (Path candidate : candidates) {
            if (!Files.exists(candidate)) {
                continue;
            }
            try (Reader reader = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
                Object obj = yaml.load(reader);
                if (obj instanceof Map<?, ?> map) {
                    Object token = ((Map<String, Object>) map).get("token");
                    if (token != null && !String.valueOf(token).trim().isBlank()) {
                        return candidate;
                    }
                }
            } catch (Exception ignored) { // Ignore malformed YAML and continue checking next candidate.
                logDebug("[NoRule] Skip invalid config candidate: " + candidate, ignored);
            }
        }
        return null;
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream primary;
        private final OutputStream secondary;
        private final Charset charset;
        private final boolean prettyStackTraces;

        private boolean primaryLineStart = true;
        private boolean secondaryLineStart = true;
        private byte[] lineBuffer = new byte[256];
        private int lineBufferLen = 0;

        private boolean inExceptionBlock = false;
        private int keptStackFrames = 0;
        private int suppressedStackFrames = 0;

        private TeeOutputStream(OutputStream primary, OutputStream secondary, Charset charset) {
            this.primary = primary;
            this.secondary = secondary;
            this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
            this.prettyStackTraces = !"false".equalsIgnoreCase(System.getProperty("norule.prettyStackTraces", "true"));
        }

        @Override
        public synchronized void write(int b) throws IOException {
            ensureCapacity(1);
            lineBuffer[lineBufferLen++] = (byte) b;
            if (b == '\n') {
                flushLineBuffer();
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                write(b[off + i]);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            if (lineBufferLen > 0) {
                flushLineBuffer();
            }
            primary.flush();
            secondary.flush();
        }

        private void ensureCapacity(int additional) {
            int required = lineBufferLen + additional;
            if (required <= lineBuffer.length) {
                return;
            }
            int nextSize = Math.max(required, lineBuffer.length * 2);
            lineBuffer = Arrays.copyOf(lineBuffer, nextSize);
        }

        private void flushLineBuffer() throws IOException {
            byte[] raw = Arrays.copyOf(lineBuffer, lineBufferLen);
            lineBufferLen = 0;

            String rawLine = new String(raw, charset);
            boolean endsWithNewline = rawLine.endsWith("\n") || rawLine.endsWith("\r\n");
            String content = rawLine.replace("\r", "").replace("\n", "");

            boolean isLogHeader = isSimpleLoggerHeader(content);
            if (isLogHeader && inExceptionBlock) {
                writeSuppressedFramesSummary();
                inExceptionBlock = false;
                keptStackFrames = 0;
                suppressedStackFrames = 0;
            }

            // Secondary (file): always write as-is (with prefix)
            writePrefixSecondaryIfNeeded();
            secondary.write(raw);
            if (endsWithNewline) {
                secondaryLineStart = true;
            }

            // Primary (console): optionally shorten stack traces
            if (prettyStackTraces) {
                if (startsExceptionBlock(content)) {
                    inExceptionBlock = true;
                    keptStackFrames = 0;
                    suppressedStackFrames = 0;
                }
                if (inExceptionBlock && isStackFrameLine(content)) {
                    if (keptStackFrames < 4) {
                        keptStackFrames++;
                    } else {
                        suppressedStackFrames++;
                        return;
                    }
                }
            }

            writePrefixPrimaryIfNeeded();
            primary.write(raw);
            if (endsWithNewline) {
                primaryLineStart = true;
            }
        }

        private void writeSuppressedFramesSummary() throws IOException {
            if (!prettyStackTraces || suppressedStackFrames <= 0) {
                return;
            }
            writePrefixPrimaryIfNeeded();
            primary.write(("... (" + suppressedStackFrames + " stack frames suppressed; see LOG file for full trace)").getBytes(charset));
            primary.write("\n".getBytes(charset));
            primaryLineStart = true;
        }

        private void writePrefixPrimaryIfNeeded() throws IOException {
            if (!primaryLineStart) {
                return;
            }
            primaryLineStart = false;
            byte[] prefix = ("[" + LocalDateTime.now().format(CONSOLE_TIME_FORMAT) + "] ").getBytes(charset);
            primary.write(prefix);
        }

        private void writePrefixSecondaryIfNeeded() throws IOException {
            if (!secondaryLineStart) {
                return;
            }
            secondaryLineStart = false;
            byte[] prefix = ("[" + LocalDateTime.now().format(CONSOLE_TIME_FORMAT) + "] ").getBytes(charset);
            secondary.write(prefix);
        }

        private boolean isSimpleLoggerHeader(String line) {
            if (line == null) {
                return false;
            }
            String s = line.trim();
            if (!s.startsWith("[")) {
                return false;
            }
            return s.contains("] INFO ")
                    || s.contains("] WARN ")
                    || s.contains("] ERROR ")
                    || s.contains("] DEBUG ")
                    || s.contains("] TRACE ");
        }

        private boolean startsExceptionBlock(String line) {
            if (line == null) {
                return false;
            }
            String s = line.trim();
            if (s.isBlank() || s.startsWith("[")) {
                return false;
            }
            return s.matches("^[\\w.$]+(Exception|Error)(:.*)?$");
        }

        private boolean isStackFrameLine(String line) {
            if (line == null) {
                return false;
            }
            String s = line.trim();
            return s.startsWith("at ")
                    || s.startsWith("Caused by:")
                    || s.startsWith("Suppressed:");
        }
    }
}




