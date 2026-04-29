package com.norule.musicbot.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

public class GuildSettingsService {
    public static class GuildSettings {
        private final String language;
        private final BotConfig.Notifications notifications;
        private final BotConfig.Welcome welcome;
        private final BotConfig.MessageLogs messageLogs;
        private final BotConfig.Music music;
        private final BotConfig.PrivateRoom privateRoom;
        private final BotConfig.Ticket ticket;

        public GuildSettings(String language,
                             BotConfig.Notifications notifications,
                             BotConfig.Welcome welcome,
                             BotConfig.MessageLogs messageLogs,
                             BotConfig.Music music,
                             BotConfig.PrivateRoom privateRoom,
                             BotConfig.Ticket ticket) {
            this.language = language;
            this.notifications = notifications;
            this.welcome = welcome;
            this.messageLogs = messageLogs;
            this.music = music;
            this.privateRoom = privateRoom;
            this.ticket = ticket;
        }

        public String getLanguage() {
            return language;
        }

        public BotConfig.Notifications getNotifications() {
            return notifications;
        }

        public BotConfig.MessageLogs getMessageLogs() {
            return messageLogs;
        }

        public BotConfig.Welcome getWelcome() {
            return welcome;
        }

        public BotConfig.Music getMusic() {
            return music;
        }

        public BotConfig.PrivateRoom getPrivateRoom() {
            return privateRoom;
        }

        public BotConfig.Ticket getTicket() {
            return ticket;
        }

        public GuildSettings withLanguage(String language) {
            return new GuildSettings(language, notifications, welcome, messageLogs, music, privateRoom, ticket);
        }

        public GuildSettings withNotifications(BotConfig.Notifications notifications) {
            return new GuildSettings(language, notifications, welcome, messageLogs, music, privateRoom, ticket);
        }

        public GuildSettings withWelcome(BotConfig.Welcome welcome) {
            return new GuildSettings(language, notifications, welcome, messageLogs, music, privateRoom, ticket);
        }

        public GuildSettings withMessageLogs(BotConfig.MessageLogs messageLogs) {
            return new GuildSettings(language, notifications, welcome, messageLogs, music, privateRoom, ticket);
        }

        public GuildSettings withMusic(BotConfig.Music music) {
            return new GuildSettings(language, notifications, welcome, messageLogs, music, privateRoom, ticket);
        }

        public GuildSettings withPrivateRoom(BotConfig.PrivateRoom privateRoom) {
            return new GuildSettings(language, notifications, welcome, messageLogs, music, privateRoom, ticket);
        }

        public GuildSettings withTicket(BotConfig.Ticket ticket) {
            return new GuildSettings(language, notifications, welcome, messageLogs, music, privateRoom, ticket);
        }
    }

    private final Path settingsDir;
    private volatile GuildSettings defaults;
    private final Map<Long, GuildSettings> cache = new ConcurrentHashMap<>();

