package com.norule.musicbot.bootstrap;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.discord.listeners.*;
import com.norule.musicbot.web.*;

import com.norule.musicbot.*;

import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;
import org.yaml.snakeyaml.Yaml;
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

public class Main {
    private static final DateTimeFormatter LOG_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter CONSOLE_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Set<String> SHUTDOWN_COMMANDS = Set.of("stop", "end");
    private static final Set<String> RELOAD_COMMANDS = Set.of("reload");
    private static final AtomicBoolean SHUTDOWN_STARTED = new AtomicBoolean(false);
    private static final AtomicReference<ScheduledExecutorService> PRESENCE_ROTATION = new AtomicReference<>();
    private static final AtomicReference<BotConfig> RUNTIME_CONFIG = new AtomicReference<>();
    private static final AtomicReference<WebControlServer> WEB_SERVER = new AtomicReference<>();
    private static final AtomicReference<I18nService> I18N_SERVICE = new AtomicReference<>();
    private static final AtomicReference<TicketService> TICKET_SERVICE = new AtomicReference<>();
    private record ActivityTemplate(String type, String text) {}

    public static void main(String[] args) {
        Path configPath = resolveConfigPath();
        Path baseDir = configPath.toAbsolutePath().getParent() == null
                ? Path.of(".").toAbsolutePath().normalize()
                : configPath.toAbsolutePath().getParent().normalize();
        installConsoleFileLogging(baseDir);
        BotConfig config = BotConfig.load(configPath);
        RUNTIME_CONFIG.set(config);
        String token = config.getToken();
        Path guildSettingsPath = resolveDataPath(baseDir, config.getGuildSettingsDir());
        Path languagePath = resolveDataPath(baseDir, config.getLanguageDir());
        Path cachePath = resolveDataPath(baseDir, "cache");
        Path musicDataPath = resolveDataPath(baseDir, "guild-music");
        Path ticketDataPath = resolveDataPath(baseDir, "guild-tickets");
        Path ticketTranscriptPath = resolveDataPath(baseDir, "ticket-transcripts");

        MusicPlayerService playerService = new MusicPlayerService(musicDataPath);
        GuildSettingsService guildSettingsService =
                new GuildSettingsService(guildSettingsPath, config);
        ModerationService moderationService = new ModerationService(resolveDataPath(baseDir, "guild-moderation"));
        TicketService ticketService = new TicketService(ticketDataPath, ticketTranscriptPath);
        TICKET_SERVICE.set(ticketService);
        I18nService i18nService = I18nService.load(languagePath, config.getDefaultLanguage());
        I18N_SERVICE.set(i18nService);
        MusicCommandListener musicCommandListener = new MusicCommandListener(playerService, config, guildSettingsService, moderationService);
        TicketListener ticketListener = new TicketListener(guildSettingsService, ticketService, i18nService);

        AudioModuleConfig audioConfig = new AudioModuleConfig()
                .withDaveSessionFactory(new LDJDADaveSessionFactory(new NativeDaveFactory()));

        JDABuilder builder = JDABuilder.createDefault(
                        token,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_MEMBERS,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.GUILD_MODERATION
                )
                .setAudioModuleConfig(audioConfig)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .disableCache(CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                .setStatus(parseOnlineStatus(config.getBotProfile().getPresenceStatus()))
                .setActivity(null)
                .addEventListeners(
                        musicCommandListener,
                        new NotificationListener(guildSettingsService, i18nService),
                        new MessageLogListener(guildSettingsService, i18nService, cachePath),
                        new ServerLogListener(guildSettingsService, i18nService),
                        new DuplicateMessageListener(guildSettingsService, moderationService, i18nService, cachePath),
                        new NumberChainListener(guildSettingsService, moderationService, i18nService),
                        new VoiceAutoLeaveListener(guildSettingsService, playerService, i18nService),
                        new PrivateRoomListener(guildSettingsService, i18nService),
                        ticketListener
                );

        JDA jda = builder.build();
        installConsoleShutdownListener(jda, i18nService, config.getDefaultLanguage(), configPath, musicCommandListener, playerService, guildSettingsService, moderationService);
        installShutdownHook(jda, i18nService, config.getDefaultLanguage());

        try {
            jda.awaitReady();
            startActivityRotation(jda, config.getBotProfile());
            syncWebServer(jda, playerService, guildSettingsService, moderationService, TICKET_SERVICE.get(), I18N_SERVICE.get());
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
                }
            }
        }, rotationSeconds, rotationSeconds, TimeUnit.SECONDS);
    }

    private static void installConsoleShutdownListener(JDA jda,
                                                       I18nService i18nService,
                                                       String language,
                                                       Path configPath,
                                                       MusicCommandListener musicCommandListener,
                                                       MusicPlayerService playerService,
                                                       GuildSettingsService guildSettingsService,
                                                       ModerationService moderationService) {
        Thread listener = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String command = line.trim().toLowerCase(Locale.ROOT);
                    if (SHUTDOWN_COMMANDS.contains(command)) {
                        System.out.println("[NoRule] Shutdown command received: " + command);
                        requestShutdown(jda, i18nService, language, true);
                        break;
                    }
                    if (RELOAD_COMMANDS.contains(command)) {
                        reloadRuntimeConfig(jda, configPath, musicCommandListener, playerService, guildSettingsService, moderationService);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "ConsoleShutdownListener");
        listener.setDaemon(true);
        listener.start();
    }

    private static void reloadRuntimeConfig(JDA jda,
                                            Path configPath,
                                            MusicCommandListener musicCommandListener,
                                            MusicPlayerService playerService,
                                            GuildSettingsService guildSettingsService,
                                            ModerationService moderationService) {
        if (SHUTDOWN_STARTED.get()) {
            return;
        }
        try {
            BotConfig reloaded = BotConfig.load(configPath);
            RUNTIME_CONFIG.set(reloaded);
            musicCommandListener.reloadRuntimeConfig(reloaded);
            startActivityRotation(jda, reloaded.getBotProfile());
            syncWebServer(jda, playerService, guildSettingsService, moderationService, TICKET_SERVICE.get(), I18N_SERVICE.get());
            System.out.println("[NoRule] Config reloaded successfully.");
        } catch (Exception e) {
            System.out.println("[NoRule] Config reload failed: " + e.getMessage());
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
        ScheduledExecutorService rotation = PRESENCE_ROTATION.getAndSet(null);
        if (rotation != null) {
            rotation.shutdownNow();
        }
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
                                      I18nService i18nService) {
        BotConfig runtime = RUNTIME_CONFIG.get();
        if (runtime == null || i18nService == null || ticketService == null) {
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
            server = new WebControlServer(jda, playerService, guildSettingsService, moderationService, ticketService, RUNTIME_CONFIG::get, i18nService);
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
        try {
            System.out.println(output);
        } catch (RuntimeException ex) {
            // Fallback for rare Jansi parse errors when ANSI output interleaves during shutdown.
            System.err.println(output.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", ""));
        }
    }

    private static String sanitizeConsoleMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        String sanitized = message
                .replace("\uFEFF", "")
                .replace("\u001B", "")
                .replace("\u009B", "")
                .replaceAll("\u001B\\[[;\\d]*[ -/]*[@-~]", "")
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
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

    private static void installConsoleFileLogging(Path baseDir) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            Path logDir = resolveDataPath(baseDir, "LOG");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("console-" + LocalDateTime.now().format(LOG_FILE_FORMAT) + ".log");
            PrintStream fileStream = new PrintStream(Files.newOutputStream(logFile), true, StandardCharsets.UTF_8);
            System.setOut(new PrintStream(new TeeOutputStream(originalOut, fileStream, StandardCharsets.UTF_8), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new TeeOutputStream(originalErr, fileStream, StandardCharsets.UTF_8), true, StandardCharsets.UTF_8));
            System.out.println("[NoRule] Console log file: " + logFile.toAbsolutePath());
        } catch (Exception e) {
            originalErr.println("[NoRule] Failed to initialize LOG console output: " + e.getMessage());
        }
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
            } catch (Exception ignored) {
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


