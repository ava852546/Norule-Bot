package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.config.*;
import com.norule.musicbot.config.domain.GuildDomainConfigAdapter;
import com.norule.musicbot.config.domain.RuntimeConfigSnapshot;
import com.norule.musicbot.discord.bot.app.stats.MessageStatsEventService;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;

import com.norule.musicbot.*;
import com.norule.musicbot.discord.bot.infra.CommandRegistrar;
import com.norule.musicbot.discord.bot.ops.ticket.TicketOps;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
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
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MusicCommandService extends ListenerAdapter {
    static final String CMD_HELP_ZH = "\u8aaa\u660e";
    static final String CMD_JOIN_ZH = "\u52a0\u5165";
    static final String CMD_PLAY_ZH = "\u64ad\u653e";
    static final String CMD_SKIP_ZH = "\u8df3\u904e";
    static final String CMD_STOP_ZH = "\u505c\u6b62";
    static final String CMD_LEAVE_ZH = "\u96e2\u958b";
    static final String CMD_MUSIC_PANEL_ZH = "\u97f3\u6a02\u9762\u677f";
    static final String CMD_REPEAT_ZH = "\u5faa\u74b0";
    static final String CMD_PING_ZH = "\u5ef6\u9072";
    static final String CMD_WELCOME_ZH = "\u6b61\u8fce\u8a0a\u606f";
    static final String OPTION_WELCOME_CHANNEL_ZH = "\u983b\u9053";
    static final String CMD_VOLUME_ZH = "\u97f3\u91cf";
    static final String CMD_HISTORY_ZH = "\u64ad\u653e\u6b77\u53f2";
    static final String CMD_MUSIC_ZH = "\u97f3\u6a02";
    static final String CMD_PLAYLIST_ZH = "\u6b4c\u55ae";
    static final String CMD_SETTINGS_ZH = "\u8a2d\u5b9a";
    static final String CMD_DELETE_ZH = "\u522a\u9664\u8a0a\u606f";
    static final String CMD_ROOM_SETTINGS_ZH = "\u5305\u5ec2\u8a2d\u5b9a";
    static final String CMD_WARNINGS_ZH = "\u8b66\u544a";
    static final String CMD_ANTI_DUPLICATE_ZH = "\u9632\u6d17\u983b";
    static final String CMD_HONEYPOT_ZH = "\u5bc6\u7f50\u983b\u9053";
    static final String CMD_NUMBER_CHAIN_ZH = "\u6578\u5b57\u63a5\u9f8d";
    static final String CMD_TICKET_ZH = "\u5ba2\u670d\u55ae";
    static final String CMD_USER_INFO_ZH = "\u4f7f\u7528\u8005\u8cc7\u8a0a";
    static final String CMD_ROLE_INFO_ZH = "\u8eab\u5206\u7d44\u8cc7\u8a0a";
    static final String CMD_SERVER_INFO_ZH = "\u4f3a\u670d\u5668\u8cc7\u8a0a";
    static final String CMD_STATS_ZH = "\u7d71\u8a08";
    static final String CMD_LEADERBOARD_ZH = "\u6392\u884c\u699c";
    static final String CMD_SHORT_URL_ZH = "\u77ed\u7db2\u5740";
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
    static final String SUB_GENERIC_ENABLE_ZH = "\u555f\u7528";
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
    private static final String PLAYLIST_SCOPE_MINE = "mine";
    private static final String PLAYLIST_SCOPE_ALL = "all";
    static final String OPTION_QUERY_ZH = "query";
    static final String OPTION_VOLUME_VALUE_ZH = "\u97f3\u91cf";
    public static final String OPTION_SPEED_VALUE_ZH = "\u500d\u901f";
    static final String SUB_DELETE_CHANNEL_ZH = "\u983b\u9053";
    static final String SUB_DELETE_USER_ZH = "\u4f7f\u7528\u8005";
    public static final String HELP_SELECT_ID = "help:select";
    public static final String HELP_BUTTON_PREFIX = "help:cat:";
    static final String SETTINGS_INFO_SELECT_ID = "settings:info:select";
    static final String SETTINGS_INFO_BUTTON_PREFIX = "settings:info:btn:";
    static final String SETTINGS_TEMPLATE_SELECT_PREFIX = "settings:template:select:";
    static final String SETTINGS_MODULE_SELECT_PREFIX = "settings:module:select:";
    static final String SETTINGS_LOGS_SELECT_PREFIX = "settings:logs:select:";
    static final String SETTINGS_LOGS_CHANNEL_PREFIX = "settings:logs:channel:";
    static final String SETTINGS_LOGS_MEMBER_MODE_PREFIX = "settings:logs:membermode:";
    static final String SETTINGS_LOGS_MEMBER_SPLIT_PREFIX = "settings:logs:membersplit:";
    static final String SETTINGS_MUSIC_SELECT_PREFIX = "settings:music:select:";
    static final String SETTINGS_MUSIC_CHANNEL_PREFIX = "settings:music:channel:";
    static final String SETTINGS_MUSIC_MODAL_PREFIX = "settings:music:modal:";
    static final String SETTINGS_LANGUAGE_SELECT_PREFIX = "settings:language:select:";
    static final String SETTINGS_NUMBER_CHAIN_SELECT_PREFIX = "settings:numberchain:select:";
    static final String SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX = "settings:numberchain:channel:";
    static final String SETTINGS_RESET_SELECT_PREFIX = "settings:reset:";
    static final String SETTINGS_RESET_CONFIRM_PREFIX = "settings:reset:confirm:";
    static final String SETTINGS_RESET_CANCEL_PREFIX = "settings:reset:cancel:";
    public static final String ROOM_SETTINGS_MENU_PREFIX = "room:settings:";
    public static final String ROOM_LIMIT_MODAL_PREFIX = "room:limit:";
    public static final String ROOM_RENAME_MODAL_PREFIX = "room:rename:";
    public static final String ROOM_TRANSFER_SELECT_PREFIX = "room:transfer:";
    public static final String DELETE_CONFIRM_PREFIX = "delete:confirm:";
    public static final String DELETE_CANCEL_PREFIX = "delete:cancel:";
    public static final String WARNING_REASON_MODAL_PREFIX = "warnings:reason:";
    static final String TEMPLATE_MODAL_PREFIX = "settings:template:";
    public static final String WELCOME_MODAL_ID = "welcome:edit";
    static final String PANEL_PLAY_PAUSE = "panel:playpause";
    static final String PANEL_SKIP = "panel:skip";
    static final String PANEL_STOP = "panel:stop";
    static final String PANEL_LEAVE = "panel:leave";
    static final String PANEL_REPEAT_TOGGLE = "panel:repeat:toggle";
    static final String PANEL_AUTOPLAY_TOGGLE = "panel:autoplay:toggle";
    static final String PANEL_VOLUME_DOWN = "panel:volume:down";
    static final String PANEL_VOLUME_UP = "panel:volume:up";
    static final String PANEL_REFRESH = "panel:refresh";
    static final String PANEL_SHUFFLE = "panel:shuffle";

    private final MusicPlayerService musicService;
    private final ModerationService moderationService;
    private final HoneypotService honeypotService;
    private volatile RuntimeConfigSnapshot runtimeConfig;
    private final GuildSettingsService settingsService;
    private volatile I18nService i18n;

    private final Map<Long, PanelRef> panelByGuild = new ConcurrentHashMap<>();
    private final Map<String, DeleteRequest> deleteRequests = new ConcurrentHashMap<>();
    private final Map<String, WarningActionRequest> warningActionRequests = new ConcurrentHashMap<>();
    private final Map<String, Long> commandCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> panelButtonCooldowns = new ConcurrentHashMap<>();
    private final Map<String, ResetRequest> resetRequests = new ConcurrentHashMap<>();
    private final Map<String, ResetConfirmRequest> resetConfirmRequests = new ConcurrentHashMap<>();
    private final Map<String, RoomSettingsRequest> roomSettingRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> templateMenuRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> moduleMenuRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> logsMenuRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> musicMenuRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> languageMenuRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> numberChainMenuRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile JDA jda;
    private final CommandRegistrar commandRegistrar;
    private final SettingsCommandHandler settingsCommandHandler;
    private final HelpCommandHandler helpCommandHandler;
    private final PingCommandHandler pingCommandHandler;
    private final WelcomeCommandHandler welcomeCommandHandler;
    private final HistoryCommandHandler historyCommandHandler;
    private final PlaylistCommandHandler playlistCommandHandler;
    private final MusicPlaybackCommandHandler playbackCommandHandler;
    private final MusicPanelController musicPanelController;
    private final HoneypotCommandHandler honeypotCommandHandler;
    private final InfoCommandHandler infoCommandHandler;
    private final UrlCommandHandler urlCommandHandler;
    private final InteractionRouter interactionRouter;
    private final TicketOps ticketOps;
    private final MessageStatsEventService statsEventService;
    private final ShortUrlService shortUrlService;
    private final GuildDomainConfigAdapter ticketConfigAdapter;
    private final Map<Long, Long> panelLastRefreshAt = new ConcurrentHashMap<>();
    private final Map<Long, String> panelLastSignature = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> delayedPanelRefreshByGuild = new ConcurrentHashMap<>();
    private final Set<Long> panelRefreshingGuilds = ConcurrentHashMap.newKeySet();
    private final Map<Long, Long> playbackFailureLastAt = new ConcurrentHashMap<>();
    private final Map<Long, String> playbackFailureLastSig = new ConcurrentHashMap<>();
    private volatile boolean botReadyForSlashCommands;
    private static final long PANEL_PERIODIC_REFRESH_MS = 10_000L;
    private static final long PANEL_MIN_EDIT_INTERVAL_MS = 3500L;
    private static final Duration DELETE_REQUEST_TTL = Duration.ofMinutes(5);

    public MusicCommandService(MusicPlayerService musicService,
                               RuntimeConfigSnapshot runtimeConfig,
                               GuildSettingsService settingsService,
                               ModerationService moderationService,
                               HoneypotService honeypotService,
                               ShortUrlService shortUrlService,
                               com.norule.musicbot.TicketService ticketService,
                               MessageStatsEventService statsEventService) {
        this.musicService = musicService;
        this.moderationService = moderationService;
        this.honeypotService = honeypotService;
        if (shortUrlService == null) {
            throw new IllegalArgumentException("shortUrlService cannot be null");
        }
        this.shortUrlService = shortUrlService;
        this.runtimeConfig = runtimeConfig;
        this.settingsService = settingsService;
        this.i18n = I18nService.load(runtimeConfig.getLanguageDir(), runtimeConfig.getDefaultLanguage());
        this.musicService.setAutoplayEnabledChecker(guildId -> settingsService.getMusic(guildId).isAutoplayEnabled());
        this.musicService.setPlaybackFailureListener(this::reportPlaybackFailure);
        this.commandRegistrar = new CommandRegistrar(this);
        this.settingsCommandHandler = new SettingsCommandHandler(this);
        this.helpCommandHandler = new HelpCommandHandler(this);
        this.pingCommandHandler = new PingCommandHandler(this);
        this.welcomeCommandHandler = new WelcomeCommandHandler(this);
        this.historyCommandHandler = new HistoryCommandHandler(this);
        this.playlistCommandHandler = new PlaylistCommandHandler(this);
        this.playbackCommandHandler = new MusicPlaybackCommandHandler(this);
        this.musicPanelController = new MusicPanelController(this);
        this.honeypotCommandHandler = new HoneypotCommandHandler(this);
        this.infoCommandHandler = new InfoCommandHandler(this);
        this.urlCommandHandler = new UrlCommandHandler(this);
        this.statsEventService = statsEventService;
        this.ticketConfigAdapter = new GuildDomainConfigAdapter(settingsService, runtimeConfig.getDefaultMusic());
        this.ticketOps = new TicketOps(new com.norule.musicbot.discord.bot.ops.ticket.TicketService(ticketConfigAdapter, ticketService, i18n));
        this.interactionRouter = new InteractionRouter(this);
        this.scheduler.scheduleAtFixedRate(this::refreshAllPanelsSafely, 5, PANEL_PERIODIC_REFRESH_MS / 1000L, TimeUnit.SECONDS);
        this.scheduler.scheduleAtFixedRate(this::cleanupTransientStateSafely, 1, 1, TimeUnit.MINUTES);
    }

    private void cleanupTransientStateSafely() {
        try {
            cleanupTransientState();
        } catch (RuntimeException ignored) {
        }
    }

    private void cleanupTransientState() {
        Instant now = Instant.now();
        long nowMillis = System.currentTimeMillis();
        deleteRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        warningActionRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        resetRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        resetConfirmRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        roomSettingRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        templateMenuRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        moduleMenuRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        logsMenuRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        musicMenuRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        languageMenuRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
        numberChainMenuRequests.entrySet().removeIf(entry -> entry.getValue() == null || now.isAfter(entry.getValue().expiresAt));
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

        if (jda == null) {
            return;
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            return;
        }
        Long channelId = musicService.getLastCommandChannelId(guildId);
        if (channelId == null) {
            PanelRef ref = panelByGuild.get(guildId);
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
        String mapped = mapMusicLoadError(lang, failure.rawError());
        channel.sendMessage(i18n.t(lang, "music.playback_failed", Map.of(
                        "title", safe(failure.title(), 80),
                        "error", safe(mapped, 180)
                )))
                .queue(success -> {
                }, error -> {
                });
    }

    public void reloadRuntimeConfig(RuntimeConfigSnapshot newConfig) {
        if (newConfig == null) {
            return;
        }
        this.runtimeConfig = newConfig;
        this.i18n = I18nService.load(newConfig.getLanguageDir(), newConfig.getDefaultLanguage());
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
        return i18n;
    }
    public JDA currentJda() {
        return jda;
    }
    public Map<Long, PanelRef> panelRefs() {
        return panelByGuild;
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
        return botReadyForSlashCommands;
    }

    @Override
    public void onReady(ReadyEvent event) {
        this.jda = event.getJDA();
        this.botReadyForSlashCommands = true;
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
        if (!raw.startsWith(runtimeConfig.getPrefix())) {
            return;
        }
        String[] split = raw.substring(runtimeConfig.getPrefix().length()).trim().split("\\s+", 2);
        String cmd = split.length > 0 ? split[0].toLowerCase() : "";
        String arg = split.length > 1 ? split[1].trim() : "";
        String lang = lang(guild.getIdLong());

        if (isKnownPrefixCommand(cmd)) {
            long remaining = acquireCooldown(event.getAuthor().getIdLong());
            if (remaining > 0) {
                event.getChannel().sendMessage(i18n.t(lang, "general.command_cooldown",
                        Map.of("seconds", String.valueOf(toCooldownSeconds(remaining)))))
                        .queue();
                return;
            }
        }

        if (isPrefixMusicCommand(cmd) && !isMusicCommandChannelAllowed(guild, event.getChannel().getIdLong())) {
            event.getChannel().sendMessage(i18n.t(lang, "music.command_channel_restricted")).queue();
            return;
        }

        switch (cmd) {
            case "help" -> helpCommandHandler.handleTextHelp(event.getChannel().asTextChannel(), guild, lang);
            case "volume" -> playbackCommandHandler.handleTextCommand(event, guild, cmd, arg, lang);
            case "history" -> event.getChannel().sendMessageEmbeds(historyCommandHandler.historyEmbed(guild, lang).build()).queue();
            case "playlist" -> playlistCommandHandler.handlePlaylistPrefix(event, guild, arg, lang);
            case "join", "play", "skip", "stop", "leave", "repeat" -> playbackCommandHandler.handleTextCommand(event, guild, cmd, arg, lang);
            case "music" -> event.getChannel().sendMessageEmbeds(musicStatsEmbed(guild, lang).build()).queue();
            default -> event.getChannel().sendMessage(i18n.t(lang, "general.unknown_command")).queue();
        }
        if (isKnownPrefixCommand(cmd)) {
            logCommandUsage(guild, event.getMember(), runtimeConfig.getPrefix() + cmd + (arg.isBlank() ? "" : " " + arg), event.getChannel().getIdLong());
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
    public String validateSettingsActionOptions(SlashCommandInteractionEvent event, String route, String lang) {
        boolean hasCode = event.getOption("code") != null;
        boolean hasChannel = event.getOption("channel") != null;
        boolean hasValue = event.getOption("value") != null;
        boolean hasReset = event.getOption("reset") != null;
        boolean hasLogSetting = event.getOption("log-setting") != null;
        boolean hasUser = event.getOption("user") != null;
        boolean hasPrefix = event.getOption("prefix") != null;
        String logSetting = event.getOption("log-setting") == null ? null : event.getOption("log-setting").getAsString();

        return switch (route) {
            case "language" -> {
                if (hasChannel) yield settingsActionOptionError(lang, route, "channel");
                if (hasValue) yield settingsActionOptionError(lang, route, "value");
                if (hasReset) yield settingsActionOptionError(lang, route, "reset");
                if (hasLogSetting) yield settingsActionOptionError(lang, route, "log-setting");
                if (hasUser) yield settingsActionOptionError(lang, route, "user");
                if (hasPrefix) yield settingsActionOptionError(lang, route, "prefix");
                yield null;
            }
            case "number-chain" -> {
                if (hasCode) yield settingsActionOptionError(lang, route, "code");
                if (hasLogSetting) yield settingsActionOptionError(lang, route, "log-setting");
                if (hasUser) yield settingsActionOptionError(lang, route, "user");
                if (hasPrefix) yield settingsActionOptionError(lang, route, "prefix");
                yield null;
            }
            case "log-settings" -> {
                if (hasCode) yield settingsActionOptionError(lang, route, "code");
                if (hasValue) yield settingsActionOptionError(lang, route, "value");
                if (hasReset) yield settingsActionOptionError(lang, route, "reset");
                if (!hasLogSetting) yield null;
                switch (logSetting) {
                    case "ignore-prefix" -> {
                        if (hasUser) yield settingsActionOptionError(lang, route, "user");
                        if (hasChannel) yield settingsActionOptionError(lang, route, "channel");
                    }
                    case "ignore-member" -> {
                        if (hasPrefix) yield settingsActionOptionError(lang, route, "prefix");
                        if (hasChannel) yield settingsActionOptionError(lang, route, "channel");
                    }
                    case "ignore-channel" -> {
                        if (hasPrefix) yield settingsActionOptionError(lang, route, "prefix");
                        if (hasUser) yield settingsActionOptionError(lang, route, "user");
                    }
                    case "view-ignore" -> {
                        if (hasPrefix) yield settingsActionOptionError(lang, route, "prefix");
                        if (hasUser) yield settingsActionOptionError(lang, route, "user");
                        if (hasChannel) yield settingsActionOptionError(lang, route, "channel");
                    }
                    default -> {
                    }
                }
                yield null;
            }
            default -> {
                if (hasCode) yield settingsActionOptionError(lang, route, "code");
                if (hasChannel) yield settingsActionOptionError(lang, route, "channel");
                if (hasValue) yield settingsActionOptionError(lang, route, "value");
                if (hasReset) yield settingsActionOptionError(lang, route, "reset");
                if (hasLogSetting) yield settingsActionOptionError(lang, route, "log-setting");
                if (hasUser) yield settingsActionOptionError(lang, route, "user");
                if (hasPrefix) yield settingsActionOptionError(lang, route, "prefix");
                yield null;
            }
        };
    }
    public String settingsActionOptionError(String lang, String route, String option) {
        return i18n.t(lang, "settings.option_not_allowed",
                Map.of(
                        "action", settingsActionLabel(lang, route),
                        "option", settingsOptionLabel(lang, route, option)
                ));
    }
    public String settingsActionLabel(String lang, String route) {
        return switch (route) {
            case "info" -> i18n.t(lang, "settings.info");
            case "reload" -> i18n.t(lang, "settings.reload");
            case "reset" -> i18n.t(lang, "settings.reset");
            case "template" -> i18n.t(lang, "settings.template");
            case "module" -> i18n.t(lang, "settings.module");
            case "logs" -> i18n.t(lang, "settings.logs");
            case "log-settings" -> i18n.t(lang, "settings.log_settings.title");
            case "music" -> i18n.t(lang, "settings.music");
            case "language" -> i18n.t(lang, "settings.info_language");
            case "number-chain" -> i18n.t(lang, "settings.info_number_chain");
            default -> route;
        };
    }
    public String settingsOptionLabel(String lang, String route, String option) {
        return switch (option) {
            case "code" -> i18n.t(lang, "settings.language_code_label");
            case "channel" -> "log-settings".equals(route)
                    ? i18n.t(lang, "settings.log_settings.channel")
                    : i18n.t(lang, "number_chain.channel");
            case "value" -> i18n.t(lang, "number_chain.value");
            case "reset" -> i18n.t(lang, "number_chain.reset");
            case "log-setting" -> i18n.t(lang, "settings.log_settings.target");
            case "user" -> i18n.t(lang, "settings.log_settings.user");
            case "prefix" -> i18n.t(lang, "settings.log_settings.prefix");
            default -> option;
        };
    }
    public void openTemplateMenu(SlashCommandInteractionEvent event, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        templateMenuRequests.put(token, new MenuRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(i18n.t(lang, "settings.template_menu_title"))
                        .setDescription(i18n.t(lang, "settings.template_menu_desc")
                                + "\n\n"
                                + i18n.t(lang, "settings.template_member_placeholders_guide"))
                        .build())
                .addComponents(ActionRow.of(settingsTemplateMenu(token, lang)))
                .setEphemeral(true)
                .queue();
    }

    private StringSelectMenu settingsTemplateMenu(String token, String lang) {
        return StringSelectMenu.create(SETTINGS_TEMPLATE_SELECT_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "settings.template_menu_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.info_key_member_join_template"), "member-join"),
                        SelectOption.of(i18n.t(lang, "settings.info_key_member_leave_template"), "member-leave"),
                        SelectOption.of(i18n.t(lang, "settings.info_key_voice_join_template"), "voice-join"),
                        SelectOption.of(i18n.t(lang, "settings.info_key_voice_leave_template"), "voice-leave"),
                        SelectOption.of(i18n.t(lang, "settings.info_key_voice_move_template"), "voice-move")
                )
                .build();
    }
    public void handleTemplateMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_TEMPLATE_SELECT_PREFIX.length());
        MenuRequest request = templateMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            templateMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.template_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        String type = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        switch (type) {
            case "member-join" -> event.replyModal(buildTemplateModal(
                    "member-join",
                    i18n.t(lang, "settings.template_member_placeholders_short"),
                    true,
                    settingsService.getNotifications(event.getGuild().getIdLong()).getMemberJoinColor(),
                    lang
            )).queue();
            case "member-leave" -> event.replyModal(buildTemplateModal(
                    "member-leave",
                    i18n.t(lang, "settings.template_member_placeholders_short"),
                    true,
                    settingsService.getNotifications(event.getGuild().getIdLong()).getMemberLeaveColor(),
                    lang
            )).queue();
            case "voice-join" -> event.replyModal(buildTemplateModal("voice-join", "{user} {channel} {from} {to}", false, null, lang)).queue();
            case "voice-leave" -> event.replyModal(buildTemplateModal("voice-leave", "{user} {channel} {from} {to}", false, null, lang)).queue();
            case "voice-move" -> event.replyModal(buildTemplateModal("voice-move", "{user} {channel} {from} {to}", false, null, lang)).queue();
            default -> event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }
    public void openModuleMenu(SlashCommandInteractionEvent event, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        moduleMenuRequests.put(token, new MenuRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(moduleMenuEmbed(event.getGuild(), lang, null).build())
                .addComponents(ActionRow.of(settingsModuleMenu(token, event.getGuild().getIdLong(), lang)))
                .setEphemeral(true)
                .queue();
    }

    private StringSelectMenu settingsModuleMenu(String token, long guildId, String lang) {
        GuildSettingsService.GuildSettings s = settingsService.getSettings(guildId);
        boolean numberChainEnabled = moderationService.isNumberChainEnabled(guildId);
        return StringSelectMenu.create(SETTINGS_MODULE_SELECT_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "settings.module_menu_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.key_notifications_enabled"), "notifications-enable")
                                .withDescription(moduleSwitchTextPlain(lang, s.getNotifications().isEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_messageLogs_enabled"), "message-log")
                                .withDescription(moduleSwitchTextPlain(lang, s.getMessageLogs().isEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_notifications_memberJoinEnabled"), "member-join")
                                .withDescription(moduleSwitchTextPlain(lang, s.getNotifications().isMemberJoinEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_welcome_enabled"), "welcome-enable")
                                .withDescription(moduleSwitchTextPlain(lang, s.getWelcome().isEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_notifications_memberLeaveEnabled"), "member-leave")
                                .withDescription(moduleSwitchTextPlain(lang, s.getNotifications().isMemberLeaveEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_notifications_voiceLogEnabled"), "voice-log")
                                .withDescription(moduleSwitchTextPlain(lang, s.getNotifications().isVoiceLogEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.info_key_log_command_usage"), "command-usage-log")
                                .withDescription(moduleSwitchTextPlain(lang, s.getMessageLogs().isCommandUsageLogEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.info_key_log_channel_lifecycle"), "channel-events-log")
                                .withDescription(moduleSwitchTextPlain(lang, s.getMessageLogs().isChannelLifecycleLogEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.info_key_log_role"), "role-events-log")
                                .withDescription(moduleSwitchTextPlain(lang, s.getMessageLogs().isRoleLogEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.info_key_log_moderation"), "moderation-log")
                                .withDescription(moduleSwitchTextPlain(lang, s.getMessageLogs().isModerationLogEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_music_autoLeaveEnabled"), "music-auto-leave")
                                .withDescription(moduleSwitchTextPlain(lang, s.getMusic().isAutoLeaveEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_music_autoplayEnabled"), "music-autoplay")
                                .withDescription(moduleSwitchTextPlain(lang, s.getMusic().isAutoplayEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_numberChain_enabled"), "number-chain-enable")
                                .withDescription(moduleSwitchTextPlain(lang, numberChainEnabled)),
                        SelectOption.of(i18n.t(lang, "settings.key_ticket_enabled"), "ticket-enable")
                                .withDescription(moduleSwitchTextPlain(lang, s.getTicket().isEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_privateRoom_enabled"), "private-room-enable")
                                .withDescription(moduleSwitchTextPlain(lang, s.getPrivateRoom().isEnabled()))
                )
                .build();
    }

    private EmbedBuilder moduleMenuEmbed(Guild guild, String lang, String changedText) {
        GuildSettingsService.GuildSettings s = settingsService.getSettings(guild.getIdLong());
        boolean numberChainEnabled = moderationService.isNumberChainEnabled(guild.getIdLong());
        String overview = joinLines(
                "**" + i18n.t(lang, "settings.module_section_core") + "**",
                moduleLine(lang, "settings.key_notifications_enabled", s.getNotifications().isEnabled()),
                moduleLine(lang, "settings.key_messageLogs_enabled", s.getMessageLogs().isEnabled()),
                moduleLine(lang, "settings.key_welcome_enabled", s.getWelcome().isEnabled()),
                "",
                "**" + i18n.t(lang, "settings.module_section_notifications") + "**",
                moduleLine(lang, "settings.key_notifications_memberJoinEnabled", s.getNotifications().isMemberJoinEnabled()),
                moduleLine(lang, "settings.key_notifications_memberLeaveEnabled", s.getNotifications().isMemberLeaveEnabled()),
                moduleLine(lang, "settings.key_notifications_voiceLogEnabled", s.getNotifications().isVoiceLogEnabled()),
                "",
                "**" + i18n.t(lang, "settings.module_section_logs") + "**",
                moduleLine(lang, "settings.info_key_log_command_usage", s.getMessageLogs().isCommandUsageLogEnabled()),
                moduleLine(lang, "settings.info_key_log_channel_lifecycle", s.getMessageLogs().isChannelLifecycleLogEnabled()),
                moduleLine(lang, "settings.info_key_log_role", s.getMessageLogs().isRoleLogEnabled()),
                moduleLine(lang, "settings.info_key_log_moderation", s.getMessageLogs().isModerationLogEnabled()),
                "",
                "**" + i18n.t(lang, "settings.module_section_music_others") + "**",
                moduleLine(lang, "settings.key_music_autoLeaveEnabled", s.getMusic().isAutoLeaveEnabled()),
                moduleLine(lang, "settings.key_music_autoplayEnabled", s.getMusic().isAutoplayEnabled()),
                moduleLine(lang, "settings.key_numberChain_enabled", numberChainEnabled),
                moduleLine(lang, "settings.key_ticket_enabled", s.getTicket().isEnabled()),
                moduleLine(lang, "settings.key_privateRoom_enabled", s.getPrivateRoom().isEnabled())
        );
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(52, 152, 219))
                .setTitle(i18n.t(lang, "settings.module_menu_title"))
                .setDescription(i18n.t(lang, "settings.module_menu_desc"));
        eb.addField(i18n.t(lang, "settings.info_module"), overview, false);
        if (changedText != null && !changedText.isBlank()) {
            eb.addField(i18n.t(lang, "settings.template_updated"), changedText, false);
        }
        return eb;
    }

    private String moduleLine(String lang, String key, boolean value) {
        return keyIcon(key) + " " + i18n.t(lang, key) + ": " + moduleSwitchTextCode(lang, value);
    }

    private String quotedSettingLine(String lang, String key, String labelKey, String value) {
        return keyIcon(key) + " " + i18n.t(lang, key) + "\n> " + i18n.t(lang, labelKey) + ": " + value;
    }
    public void handleModuleMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_MODULE_SELECT_PREFIX.length());
        MenuRequest request = moduleMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            moduleMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.module_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        if (needsDefaultLogChannel(action)
                && !settingsService.getMessageLogs(event.getGuild().getIdLong()).isEnabled()
                && settingsService.getMessageLogs(event.getGuild().getIdLong()).getChannelId() == null) {
            event.reply(i18n.t(lang, "settings.logs_default_required")).setEphemeral(true).queue();
            return;
        }
        ToggleResult result = toggleModuleValue(event.getGuild().getIdLong(), action);
        if (result == null) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        String keyText = i18n.t(lang, result.key);
        String changed = i18n.t(lang, "general.settings_saved", Map.of("key", keyText, "value", moduleSwitchTextCode(lang, result.value)));
        event.editMessageEmbeds(moduleMenuEmbed(event.getGuild(), lang, changed).build())
                .setComponents(ActionRow.of(settingsModuleMenu(token, event.getGuild().getIdLong(), lang)))
                .queue();
    }

    private ToggleResult toggleModuleValue(long guildId, String action) {
        switch (action) {
            case "notifications-enable" -> {
                boolean value = !settingsService.getNotifications(guildId).isEnabled();
                settingsService.updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withEnabled(value)));
                return new ToggleResult("settings.key_notifications_enabled", value);
            }
            case "voice-log" -> {
                boolean value = !settingsService.getNotifications(guildId).isVoiceLogEnabled();
                settingsService.updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withVoiceLogEnabled(value)));
                return new ToggleResult("settings.key_notifications_voiceLogEnabled", value);
            }
            case "message-log" -> {
                boolean value = !settingsService.getMessageLogs(guildId).isEnabled();
                settingsService.updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withEnabled(value)));
                return new ToggleResult("settings.key_messageLogs_enabled", value);
            }
            case "member-leave" -> {
                boolean value = !settingsService.getNotifications(guildId).isMemberLeaveEnabled();
                settingsService.updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withMemberLeaveEnabled(value)));
                return new ToggleResult("settings.key_notifications_memberLeaveEnabled", value);
            }
            case "welcome-enable" -> {
                boolean value = !settingsService.getWelcome(guildId).isEnabled();
                settingsService.updateSettings(guildId, s -> s.withWelcome(s.getWelcome().withEnabled(value)));
                return new ToggleResult("settings.key_welcome_enabled", value);
            }
            case "member-join" -> {
                boolean value = !settingsService.getNotifications(guildId).isMemberJoinEnabled();
                settingsService.updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withMemberJoinEnabled(value)));
                return new ToggleResult("settings.key_notifications_memberJoinEnabled", value);
            }
            case "command-usage-log" -> {
                boolean value = !settingsService.getMessageLogs(guildId).isCommandUsageLogEnabled();
                settingsService.updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withCommandUsageLogEnabled(value)));
                return new ToggleResult("settings.info_key_log_command_usage", value);
            }
            case "channel-events-log" -> {
                boolean value = !settingsService.getMessageLogs(guildId).isChannelLifecycleLogEnabled();
                settingsService.updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withChannelLifecycleLogEnabled(value)));
                return new ToggleResult("settings.info_key_log_channel_lifecycle", value);
            }
            case "role-events-log" -> {
                boolean value = !settingsService.getMessageLogs(guildId).isRoleLogEnabled();
                settingsService.updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withRoleLogEnabled(value)));
                return new ToggleResult("settings.info_key_log_role", value);
            }
            case "moderation-log" -> {
                boolean value = !settingsService.getMessageLogs(guildId).isModerationLogEnabled();
                settingsService.updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withModerationLogEnabled(value)));
                return new ToggleResult("settings.info_key_log_moderation", value);
            }
            case "music-auto-leave" -> {
                boolean value = !settingsService.getMusic(guildId).isAutoLeaveEnabled();
                settingsService.updateSettings(guildId, s -> s.withMusic(s.getMusic().withAutoLeaveEnabled(value)));
                return new ToggleResult("settings.key_music_autoLeaveEnabled", value);
            }
            case "music-autoplay" -> {
                boolean value = !settingsService.getMusic(guildId).isAutoplayEnabled();
                settingsService.updateSettings(guildId, s -> s.withMusic(s.getMusic().withAutoplayEnabled(value)));
                if (!value) {
                    musicService.clearAutoplayNotice(guildId);
                }
                return new ToggleResult("settings.key_music_autoplayEnabled", value);
            }
            case "number-chain-enable" -> {
                boolean value = !moderationService.isNumberChainEnabled(guildId);
                moderationService.setNumberChainEnabled(guildId, value);
                return new ToggleResult("settings.key_numberChain_enabled", value);
            }
            case "private-room-enable" -> {
                boolean value = !settingsService.getPrivateRoom(guildId).isEnabled();
                settingsService.updateSettings(guildId, s -> s.withPrivateRoom(s.getPrivateRoom().withEnabled(value)));
                return new ToggleResult("settings.key_privateRoom_enabled", value);
            }
            case "ticket-enable" -> {
                boolean value = !settingsService.getTicket(guildId).isEnabled();
                settingsService.updateSettings(guildId, s -> s.withTicket(s.getTicket().withEnabled(value)));
                return new ToggleResult("settings.key_ticket_enabled", value);
            }
            default -> {
                return null;
            }
        }
    }
    public void openLogsMenu(SlashCommandInteractionEvent event, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        logsMenuRequests.put(token, new MenuRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle(i18n.t(lang, "settings.logs_menu_title"))
                        .setDescription(i18n.t(lang, "settings.logs_menu_desc"))
                        .build())
                .addComponents(ActionRow.of(settingsLogsMenu(token, event.getGuild().getIdLong(), lang)))
                .setEphemeral(true)
                .queue();
    }

    private StringSelectMenu settingsLogsMenu(String token, long guildId, String lang) {
        return StringSelectMenu.create(SETTINGS_LOGS_SELECT_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "settings.logs_menu_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.info_key_log_channel"), "default-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "default-channel")),
                        SelectOption.of(i18n.t(lang, "settings.key_messageLogs_messageLogChannelId"), "messages-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "messages-channel")),
                        SelectOption.of(i18n.t(lang, "settings.key_notifications_memberChannelId"), "member-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "member-channel")),
                        SelectOption.of(i18n.t(lang, "settings.key_notifications_voiceChannelId"), "voice-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "voice-channel")),
                        SelectOption.of(i18n.t(lang, "settings.key_messageLogs_commandUsageChannelId"), "command-usage-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "command-usage-channel")),
                        SelectOption.of(i18n.t(lang, "settings.key_messageLogs_channelLifecycleChannelId"), "channel-events-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "channel-events-channel")),
                        SelectOption.of(i18n.t(lang, "settings.key_messageLogs_roleLogChannelId"), "role-events-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "role-events-channel")),
                        SelectOption.of(i18n.t(lang, "settings.key_messageLogs_moderationLogChannelId"), "moderation-channel")
                                .withDescription(logsModuleStatusText(lang, guildId, "moderation-channel"))
                )
                .build();
    }
    public void handleLogsMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_LOGS_SELECT_PREFIX.length());
        MenuRequest request = logsMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            logsMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.logs_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        String target = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        if (target.isBlank()) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }

        if ("member-channel".equals(target)) {
            var notifications = settingsService.getNotifications(event.getGuild().getIdLong());
            boolean split = notifications.getMemberJoinChannelId() != null || notifications.getMemberLeaveChannelId() != null;
            if (!split) {
                openSharedMemberChannelPicker(event, token, lang);
                return;
            }
            event.editMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(241, 196, 15))
                            .setTitle(i18n.t(lang, "settings.logs_member_mode_title"))
                            .setDescription(i18n.t(lang, "settings.logs_member_mode_desc"))
                            .build())
                    .setComponents(ActionRow.of(settingsMemberChannelModeMenu(token, event.getGuild().getIdLong(), lang)))
                    .queue();
            return;
        }

        String channelComponentId = SETTINGS_LOGS_CHANNEL_PREFIX + token + ":" + target;
        EntitySelectMenu channelMenu = EntitySelectMenu.create(channelComponentId, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder(i18n.t(lang, "settings.logs_menu_channel_placeholder"))
                .setRequiredRange(1, 1)
                .build();

        String key = logsTargetKey(target);
        String keyText = key == null ? target : i18n.t(lang, key);
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle(i18n.t(lang, "settings.logs_menu_pick_channel_title"))
                        .setDescription(i18n.t(lang, "settings.logs_menu_pick_channel_desc", Map.of("target", keyText)))
                        .build())
                .setComponents(ActionRow.of(channelMenu))
                .queue();
    }

    private StringSelectMenu settingsMemberChannelModeMenu(String token, long guildId, String lang) {
        return StringSelectMenu.create(SETTINGS_LOGS_MEMBER_MODE_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "settings.logs_member_mode_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.logs_member_mode_shared"), "member-channel-shared"),
                        SelectOption.of(i18n.t(lang, "settings.logs_member_mode_split"), "member-channel-split")
                )
                .build();
    }

    private StringSelectMenu settingsMemberSplitMenu(String token, long guildId, String lang) {
        var n = settingsService.getNotifications(guildId);
        String joinValue = safe(formatTextChannelById(guildId, n.getMemberJoinChannelId()), 80);
        String leaveValue = safe(formatTextChannelById(guildId, n.getMemberLeaveChannelId()), 80);
        return StringSelectMenu.create(SETTINGS_LOGS_MEMBER_SPLIT_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "settings.logs_member_split_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.logs_member_split_join"), "member-join-channel")
                                .withDescription(i18n.t(lang, "settings.music_menu_current", Map.of("value", joinValue))),
                        SelectOption.of(i18n.t(lang, "settings.logs_member_split_leave"), "member-leave-channel")
                                .withDescription(i18n.t(lang, "settings.music_menu_current", Map.of("value", leaveValue)))
                )
                .build();
    }
    public void handleLogsMemberModeSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_LOGS_MEMBER_MODE_PREFIX.length());
        MenuRequest request = logsMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            logsMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.logs_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }

        String mode = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        if ("member-channel-shared".equals(mode)) {
            openSharedMemberChannelPicker(event, token, lang);
            return;
        }

        if ("member-channel-split".equals(mode)) {
            event.editMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(241, 196, 15))
                            .setTitle(i18n.t(lang, "settings.logs_member_split_title"))
                            .setDescription(i18n.t(lang, "settings.logs_member_split_desc"))
                            .build())
                    .setComponents(ActionRow.of(settingsMemberSplitMenu(token, event.getGuild().getIdLong(), lang)))
                    .queue();
            return;
        }

        event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
    }

    private void openSharedMemberChannelPicker(StringSelectInteractionEvent event, String token, String lang) {
        String channelComponentId = SETTINGS_LOGS_CHANNEL_PREFIX + token + ":member-channel-shared";
        EntitySelectMenu channelMenu = EntitySelectMenu.create(channelComponentId, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder(i18n.t(lang, "settings.logs_menu_channel_placeholder"))
                .setRequiredRange(1, 1)
                .build();
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle(i18n.t(lang, "settings.logs_menu_pick_channel_title"))
                        .setDescription(i18n.t(lang, "settings.logs_menu_pick_channel_desc",
                                Map.of("target", i18n.t(lang, "settings.logs_member_mode_shared"))))
                        .build())
                .setComponents(ActionRow.of(channelMenu))
                .queue();
    }
    public void handleLogsMemberSplitSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_LOGS_MEMBER_SPLIT_PREFIX.length());
        MenuRequest request = logsMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            logsMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.logs_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        String target = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        if (!"member-join-channel".equals(target) && !"member-leave-channel".equals(target)) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        String channelComponentId = SETTINGS_LOGS_CHANNEL_PREFIX + token + ":" + target;
        EntitySelectMenu channelMenu = EntitySelectMenu.create(channelComponentId, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder(i18n.t(lang, "settings.logs_menu_channel_placeholder"))
                .setRequiredRange(1, 1)
                .build();
        String targetText = "member-join-channel".equals(target)
                ? i18n.t(lang, "settings.logs_member_split_join")
                : i18n.t(lang, "settings.logs_member_split_leave");
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle(i18n.t(lang, "settings.logs_menu_pick_channel_title"))
                        .setDescription(i18n.t(lang, "settings.logs_menu_pick_channel_desc", Map.of("target", targetText)))
                        .build())
                .setComponents(ActionRow.of(channelMenu))
                .queue();
    }
    public void handleLogsChannelSelect(EntitySelectInteractionEvent event, String lang) {
        String suffix = event.getComponentId().substring(SETTINGS_LOGS_CHANNEL_PREFIX.length());
        int idx = suffix.indexOf(':');
        if (idx <= 0 || idx >= suffix.length() - 1) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        String token = suffix.substring(0, idx);
        String target = suffix.substring(idx + 1);

        MenuRequest request = logsMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            logsMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.logs_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }

        List<TextChannel> channels = event.getMentions().getChannels(TextChannel.class);
        if (channels.isEmpty()) {
            event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
            return;
        }
        GuildChannel selected = channels.get(0);
        if (!(selected instanceof TextChannel textChannel)) {
            event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
            return;
        }
        String missing = formatMissingPermissions(event.getGuild().getSelfMember(), textChannel,
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
        if (!"-".equals(missing)) {
            event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", missing)))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        long guildId = event.getGuild().getIdLong();
        switch (target) {
            case "default-channel" ->
                    settingsService.updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withChannelId(textChannel.getIdLong())));
            case "messages-channel" ->
                    settingsService.updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withMessageLogChannelId(textChannel.getIdLong())));
            case "member-channel-shared" ->
                    settingsService.updateSettings(guildId, s -> s.withNotifications(s.getNotifications()
                            .withMemberChannelId(textChannel.getIdLong())
                            .withMemberJoinChannelId(null)
                            .withMemberLeaveChannelId(null)));
            case "member-join-channel" ->
                    settingsService.updateSettings(guildId, s -> s.withNotifications(s.getNotifications()
                            .withMemberChannelId(null)
                            .withMemberJoinChannelId(textChannel.getIdLong())));
            case "member-leave-channel" ->
                    settingsService.updateSettings(guildId, s -> s.withNotifications(s.getNotifications()
                            .withMemberChannelId(null)
                            .withMemberLeaveChannelId(textChannel.getIdLong())));
            case "voice-channel" ->
                    settingsService.updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withVoiceChannelId(textChannel.getIdLong())));
            case "command-usage-channel" ->
                    settingsService.updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withCommandUsageChannelId(textChannel.getIdLong())));
            case "channel-events-channel" ->
                    settingsService.updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withChannelLifecycleChannelId(textChannel.getIdLong())));
            case "role-events-channel" ->
                    settingsService.updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withRoleLogChannelId(textChannel.getIdLong())));
            case "moderation-channel" ->
                    settingsService.updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withModerationLogChannelId(textChannel.getIdLong())));
            default -> {
                event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
                return;
            }
        }
        String key = logsTargetKey(target);
        String keyText = key == null ? target : i18n.t(lang, key);
        String savedText = i18n.t(lang, "general.settings_saved", Map.of("key", keyText, "value", textChannel.getAsMention()));
        if ("member-join-channel".equals(target) || "member-leave-channel".equals(target)) {
            event.editMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(46, 204, 113))
                            .setTitle(i18n.t(lang, "settings.logs_member_split_title"))
                            .setDescription(savedText)
                            .build())
                    .setComponents(ActionRow.of(settingsMemberSplitMenu(token, guildId, lang)))
                    .queue();
            return;
        }
        if ("member-channel-shared".equals(target)) {
            event.editMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(46, 204, 113))
                            .setTitle(i18n.t(lang, "settings.logs_member_mode_title"))
                            .setDescription(savedText)
                            .build())
                    .setComponents(ActionRow.of(settingsMemberChannelModeMenu(token, guildId, lang)))
                    .queue();
            return;
        }

        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(i18n.t(lang, "settings.logs_menu_title"))
                        .setDescription(savedText)
                        .build())
                .setComponents(ActionRow.of(settingsLogsMenu(token, guildId, lang)))
                .queue();
    }

    private String logsTargetKey(String target) {
        return switch (target) {
            case "default-channel" -> "settings.info_key_log_channel";
            case "messages-channel" -> "settings.key_messageLogs_messageLogChannelId";
            case "member-channel", "member-channel-shared" -> "settings.key_notifications_memberChannelId";
            case "member-join-channel" -> "settings.info_key_member_join_channel";
            case "member-leave-channel" -> "settings.info_key_member_leave_channel";
            case "voice-channel" -> "settings.key_notifications_voiceChannelId";
            case "command-usage-channel" -> "settings.key_messageLogs_commandUsageChannelId";
            case "channel-events-channel" -> "settings.key_messageLogs_channelLifecycleChannelId";
            case "role-events-channel" -> "settings.key_messageLogs_roleLogChannelId";
            case "moderation-channel" -> "settings.key_messageLogs_moderationLogChannelId";
            default -> null;
        };
    }
    public void openMusicMenu(SlashCommandInteractionEvent event, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        musicMenuRequests.put(token, new MenuRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(musicMenuEmbed(event.getGuild(), lang, null).build())
                .addComponents(ActionRow.of(settingsMusicMenu(token, event.getGuild(), lang)))
                .setEphemeral(true)
                .queue();
    }

    private StringSelectMenu settingsMusicMenu(String token, Guild guild, String lang) {
        long guildId = guild.getIdLong();
        var music = settingsService.getMusic(guildId);
        var room = settingsService.getPrivateRoom(guildId);
        return StringSelectMenu.create(SETTINGS_MUSIC_SELECT_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "settings.music_menu_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.key_music_autoLeaveEnabled"), "auto-leave-toggle")
                                .withDescription(i18n.t(lang, "settings.music_menu_current",
                                        Map.of("value", boolText(lang, music.isAutoLeaveEnabled())))),
                        SelectOption.of(i18n.t(lang, "settings.key_music_autoLeaveMinutes"), "auto-leave-minutes")
                                .withDescription(i18n.t(lang, "settings.music_menu_current",
                                        Map.of("value", String.valueOf(music.getAutoLeaveMinutes())))),
                        SelectOption.of(i18n.t(lang, "settings.key_music_autoplayEnabled"), "autoplay-toggle")
                                .withDescription(i18n.t(lang, "settings.music_menu_current",
                                        Map.of("value", boolText(lang, music.isAutoplayEnabled())))),
                        SelectOption.of(i18n.t(lang, "settings.key_music_commandChannelId"), "command-channel")
                                .withDescription(i18n.t(lang, "settings.music_menu_current",
                                        Map.of("value", safe(formatTextChannel(guild, music.getCommandChannelId()), 60)))),
                        SelectOption.of(i18n.t(lang, "settings.key_privateRoom_triggerVoiceChannelId"), "private-room-channel")
                                .withDescription(i18n.t(lang, "settings.music_menu_current",
                                        Map.of("value", safe(formatVoiceChannel(guild, room.getTriggerVoiceChannelId()), 60))))
                )
                .build();
    }

    private EmbedBuilder musicMenuEmbed(Guild guild, String lang, String changedText) {
        long guildId = guild.getIdLong();
        var music = settingsService.getMusic(guildId);
        var room = settingsService.getPrivateRoom(guildId);
        String body = String.join("\n\n",
                quotedSettingLine(lang, "settings.key_music_autoLeaveEnabled", "settings.status_label",
                        boolText(lang, music.isAutoLeaveEnabled())),
                quotedSettingLine(lang, "settings.key_music_autoLeaveMinutes", "settings.value_label",
                        String.valueOf(music.getAutoLeaveMinutes())),
                quotedSettingLine(lang, "settings.key_music_autoplayEnabled", "settings.status_label",
                        boolText(lang, music.isAutoplayEnabled())),
                quotedSettingLine(lang, "settings.key_music_commandChannelId", "settings.value_label",
                        formatTextChannel(guild, music.getCommandChannelId())),
                quotedSettingLine(lang, "settings.key_privateRoom_triggerVoiceChannelId", "settings.value_label",
                        formatVoiceChannel(guild, room.getTriggerVoiceChannelId()))
        );
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(155, 89, 182))
                .setTitle("\uD83C\uDFB5 " + i18n.t(lang, "settings.music_menu_title"))
                .setDescription(i18n.t(lang, "settings.music_menu_desc"))
                .addField("\uD83C\uDFBC " + i18n.t(lang, "settings.info_music"), body, false);
        if (changedText != null && !changedText.isBlank()) {
            eb.addField(i18n.t(lang, "settings.template_updated"), changedText, false);
        }
        return eb;
    }
    public void handleMusicMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_MUSIC_SELECT_PREFIX.length());
        MenuRequest request = musicMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            musicMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.music_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        long guildId = event.getGuild().getIdLong();
        String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        switch (action) {
            case "auto-leave-toggle" -> {
                boolean value = !settingsService.getMusic(guildId).isAutoLeaveEnabled();
                settingsService.updateSettings(guildId, s -> s.withMusic(s.getMusic().withAutoLeaveEnabled(value)));
                String changed = i18n.t(lang, "general.settings_saved",
                        Map.of("key", i18n.t(lang, "settings.key_music_autoLeaveEnabled"), "value", boolText(lang, value)));
                event.editMessageEmbeds(musicMenuEmbed(event.getGuild(), lang, changed).build())
                        .setComponents(ActionRow.of(settingsMusicMenu(token, event.getGuild(), lang)))
                        .queue();
            }
            case "autoplay-toggle" -> {
                boolean value = !settingsService.getMusic(guildId).isAutoplayEnabled();
                settingsService.updateSettings(guildId, s -> s.withMusic(s.getMusic().withAutoplayEnabled(value)));
                if (!value) {
                    musicService.clearAutoplayNotice(guildId);
                }
                String changed = i18n.t(lang, "general.settings_saved",
                        Map.of("key", i18n.t(lang, "settings.key_music_autoplayEnabled"), "value", boolText(lang, value)));
                event.editMessageEmbeds(musicMenuEmbed(event.getGuild(), lang, changed).build())
                        .setComponents(ActionRow.of(settingsMusicMenu(token, event.getGuild(), lang)))
                        .queue();
            }
            case "auto-leave-minutes" -> {
                int currentMinutes = settingsService.getMusic(guildId).getAutoLeaveMinutes();
                TextInput input = TextInput.create("minutes", TextInputStyle.SHORT)
                        .setRequired(true)
                        .setPlaceholder("1-60")
                        .setValue(String.valueOf(currentMinutes))
                        .build();
                Modal modal = Modal.create(SETTINGS_MUSIC_MODAL_PREFIX + token + ":auto-leave-minutes",
                                i18n.t(lang, "settings.music_menu_minutes_title"))
                        .addComponents(Label.of(i18n.t(lang, "settings.music_menu_minutes_hint"), input))
                        .build();
                event.replyModal(modal).queue();
            }
            case "command-channel", "private-room-channel" -> {
                String componentId = SETTINGS_MUSIC_CHANNEL_PREFIX + token + ":" + action;
                EntitySelectMenu.Builder channelBuilder = EntitySelectMenu
                        .create(componentId, EntitySelectMenu.SelectTarget.CHANNEL)
                        .setRequiredRange(1, 1)
                        .setPlaceholder(i18n.t(lang, "settings.music_menu_channel_placeholder"));
                if ("command-channel".equals(action)) {
                    channelBuilder.setChannelTypes(ChannelType.TEXT);
                } else {
                    channelBuilder.setChannelTypes(ChannelType.VOICE, ChannelType.STAGE);
                }
                String key = musicTargetKey(action);
                String keyText = key == null ? action : i18n.t(lang, key);
                event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(155, 89, 182))
                                .setTitle(i18n.t(lang, "settings.music_menu_pick_channel_title"))
                                .setDescription(i18n.t(lang, "settings.music_menu_pick_channel_desc", Map.of("target", keyText)))
                                .build())
                        .setComponents(ActionRow.of(channelBuilder.build()))
                        .queue();
            }
            default -> event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }
    public void handleMusicMenuModal(ModalInteractionEvent event, String lang) {
        if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String suffix = event.getModalId().substring(SETTINGS_MUSIC_MODAL_PREFIX.length());
        int idx = suffix.indexOf(':');
        if (idx <= 0 || idx >= suffix.length() - 1) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        String token = suffix.substring(0, idx);
        String action = suffix.substring(idx + 1);
        MenuRequest request = musicMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            musicMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.music_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId || event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        if (!"auto-leave-minutes".equals(action)) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        String text = Objects.requireNonNull(event.getValue("minutes")).getAsString().trim();
        int minutes;
        try {
            minutes = Integer.parseInt(text);
        } catch (Exception ignored) {
            event.reply(i18n.t(lang, "settings.music_menu_minutes_invalid")).setEphemeral(true).queue();
            return;
        }
        if (minutes < 1 || minutes > 60) {
            event.reply(i18n.t(lang, "settings.music_menu_minutes_invalid")).setEphemeral(true).queue();
            return;
        }
        settingsService.updateSettings(event.getGuild().getIdLong(), s -> s.withMusic(s.getMusic().withAutoLeaveMinutes(minutes)));
        String changed = i18n.t(lang, "general.settings_saved",
                Map.of("key", i18n.t(lang, "settings.key_music_autoLeaveMinutes"), "value", String.valueOf(minutes)));
        event.replyEmbeds(musicMenuEmbed(event.getGuild(), lang, changed).build())
                .addComponents(ActionRow.of(settingsMusicMenu(token, event.getGuild(), lang)))
                .setEphemeral(true)
                .queue();
    }

    void handleMusicChannelSelect(EntitySelectInteractionEvent event, String lang) {
        String suffix = event.getComponentId().substring(SETTINGS_MUSIC_CHANNEL_PREFIX.length());
        int idx = suffix.indexOf(':');
        if (idx <= 0 || idx >= suffix.length() - 1) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        String token = suffix.substring(0, idx);
        String target = suffix.substring(idx + 1);

        MenuRequest request = musicMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            musicMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.music_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }

        List<GuildChannel> channels = event.getMentions().getChannels();
        if (channels.isEmpty()) {
            event.reply(i18n.t(lang, "general.invalid_channel")).setEphemeral(true).queue();
            return;
        }
        GuildChannel selected = channels.get(0);
        long guildId = event.getGuild().getIdLong();
        String displayValue;
        switch (target) {
            case "command-channel" -> {
                if (selected.getType() != ChannelType.TEXT) {
                    event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                    return;
                }
                settingsService.updateSettings(guildId, s -> s.withMusic(s.getMusic().withCommandChannelId(selected.getIdLong())));
                displayValue = "<#" + selected.getId() + ">";
            }
            case "private-room-channel" -> {
                if (selected.getType() != ChannelType.VOICE && selected.getType() != ChannelType.STAGE) {
                    event.reply(i18n.t(lang, "settings.validation_expected_voice_channel")).setEphemeral(true).queue();
                    return;
                }
                settingsService.updateSettings(guildId, s -> s.withPrivateRoom(
                        s.getPrivateRoom()
                                .withTriggerVoiceChannelId(selected.getIdLong())
                                .withEnabled(true)
                ));
                displayValue = "<#" + selected.getId() + ">";
            }
            default -> {
                event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
                return;
            }
        }
        String key = musicTargetKey(target);
        String keyText = key == null ? target : i18n.t(lang, key);
        String changed = i18n.t(lang, "general.settings_saved", Map.of("key", keyText, "value", displayValue));
        if ("private-room-channel".equals(target)) {
            changed = changed + "\n" + i18n.t(lang, "settings.private_room_auto_enabled_notice");
        }
        event.editMessageEmbeds(musicMenuEmbed(event.getGuild(), lang, changed).build())
                .setComponents(ActionRow.of(settingsMusicMenu(token, event.getGuild(), lang)))
                .queue();
    }

    private String musicTargetKey(String target) {
        return switch (target) {
            case "command-channel" -> "settings.key_music_commandChannelId";
            case "private-room-channel" -> "settings.key_privateRoom_triggerVoiceChannelId";
            default -> null;
        };
    }
    public void handleDeleteSlash(SlashCommandInteractionEvent event, String lang) {
        if (!has(event.getMember(), Permission.MESSAGE_MANAGE)) {
            event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MESSAGE_MANAGE.getName()))).setEphemeral(true).queue();
            return;
        }

        var timeOption = event.getOption("time");
        var amountOption = event.getOption("amount");
        boolean explicitTime = timeOption != null;
        boolean explicitAmount = amountOption != null;
        Duration lookback;
        if (explicitTime) {
            String timeInput = Objects.requireNonNull(timeOption.getAsString()).trim();
            lookback = parseDeleteLookback(timeInput);
        } else if (explicitAmount) {
            lookback = Duration.ofDays(14);
        } else {
            lookback = Duration.ofHours(24);
        }
        if (lookback == null) {
            event.reply(i18n.t(lang, "delete.time_range")).setEphemeral(true).queue();
            return;
        }

        Integer amount = amountOption == null ? null : (int) amountOption.getAsLong();
        if (amount != null && (amount < 1 || amount > 99)) {
            event.reply(i18n.t(lang, "delete.amount_range")).setEphemeral(true).queue();
            return;
        }

        String sub = event.getSubcommandName();
        if (sub == null && event.getOption("type") != null) {
            sub = Objects.requireNonNull(event.getOption("type")).getAsString();
        }
        if (sub == null || sub.isBlank()) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        sub = canonicalDeleteSubcommand(sub);
        TextChannel channel = null;
        Long targetUserId = null;
        String scope;
        String extraNotice = "";
        if ("channel".equals(sub)) {
            var channelOption = event.getOption("channel");
            if (channelOption == null) {
                if (event.getChannelType() != ChannelType.TEXT) {
                    event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                    return;
                }
                channel = event.getChannel().asTextChannel();
                extraNotice = i18n.t(lang, "delete.default_channel_notice", Map.of("channel", channel.getAsMention()));
            } else {
                if (channelOption.getAsChannel().getType() != ChannelType.TEXT) {
                    event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                    return;
                }
                channel = channelOption.getAsChannel().asTextChannel();
            }
            if (event.getOption("user") != null) {
                targetUserId = Objects.requireNonNull(event.getOption("user")).getAsUser().getIdLong();
            }
            scope = channel.getAsMention()
                    + (targetUserId == null ? "" : " \u00b7 " + Objects.requireNonNull(event.getOption("user")).getAsUser().getAsMention());
        } else {
            if (event.getOption("user") == null) {
                event.reply(i18n.t(lang, "general.invalid_user")).setEphemeral(true).queue();
                return;
            }
            var channelOption = event.getOption("channel");
            if (channelOption != null) {
                if (channelOption.getAsChannel().getType() != ChannelType.TEXT) {
                    event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                    return;
                }
                channel = channelOption.getAsChannel().asTextChannel();
            }
            targetUserId = Objects.requireNonNull(event.getOption("user")).getAsUser().getIdLong();
            scope = Objects.requireNonNull(event.getOption("user")).getAsUser().getAsMention()
                    + (channel == null ? " \u00b7 \u5168\u90e8\u6587\u5b57\u983b\u9053" : " \u00b7 " + channel.getAsMention());
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        deleteRequests.put(token, new DeleteRequest(
                event.getUser().getIdLong(),
                channel == null ? null : channel.getIdLong(),
                targetUserId,
                lookback,
                amount,
                Instant.now().plus(DELETE_REQUEST_TTL)
        ));

                event.replyEmbeds(new EmbedBuilder()
                        .setTitle(i18n.t(lang, "delete.confirm_title"))
                        .setDescription(i18n.t(lang, "delete.confirm_body", Map.of("count", deleteAmountText(lang, amount), "scope", scope))
                                + (explicitTime ? "\n" + deleteTimeNotice(lang, lookback) : "")
                                + (!explicitTime && !explicitAmount ? "\n" + deleteDefaultTimeNotice(lang, "24h") : "")
                                + (extraNotice.isBlank() ? "" : "\n" + extraNotice))
                        .addField("Info", i18n.t(lang, "delete.confirm_warning"), false)
                        .setColor(new Color(241, 196, 15))
                        .build())
                .addComponents(ActionRow.of(
                        Button.danger(DELETE_CONFIRM_PREFIX + token, i18n.t(lang, "delete.confirm_button")),
                        Button.secondary(DELETE_CANCEL_PREFIX + token, i18n.t(lang, "delete.cancel_button"))
                ))
                .setEphemeral(true)
                .queue();
    }
    public void handleDeleteButtons(ButtonInteractionEvent event, String lang) {
        String id = event.getComponentId();
        String token = id.substring(id.lastIndexOf(':') + 1);
        DeleteRequest req = deleteRequests.get(token);
        if (req == null || Instant.now().isAfter(req.expiresAt)) {
            deleteRequests.remove(token);
            event.reply(i18n.t(lang, "delete.cancelled")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != req.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        if (id.startsWith(DELETE_CANCEL_PREFIX)) {
            deleteRequests.remove(token);
            event.editMessage(i18n.t(lang, "delete.cancelled")).setComponents(List.of()).queue();
            return;
        }
        Guild guild = event.getGuild();
        event.deferEdit().queue(
                success -> {
                    event.getHook().editOriginal(i18n.t(lang, "delete.processing"))
                            .setComponents(List.of())
                            .queue();
                    scheduler.execute(() -> {
                        try {
                            int deleted = performDeleteRequest(guild, req);
                            deleteRequests.remove(token);
                            if (deleted <= 0) {
                                event.getHook().editOriginal(i18n.t(lang, "delete.no_target")).queue();
                            } else {
                                event.getHook().editOriginal(i18n.t(lang, "delete.processed", Map.of("count", String.valueOf(deleted)))).queue();
                            }
                        } catch (Exception ex) {
                            deleteRequests.remove(token);
                            event.getHook().editOriginal(i18n.t(lang, "delete.failed")).queue();
                        }
                    });
                },
                failure -> event.reply(i18n.t(lang, "delete.failed")).setEphemeral(true).queue()
        );
    }

    void recreatePanelForChannel(Guild guild, TextChannel channel, String lang) {
        musicPanelController.recreatePanelForChannel(guild, channel, lang);
    }

    void moveActivePanelToBottom(Guild guild, TextChannel preferredChannel) {
        musicPanelController.moveActivePanelToBottom(guild, preferredChannel);
    }

    void setRepeat(Guild guild, String input) {
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

    void toggleAutoplay(long guildId) {
        settingsService.updateSettings(guildId, s -> s.withMusic(s.getMusic().withAutoplayEnabled(!s.getMusic().isAutoplayEnabled())));
        musicService.clearAutoplayNotice(guildId);
    }

    EmbedBuilder helpEmbed(Guild guild, String lang, String category) {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(new Color(52, 152, 219));
        eb.setTitle("NoRule Help Center");
        String botDesc = runtimeConfig.getBotDescription();
        String intro = i18n.t(lang, "help.intro");
        if (botDesc != null && !botDesc.isBlank()) {
            eb.setDescription(botDesc + "\n\n" + intro);
        } else {
            eb.setDescription(intro);
        }
        eb.setFooter(guild.getName(), guild.getIconUrl());
        switch (category) {
            case "music" -> eb.addField(i18n.t(lang, "help.category_music"), i18n.t(lang, "help.content_music"), false);
            case "settings" -> eb.addField(i18n.t(lang, "help.category_settings"), i18n.t(lang, "help.content_settings"), false);
            case "moderation" -> eb.addField(i18n.t(lang, "help.category_moderation"), i18n.t(lang, "help.content_moderation"), false);
            case "private-room" -> eb.addField(i18n.t(lang, "help.category_private_room"), i18n.t(lang, "help.content_private_room"), false);
            case "ticket" -> eb.addField(i18n.t(lang, "help.category_ticket"), i18n.t(lang, "help.content_ticket"), false);
            case "game" -> eb.addField(i18n.t(lang, "help.category_game"), i18n.t(lang, "help.content_game"), false);
            default -> eb.addField(i18n.t(lang, "help.category_general"), i18n.t(lang, "help.content_general"), false);
        }
        eb.addField(i18n.t(lang, "help.tip_title"), i18n.t(lang, "help.tip_body"), false);
        return eb;
    }

    private OptionData buildWelcomeActionOption(boolean zh) {
        OptionData option = new OptionData(OptionType.STRING, "action",
                zh ? "\u6b61\u8fce\u8a0a\u606f\u64cd\u4f5c" : "Welcome message action", false);
        option.addChoices(
                new Command.Choice(zh ? SUB_GENERIC_ENABLE_ZH : "enable", "enable"),
                new Command.Choice(zh ? SUB_GENERIC_STATUS_ZH : "status", "status")
        );
        if (zh) {
            option.setNameLocalization(DiscordLocale.CHINESE_TAIWAN, "\u9078\u9805");
            option.setNameLocalization(DiscordLocale.CHINESE_CHINA, "\u9009\u9879");
        }
        return option;
    }

    String previewWelcomeText(String text, Guild guild, User user) {
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
        event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
    }

    StringSelectMenu helpMenu(String lang) {
        return StringSelectMenu.create(HELP_SELECT_ID)
                .setPlaceholder(i18n.t(lang, "help.select_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "help.category_general"), "general"),
                        SelectOption.of(i18n.t(lang, "help.category_music"), "music"),
                        SelectOption.of(i18n.t(lang, "help.category_settings"), "settings"),
                        SelectOption.of(i18n.t(lang, "help.category_moderation"), "moderation"),
                        SelectOption.of(i18n.t(lang, "help.category_private_room"), "private-room"),
                        SelectOption.of(i18n.t(lang, "help.category_ticket"), "ticket"),
                        SelectOption.of(i18n.t(lang, "help.category_game"), "game")
                )
                .build();
    }

    List<Button> helpButtonsPrimary(String lang, String selectedCategory) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(categoryButton(lang, "general", selectedCategory, i18n.t(lang, "help.category_general")));
        buttons.add(categoryButton(lang, "music", selectedCategory, i18n.t(lang, "help.category_music")));
        buttons.add(categoryButton(lang, "settings", selectedCategory, i18n.t(lang, "help.category_settings")));
        buttons.add(categoryButton(lang, "moderation", selectedCategory, i18n.t(lang, "help.category_moderation")));
        buttons.add(categoryButton(lang, "private-room", selectedCategory, i18n.t(lang, "help.category_private_room")));
        return buttons;
    }

    List<Button> helpButtonsSecondary(String lang, String selectedCategory) {
        return List.of(
                categoryButton(lang, "game", selectedCategory, i18n.t(lang, "help.category_game")),
                categoryButton(lang, "ticket", selectedCategory, i18n.t(lang, "help.category_ticket"))
        );
    }

    private Button categoryButton(String lang, String category, String selectedCategory, String label) {
        if (category.equals(selectedCategory)) {
            return Button.success(HELP_BUTTON_PREFIX + category, label).asDisabled();
        }
        return Button.secondary(HELP_BUTTON_PREFIX + category, label);
    }

    EmbedBuilder settingsInfoEmbed(Guild guild, String lang, String section) {
        long guildId = guild.getIdLong();
        String currentSection = section == null || section.isBlank() ? "notifications" : section;
        GuildSettingsService.GuildSettings settings = settingsService.getSettings(guildId);
        var n = settings.getNotifications();
        var nDef = runtimeConfig.getDefaultNotifications();
        var logs = settings.getMessageLogs();
        var logsDef = runtimeConfig.getDefaultMessageLogs();
        var music = settings.getMusic();
        var musicDef = runtimeConfig.getDefaultMusic();
        var room = settings.getPrivateRoom();
        var roomDef = runtimeConfig.getDefaultPrivateRoom();
        boolean numberChainEnabled = moderationService.isNumberChainEnabled(guildId);
        Long numberChainChannelId = moderationService.getNumberChainChannelId(guildId);
        long numberChainNext = moderationService.getNumberChainNext(guildId);
        long numberChainHighest = moderationService.getNumberChainHighestNumber(guildId);

        String notifications = joinLines(
                line(lang, "settings.info_key_enabled", compare(lang, moduleSwitchTextCode(lang, n.isEnabled()), moduleSwitchTextCode(lang, nDef.isEnabled()))),
                line(lang, "settings.info_key_member_join_enabled", compare(lang, moduleSwitchTextCode(lang, n.isMemberJoinEnabled()), moduleSwitchTextCode(lang, nDef.isMemberJoinEnabled()))),
                line(lang, "settings.info_key_member_leave_enabled", compare(lang, moduleSwitchTextCode(lang, n.isMemberLeaveEnabled()), moduleSwitchTextCode(lang, nDef.isMemberLeaveEnabled()))),
                line(lang, "settings.info_key_voice_log_enabled", compare(lang, moduleSwitchTextCode(lang, n.isVoiceLogEnabled()), moduleSwitchTextCode(lang, nDef.isVoiceLogEnabled()))),
                line(lang, "settings.info_key_member_channel_mode", compare(lang,
                        formatMemberChannelMode(lang, n.getMemberJoinChannelId(), n.getMemberLeaveChannelId()),
                        formatMemberChannelMode(lang, nDef.getMemberJoinChannelId(), nDef.getMemberLeaveChannelId()))),
                line(lang, "settings.info_key_member_channel", compare(lang,
                        formatTextChannelInfo(guild, n.getMemberChannelId()),
                        formatTextChannelInfo(guild, nDef.getMemberChannelId()))),
                line(lang, "settings.info_key_member_join_channel", compare(lang,
                        formatTextChannelInfo(guild, n.getMemberJoinChannelId()),
                        formatTextChannelInfo(guild, nDef.getMemberJoinChannelId()))),
                line(lang, "settings.info_key_member_leave_channel", compare(lang,
                        formatTextChannelInfo(guild, n.getMemberLeaveChannelId()),
                        formatTextChannelInfo(guild, nDef.getMemberLeaveChannelId()))),
                line(lang, "settings.info_key_voice_channel", compare(lang,
                        formatTextChannelInfo(guild, n.getVoiceChannelId()),
                        formatTextChannelInfo(guild, nDef.getVoiceChannelId())))
        );
        String notificationTemplates = joinInfoBlocks(
                templateCompareMarkdown(lang, "settings.info_key_member_join_template", n.getMemberJoinMessage(), nDef.getMemberJoinMessage()),
                line(lang, "settings.info_key_member_join_color", compare(lang, formatColor(n.getMemberJoinColor()), formatColor(nDef.getMemberJoinColor()))),
                templateCompareMarkdown(lang, "settings.info_key_member_leave_template", n.getMemberLeaveMessage(), nDef.getMemberLeaveMessage()),
                line(lang, "settings.info_key_member_leave_color", compare(lang, formatColor(n.getMemberLeaveColor()), formatColor(nDef.getMemberLeaveColor()))),
                templateCompareMarkdown(lang, "settings.info_key_voice_join_template", n.getVoiceJoinMessage(), nDef.getVoiceJoinMessage()),
                templateCompareMarkdown(lang, "settings.info_key_voice_leave_template", n.getVoiceLeaveMessage(), nDef.getVoiceLeaveMessage()),
                templateCompareMarkdown(lang, "settings.info_key_voice_move_template", n.getVoiceMoveMessage(), nDef.getVoiceMoveMessage())
        );
        String messageLogs = joinLines(
                line(lang, "settings.info_key_enabled", compare(lang, moduleSwitchTextCode(lang, logs.isEnabled()), moduleSwitchTextCode(lang, logsDef.isEnabled()))),
                line(lang, "settings.info_key_log_channel", compare(lang,
                        formatTextChannelInfo(guild, logs.getChannelId()),
                        formatTextChannelInfo(guild, logsDef.getChannelId()))),
                line(lang, "settings.info_key_message_log_channel", compare(lang,
                        formatTextChannelInfo(guild, logs.getMessageLogChannelId()),
                        formatTextChannelInfo(guild, logsDef.getMessageLogChannelId()))),
                line(lang, "settings.info_key_log_role_channel", compare(lang,
                        formatTextChannelInfo(guild, logs.getRoleLogChannelId()),
                        formatTextChannelInfo(guild, logsDef.getRoleLogChannelId()))),
                line(lang, "settings.info_key_log_moderation_channel", compare(lang,
                        formatTextChannelInfo(guild, logs.getModerationLogChannelId()),
                        formatTextChannelInfo(guild, logsDef.getModerationLogChannelId()))),
                line(lang, "settings.info_key_log_command_channel", compare(lang,
                        formatTextChannelInfo(guild, logs.getCommandUsageChannelId()),
                        formatTextChannelInfo(guild, logsDef.getCommandUsageChannelId()))),
                line(lang, "settings.info_key_log_channel_events_channel", compare(lang,
                        formatTextChannelInfo(guild, logs.getChannelLifecycleChannelId()),
                        formatTextChannelInfo(guild, logsDef.getChannelLifecycleChannelId()))),
                line(lang, "settings.info_key_log_role", compare(lang, moduleSwitchTextCode(lang, logs.isRoleLogEnabled()), moduleSwitchTextCode(lang, logsDef.isRoleLogEnabled()))),
                line(lang, "settings.info_key_log_channel_lifecycle", compare(lang, moduleSwitchTextCode(lang, logs.isChannelLifecycleLogEnabled()), moduleSwitchTextCode(lang, logsDef.isChannelLifecycleLogEnabled()))),
                line(lang, "settings.info_key_log_moderation", compare(lang, moduleSwitchTextCode(lang, logs.isModerationLogEnabled()), moduleSwitchTextCode(lang, logsDef.isModerationLogEnabled()))),
                line(lang, "settings.info_key_log_command_usage", compare(lang, moduleSwitchTextCode(lang, logs.isCommandUsageLogEnabled()), moduleSwitchTextCode(lang, logsDef.isCommandUsageLogEnabled()))),
                line(lang, "settings.info_key_log_ignored_members", compare(lang,
                        formatIgnoredMembersInfo(lang, logs.getIgnoredMemberIds()),
                        formatIgnoredMembersInfo(lang, logsDef.getIgnoredMemberIds()))),
                lineLabel("\uD83C\uDFF7\uFE0F", ignoredRolesInfoLabel(lang), compare(lang,
                        formatIgnoredRolesInfo(lang, logs.getIgnoredRoleIds()),
                        formatIgnoredRolesInfo(lang, logsDef.getIgnoredRoleIds()))),
                line(lang, "settings.info_key_log_ignored_channels", compare(lang,
                        formatIgnoredChannelsInfo(lang, logs.getIgnoredChannelIds()),
                        formatIgnoredChannelsInfo(lang, logsDef.getIgnoredChannelIds()))),
                line(lang, "settings.info_key_log_ignored_prefixes", compare(lang,
                        formatIgnoredPrefixesInfo(lang, logs.getIgnoredPrefixes()),
                        formatIgnoredPrefixesInfo(lang, logsDef.getIgnoredPrefixes())))
        );
        String musicInfo = joinLines(
                line(lang, "settings.info_key_auto_leave_enabled", compare(lang, moduleSwitchTextCode(lang, music.isAutoLeaveEnabled()), moduleSwitchTextCode(lang, musicDef.isAutoLeaveEnabled()))),
                line(lang, "settings.info_key_auto_leave_minutes", compare(lang, String.valueOf(music.getAutoLeaveMinutes()), String.valueOf(musicDef.getAutoLeaveMinutes()))),
                line(lang, "settings.info_key_autoplay_enabled", compare(lang, moduleSwitchTextCode(lang, isAutoplayEnabled(guildId)), moduleSwitchTextCode(lang, true))),
                line(lang, "settings.info_key_default_repeat_mode", compare(lang, music.getDefaultRepeatMode().name(), musicDef.getDefaultRepeatMode().name())),
                line(lang, "settings.info_key_music_command_channel", compare(lang,
                        formatTextChannelInfo(guild, music.getCommandChannelId()),
                        formatTextChannelInfo(guild, musicDef.getCommandChannelId())))
        );
        String privateRoom = joinLines(
                line(lang, "settings.info_key_enabled", compare(lang, moduleSwitchTextCode(lang, room.isEnabled()), moduleSwitchTextCode(lang, roomDef.isEnabled()))),
                line(lang, "settings.info_key_trigger_channel", compare(lang,
                        formatVoiceChannelInfo(guild, room.getTriggerVoiceChannelId()),
                        formatVoiceChannelInfo(guild, roomDef.getTriggerVoiceChannelId()))),
                line(lang, "settings.info_key_category_auto", compare(lang,
                        resolveTriggerCategoryWithSource(guild, room.getTriggerVoiceChannelId()),
                        resolveTriggerCategoryWithSource(guild, roomDef.getTriggerVoiceChannelId()))),
                line(lang, "settings.info_key_user_limit", compare(lang, String.valueOf(room.getUserLimit()), String.valueOf(roomDef.getUserLimit())))
        );
        String numberChainInfo = joinLines(
                line(lang, "settings.info_key_number_chain_enabled", compare(lang, moduleSwitchTextCode(lang, numberChainEnabled), moduleSwitchTextCode(lang, false))),
                line(lang, "settings.info_key_number_chain_channel", compare(lang,
                        formatTextChannelInfo(guild, numberChainChannelId),
                        formatTextChannelInfo(guild, null))),
                line(lang, "settings.info_key_number_chain_next", compare(lang, String.valueOf(numberChainNext), "1")),
                lineLabel("\uD83C\uDFC6", numberChainHighestLabel(lang), String.valueOf(numberChainHighest)),
                lineLabel("\uD83D\uDC65", numberChainTopContributorsLabel(lang), formatNumberChainTopContributors(guild, lang))
        );
        String moduleInfo = joinLines(
                "**" + i18n.t(lang, "settings.module_section_core") + "**",
                moduleLine(lang, "settings.key_notifications_enabled", n.isEnabled()),
                moduleLine(lang, "settings.key_messageLogs_enabled", logs.isEnabled()),
                moduleLine(lang, "settings.key_welcome_enabled", settings.getWelcome().isEnabled()),
                "",
                "**" + i18n.t(lang, "settings.module_section_notifications") + "**",
                moduleLine(lang, "settings.key_notifications_memberJoinEnabled", n.isMemberJoinEnabled()),
                moduleLine(lang, "settings.key_notifications_memberLeaveEnabled", n.isMemberLeaveEnabled()),
                moduleLine(lang, "settings.key_notifications_voiceLogEnabled", n.isVoiceLogEnabled()),
                "",
                "**" + i18n.t(lang, "settings.module_section_logs") + "**",
                moduleLine(lang, "settings.info_key_log_command_usage", logs.isCommandUsageLogEnabled()),
                moduleLine(lang, "settings.info_key_log_channel_lifecycle", logs.isChannelLifecycleLogEnabled()),
                moduleLine(lang, "settings.info_key_log_role", logs.isRoleLogEnabled()),
                moduleLine(lang, "settings.info_key_log_moderation", logs.isModerationLogEnabled()),
                "",
                "**" + i18n.t(lang, "settings.module_section_music_others") + "**",
                moduleLine(lang, "settings.key_music_autoLeaveEnabled", music.isAutoLeaveEnabled()),
                moduleLine(lang, "settings.key_music_autoplayEnabled", music.isAutoplayEnabled()),
                moduleLine(lang, "settings.key_numberChain_enabled", moderationService.isNumberChainEnabled(guildId)),
                moduleLine(lang, "settings.key_ticket_enabled", settings.getTicket().isEnabled()),
                line(lang, "settings.key_ticket_maxOpenPerUser", String.valueOf(settings.getTicket().getMaxOpenPerUser())),
                line(lang, "settings.key_ticket_blacklistUserIds", String.valueOf(settings.getTicket().getBlacklistedUserIds().size())),
                moduleLine(lang, "settings.key_privateRoom_enabled", room.isEnabled())
        );

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(26, 188, 156))
                .setTitle("\u2699\uFE0F " + i18n.t(lang, "settings.info_title"))
                .setDescription(i18n.t(lang, "settings.info_desc") + "\n`" + guild.getName() + "`")
                .setTimestamp(Instant.now());

        switch (currentSection) {
            case "notifications" -> eb.addField(infoSectionTitle(lang, "settings.info_notifications"), notifications, false);
            case "templates" -> eb.addField(infoSectionTitle(lang, "settings.info_notification_templates"), notificationTemplates, false);
            case "logs" -> eb.addField(infoSectionTitle(lang, "settings.info_message_logs"), messageLogs, false);
            case "music" -> eb.addField(infoSectionTitle(lang, "settings.info_music"), musicInfo, false);
            case "private-room" -> eb.addField(infoSectionTitle(lang, "settings.info_private_room"), privateRoom, false);
            case "number-chain" -> eb.addField(infoSectionTitle(lang, "settings.info_number_chain"), numberChainInfo, false);
            case "module" -> eb.addField(infoSectionTitle(lang, "settings.info_module"), moduleInfo, false);
            default -> eb.addField(infoSectionTitle(lang, "settings.info_notifications"), notifications, false);
        }
        return eb;
    }

    StringSelectMenu settingsInfoMenu(String lang, String selected) {
        String current = selected == null || selected.isBlank() ? "notifications" : selected;
        return StringSelectMenu.create(SETTINGS_INFO_SELECT_ID)
                .setPlaceholder(i18n.t(lang, "settings.info_select_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.info_notifications"), "notifications").withDefault("notifications".equals(current)),
                        SelectOption.of(i18n.t(lang, "settings.info_notification_templates"), "templates").withDefault("templates".equals(current)),
                        SelectOption.of(i18n.t(lang, "settings.info_message_logs"), "logs").withDefault("logs".equals(current)),
                        SelectOption.of(i18n.t(lang, "settings.info_music"), "music").withDefault("music".equals(current)),
                        SelectOption.of(i18n.t(lang, "settings.info_private_room"), "private-room").withDefault("private-room".equals(current)),
                        SelectOption.of(i18n.t(lang, "settings.info_number_chain"), "number-chain").withDefault("number-chain".equals(current)),
                        SelectOption.of(i18n.t(lang, "settings.info_module"), "module").withDefault("module".equals(current))
                )
                .build();
    }

    List<Button> settingsInfoButtons(String lang, String selected, int rowIndex) {
        String current = selected == null || selected.isBlank() ? "notifications" : selected;
        List<Button> row0 = List.of(
                infoSectionButton(lang, "notifications", current, "settings.info_notifications"),
                infoSectionButton(lang, "templates", current, "settings.info_notification_templates"),
                infoSectionButton(lang, "logs", current, "settings.info_message_logs"),
                infoSectionButton(lang, "music", current, "settings.info_music")
        );
        List<Button> row1 = List.of(
                infoSectionButton(lang, "private-room", current, "settings.info_private_room"),
                infoSectionButton(lang, "number-chain", current, "settings.info_number_chain"),
                infoSectionButton(lang, "module", current, "settings.info_module")
        );
        return rowIndex == 0 ? row0 : row1;
    }

    private Button infoSectionButton(String lang, String value, String current, String labelKey) {
        String id = SETTINGS_INFO_BUTTON_PREFIX + value;
        String label = i18n.t(lang, labelKey);
        if (value.equals(current)) {
            return Button.primary(id, safe(label, 80)).asDisabled();
        }
        return Button.secondary(id, safe(label, 80));
    }

    void openSettingsResetMenu(SlashCommandInteractionEvent event, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        resetRequests.put(token, new ResetRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(230, 126, 34))
                        .setTitle(i18n.t(lang, "settings.reset_title"))
                        .setDescription(i18n.t(lang, "settings.reset_desc"))
                        .build())
                .addComponents(ActionRow.of(settingsResetMenu(token, lang)))
                .setEphemeral(true)
                .queue();
    }

    private StringSelectMenu settingsResetMenu(String token, String lang) {
        return StringSelectMenu.create(SETTINGS_RESET_SELECT_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "settings.reset_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.reset_option_language"), "language"),
                        SelectOption.of(i18n.t(lang, "settings.reset_option_notifications"), "notifications"),
                        SelectOption.of(i18n.t(lang, "settings.reset_option_message_logs"), "message-logs"),
                        SelectOption.of(i18n.t(lang, "settings.reset_option_music"), "music"),
                        SelectOption.of(i18n.t(lang, "settings.reset_option_private_room"), "private-room"),
                        SelectOption.of(i18n.t(lang, "settings.reset_option_ticket"), "ticket"),
                        SelectOption.of(i18n.t(lang, "settings.reset_option_number_chain"), "number-chain"),
                        SelectOption.of(i18n.t(lang, "settings.reset_option_all"), "all")
                )
                .build();
    }

    void handleSettingsResetSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_RESET_SELECT_PREFIX.length());
        ResetRequest request = resetRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            resetRequests.remove(token);
            event.reply(i18n.t(lang, "settings.reset_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        String selection = event.getValues().isEmpty() ? "all" : event.getValues().get(0);
        resetRequests.remove(token);
        if (!isResetSelection(selection)) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }

        String confirmToken = UUID.randomUUID().toString().replace("-", "");
        resetConfirmRequests.put(confirmToken, new ResetConfirmRequest(
                request.requestUserId,
                request.guildId,
                selection,
                Instant.now().plusSeconds(120)
        ));
        String target = i18n.t(lang, "settings.reset_target_" + selection);
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(231, 76, 60))
                        .setTitle(i18n.t(lang, "settings.reset_confirm_title"))
                        .setDescription(i18n.t(lang, "settings.reset_confirm_desc", Map.of("target", target)))
                        .build())
                .setComponents(ActionRow.of(
                        Button.danger(SETTINGS_RESET_CONFIRM_PREFIX + confirmToken, i18n.t(lang, "settings.reset_confirm_button")),
                        Button.secondary(SETTINGS_RESET_CANCEL_PREFIX + confirmToken, i18n.t(lang, "settings.reset_cancel_button"))
                ))
                .queue();
    }

    void handleSettingsResetConfirmButtons(ButtonInteractionEvent event, String lang) {
        String id = event.getComponentId();
        String token = id.substring(id.lastIndexOf(':') + 1);
        ResetConfirmRequest request = resetConfirmRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            resetConfirmRequests.remove(token);
            event.reply(i18n.t(lang, "settings.reset_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }

        if (id.startsWith(SETTINGS_RESET_CANCEL_PREFIX)) {
            resetConfirmRequests.remove(token);
            event.editMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(149, 165, 166))
                            .setTitle(i18n.t(lang, "settings.reset_title"))
                            .setDescription(i18n.t(lang, "settings.reset_cancelled"))
                            .build())
                    .setComponents(List.of())
                    .queue();
            return;
        }

        if (!applyResetSelection(request.guildId, request.selection)) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        resetConfirmRequests.remove(token);
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(i18n.t(lang, "settings.reset_title"))
                        .setDescription(i18n.t(lang, "settings.reset_done",
                                Map.of("target", i18n.t(lang, "settings.reset_target_" + request.selection))))
                        .build())
                .setComponents(List.of())
                .queue();
    }

    private boolean isResetSelection(String selection) {
        return "language".equals(selection)
                || "notifications".equals(selection)
                || "message-logs".equals(selection)
                || "music".equals(selection)
                || "private-room".equals(selection)
                || "ticket".equals(selection)
                || "number-chain".equals(selection)
                || "all".equals(selection);
    }

    private boolean applyResetSelection(long guildId, String selection) {
        switch (selection) {
            case "language" -> settingsService.updateSettings(guildId, s -> s.withLanguage(runtimeConfig.getDefaultLanguage()));
            case "notifications" -> settingsService.updateSettings(guildId, s -> s.withNotifications(runtimeConfig.getDefaultNotifications()));
            case "message-logs" -> settingsService.updateSettings(guildId, s -> s.withMessageLogs(runtimeConfig.getDefaultMessageLogs()));
            case "music" -> settingsService.updateSettings(guildId, s -> s.withMusic(runtimeConfig.getDefaultMusic()));
            case "private-room" -> settingsService.updateSettings(guildId, s -> s.withPrivateRoom(runtimeConfig.getDefaultPrivateRoom()));
            case "ticket" -> settingsService.updateSettings(guildId, s -> s.withTicket(runtimeConfig.getDefaultTicket()));
            case "number-chain" -> resetNumberChainSettings(guildId);
            case "all" -> {
                settingsService.updateSettings(guildId, s -> new GuildSettingsService.GuildSettings(
                        runtimeConfig.getDefaultLanguage(),
                        runtimeConfig.getDefaultNotifications(),
                        runtimeConfig.getDefaultWelcome(),
                        runtimeConfig.getDefaultMessageLogs(),
                        runtimeConfig.getDefaultMusic(),
                        runtimeConfig.getDefaultPrivateRoom(),
                        runtimeConfig.getDefaultTicket()
                ));
                resetNumberChainSettings(guildId);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void resetNumberChainSettings(long guildId) {
        moderationService.setNumberChainEnabled(guildId, false);
        moderationService.setNumberChainChannelId(guildId, null);
        moderationService.resetNumberChain(guildId);
    }
    public void handlePrivateRoomSettingsCommand(SlashCommandInteractionEvent event, String lang) {
        Member member = event.getMember();
        if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
            event.reply(i18n.t(lang, "room_settings.must_join_private_room")).setEphemeral(true).queue();
            return;
        }
        AudioChannel current = member.getVoiceState().getChannel();
        if (!(current instanceof VoiceChannel voiceChannel)
                || !isUserOwnedPrivateRoom(event.getGuild(), voiceChannel, member.getIdLong())) {
            event.reply(i18n.t(lang, "room_settings.must_join_private_room")).setEphemeral(true).queue();
            return;
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        roomSettingRequests.put(token, new RoomSettingsRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                voiceChannel.getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(privateRoomSettingsEmbed(voiceChannel, lang).build())
                .addComponents(ActionRow.of(privateRoomSettingsMenu(token, lang)))
                .setEphemeral(true)
                .queue();
    }

    private StringSelectMenu privateRoomSettingsMenu(String token, String lang) {
        return StringSelectMenu.create(ROOM_SETTINGS_MENU_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "room_settings.select_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "room_settings.option_lock"), "lock"),
                        SelectOption.of(i18n.t(lang, "room_settings.option_limit"), "limit"),
                        SelectOption.of(i18n.t(lang, "room_settings.option_rename"), "rename"),
                        SelectOption.of(i18n.t(lang, "room_settings.option_transfer"), "transfer")
                )
                .build();
    }

    private EmbedBuilder privateRoomSettingsEmbed(VoiceChannel room, String lang) {
        boolean locked = isRoomLocked(room);
        Long ownerId = PrivateRoomListener.getRoomOwnerId(room.getGuild().getIdLong(), room.getIdLong());
        Member owner = ownerId == null ? null : room.getGuild().getMemberById(ownerId);
        String ownerText = owner == null ? i18n.t(lang, "room_settings.owner_unknown") : owner.getAsMention();
        return new EmbedBuilder()
                .setColor(new Color(155, 89, 182))
                .setTitle(i18n.t(lang, "room_settings.title"))
                .setDescription(i18n.t(lang, "room_settings.desc"))
                .addField(i18n.t(lang, "room_settings.field_channel"), room.getAsMention(), true)
                .addField(i18n.t(lang, "room_settings.field_name"), room.getName(), true)
                .addField(i18n.t(lang, "room_settings.field_limit"), room.getUserLimit() <= 0 ? i18n.t(lang, "room_settings.unlimited") : String.valueOf(room.getUserLimit()), true)
                .addField(i18n.t(lang, "room_settings.field_lock"), locked ? i18n.t(lang, "settings.info_bool_on") : i18n.t(lang, "settings.info_bool_off"), true)
                .addField(i18n.t(lang, "room_settings.field_owner"), ownerText, true);
    }
    public void handleRoomSettingsSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(ROOM_SETTINGS_MENU_PREFIX.length());
        RoomSettingsRequest request = roomSettingRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            roomSettingRequests.remove(token);
            event.reply(i18n.t(lang, "room_settings.expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }

        VoiceChannel room = event.getGuild().getVoiceChannelById(request.roomChannelId);
        if (room == null || !isUserOwnedPrivateRoom(event.getGuild(), room, request.requestUserId)) {
            roomSettingRequests.remove(token);
            event.reply(i18n.t(lang, "room_settings.room_not_found")).setEphemeral(true).queue();
            return;
        }

        String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        switch (action) {
            case "lock" -> {
                String missing = formatMissingPermissions(event.getGuild().getSelfMember(), room,
                        Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS);
                if (!"-".equals(missing)) {
                    event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", missing)))
                            .setEphemeral(true)
                            .queue();
                    return;
                }
                boolean currentlyLocked = isRoomLocked(room);
                var overrideAction = room.upsertPermissionOverride(event.getGuild().getPublicRole());
                if (currentlyLocked) {
                    overrideAction.clear(Permission.VOICE_CONNECT, Permission.VIEW_CHANNEL);
                    overrideAction.grant(Permission.VOICE_CONNECT, Permission.VIEW_CHANNEL);
                } else {
                    overrideAction.deny(Permission.VOICE_CONNECT, Permission.VIEW_CHANNEL);
                }
                overrideAction
                        .queue(success -> event.editMessageEmbeds(privateRoomSettingsEmbed(room, lang).build())
                                        .setComponents(ActionRow.of(privateRoomSettingsMenu(token, lang)))
                                        .queue(),
                                error -> event.reply(i18n.t(lang, "room_settings.action_failed")).setEphemeral(true).queue());
            }
            case "limit" -> openRoomLimitModal(event, token, room, lang);
            case "rename" -> openRoomRenameModal(event, token, room, lang);
            case "transfer" -> openRoomTransferMenu(event, token, room, lang);
            default -> event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }

    private void openRoomTransferMenu(StringSelectInteractionEvent event, String token, VoiceChannel room, String lang) {
        EntitySelectMenu memberMenu = EntitySelectMenu.create(ROOM_TRANSFER_SELECT_PREFIX + token, EntitySelectMenu.SelectTarget.USER)
                .setPlaceholder(i18n.t(lang, "room_settings.transfer_placeholder"))
                .setRequiredRange(1, 1)
                .build();
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle(i18n.t(lang, "room_settings.transfer_title"))
                        .setDescription(i18n.t(lang, "room_settings.transfer_desc", Map.of("channel", room.getAsMention())))
                        .build())
                .setComponents(ActionRow.of(memberMenu))
                .queue();
    }

    private void openRoomLimitModal(StringSelectInteractionEvent event, String token, VoiceChannel room, String lang) {
        TextInput input = TextInput.create("limit", TextInputStyle.SHORT)
                .setPlaceholder(i18n.t(lang, "room_settings.limit_placeholder"))
                .setRequired(false)
                .setMaxLength(2)
                .build();
        Modal modal = Modal.create(ROOM_LIMIT_MODAL_PREFIX + token, i18n.t(lang, "room_settings.limit_title"))
                .addComponents(Label.of(i18n.t(lang, "room_settings.limit_label"), input))
                .build();
        event.replyModal(modal).queue();
    }

    private void openRoomRenameModal(StringSelectInteractionEvent event, String token, VoiceChannel room, String lang) {
        TextInput input = TextInput.create("name", TextInputStyle.SHORT)
                .setPlaceholder(i18n.t(lang, "room_settings.rename_placeholder", Map.of("name", room.getName())))
                .setRequired(true)
                .setMinLength(1)
                .setMaxLength(10)
                .build();
        Modal modal = Modal.create(ROOM_RENAME_MODAL_PREFIX + token, i18n.t(lang, "room_settings.rename_title"))
                .addComponents(Label.of(i18n.t(lang, "room_settings.rename_label"), input))
                .build();
        event.replyModal(modal).queue();
    }
    public void handleRoomSettingsModal(ModalInteractionEvent event) {
        String lang = lang(event.getGuild().getIdLong());
        String modalId = event.getModalId();
        boolean isLimit = modalId.startsWith(ROOM_LIMIT_MODAL_PREFIX);
        String token = modalId.substring((isLimit ? ROOM_LIMIT_MODAL_PREFIX : ROOM_RENAME_MODAL_PREFIX).length());
        RoomSettingsRequest request = roomSettingRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            roomSettingRequests.remove(token);
            event.reply(i18n.t(lang, "room_settings.expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }

        VoiceChannel room = event.getGuild().getVoiceChannelById(request.roomChannelId);
        if (room == null || !isUserOwnedPrivateRoom(event.getGuild(), room, request.requestUserId)) {
            roomSettingRequests.remove(token);
            event.reply(i18n.t(lang, "room_settings.room_not_found")).setEphemeral(true).queue();
            return;
        }

        String missing = formatMissingPermissions(event.getGuild().getSelfMember(), room, Permission.MANAGE_CHANNEL);
        if (!"-".equals(missing)) {
            event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", missing)))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        if (isLimit) {
            String raw = event.getValue("limit") == null ? "" : event.getValue("limit").getAsString().trim();
            int limit;
            if (raw.isBlank()) {
                limit = 0;
            } else {
                try {
                    limit = Integer.parseInt(raw);
                } catch (NumberFormatException e) {
                    event.reply(i18n.t(lang, "room_settings.limit_invalid")).setEphemeral(true).queue();
                    return;
                }
                if (limit < 1 || limit > 99) {
                    event.reply(i18n.t(lang, "room_settings.limit_invalid")).setEphemeral(true).queue();
                    return;
                }
            }
            room.getManager().setUserLimit(limit).queue(
                    success -> {
                        roomSettingRequests.put(token, new RoomSettingsRequest(
                                request.requestUserId,
                                request.guildId,
                                request.roomChannelId,
                                Instant.now().plusSeconds(120)
                        ));
                        event.replyEmbeds(privateRoomSettingsEmbed(room, lang).build())
                                .addComponents(ActionRow.of(privateRoomSettingsMenu(token, lang)))
                                .setEphemeral(true)
                                .queue();
                    },
                    error -> event.reply(i18n.t(lang, "room_settings.action_failed")).setEphemeral(true).queue()
            );
            return;
        }

        String name = event.getValue("name") == null ? "" : event.getValue("name").getAsString().trim();
        if (name.isBlank() || name.length() > 10) {
            event.reply(i18n.t(lang, "room_settings.rename_invalid")).setEphemeral(true).queue();
            return;
        }
        room.getManager().setName(name).queue(
                success -> {
                    roomSettingRequests.put(token, new RoomSettingsRequest(
                            request.requestUserId,
                            request.guildId,
                            request.roomChannelId,
                            Instant.now().plusSeconds(120)
                    ));
                    event.replyEmbeds(privateRoomSettingsEmbed(room, lang).build())
                            .addComponents(ActionRow.of(privateRoomSettingsMenu(token, lang)))
                            .setEphemeral(true)
                            .queue();
                },
                error -> event.reply(i18n.t(lang, "room_settings.action_failed")).setEphemeral(true).queue()
        );
    }
    public void handleRoomTransferSelect(EntitySelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(ROOM_TRANSFER_SELECT_PREFIX.length());
        RoomSettingsRequest request = roomSettingRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            roomSettingRequests.remove(token);
            event.reply(i18n.t(lang, "room_settings.expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }

        VoiceChannel room = event.getGuild().getVoiceChannelById(request.roomChannelId);
        if (room == null || !isUserOwnedPrivateRoom(event.getGuild(), room, request.requestUserId)) {
            roomSettingRequests.remove(token);
            event.reply(i18n.t(lang, "room_settings.room_not_found")).setEphemeral(true).queue();
            return;
        }

        String missing = formatMissingPermissions(event.getGuild().getSelfMember(), room,
                Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS);
        if (!"-".equals(missing)) {
            event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", missing)))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        List<Member> members = event.getMentions().getMembers();
        Member target = members.isEmpty() ? null : members.get(0);
        if (target == null
                || target.getUser().isBot()
                || target.getIdLong() == request.requestUserId
                || target.getVoiceState() == null
                || target.getVoiceState().getChannel() == null
                || target.getVoiceState().getChannel().getIdLong() != room.getIdLong()) {
            event.reply(i18n.t(lang, "room_settings.transfer_invalid")).setEphemeral(true).queue();
            return;
        }

        long oldOwnerId = request.requestUserId;
        room.upsertPermissionOverride(target)
                .grant(Permission.getPermissions(PrivateRoomListener.getRoomOwnerPermissionRaw()))
                .queue(success -> removeOldRoomOwnerOverride(room, oldOwnerId, () -> {
                            PrivateRoomListener.setRoomOwner(event.getGuild().getIdLong(), room.getIdLong(), target.getIdLong());
                            roomSettingRequests.remove(token);
                            event.editMessageEmbeds(new EmbedBuilder()
                                            .setColor(new Color(46, 204, 113))
                                            .setTitle(i18n.t(lang, "room_settings.title"))
                                            .setDescription(i18n.t(lang, "room_settings.transfer_success", Map.of("user", target.getAsMention())))
                                            .build())
                                    .setComponents(List.of())
                                    .queue();
                        },
                        () -> event.reply(i18n.t(lang, "room_settings.action_failed")).setEphemeral(true).queue()),
                        error -> event.reply(i18n.t(lang, "room_settings.action_failed")).setEphemeral(true).queue());
    }

    private void removeOldRoomOwnerOverride(VoiceChannel room, long oldOwnerId, Runnable onSuccess, Runnable onError) {
        var oldOverride = room.getMemberPermissionOverrides().stream()
                .filter(override -> override.getIdLong() == oldOwnerId)
                .findFirst()
                .orElse(null);
        if (oldOverride == null) {
            onSuccess.run();
            return;
        }
        oldOverride.delete().queue(success -> onSuccess.run(), error -> onError.run());
    }

    private boolean isRoomLocked(VoiceChannel room) {
        var override = room.getPermissionOverride(room.getGuild().getPublicRole());
        return override != null && (override.getDenied().contains(Permission.VOICE_CONNECT)
                || override.getDenied().contains(Permission.VIEW_CHANNEL));
    }

    private boolean isUserOwnedPrivateRoom(Guild guild, VoiceChannel room, long userId) {
        if (PrivateRoomListener.isManagedPrivateRoom(guild.getIdLong(), room.getIdLong())
                && PrivateRoomListener.isRoomOwner(guild.getIdLong(), room.getIdLong(), userId)) {
            return true;
        }
        var override = room.getMemberPermissionOverrides().stream()
                .filter(o -> o.getIdLong() == userId)
                .findFirst()
                .orElse(null);
        if (override == null) {
            return false;
        }
        var allowed = override.getAllowed();
        return allowed.contains(Permission.MANAGE_CHANNEL)
                && allowed.contains(Permission.VOICE_MOVE_OTHERS)
                && allowed.contains(Permission.VOICE_MUTE_OTHERS);
    }

    private Modal buildTemplateModal(
            String templateType,
            String placeholders,
            boolean includeColor,
            Integer currentColor,
            String lang
    ) {
        TextInput input = TextInput.create("template", TextInputStyle.PARAGRAPH)
                .setPlaceholder(placeholders)
                .setRequired(true)
                .setMaxLength(1000)
                .build();

        Modal.Builder modalBuilder = Modal.create(TEMPLATE_MODAL_PREFIX + templateType, i18n.t(lang, "settings.template_modal_title"))
                .addComponents(Label.of(i18n.t(lang, "settings.template_modal_label"), input));
        if (includeColor) {
            String placeholder = currentColor == null
                    ? "#00FF00"
                    : String.format("#%06X", currentColor & 0xFFFFFF);
            TextInput colorInput = TextInput.create("color", TextInputStyle.SHORT)
                    .setPlaceholder(placeholder)
                    .setRequired(false)
                    .setMinLength(3)
                    .setMaxLength(9)
                    .build();
            modalBuilder.addComponents(Label.of(i18n.t(lang, "settings.template_modal_color_label"), colorInput));
        }
        return modalBuilder.build();
    }

    String applyTemplate(long guildId, String templateType, String template, Integer color, String lang) {
        switch (templateType) {
            case "member-join" -> {
                settingsService.updateSettings(guildId, s -> {
                    var notifications = s.getNotifications().withMemberJoinMessage(template);
                    if (color != null) {
                        notifications = notifications.withMemberJoinColor(color);
                    }
                    return s.withNotifications(notifications);
                });
                return i18n.t(lang, "settings.info_key_member_join_template");
            }
            case "member-leave" -> {
                settingsService.updateSettings(guildId, s -> {
                    var notifications = s.getNotifications().withMemberLeaveMessage(template);
                    if (color != null) {
                        notifications = notifications.withMemberLeaveColor(color);
                    }
                    return s.withNotifications(notifications);
                });
                return i18n.t(lang, "settings.info_key_member_leave_template");
            }
            case "voice-join" -> {
                settingsService.updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withVoiceJoinMessage(template)));
                return i18n.t(lang, "settings.info_key_voice_join_template");
            }
            case "voice-leave" -> {
                settingsService.updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withVoiceLeaveMessage(template)));
                return i18n.t(lang, "settings.info_key_voice_leave_template");
            }
            case "voice-move" -> {
                settingsService.updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withVoiceMoveMessage(template)));
                return i18n.t(lang, "settings.info_key_voice_move_template");
            }
            default -> {
                return null;
            }
        }
    }

    String renderTemplatePreview(String template, String guildName) {
        return template
                .replace("{user}", "@NoRuleUser (ID: 123456789012345678)")
                .replace("{username}", "NoRuleUser")
                .replace("{guild}", guildName)
                .replace("{id}", "123456789012345678")
                .replace("{tag}", "NoRuleUser#0001")
                .replace("{isBot}", "false")
                .replace("{createdAt}", "2024-01-01 12:00:00 UTC")
                .replace("{accountAgeDays}", "999")
                .replace("{channel}", "General Voice (ID: 234567890123456789)")
                .replace("{from}", "Lobby (ID: 345678901234567890)")
                .replace("{to}", "Gaming (ID: 456789012345678901)");
    }

    void createPanelMessageWithFeedback(Guild guild, TextChannel channel, String lang, Runnable onSuccess, java.util.function.Consumer<String> onError) {
        String missing = formatMissingPermissions(guild.getSelfMember(), channel,
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
        if (!"-".equals(missing)) {
            onError.accept(i18n.t(lang, "general.missing_permissions", Map.of("permissions", missing)));
            return;
        }
        EmbedBuilder panel = panelEmbed(guild, lang);
        try {
            channel.sendMessageEmbeds(panel.build())
                    .setComponents(panelRows(lang, guild.getIdLong()))
                    .queue(message -> {
                        panelByGuild.put(guild.getIdLong(), new PanelRef(channel.getIdLong(), message.getIdLong()));
                        panelLastSignature.put(guild.getIdLong(), panelSignature(guild));
                        panelLastRefreshAt.put(guild.getIdLong(), System.currentTimeMillis());
                        musicService.setGuildStateListener(guild.getIdLong(), () -> refreshPanel(guild.getIdLong()));
                        onSuccess.run();
                    }, error -> onError.accept(error.getMessage()));
        } catch (Exception e) {
            onError.accept(e.getMessage() == null ? "unknown error" : e.getMessage());
        }
    }

    void refreshPanel(long guildId) {
        refreshPanelInternal(guildId, true);
    }

    private void refreshPanelPeriodic(long guildId) {
        refreshPanelInternal(guildId, false);
    }

    private void refreshPanelInternal(long guildId, boolean force) {
        PanelRef ref = panelByGuild.get(guildId);
        if (ref == null || jda == null) {
            return;
        }
        if (!panelRefreshingGuilds.add(guildId)) {
            return;
        }
        try {
            if (!force) {
                if (musicService.getCurrentTitle(jda.getGuildById(guildId)) == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                long last = panelLastRefreshAt.getOrDefault(guildId, 0L);
                if (now - last < PANEL_PERIODIC_REFRESH_MS) {
                    return;
                }
            }
            long now = System.currentTimeMillis();
            long lastRefresh = panelLastRefreshAt.getOrDefault(guildId, 0L);
            if (now - lastRefresh < PANEL_MIN_EDIT_INTERVAL_MS) {
                scheduleDelayedPanelRefresh(guildId, PANEL_MIN_EDIT_INTERVAL_MS - (now - lastRefresh));
                return;
            }
            Guild guild = jda.getGuildById(guildId);
            if (guild == null) {
                panelByGuild.remove(guildId);
                return;
            }
            TextChannel channel = guild.getTextChannelById(ref.channelId);
            if (channel == null) {
                panelByGuild.remove(guildId);
                return;
            }
            String signature = panelSignature(guild);
            if (signature.equals(panelLastSignature.get(guildId))) {
                return;
            }
            String lang = lang(guildId);
            channel.editMessageEmbedsById(ref.messageId, panelEmbed(guild, lang).build())
                    .setComponents(panelRows(lang, guildId))
                    .queue(success -> {
                        panelLastSignature.put(guildId, signature);
                        panelLastRefreshAt.put(guildId, System.currentTimeMillis());
                    }, error -> {
                        panelByGuild.remove(guildId);
                        panelLastSignature.remove(guildId);
                        panelLastRefreshAt.remove(guildId);
                    });
        } finally {
            panelRefreshingGuilds.remove(guildId);
        }
    }

    void refreshPanelMessage(Guild guild, TextChannel channel, long messageId, boolean force) {
        refreshPanelMessage(guild, channel, messageId, force, false);
    }

    void refreshPanelMessage(Guild guild, TextChannel channel, long messageId, boolean force, boolean immediate) {
        long guildId = guild.getIdLong();
        PanelRef active = panelByGuild.get(guildId);
        if (active == null || active.channelId != channel.getIdLong() || active.messageId != messageId) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastRefresh = panelLastRefreshAt.getOrDefault(guildId, 0L);
        if (!immediate && now - lastRefresh < PANEL_MIN_EDIT_INTERVAL_MS) {
            scheduleDelayedPanelRefresh(guildId, PANEL_MIN_EDIT_INTERVAL_MS - (now - lastRefresh));
            return;
        }
        String signature = panelSignature(guild);
        if (!force && signature.equals(panelLastSignature.get(guildId))) {
            return;
        }
        String lang = lang(guild.getIdLong());
        channel.editMessageEmbedsById(messageId, panelEmbed(guild, lang).build())
                .setComponents(panelRows(lang, guild.getIdLong()))
                .queue(success -> {
                    panelLastSignature.put(guildId, signature);
                    panelLastRefreshAt.put(guildId, System.currentTimeMillis());
                }, error -> {
                    PanelRef ref = panelByGuild.get(guild.getIdLong());
                    if (ref != null && ref.messageId == messageId) {
                        panelByGuild.remove(guild.getIdLong());
                        panelLastSignature.remove(guildId);
                        panelLastRefreshAt.remove(guildId);
                    }
                });
    }

    private void scheduleDelayedPanelRefresh(long guildId, long delayMs) {
        if (delayMs <= 0L) {
            scheduler.execute(() -> refreshPanelInternal(guildId, true));
            return;
        }
        ScheduledFuture<?> existing = delayedPanelRefreshByGuild.get(guildId);
        if (existing != null && !existing.isDone()) {
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            delayedPanelRefreshByGuild.remove(guildId);
            refreshPanelInternal(guildId, true);
        }, delayMs, TimeUnit.MILLISECONDS);
        delayedPanelRefreshByGuild.put(guildId, future);
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

    private EmbedBuilder panelEmbed(Guild guild, String lang) {
        long guildId = guild.getIdLong();
        String current = musicService.getCurrentTitle(guild);
        String author = current == null ? musicUx(lang, "panel_none") : safe(musicService.getCurrentAuthor(guild), 80);
        long duration = musicService.getCurrentDurationMillis(guild);
        long position = musicService.getCurrentPositionMillis(guild);
        String progress = current == null ? musicUx(lang, "panel_none") : buildProgressBar(position, duration);
        String requester = current == null ? musicUx(lang, "panel_none") : musicService.getCurrentRequesterDisplay(guild);
        String artwork = musicService.getCurrentArtworkUrl(guild);
        String state = current == null ? musicUx(lang, "panel_idle") : (musicService.isPaused(guild)
                ? musicUx(lang, "panel_paused") : musicUx(lang, "panel_playing"));
        List<AudioTrack> queue = musicService.getQueueSnapshot(guild);
        String queueText = queue.isEmpty() ? musicUx(lang, "panel_none") : formatQueue(queue);
        String connected = guild.getAudioManager().getConnectedChannel() == null
                ? musicUx(lang, "panel_none")
                : "<#" + guild.getAudioManager().getConnectedChannel().getId() + ">";
        String source = musicService.getCurrentSource(guild);
        if (source == null || source.isBlank()) {
            source = musicUx(lang, "panel_none");
        }
        String autoplayState = isAutoplayEnabled(guildId) ? musicUx(lang, "autoplay_on") : musicUx(lang, "autoplay_off");
        String autoplayNotice = musicService.getAutoplayNotice(guildId);
        String currentText = current == null ? musicUx(lang, "panel_none") : ("`" + current + "`");
        String summaryLine = "\u25B6 **" + musicUx(lang, "panel_state") + "**: " + state
                + "  |  \uD83D\uDD01 **" + musicUx(lang, "panel_repeat") + "**: " + mapRepeatLabel(lang, musicService.getRepeatMode(guild))
                + "\n\uD83D\uDD0A **" + musicUx(lang, "panel_channel") + "**: " + connected
                + "  |  \uD83D\uDCCB **" + musicUx(lang, "panel_queue") + "**: `" + queue.size() + "`"
                + "  |  \uD83D\uDD09 **" + musicUx(lang, "panel_volume") + "**: `" + musicService.getVolume(guild) + "%`"
                + "\n\uD83E\uDDE0 **" + musicUx(lang, "panel_autoplay") + "**: " + autoplayState;
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(current == null ? new Color(99, 110, 114) : new Color(22, 160, 133))
                .setTitle("\uD83C\uDFB5 " + musicUx(lang, "panel_title"))
                .setDescription(summaryLine)
                .addField("\uD83C\uDFA7 " + musicUx(lang, "panel_current"), currentText, false)
                .addField("\uD83D\uDC64 " + musicUx(lang, "panel_requester"), requester, true)
                .addField("\uD83C\uDFA4 " + musicUx(lang, "panel_author"), author, true)
                .addField("\uD83D\uDD17 " + musicUx(lang, "panel_source"), source, true)
                .addField("\u23F1\uFE0F " + musicUx(lang, "panel_duration"), current == null ? musicUx(lang, "panel_none") : formatDuration(duration), true)
                .addField("\uD83D\uDCCA " + musicUx(lang, "panel_progress"), progress, false)
                .addField("\uD83D\uDCC3 " + musicUx(lang, "panel_queue"), queueText, false)
                .setFooter("\u21BB " + musicUx(lang, "btn_refresh"))
                .setTimestamp(Instant.now());
        if (autoplayNotice != null && !autoplayNotice.isBlank()) {
            builder.addField(musicUx(lang, "panel_autoplay_notice"), formatAutoplayNotice(lang, autoplayNotice), false);
        }
        if (artwork != null && !artwork.isBlank()) {
            builder.setImage(artwork);
        }
        return builder;
    }

    private List<Button> panelButtons(String lang, long guildId) {
        List<Button> buttons = new ArrayList<>();
        String repeatMode = musicService.getRepeatModeByGuildId(guildId);
        boolean autoplayEnabled = isAutoplayEnabled(guildId);
        Guild guild = jda == null ? null : jda.getGuildById(guildId);
        int currentVolume = guild == null ? 100 : musicService.getVolume(guild);
        boolean hasQueue = guild != null && !musicService.getQueueSnapshot(guild).isEmpty();
        buttons.add(Button.primary(PANEL_PLAY_PAUSE, "\u23EF " + musicUx(lang, "btn_play_pause")));
        buttons.add(Button.primary(PANEL_SKIP, "\u23ED " + musicUx(lang, "btn_skip")));
        buttons.add(Button.danger(PANEL_STOP, "\u23F9 " + musicUx(lang, "btn_stop")));
        buttons.add(Button.secondary(PANEL_LEAVE, "\uD83D\uDCE4 " + musicUx(lang, "btn_leave")));
        buttons.add(currentVolume <= 1
                ? Button.secondary(PANEL_VOLUME_DOWN, "\uD83D\uDD09 " + musicUx(lang, "btn_volume_down")).asDisabled()
                : Button.secondary(PANEL_VOLUME_DOWN, "\uD83D\uDD09 " + musicUx(lang, "btn_volume_down")));
        buttons.add(currentVolume >= 100
                ? Button.secondary(PANEL_VOLUME_UP, "\uD83D\uDD0A " + musicUx(lang, "btn_volume_up")).asDisabled()
                : Button.secondary(PANEL_VOLUME_UP, "\uD83D\uDD0A " + musicUx(lang, "btn_volume_up")));
        buttons.add(Button.secondary(PANEL_REFRESH, "\uD83D\uDD04 " + musicUx(lang, "btn_refresh")));
        buttons.add(autoplayEnabled
                ? Button.success(PANEL_AUTOPLAY_TOGGLE, "\uD83E\uDDE0 " + musicUx(lang, "btn_autoplay_on"))
                : Button.secondary(PANEL_AUTOPLAY_TOGGLE, "\uD83E\uDDE0 " + musicUx(lang, "btn_autoplay_off")));
        buttons.add(repeatToggleButton(lang, repeatMode));
        buttons.add(hasQueue
                ? Button.secondary(PANEL_SHUFFLE, "\uD83D\uDD00 " + musicUx(lang, "btn_shuffle"))
                : Button.secondary(PANEL_SHUFFLE, "\uD83D\uDD00 " + musicUx(lang, "btn_shuffle")).asDisabled());
        return buttons;
    }

    private Button repeatToggleButton(String lang, String repeatMode) {
        String mode = repeatMode == null ? "OFF" : repeatMode.toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "SINGLE" -> Button.success(PANEL_REPEAT_TOGGLE, "\uD83D\uDD02 " + musicUx(lang, "btn_repeat_single"));
            case "ALL" -> Button.success(PANEL_REPEAT_TOGGLE, "\uD83D\uDD01 " + musicUx(lang, "btn_repeat_all"));
            default -> Button.secondary(PANEL_REPEAT_TOGGLE, "\u2B55 " + musicUx(lang, "btn_repeat_off"));
        };
    }

    private List<ActionRow> panelRows(String lang, long guildId) {
        List<Button> buttons = panelButtons(lang, guildId);
        return List.of(
                ActionRow.of(buttons.subList(0, 4)),
                ActionRow.of(buttons.subList(4, 8)),
                ActionRow.of(buttons.subList(8, buttons.size()))
        );
    }

    int adjustPanelVolume(Guild guild, int delta) {
        int current = musicService.getVolume(guild);
        int target = Math.max(1, Math.min(100, current + delta));
        return musicService.setVolume(guild, target);
    }

    private String deleteAmountText(String lang, Integer amount) {
        if (amount != null) {
            return String.valueOf(amount);
        }
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "\u6240\u6709\u7b26\u5408\u6761\u4ef6\u7684\u6d88\u606f";
        }
        if (lang != null && lang.toLowerCase(Locale.ROOT).startsWith("zh")) {
            return "\u6240\u6709\u7b26\u5408\u689d\u4ef6\u7684\u8a0a\u606f";
        }
        return "all matching messages";
    }

    private List<Message> findMessagesForDeletion(TextChannel channel, Long targetUserId, Integer amount, int maxPages, Duration lookback) {
        List<Message> matched = new ArrayList<>();
        Instant cutoff = Instant.now().minus(lookback);
        MessageHistory history = channel.getHistory();
        List<Message> page = history.retrievePast(100).complete();
        for (int i = 0; i < maxPages && !page.isEmpty() && (amount == null || matched.size() < amount); i++) {
            for (Message message : page) {
                if (targetUserId == null && message.getAuthor().isBot()) {
                    continue;
                }
                Instant createdAt = message.getTimeCreated().toInstant();
                if (createdAt.isBefore(cutoff)) {
                    continue;
                }
                if (targetUserId != null && message.getAuthor().getIdLong() != targetUserId) {
                    continue;
                }
                matched.add(message);
                if (amount != null && matched.size() >= amount) {
                    break;
                }
            }
            if (amount != null && matched.size() >= amount) {
                break;
            }
            String before = page.get(page.size() - 1).getId();
            page = MessageHistory.getHistoryBefore(channel, before).limit(100).complete().getRetrievedHistory();
        }
        return matched;
    }

    private int performDeleteRequest(Guild guild, DeleteRequest req) {
        Duration lookback = req.lookback;
        if (req.channelId != null) {
            TextChannel channel = guild.getTextChannelById(req.channelId);
            if (channel == null || !canManageMessages(channel)) {
                return 0;
            }
            List<Message> targets = findMessagesForDeletion(channel, req.targetUserId, req.amount, 25, lookback);
            return performDelete(channel, targets);
        }
        int total = 0;
        for (TextChannel channel : guild.getTextChannels()) {
            if (!canManageMessages(channel)) {
                continue;
            }
            Integer remaining = req.amount == null ? null : req.amount - total;
            if (remaining != null && remaining <= 0) {
                break;
            }
            List<Message> targets = findMessagesForDeletion(channel, req.targetUserId, remaining, 25, lookback);
            total += performDelete(channel, targets);
        }
        return total;
    }

    private Duration parseDeleteLookback(String input) {
        if (input == null) {
            return null;
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        long totalSeconds = 0L;
        long currentValue = 0L;
        boolean foundUnit = false;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isDigit(ch)) {
                currentValue = currentValue * 10L + (ch - '0');
                continue;
            }
            if (currentValue <= 0L) {
                return null;
            }
            long unitSeconds;
            switch (ch) {
                case 'd' -> unitSeconds = 86400L;
                case 'h' -> unitSeconds = 3600L;
                case 'm' -> unitSeconds = 60L;
                case 's' -> unitSeconds = 1L;
                default -> {
                    return null;
                }
            }
            try {
                totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(currentValue, unitSeconds));
            } catch (ArithmeticException ex) {
                return null;
            }
            currentValue = 0L;
            foundUnit = true;
        }
        if (!foundUnit || currentValue != 0L || totalSeconds <= 0L) {
            return null;
        }
        Duration lookback = Duration.ofSeconds(totalSeconds);
        return lookback.compareTo(Duration.ofDays(14)) <= 0 ? lookback : null;
    }

    private String formatDeleteLookback(Duration lookback) {
        long totalSeconds = lookback.getSeconds();
        long days = totalSeconds / 86400L;
        totalSeconds %= 86400L;
        long hours = totalSeconds / 3600L;
        totalSeconds %= 3600L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        StringBuilder sb = new StringBuilder();
        if (days > 0L) {
            sb.append(days).append('d');
        }
        if (hours > 0L) {
            sb.append(hours).append('h');
        }
        if (minutes > 0L) {
            sb.append(minutes).append('m');
        }
        if (seconds > 0L || sb.length() == 0) {
            sb.append(seconds).append('s');
        }
        return sb.toString();
    }

    private String deleteTimeNotice(String lang, Duration lookback) {
        String formatted = formatDeleteLookback(lookback);
        String translated = i18n.t(lang, "delete.time_notice", Map.of("time", formatted));
        if (!"delete.time_notice".equals(translated)) {
            return translated;
        }
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "\u641c\u7d22\u8303\u56f4\uff1a\u6700\u8fd1 " + formatted + "\u3002";
        }
        if ("zh-TW".equalsIgnoreCase(lang)) {
            return "\u641c\u5c0b\u7bc4\u570d\uff1a\u6700\u8fd1 " + formatted + "\u3002";
        }
        return "Search range: last " + formatted + ".";
    }

    private String deleteDefaultTimeNotice(String lang, String formatted) {
        String translated = i18n.t(lang, "delete.default_time_notice", Map.of("time", formatted));
        if (!"delete.default_time_notice".equals(translated)) {
            return translated;
        }
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "\u672a\u63d0\u4f9b\u65f6\u95f4\uff0c\u4f7f\u7528\u9ed8\u8ba4\u8303\u56f4\uff1a\u6700\u8fd1 " + formatted + "\u3002";
        }
        if ("zh-TW".equalsIgnoreCase(lang)) {
            return "\u672a\u63d0\u4f9b\u6642\u9593\uff0c\u4f7f\u7528\u9810\u8a2d\u7bc4\u570d\uff1a\u6700\u8fd1 " + formatted + "\u3002";
        }
        return "Time not provided. Using default range: last " + formatted + ".";
    }

    private boolean canManageMessages(TextChannel channel) {
        Member self = channel.getGuild().getSelfMember();
        return self.hasAccess(channel)
                && self.hasPermission(channel, Permission.MESSAGE_HISTORY, Permission.MESSAGE_MANAGE);
    }

    private int performDelete(TextChannel channel, List<Message> messages) {
        if (messages.isEmpty()) {
            return 0;
        }
        if (messages.size() == 1) {
            channel.deleteMessageById(messages.get(0).getId()).complete();
            return 1;
        }
        int total = 0;
        List<Message> buffer = new ArrayList<>();
        for (Message message : messages) {
            buffer.add(message);
            if (buffer.size() == 100) {
                channel.deleteMessages(buffer).complete();
                total += buffer.size();
                buffer = new ArrayList<>();
            }
        }
        if (!buffer.isEmpty()) {
            if (buffer.size() == 1) {
                channel.deleteMessageById(buffer.get(0).getId()).complete();
            } else {
                channel.deleteMessages(buffer).complete();
            }
            total += buffer.size();
        }
        return total;
    }
    public List<CommandData> buildCommands() {
        List<CommandData> commands = new ArrayList<>();
        commands.add(Commands.slash("help", "Show bot help")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_HELP_ZH, "\u986f\u793a\u6a5f\u5668\u4eba\u8aaa\u660e")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("ping", "Check bot latency")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_PING_ZH, "\u6aa2\u67e5 Bot \u5ef6\u9072")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("url", "Create a short URL")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(
                        new OptionData(OptionType.STRING, "url", "Target URL (http/https)", true),
                        new OptionData(OptionType.STRING, "slug", "Custom slug (optional), e.g. discord", false)
                ));
        commands.add(Commands.slash(CMD_SHORT_URL_ZH, "\u5efa\u7acb\u77ed\u7db2\u5740")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(
                        new OptionData(OptionType.STRING, "url", "目標網址（http/https）", true),
                        new OptionData(OptionType.STRING, "slug", "自訂代碼（選填），例如 discord", false)
                ));
        commands.add(Commands.slash("welcome", "Edit member join welcome message")
                .addOptions(
                        buildWelcomeActionOption(false),
                        new OptionData(OptionType.CHANNEL, "channel", "Welcome message channel", false)
                                .setChannelTypes(ChannelType.TEXT)
                )
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(Commands.slash(CMD_WELCOME_ZH, "\u7de8\u8f2f\u6210\u54e1\u52a0\u5165\u6b61\u8fce\u8a0a\u606f")
                .addOptions(
                        buildWelcomeActionOption(true),
                        new OptionData(OptionType.CHANNEL, OPTION_WELCOME_CHANNEL_ZH, "\u6b61\u8fce\u8a0a\u606f\u983b\u9053", false)
                                .setChannelTypes(ChannelType.TEXT)
                )
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(Commands.slash("join", "Join your voice channel")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_JOIN_ZH, "\u8b93 Bot \u52a0\u5165\u4f60\u7684\u8a9e\u97f3\u983b\u9053")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("play", "Play music")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.STRING, "query", "URL / keywords / Spotify URL", true)));
        commands.add(Commands.slash(CMD_PLAY_ZH, "\u64ad\u653e\u97f3\u6a02")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.STRING, "query", "URL\uff0f\u95dc\u9375\u5b57\uff0fSpotify \u9023\u7d50", true)));
        commands.add(Commands.slash("skip", "Skip current track")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_SKIP_ZH, "\u8df3\u904e\u76ee\u524d\u6b4c\u66f2")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("stop", "Stop playback and clear queue")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_STOP_ZH, "\u505c\u6b62\u64ad\u653e\u4e26\u6e05\u7a7a\u4f47\u5217")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("leave", "Leave voice channel")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_LEAVE_ZH, "\u8b93 Bot \u96e2\u958b\u8a9e\u97f3\u983b\u9053")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("music-panel", "Create music control panel")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_MUSIC_PANEL_ZH, "\u5efa\u7acb\u97f3\u6a02\u63a7\u5236\u9762\u677f")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("private-room-settings", "Manage your private room")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_ROOM_SETTINGS_ZH, "\u7ba1\u7406\u4f60\u7684\u79c1\u4eba\u5305\u5ec2")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("repeat", "Set repeat mode")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.STRING, "mode", "off/single/all", true)
                        .addChoice("off", "OFF")
                        .addChoice("single", "SINGLE")
                        .addChoice("all", "ALL")));
        commands.add(Commands.slash(CMD_REPEAT_ZH, "\u8a2d\u5b9a\u5faa\u74b0\u6a21\u5f0f")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.STRING, "mode", "off\uff08\u95dc\uff09 / single\uff08\u55ae\u66f2\uff09 / all\uff08\u4f47\u5217\uff09", true)
                        .addChoice("off", "OFF")
                        .addChoice("single", "SINGLE")
                        .addChoice("all", "ALL")));
        commands.add(Commands.slash("volume", "Set playback volume")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.INTEGER, "value", "1-100", true).setRequiredRange(1, 100)));
        commands.add(Commands.slash(CMD_VOLUME_ZH, "\u8a2d\u5b9a\u64ad\u653e\u97f3\u91cf")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(localizedOptionName(
                        new OptionData(OptionType.INTEGER, "value", "1-100", true).setRequiredRange(1, 100),
                        OPTION_VOLUME_VALUE_ZH,
                        OPTION_VOLUME_VALUE_ZH
                )));
        commands.add(Commands.slash("history", "Show recently played tracks")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_HISTORY_ZH, "\u986f\u793a\u6700\u8fd1\u64ad\u653e\u7684\u6b4c\u66f2")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("music", "Music utility commands")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addSubcommands(
                        new SubcommandData("stats", "Show music statistics")
                ));
        commands.add(Commands.slash(CMD_MUSIC_ZH, "\u97f3\u6a02\u5de5\u5177\u6307\u4ee4")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addSubcommands(
                        localizedSubcommandName(
                                new SubcommandData("stats", "\u986f\u793a\u97f3\u6a02\u7d71\u8a08"),
                                SUB_MUSIC_STATS_ZH,
                                SUB_MUSIC_STATS_ZH
                        )
                ));
        commands.add(buildPlaylistCommand("playlist"));
        commands.add(buildPlaylistCommand(CMD_PLAYLIST_ZH));
        commands.add(buildSettingsCommand("settings"));
        commands.add(buildSettingsCommand(CMD_SETTINGS_ZH));
        commands.add(buildDeleteCommand());
        commands.add(buildDeleteCommandZh());
        commands.add(buildWarningsCommand("warnings"));
        commands.add(buildWarningsCommand(CMD_WARNINGS_ZH));
        commands.add(buildAntiDuplicateCommand("anti-duplicate"));
        commands.add(buildAntiDuplicateCommand(CMD_ANTI_DUPLICATE_ZH));
        commands.add(buildHoneypotCommand("honeypot-channel"));
        commands.add(buildHoneypotCommand(CMD_HONEYPOT_ZH));
        commands.add(buildNumberChainCommand("number-chain"));
        commands.add(buildNumberChainCommand(CMD_NUMBER_CHAIN_ZH));
        commands.add(buildTicketCommand("ticket"));
        commands.add(buildTicketCommand(CMD_TICKET_ZH));
        commands.add(buildUserInfoCommand("user-info"));
        commands.add(buildUserInfoCommand(CMD_USER_INFO_ZH));
        commands.add(buildRoleInfoCommand("role-info"));
        commands.add(buildRoleInfoCommand(CMD_ROLE_INFO_ZH));
        commands.add(buildServerInfoCommand("server-info"));
        commands.add(buildServerInfoCommand(CMD_SERVER_INFO_ZH));
        commands.add(Commands.slash("stats", "Show message and voice stats for a user")
                .addOptions(new OptionData(OptionType.USER, "user", "Target user", false))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_STATS_ZH, "\u986f\u793a\u4f7f\u7528\u8005\u7684\u8a0a\u606f\u8207\u8a9e\u97f3\u7d71\u8a08")
                .addOptions(new OptionData(OptionType.USER, "user", "Target user", false))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("top", "Open leaderboard menu")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_LEADERBOARD_ZH, "\u958b\u555f\u6392\u884c\u699c\u9078\u55ae")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        return commands;
    }

    private SlashCommandData buildNumberChainCommand(String commandName) {
        boolean zh = CMD_NUMBER_CHAIN_ZH.equals(commandName);
        OptionData channel = new OptionData(OptionType.CHANNEL, "channel",
                zh ? "\u6578\u5b57\u63a5\u9f8d\u6587\u5b57\u983b\u9053" : "Number chain text channel", false)
                .setChannelTypes(ChannelType.TEXT);
        OptionData reset = new OptionData(OptionType.BOOLEAN, "reset",
                zh ? "\u5c07\u9032\u5ea6\u91cd\u8a2d\u56de 1" : "Reset progress back to 1", false);
        return Commands.slash(commandName, zh ? "\u6578\u5b57\u63a5\u9f8d\u8a2d\u5b9a" : "Number chain settings")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addOptions(buildNumberChainActionOption(zh), channel, reset);
    }

    private OptionData buildNumberChainActionOption(boolean zh) {
        OptionData option = new OptionData(OptionType.STRING, "action",
                zh ? "\u6578\u5b57\u63a5\u9f8d\u8a2d\u5b9a" : "Number chain settings", false)
                .addChoices(
                        new Command.Choice(zh ? SUB_GENERIC_ENABLE_ZH : "enable", "enable"),
                        new Command.Choice(zh ? SUB_GENERIC_STATUS_ZH : "status", "status")
                );
        if (zh) {
            option.setNameLocalization(DiscordLocale.CHINESE_TAIWAN, "\u9078\u9805");
            option.setNameLocalization(DiscordLocale.CHINESE_CHINA, "\u9009\u9879");
        }
        return option;
    }

    private SlashCommandData buildTicketCommand(String commandName) {
        boolean zh = CMD_TICKET_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u5ba2\u670d\u55ae\u7cfb\u7d71" : "Ticket system")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED);
    }

    private SlashCommandData buildUserInfoCommand(String commandName) {
        boolean zh = CMD_USER_INFO_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u67e5\u8a62\u4f7f\u7528\u8005\u8cc7\u8a0a" : "Show user information")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.USER, "user", zh ? "\u8981\u67e5\u8a62\u7684\u4f7f\u7528\u8005" : "User to inspect", false));
    }

    private SlashCommandData buildRoleInfoCommand(String commandName) {
        boolean zh = CMD_ROLE_INFO_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u67e5\u8a62\u8eab\u5206\u7d44\u8cc7\u8a0a" : "Show role information")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.ROLE, "role", zh ? "\u8981\u67e5\u8a62\u7684\u8eab\u5206\u7d44" : "Role to inspect", true));
    }

    private SlashCommandData buildServerInfoCommand(String commandName) {
        boolean zh = CMD_SERVER_INFO_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u67e5\u8a62\u4f3a\u670d\u5668\u8cc7\u8a0a" : "Show server information")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED);
    }

    private SlashCommandData buildPlaylistCommand(String commandName) {
        boolean zh = CMD_PLAYLIST_ZH.equals(commandName);
        SubcommandData save = new SubcommandData(zh ? "\u5132\u5b58" : "save",
                zh ? "\u5132\u5b58\u76ee\u524d\u4f47\u5217\u70ba\u6b4c\u55ae" : "Save current queue as playlist")
                .addOptions(new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                        .setAutoComplete(true));
        SubcommandData load = new SubcommandData(zh ? "\u8f09\u5165" : "load",
                zh ? "\u8f09\u5165\u5df2\u5132\u5b58\u6b4c\u55ae" : "Load saved playlist")
                .addOptions(new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                        .setAutoComplete(true));
        SubcommandData add = new SubcommandData(zh ? SUB_PLAYLIST_ADD_ZH : "add",
                zh ? "\u5c07\u6307\u5b9a URL \u6b4c\u66f2\u65b0\u589e\u5230\u6b4c\u55ae\uff08\u50c5\u9650\u6b4c\u55ae\u5efa\u7acb\u8005\uff09"
                        : "Add a track by URL to a playlist (playlist owner only)")
                .addOptions(
                        new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                                .setAutoComplete(true),
                        new OptionData(OptionType.STRING, "url", zh ? "URL" : "URL", true)
                );
        SubcommandData delete = new SubcommandData(zh ? "\u522a\u9664" : "delete",
                zh ? "\u522a\u9664\u5df2\u5132\u5b58\u6b4c\u55ae" : "Delete saved playlist")
                .addOptions(new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                        .setAutoComplete(true));
        SubcommandData list = new SubcommandData(zh ? "\u5217\u8868" : "list",
                zh ? "\u5217\u51fa\u5df2\u5132\u5b58\u6b4c\u55ae" : "List saved playlists")
                .addOptions(new OptionData(OptionType.STRING, "scope", zh ? "\u986f\u793a\u7bc4\u570d" : "Display scope", false)
                        .addChoice(zh ? "\u53ea\u986f\u793a\u81ea\u5df1\u7684" : "Only mine", PLAYLIST_SCOPE_MINE)
                        .addChoice(zh ? "\u5168\u90e8\u6b4c\u55ae" : "All playlists", PLAYLIST_SCOPE_ALL));
        SubcommandData view = new SubcommandData(zh ? "\u67e5\u770b" : "view",
                zh ? "\u67e5\u770b\u6b4c\u55ae\u5167\u7684\u6b4c\u66f2" : "View tracks inside a playlist")
                .addOptions(new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                        .setAutoComplete(true));
        SubcommandData removeTrack = new SubcommandData(zh ? SUB_PLAYLIST_REMOVE_TRACK_ZH : "remove-track",
                zh ? "\u522a\u9664\u6b4c\u55ae\u5167\u7684\u6b4c\u66f2\uff08\u5148\u4f7f\u7528 /" + CMD_PLAYLIST_ZH + " " + SUB_PLAYLIST_VIEW_ZH + " \u67e5\u770b\u7de8\u865f\uff09"
                        : "Remove a track from a playlist (use /playlist view to find the track number)")
                .addOptions(
                        new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true).setAutoComplete(true),
                        new OptionData(OptionType.INTEGER, "index", zh ? "\u6b4c\u66f2\u7de8\u865f\uff081 \u958b\u59cb\uff09" : "Track number (starting at 1)", true)
                                .setRequiredRange(1, 10000)
                );
        SubcommandData export = new SubcommandData(zh ? "\u532f\u51fa" : "export",
                zh ? "\u7522\u751f 6 \u4f4d\u6578\u4ee3\u78bc\u4f9b\u8de8\u4f3a\u670d\u5668\u532f\u5165" : "Generate a 6-digit code for cross-server import")
                .addOptions(new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                        .setAutoComplete(true));
        SubcommandData importSub = new SubcommandData(zh ? "\u532f\u5165" : "import",
                zh ? "\u4f7f\u7528 6 \u4f4d\u6578\u4ee3\u78bc\u532f\u5165\u6b4c\u55ae" : "Import playlist using a 6-digit code")
                .addOptions(
                        new OptionData(OptionType.STRING, "code", zh ? "\u516d\u4f4d\u6578\u4ee3\u78bc" : "6-digit code", true),
                        new OptionData(OptionType.STRING, "name", zh ? "\u65b0\u6b4c\u55ae\u540d\u7a31\uff08\u9078\u586b\uff09" : "New playlist name (optional)", false)
                );
        return Commands.slash(commandName, zh ? "\u6b4c\u55ae\u7ba1\u7406" : "Playlist management")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addSubcommands(save, load, add, delete, list, view, removeTrack, export, importSub);
    }

    private SlashCommandData buildDeleteCommand() {
        return Commands.slash("delete-messages", "Delete messages")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE))
                .addOptions(
                        new OptionData(OptionType.STRING, "type", "Delete type", true)
                                .addChoices(
                                        new Command.Choice("channel", "channel"),
                                        new Command.Choice("user", "user")
                                ),
                        new OptionData(OptionType.CHANNEL, "channel", "Text channel", false)
                                .setChannelTypes(ChannelType.TEXT),
                        new OptionData(OptionType.USER, "user", "Target user", false),
                        new OptionData(OptionType.STRING, "time", "Duration like 24h or 13d23h59m59s (max 14d, default 24h)", false),
                        new OptionData(OptionType.INTEGER, "amount", "1-99", false).setRequiredRange(1, 99)
                );
    }

    private SlashCommandData buildDeleteCommandZh() {
        return Commands.slash(CMD_DELETE_ZH, "\u522a\u9664\u8a0a\u606f")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE))
                .addOptions(
                        new OptionData(OptionType.STRING, "type", "\u522a\u9664\u985e\u578b", true)
                                .addChoices(
                                        new Command.Choice(SUB_DELETE_CHANNEL_ZH, "channel"),
                                        new Command.Choice(SUB_DELETE_USER_ZH, "user")
                                ),
                        new OptionData(OptionType.CHANNEL, "channel", "\u6587\u5b57\u983b\u9053", false)
                                .setChannelTypes(ChannelType.TEXT),
                        new OptionData(OptionType.USER, "user", "\u76ee\u6a19\u4f7f\u7528\u8005", false),
                        new OptionData(OptionType.STRING, "time", "\u6642\u9593\u7bc4\u570d\uff0c\u4f8b\u5982 24h \u6216 13d23h59m59s\uff08\u4e0a\u9650 14d\uff0c\u9810\u8a2d 24h\uff09", false),
                        new OptionData(OptionType.INTEGER, "amount", "1-99", false).setRequiredRange(1, 99)
                );
    }

    private SlashCommandData buildSettingsCommand(String commandName) {
        boolean zh = CMD_SETTINGS_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u4f3a\u670d\u5668\u8a2d\u5b9a" : "Guild settings")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addOptions(buildSettingsActionOption(zh));
    }

    private static OptionData localizedOptionName(OptionData option, String zhTwName, String zhCnName) {
        return option
                .setNameLocalization(DiscordLocale.CHINESE_TAIWAN, zhTwName)
                .setNameLocalization(DiscordLocale.CHINESE_CHINA, zhCnName);
    }

    private static SubcommandData localizedSubcommandName(SubcommandData subcommand, String zhTwName, String zhCnName) {
        return subcommand
                .setNameLocalization(DiscordLocale.CHINESE_TAIWAN, zhTwName)
                .setNameLocalization(DiscordLocale.CHINESE_CHINA, zhCnName);
    }

    private OptionData buildSettingsActionOption(boolean zh) {
        OptionData option = new OptionData(OptionType.STRING, "action",
                zh ? "\u4f3a\u670d\u5668\u8a2d\u5b9a" : "Guild settings", true)
                .addChoices(
                        new Command.Choice(zh ? SUB_SETTINGS_INFO_ZH : "info", "info"),
                        new Command.Choice(zh ? SUB_SETTINGS_RELOAD_ZH : "reload", "reload"),
                        new Command.Choice(zh ? SUB_SETTINGS_RESET_ZH : "reset", "reset"),
                        new Command.Choice(zh ? SUB_SETTINGS_TEMPLATE_ZH : "template", "template"),
                        new Command.Choice(zh ? SUB_SETTINGS_MODULE_ZH : "module", "module"),
                        new Command.Choice(zh ? SUB_SETTINGS_LOGS_ZH : "logs", "logs"),
                        new Command.Choice(zh ? SUB_SETTINGS_LOG_SETTINGS_ZH : "log-settings", "log-settings"),
                        new Command.Choice(zh ? SUB_SETTINGS_MUSIC_ZH : "music", "music"),
                        new Command.Choice(zh ? SUB_SETTINGS_NUMBER_CHAIN_ZH : "number-chain", "number-chain"),
                        new Command.Choice(zh ? SUB_SETTINGS_LANGUAGE_ZH : "language", "language")
                );
        if (zh) {
            option.setNameLocalization(DiscordLocale.CHINESE_TAIWAN, "\u9078\u9805");
            option.setNameLocalization(DiscordLocale.CHINESE_CHINA, "\u9009\u9879");
        }
        return option;
    }

    private OptionData buildWarningsActionOption(boolean zh) {
        OptionData option = new OptionData(OptionType.STRING, "action",
                zh ? "\u8b66\u544a\u7ba1\u7406" : "Manage warning counts", true)
                .addChoices(
                        new Command.Choice(zh ? "\u589e\u52a0" : "add", "add"),
                        new Command.Choice(zh ? "\u6e1b\u5c11" : "remove", "remove"),
                        new Command.Choice(zh ? "\u67e5\u770b" : "view", "view"),
                        new Command.Choice(zh ? "\u6e05\u9664" : "clear", "clear")
                );
        if (zh) {
            option.setNameLocalization(DiscordLocale.CHINESE_TAIWAN, "\u9078\u9805");
            option.setNameLocalization(DiscordLocale.CHINESE_CHINA, "\u9009\u9879");
        }
        return option;
    }

    private OptionData buildAntiDuplicateActionOption(boolean zh) {
        OptionData option = new OptionData(OptionType.STRING, "action",
                zh ? "\u9632\u91cd\u8907\u8a0a\u606f\u5075\u6e2c\u8a2d\u5b9a" : "Duplicate message detection settings", true)
                .addChoices(
                        new Command.Choice(zh ? SUB_GENERIC_ENABLE_ZH : "enable", "enable"),
                        new Command.Choice(zh ? SUB_GENERIC_STATUS_ZH : "status", "status")
                );
        if (zh) {
            option.setNameLocalization(DiscordLocale.CHINESE_TAIWAN, "\u9078\u9805");
            option.setNameLocalization(DiscordLocale.CHINESE_CHINA, "\u9009\u9879");
        }
        return option;
    }

    public void handleWarningsSlash(SlashCommandInteractionEvent event, String lang) {
        if (!has(event.getMember(), Permission.MODERATE_MEMBERS)) {
            event.reply(i18n.t(lang, "general.missing_permissions",
                            Map.of("permissions", Permission.MODERATE_MEMBERS.getName())))
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String sub = event.getSubcommandName();
        if ((sub == null || sub.isBlank()) && event.getOption("action") != null) {
            sub = event.getOption("action").getAsString();
        }
        if (sub == null) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        User target = event.getOption("user") == null ? event.getUser() : event.getOption("user").getAsUser();
        int amount = event.getOption("amount") == null ? 1 : Math.max(1, (int) event.getOption("amount").getAsLong());
        long guildId = event.getGuild().getIdLong();

        switch (sub) {
            case "add" -> {
                openWarningReasonModal(event, sub, target, amount, lang);
            }
            case "remove" -> {
                openWarningReasonModal(event, sub, target, amount, lang);
            }
            case "view" -> {
                int count = moderationService.getWarnings(guildId, target.getIdLong());
                event.replyEmbeds(warningsResultEmbed(i18n.t(lang, "warnings.result_view",
                                Map.of("user", target.getAsMention(), "count", String.valueOf(count)))).build())
                        .queue();
            }
            case "clear" -> {
                openWarningReasonModal(event, sub, target, amount, lang);
            }
            default -> event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }

    private void openWarningReasonModal(SlashCommandInteractionEvent event, String action, User target, int amount, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        warningActionRequests.put(token, new WarningActionRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                action,
                target.getIdLong(),
                amount,
                Instant.now().plusSeconds(180)
        ));
        TextInput reasonInput = TextInput.create("reason", TextInputStyle.PARAGRAPH)
                .setRequired(true)
                .setPlaceholder(i18n.t(lang, "warnings.reason_placeholder"))
                .setMaxLength(500)
                .build();
        Modal modal = Modal.create(WARNING_REASON_MODAL_PREFIX + token, i18n.t(lang, "warnings.reason_modal_title"))
                .addComponents(Label.of(i18n.t(lang, "warnings.reason_modal_label"), reasonInput))
                .build();
        event.replyModal(modal).queue();
    }
    public void handleWarningReasonModal(ModalInteractionEvent event, String lang) {
        String token = event.getModalId().substring(WARNING_REASON_MODAL_PREFIX.length());
        WarningActionRequest request = warningActionRequests.remove(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId || event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        String reason = Objects.requireNonNull(event.getValue("reason")).getAsString().trim();
        if (reason.isBlank()) {
            event.replyEmbeds(warningsResultEmbed(i18n.t(lang, "warnings.reason_required")).build())
                    .setEphemeral(true)
                    .queue();
            return;
        }
        User target = jda == null ? null : jda.getUserById(request.targetUserId);
        String userText = target == null ? "<@" + request.targetUserId + ">" : target.getAsMention();
        String result;
        switch (request.action) {
            case "add" -> {
                int count = moderationService.addWarnings(request.guildId, request.targetUserId, request.amount);
                result = i18n.t(lang, "warnings.result_add",
                        Map.of("user", userText, "count", String.valueOf(count)));
            }
            case "remove" -> {
                int count = moderationService.removeWarnings(request.guildId, request.targetUserId, request.amount);
                result = i18n.t(lang, "warnings.result_remove",
                        Map.of("user", userText, "count", String.valueOf(count)));
            }
            case "clear" -> {
                moderationService.clearWarnings(request.guildId, request.targetUserId);
                result = i18n.t(lang, "warnings.result_clear", Map.of("user", userText));
            }
            default -> {
                event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
                return;
            }
        }
        result = result + "\n" + i18n.t(lang, "warnings.reason_line", Map.of("reason", reason));
        event.replyEmbeds(warningsResultEmbed(result).build()).queue();
    }

    private EmbedBuilder warningsResultEmbed(String content) {
        return new EmbedBuilder()
                .setColor(new Color(241, 196, 15))
                .setDescription(content);
    }
    public void handleAntiDuplicateSlash(SlashCommandInteractionEvent event, String lang) {
        if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(i18n.t(lang, "general.missing_permissions",
                            Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String sub = event.getSubcommandName();
        if ((sub == null || sub.isBlank()) && event.getOption("action") != null) {
            sub = event.getOption("action").getAsString();
        }
        long guildId = event.getGuild().getIdLong();
        if ("enable".equals(sub)) {
            boolean enabled = event.getOption("value") == null
                    ? !moderationService.isDuplicateDetectionEnabled(guildId)
                    : event.getOption("value").getAsBoolean();
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
                    event.reply(i18n.t(lang, "anti_duplicate.missing_bot_permissions",
                                    Map.of("permissions", String.join(", ", missingBotPermissions))))
                            .setEphemeral(true)
                            .queue();
                    return;
                }
            }
            moderationService.setDuplicateDetectionEnabled(guildId, enabled);
            event.reply(i18n.t(lang, "anti_duplicate.result_set",
                            Map.of("status", boolText(lang, enabled))))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        boolean enabled = moderationService.isDuplicateDetectionEnabled(guildId);
        event.reply(i18n.t(lang, "anti_duplicate.result_status",
                        Map.of("status", boolText(lang, enabled))))
                .setEphemeral(true)
                .queue();
    }
    public void openLanguageMenu(SlashCommandInteractionEvent event, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        languageMenuRequests.put(token, new MenuRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(52, 152, 219))
                        .setTitle(i18n.t(lang, "settings.language_menu_title"))
                        .setDescription(i18n.t(lang, "settings.language_menu_desc"))
                        .build())
                .addComponents(ActionRow.of(settingsLanguageMenu(token, event.getGuild().getIdLong(), lang)))
                .setEphemeral(true)
                .queue();
    }
    public void openNumberChainMenu(SlashCommandInteractionEvent event, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        numberChainMenuRequests.put(token, new MenuRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(numberChainMenuEmbed(event.getGuild(), lang, null).build())
                .addComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                .setEphemeral(true)
                .queue();
    }

    private StringSelectMenu settingsNumberChainMenu(String token, Guild guild, String lang) {
        long guildId = guild.getIdLong();
        boolean enabled = moderationService.isNumberChainEnabled(guildId);
        Long channelId = moderationService.getNumberChainChannelId(guildId);
        long next = moderationService.getNumberChainNext(guildId);
        return StringSelectMenu.create(SETTINGS_NUMBER_CHAIN_SELECT_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "settings.number_chain_menu_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.info_key_number_chain_enabled"), "enable-toggle")
                                .withDescription(i18n.t(lang, "settings.music_menu_current",
                                        Map.of("value", boolText(lang, enabled)))),
                        SelectOption.of(i18n.t(lang, "settings.info_key_number_chain_channel"), "set-channel")
                                .withDescription(i18n.t(lang, "settings.music_menu_current",
                                        Map.of("value", safe(formatTextChannel(guild, channelId), 60)))),
                        SelectOption.of(i18n.t(lang, "settings.info_key_number_chain_next"), "reset")
                                .withDescription(i18n.t(lang, "settings.music_menu_current",
                                        Map.of("value", String.valueOf(next))))
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
                "\uD83C\uDFC6 " + numberChainHighestLabel(lang) + "\n> " + i18n.t(lang, "settings.value_label") + ": "
                        + moderationService.getNumberChainHighestNumber(guildId),
                "\uD83D\uDC65 " + numberChainTopContributorsLabel(lang) + "\n> "
                        + formatNumberChainTopContributors(guild, lang)
        );
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(46, 204, 113))
                .setTitle("\uD83D\uDD22 " + i18n.t(lang, "settings.number_chain_menu_title"))
                .setDescription(i18n.t(lang, "settings.number_chain_menu_desc"))
                .addField("\uD83D\uDD22 " + i18n.t(lang, "settings.info_number_chain"), body, false);
        if (changedText != null && !changedText.isBlank()) {
            eb.addField(i18n.t(lang, "settings.template_updated"), changedText, false);
        }
        return eb;
    }
    public void handleNumberChainMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_NUMBER_CHAIN_SELECT_PREFIX.length());
        MenuRequest request = numberChainMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            numberChainMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.number_chain_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        switch (action) {
            case "enable-toggle" -> {
                boolean value = !moderationService.isNumberChainEnabled(guildId);
                moderationService.setNumberChainEnabled(guildId, value);
                String changed = i18n.t(lang, "general.settings_saved",
                        Map.of("key", i18n.t(lang, "settings.info_key_number_chain_enabled"), "value", boolText(lang, value)));
                event.editMessageEmbeds(numberChainMenuEmbed(event.getGuild(), lang, changed).build())
                        .setComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                        .queue();
            }
            case "set-channel" -> {
                EntitySelectMenu channelMenu = EntitySelectMenu
                        .create(SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX + token, EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT)
                        .setRequiredRange(1, 1)
                        .setPlaceholder(i18n.t(lang, "settings.number_chain_menu_channel_placeholder"))
                        .build();
                event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(46, 204, 113))
                                .setTitle(i18n.t(lang, "settings.number_chain_menu_pick_channel_title"))
                                .setDescription(i18n.t(lang, "settings.number_chain_menu_pick_channel_desc"))
                                .build())
                        .setComponents(ActionRow.of(channelMenu))
                        .queue();
            }
            case "reset" -> {
                moderationService.resetNumberChain(guildId);
                String changed = i18n.t(lang, "number_chain.result_reset");
                event.editMessageEmbeds(numberChainMenuEmbed(event.getGuild(), lang, changed).build())
                        .setComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                        .queue();
            }
            default -> event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }
    public void handleNumberChainChannelSelect(EntitySelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_NUMBER_CHAIN_CHANNEL_PREFIX.length());
        MenuRequest request = numberChainMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            numberChainMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.number_chain_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }

        List<IMentionable> values = event.getValues();
        if (values.isEmpty() || !(values.get(0) instanceof TextChannel channel)) {
            event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        moderationService.setNumberChainChannelId(guildId, channel.getIdLong());
        moderationService.resetNumberChain(guildId);
        String changed = i18n.t(lang, "number_chain.result_set_channel", Map.of("channel", channel.getAsMention()));
        event.editMessageEmbeds(numberChainMenuEmbed(event.getGuild(), lang, changed).build())
                .setComponents(ActionRow.of(settingsNumberChainMenu(token, event.getGuild(), lang)))
                .queue();
    }

    private StringSelectMenu settingsLanguageMenu(String token, long guildId, String lang) {
        String current = settingsService.getLanguage(guildId);
        StringSelectMenu.Builder builder = StringSelectMenu.create(SETTINGS_LANGUAGE_SELECT_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "settings.language_menu_placeholder"));
        int count = 0;
        for (Map.Entry<String, String> entry : i18n.getAvailableLanguages().entrySet()) {
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

    void handleLanguageMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_LANGUAGE_SELECT_PREFIX.length());
        MenuRequest request = languageMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            languageMenuRequests.remove(token);
            event.reply(i18n.t(lang, "settings.language_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        String code = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        if (!i18n.hasLanguage(code)) {
            event.reply(i18n.t(lang, "settings.language_invalid", Map.of("language", code))).setEphemeral(true).queue();
            return;
        }
        String normalized = i18n.normalizeLanguage(code);
        settingsService.updateSettings(event.getGuild().getIdLong(), s -> s.withLanguage(normalized));
        String languageDisplay = languageDisplayText(normalized);
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(i18n.t(normalized, "settings.language_menu_title"))
                        .setDescription(i18n.t(normalized, "settings.language_updated", Map.of("language", languageDisplay)))
                        .build())
                .setComponents(ActionRow.of(settingsLanguageMenu(token, event.getGuild().getIdLong(), normalized)))
                .queue();
    }

    private SlashCommandData buildWarningsCommand(String commandName) {
        boolean zh = CMD_WARNINGS_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u7ba1\u7406\u8b66\u544a\u6b21\u6578" : "Manage warning counts")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
                .addOptions(
                        buildWarningsActionOption(zh),
                        new OptionData(OptionType.USER, "user", zh ? "\u76ee\u6a19\u4f7f\u7528\u8005" : "Target user", false),
                        new OptionData(OptionType.INTEGER, "amount", zh ? "\u8b66\u544a\u6578\u91cf" : "Warning amount", false).setRequiredRange(1, 50)
                );
    }

    private SlashCommandData buildAntiDuplicateCommand(String commandName) {
        boolean zh = CMD_ANTI_DUPLICATE_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u91cd\u8907\u8a0a\u606f\u5075\u6e2c\u8a2d\u5b9a" : "Duplicate message detection settings")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addOptions(
                        buildAntiDuplicateActionOption(zh),
                        new OptionData(OptionType.BOOLEAN, "value", "true or false", false)
                );
    }

    private SlashCommandData buildHoneypotCommand(String commandName) {
        boolean zh = CMD_HONEYPOT_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u5efa\u7acb\u5bc6\u7f50\u6587\u5b57\u983b\u9053" : "Create a honeypot text channel")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER));
    }
    public String canonicalSlashName(String name) {
        return switch (name) {
            case CMD_HELP_ZH -> "help";
            case CMD_PING_ZH -> "ping";
            case CMD_WELCOME_ZH -> "welcome";
            case CMD_VOLUME_ZH -> "volume";
            case CMD_HISTORY_ZH -> "history";
            case CMD_MUSIC_ZH -> "music";
            case CMD_PLAYLIST_ZH -> "playlist";
            case CMD_JOIN_ZH -> "join";
            case CMD_PLAY_ZH -> "play";
            case CMD_SKIP_ZH -> "skip";
            case CMD_STOP_ZH -> "stop";
            case CMD_LEAVE_ZH -> "leave";
            case CMD_MUSIC_PANEL_ZH -> "music-panel";
            case CMD_REPEAT_ZH -> "repeat";
            case CMD_SETTINGS_ZH -> "settings";
            case CMD_DELETE_ZH -> "delete-messages";
            case CMD_ROOM_SETTINGS_ZH -> "private-room-settings";
            case CMD_WARNINGS_ZH -> "warnings";
            case CMD_ANTI_DUPLICATE_ZH -> "anti-duplicate";
            case CMD_HONEYPOT_ZH -> "honeypot-channel";
            case CMD_NUMBER_CHAIN_ZH -> "number-chain";
            case CMD_TICKET_ZH -> "ticket";
            case CMD_USER_INFO_ZH -> "user-info";
            case CMD_ROLE_INFO_ZH -> "role-info";
            case CMD_SERVER_INFO_ZH -> "server-info";
            case CMD_STATS_ZH -> "stats";
            case CMD_LEADERBOARD_ZH -> "top";
            case CMD_SHORT_URL_ZH -> "url";
            default -> name;
        };
    }
    public String canonicalSettingsSubcommand(String sub) {
        return switch (sub) {
            case SUB_SETTINGS_INFO_ZH -> "info";
            case SUB_SETTINGS_RELOAD_ZH -> "reload";
            case SUB_SETTINGS_RESET_ZH -> "reset";
            case SUB_SETTINGS_TEMPLATE_ZH -> "template";
            case SUB_SETTINGS_MODULE_ZH -> "module";
            case SUB_SETTINGS_LOGS_ZH -> "logs";
            case SUB_SETTINGS_LOG_SETTINGS_ZH -> "log-settings";
            case SUB_SETTINGS_MUSIC_ZH -> "music";
            case SUB_SETTINGS_LANGUAGE_ZH -> "language";
            case SUB_SETTINGS_NUMBER_CHAIN_ZH -> "number-chain";
            default -> sub;
        };
    }

    private String canonicalDeleteSubcommand(String sub) {
        return switch (sub) {
            case SUB_DELETE_CHANNEL_ZH -> "channel";
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
                || "leave".equals(cmd)
                || "repeat".equals(cmd)
                || "volume".equals(cmd)
                || "history".equals(cmd)
                || "music".equals(cmd)
                || "playlist".equals(cmd);
    }

    private boolean isKnownPrefixCommand(String cmd) {
        return "help".equals(cmd)
                || "volume".equals(cmd)
                || "history".equals(cmd)
                || "music".equals(cmd)
                || "playlist".equals(cmd)
                || "join".equals(cmd)
                || "play".equals(cmd)
                || "skip".equals(cmd)
                || "stop".equals(cmd)
                || "leave".equals(cmd)
                || "repeat".equals(cmd);
    }
    public boolean isSlashMusicCommand(String name) {
        name = canonicalSlashName(name);
        return "join".equals(name)
                || "play".equals(name)
                || "skip".equals(name)
                || "stop".equals(name)
                || "leave".equals(name)
                || "repeat".equals(name)
                || "volume".equals(name)
                || "history".equals(name)
                || "music".equals(name)
                || "playlist".equals(name)
                || "music-panel".equals(name);
    }
    public boolean isKnownSlashCommand(String name) {
        name = canonicalSlashName(name);
        return "help".equals(name)
                || "ping".equals(name)
                || "welcome".equals(name)
                || "volume".equals(name)
                || "history".equals(name)
                || "music".equals(name)
                || "playlist".equals(name)
                || "join".equals(name)
                || "play".equals(name)
                || "skip".equals(name)
                || "stop".equals(name)
                || "leave".equals(name)
                || "music-panel".equals(name)
                || "repeat".equals(name)
                || "settings".equals(name)
                || "delete-messages".equals(name)
                || "private-room-settings".equals(name)
                || "warnings".equals(name)
                || "anti-duplicate".equals(name)
                || "honeypot-channel".equals(name)
                || "number-chain".equals(name)
                || "user-info".equals(name)
                || "role-info".equals(name)
                || "server-info".equals(name)
                || "url".equals(name)
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
        } else if ("settings".equals(command) && event.getOption("action") != null) {
            sub = canonicalSettingsSubcommand(event.getOption("action").getAsString());
        } else if ("music".equals(command) && sub != null) {
            sub = canonicalMusicSubcommand(sub);
        } else if ("playlist".equals(command) && sub != null) {
            sub = canonicalPlaylistSubcommand(sub);
        }
        if (("warnings".equals(command) || "anti-duplicate".equals(command) || "ticket".equals(command))
                && sub == null && event.getOption("action") != null) {
            sub = event.getOption("action").getAsString();
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
                .setTitle(i18n.t(lang(guild.getIdLong()), "logs.command_title"))
                .setDescription("`" + safe(commandText, 256) + "`")
                .addField(i18n.t(lang(guild.getIdLong()), "logs.command_user"), member.getAsMention() + " (`" + member.getUser().getAsTag() + "`)", false)
                .addField(i18n.t(lang(guild.getIdLong()), "logs.command_channel"), "<#" + channelId + ">", true)
                .setTimestamp(Instant.now());
        target.sendMessageEmbeds(eb.build()).queue(success -> {
        }, error -> {
        });
    }

    boolean canControlPanel(Guild guild, Member member) {
        if (member == null || member.getVoiceState() == null) {
            return false;
        }
        AudioChannel userChannel = member.getVoiceState().getChannel();
        AudioChannel botChannel = guild.getAudioManager().getConnectedChannel();
        return userChannel != null && botChannel != null && userChannel.getIdLong() == botChannel.getIdLong();
    }

    private String formatMissingPermissions(Member member, AudioChannel channel, Permission... permissions) {
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

    private void refreshAllPanelsSafely() {
        try {
            List<Long> guildIds = new ArrayList<>(panelByGuild.keySet());
            for (Long guildId : guildIds) {
                refreshPanelPeriodic(guildId);
            }
        } catch (Exception ignored) {
        }
    }

    private String panelSignature(Guild guild) {
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
        int cooldownSeconds = Math.max(0, runtimeConfig.getCommandCooldownSeconds());
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

    long acquirePanelButtonCooldown(long userId) {
        int cooldownSeconds = Math.max(0, runtimeConfig.getCommandCooldownSeconds());
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

    String detectSource(AudioTrack track) {
        String uri = track.getInfo().uri == null ? "" : track.getInfo().uri.toLowerCase();
        if (uri.contains("spotify")) {
            return "spotify";
        }
        if (uri.contains("youtube") || uri.contains("youtu.be")) {
            return "youtube";
        }
        if (uri.contains("soundcloud.com")) {
            return "soundcloud";
        }
        return "url";
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

    private String formatQueue(List<AudioTrack> queue) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(5, queue.size());
        for (int i = 0; i < max; i++) {
            AudioTrack track = queue.get(i);
            sb.append(i + 1)
                    .append(". ")
                    .append(safe(track.getInfo().title, 60))
                    .append(" (")
                    .append(formatDuration(track.getDuration()))
                    .append(")")
                    .append('\n');
        }
        if (queue.size() > max) {
            sb.append("...");
        }
        return sb.toString();
    }

    private String buildProgressBar(long positionMillis, long durationMillis) {
        if (durationMillis <= 0L) {
            return formatDuration(positionMillis) + " / --:--";
        }
        int totalSlots = 16;
        double ratio = Math.max(0d, Math.min(1d, (double) positionMillis / (double) durationMillis));
        int marker = (int) Math.round(ratio * (totalSlots - 1));
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < totalSlots; i++) {
            bar.append(i == marker ? ">" : "=");
        }
        return "[" + bar + "]\n`" + formatDuration(positionMillis) + " / " + formatDuration(durationMillis) + "`";
    }
    public String mapRepeatLabel(String lang, String mode) {
        String normalized = mode == null ? "OFF" : mode.toUpperCase();
        return switch (normalized) {
            case "SINGLE" -> i18n.t(lang, "music.repeat_single");
            case "ALL" -> i18n.t(lang, "music.repeat_all");
            default -> i18n.t(lang, "music.repeat_off");
        };
    }

    private String formatAutoplayNotice(String lang, String notice) {
        if ("NO_MATCH".equalsIgnoreCase(notice)) {
            return i18n.t(lang, "music.autoplay_notice_no_match");
        }
        if (notice.startsWith("LOAD_FAILED:")) {
            return i18n.t(lang, "music.autoplay_notice_load_failed",
                    Map.of("error", safe(mapMusicLoadError(lang, notice.substring("LOAD_FAILED:".length())), 140)));
        }
        return safe(notice, 160);
    }
    public String mapMusicLoadError(String lang, String rawError) {
        if ("SPOTIFY_RATE_LIMITED".equalsIgnoreCase(rawError)
                || "SPOTIFY_PLAYLIST_COOLDOWN".equalsIgnoreCase(rawError)) {
            return i18n.t(lang, "music.spotify_rate_limited");
        }
        if ("SPOTIFY_PERSONAL_PLAYLIST_UNSUPPORTED".equalsIgnoreCase(rawError)) {            boolean zhCn = lang != null && lang.startsWith("zh-CN");            boolean zh = lang != null && lang.startsWith("zh");            if (zhCn) {                return "This Spotify playlist cannot be resolved right now. Please use a public playlist you created, or use a track link.";            }            if (zh) {                return "This Spotify playlist cannot be resolved right now. Please use a public playlist you created, or use a track link.";            }            return "This Spotify playlist cannot be resolved right now. Use a public playlist you created, or use a track link.";        }
        return i18n.t(lang, YoutubePlaybackErrorMapper.toMessageKey(rawError));
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
    public String musicUx(String lang, String key) {
        return musicUx(lang, key, Map.of());
    }
    public String musicText(String lang, String key) {
        return musicText(lang, key, Map.of());
    }
    public String musicText(String lang, String key, Map<String, String> placeholders) {
        String fullKey = "music." + key;
        String value = i18n.t(lang, fullKey, placeholders);
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
            case "playlist_source" -> zhCn ? "\u6B4C\u5355" : (zh ? "\u6B4C\u55AE" : "Playlist");
            case "queue_added" -> zhCn ? "\u5DF2\u52A0\u5165\u961F\u5217\uFF1A`{title}`" : (zh ? "\u5DF2\u52A0\u5165\u4F47\u5217\uFF1A`{title}`" : "Queued: `{title}`");
            case "panel_autoplay" -> zhCn ? "\u81EA\u52A8\u63A8\u8350" : (zh ? "\u81EA\u52D5\u63A8\u85A6" : "Autoplay");
            case "panel_autoplay_notice" -> zhCn ? "\u81EA\u52A8\u63A8\u8350\u63D0\u793A" : (zh ? "\u81EA\u52D5\u63A8\u85A6\u63D0\u793A" : "Autoplay Notice");
            case "btn_autoplay_on" -> zhCn ? "\u81EA\u52A8\u63A8\u8350\uFF1A\u5F00\u542F" : (zh ? "\u81EA\u52D5\u63A8\u85A6\uFF1A\u958B\u555F" : "Autoplay: ON");
            case "btn_autoplay_off" -> zhCn ? "\u81EA\u52A8\u63A8\u8350\uFF1A\u5173\u95ED" : (zh ? "\u81EA\u52D5\u63A8\u85A6\uFF1A\u95DC\u9589" : "Autoplay: OFF");
            case "panel_title" -> zhCn ? "\u97F3\u4E50\u63A7\u5236\u9762\u677F" : (zh ? "\u97F3\u6A02\u63A7\u5236\u9762\u677F" : "Music Control Panel");
            case "panel_current" -> zhCn ? "\u5F53\u524D\u64AD\u653E" : (zh ? "\u76EE\u524D\u64AD\u653E" : "Now Playing");
            case "panel_channel" -> zhCn ? "\u9891\u9053" : (zh ? "\u983B\u9053" : "Channel");
            case "panel_queue" -> zhCn ? "\u961F\u5217" : (zh ? "\u4F47\u5217" : "Queue");
            case "panel_repeat" -> zhCn ? "\u5FAA\u73AF" : (zh ? "\u5FAA\u74B0" : "Repeat");
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
            case "btn_leave" -> zhCn ? "\u79BB\u5F00" : (zh ? "\u96E2\u958B" : "Leave");
            case "btn_volume_down" -> zhCn ? "\u964D\u4F4E\u97F3\u91CF" : (zh ? "\u964D\u4F4E\u97F3\u91CF" : "Volume Down");
            case "btn_volume_up" -> zhCn ? "\u589E\u52A0\u97F3\u91CF" : (zh ? "\u589E\u52A0\u97F3\u91CF" : "Volume Up");
            case "btn_repeat_single" -> zhCn ? "\u5355\u66F2\u5FAA\u73AF" : (zh ? "\u55AE\u66F2\u5FAA\u74B0" : "Repeat One");
            case "btn_repeat_all" -> zhCn ? "\u961F\u5217\u5FAA\u73AF" : (zh ? "\u4F47\u5217\u5FAA\u74B0" : "Repeat Queue");
            case "btn_repeat_off" -> zhCn ? "\u5173\u95ED\u5FAA\u73AF" : (zh ? "\u95DC\u9589\u5FAA\u74B0" : "Disable Repeat");
            case "btn_refresh" -> zhCn ? "\u5237\u65B0" : (zh ? "\u5237\u65B0" : "Refresh");
            case "btn_shuffle" -> zhCn ? "\u968F\u673A\u6253\u4E71" : (zh ? "\u96A8\u6A5F\u6253\u4E82" : "Shuffle");
            case "panel_volume" -> zhCn ? "\u97F3\u91CF" : (zh ? "\u97F3\u91CF" : "Volume");
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

    private String formatColor(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
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

    int resolveTemplateColor(long guildId, String templateType) {
        var notifications = settingsService.getNotifications(guildId);
        return switch (templateType) {
            case "member-join" -> notifications.getMemberJoinColor();
            case "member-leave" -> notifications.getMemberLeaveColor();
            default -> 0x3498DB;
        };
    }

    String boolText(String lang, boolean value) {
        return value ? i18n.t(lang, "settings.info_bool_on") : i18n.t(lang, "settings.info_bool_off");
    }

    private String moduleSwitchState(String lang, boolean enabled) {
        return boolText(lang, enabled)
                .replace("\u2705", "")
                .replace("\u274C", "")
                .replace("\u2714\uFE0F", "")
                .replace("\u2716\uFE0F", "")
                .trim();
    }

    private String moduleSwitchTextPlain(String lang, boolean enabled) {
        String state = moduleSwitchState(lang, enabled);
        return enabled ? "\uD83D\uDFE2 " + state : "\u26AA " + state;
    }

    private String moduleSwitchTextCode(String lang, boolean enabled) {
        return "`" + moduleSwitchTextPlain(lang, enabled) + "`";
    }

    private String formatTextChannel(Guild guild, Long id) {
        if (id == null) {
            return i18n.t(lang(guild.getIdLong()), "settings.info_channels_none");
        }
        TextChannel channel = guild.getTextChannelById(id);
        return channel == null ? "#" + id : channel.getAsMention() + " (" + id + ")";
    }

    private String formatTextChannelInfo(Guild guild, Long id) {
        if (id == null) {
            return i18n.t(lang(guild.getIdLong()), "settings.info_channels_none");
        }
        TextChannel channel = guild.getTextChannelById(id);
        return channel == null ? "#" + id : channel.getAsMention();
    }

    private String formatIgnoredMembersInfo(String lang, List<Long> ids) {
        return formatCompactList(lang, ids == null ? List.of() : ids.stream()
                .map(id -> "<@" + id + ">")
                .toList());
    }

    private String formatIgnoredRolesInfo(String lang, List<Long> ids) {
        return formatCompactList(lang, ids == null ? List.of() : ids.stream()
                .map(id -> "<@&" + id + ">")
                .toList());
    }

    private String formatIgnoredChannelsInfo(String lang, List<Long> ids) {
        return formatCompactList(lang, ids == null ? List.of() : ids.stream()
                .map(id -> "<#" + id + ">")
                .toList());
    }

    private String formatIgnoredPrefixesInfo(String lang, List<String> prefixes) {
        return formatCompactList(lang, prefixes == null ? List.of() : prefixes.stream()
                .map(prefix -> "`" + prefix.replace("`", "'") + "`")
                .toList());
    }

    private String formatCompactList(String lang, List<String> values) {
        if (values == null || values.isEmpty()) {
            return i18n.t(lang, "settings.info_channels_none");
        }
        int limit = Math.min(5, values.size());
        String result = String.join(", ", values.subList(0, limit));
        if (values.size() > limit) {
            result += " +" + (values.size() - limit);
        }
        return result;
    }

    private String formatTextChannelById(long guildId, Long id) {
        Guild guild = jda == null ? null : jda.getGuildById(guildId);
        if (guild == null) {
            return id == null ? i18n.t(lang(guildId), "settings.info_channels_none") : "#" + id;
        }
        return formatTextChannel(guild, id);
    }

    private String formatVoiceChannel(Guild guild, Long id) {
        if (id == null) {
            return i18n.t(lang(guild.getIdLong()), "settings.info_channels_none");
        }
        AudioChannel channel = guild.getVoiceChannelById(id);
        if (channel == null) {
            channel = guild.getStageChannelById(id);
        }
        return channel == null ? "#" + id : "<#" + id + "> (" + id + ")";
    }

    private String formatVoiceChannelInfo(Guild guild, Long id) {
        if (id == null) {
            return i18n.t(lang(guild.getIdLong()), "settings.info_channels_none");
        }
        AudioChannel channel = guild.getVoiceChannelById(id);
        if (channel == null) {
            channel = guild.getStageChannelById(id);
        }
        return channel == null ? "#" + id : "<#" + id + ">";
    }

    private String formatMemberChannelMode(String lang, Long memberJoinChannelId, Long memberLeaveChannelId) {        boolean split = memberJoinChannelId != null || memberLeaveChannelId != null;
        return split ? i18n.t(lang, "settings.member_channel_mode_split") : i18n.t(lang, "settings.member_channel_mode_same");
    }

    private String resolveTriggerCategoryWithSource(Guild guild, Long triggerVoiceChannelId) {
        if (triggerVoiceChannelId == null) {
            return i18n.t(lang(guild.getIdLong()), "settings.info_channels_none");
        }
        AudioChannel trigger = guild.getVoiceChannelById(triggerVoiceChannelId);
        if (trigger == null) {
            trigger = guild.getStageChannelById(triggerVoiceChannelId);
        }
        if (!(trigger instanceof ICategorizableChannel)) {
            return "<#" + triggerVoiceChannelId + ">";
        }
        ICategorizableChannel categorizable = (ICategorizableChannel) trigger;
        Category parent = categorizable.getParentCategory();
        if (parent == null) {
            return "<#" + triggerVoiceChannelId + "> -> " + i18n.t(lang(guild.getIdLong()), "settings.info_channels_none");
        }
        return "<#" + triggerVoiceChannelId + "> -> " + parent.getName() + " (" + parent.getId() + ")";
    }

    private String trimTemplate(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return safe(value.replace("\n", "\\n"), 180);
    }

    private String templateCompareMarkdown(String lang, String titleKey, String effective, String defaults) {
        String effectiveText = localizeTemplateForDisplay(lang, trimTemplate(effective));
        return "**" + i18n.t(lang, titleKey) + "**\n`" + effectiveText + "`";
    }

    private String localizeTemplateForDisplay(String lang, String template) {
        return template;
    }

    private String line(String lang, String key, String value) {
        String icon = keyIcon(key);
        return icon + " " + i18n.t(lang, key) + ": " + value;
    }

    private String lineLabel(String icon, String label, String value) {
        return icon + " " + label + ": " + value;
    }

    private String joinInfoBlocks(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private String joinLines(String... values) {
        return String.join("\n", values);
    }

    private String compare(String lang, String effective, String defaults) {
        return safe(effective, 160);
    }

    private boolean needsDefaultLogChannel(String action) {
        return "message-log".equals(action)
                || "command-usage-log".equals(action)
                || "channel-events-log".equals(action)
                || "role-events-log".equals(action)
                || "moderation-log".equals(action);
    }

    private String logsModuleStatusText(String lang, long guildId, String target) {
        GuildSettingsService.GuildSettings s = settingsService.getSettings(guildId);
        var logs = s.getMessageLogs();
        var n = s.getNotifications();
        String module = switch (target) {
            case "default-channel" -> i18n.t(lang, "settings.logs_menu_module_default");
            case "messages-channel" ->
                    i18n.t(lang, "settings.logs_menu_module_state", Map.of("state", boolText(lang, logs.isEnabled())));
            case "member-channel" -> i18n.t(lang, "settings.logs_menu_module_member_state",
                    Map.of("join", boolText(lang, n.isMemberJoinEnabled()), "leave", boolText(lang, n.isMemberLeaveEnabled())));
            case "voice-channel" ->
                    i18n.t(lang, "settings.logs_menu_module_state", Map.of("state", boolText(lang, n.isVoiceLogEnabled())));
            case "command-usage-channel" ->
                    i18n.t(lang, "settings.logs_menu_module_state", Map.of("state", boolText(lang, logs.isCommandUsageLogEnabled())));
            case "channel-events-channel" ->
                    i18n.t(lang, "settings.logs_menu_module_state", Map.of("state", boolText(lang, logs.isChannelLifecycleLogEnabled())));
            case "role-events-channel" ->
                    i18n.t(lang, "settings.logs_menu_module_state", Map.of("state", boolText(lang, logs.isRoleLogEnabled())));
            case "moderation-channel" ->
                    i18n.t(lang, "settings.logs_menu_module_state", Map.of("state", boolText(lang, logs.isModerationLogEnabled())));
            default -> i18n.t(lang, "settings.logs_menu_module_none");
        };

        String channel = switch (target) {
            case "default-channel" ->
                    i18n.t(lang, "settings.logs_menu_channel_state", Map.of("state", setStateText(lang, logs.getChannelId() != null)));
            case "messages-channel" ->
                    i18n.t(lang, "settings.logs_menu_channel_state", Map.of("state", setStateText(lang, logs.getMessageLogChannelId() != null)));
            case "member-channel" -> {
                boolean split = n.getMemberJoinChannelId() != null || n.getMemberLeaveChannelId() != null;
                if (split) {
                    yield i18n.t(lang, "settings.logs_menu_channel_member_split",
                            Map.of("join", setStateText(lang, n.getMemberJoinChannelId() != null),
                                    "leave", setStateText(lang, n.getMemberLeaveChannelId() != null)));
                }
                yield i18n.t(lang, "settings.logs_menu_channel_state",
                        Map.of("state", setStateText(lang, n.getMemberChannelId() != null)));
            }
            case "voice-channel" ->
                    i18n.t(lang, "settings.logs_menu_channel_state", Map.of("state", setStateText(lang, n.getVoiceChannelId() != null)));
            case "command-usage-channel" ->
                    i18n.t(lang, "settings.logs_menu_channel_state", Map.of("state", setStateText(lang, logs.getCommandUsageChannelId() != null)));
            case "channel-events-channel" ->
                    i18n.t(lang, "settings.logs_menu_channel_state", Map.of("state", setStateText(lang, logs.getChannelLifecycleChannelId() != null)));
            case "role-events-channel" ->
                    i18n.t(lang, "settings.logs_menu_channel_state", Map.of("state", setStateText(lang, logs.getRoleLogChannelId() != null)));
            case "moderation-channel" ->
                    i18n.t(lang, "settings.logs_menu_channel_state", Map.of("state", setStateText(lang, logs.getModerationLogChannelId() != null)));
            default -> i18n.t(lang, "settings.logs_menu_channel_state", Map.of("state", setStateText(lang, false)));
        };

        return i18n.t(lang, "settings.logs_menu_status_format", Map.of("module", module, "channel", channel));
    }

    private String setStateText(String lang, boolean set) {
        return i18n.t(lang, set ? "settings.logs_menu_channel_set" : "settings.logs_menu_channel_unset");
    }

    private String infoSectionTitle(String lang, String key) {
        return sectionIcon(key) + " " + i18n.t(lang, key);
    }

    private String sectionIcon(String key) {
        return switch (key) {
            case "settings.info_section_overview" -> "\uD83D\uDCCC";
            case "settings.info_notifications" -> "\uD83D\uDD14";
            case "settings.info_notification_templates" -> "\uD83E\uDDE9";
            case "settings.info_message_logs" -> "\uD83D\uDDD2\uFE0F";
            case "settings.info_music" -> "\uD83C\uDFB5";
            case "settings.info_private_room" -> "\uD83C\uDFE0";
            case "settings.info_number_chain" -> "\u0031\u20E3";
            case "settings.info_module" -> "\uD83E\uDDF0";
            default -> "\uD83D\uDCC4";
        };
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
        String normalized = i18n.normalizeLanguage(languageCode);
        String display = i18n.getAvailableLanguages().get(normalized);
        if (display == null || display.isBlank()) {
            return normalized;
        }
        return display + " (" + normalized + ")";
    }

    private String ignoredRolesInfoLabel(String lang) {
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "\u5ffd\u7565\u7684\u8eab\u5206\u7ec4";
        }
        if (lang != null && lang.toLowerCase().startsWith("zh")) {
            return "\u5ffd\u7565\u7684\u8eab\u5206\u7d44";
        }
        return "Ignored Roles";
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
            return "\u63a5\u9f99\u6210\u5458 Top 5";
        }
        if (lang != null && lang.toLowerCase().startsWith("zh")) {
            return "\u63a5\u9f8d\u6210\u54e1 Top 5";
        }
        return "Top 5 Contributors";
    }
    public String formatNumberChainTopContributors(Guild guild, String lang) {
        List<ModerationService.NumberChainContributor> contributors =
                moderationService.getTopNumberChainContributors(guild.getIdLong(), 5);
        if (contributors.isEmpty()) {
            return i18n.t(lang, "settings.info_channels_none");
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < contributors.size(); i++) {
            ModerationService.NumberChainContributor contributor = contributors.get(i);
            lines.add((i + 1) + ". <@" + contributor.getUserId() + "> - " + contributor.getCount());
        }
        return String.join("\n", lines);
    }

    @FunctionalInterface
    interface TextSink {
        void send(String text);
    }

    static class PanelRef {
        final long channelId;
        final long messageId;

        PanelRef(long channelId, long messageId) {
            this.channelId = channelId;
            this.messageId = messageId;
        }
    }

    private static class DeleteRequest {
        private final long requestUserId;
        private final Long channelId;
        private final Long targetUserId;
        private final Duration lookback;
        private final Integer amount;
        private final Instant expiresAt;

        private DeleteRequest(long requestUserId, Long channelId, Long targetUserId, Duration lookback, Integer amount, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.channelId = channelId;
            this.targetUserId = targetUserId;
            this.lookback = lookback;
            this.amount = amount;
            this.expiresAt = expiresAt;
        }
    }

    private static class WarningActionRequest {
        private final long requestUserId;
        private final long guildId;
        private final String action;
        private final long targetUserId;
        private final int amount;
        private final Instant expiresAt;

        private WarningActionRequest(long requestUserId, long guildId, String action, long targetUserId, int amount, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.action = action;
            this.targetUserId = targetUserId;
            this.amount = amount;
            this.expiresAt = expiresAt;
        }
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

    private static class ToggleResult {
        private final String key;
        private final boolean value;

        private ToggleResult(String key, boolean value) {
            this.key = key;
            this.value = value;
        }
    }

    private static class ResetRequest {
        private final long requestUserId;
        private final long guildId;
        private final Instant expiresAt;

        private ResetRequest(long requestUserId, long guildId, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.expiresAt = expiresAt;
        }
    }

    private static class ResetConfirmRequest {
        private final long requestUserId;
        private final long guildId;
        private final String selection;
        private final Instant expiresAt;

        private ResetConfirmRequest(long requestUserId, long guildId, String selection, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.selection = selection;
            this.expiresAt = expiresAt;
        }
    }

    private static class RoomSettingsRequest {
        private final long requestUserId;
        private final long guildId;
        private final long roomChannelId;
        private final Instant expiresAt;

        private RoomSettingsRequest(long requestUserId, long guildId, long roomChannelId, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.roomChannelId = roomChannelId;
            this.expiresAt = expiresAt;
        }
    }
}