    public GuildSettingsService(Path settingsDir, BotConfig defaultsConfig) {
        this.settingsDir = settingsDir;
        this.defaults = new GuildSettings(
                defaultsConfig.getDefaultLanguage(),
                BotConfig.Notifications.defaultValues(),
                BotConfig.Welcome.defaultValues(),
                BotConfig.MessageLogs.defaultValues(),
                BotConfig.Music.defaultValues(),
                BotConfig.PrivateRoom.defaultValues(),
                BotConfig.Ticket.defaultValues()
        );

        try {
            Files.createDirectories(settingsDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create guild settings directory: " + settingsDir.toAbsolutePath(), e);
        }
    }

    public GuildSettings getSettings(long guildId) {
        return cache.computeIfAbsent(guildId, this::loadOrCreateSettings);
    }

    public String getLanguage(long guildId) {
        return getSettings(guildId).getLanguage();
    }

    public BotConfig.Notifications getNotifications(long guildId) {
        return getSettings(guildId).getNotifications();
    }

    public BotConfig.MessageLogs getMessageLogs(long guildId) {
        return getSettings(guildId).getMessageLogs();
    }

    public BotConfig.Welcome getWelcome(long guildId) {
        return getSettings(guildId).getWelcome();
    }

    public BotConfig.Music getMusic(long guildId) {
        return getSettings(guildId).getMusic();
    }

    public BotConfig.PrivateRoom getPrivateRoom(long guildId) {
        return getSettings(guildId).getPrivateRoom();
    }

    public BotConfig.Ticket getTicket(long guildId) {
        return getSettings(guildId).getTicket();
    }

    public Path getSettingsDirectory() {
        return settingsDir;
    }

    public GuildSettings updateSettings(long guildId, UnaryOperator<GuildSettings> updater) {
        GuildSettings current = getSettings(guildId);
        GuildSettings updated = updater.apply(current);
        writeSettings(guildId, updated);
        cache.put(guildId, updated);
        return updated;
    }

    public GuildSettings reload(long guildId) {
        cache.remove(guildId);
        return getSettings(guildId);
    }

    public void reloadAll(BotConfig defaultsConfig) {
        if (defaultsConfig != null) {
            this.defaults = new GuildSettings(
                    defaultsConfig.getDefaultLanguage(),
                    BotConfig.Notifications.defaultValues(),
                    BotConfig.Welcome.defaultValues(),
                    BotConfig.MessageLogs.defaultValues(),
                    BotConfig.Music.defaultValues(),
                    BotConfig.PrivateRoom.defaultValues(),
                    BotConfig.Ticket.defaultValues()
            );
        }
        cache.clear();
    }

    private GuildSettings loadOrCreateSettings(long guildId) {
        Path guildFile = settingsDir.resolve(guildId + ".yml");
        if (!Files.exists(guildFile)) {
            writeTemplate(guildFile, guildId);
            return defaults;
        }

        try (InputStream in = Files.newInputStream(guildFile)) {
            Object root = new Yaml().load(in);
            Map<String, Object> rootMap = asMap(root);
            String language = readLanguage(rootMap.get("language"), defaults.getLanguage());
            BotConfig.Notifications notifications = BotConfig.Notifications.fromMap(asMap(rootMap.get("notifications")), defaults.getNotifications());
            BotConfig.Welcome welcome = BotConfig.Welcome.fromMap(asMap(rootMap.get("welcome")), defaults.getWelcome());
            BotConfig.MessageLogs messageLogs = BotConfig.MessageLogs.fromMap(asMap(rootMap.get("messageLogs")), defaults.getMessageLogs());
            BotConfig.Music music = BotConfig.Music.fromMap(asMap(rootMap.get("music")), defaults.getMusic());
            BotConfig.PrivateRoom privateRoom = BotConfig.PrivateRoom.fromMap(asMap(rootMap.get("privateRoom")), defaults.getPrivateRoom());
            BotConfig.Ticket ticket = BotConfig.Ticket.fromMap(asMap(rootMap.get("ticket")), defaults.getTicket());
            return new GuildSettings(language, notifications, welcome, messageLogs, music, privateRoom, ticket);
        } catch (Exception e) {
            return defaults;
        }
    }

    private void writeTemplate(Path guildFile, long guildId) {
        writeToFile(guildFile, String.valueOf(guildId), defaults);
    }

    private void writeSettings(long guildId, GuildSettings settings) {
        Path guildFile = settingsDir.resolve(guildId + ".yml");
        writeToFile(guildFile, String.valueOf(guildId), settings);
    }

    private void writeToFile(Path guildFile, String guildId, GuildSettings settings) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("guildId", guildId);
        root.put("language", settings.getLanguage());

        BotConfig.Notifications notifications = settings.getNotifications();
        Map<String, Object> notificationsMap = new LinkedHashMap<>();
        notificationsMap.put("enabled", notifications.isEnabled());
        notificationsMap.put("memberJoinEnabled", notifications.isMemberJoinEnabled());
        notificationsMap.put("memberLeaveEnabled", notifications.isMemberLeaveEnabled());
        notificationsMap.put("voiceLogEnabled", notifications.isVoiceLogEnabled());
        notificationsMap.put("memberChannelId", toText(notifications.getMemberChannelId()));
        notificationsMap.put("memberJoinChannelId", toText(notifications.getMemberJoinChannelId()));
        notificationsMap.put("memberLeaveChannelId", toText(notifications.getMemberLeaveChannelId()));
        notificationsMap.put("memberJoinTitle", notifications.getMemberJoinTitle());
        notificationsMap.put("memberJoinMessage", notifications.getMemberJoinMessage());
        notificationsMap.put("memberJoinThumbnailUrl", notifications.getMemberJoinThumbnailUrl());
        notificationsMap.put("memberJoinImageUrl", notifications.getMemberJoinImageUrl());
        notificationsMap.put("memberLeaveMessage", notifications.getMemberLeaveMessage());
        notificationsMap.put("memberJoinColor", String.format("#%06X", notifications.getMemberJoinColor()));
        notificationsMap.put("memberLeaveColor", String.format("#%06X", notifications.getMemberLeaveColor()));
        notificationsMap.put("voiceChannelId", toText(notifications.getVoiceChannelId()));
        notificationsMap.put("voiceJoinMessage", notifications.getVoiceJoinMessage());
        notificationsMap.put("voiceLeaveMessage", notifications.getVoiceLeaveMessage());
        notificationsMap.put("voiceMoveMessage", notifications.getVoiceMoveMessage());
        notificationsMap.put("voiceJoinColor", String.format("#%06X", notifications.getVoiceJoinColor()));
        notificationsMap.put("voiceLeaveColor", String.format("#%06X", notifications.getVoiceLeaveColor()));
        notificationsMap.put("voiceMoveColor", String.format("#%06X", notifications.getVoiceMoveColor()));
        root.put("notifications", notificationsMap);

        BotConfig.Welcome welcome = settings.getWelcome();
        Map<String, Object> welcomeMap = new LinkedHashMap<>();
        welcomeMap.put("enabled", welcome.isEnabled());
        welcomeMap.put("channelId", toText(welcome.getChannelId()));
        welcomeMap.put("title", welcome.getTitle());
        welcomeMap.put("message", welcome.getMessage());
        welcomeMap.put("thumbnailUrl", welcome.getThumbnailUrl());
        welcomeMap.put("imageUrl", welcome.getImageUrl());
        root.put("welcome", welcomeMap);

        BotConfig.MessageLogs logs = settings.getMessageLogs();
        Map<String, Object> logsMap = new LinkedHashMap<>();
        logsMap.put("enabled", logs.isEnabled());
        logsMap.put("channelId", toText(logs.getChannelId()));
        logsMap.put("messageLogChannelId", toText(logs.getMessageLogChannelId()));
        logsMap.put("commandUsageChannelId", toText(logs.getCommandUsageChannelId()));
        logsMap.put("channelLifecycleChannelId", toText(logs.getChannelLifecycleChannelId()));
        logsMap.put("roleLogChannelId", toText(logs.getRoleLogChannelId()));
        logsMap.put("moderationLogChannelId", toText(logs.getModerationLogChannelId()));
        logsMap.put("roleLogEnabled", logs.isRoleLogEnabled());
        logsMap.put("channelLifecycleLogEnabled", logs.isChannelLifecycleLogEnabled());
        logsMap.put("moderationLogEnabled", logs.isModerationLogEnabled());
        logsMap.put("commandUsageLogEnabled", logs.isCommandUsageLogEnabled());
        logsMap.put("ignoredMemberIds", new ArrayList<>(logs.getIgnoredMemberIds()));
        logsMap.put("ignoredRoleIds", new ArrayList<>(logs.getIgnoredRoleIds()));
        logsMap.put("ignoredChannelIds", new ArrayList<>(logs.getIgnoredChannelIds()));
        logsMap.put("ignoredPrefixes", new ArrayList<>(logs.getIgnoredPrefixes()));
        root.put("messageLogs", logsMap);

        BotConfig.Music music = settings.getMusic();
        Map<String, Object> musicMap = new LinkedHashMap<>();
        musicMap.put("autoLeaveEnabled", music.isAutoLeaveEnabled());
        musicMap.put("autoLeaveMinutes", music.getAutoLeaveMinutes());
        musicMap.put("autoplayEnabled", music.isAutoplayEnabled());
        musicMap.put("defaultRepeatMode", music.getDefaultRepeatMode().name());
        musicMap.put("commandChannelId", toText(music.getCommandChannelId()));
        musicMap.put("historyLimit", music.getHistoryLimit());
        musicMap.put("statsRetentionDays", music.getStatsRetentionDays());
        musicMap.put("playlistTrackLimit", music.getPlaylistTrackLimit());
        root.put("music", musicMap);

        BotConfig.PrivateRoom privateRoom = settings.getPrivateRoom();
        Map<String, Object> privateRoomMap = new LinkedHashMap<>();
        privateRoomMap.put("enabled", privateRoom.isEnabled());
        privateRoomMap.put("triggerVoiceChannelId", toText(privateRoom.getTriggerVoiceChannelId()));
        privateRoomMap.put("userLimit", privateRoom.getUserLimit());
        root.put("privateRoom", privateRoomMap);

        BotConfig.Ticket ticket = settings.getTicket();
        Map<String, Object> ticketMap = new LinkedHashMap<>();
        ticketMap.put("enabled", ticket.isEnabled());
        ticketMap.put("panelChannelId", toText(ticket.getPanelChannelId()));
        ticketMap.put("openCategoryId", toText(ticket.getOpenCategoryId()));
        ticketMap.put("closedCategoryId", toText(ticket.getClosedCategoryId()));
        ticketMap.put("autoCloseDays", ticket.getAutoCloseDays());
        ticketMap.put("maxOpenPerUser", ticket.getMaxOpenPerUser());
        ticketMap.put("openUiMode", ticket.getOpenUiMode().name());
        ticketMap.put("panelTitle", ticket.getPanelTitle());
        ticketMap.put("panelDescription", ticket.getPanelDescription());
        ticketMap.put("panelButtonStyle", ticket.getPanelButtonStyle());
        ticketMap.put("panelButtonLimit", ticket.getPanelButtonLimit());
        ticketMap.put("welcomeMessage", ticket.getWelcomeMessage());
        ticketMap.put("preOpenFormEnabled", ticket.isPreOpenFormEnabled());
        ticketMap.put("preOpenFormTitle", ticket.getPreOpenFormTitle());
        ticketMap.put("preOpenFormLabel", ticket.getPreOpenFormLabel());
        ticketMap.put("preOpenFormPlaceholder", ticket.getPreOpenFormPlaceholder());
        ticketMap.put("optionLabels", ticket.getOptionLabels());
        List<Map<String, Object>> optionMaps = new ArrayList<>();
        for (BotConfig.Ticket.TicketOption option : ticket.getOptions()) {
            Map<String, Object> optionMap = new LinkedHashMap<>();
            optionMap.put("id", option.getId());
            optionMap.put("label", option.getLabel());
            optionMap.put("panelTitle", option.getPanelTitle());
            optionMap.put("panelDescription", option.getPanelDescription());
            optionMap.put("panelButtonStyle", option.getPanelButtonStyle());
            optionMap.put("welcomeMessage", option.getWelcomeMessage());
            optionMap.put("preOpenFormEnabled", option.isPreOpenFormEnabled());
            optionMap.put("preOpenFormTitle", option.getPreOpenFormTitle());
            optionMap.put("preOpenFormLabel", option.getPreOpenFormLabel());
            optionMap.put("preOpenFormPlaceholder", option.getPreOpenFormPlaceholder());
            optionMaps.add(optionMap);
        }
        ticketMap.put("options", optionMaps);
        ticketMap.put("supportRoleIds", ticket.getSupportRoleIds().stream().map(String::valueOf).toList());
        ticketMap.put("blacklistedUserIds", ticket.getBlacklistedUserIds().stream().map(String::valueOf).toList());
        root.put("ticket", ticketMap);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);

        Yaml yaml = new Yaml(options);
        try {
            Files.createDirectories(guildFile.getParent());
        } catch (IOException ignored) {
        }

        try (Writer writer = Files.newBufferedWriter(guildFile)) {
            yaml.dump(root, writer);
        } catch (IOException ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object object) {
        if (object instanceof Map) {
            return (Map<String, Object>) object;
        }
        return Map.of();
    }

    private String toText(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String readLanguage(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }
}


