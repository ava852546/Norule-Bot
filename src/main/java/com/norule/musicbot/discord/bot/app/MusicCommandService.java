package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.*;
import com.norule.musicbot.config.domain.MinecraftStatusConfig;
import com.norule.musicbot.config.domain.GuildDomainConfigAdapter;
import com.norule.musicbot.config.domain.RuntimeConfigSnapshot;
import com.norule.musicbot.discord.bot.gateway.InteractionRouter;
import com.norule.musicbot.discord.bot.gateway.command.CommandNames;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import com.norule.musicbot.discord.bot.gateway.command.honeypot.HoneypotCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.music.HistoryCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicPlaybackCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicPlaybackText;
import com.norule.musicbot.discord.bot.gateway.command.music.PlaylistCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.meta.HelpCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.meta.InfoCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.meta.PingCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.minecraft.MinecraftStatusCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.registry.DiscordCommandCatalog;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelController;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelRefreshService;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelRenderer;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelStateStore;
import com.norule.musicbot.discord.bot.gateway.command.moderation.DeleteMessagesCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.moderation.WarningCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.privateroom.PrivateRoomSettingsCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.settings.SettingsCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.shorturl.UrlCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.welcome.WelcomeCommandHandler;
import com.norule.musicbot.discord.bot.service.stats.MessageStatsEventService;
import com.norule.musicbot.domain.wordchain.WordChainStatusSnapshot;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.gateway.minecraft.McSrvStatGateway;

