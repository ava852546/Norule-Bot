package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RuntimeConfigSnapshot {
    private final String prefix;
    private final int commandCooldownSeconds;
    private final String defaultLanguage;
    private final Path languageDir;
    private final int numberChainReactionDelayMillis;
    private final List<Long> developerIds;
    private final String botDescription;
    private final BotConfig.Notifications defaultNotifications;
    private final BotConfig.Welcome defaultWelcome;
    private final BotConfig.MessageLogs defaultMessageLogs;
    private final BotConfig.Music defaultMusic;
    private final BotConfig.PrivateRoom defaultPrivateRoom;
    private final BotConfig.Ticket defaultTicket;
    private final BotConfig.MinecraftStatus minecraftStatus;

    public RuntimeConfigSnapshot(String prefix,
                                 int commandCooldownSeconds,
                                 String defaultLanguage,
                                 Path languageDir,
                                 int numberChainReactionDelayMillis,
                                 List<Long> developerIds,
                                 String botDescription,
                                 BotConfig.Notifications defaultNotifications,
                                 BotConfig.Welcome defaultWelcome,
                                 BotConfig.MessageLogs defaultMessageLogs,
                                 BotConfig.Music defaultMusic,
                                 BotConfig.PrivateRoom defaultPrivateRoom,
                                 BotConfig.Ticket defaultTicket,
                                 BotConfig.MinecraftStatus minecraftStatus) {
        this.prefix = prefix == null ? "!" : prefix;
        this.commandCooldownSeconds = Math.max(0, commandCooldownSeconds);
        this.defaultLanguage = defaultLanguage == null ? "en" : defaultLanguage;
        this.languageDir = languageDir;
        this.numberChainReactionDelayMillis = Math.max(0, numberChainReactionDelayMillis);
        this.developerIds = developerIds == null ? List.of() : List.copyOf(developerIds);
        this.botDescription = botDescription == null ? "" : botDescription;
        this.defaultNotifications = defaultNotifications == null ? BotConfig.Notifications.defaultValues() : defaultNotifications;
        this.defaultWelcome = defaultWelcome == null ? BotConfig.Welcome.defaultValues() : defaultWelcome;
        this.defaultMessageLogs = defaultMessageLogs == null ? BotConfig.MessageLogs.defaultValues() : defaultMessageLogs;
        this.defaultMusic = defaultMusic == null ? BotConfig.Music.defaultValues() : defaultMusic;
        this.defaultPrivateRoom = defaultPrivateRoom == null ? BotConfig.PrivateRoom.defaultValues() : defaultPrivateRoom;
        this.defaultTicket = defaultTicket == null ? BotConfig.Ticket.defaultValues() : defaultTicket;
        this.minecraftStatus = minecraftStatus == null ? BotConfig.MinecraftStatus.defaultValues() : minecraftStatus;
    }

    public static RuntimeConfigSnapshot from(BotConfig config, Path baseDir) {
        Path resolvedLanguageDir = baseDir.resolve(config.getLanguageDir() == null ? "lang" : config.getLanguageDir()).normalize();
        List<Long> ids = new ArrayList<>();
        if (config.getDevelopers() != null) {
            ids.addAll(config.getDevelopers().getIds());
        }
        String description = config.getBotProfile() == null ? "" : config.getBotProfile().getDescription();
        return new RuntimeConfigSnapshot(
                config.getPrefix(),
                config.getCommandCooldownSeconds(),
                config.getDefaultLanguage(),
                resolvedLanguageDir,
                config.getNumberChainReactionDelayMillis(),
                ids,
                description,
                config.getNotifications(),
                config.getWelcome(),
                config.getMessageLogs(),
                config.getMusic(),
                config.getPrivateRoom(),
                config.getTicket(),
                config.getMinecraftStatus()
        );
    }

    public String getPrefix() { return prefix; }
    public int getCommandCooldownSeconds() { return commandCooldownSeconds; }
    public String getDefaultLanguage() { return defaultLanguage; }
    public Path getLanguageDir() { return languageDir; }
    public int getNumberChainReactionDelayMillis() { return numberChainReactionDelayMillis; }
    public List<Long> getDeveloperIds() { return developerIds; }
    public String getBotDescription() { return botDescription; }
    public BotConfig.Notifications getDefaultNotifications() { return defaultNotifications; }
    public BotConfig.Welcome getDefaultWelcome() { return defaultWelcome; }
    public BotConfig.MessageLogs getDefaultMessageLogs() { return defaultMessageLogs; }
    public BotConfig.Music getDefaultMusic() { return defaultMusic; }
    public BotConfig.PrivateRoom getDefaultPrivateRoom() { return defaultPrivateRoom; }
    public BotConfig.Ticket getDefaultTicket() { return defaultTicket; }
    public BotConfig.MinecraftStatus getMinecraftStatus() { return minecraftStatus; }
}
