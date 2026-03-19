package com.norule.musicbot;

import moe.kyokobot.libdave.NativeDaveFactory;
import moe.kyokobot.libdave.jda.LDJDADaveSessionFactory;
import org.yaml.snakeyaml.Yaml;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private static final Set<String> SHUTDOWN_COMMANDS = Set.of("stop", "end");
    private static final Set<String> RELOAD_COMMANDS = Set.of("reload");
    private static final AtomicBoolean SHUTDOWN_STARTED = new AtomicBoolean(false);
    private static final AtomicReference<ScheduledExecutorService> PRESENCE_ROTATION = new AtomicReference<>();
    private static final AtomicReference<BotConfig> RUNTIME_CONFIG = new AtomicReference<>();
    private static final AtomicReference<WebControlServer> WEB_SERVER = new AtomicReference<>();
    private static final AtomicReference<I18nService> I18N_SERVICE = new AtomicReference<>();

    public static void main(String[] args) {
        Path configPath = resolveConfigPath();
        BotConfig config = BotConfig.load(configPath);
        RUNTIME_CONFIG.set(config);
        String token = config.getToken();
        Path baseDir = configPath.toAbsolutePath().getParent() == null
                ? Path.of(".").toAbsolutePath().normalize()
                : configPath.toAbsolutePath().getParent().normalize();
        Path guildSettingsPath = resolveDataPath(baseDir, config.getGuildSettingsDir());
        Path languagePath = resolveDataPath(baseDir, config.getLanguageDir());

        MusicPlayerService playerService = new MusicPlayerService();
        GuildSettingsService guildSettingsService =
                new GuildSettingsService(guildSettingsPath, config);
        I18nService i18nService = I18nService.load(languagePath, config.getDefaultLanguage());
        I18N_SERVICE.set(i18nService);
        MusicCommandListener musicCommandListener = new MusicCommandListener(playerService, config, guildSettingsService);

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
                .disableCache(CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                .setStatus(parseOnlineStatus(config.getBotProfile().getPresenceStatus()))
                .setActivity(buildActivity(config.getBotProfile().getActivityType(), config.getBotProfile().getActivityText()))
                .addEventListeners(
                        musicCommandListener,
                        new NotificationListener(guildSettingsService, i18nService),
                        new MessageLogListener(guildSettingsService, i18nService),
                        new ServerLogListener(guildSettingsService, i18nService),
                        new VoiceAutoLeaveListener(guildSettingsService, playerService, i18nService),
                        new PrivateRoomListener(guildSettingsService, i18nService)
                );

        JDA jda = builder.build();
        installConsoleShutdownListener(jda, i18nService, config.getDefaultLanguage(), configPath, musicCommandListener, playerService, guildSettingsService);
        installShutdownHook(jda, i18nService, config.getDefaultLanguage());

        try {
            jda.awaitReady();
            startActivityRotation(jda, config.getBotProfile());
            syncWebServer(jda, playerService, guildSettingsService, I18N_SERVICE.get());
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

    private static void startActivityRotation(JDA jda, BotConfig.BotProfile profile) {
        ScheduledExecutorService existing = PRESENCE_ROTATION.getAndSet(null);
        if (existing != null) {
            existing.shutdownNow();
        }

        jda.getPresence().setStatus(parseOnlineStatus(profile.getPresenceStatus()));
        String raw = profile.getActivityText();
        if (raw == null || raw.isBlank()) {
            jda.getPresence().setActivity(null);
            return;
        }

        List<Activity> activities = new ArrayList<>();
        if (raw.contains("||")) {
            for (String part : raw.split("\\|\\|")) {
                String entry = part.trim();
                if (entry.isBlank()) {
                    continue;
                }
                String type = profile.getActivityType();
                String text = entry;
                int sep = entry.indexOf('|');
                if (sep > 0 && sep < entry.length() - 1) {
                    type = entry.substring(0, sep).trim();
                    text = entry.substring(sep + 1).trim();
                }
                Activity activity = buildActivity(type, text);
                if (activity != null) {
                    activities.add(activity);
                }
            }
        } else {
            Activity activity = buildActivity(profile.getActivityType(), raw);
            if (activity != null) {
                activities.add(activity);
            }
        }

        if (activities.isEmpty()) {
            jda.getPresence().setActivity(null);
            return;
        }
        jda.getPresence().setActivity(activities.get(0));
        if (activities.size() < 2) {
            return;
        }

        ScheduledExecutorService rotation = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PresenceRotation");
            t.setDaemon(true);
            return t;
        });
        PRESENCE_ROTATION.set(rotation);
        rotation.scheduleAtFixedRate(new Runnable() {
            private int index = 1;

            @Override
            public void run() {
                try {
                    jda.getPresence().setActivity(activities.get(index));
                    index = (index + 1) % activities.size();
                } catch (Exception ignored) {
                }
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    private static void installConsoleShutdownListener(JDA jda,
                                                       I18nService i18nService,
                                                       String language,
                                                       Path configPath,
                                                       MusicCommandListener musicCommandListener,
                                                       MusicPlayerService playerService,
                                                       GuildSettingsService guildSettingsService) {
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
                        reloadRuntimeConfig(jda, configPath, musicCommandListener, playerService, guildSettingsService);
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
                                            GuildSettingsService guildSettingsService) {
        if (SHUTDOWN_STARTED.get()) {
            return;
        }
        try {
            BotConfig reloaded = BotConfig.load(configPath);
            RUNTIME_CONFIG.set(reloaded);
            musicCommandListener.reloadRuntimeConfig(reloaded);
            startActivityRotation(jda, reloaded.getBotProfile());
            syncWebServer(jda, playerService, guildSettingsService, I18N_SERVICE.get());
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
                                      I18nService i18nService) {
        BotConfig runtime = RUNTIME_CONFIG.get();
        if (runtime == null || i18nService == null) {
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
            server = new WebControlServer(jda, playerService, guildSettingsService, RUNTIME_CONFIG::get, i18nService);
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
        System.out.println("[NoRule] " + message);
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
}