import com.norule.musicbot.*;
import com.norule.musicbot.discord.bot.infra.CommandRegistrar;
import com.norule.musicbot.ops.minecraft.MinecraftStatusOps;
import com.norule.musicbot.discord.bot.ops.ticket.TicketOps;
import com.norule.musicbot.discord.bot.ops.wordchain.WordChainOps;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MusicCommandService extends ListenerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicCommandService.class);
    static final String CMD_HELP_ZH = CommandNames.CMD_HELP_ZH;
    static final String CMD_JOIN_ZH = CommandNames.CMD_JOIN_ZH;
    static final String CMD_PLAY_ZH = CommandNames.CMD_PLAY_ZH;
    static final String CMD_SKIP_ZH = CommandNames.CMD_SKIP_ZH;
    static final String CMD_STOP_ZH = CommandNames.CMD_STOP_ZH;
    static final String CMD_LEAVE_ZH = CommandNames.CMD_LEAVE_ZH;
    static final String CMD_MUSIC_PANEL_ZH = CommandNames.CMD_MUSIC_PANEL_ZH;
    static final String CMD_REPEAT_ZH = CommandNames.CMD_REPEAT_ZH;
    static final String CMD_PING_ZH = CommandNames.CMD_PING_ZH;
    static final String CMD_WELCOME_ZH = CommandNames.CMD_WELCOME_ZH;
    public static final String OPTION_WELCOME_CHANNEL_ZH = "\u983b\u9053";
    static final String CMD_VOLUME_ZH = CommandNames.CMD_VOLUME_ZH;
    static final String CMD_HISTORY_ZH = CommandNames.CMD_HISTORY_ZH;
    static final String CMD_MUSIC_ZH = CommandNames.CMD_MUSIC_ZH;
    static final String CMD_PLAYLIST_ZH = CommandNames.CMD_PLAYLIST_ZH;
    static final String CMD_SETTINGS_ZH = CommandNames.CMD_SETTINGS_ZH;
    static final String CMD_DELETE_ZH = CommandNames.CMD_DELETE_ZH;
    static final String CMD_ROOM_SETTINGS_ZH = CommandNames.CMD_ROOM_SETTINGS_ZH;
    static final String CMD_WARNINGS_ZH = CommandNames.CMD_WARNINGS_ZH;
    static final String CMD_ANTI_DUPLICATE_ZH = CommandNames.CMD_ANTI_DUPLICATE_ZH;
    static final String CMD_HONEYPOT_ZH = CommandNames.CMD_HONEYPOT_ZH;
    static final String CMD_NUMBER_CHAIN_ZH = CommandNames.CMD_NUMBER_CHAIN_ZH;
    static final String CMD_WORD_CHAIN_ZH = CommandNames.CMD_WORD_CHAIN_ZH;
    static final String CMD_TICKET_ZH = CommandNames.CMD_TICKET_ZH;
    static final String CMD_USER_INFO_ZH = CommandNames.CMD_USER_INFO_ZH;
    static final String CMD_ROLE_INFO_ZH = CommandNames.CMD_ROLE_INFO_ZH;
    static final String CMD_SERVER_INFO_ZH = CommandNames.CMD_SERVER_INFO_ZH;
    static final String CMD_STATS_ZH = CommandNames.CMD_STATS_ZH;
    static final String CMD_LEADERBOARD_ZH = CommandNames.CMD_LEADERBOARD_ZH;
    static final String CMD_SHORT_URL_ZH = CommandNames.CMD_SHORT_URL_ZH;
    static final String CMD_MINECRAFT_STATUS_ZH = CommandNames.CMD_MINECRAFT_STATUS_ZH;
    static final String SUB_SETTINGS_INFO_ZH = "\u8a73\u7d30\u8cc7\u8a0a";
    static final String SUB_SETTINGS_RELOAD_ZH = "\u91cd\u8f09\u8a2d\u5b9a";
    static final String SUB_SETTINGS_RESET_ZH = "\u6062\u5fa9\u9810\u8a2d";
    static final String SUB_SETTINGS_TEMPLATE_ZH = "\u6a21\u677f\u7de8\u8f2f";
    static final String SUB_SETTINGS_MODULE_ZH = "\u6a21\u7d44\u958b\u95dc";
    static final String SUB_SETTINGS_LOGS_ZH = "\u65e5\u8a8c\u983b\u9053";
    static final String SUB_SETTINGS_LOG_SETTINGS_ZH = "\u65e5\u8a8c\u5ffd\u7565";
    static final String SUB_SETTINGS_MUSIC_ZH = "\u97f3\u6a02\u8a2d\u5b9a";
    static final String SUB_SETTINGS_LANGUAGE_ZH = "\u8a9e\u8a00\u8a2d\u7f6e";
    static final String SUB_SETTINGS_NUMBER_CHAIN_ZH = "\u63a5\u9f8d\u904a\u6232";
    static final String SUB_SETTINGS_WORD_CHAIN_ZH = "\u82f1\u6587\u63a5\u9f8d";
    static final String SUB_GENERIC_ENABLE_ZH = "\u555f\u7528";
    static final String SUB_GENERIC_DISABLE_ZH = "\u95dc\u9589";
    static final String SUB_GENERIC_STATUS_ZH = "\u72c0\u614b";
    static final String SUB_MUSIC_STATS_ZH = "\u7d71\u8a08";
    static final String SUB_PLAYLIST_SAVE_ZH = "\u5132\u5b58";
    static final String SUB_PLAYLIST_LOAD_ZH = "\u8f09\u5165";
    static final String SUB_PLAYLIST_DELETE_ZH = "\u522a\u9664";
    static final String SUB_PLAYLIST_LIST_ZH = "\u5217\u8868";
    static final String SUB_PLAYLIST_EXPORT_ZH = "\u532f\u51fa";
    static final String SUB_PLAYLIST_IMPORT_ZH = "\u532f\u5165";
    static final String SUB_PLAYLIST_VIEW_ZH = "\u67e5\u770b";
    static final String SUB_PLAYLIST_REMOVE_TRACK_ZH = "\u522a\u9664\u6b4c\u66f2";
    static final String SUB_PLAYLIST_ADD_ZH = "\u65b0\u589e\u6b4c\u66f2";
    public static final String OPTION_QUERY_ZH = "query";
    public static final String OPTION_VOLUME_VALUE_ZH = "\u97f3\u91cf";
    public static final String OPTION_SPEED_VALUE_ZH = "\u500d\u901f";
    static final String SUB_DELETE_CHANNEL_ZH = "\u983b\u9053";
    static final String SUB_DELETE_USER_ZH = "\u4f7f\u7528\u8005";
    public static final String HELP_SELECT_ID = ComponentIds.HELP_SELECT_ID;
    public static final String HELP_BUTTON_PREFIX = ComponentIds.HELP_BUTTON_PREFIX;
    public static final String SETTINGS_LANGUAGE_SELECT_PREFIX = ComponentIds.SETTINGS_LANGUAGE_SELECT_PREFIX;
    public static final String SETTINGS_NUMBER_CHAIN_SELECT_PREFIX = ComponentIds.SETTINGS_NUMBER_CHAIN_SELECT_PREFIX;
    public static final String SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX = ComponentIds.SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX;
    public static final String SETTINGS_WORD_CHAIN_SELECT_PREFIX = ComponentIds.SETTINGS_WORD_CHAIN_SELECT_PREFIX;
    public static final String SETTINGS_WORD_CHAIN_CHANNEL_PREFIX = ComponentIds.SETTINGS_WORD_CHAIN_CHANNEL_PREFIX;
    public static final String TEMPLATE_MODAL_PREFIX = ComponentIds.TEMPLATE_MODAL_PREFIX;
    public static final String WELCOME_MODAL_ID = ComponentIds.WELCOME_MODAL_ID;
    public static final String PANEL_PLAY_PAUSE = ComponentIds.PANEL_PLAY_PAUSE;
    public static final String PANEL_SKIP = ComponentIds.PANEL_SKIP;
    public static final String PANEL_STOP = ComponentIds.PANEL_STOP;
    public static final String PANEL_LEAVE = ComponentIds.PANEL_LEAVE;
    public static final String PANEL_REPEAT_TOGGLE = ComponentIds.PANEL_REPEAT_TOGGLE;
    public static final String PANEL_AUTOPLAY_TOGGLE = ComponentIds.PANEL_AUTOPLAY_TOGGLE;
    public static final String PANEL_VOLUME_DOWN = ComponentIds.PANEL_VOLUME_DOWN;
    public static final String PANEL_VOLUME_UP = ComponentIds.PANEL_VOLUME_UP;
    public static final String PANEL_REFRESH = ComponentIds.PANEL_REFRESH;
    public static final String PANEL_SHUFFLE = ComponentIds.PANEL_SHUFFLE;
    private static final String KEY_UNKNOWN_COMMAND = "general.unknown_command";
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";
    private static final String CMD_VOLUME = CommandNames.CMD_VOLUME;
    private static final String CMD_HISTORY = CommandNames.CMD_HISTORY;
    private static final String CMD_PLAYLIST = CommandNames.CMD_PLAYLIST;
    private static final String CMD_LEAVE = CommandNames.CMD_LEAVE;
    private static final String CMD_REPEAT = CommandNames.CMD_REPEAT;
    private static final String CMD_MUSIC = CommandNames.CMD_MUSIC;
    private static final String OPTION_CHANNEL = CommandOptions.CHANNEL;
    private static final String OPTION_VALUE = CommandOptions.VALUE;
    private static final String OPTION_RESET = CommandOptions.RESET;
    private static final String ROUTE_LANGUAGE = "language";
    private static final String ROUTE_NUMBER_CHAIN = CommandNames.CMD_NUMBER_CHAIN;
    private static final String ROUTE_WORD_CHAIN = CommandNames.CMD_WORD_CHAIN;
    private static final String ROUTE_LOG_SETTINGS = "log-settings";
    private static final String ROUTE_ACTION = CommandOptions.ACTION;
    private static final String ROUTE_RELOAD = "reload";
    private static final String ROUTE_TEMPLATE = "template";
    private static final String ROUTE_MODULE = "module";
    private static final String VALUE_MEMBER_JOIN = "member-join";
    private static final String VALUE_MEMBER_LEAVE = "member-leave";

    private final MusicPlayerService musicService;
    private final ModerationService moderationService;
    private final HoneypotService honeypotService;
    private final AtomicReference<RuntimeConfigSnapshot> runtimeConfig = new AtomicReference<>();
    private final GuildSettingsService settingsService;
    private final AtomicReference<I18nService> i18n = new AtomicReference<>();

    private final MusicPanelStateStore panelStateStore = new MusicPanelStateStore();
    private final Map<String, Long> commandCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> panelButtonCooldowns = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> languageMenuRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> numberChainMenuRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> wordChainMenuRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<JDA> jda = new AtomicReference<>();
    private final CommandRegistrar commandRegistrar;
    private final DiscordCommandCatalog discordCommandCatalog;
    private final DeleteMessagesCommandHandler deleteMessagesCommandHandler;
    private final PrivateRoomSettingsCommandHandler privateRoomSettingsCommandHandler;
    private final WarningCommandHandler warningCommandHandler;
    private final SettingsCommandHandler settingsCommandHandler;
    private final HelpCommandHandler helpCommandHandler;
    private final PingCommandHandler pingCommandHandler;
    private final WelcomeCommandHandler welcomeCommandHandler;
    private final HistoryCommandHandler historyCommandHandler;
    private final PlaylistCommandHandler playlistCommandHandler;
    private final MusicPlaybackCommandHandler playbackCommandHandler;
    private final MusicPlaybackText musicPlaybackText;
    private final MusicPanelController musicPanelController;
    private final HoneypotCommandHandler honeypotCommandHandler;
    private final InfoCommandHandler infoCommandHandler;
    private final UrlCommandHandler urlCommandHandler;
    private final MinecraftStatusCommandHandler minecraftStatusCommandHandler;
    private final InteractionRouter interactionRouter;
    private final WordChainOps wordChainOps;
    private final TicketOps ticketOps;
    private final MessageStatsEventService statsEventService;
    private final ShortUrlService shortUrlService;
    private final GuildDomainConfigAdapter ticketConfigAdapter;
    private final MusicPanelRenderer musicPanelRenderer;
    private final MusicPanelRefreshService musicPanelRefreshService;
    private final Map<Long, Long> playbackFailureLastAt = new ConcurrentHashMap<>();
    private final Map<Long, String> playbackFailureLastSig = new ConcurrentHashMap<>();
    private final AtomicBoolean botReadyForSlashCommands = new AtomicBoolean(false);
    private static final long PANEL_PERIODIC_REFRESH_MS = 10_000L;
    private static final long PANEL_MIN_EDIT_INTERVAL_MS = 3500L;

    @SuppressWarnings("java:S107")
    public MusicCommandService(MusicPlayerService musicService,
                               RuntimeConfigSnapshot runtimeConfig,
                               GuildSettingsService settingsService,
                               ModerationService moderationService,
                               HoneypotService honeypotService,
                               ShortUrlService shortUrlService,
                               com.norule.musicbot.TicketService ticketService,
                               MessageStatsEventService statsEventService,
                               WordChainOps wordChainOps) {
        this.musicService = musicService;
        this.moderationService = moderationService;
        this.honeypotService = honeypotService;
        if (shortUrlService == null) {
            throw new IllegalArgumentException("shortUrlService cannot be null");
        }
        this.shortUrlService = shortUrlService;
        this.runtimeConfig.set(runtimeConfig);
        this.settingsService = settingsService;
        this.i18n.set(I18nService.load(runtimeConfig.getLanguageDir(), runtimeConfig.getDefaultLanguage()));
        this.musicService.setAutoplayEnabledChecker(guildId -> settingsService.getMusic(guildId).isAutoplayEnabled());
        this.musicService.setPlaybackFailureListener(this::reportPlaybackFailure);
        this.discordCommandCatalog = new DiscordCommandCatalog();
        this.commandRegistrar = new CommandRegistrar(this);
        this.settingsCommandHandler = new SettingsCommandHandler(this);
        this.helpCommandHandler = new HelpCommandHandler(this);
        this.pingCommandHandler = new PingCommandHandler(this);
        this.welcomeCommandHandler = new WelcomeCommandHandler(this);
        this.historyCommandHandler = new HistoryCommandHandler(this);
        this.musicPanelRenderer = new MusicPanelRenderer(this);
        this.musicPanelRefreshService = new MusicPanelRefreshService(
                this,
                this.panelStateStore,
                this.musicPanelRenderer,
                this.scheduler,
                PANEL_PERIODIC_REFRESH_MS,
                PANEL_MIN_EDIT_INTERVAL_MS
        );
        this.musicPanelController = new MusicPanelController(this, this::refreshPanel);
        this.musicPlaybackText = new MusicPlaybackText(this::i18nService);
        this.deleteMessagesCommandHandler = new DeleteMessagesCommandHandler(this);
        this.privateRoomSettingsCommandHandler = new PrivateRoomSettingsCommandHandler(this);
        this.warningCommandHandler = new WarningCommandHandler(this);
        this.playlistCommandHandler = new PlaylistCommandHandler(this, this.musicPanelController);
        this.playbackCommandHandler = new MusicPlaybackCommandHandler(this, this.musicPanelController, this.musicPlaybackText);
        this.honeypotCommandHandler = new HoneypotCommandHandler(this);
        this.infoCommandHandler = new InfoCommandHandler(this);
        this.urlCommandHandler = new UrlCommandHandler(this);
        MinecraftStatusOps minecraftStatusOps = new MinecraftStatusOps(
                new com.norule.musicbot.service.minecraft.MinecraftStatusService(
                        new McSrvStatGateway(),
                        () -> new MinecraftStatusConfig(this.runtimeConfig.get().getMinecraftStatus())
                )
        );
        this.minecraftStatusCommandHandler = new MinecraftStatusCommandHandler(this, minecraftStatusOps);
        this.statsEventService = statsEventService;
        this.wordChainOps = wordChainOps;
        this.ticketConfigAdapter = new GuildDomainConfigAdapter(settingsService, runtimeConfig.getDefaultMusic());
        this.ticketOps = new TicketOps(new com.norule.musicbot.discord.bot.ops.ticket.TicketService(ticketConfigAdapter, ticketService, i18nService()));
        this.interactionRouter = new InteractionRouter(this, wordChainOps);
        this.scheduler.scheduleAtFixedRate(this::refreshAllPanelsSafely, 5, PANEL_PERIODIC_REFRESH_MS / 1000L, TimeUnit.SECONDS);
        this.scheduler.scheduleAtFixedRate(this::cleanupTransientStateSafely, 1, 1, TimeUnit.MINUTES);
    }

    private void cleanupTransientStateSafely() {
        try {
            cleanupTransientState();
        } catch (RuntimeException ignored) {
            // Keep scheduled cleanup alive even if a transient repository state throws.
            LOGGER.debug("cleanupTransientState failed", ignored);
        }
    }

    private void cleanupTransientState() {
        Instant now = Instant.now();
        long nowMillis = System.currentTimeMillis();
        deleteMessagesCommandHandler.cleanupExpiredRequests(now);
        warningCommandHandler.cleanupExpiredRequests(now);
        privateRoomSettingsCommandHandler.cleanupExpiredRequests(now);
        languageMenuRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        numberChainMenuRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        wordChainMenuRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        commandCooldowns.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= nowMillis);
        panelButtonCooldowns.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() <= nowMillis);
        playbackCommandHandler.cleanupExpiredRequests(now);
        playlistCommandHandler.cleanupExpiredRequests(now);
        settingsCommandHandler.cleanupExpiredRequests(now);
        musicService.cleanupTransientCaches(nowMillis);
    }

    private void reportPlaybackFailure(long guildId, MusicPlayerService.PlaybackFailure failure) {
        if (failure == null || failure.rawError() == null || failure.rawError().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        String sig = (failure.title() == null ? "-" : failure.title()) + "|" + failure.rawError().trim();
        Long lastAt = playbackFailureLastAt.get(guildId);
        String lastSig = playbackFailureLastSig.get(guildId);
        if (lastAt != null && now - lastAt < 8000L && sig.equals(lastSig)) {
            return;
        }
        playbackFailureLastAt.put(guildId, now);
        playbackFailureLastSig.put(guildId, sig);

        JDA currentJda = jda.get();
        if (currentJda == null) {
            return;
        }
        Guild guild = currentJda.getGuildById(guildId);
        if (guild == null) {
            return;
        }
        Long channelId = musicService.getLastCommandChannelId(guildId);
        if (channelId == null) {
            MusicPanelStateStore.PanelRef ref = panelStateStore.getPanelRef(guildId);
            if (ref != null) {
                channelId = ref.channelId;
            }
        }
        if (channelId == null) {
            return;
        }
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null || !channel.canTalk()) {
            return;
        }
        String lang = lang(guildId);
        String mapped = musicPlaybackText.mapMusicLoadError(lang, failure.rawError());
        channel.sendMessage(i18nService().t(lang, "music.playback_failed", Map.of(
                        "title", safe(failure.title(), 80),
                        "error", safe(mapped, 180)
                )))
                .queue(null, error -> LOGGER.debug("Failed to send playback failure message", error));
    }

    public void reloadRuntimeConfig(RuntimeConfigSnapshot newConfig) {
        if (newConfig == null) {
            return;
        }
        this.runtimeConfig.set(newConfig);
        this.i18n.set(I18nService.load(newConfig.getLanguageDir(), newConfig.getDefaultLanguage()));
        this.ticketConfigAdapter.replaceGlobalMusic(newConfig.getDefaultMusic());
        commandRegistrar.syncCommands();
    }
    public MusicPlayerService musicService() {
        return musicService;
    }
    public ModerationService moderationService() {
        return moderationService;
    }
    public HoneypotService honeypotService() {
        return honeypotService;
    }
    public GuildSettingsService settingsService() {
        return settingsService;
    }
    public I18nService i18nService() {
        return i18n.get();
    }
    public RuntimeConfigSnapshot runtimeConfigSnapshot() {
        return runtimeConfig.get();
    }
    public JDA currentJda() {
        return jda.get();
    }
    public Map<Long, MusicPanelStateStore.PanelRef> panelRefs() {
        return panelStateStore.panelRefs();
    }
    public DeleteMessagesCommandHandler deleteMessagesCommandHandler() {
        return deleteMessagesCommandHandler;
    }
    public PrivateRoomSettingsCommandHandler privateRoomSettingsCommandHandler() {
        return privateRoomSettingsCommandHandler;
    }
    public WarningCommandHandler warningCommandHandler() {
        return warningCommandHandler;
    }
    public SettingsCommandHandler settingsCommandHandler() {
        return settingsCommandHandler;
    }
    public HelpCommandHandler helpCommandHandler() {
        return helpCommandHandler;
    }
    public PingCommandHandler pingCommandHandler() {
        return pingCommandHandler;
    }
    public WelcomeCommandHandler welcomeCommandHandler() {
        return welcomeCommandHandler;
    }
    public HistoryCommandHandler historyCommandHandler() {
        return historyCommandHandler;
    }
    public PlaylistCommandHandler playlistCommandHandler() {
        return playlistCommandHandler;
    }
    public MusicPlaybackCommandHandler playbackCommandHandler() {
        return playbackCommandHandler;
    }
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }
    public MusicPanelController musicPanelController() {
        return musicPanelController;
    }
    public HoneypotCommandHandler honeypotCommandHandler() {
        return honeypotCommandHandler;
    }

    public InfoCommandHandler infoCommandHandler() {
        return infoCommandHandler;
    }
    public UrlCommandHandler urlCommandHandler() {
        return urlCommandHandler;
    }
    public MinecraftStatusCommandHandler minecraftStatusCommandHandler() {
        return minecraftStatusCommandHandler;
    }
    public ShortUrlService shortUrlService() {
        return shortUrlService;
    }

    public TicketOps ticketOps() {
        return ticketOps;
    }
    public MessageStatsEventService statsEventService() {
        return statsEventService;
    }
    public boolean isBotReadyForSlashCommands() {
        return botReadyForSlashCommands.get();
    }

    @Override
    public void onReady(ReadyEvent event) {
        this.jda.set(event.getJDA());
        this.botReadyForSlashCommands.set(true);
        commandRegistrar.syncCommands();
        ticketOps.onReady(event);
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        // Global command registration is handled in onReady/syncCommands.
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
                ticketOps.onMessage(event);
        String raw = event.getMessage().getContentRaw();
        if (!event.isFromGuild()) {
            return;
        }
        Guild guild = event.getGuild();
        if (honeypotService.isHoneypotChannel(guild.getIdLong(), event.getChannel().getIdLong())) {
            return;
        }
        RuntimeConfigSnapshot snapshot = runtimeConfig.get();
        if (!raw.startsWith(snapshot.getPrefix())) {
            return;
        }
        String[] split = raw.substring(snapshot.getPrefix().length()).trim().split("\\s+", 2);
        String cmd = split.length > 0 ? split[0].toLowerCase() : "";
        String arg = split.length > 1 ? split[1].trim() : "";
        String lang = lang(guild.getIdLong());

        if (isKnownPrefixCommand(cmd)) {
            long remaining = acquireCooldown(event.getAuthor().getIdLong());
            if (remaining > 0) {
                event.getChannel().sendMessage(i18nService().t(lang, "general.command_cooldown",
                        Map.of("seconds", String.valueOf(toCooldownSeconds(remaining)))))
                        .queue();
                return;
            }
        }

        if (isPrefixMusicCommand(cmd) && !isMusicCommandChannelAllowed(guild, event.getChannel().getIdLong())) {
            event.getChannel().sendMessage(i18nService().t(lang, "music.command_channel_restricted")).queue();
            return;
        }

        switch (cmd) {
            case "help" -> helpCommandHandler.handleTextHelp(event.getChannel().asTextChannel(), guild, lang);
            case CMD_VOLUME -> playbackCommandHandler.handleTextCommand(event, guild, cmd, arg, lang);
            case CMD_HISTORY -> event.getChannel().sendMessageEmbeds(historyCommandHandler.historyEmbed(guild, lang).build()).queue();
            case CMD_PLAYLIST -> playlistCommandHandler.handlePlaylistPrefix(event, guild, arg, lang);
            case "join", "play", "skip", "stop", CMD_LEAVE, CMD_REPEAT -> playbackCommandHandler.handleTextCommand(event, guild, cmd, arg, lang);
            case CMD_MUSIC -> event.getChannel().sendMessageEmbeds(musicStatsEmbed(guild, lang).build()).queue();
            default -> event.getChannel().sendMessage(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).queue();
        }
        if (isKnownPrefixCommand(cmd)) {
            logCommandUsage(guild, event.getMember(), snapshot.getPrefix() + cmd + (arg.isBlank() ? "" : " " + arg), event.getChannel().getIdLong());
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        dispatchSlashFromGateway(event);
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        dispatchAutoCompleteFromGateway(event);
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        dispatchStringSelectFromGateway(event);
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        dispatchModalFromGateway(event);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        dispatchButtonFromGateway(event);
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        dispatchEntitySelectFromGateway(event);
    }

    public void dispatchSlashFromGateway(SlashCommandInteractionEvent event) {
        interactionRouter.onSlashCommandInteraction(event);
    }

    public void dispatchAutoCompleteFromGateway(CommandAutoCompleteInteractionEvent event) {
        interactionRouter.onCommandAutoCompleteInteraction(event);
    }

    public void dispatchStringSelectFromGateway(StringSelectInteractionEvent event) {
        interactionRouter.onStringSelectInteraction(event);
    }

    public void dispatchModalFromGateway(ModalInteractionEvent event) {
        interactionRouter.onModalInteraction(event);
    }

    public void dispatchButtonFromGateway(ButtonInteractionEvent event) {
        interactionRouter.onButtonInteraction(event);
    }

    public void dispatchEntitySelectFromGateway(EntitySelectInteractionEvent event) {
        interactionRouter.onEntitySelectInteraction(event);
    }
    private String registerMenuRequest(Map<String, MenuRequest> requests, long requestUserId, long guildId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        requests.put(token, new MenuRequest(
                requestUserId,
                guildId,
                Instant.now().plusSeconds(120)
        ));
        return token;
    }

    private String quotedSettingLine(String lang, String key, String labelKey, String value) {
        return keyIcon(key) + " " + i18nService().t(lang, key) + "\n> " + i18nService().t(lang, labelKey) + ": " + value;
    }

    public void setRepeat(Guild guild, String input) {
        musicService.setRepeatMode(guild, normalizeRepeat(input));
    }

    private String normalizeRepeat(String input) {
        if (input == null) {
            return "OFF";
        }
        String t = input.trim().toUpperCase();
        if ("SINGLE".equals(t) || "ONE".equals(t)) {
            return "SINGLE";
        }
        if ("ALL".equals(t) || "QUEUE".equals(t)) {
            return "ALL";
        }
        return "OFF";
    }

    private boolean isAutoplayEnabled(long guildId) {
        return settingsService.getMusic(guildId).isAutoplayEnabled();
    }
    public boolean isAutoplayEnabledForSettings(long guildId) {
        return isAutoplayEnabled(guildId);
    }

    public void toggleAutoplay(long guildId) {
        settingsService.updateSettings(guildId, s -> s.withMusic(s.getMusic().withAutoplayEnabled(!s.getMusic().isAutoplayEnabled())));
        musicService.clearAutoplayNotice(guildId);
    }

    public EmbedBuilder helpEmbed(Guild guild, String lang, String category) {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(new Color(52, 152, 219));
        eb.setTitle("NoRule Help Center");
        String botDesc = runtimeConfig.get().getBotDescription();
        String intro = i18nService().t(lang, "help.intro");
        if (botDesc != null && !botDesc.isBlank()) {
            eb.setDescription(botDesc + "\n\n" + intro);
        } else {
            eb.setDescription(intro);
        }
        eb.setFooter(guild.getName(), guild.getIconUrl());
        switch (category) {
            case CMD_MUSIC -> eb.addField(i18nService().t(lang, "help.category_music"), i18nService().t(lang, "help.content_music"), false);
            case "settings" -> eb.addField(i18nService().t(lang, "help.category_settings"), i18nService().t(lang, "help.content_settings"), false);
            case "moderation" -> eb.addField(i18nService().t(lang, "help.category_moderation"), i18nService().t(lang, "help.content_moderation"), false);
            case "private-room" -> eb.addField(i18nService().t(lang, "help.category_private_room"), i18nService().t(lang, "help.content_private_room"), false);
            case "ticket" -> eb.addField(i18nService().t(lang, "help.category_ticket"), i18nService().t(lang, "help.content_ticket"), false);
            case "game" -> eb.addField(i18nService().t(lang, "help.category_game"), i18nService().t(lang, "help.content_game"), false);
            default -> eb.addField(i18nService().t(lang, "help.category_general"), i18nService().t(lang, "help.content_general"), false);
        }
        eb.addField(i18nService().t(lang, "help.tip_title"), i18nService().t(lang, "help.tip_body"), false);
        return eb;
    }

    public String previewWelcomeText(String text, Guild guild, User user) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replace("{user}", user.getAsMention())
                .replace("{\u7528\u6236}", user.getAsMention())
                .replace("{username}", user.getName())
                .replace("{\u7528\u6236\u540d\u7a31}", user.getName())
                .replace("{guild}", guild.getName())
                .replace("{\u4f3a\u670d\u5668}", guild.getName())
                .replace("{\u516c\u6703}", guild.getName())
                .replace("{id}", user.getId())
                .replace("{tag}", user.getAsTag())
                .replace("{isBot}", String.valueOf(user.isBot()))
                .replace("{createdAt}", "<t:" + user.getTimeCreated().toInstant().getEpochSecond() + ":F>")
                .replace("{accountAgeDays}", String.valueOf(Math.max(0L, Duration.between(user.getTimeCreated().toInstant(), Instant.now()).toDays())));
    }
    public void handleMusicSlash(SlashCommandInteractionEvent event, String lang) {
        String sub = canonicalMusicSubcommand(event.getSubcommandName());
        if (sub == null || sub.isBlank()) {
            event.replyEmbeds(musicStatsEmbed(event.getGuild(), lang).build()).queue();
            return;
        }
        if ("stats".equals(sub)) {
            event.replyEmbeds(musicStatsEmbed(event.getGuild(), lang).build()).queue();
            return;
        }
        event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
    }

    public StringSelectMenu helpMenu(String lang) {
        return StringSelectMenu.create(HELP_SELECT_ID)
                .setPlaceholder(i18nService().t(lang, "help.select_placeholder"))
                .addOptions(
                        SelectOption.of(i18nService().t(lang, "help.category_general"), "general"),
                        SelectOption.of(i18nService().t(lang, "help.category_music"), CMD_MUSIC),
                        SelectOption.of(i18nService().t(lang, "help.category_settings"), "settings"),
                        SelectOption.of(i18nService().t(lang, "help.category_moderation"), "moderation"),
                        SelectOption.of(i18nService().t(lang, "help.category_private_room"), "private-room"),
                        SelectOption.of(i18nService().t(lang, "help.category_ticket"), "ticket"),
                        SelectOption.of(i18nService().t(lang, "help.category_game"), "game")
                )
                .build();
    }

    public List<Button> helpButtonsPrimary(String lang, String selectedCategory) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(categoryButton(lang, "general", selectedCategory, i18nService().t(lang, "help.category_general")));
        buttons.add(categoryButton(lang, CMD_MUSIC, selectedCategory, i18nService().t(lang, "help.category_music")));
        buttons.add(categoryButton(lang, "settings", selectedCategory, i18nService().t(lang, "help.category_settings")));
        buttons.add(categoryButton(lang, "moderation", selectedCategory, i18nService().t(lang, "help.category_moderation")));
        buttons.add(categoryButton(lang, "private-room", selectedCategory, i18nService().t(lang, "help.category_private_room")));
        return buttons;
    }

    public List<Button> helpButtonsSecondary(String lang, String selectedCategory) {
        return List.of(
                categoryButton(lang, "game", selectedCategory, i18nService().t(lang, "help.category_game")),
                categoryButton(lang, "ticket", selectedCategory, i18nService().t(lang, "help.category_ticket"))
        );
    }

    private Button categoryButton(String lang, String category, String selectedCategory, String label) {
        if (category.equals(selectedCategory)) {
            return Button.success(HELP_BUTTON_PREFIX + category, label).asDisabled();
        }
        return Button.secondary(HELP_BUTTON_PREFIX + category, label);
    }

    public void createPanelMessageWithFeedback(Guild guild, TextChannel channel, String lang, Runnable onSuccess, java.util.function.Consumer<String> onError) {
        musicPanelRefreshService.createPanelMessageWithFeedback(guild, channel, lang, onSuccess, onError);
    }

    public void refreshPanel(long guildId) {
        musicPanelRefreshService.refreshPanel(guildId);
    }

    private void refreshPanelPeriodic(long guildId) {
        musicPanelRefreshService.refreshPanelPeriodic(guildId);
    }

    public void refreshPanelMessage(Guild guild, TextChannel channel, long messageId, boolean force) {
        musicPanelRefreshService.refreshPanelMessage(guild, channel, messageId, force);
    }

    public void refreshPanelMessage(Guild guild, TextChannel channel, long messageId, boolean force, boolean immediate) {
        musicPanelRefreshService.refreshPanelMessage(guild, channel, messageId, force, immediate);
    }

    boolean isPanelButton(String componentId) {
        return PANEL_PLAY_PAUSE.equals(componentId)
                || PANEL_SKIP.equals(componentId)
                || PANEL_STOP.equals(componentId)
                || PANEL_LEAVE.equals(componentId)
                || PANEL_REPEAT_TOGGLE.equals(componentId)
                || PANEL_AUTOPLAY_TOGGLE.equals(componentId)
                || PANEL_VOLUME_DOWN.equals(componentId)
                || PANEL_VOLUME_UP.equals(componentId)
                || PANEL_REFRESH.equals(componentId)
                || PANEL_SHUFFLE.equals(componentId);
    }

    public int adjustPanelVolume(Guild guild, int delta) {
        int current = musicService.getVolume(guild);
        int target = Math.max(1, Math.min(100, current + delta));
        return musicService.setVolume(guild, target);
    }

    public List<CommandData> buildCommands() {
        return discordCommandCatalog.buildCommands();
    }

    public void handleAntiDuplicateSlash(SlashCommandInteractionEvent event, String lang) {
        if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(i18nService().t(lang, "general.missing_permissions",
                            Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String sub = event.getSubcommandName();
        if ((sub == null || sub.isBlank()) && event.getOption(ROUTE_ACTION) != null) {
            sub = event.getOption(ROUTE_ACTION).getAsString();
        }
        long guildId = event.getGuild().getIdLong();
        if ("enable".equals(sub)) {
            boolean enabled = event.getOption(OPTION_VALUE) == null
                    ? !moderationService.isDuplicateDetectionEnabled(guildId)
                    : event.getOption(OPTION_VALUE).getAsBoolean();
            if (enabled) {
                Member self = event.getGuild().getSelfMember();
                List<String> missingBotPermissions = new ArrayList<>();
                if (!self.hasPermission(event.getGuildChannel(), Permission.MESSAGE_MANAGE)) {
                    missingBotPermissions.add(Permission.MESSAGE_MANAGE.getName());
                }
                if (!self.hasPermission(Permission.MODERATE_MEMBERS)) {
                    missingBotPermissions.add(Permission.MODERATE_MEMBERS.getName());
                }
                if (!missingBotPermissions.isEmpty()) {
                    event.reply(i18nService().t(lang, "anti_duplicate.missing_bot_permissions",
                                    Map.of("permissions", String.join(", ", missingBotPermissions))))
                            .setEphemeral(true)
                            .queue();
                    return;
                }
            }
            moderationService.setDuplicateDetectionEnabled(guildId, enabled);
            event.reply(i18nService().t(lang, "anti_duplicate.result_set",
                            Map.of("status", boolText(lang, enabled))))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        boolean enabled = moderationService.isDuplicateDetectionEnabled(guildId);
        event.reply(i18nService().t(lang, "anti_duplicate.result_status",
                        Map.of("status", boolText(lang, enabled))))
                .setEphemeral(true)
                .queue();
    }
    public void openLanguageMenu(SlashCommandInteractionEvent event, String lang) {
        String token = registerMenuRequest(languageMenuRequests, event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(52, 152, 219))
                        .setTitle(i18nService().t(lang, "settings.language_menu_title"))
                        .setDescription(i18nService().t(lang, "settings.language_menu_desc"))
                        .build())
                .addComponents(ActionRow.of(settingsLanguageMenu(token, event.getGuild().getIdLong(), lang)))
                .setEphemeral(true)
                .queue();
    }

    public void openLanguageMenu(StringSelectInteractionEvent event, String lang) {
        String token = registerMenuRequest(languageMenuRequests, event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(52, 152, 219))
                        .setTitle(i18nService().t(lang, "settings.language_menu_title"))
                        .setDescription(i18nService().t(lang, "settings.language_menu_desc"))
                        .build())
                .setComponents(ActionRow.of(settingsLanguageMenu(token, event.getGuild().getIdLong(), lang)))
                .queue();
    }

    public void openNumberChainMenu(SlashCommandInteractionEvent event, String lang) {
        String token = registerMenuRequest(numberChainMenuRequests, event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.replyEmbeds(numberChainMenuEmbed(event.getGuild(), lang, null).build())
                .addComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                .setEphemeral(true)
                .queue();
    }

    public void openNumberChainMenu(StringSelectInteractionEvent event, String lang) {
        String token = registerMenuRequest(numberChainMenuRequests, event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.editMessageEmbeds(numberChainMenuEmbed(event.getGuild(), lang, null).build())
                .setComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                .queue();
    }

    private StringSelectMenu settingsNumberChainMenu(String token, Guild guild, String lang) {
        long guildId = guild.getIdLong();
        boolean enabled = moderationService.isNumberChainEnabled(guildId);
        Long channelId = moderationService.getNumberChainChannelId(guildId);
        long next = moderationService.getNumberChainNext(guildId);
        return StringSelectMenu.create(SETTINGS_NUMBER_CHAIN_SELECT_PREFIX + token)
                .setPlaceholder(i18nService().t(lang, "settings.number_chain_menu_placeholder"))
                .addOptions(
                        SelectOption.of(i18nService().t(lang, "settings.info_key_number_chain_enabled"), "enable-toggle")
                                .withDescription(i18nService().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, boolText(lang, enabled)))),
                        SelectOption.of(i18nService().t(lang, "settings.info_key_number_chain_channel"), "set-channel")
                                .withDescription(i18nService().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, safe(formatTextChannel(guild, channelId), 60)))),
                        SelectOption.of(i18nService().t(lang, "settings.info_key_number_chain_next"), OPTION_RESET)
                                .withDescription(i18nService().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, String.valueOf(next))))
                )
                .build();
    }

    private EmbedBuilder numberChainMenuEmbed(Guild guild, String lang, String changedText) {
        long guildId = guild.getIdLong();
        String body = String.join("\n\n",
                quotedSettingLine(lang, "settings.info_key_number_chain_enabled", "settings.status_label",
                        boolText(lang, moderationService.isNumberChainEnabled(guildId))),
                quotedSettingLine(lang, "settings.info_key_number_chain_channel", "settings.value_label",
                        formatTextChannel(guild, moderationService.getNumberChainChannelId(guildId))),
                quotedSettingLine(lang, "settings.info_key_number_chain_next", "settings.value_label",
                        String.valueOf(moderationService.getNumberChainNext(guildId))),
                "\uD83C\uDFC6 " + numberChainHighestLabel(lang) + "\n> " + i18nService().t(lang, "settings.value_label") + ": "
                        + moderationService.getNumberChainHighestNumber(guildId),
                "\uD83D\uDC65 " + numberChainTopContributorsLabel(lang) + "\n> "
                        + formatNumberChainTopContributors(guild, lang)
        );
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(46, 204, 113))
                .setTitle("\uD83D\uDD22 " + i18nService().t(lang, "settings.number_chain_menu_title"))
                .setDescription(i18nService().t(lang, "settings.number_chain_menu_desc"))
                .addField("\uD83D\uDD22 " + i18nService().t(lang, "settings.info_number_chain"), body, false);
        if (changedText != null && !changedText.isBlank()) {
            eb.addField(i18nService().t(lang, "settings.template_updated"), changedText, false);
        }
        return eb;
    }
    public void handleNumberChainMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_NUMBER_CHAIN_SELECT_PREFIX.length());
        MenuRequest request = numberChainMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            numberChainMenuRequests.remove(token);
            event.reply(i18nService().t(lang, "settings.number_chain_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        switch (action) {
            case "enable-toggle" -> {
                boolean value = !moderationService.isNumberChainEnabled(guildId);
                moderationService.setNumberChainEnabled(guildId, value);
                String changed = i18nService().t(lang, "general.settings_saved",
                        Map.of("key", i18nService().t(lang, "settings.info_key_number_chain_enabled"), OPTION_VALUE, boolText(lang, value)));
                event.editMessageEmbeds(numberChainMenuEmbed(event.getGuild(), lang, changed).build())
                        .setComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                        .queue();
            }
            case "set-channel" -> {
                EntitySelectMenu channelMenu = EntitySelectMenu
                        .create(SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX + token, EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT)
                        .setRequiredRange(1, 1)
                        .setPlaceholder(i18nService().t(lang, "settings.number_chain_menu_channel_placeholder"))
                        .build();
                event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(46, 204, 113))
                                .setTitle(i18nService().t(lang, "settings.number_chain_menu_pick_channel_title"))
                                .setDescription(i18nService().t(lang, "settings.number_chain_menu_pick_channel_desc"))
                                .build())
                        .setComponents(ActionRow.of(channelMenu))
                        .queue();
            }
            case OPTION_RESET -> {
                moderationService.resetNumberChain(guildId);
                String changed = i18nService().t(lang, "number_chain.result_reset");
                event.editMessageEmbeds(numberChainMenuEmbed(event.getGuild(), lang, changed).build())
                        .setComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                        .queue();
            }
            default -> event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
        }
    }
    public void handleNumberChainChannelSelect(EntitySelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX.length());
        MenuRequest request = numberChainMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            numberChainMenuRequests.remove(token);
            event.reply(i18nService().t(lang, "settings.number_chain_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }

        List<IMentionable> values = event.getValues();
        if (values.isEmpty() || !(values.get(0) instanceof TextChannel channel)) {
            event.reply(i18nService().t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        moderationService.setNumberChainChannelId(guildId, channel.getIdLong());
        moderationService.resetNumberChain(guildId);
        String changed = i18nService().t(lang, "number_chain.result_set_channel", Map.of(OPTION_CHANNEL, channel.getAsMention()));
        event.editMessageEmbeds(numberChainMenuEmbed(event.getGuild(), lang, changed).build())
                .setComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                .queue();
    }

    public void openWordChainMenu(SlashCommandInteractionEvent event, String lang) {
        if (wordChainOps == null) {
            event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String token = registerMenuRequest(wordChainMenuRequests, event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.deferReply(true).queue(hook -> wordChainOps.status(event.getGuild().getIdLong())
                .thenAccept(status -> hook.editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, status, null).build())
                        .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, status)))
                        .queue())
                .exceptionally(error -> {
                    hook.editOriginal(i18nService().t(lang, "general.action_failed")).queue();
                    return null;
                }));
    }

    public void openWordChainMenu(StringSelectInteractionEvent event, String lang) {
        if (wordChainOps == null) {
            event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String token = registerMenuRequest(wordChainMenuRequests, event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.deferEdit().queue(ignored -> wordChainOps.status(event.getGuild().getIdLong())
                .thenAccept(status -> event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, status, null).build())
                        .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, status)))
                        .queue())
                .exceptionally(error -> {
                    event.getHook().editOriginal(i18nService().t(lang, "general.action_failed")).queue();
                    return null;
                }));
    }

    private StringSelectMenu settingsWordChainMenu(String token, Guild guild, String lang, WordChainStatusSnapshot status) {
        return StringSelectMenu.create(SETTINGS_WORD_CHAIN_SELECT_PREFIX + token)
                .setPlaceholder(i18nService().t(lang, "settings.word_chain_menu_placeholder"))
                .addOptions(
                        SelectOption.of(i18nService().t(lang, "settings.info_key_word_chain_enabled"), "enable-toggle")
                                .withDescription(i18nService().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, boolText(lang, status.enabled())))),
                        SelectOption.of(i18nService().t(lang, "settings.info_key_word_chain_channel"), "set-channel")
                                .withDescription(i18nService().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, safe(formatTextChannel(guild, status.channelId()), 60)))),
                        SelectOption.of(i18nService().t(lang, "settings.info_key_word_chain_next"), OPTION_RESET)
                                .withDescription(i18nService().t(lang, "settings.music_menu_current",
                                        Map.of(OPTION_VALUE, wordChainNextLetter(status))))
                )
                .build();
    }

    private EmbedBuilder wordChainMenuEmbed(Guild guild, String lang, WordChainStatusSnapshot status, String changedText) {
        String body = String.join("\n\n",
                quotedSettingLine(lang, "settings.info_key_word_chain_enabled", "settings.status_label",
                        boolText(lang, status.enabled())),
                quotedSettingLine(lang, "settings.info_key_word_chain_channel", "settings.value_label",
                        formatTextChannel(guild, status.channelId())),
                quotedSettingLine(lang, "settings.info_key_word_chain_last_word", "settings.value_label",
                        status.lastWord() == null || status.lastWord().isBlank() ? "-" : status.lastWord()),
                quotedSettingLine(lang, "settings.info_key_word_chain_next", "settings.value_label",
                        wordChainNextLetter(status)),
                quotedSettingLine(lang, "settings.info_key_word_chain_chain_count", "settings.value_label",
                        String.valueOf(status.chainCount()))
        );
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(46, 204, 113))
                .setTitle("\uD83D\uDD20 " + i18nService().t(lang, "settings.word_chain_menu_title"))
                .setDescription(i18nService().t(lang, "settings.word_chain_menu_desc"))
                .addField("\uD83D\uDD20 " + i18nService().t(lang, "settings.info_word_chain"), body, false);
        if (changedText != null && !changedText.isBlank()) {
            eb.addField(i18nService().t(lang, "settings.template_updated"), changedText, false);
        }
        return eb;
    }

    public void handleWordChainMenuSelect(StringSelectInteractionEvent event, String lang) {
        if (wordChainOps == null) {
            event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String token = event.getComponentId().substring(SETTINGS_WORD_CHAIN_SELECT_PREFIX.length());
        MenuRequest request = wordChainMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            wordChainMenuRequests.remove(token);
            event.reply(i18nService().t(lang, "settings.word_chain_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }
        long guildId = event.getGuild().getIdLong();
        String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        switch (action) {
            case "enable-toggle" -> event.deferEdit().queue(ignored -> wordChainOps.status(guildId)
                    .thenAccept(current -> {
                        if (current.enabled()) {
                            wordChainOps.disable(guildId)
                                    .thenAccept(updated -> {
                                        String changed = i18nService().t(lang, "general.settings_saved",
                                                Map.of("key", i18nService().t(lang, "settings.info_key_word_chain_enabled"),
                                                        OPTION_VALUE, boolText(lang, updated.enabled())));
                                        event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, updated, changed).build())
                                                .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, updated)))
                                                .queue();
                                    })
                                    .exceptionally(error -> {
                                        event.getHook().editOriginal(i18nService().t(lang, "general.action_failed")).queue();
                                        return null;
                                    });
                            return;
                        }
                        if (current.channelId() == null) {
                            String changed = i18nService().t(lang, "settings.word_chain_channel_required");
                            event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, current, changed).build())
                                    .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, current)))
                                    .queue();
                            return;
                        }
                        wordChainOps.setChannel(guildId, current.channelId())
                                .thenAccept(updated -> {
                                    String changed = i18nService().t(lang, "general.settings_saved",
                                            Map.of("key", i18nService().t(lang, "settings.info_key_word_chain_enabled"),
                                                    OPTION_VALUE, boolText(lang, updated.enabled())));
                                    event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, updated, changed).build())
                                            .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, updated)))
                                            .queue();
                                })
                                .exceptionally(error -> {
                                    event.getHook().editOriginal(i18nService().t(lang, "general.action_failed")).queue();
                                    return null;
                                });
                    })
                    .exceptionally(error -> {
                        event.getHook().editOriginal(i18nService().t(lang, "general.action_failed")).queue();
                        return null;
                    }));
            case "set-channel" -> {
                EntitySelectMenu channelMenu = EntitySelectMenu
                        .create(SETTINGS_WORD_CHAIN_CHANNEL_PREFIX + token, EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT)
                        .setRequiredRange(1, 1)
                        .setPlaceholder(i18nService().t(lang, "settings.word_chain_menu_channel_placeholder"))
                        .build();
                event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(46, 204, 113))
                                .setTitle(i18nService().t(lang, "settings.word_chain_menu_pick_channel_title"))
                                .setDescription(i18nService().t(lang, "settings.word_chain_menu_pick_channel_desc"))
                                .build())
                        .setComponents(ActionRow.of(channelMenu))
                        .queue();
            }
            case OPTION_RESET -> event.deferEdit().queue(ignored -> wordChainOps.reset(guildId)
                    .thenAccept(status -> {
                        String changed = i18nService().t(lang, "general.settings_saved",
                                Map.of("key", i18nService().t(lang, "settings.info_key_word_chain_chain_count"), OPTION_VALUE, "0"));
                        event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, status, changed).build())
                                .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, status)))
                                .queue();
                    })
                    .exceptionally(error -> {
                        event.getHook().editOriginal(i18nService().t(lang, "general.action_failed")).queue();
                        return null;
                    }));
            default -> event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
        }
    }

    public void handleWordChainChannelSelect(EntitySelectInteractionEvent event, String lang) {
        if (wordChainOps == null) {
            event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String token = event.getComponentId().substring(SETTINGS_WORD_CHAIN_CHANNEL_PREFIX.length());
        MenuRequest request = wordChainMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            wordChainMenuRequests.remove(token);
            event.reply(i18nService().t(lang, "settings.word_chain_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }
        List<IMentionable> values = event.getValues();
        if (values.isEmpty() || !(values.get(0) instanceof TextChannel channel)) {
            event.reply(i18nService().t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
            return;
        }
        event.deferEdit().queue(ignored -> wordChainOps.setChannel(event.getGuild().getIdLong(), channel.getIdLong())
                .thenAccept(status -> {
                    String changed = i18nService().t(lang, "general.settings_saved",
                            Map.of("key", i18nService().t(lang, "settings.info_key_word_chain_channel"), OPTION_VALUE, channel.getAsMention()));
                    event.getHook().editOriginalEmbeds(wordChainMenuEmbed(event.getGuild(), lang, status, changed).build())
                            .setComponents(ActionRow.of(settingsWordChainMenu(token, event.getGuild(), lang, status)))
                            .queue();
                })
                .exceptionally(error -> {
                    event.getHook().editOriginal(i18nService().t(lang, "general.action_failed")).queue();
                    return null;
                }));
    }

    private String wordChainNextLetter(WordChainStatusSnapshot status) {
        if (status == null || status.nextRequiredStartLetter() == null) {
            return "-";
        }
        return String.valueOf(status.nextRequiredStartLetter());
    }

    private StringSelectMenu settingsLanguageMenu(String token, long guildId, String lang) {
        String current = settingsService.getLanguage(guildId);
        StringSelectMenu.Builder builder = StringSelectMenu.create(SETTINGS_LANGUAGE_SELECT_PREFIX + token)
                .setPlaceholder(i18nService().t(lang, "settings.language_menu_placeholder"));
        int count = 0;
        for (Map.Entry<String, String> entry : i18nService().getAvailableLanguages().entrySet()) {
            if (count >= 25) {
                break;
            }
            String code = entry.getKey();
            String name = entry.getValue() == null || entry.getValue().isBlank() ? code : entry.getValue();
            builder.addOptions(
                    SelectOption.of(code + " - " + name, code).withDefault(code.equalsIgnoreCase(current))
            );
            count++;
        }
        return builder.build();
    }

    public void handleLanguageMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_LANGUAGE_SELECT_PREFIX.length());
        MenuRequest request = languageMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            languageMenuRequests.remove(token);
            event.reply(i18nService().t(lang, "settings.language_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }
        String code = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        if (!i18nService().hasLanguage(code)) {
            event.reply(i18nService().t(lang, "settings.language_invalid", Map.of(ROUTE_LANGUAGE, code))).setEphemeral(true).queue();
            return;
        }
        String normalized = i18nService().normalizeLanguage(code);
        settingsService.updateSettings(event.getGuild().getIdLong(), s -> s.withLanguage(normalized));
        String languageDisplay = languageDisplayText(normalized);
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(i18nService().t(normalized, "settings.language_menu_title"))
                        .setDescription(i18nService().t(normalized, "settings.language_updated", Map.of(ROUTE_LANGUAGE, languageDisplay)))
                        .build())
                .setComponents(ActionRow.of(settingsLanguageMenu(token, event.getGuild().getIdLong(), normalized)))
                .queue();
    }




    public String canonicalSlashName(String name) {
        return switch (name) {
            case CMD_HELP_ZH -> "help";
            case CMD_PING_ZH -> "ping";
            case CMD_WELCOME_ZH -> "welcome";
            case CMD_VOLUME_ZH -> CMD_VOLUME;
            case CMD_HISTORY_ZH -> CMD_HISTORY;
            case CMD_MUSIC_ZH -> CMD_MUSIC;
            case CMD_PLAYLIST_ZH -> CMD_PLAYLIST;
            case CMD_JOIN_ZH -> "join";
            case CMD_PLAY_ZH -> "play";
            case CMD_SKIP_ZH -> "skip";
            case CMD_STOP_ZH -> "stop";
            case CMD_LEAVE_ZH -> CMD_LEAVE;
            case CMD_MUSIC_PANEL_ZH -> "music-panel";
            case CMD_REPEAT_ZH -> CMD_REPEAT;
            case CMD_SETTINGS_ZH -> "settings";
            case CMD_DELETE_ZH -> "delete-messages";
            case CMD_ROOM_SETTINGS_ZH -> "private-room-settings";
            case CMD_WARNINGS_ZH -> "warnings";
            case CMD_ANTI_DUPLICATE_ZH -> "anti-duplicate";
            case CMD_HONEYPOT_ZH -> "honeypot-channel";
            case CMD_NUMBER_CHAIN_ZH -> ROUTE_NUMBER_CHAIN;
            case CMD_WORD_CHAIN_ZH -> ROUTE_WORD_CHAIN;
            case CMD_TICKET_ZH -> "ticket";
            case CMD_USER_INFO_ZH -> "user-info";
            case CMD_ROLE_INFO_ZH -> "role-info";
            case CMD_SERVER_INFO_ZH -> "server-info";
            case CMD_STATS_ZH -> "stats";
            case CMD_LEADERBOARD_ZH -> "top";
            case CMD_SHORT_URL_ZH -> "url";
            case CMD_MINECRAFT_STATUS_ZH -> "mcstatus";
            default -> name;
        };
    }
    public String canonicalSettingsSubcommand(String sub) {
        return switch (sub) {
            case SUB_SETTINGS_INFO_ZH -> "info";
            case SUB_SETTINGS_RELOAD_ZH -> ROUTE_RELOAD;
            case SUB_SETTINGS_RESET_ZH -> OPTION_RESET;
            case SUB_SETTINGS_TEMPLATE_ZH -> ROUTE_TEMPLATE;
            case SUB_SETTINGS_MODULE_ZH -> ROUTE_MODULE;
            case SUB_SETTINGS_LOGS_ZH -> "logs";
            case SUB_SETTINGS_LOG_SETTINGS_ZH -> ROUTE_LOG_SETTINGS;
            case SUB_SETTINGS_MUSIC_ZH -> CMD_MUSIC;
            case SUB_SETTINGS_LANGUAGE_ZH -> ROUTE_LANGUAGE;
            case SUB_SETTINGS_NUMBER_CHAIN_ZH -> ROUTE_NUMBER_CHAIN;
            case SUB_SETTINGS_WORD_CHAIN_ZH -> ROUTE_WORD_CHAIN;
            default -> sub;
        };
    }

    private String canonicalDeleteSubcommand(String sub) {
        return switch (sub) {
            case SUB_DELETE_CHANNEL_ZH -> OPTION_CHANNEL;
            case SUB_DELETE_USER_ZH -> "user";
            default -> sub;
        };
    }

    private String canonicalMusicSubcommand(String sub) {
        return switch (sub) {
            case SUB_MUSIC_STATS_ZH -> "stats";
            default -> sub;
        };
    }
    public String canonicalPlaylistSubcommand(String sub) {
        return switch (sub) {
            case SUB_PLAYLIST_SAVE_ZH -> "save";
            case SUB_PLAYLIST_LOAD_ZH -> "load";
            case SUB_PLAYLIST_ADD_ZH -> "add";
            case SUB_PLAYLIST_DELETE_ZH -> "delete";
            case SUB_PLAYLIST_LIST_ZH -> "list";
            case SUB_PLAYLIST_VIEW_ZH -> "view";
            case SUB_PLAYLIST_REMOVE_TRACK_ZH -> "remove-track";
            case SUB_PLAYLIST_EXPORT_ZH -> "export";
            case SUB_PLAYLIST_IMPORT_ZH -> "import";
            default -> sub;
        };
    }
    public String lang(long guildId) {
        return settingsService.getLanguage(guildId);
    }

    private boolean isPrefixMusicCommand(String cmd) {
        return "join".equals(cmd)
                || "play".equals(cmd)
                || "skip".equals(cmd)
                || "stop".equals(cmd)
                || CMD_LEAVE.equals(cmd)
                || CMD_REPEAT.equals(cmd)
                || CMD_VOLUME.equals(cmd)
                || CMD_HISTORY.equals(cmd)
                || CMD_MUSIC.equals(cmd)
                || CMD_PLAYLIST.equals(cmd);
    }

    private boolean isKnownPrefixCommand(String cmd) {
        return "help".equals(cmd)
                || CMD_VOLUME.equals(cmd)
                || CMD_HISTORY.equals(cmd)
                || CMD_MUSIC.equals(cmd)
                || CMD_PLAYLIST.equals(cmd)
                || "join".equals(cmd)
                || "play".equals(cmd)
                || "skip".equals(cmd)
                || "stop".equals(cmd)
                || CMD_LEAVE.equals(cmd)
                || CMD_REPEAT.equals(cmd);
    }
    public boolean isSlashMusicCommand(String name) {
        name = canonicalSlashName(name);
        return "join".equals(name)
                || "play".equals(name)
                || "skip".equals(name)
                || "stop".equals(name)
                || CMD_LEAVE.equals(name)
                || CMD_REPEAT.equals(name)
                || CMD_VOLUME.equals(name)
                || CMD_HISTORY.equals(name)
                || CMD_MUSIC.equals(name)
                || CMD_PLAYLIST.equals(name)
                || "music-panel".equals(name);
    }
    public boolean isKnownSlashCommand(String name) {
        name = canonicalSlashName(name);
        return "help".equals(name)
                || "ping".equals(name)
                || "welcome".equals(name)
                || CMD_VOLUME.equals(name)
                || CMD_HISTORY.equals(name)
                || CMD_MUSIC.equals(name)
                || CMD_PLAYLIST.equals(name)
                || "join".equals(name)
                || "play".equals(name)
                || "skip".equals(name)
                || "stop".equals(name)
                || CMD_LEAVE.equals(name)
                || "music-panel".equals(name)
                || CMD_REPEAT.equals(name)
                || "settings".equals(name)
                || "delete-messages".equals(name)
                || "private-room-settings".equals(name)
                || "warnings".equals(name)
                || "anti-duplicate".equals(name)
                || "honeypot-channel".equals(name)
                || ROUTE_NUMBER_CHAIN.equals(name)
                || ROUTE_WORD_CHAIN.equals(name)
                || "user-info".equals(name)
                || "role-info".equals(name)
                || "server-info".equals(name)
                || "url".equals(name)
                || "mcstatus".equals(name)
                || "stats".equals(name)
                || "top".equals(name);
    }
    public boolean isMusicCommandChannelAllowed(Guild guild, long channelId) {
        Long configured = settingsService.getMusic(guild.getIdLong()).getCommandChannelId();
        return configured == null || configured == channelId;
    }
    public boolean has(Member member, Permission permission) {
        return member != null && member.hasPermission(permission);
    }
    public String buildSlashRoute(SlashCommandInteractionEvent event) {
        String command = canonicalSlashName(event.getName());
        String group = event.getSubcommandGroup();
        String sub = event.getSubcommandName();
        if ("settings".equals(command) && sub != null) {
            sub = canonicalSettingsSubcommand(sub);
        } else if ("settings".equals(command) && event.getOption(ROUTE_ACTION) != null) {
            sub = canonicalSettingsSubcommand(event.getOption(ROUTE_ACTION).getAsString());
        } else if (CMD_MUSIC.equals(command) && sub != null) {
            sub = canonicalMusicSubcommand(sub);
        } else if (CMD_PLAYLIST.equals(command) && sub != null) {
            sub = canonicalPlaylistSubcommand(sub);
        }
        if (("warnings".equals(command) || "anti-duplicate".equals(command) || "ticket".equals(command))
                && sub == null && event.getOption(ROUTE_ACTION) != null) {
            sub = event.getOption(ROUTE_ACTION).getAsString();
        }
        if ("delete-messages".equals(command) && sub != null) {
            sub = canonicalDeleteSubcommand(sub);
        }
        if (group != null && sub != null) {
            return command + " " + group + " " + sub;
        }
        if (sub != null) {
            return command + " " + sub;
        }
        return command;
    }
    public void logCommandUsage(Guild guild, Member member, String commandText, long channelId) {
        var logs = settingsService.getMessageLogs(guild.getIdLong());
        if (!logs.isEnabled() || !logs.isCommandUsageLogEnabled() || member == null) {
            return;
        }
        Long targetChannelId = logs.getCommandUsageChannelId() != null ? logs.getCommandUsageChannelId() : logs.getChannelId();
        if (targetChannelId == null) {
            return;
        }
        TextChannel target = guild.getTextChannelById(targetChannelId);
        if (target == null) {
            return;
        }
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(52, 152, 219))
                .setTitle(i18nService().t(lang(guild.getIdLong()), "logs.command_title"))
                .setDescription("`" + safe(commandText, 256) + "`")
                .addField(i18nService().t(lang(guild.getIdLong()), "logs.command_user"), member.getAsMention() + " (`" + member.getUser().getAsTag() + "`)", false)
                .addField(i18nService().t(lang(guild.getIdLong()), "logs.command_channel"), "<#" + channelId + ">", true)
                .setTimestamp(Instant.now());
        target.sendMessageEmbeds(eb.build()).queue(success -> {
        }, error -> {
        });
    }

    public boolean canControlPanel(Guild guild, Member member) {
        if (member == null || member.getVoiceState() == null) {
            return false;
        }
        AudioChannel userChannel = member.getVoiceState().getChannel();
        AudioChannel botChannel = guild.getAudioManager().getConnectedChannel();
        return userChannel != null && botChannel != null && userChannel.getIdLong() == botChannel.getIdLong();
    }

    private String formatMissingPermissions(Member member, GuildChannel channel, Permission... permissions) {
        EnumSet<Permission> missing = EnumSet.noneOf(Permission.class);
        for (Permission permission : permissions) {
            if (!member.hasPermission(channel, permission)) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            return "-";
        }
        List<String> names = new ArrayList<>();
        for (Permission permission : missing) {
            names.add(permission.getName());
        }
        return String.join(", ", names);
    }

    public String formatMissingPermissionsForPanel(Member member, GuildChannel channel, Permission... permissions) {
        return formatMissingPermissions(member, channel, permissions);
    }

    private void refreshAllPanelsSafely() {
        try {
            List<Long> guildIds = panelStateStore.snapshotGuildIds();
            for (Long guildId : guildIds) {
                refreshPanelPeriodic(guildId);
            }
        } catch (Exception ignored) {
        }
    }

    public String panelSignature(Guild guild) {
        String current = musicService.getCurrentTitle(guild);
        long duration = musicService.getCurrentDurationMillis(guild);
        long position = musicService.getCurrentPositionMillis(guild);
        long positionBucket = Math.max(0L, position / PANEL_PERIODIC_REFRESH_MS);
        String state = current == null ? "IDLE" : (musicService.isPaused(guild) ? "PAUSED" : "PLAYING");
        String repeat = musicService.getRepeatMode(guild);
        List<AudioTrack> queue = musicService.getQueueSnapshot(guild);
        String queueHead = queue.isEmpty() ? "-" : safe(queue.get(0).getInfo().title, 40);
        String connected = guild.getAudioManager().getConnectedChannel() == null
                ? "-"
                : guild.getAudioManager().getConnectedChannel().getId();
        String source = musicService.getCurrentSource(guild);
        String autoplayNotice = musicService.getAutoplayNotice(guild.getIdLong());
        return String.join("|",
                safe(current, 60),
                String.valueOf(duration),
                String.valueOf(positionBucket),
                state,
                safe(repeat, 12),
                String.valueOf(queue.size()),
                queueHead,
                connected,
                safe(source, 20),
                safe(autoplayNotice, 50),
                String.valueOf(isAutoplayEnabled(guild.getIdLong()))
        );
    }
    public long acquireCooldown(long userId) {
        int cooldownSeconds = Math.max(0, runtimeConfig.get().getCommandCooldownSeconds());
        if (cooldownSeconds <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        String key = String.valueOf(userId);
        Long nextAllowed = commandCooldowns.get(key);
        if (nextAllowed != null && nextAllowed > now) {
            return nextAllowed - now;
        }
        commandCooldowns.put(key, now + cooldownSeconds * 1000L);
        return 0;
    }

    public long acquirePanelButtonCooldown(long userId) {
        int cooldownSeconds = Math.max(0, runtimeConfig.get().getCommandCooldownSeconds());
        if (cooldownSeconds <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        String key = String.valueOf(userId);
        Long nextAllowed = panelButtonCooldowns.get(key);
        if (nextAllowed != null && nextAllowed > now) {
            return nextAllowed - now;
        }
        panelButtonCooldowns.put(key, now + cooldownSeconds * 1000L);
        return 0;
    }
    public long toCooldownSeconds(long remainingMillis) {
        return Math.max(1L, (remainingMillis + 999L) / 1000L);
    }

    private String formatDuration(long millis) {
        if (millis <= 0) {
            return "00:00";
        }
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    private EmbedBuilder musicStatsEmbed(Guild guild, String lang) {
        MusicDataService.MusicStatsSnapshot stats = musicService.getStats(guild.getIdLong());
        String topSong = stats.topSongLabel() == null || stats.topSongLabel().isBlank()
                ? musicText(lang, "stats_none")
                : safe(stats.topSongLabel(), 100) + " (`" + stats.topSongCount() + "`)";
        String topRequester = stats.topRequesterId() == null
                ? musicText(lang, "stats_none")
                : "<@" + stats.topRequesterId() + "> (`" + stats.topRequesterCount() + "`)";
        return new EmbedBuilder()
                .setColor(new Color(155, 89, 182))
                .setTitle("\uD83D\uDCCA " + musicText(lang, "stats_title"))
                .setDescription(musicText(lang, "stats_desc"))
                .addField("\uD83C\uDFB5 " + musicText(lang, "stats_top_song"), topSong, false)
                .addField("\uD83D\uDC64 " + musicText(lang, "stats_top_user"), topRequester, false)
                .addField("\u23F1\uFE0F " + musicText(lang, "stats_today_time"), formatDuration(stats.todayPlaybackMillis()), true)
                .addField("\uD83D\uDDD2\uFE0F " + musicText(lang, "stats_history_count"), String.valueOf(stats.historyCount()), true)
                .setTimestamp(Instant.now());
    }

    private String safe(String s, int max) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1);
    }

    public String limitText(String value, int max) {
        return safe(value, max);
    }

    public String repeatLabel(String lang, String repeatMode) {
        return musicPlaybackText.mapRepeatLabel(lang, repeatMode);
    }

    public String mapMusicLoadError(String lang, String error) {
        return musicPlaybackText.mapMusicLoadError(lang, error);
    }

    public String musicUx(String lang, String key) {
        return musicUx(lang, key, Map.of());
    }
    public String musicText(String lang, String key) {
        return musicText(lang, key, Map.of());
    }
    public String musicText(String lang, String key, Map<String, String> placeholders) {
        String fullKey = "music." + key;
        String value = i18nService().t(lang, fullKey, placeholders);
        return isMissingTranslation(value, fullKey) ? musicUx(lang, key, placeholders) : value;
    }

    private boolean isMissingTranslation(String value, String key) {
        return value == null || value.isBlank() || value.equals(key);
    }
    public String musicUx(String lang, String key, Map<String, String> placeholders) {
        boolean zhCn = lang != null && lang.startsWith("zh-CN");
        boolean zh = lang != null && lang.startsWith("zh");
        String value = switch (key) {
            case "volume_usage" -> zhCn ? "\u8BF7\u4F7F\u7528 `!volume <1-100>`\u3002" : (zh ? "\u8ACB\u4F7F\u7528 `!volume <1-100>`\u3002" : "Use `!volume <1-100>`.");
            case "volume_set" -> zhCn ? "\u97F3\u91CF\u5DF2\u8BBE\u7F6E\u4E3A `{value}%`\u3002" : (zh ? "\u97F3\u91CF\u5DF2\u8A2D\u5B9A\u70BA `{value}%`\u3002" : "Volume set to `{value}%`.");
            case "playlist_usage" -> zhCn ? "\u8BF7\u4F7F\u7528 `!playlist <save|load|add|delete|list|view|export> [name]`\u3001`!playlist list <mine|all>` \u6216 `!playlist import <code> [name]`\u3002" : (zh ? "\u8ACB\u4F7F\u7528 `!playlist <save|load|add|delete|list|view|export> [name]`\u3001`!playlist list <mine|all>` \u6216 `!playlist import <code> [name]`\u3002" : "Use `!playlist <save|load|add|delete|list|view|export> [name]`, `!playlist list <mine|all>`, or `!playlist import <code> [name]`.");
            case "playlist_name_required" -> zhCn ? "\u8BF7\u63D0\u4F9B\u6B4C\u5355\u540D\u79F0\u3002" : (zh ? "\u8ACB\u63D0\u4F9B\u6B4C\u55AE\u540D\u7A31\u3002" : "Please provide a playlist name.");
            case "playlist_save_empty" -> zhCn ? "\u76EE\u524D\u6CA1\u6709\u53EF\u4FDD\u5B58\u7684\u6B4C\u66F2\u6216\u961F\u5217\u3002" : (zh ? "\u76EE\u524D\u6C92\u6709\u53EF\u5132\u5B58\u7684\u6B4C\u66F2\u6216\u4F47\u5217\u3002" : "There is no current track or queue to save.");
            case "playlist_save_success" -> zhCn ? "\u6B4C\u5355 `{name}` \u5DF2\u4FDD\u5B58\uFF0C\u5171 `{count}` \u9996\u6B4C\u66F2\u3002" : (zh ? "\u6B4C\u55AE `{name}` \u5DF2\u5132\u5B58\uFF0C\u5171 `{count}` \u9996\u6B4C\u66F2\u3002" : "Playlist `{name}` saved with `{count}` tracks.");
            case "playlist_save_duplicate" -> zhCn ? "\u6B4C\u5355 `{name}` \u5DF2\u5305\u542B\u76EE\u524D\u8981\u65B0\u589E\u7684\u6B4C\u66F2\uFF0C\u672A\u91CD\u590D\u65B0\u589E\u3002" : (zh ? "\u6B4C\u55AE `{name}` \u5DF2\u5305\u542B\u76EE\u524D\u8981\u65B0\u589E\u7684\u6B4C\u66F2\uFF0C\u672A\u91CD\u8907\u65B0\u589E\u3002" : "Playlist `{name}` already contains the current tracks. Nothing was added.");
            case "playlist_load_missing" -> zhCn ? "\u627E\u4E0D\u5230\u6B4C\u5355 `{name}`\u3002" : (zh ? "\u627E\u4E0D\u5230\u6B4C\u55AE `{name}`\u3002" : "Playlist `{name}` was not found.");
            case "playlist_load_success" -> zhCn ? "\u6B4C\u5355 `{name}` \u5DF2\u52A0\u5165\u961F\u5217\uFF0C\u5171 `{count}` \u9996\u6B4C\u66F2\u3002" : (zh ? "\u6B4C\u55AE `{name}` \u5DF2\u52A0\u5165\u4F47\u5217\uFF0C\u5171 `{count}` \u9996\u6B4C\u66F2\u3002" : "Playlist `{name}` queued with `{count}` tracks.");
            case "playlist_add_no_track" -> zhCn ? "\u7121\u6CD5\u5F9E\u63D0\u4F9B\u7684 URL \u89E3\u6790\u53EF\u65B0\u589E\u7684\u6B4C\u66F2\u3002" : (zh ? "\u7121\u6CD5\u5F9E\u63D0\u4F9B\u7684 URL \u89E3\u6790\u53EF\u65B0\u589E\u7684\u6B4C\u66F2\u3002" : "No addable track could be resolved from the provided URL.");
            case "playlist_add_success" -> zhCn ? "\u5DF2\u5C07 `{title}` \u65B0\u589E\u5230\u6B4C\u5355 `{name}`\uFF0C\u76EE\u524D\u5171 `{count}` \u9996\u6B4C\u66F2\u3002" : (zh ? "\u5DF2\u5C07 `{title}` \u65B0\u589E\u5230\u6B4C\u55AE `{name}`\uFF0C\u76EE\u524D\u5171 `{count}` \u9996\u6B4C\u66F2\u3002" : "Added `{title}` to playlist `{name}`. It now has `{count}` tracks.");
            case "playlist_add_duplicate" -> zhCn ? "\u6B4C\u5355 `{name}` \u5DF2\u6709 `{title}`\uFF0C\u672A\u91CD\u590D\u65B0\u589E\u3002" : (zh ? "\u6B4C\u55AE `{name}` \u5DF2\u6709 `{title}`\uFF0C\u672A\u91CD\u8907\u65B0\u589E\u3002" : "Playlist `{name}` already contains `{title}`. Nothing was added.");
            case "playlist_add_not_owner" -> zhCn ? "\u6B4C\u5355 `{name}` \u7531 `{owner}` \u5EFA\u7ACB\uff0c\u53EA\u6709\u5EFA\u7ACB\u8005\u53EF\u4EE5\u65B0\u589E\u6B4C\u66F2\u3002" : (zh ? "\u6B4C\u55AE `{name}` \u7531 `{owner}` \u5EFA\u7ACB\uff0c\u53EA\u6709\u5EFA\u7ACB\u8005\u53EF\u4EE5\u65B0\u589E\u6B4C\u66F2\u3002" : "Playlist `{name}` was created by `{owner}`. Only the creator can add tracks.");
            case "playlist_add_limit" -> zhCn ? "\u6B4C\u5355 `{name}` \u5DF2\u9054\u4E0A\u9650 `{count}` \u9996\u6B4C\u66F2\uff0c\u7121\u6CD5\u7E7C\u7E8C\u65B0\u589E\u3002" : (zh ? "\u6B4C\u55AE `{name}` \u5DF2\u9054\u4E0A\u9650 `{count}` \u9996\u6B4C\u66F2\uff0c\u7121\u6CD5\u7E7C\u7E8C\u65B0\u589E\u3002" : "Playlist `{name}` already reached the limit of `{count}` tracks.");
            case "playlist_add_failed" -> zhCn ? "\u89E3\u6790 URL \u5931\u8D25\uFF1A{reason}" : (zh ? "\u89E3\u6790 URL \u5931\u6557\uFF1A{reason}" : "Failed to resolve URL: {reason}");
            case "playlist_delete_missing" -> zhCn ? "\u627E\u4E0D\u5230\u6B4C\u5355 `{name}`\u3002" : (zh ? "\u627E\u4E0D\u5230\u6B4C\u55AE `{name}`\u3002" : "Playlist `{name}` was not found.");
            case "playlist_delete_success" -> zhCn ? "\u6B4C\u5355 `{name}` \u5DF2\u5220\u9664\u3002" : (zh ? "\u6B4C\u55AE `{name}` \u5DF2\u522A\u9664\u3002" : "Playlist `{name}` deleted.");
            case "playlist_export_missing" -> zhCn ? "\u627E\u4E0D\u5230\u53EF\u532F\u51FA\u7684\u6B4C\u5355 `{name}`\u3002" : (zh ? "\u627E\u4E0D\u5230\u53EF\u532F\u51FA\u7684\u6B4C\u55AE `{name}`\u3002" : "Playlist `{name}` was not found for export.");
            case "playlist_export_success" -> zhCn ? "\u6B4C\u5355 `{name}` \u5DF2\u751F\u6210 6 \u4F4D\u6570\u532F\u51FA\u4EE3\u7801 `{code}`\uFF0C\u5171 `{count}` \u9996\u6B4C\u66F2\u3002\u4EE3\u7801 `{minutes}` \u5206\u949F\u5185\u6709\u6548\u3002" : (zh ? "\u6B4C\u55AE `{name}` \u5DF2\u7522\u751F 6 \u4F4D\u6578\u532F\u51FA\u4EE3\u78BC `{code}`\uFF0C\u5171 `{count}` \u9996\u6B4C\u66F2\u3002\u4EE3\u78BC `{minutes}` \u5206\u9418\u5167\u6709\u6548\u3002" : "Playlist `{name}` generated 6-digit export code `{code}` with `{count}` tracks. The code is valid for `{minutes}` minutes.");
            case "playlist_code_required" -> zhCn ? "\u8BF7\u63D0\u4F9B 6 \u4F4D\u6570\u532F\u5165\u4EE3\u7801\u3002" : (zh ? "\u8ACB\u63D0\u4F9B 6 \u4F4D\u6578\u532F\u5165\u4EE3\u78BC\u3002" : "Please provide a 6-digit import code.");
            case "playlist_import_invalid_code" -> zhCn ? "\u532F\u5165\u4EE3\u7801 `{code}` \u65E0\u6548\u3001\u5DF2\u8FC7\u671F\u6216\u4E0D\u5B58\u5728\u3002" : (zh ? "\u532F\u5165\u4EE3\u78BC `{code}` \u7121\u6548\u3001\u5DF2\u904E\u671F\u6216\u4E0D\u5B58\u5728\u3002" : "Import code `{code}` is invalid, expired, or unavailable.");
            case "playlist_import_success" -> zhCn ? "\u5DF2\u4ECE\u4EE3\u7801 `{code}` \u532F\u5165\u6B4C\u5355 `{name}`\uFF0C\u5171 `{count}` \u9996\u6B4C\u66F2\u3002" : (zh ? "\u5DF2\u5F9E\u4EE3\u78BC `{code}` \u532F\u5165\u6B4C\u55AE `{name}`\uFF0C\u5171 `{count}` \u9996\u6B4C\u66F2\u3002" : "Imported playlist `{name}` from code `{code}` with `{count}` tracks.");
            case "playlist_name_conflict" -> zhCn ? "\u6B4C\u5355 `{name}` \u5DF2\u5B58\u5728\uff0c\u7531 `{owner}` \u5EFA\u7ACB\u3002\u8BF7\u66F4\u6539\u540D\u79F0\u3002" : (zh ? "\u6B4C\u55AE `{name}` \u5DF2\u5B58\u5728\uff0c\u7531 `{owner}` \u5EFA\u7ACB\u3002\u8ACB\u66F4\u6539\u540D\u7A31\u3002" : "Playlist `{name}` already exists and was created by `{owner}`. Please choose a different name.");
            case "playlist_delete_not_owner" -> zhCn ? "\u6B4C\u5355 `{name}` \u7531 `{owner}` \u5EFA\u7ACB\uff0c\u53EA\u6709\u5EFA\u7ACB\u8005\u53EF\u4EE5\u5220\u9664\u6216\u8986\u76D6\u3002" : (zh ? "\u6B4C\u55AE `{name}` \u7531 `{owner}` \u5EFA\u7ACB\uff0c\u53EA\u6709\u5EFA\u7ACB\u8005\u53EF\u4EE5\u522A\u9664\u6216\u8986\u84CB\u3002" : "Playlist `{name}` was created by `{owner}`. Only the creator can delete or overwrite it.");
            case "playlist_title" -> zhCn ? "\u5DF2\u4FDD\u5B58\u6B4C\u5355" : (zh ? "\u5DF2\u5132\u5B58\u6B4C\u55AE" : "Saved Playlists");
            case "playlist_desc" -> zhCn ? "\u4FDD\u5B58\u76EE\u524D\u64AD\u653E\u6B4C\u66F2\u4E0E\u961F\u5217\uFF0C\u4E4B\u540E\u53EF\u968F\u65F6\u91CD\u65B0\u8F7D\u5165\u3002" : (zh ? "\u5132\u5B58\u76EE\u524D\u64AD\u653E\u6B4C\u66F2\u8207\u4F47\u5217\uFF0C\u4E4B\u5F8C\u53EF\u96A8\u6642\u91CD\u65B0\u8F09\u5165\u3002" : "Save the current track and queue, then load them again anytime.");
            case "playlist_field" -> zhCn ? "\u6B4C\u5355\u5217\u8868" : (zh ? "\u6B4C\u55AE\u5217\u8868" : "Playlists");
            case "playlist_list_desc" -> zhCn ? "\u8FD9\u4E2A\u670D\u52A1\u5668\u5DF2\u4FDD\u5B58\u7684\u6B4C\u5355\u3002" : (zh ? "\u9019\u500B\u4F3A\u670D\u5668\u5DF2\u5132\u5B58\u7684\u6B4C\u55AE\u3002" : "Saved playlists for this server.");
            case "playlist_list_empty" -> zhCn ? "\u76EE\u524D\u8FD8\u6CA1\u6709\u4FDD\u5B58\u4EFB\u4F55\u6B4C\u5355\u3002" : (zh ? "\u76EE\u524D\u9084\u6C92\u6709\u5132\u5B58\u4EFB\u4F55\u6B4C\u55AE\u3002" : "No playlists saved yet.");
            case "playlist_list_desc_all" -> zhCn ? "\u8FD9\u4E2A\u670D\u52A1\u5668\u7684\u6240\u6709\u6B4C\u5355\u3002" : (zh ? "\u9019\u500B\u4F3A\u670D\u5668\u7684\u5168\u90E8\u6B4C\u55AE\u3002" : "All playlists saved in this server.");
            case "playlist_list_desc_mine" -> zhCn ? "\u4F60\u5728\u8FD9\u4E2A\u670D\u52A1\u5668\u5EFA\u7ACB\u7684\u6B4C\u5355\u3002" : (zh ? "\u4F60\u5728\u9019\u500B\u4F3A\u670D\u5668\u5EFA\u7ACB\u7684\u6B4C\u55AE\u3002" : "Playlists you created in this server.");
            case "playlist_list_empty_all" -> zhCn ? "\u76EE\u524D\u8FD8\u6CA1\u6709\u4EFB\u4F55\u6B4C\u5355\u3002" : (zh ? "\u76EE\u524D\u9084\u6C92\u6709\u4EFB\u4F55\u6B4C\u55AE\u3002" : "There are no playlists in this server yet.");
            case "playlist_list_empty_mine" -> zhCn ? "\u4F60\u8FD8\u6CA1\u6709\u5728\u8FD9\u4E2A\u670D\u52A1\u5668\u5EFA\u7ACB\u4EFB\u4F55\u6B4C\u5355\u3002" : (zh ? "\u4F60\u9084\u6C92\u6709\u5728\u9019\u500B\u4F3A\u670D\u5668\u5EFA\u7ACB\u4EFB\u4F55\u6B4C\u55AE\u3002" : "You have not created any playlists in this server yet.");
            case "playlist_owner" -> zhCn ? "\u521B\u5EFA\u8005" : (zh ? "\u5EFA\u7ACB\u8005" : "Owner");
            case "playlist_updated" -> zhCn ? "\u5DF2\u66F4\u65B0" : (zh ? "\u5DF2\u66F4\u65B0" : "Updated");
            case "playlist_view_title" -> zhCn ? "\u6B4C\u5355\uff1A{name}" : (zh ? "\u6B4C\u55AE\uff1A{name}" : "Playlist: {name}");
            case "playlist_view_desc" -> zhCn ? "\u4EE5\u4E0B\u4E3A\u6B4C\u5355 `{name}` \u7684\u66F2\u76EE\u5185\u5BB9\u3002" : (zh ? "\u4EE5\u4E0B\u70BA\u6B4C\u55AE `{name}` \u7684\u66F2\u76EE\u5167\u5BB9\u3002" : "Tracks inside playlist `{name}`.");
            case "playlist_view_missing" -> zhCn ? "\u627E\u4E0D\u5230\u6B4C\u5355 `{name}`\u3002" : (zh ? "\u627E\u4E0D\u5230\u6B4C\u55AE `{name}`\u3002" : "Playlist `{name}` was not found.");
            case "playlist_view_empty" -> zhCn ? "\u8FD9\u4E2A\u6B4C\u5355\u76EE\u524D\u6CA1\u6709\u66F2\u76EE\u3002" : (zh ? "\u9019\u500B\u6B4C\u55AE\u76EE\u524D\u6C92\u6709\u66F2\u76EE\u3002" : "This playlist is currently empty.");
            case "playlist_view_expired" -> zhCn ? "\u6B4C\u5355\u67E5\u770B\u5206\u9875\u5DF2\u8FC7\u671F\uff0c\u8BF7\u91CD\u65B0\u6267\u884C\u6307\u4EE4\u3002" : (zh ? "\u6B4C\u55AE\u67E5\u770B\u5206\u9801\u5DF2\u904E\u671F\uff0C\u8ACB\u91CD\u65B0\u57F7\u884C\u6307\u4EE4\u3002" : "The playlist view pagination has expired. Please run the command again.");
            case "playlist_track_count" -> zhCn ? "\u66F2\u76EE\u6570" : (zh ? "\u66F2\u76EE\u6578" : "Tracks");
            case "playlist_track_list" -> zhCn ? "\u66F2\u76EE\u5185\u5BB9" : (zh ? "\u66F2\u76EE\u5167\u5BB9" : "Track List");
            case "playlist_view_more" -> zhCn ? "\u8FD8\u6709 `{count}` \u9996\u672A\u663E\u793A\u3002" : (zh ? "\u9084\u6709 `{count}` \u9996\u672A\u986F\u793A\u3002" : "`{count}` more not shown.");
            case "playlist_prev_page" -> zhCn ? "\u4E0A\u4E00\u9875" : (zh ? "\u4E0A\u4E00\u9801" : "Previous");
            case "playlist_next_page" -> zhCn ? "\u4E0B\u4E00\u9875" : (zh ? "\u4E0B\u4E00\u9801" : "Next");
            case "playlist_page_indicator" -> zhCn ? "\u7B2C `{current}` / `{total}` \u9875" : (zh ? "\u7B2C `{current}` / `{total}` \u9801" : "Page `{current}` / `{total}`");
            case "playlist_source" -> zhCn ? "\u6B4C\u5355" : (zh ? "\u6B4C\u55AE" : CMD_PLAYLIST);
            case "queue_added" -> zhCn ? "\u5DF2\u52A0\u5165\u961F\u5217\uFF1A`{title}`" : (zh ? "\u5DF2\u52A0\u5165\u4F47\u5217\uFF1A`{title}`" : "Queued: `{title}`");
            case "panel_autoplay" -> zhCn ? "\u81EA\u52A8\u63A8\u8350" : (zh ? "\u81EA\u52D5\u63A8\u85A6" : "Autoplay");
            case "panel_autoplay_notice" -> zhCn ? "\u81EA\u52A8\u63A8\u8350\u63D0\u793A" : (zh ? "\u81EA\u52D5\u63A8\u85A6\u63D0\u793A" : "Autoplay Notice");
            case "btn_autoplay_on" -> zhCn ? "\u81EA\u52A8\u63A8\u8350\uFF1A\u5F00\u542F" : (zh ? "\u81EA\u52D5\u63A8\u85A6\uFF1A\u958B\u555F" : "Autoplay: ON");
            case "btn_autoplay_off" -> zhCn ? "\u81EA\u52A8\u63A8\u8350\uFF1A\u5173\u95ED" : (zh ? "\u81EA\u52D5\u63A8\u85A6\uFF1A\u95DC\u9589" : "Autoplay: OFF");
            case "panel_title" -> zhCn ? "\u97F3\u4E50\u63A7\u5236\u9762\u677F" : (zh ? "\u97F3\u6A02\u63A7\u5236\u9762\u677F" : "Music Control Panel");
            case "panel_current" -> zhCn ? "\u5F53\u524D\u64AD\u653E" : (zh ? "\u76EE\u524D\u64AD\u653E" : "Now Playing");
            case "panel_channel" -> zhCn ? "\u9891\u9053" : (zh ? "\u983B\u9053" : OPTION_CHANNEL);
            case "panel_queue" -> zhCn ? "\u961F\u5217" : (zh ? "\u4F47\u5217" : "Queue");
            case "panel_repeat" -> zhCn ? "\u5FAA\u73AF" : (zh ? "\u5FAA\u74B0" : CMD_REPEAT);
            case "panel_state" -> zhCn ? "\u72B6\u6001" : (zh ? "\u72C0\u614B" : "State");
            case "panel_paused" -> zhCn ? "\u6682\u505C" : (zh ? "\u66AB\u505C" : "Paused");
            case "panel_playing" -> zhCn ? "\u64AD\u653E\u4E2D" : (zh ? "\u64AD\u653E\u4E2D" : "Playing");
            case "panel_idle" -> zhCn ? "\u95F2\u7F6E" : (zh ? "\u9592\u7F6E" : "Idle");
            case "panel_source" -> zhCn ? "\u6765\u6E90" : (zh ? "\u4F86\u6E90" : "Source");
            case "panel_requester" -> zhCn ? "\u70B9\u6B4C\u8005" : (zh ? "\u9EDE\u6B4C\u8005" : "Requested By");
            case "panel_progress" -> zhCn ? "\u64AD\u653E\u8FDB\u5EA6" : (zh ? "\u64AD\u653E\u9032\u5EA6" : "Progress");
            case "panel_none" -> zhCn ? "\uFF08\u65E0\uFF09" : (zh ? "\uFF08\u7121\uFF09" : "(none)");
            case "autoplay_on" -> zhCn ? "\u5F00\u542F" : (zh ? "\u958B\u555F" : "ON");
            case "autoplay_off" -> zhCn ? "\u5173\u95ED" : (zh ? "\u95DC\u9589" : "OFF");
            case "btn_play_pause" -> zhCn ? "\u64AD\u653E/\u6682\u505C" : (zh ? "\u64AD\u653E/\u66AB\u505C" : "Play/Pause");
            case "btn_skip" -> zhCn ? "\u8DF3\u8FC7" : (zh ? "\u8DF3\u904E" : "Skip");
            case "btn_stop" -> zhCn ? "\u505C\u6B62" : (zh ? "\u505C\u6B62" : "Stop");
            case "btn_leave" -> zhCn ? "\u79BB\u5F00" : (zh ? "\u96E2\u958B" : CMD_LEAVE);
            case "btn_volume_down" -> zhCn ? "\u964D\u4F4E\u97F3\u91CF" : (zh ? "\u964D\u4F4E\u97F3\u91CF" : "Volume Down");
            case "btn_volume_up" -> zhCn ? "\u589E\u52A0\u97F3\u91CF" : (zh ? "\u589E\u52A0\u97F3\u91CF" : "Volume Up");
            case "btn_repeat_single" -> zhCn ? "\u5355\u66F2\u5FAA\u73AF" : (zh ? "\u55AE\u66F2\u5FAA\u74B0" : "Repeat One");
            case "btn_repeat_all" -> zhCn ? "\u961F\u5217\u5FAA\u73AF" : (zh ? "\u4F47\u5217\u5FAA\u74B0" : "Repeat Queue");
            case "btn_repeat_off" -> zhCn ? "\u5173\u95ED\u5FAA\u73AF" : (zh ? "\u95DC\u9589\u5FAA\u74B0" : "Disable Repeat");
            case "btn_refresh" -> zhCn ? "\u5237\u65B0" : (zh ? "\u5237\u65B0" : "Refresh");
            case "btn_shuffle" -> zhCn ? "\u968F\u673A\u6253\u4E71" : (zh ? "\u96A8\u6A5F\u6253\u4E82" : "Shuffle");
            case "panel_volume" -> zhCn ? "\u97F3\u91CF" : (zh ? "\u97F3\u91CF" : CMD_VOLUME);
            case "panel_author" -> zhCn ? "\u4E0A\u4F20\u8005" : (zh ? "\u4E0A\u50B3\u8005" : "Uploader");
            case "panel_duration" -> zhCn ? "\u65F6\u957F" : (zh ? "\u6642\u9577" : "Duration");
            case "history_title" -> zhCn ? "\u64AD\u653E\u5386\u53F2" : (zh ? "\u64AD\u653E\u6B77\u53F2" : "Playback History");
            case "history_desc" -> zhCn ? "\u8FD9\u4E2A\u670D\u52A1\u5668\u6700\u8FD1\u64AD\u653E\u8FC7\u7684\u6B4C\u66F2\u3002" : (zh ? "\u9019\u500B\u4F3A\u670D\u5668\u6700\u8FD1\u64AD\u653E\u904E\u7684\u6B4C\u66F2\u3002" : "Recently played tracks for this server.");
            case "history_field" -> zhCn ? "\u6700\u8FD1\u64AD\u653E" : (zh ? "\u6700\u8FD1\u64AD\u653E" : "Recently Played");
            case "history_empty" -> zhCn ? "\u76EE\u524D\u8FD8\u6CA1\u6709\u64AD\u653E\u5386\u53F2\u3002" : (zh ? "\u76EE\u524D\u9084\u6C92\u6709\u64AD\u653E\u6B77\u53F2\u3002" : "No playback history yet.");
            case "history_source" -> zhCn ? "\u6765\u6E90" : (zh ? "\u4F86\u6E90" : "Source");
            case "history_duration" -> zhCn ? "\u65F6\u957F" : (zh ? "\u6642\u9577" : "Duration");
            case "history_requester" -> zhCn ? "\u70B9\u6B4C\u8005" : (zh ? "\u9EDE\u6B4C\u8005" : "Requester");
            case "stats_title" -> zhCn ? "\u97F3\u4E50\u7EDF\u8BA1" : (zh ? "\u97F3\u6A02\u7D71\u8A08" : "Music Stats");
            case "stats_desc" -> zhCn ? "\u8FD9\u4E2A\u670D\u52A1\u5668\u7684\u97F3\u4E50\u6D3B\u52A8\u6982\u89C8\u3002" : (zh ? "\u9019\u500B\u4F3A\u670D\u5668\u7684\u97F3\u6A02\u6D3B\u52D5\u6982\u89BD\u3002" : "Music activity overview for this server.");
            case "stats_top_song" -> zhCn ? "\u6700\u591A\u64AD\u653E\u6B4C\u66F2" : (zh ? "\u6700\u591A\u64AD\u653E\u6B4C\u66F2" : "Most Played Song");
            case "stats_top_user" -> zhCn ? "\u6700\u5E38\u70B9\u6B4C\u6210\u5458" : (zh ? "\u6700\u5E38\u9EDE\u6B4C\u6210\u54E1" : "Most Active Requester");
            case "stats_today_time" -> zhCn ? "\u4ECA\u65E5\u64AD\u653E\u65F6\u6570" : (zh ? "\u4ECA\u65E5\u64AD\u653E\u6642\u6578" : "Today Playback Time");
            case "stats_history_count" -> zhCn ? "\u5386\u53F2\u7EAA\u5F55\u6570\u91CF" : (zh ? "\u6B77\u53F2\u7D00\u9304\u6578\u91CF" : "History Entries");
            case "stats_none" -> zhCn ? "\u6682\u65E0\u8D44\u6599" : (zh ? "\u66AB\u7121\u8CC7\u6599" : "No data");
            default -> key;
        };
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return value;
    }

    public Integer parseHexColor(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
        }
        try {
            return Integer.parseInt(text, 16) & 0xFFFFFF;
        } catch (Exception ignored) {
            return null;
        }
    }

    public int resolveTemplateColor(long guildId, String templateType) {
        var notifications = settingsService.getNotifications(guildId);
        return switch (templateType) {
            case VALUE_MEMBER_JOIN -> notifications.getMemberJoinColor();
            case VALUE_MEMBER_LEAVE -> notifications.getMemberLeaveColor();
            default -> 0x3498DB;
        };
    }

    public String boolText(String lang, boolean value) {
        return value ? i18nService().t(lang, "settings.info_bool_on") : i18nService().t(lang, "settings.info_bool_off");
    }

    private String formatTextChannel(Guild guild, Long id) {
        if (id == null) {
            return i18nService().t(lang(guild.getIdLong()), "settings.info_channels_none");
        }
        TextChannel channel = guild.getTextChannelById(id);
        return channel == null ? "#" + id : channel.getAsMention() + " (" + id + ")";
    }

    private String keyIcon(String key) {
        return switch (key) {
            case "settings.info_language" -> "\uD83C\uDF10";
            case "settings.info_key_enabled", "settings.key_messageLogs_enabled",
                 "settings.key_notifications_enabled", "settings.key_privateRoom_enabled" -> "\u2699\uFE0F";
            case "settings.info_key_member_join_enabled", "settings.key_notifications_memberJoinEnabled",
                 "settings.info_key_member_join_template", "settings.info_key_member_join_color",
                 "settings.info_key_member_join_channel" -> "\uD83D\uDC4B";
            case "settings.key_welcome_enabled" -> "\uD83C\uDF89";
            case "settings.info_key_member_leave_enabled", "settings.key_notifications_memberLeaveEnabled",
                 "settings.info_key_member_leave_template", "settings.info_key_member_leave_color",
                 "settings.info_key_member_leave_channel" -> "\uD83D\uDEAA";
            case "settings.info_key_member_channel", "settings.key_notifications_memberChannelId",
                 "settings.info_key_member_channel_mode" -> "\uD83D\uDC65";
            case "settings.info_key_voice_log_enabled", "settings.key_notifications_voiceLogEnabled",
                 "settings.info_key_voice_channel", "settings.key_notifications_voiceChannelId",
                 "settings.info_key_voice_join_template", "settings.info_key_voice_leave_template",
                 "settings.info_key_voice_move_template" -> "\uD83D\uDD0A";
            case "settings.info_key_log_channel", "settings.key_messageLogs_channelId",
                 "settings.info_key_message_log_channel", "settings.key_messageLogs_messageLogChannelId" -> "\uD83D\uDCCC";
            case "settings.info_key_log_command_channel", "settings.key_messageLogs_commandUsageChannelId",
                 "settings.info_key_log_command_usage" -> "\uD83E\uDDED";
            case "settings.info_key_log_channel_events_channel", "settings.key_messageLogs_channelLifecycleChannelId",
                 "settings.info_key_log_channel_lifecycle" -> "\uD83C\uDFD7\uFE0F";
            case "settings.info_key_log_role_channel", "settings.key_messageLogs_roleLogChannelId",
                 "settings.info_key_log_role" -> "\uD83C\uDFF7\uFE0F";
            case "settings.info_key_log_moderation_channel", "settings.key_messageLogs_moderationLogChannelId",
                 "settings.info_key_log_moderation" -> "\uD83D\uDEE1\uFE0F";
            case "settings.key_music_autoLeaveEnabled", "settings.key_music_autoLeaveMinutes",
                 "settings.info_key_auto_leave_enabled", "settings.info_key_auto_leave_minutes" -> "\u23F1\uFE0F";
            case "settings.key_music_autoplayEnabled", "settings.info_key_autoplay_enabled" -> "\uD83D\uDD01";
            case "settings.key_numberChain_enabled" -> "\u0031\u20E3";
            case "settings.key_ticket_enabled" -> "\uD83C\uDFAB";
            case "settings.key_ticket_maxOpenPerUser" -> "\uD83D\uDD22";
            case "settings.key_ticket_blacklistUserIds" -> "\uD83D\uDEAB";
            case "settings.info_key_number_chain_enabled",
                 "settings.info_key_number_chain_channel",
                 "settings.info_key_number_chain_next" -> "\u0031\u20E3";
            case "settings.info_key_default_repeat_mode" -> "\uD83D\uDD02";
            case "settings.key_music_commandChannelId", "settings.info_key_music_command_channel" -> "\uD83C\uDFB6";
            case "settings.key_privateRoom_triggerVoiceChannelId", "settings.info_key_trigger_channel" -> "\uD83C\uDFA4";
            case "settings.info_key_category_auto", "settings.info_key_category" -> "\uD83D\uDCC2";
            case "settings.key_privateRoom_userLimit", "settings.info_key_user_limit" -> "\uD83D\uDC64";
            default -> "\u25AB\uFE0F";
        };
    }

    private String languageDisplayText(String languageCode) {
        String normalized = i18nService().normalizeLanguage(languageCode);
        String display = i18nService().getAvailableLanguages().get(normalized);
        if (display == null || display.isBlank()) {
            return normalized;
        }
        return display + " (" + normalized + ")";
    }

    public String numberChainHighestLabel(String lang) {
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "\u6700\u9ad8\u7eaa\u5f55";
        }
        if (lang != null && lang.toLowerCase().startsWith("zh")) {
            return "\u6700\u9ad8\u7d00\u9304";
        }
        return "Highest Record";
    }
    public String numberChainTopContributorsLabel(String lang) {
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "\u63a5\u9f99\u6210\u5458\u524d 5 \u540d";
        }
        if (lang != null && lang.toLowerCase().startsWith("zh")) {
            return "\u63a5\u9f8d\u6210\u54e1\u524d 5 \u540d";
        }
        return "Top 5 Contributors";
    }
    public String formatNumberChainTopContributors(Guild guild, String lang) {
        List<ModerationService.NumberChainContributor> contributors =
                moderationService.getTopNumberChainContributors(guild.getIdLong(), 5);
        if (contributors.isEmpty()) {
            return i18nService().t(lang, "settings.info_channels_none");
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < contributors.size(); i++) {
            ModerationService.NumberChainContributor contributor = contributors.get(i);
            lines.add((i + 1) + ". <@" + contributor.getUserId() + "> - " + contributor.getCount());
        }
        return String.join("\n", lines);
    }

    @FunctionalInterface
    public interface TextSink {
        void send(String text);
    }

    private static class MenuRequest {
        private final long requestUserId;
        private final long guildId;
        private final Instant expiresAt;

        private MenuRequest(long requestUserId, long guildId, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.expiresAt = expiresAt;
        }
    }
}
