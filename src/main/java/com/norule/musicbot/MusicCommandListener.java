package com.norule.musicbot;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MusicCommandListener extends ListenerAdapter {
    private static final String CMD_DELETE_ZH = "\u522a\u9664\u8a0a\u606f";
    private static final String CMD_ROOM_SETTINGS_ZH = "\u5305\u5ec2\u8a2d\u5b9a";
    private static final String HELP_SELECT_ID = "help:select";
    private static final String HELP_BUTTON_PREFIX = "help:cat:";
    private static final String SETTINGS_INFO_SELECT_ID = "settings:info:select";
    private static final String SETTINGS_TEMPLATE_SELECT_PREFIX = "settings:template:select:";
    private static final String SETTINGS_MODULE_SELECT_PREFIX = "settings:module:select:";
    private static final String SETTINGS_LOGS_SELECT_PREFIX = "settings:logs:select:";
    private static final String SETTINGS_LOGS_CHANNEL_PREFIX = "settings:logs:channel:";
    private static final String SETTINGS_LOGS_MEMBER_MODE_PREFIX = "settings:logs:membermode:";
    private static final String SETTINGS_LOGS_MEMBER_SPLIT_PREFIX = "settings:logs:membersplit:";
    private static final String SETTINGS_MUSIC_SELECT_PREFIX = "settings:music:select:";
    private static final String SETTINGS_MUSIC_CHANNEL_PREFIX = "settings:music:channel:";
    private static final String SETTINGS_MUSIC_MODAL_PREFIX = "settings:music:modal:";
    private static final String SETTINGS_RESET_SELECT_PREFIX = "settings:reset:";
    private static final String SETTINGS_RESET_CONFIRM_PREFIX = "settings:reset:confirm:";
    private static final String SETTINGS_RESET_CANCEL_PREFIX = "settings:reset:cancel:";
    private static final String ROOM_SETTINGS_MENU_PREFIX = "room:settings:";
    private static final String ROOM_LIMIT_MODAL_PREFIX = "room:limit:";
    private static final String ROOM_RENAME_MODAL_PREFIX = "room:rename:";
    private static final String PLAY_PICK_PREFIX = "play:pick:";
    private static final String DELETE_CONFIRM_PREFIX = "delete:confirm:";
    private static final String DELETE_CANCEL_PREFIX = "delete:cancel:";
    private static final String TEMPLATE_MODAL_PREFIX = "settings:template:";
    private static final String PANEL_PLAY_PAUSE = "panel:playpause";
    private static final String PANEL_SKIP = "panel:skip";
    private static final String PANEL_STOP = "panel:stop";
    private static final String PANEL_LEAVE = "panel:leave";
    private static final String PANEL_REPEAT_SINGLE = "panel:repeat:single";
    private static final String PANEL_REPEAT_ALL = "panel:repeat:all";
    private static final String PANEL_REPEAT_OFF = "panel:repeat:off";
    private static final String PANEL_AUTOPLAY_TOGGLE = "panel:autoplay:toggle";

    private final MusicPlayerService musicService;
    private volatile BotConfig config;
    private final GuildSettingsService settingsService;
    private volatile I18nService i18n;

    private final Map<Long, PanelRef> panelByGuild = new ConcurrentHashMap<>();
    private final Map<String, SearchRequest> searchRequests = new ConcurrentHashMap<>();
    private final Map<String, DeleteRequest> deleteRequests = new ConcurrentHashMap<>();
    private final Map<String, Long> commandCooldowns = new ConcurrentHashMap<>();
    private final Map<String, ResetRequest> resetRequests = new ConcurrentHashMap<>();
    private final Map<String, ResetConfirmRequest> resetConfirmRequests = new ConcurrentHashMap<>();
    private final Map<String, RoomSettingsRequest> roomSettingRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> templateMenuRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> moduleMenuRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> logsMenuRequests = new ConcurrentHashMap<>();
    private final Map<String, MenuRequest> musicMenuRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile JDA jda;
    private final Map<Long, Long> panelLastRefreshAt = new ConcurrentHashMap<>();
    private final Map<Long, String> panelLastSignature = new ConcurrentHashMap<>();
    private final Set<Long> panelRefreshingGuilds = ConcurrentHashMap.newKeySet();
    private static final long PANEL_PERIODIC_REFRESH_MS = 30000L;

    public MusicCommandListener(MusicPlayerService musicService, BotConfig config, GuildSettingsService settingsService) {
        this.musicService = musicService;
        this.config = config;
        this.settingsService = settingsService;
        this.i18n = I18nService.load(java.nio.file.Path.of(config.getLanguageDir()), config.getDefaultLanguage());
        this.musicService.setAutoplayEnabledChecker(guildId -> settingsService.getMusic(guildId).isAutoplayEnabled());
        this.scheduler.scheduleAtFixedRate(this::refreshAllPanelsSafely, 5, 30, TimeUnit.SECONDS);
    }

    public void reloadRuntimeConfig(BotConfig newConfig) {
        if (newConfig == null) {
            return;
        }
        this.config = newConfig;
        this.i18n = I18nService.load(java.nio.file.Path.of(newConfig.getLanguageDir()), newConfig.getDefaultLanguage());
        syncCommands();
    }

    @Override
    public void onReady(ReadyEvent event) {
        this.jda = event.getJDA();
        List<CommandData> commands = buildCommands();
        event.getJDA().updateCommands().addCommands(commands).queue(
                success -> System.out.println("[NoRule] Registered global slash commands: " + success.size()),
                failure -> System.out.println("[NoRule] Failed to register global slash commands: " + failure.getMessage())
        );
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        // Global command registration is handled in onReady/syncCommands.
    }

    private void syncCommands() {
        JDA current = this.jda;
        if (current == null) {
            return;
        }
        List<CommandData> commands = buildCommands();
        current.updateCommands().addCommands(commands).queue(
                success -> System.out.println("[NoRule] Synced global slash commands: " + success.size()),
                failure -> System.out.println("[NoRule] Failed to sync global slash commands: " + failure.getMessage())
        );
    }

    private void clearGlobalCommands(JDA jda) {
        jda.retrieveCommands().queue(globalCommands -> {
            if (globalCommands == null || globalCommands.isEmpty()) {
                return;
            }
            // Use guild commands as source of truth; clear stale global commands.
            jda.updateCommands().queue(
                    success -> System.out.println("[NoRule] Cleared stale global slash commands: " + globalCommands.size()),
                    error -> System.out.println("[NoRule] Failed to clear global slash commands: " + error.getMessage())
            );
        }, error -> System.out.println("[NoRule] Failed to retrieve global commands: " + error.getMessage()));
    }

    private void updateGuildCommandsAndPermissions(Guild guild, List<CommandData> commands) {
        guild.updateCommands().addCommands(commands).queue(
                success -> {
                    System.out.println("[NoRule] Registered slash commands for guild " + guild.getId() + ": " + success.size());
                    // Default permissions are already set during command build.
                    // Skip per-command edit calls to avoid unnecessary Missing Access noise.
                },
                failure -> System.out.println("[NoRule] Failed to register slash commands for guild "
                        + guild.getId() + ": " + failure.getMessage())
        );
    }

    private void enforceCommandPermissions(Guild guild) {
        Set<String> publicCommands = new HashSet<>(Set.of(
                "help", "join", "play", "skip", "stop", "leave", "music-panel", "repeat",
                "private-room-settings", CMD_ROOM_SETTINGS_ZH
        ));
        Set<String> adminCommands = Set.of("settings");
        Set<String> modCommands = Set.of("delete-messages", CMD_DELETE_ZH);

        guild.retrieveCommands().queue(commands -> {
            for (Command command : commands) {
                String name = command.getName();
                DefaultMemberPermissions target = null;
                if (publicCommands.contains(name)) {
                    target = DefaultMemberPermissions.ENABLED;
                } else if (adminCommands.contains(name)) {
                    target = DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER);
                } else if (modCommands.contains(name)) {
                    target = DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE);
                }
                if (target != null) {
                    command.editCommand().setDefaultPermissions(target).queue(
                            ignored -> {
                            },
                            error -> System.out.println("[NoRule] Failed to set command permissions for "
                                    + command.getName() + " in guild " + guild.getId() + ": " + error.getMessage())
                    );
                }
            }
        }, error -> System.out.println("[NoRule] Failed to retrieve guild commands for permission sync in guild "
                + guild.getId() + ": " + error.getMessage()));
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }
        String raw = event.getMessage().getContentRaw();
        if (!raw.startsWith(config.getPrefix())) {
            return;
        }
        String[] split = raw.substring(config.getPrefix().length()).trim().split("\\s+", 2);
        String cmd = split.length > 0 ? split[0].toLowerCase() : "";
        String arg = split.length > 1 ? split[1].trim() : "";
        Guild guild = event.getGuild();
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
            case "help" -> sendHelp(event.getChannel().asTextChannel(), guild, lang);
            case "join" -> handleJoin(guild, event.getMember(), text -> event.getChannel().sendMessage(text).queue());
            case "play" -> directPlay(
                    guild,
                    event.getMember(),
                    arg,
                    text -> event.getChannel().sendMessage(text).queue(),
                    event.getChannel().asTextChannel()
            );
            case "skip" -> handleSkip(guild, text -> event.getChannel().sendMessage(text).queue());
            case "stop" -> handleStop(guild, text -> event.getChannel().sendMessage(text).queue());
            case "leave" -> handleLeave(guild, text -> event.getChannel().sendMessage(text).queue());
            case "repeat" -> {
                setRepeat(guild, arg);
                event.getChannel().sendMessage(mapRepeatLabel(lang, musicService.getRepeatMode(guild))).queue();
            }
            default -> event.getChannel().sendMessage(i18n.t(lang, "general.unknown_command")).queue();
        }
        if (isKnownPrefixCommand(cmd)) {
            logCommandUsage(guild, event.getMember(), config.getPrefix() + cmd + (arg.isBlank() ? "" : " " + arg), event.getChannel().getIdLong());
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || event.getGuild() == null) {
            event.reply("Guild only.").setEphemeral(true).queue();
            return;
        }

        String lang = lang(event.getGuild().getIdLong());
        long remaining = acquireCooldown(event.getUser().getIdLong());
        if (remaining > 0) {
            event.reply(i18n.t(lang, "general.command_cooldown",
                            Map.of("seconds", String.valueOf(toCooldownSeconds(remaining)))))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if (isSlashMusicCommand(event.getName()) && !isMusicCommandChannelAllowed(event.getGuild(), event.getChannel().getIdLong())) {
            event.reply(i18n.t(lang, "music.command_channel_restricted")).setEphemeral(true).queue();
            return;
        }
        if (isKnownSlashCommand(event.getName())) {
            logCommandUsage(event.getGuild(), event.getMember(), "/" + buildSlashRoute(event), event.getChannel().getIdLong());
        }
        switch (event.getName()) {
            case "help" -> event.replyEmbeds(helpEmbed(event.getGuild(), lang, "general").build())
                    .addComponents(ActionRow.of(helpMenu(lang)), ActionRow.of(helpButtons(lang, "general")))
                    .setEphemeral(true)
                    .queue();
            case "join" -> {
                event.deferReply().queue();
                handleJoin(event.getGuild(), event.getMember(), text -> event.getHook().sendMessage(text).queue());
            }
            case "play" -> handlePlaySlash(event, lang);
            case "skip" -> {
                event.deferReply().queue();
                handleSkip(event.getGuild(), text -> event.getHook().sendMessage(text).queue());
            }
            case "stop" -> {
                event.deferReply().queue();
                handleStop(event.getGuild(), text -> event.getHook().sendMessage(text).queue());
            }
            case "leave" -> {
                event.deferReply().queue();
                handleLeave(event.getGuild(), text -> event.getHook().sendMessage(text).queue());
            }
            case "music-panel" -> {
                event.deferReply().queue();
                if (event.getChannelType() != ChannelType.TEXT) {
                    event.getHook().sendMessage("Music panel can only be created in a text channel.").setEphemeral(true).queue();
                    return;
                }
                TextChannel textChannel = event.getChannel().asTextChannel();
                if (!textChannel.canTalk()) {
                    event.getHook().sendMessage("I cannot send messages in this channel. Check bot permissions.").setEphemeral(true).queue();
                    return;
                }
                createPanelMessageWithFeedback(event.getGuild(), textChannel, lang,
                        () -> event.getHook().sendMessage(i18n.t(lang, "music.panel_title")).setEphemeral(true).queue(),
                        error -> event.getHook().sendMessage("Failed to create/update panel: " + error).setEphemeral(true).queue());
            }
            case "repeat" -> {
                String mode = Objects.requireNonNull(event.getOption("mode")).getAsString();
                setRepeat(event.getGuild(), mode);
                refreshPanel(event.getGuild().getIdLong());
                event.reply(mapRepeatLabel(lang, musicService.getRepeatMode(event.getGuild()))).setEphemeral(true).queue();
            }
            case "settings" -> handleSettings(event, lang);
            case "private-room-settings", CMD_ROOM_SETTINGS_ZH -> handlePrivateRoomSettingsCommand(event, lang);
            case CMD_DELETE_ZH, "delete-messages" -> handleDeleteSlash(event, lang);
            default -> event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = lang(event.getGuild().getIdLong());
        String componentId = event.getComponentId();

        if (HELP_SELECT_ID.equals(componentId)) {
            String value = event.getValues().isEmpty() ? "general" : event.getValues().get(0);
            event.editMessageEmbeds(helpEmbed(event.getGuild(), lang, value).build())
                    .setComponents(ActionRow.of(helpMenu(lang)), ActionRow.of(helpButtons(lang, value)))
                    .queue();
            return;
        }

        if (SETTINGS_INFO_SELECT_ID.equals(componentId)) {
            String section = event.getValues().isEmpty() ? "overview" : event.getValues().get(0);
            event.editMessageEmbeds(settingsInfoEmbed(event.getGuild(), lang, section).build())
                    .setComponents(ActionRow.of(settingsInfoMenu(lang, section)))
                    .queue();
            return;
        }

        if (componentId.startsWith(SETTINGS_TEMPLATE_SELECT_PREFIX)) {
            handleTemplateMenuSelect(event, lang);
            return;
        }

        if (componentId.startsWith(SETTINGS_MODULE_SELECT_PREFIX)) {
            handleModuleMenuSelect(event, lang);
            return;
        }

        if (componentId.startsWith(SETTINGS_LOGS_SELECT_PREFIX)) {
            handleLogsMenuSelect(event, lang);
            return;
        }

        if (componentId.startsWith(SETTINGS_LOGS_MEMBER_MODE_PREFIX)) {
            handleLogsMemberModeSelect(event, lang);
            return;
        }

        if (componentId.startsWith(SETTINGS_LOGS_MEMBER_SPLIT_PREFIX)) {
            handleLogsMemberSplitSelect(event, lang);
            return;
        }

        if (componentId.startsWith(SETTINGS_MUSIC_SELECT_PREFIX)) {
            handleMusicMenuSelect(event, lang);
            return;
        }

        if (componentId.startsWith(SETTINGS_RESET_SELECT_PREFIX)) {
            handleSettingsResetSelect(event, lang);
            return;
        }

        if (componentId.startsWith(ROOM_SETTINGS_MENU_PREFIX)) {
            handleRoomSettingsSelect(event, lang);
            return;
        }

        if (componentId.startsWith(PLAY_PICK_PREFIX)) {
            String token = componentId.substring(PLAY_PICK_PREFIX.length());
            SearchRequest request = searchRequests.remove(token);
            if (request == null) {
                event.reply(i18n.t(lang, "music.search_expired")).setEphemeral(true).queue();
                return;
            }
            if (Instant.now().isAfter(request.expiresAt)) {
                event.editMessage(i18n.t(lang, "music.search_expired")).setComponents(List.of()).queue();
                return;
            }
            if (event.getUser().getIdLong() != request.requestUserId) {
                event.reply(i18n.t(lang, "delete.only_requester")).setEphemeral(true).queue();
                return;
            }
            int index = Integer.parseInt(event.getValues().get(0));
            if (index < 0 || index >= request.results.size()) {
                event.reply(i18n.t(lang, "music.not_found", Map.of("query", request.query))).setEphemeral(true).queue();
                return;
            }
            AudioTrack picked = request.results.get(index);
            Member member = event.getMember();
            if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
                event.reply(i18n.t(lang, "music.join_first")).setEphemeral(true).queue();
                return;
            }
            AudioChannel memberChannel = member.getVoiceState().getChannel();
            AudioChannel botChannel = event.getGuild().getAudioManager().getConnectedChannel();
            if (botChannel != null && botChannel.getIdLong() != memberChannel.getIdLong()) {
                event.reply(i18n.t(lang, "music.join_bot_voice_channel",
                                Map.of("channel", botChannel.getAsMention())))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            if (botChannel == null) {
                musicService.joinChannel(event.getGuild(), memberChannel);
            }
            musicService.rememberCommandChannel(event.getGuild().getIdLong(), request.channelId);
            String identifier = picked.getInfo().uri != null ? picked.getInfo().uri : picked.getInfo().title;
            String sourceLabel = detectSource(picked);
            musicService.queueTrackByIdentifier(
                    event.getGuild(),
                    identifier,
                    sourceLabel,
                    ignored -> refreshPanel(event.getGuild().getIdLong()),
                    event.getUser().getIdLong(),
                    event.getUser().getName()
            );
            TextChannel panelChannel = event.getGuild().getTextChannelById(request.channelId);
            if (panelChannel != null) {
                recreatePanelForChannel(event.getGuild(), panelChannel, lang);
            }
            event.editMessage(i18n.t(lang, "music.queue_added", Map.of("title", picked.getInfo().title)))
                    .setComponents(List.of())
                    .queue();
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = lang(event.getGuild().getIdLong());
        if (event.getModalId().startsWith(ROOM_LIMIT_MODAL_PREFIX) || event.getModalId().startsWith(ROOM_RENAME_MODAL_PREFIX)) {
            handleRoomSettingsModal(event);
            return;
        }
        if (event.getModalId().startsWith(SETTINGS_MUSIC_MODAL_PREFIX)) {
            handleMusicMenuModal(event, lang);
            return;
        }
        if (!event.getModalId().startsWith(TEMPLATE_MODAL_PREFIX)) {
            return;
        }
        if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName()))).setEphemeral(true).queue();
            return;
        }

        String templateType = event.getModalId().substring(TEMPLATE_MODAL_PREFIX.length());
        String template = Objects.requireNonNull(event.getValue("template")).getAsString();
        Integer color = null;
        if ("member-join".equals(templateType) || "member-leave".equals(templateType)) {
            String colorRaw = event.getValue("color") == null ? "" : event.getValue("color").getAsString();
            if (colorRaw != null && !colorRaw.isBlank()) {
                color = parseHexColor(colorRaw);
                if (color == null) {
                    event.reply(i18n.t(lang, "settings.template_color_invalid")).setEphemeral(true).queue();
                    return;
                }
            }
        }

        String displayKey = applyTemplate(event.getGuild().getIdLong(), templateType, template, color, lang);
        if (displayKey == null) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }

        int previewColor = resolveTemplateColor(event.getGuild().getIdLong(), templateType);
        String preview = renderTemplatePreview(template, event.getGuild().getName());
        EmbedBuilder previewEmbed = new EmbedBuilder()
                .setTitle(i18n.t(lang, "settings.template_preview_title"))
                .setDescription(preview)
                .setColor(previewColor)
                .addField(i18n.t(lang, "settings.template_updated"), displayKey, false);
        event.replyEmbeds(previewEmbed.build()).setEphemeral(true).queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = lang(event.getGuild().getIdLong());
        String id = event.getComponentId();

        if (id.startsWith(DELETE_CONFIRM_PREFIX) || id.startsWith(DELETE_CANCEL_PREFIX)) {
            handleDeleteButtons(event, lang);
            return;
        }
        if (id.startsWith(SETTINGS_RESET_CONFIRM_PREFIX) || id.startsWith(SETTINGS_RESET_CANCEL_PREFIX)) {
            handleSettingsResetConfirmButtons(event, lang);
            return;
        }
        if (id.startsWith(HELP_BUTTON_PREFIX)) {
            String category = id.substring(HELP_BUTTON_PREFIX.length());
            event.editMessageEmbeds(helpEmbed(event.getGuild(), lang, category).build())
                    .setComponents(ActionRow.of(helpMenu(lang)), ActionRow.of(helpButtons(lang, category)))
                    .queue();
            return;
        }

        if (isPanelButton(id)) {
            PanelRef active = panelByGuild.get(event.getGuild().getIdLong());
            if (active == null
                    || active.channelId != event.getChannel().getIdLong()
                    || active.messageId != event.getMessageIdLong()) {
                event.reply(i18n.t(lang, "music.panel_stale")).setEphemeral(true).queue();
                return;
            }
            if (!canControlPanel(event.getGuild(), event.getMember())) {
                event.reply(i18n.t(lang, "music.panel_same_voice_only")).setEphemeral(true).queue();
                return;
            }
        }

        switch (id) {
            case PANEL_PLAY_PAUSE -> {
                musicService.togglePause(event.getGuild());
                event.deferEdit().queue();
                refreshPanelMessage(event.getGuild(), event.getChannel().asTextChannel(), event.getMessageIdLong());
            }
            case PANEL_SKIP -> {
                musicService.skip(event.getGuild());
                event.deferEdit().queue();
                refreshPanelMessage(event.getGuild(), event.getChannel().asTextChannel(), event.getMessageIdLong());
            }
            case PANEL_STOP -> {
                musicService.stop(event.getGuild());
                event.deferEdit().queue();
                refreshPanelMessage(event.getGuild(), event.getChannel().asTextChannel(), event.getMessageIdLong());
            }
            case PANEL_LEAVE -> {
                musicService.stop(event.getGuild());
                musicService.leaveChannel(event.getGuild());
                event.deferEdit().queue();
                refreshPanelMessage(event.getGuild(), event.getChannel().asTextChannel(), event.getMessageIdLong());
                String operator = event.getMember() == null ? event.getUser().getAsMention() : event.getMember().getAsMention();
                event.getChannel().asTextChannel()
                        .sendMessage(i18n.t(lang, "music.left_by_operator", Map.of("user", operator)))
                        .queue(success -> {
                        }, error -> {
                        });
            }
            case PANEL_REPEAT_SINGLE -> {
                setRepeat(event.getGuild(), "SINGLE");
                event.deferEdit().queue();
                refreshPanelMessage(event.getGuild(), event.getChannel().asTextChannel(), event.getMessageIdLong());
            }
            case PANEL_REPEAT_ALL -> {
                setRepeat(event.getGuild(), "ALL");
                event.deferEdit().queue();
                refreshPanelMessage(event.getGuild(), event.getChannel().asTextChannel(), event.getMessageIdLong());
            }
            case PANEL_REPEAT_OFF -> {
                setRepeat(event.getGuild(), "OFF");
                event.deferEdit().queue();
                refreshPanelMessage(event.getGuild(), event.getChannel().asTextChannel(), event.getMessageIdLong());
            }
            case PANEL_AUTOPLAY_TOGGLE -> {
                toggleAutoplay(event.getGuild().getIdLong());
                event.deferEdit().queue();
                refreshPanelMessage(event.getGuild(), event.getChannel().asTextChannel(), event.getMessageIdLong());
            }
            default -> {
            }
        }
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = lang(event.getGuild().getIdLong());
        String componentId = event.getComponentId();
        if (componentId.startsWith(SETTINGS_LOGS_CHANNEL_PREFIX)) {
            handleLogsChannelSelect(event, lang);
            return;
        }
        if (componentId.startsWith(SETTINGS_MUSIC_CHANNEL_PREFIX)) {
            handleMusicChannelSelect(event, lang);
        }
    }

    private void handlePlaySlash(SlashCommandInteractionEvent event, String lang) {
        String query = Objects.requireNonNull(event.getOption("query")).getAsString();
        if (looksLikeUrl(query)) {
            event.deferReply().queue();
            TextChannel panelChannel = event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null;
            directPlay(event.getGuild(), event.getMember(), query, text -> event.getHook().sendMessage(text).queue(), panelChannel);
            return;
        }

        event.deferReply(true).queue();
        musicService.searchTopTracks(query, 10, results -> {
            if (results.isEmpty()) {
                event.getHook().sendMessage(i18n.t(lang, "music.not_found", Map.of("query", query))).queue();
                return;
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            SearchRequest request = new SearchRequest(
                    event.getUser().getIdLong(),
                    event.getChannel().asTextChannel().getIdLong(),
                    query,
                    results,
                    Instant.now().plusSeconds(30)
            );
            searchRequests.put(token, request);
            event.getHook().sendMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(52, 152, 219))
                            .setTitle(i18n.t(lang, "music.search_title"))
                            .setDescription(i18n.t(lang, "music.search_desc", Map.of("seconds", "30")))
                            .build())
                    .setComponents(ActionRow.of(buildSearchMenu(token, results)))
                    .queue(message -> scheduler.schedule(() -> expireSearchMenu(token, event.getGuild().getIdLong(), message.getIdLong()),
                            30, TimeUnit.SECONDS));
        }, error -> event.getHook().sendMessage(i18n.t(lang, "music.load_failed", Map.of("error", error))).queue());
    }

    private void handleSettings(SlashCommandInteractionEvent event, String lang) {
        if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName()))).setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        String sub = Objects.requireNonNull(event.getSubcommandName());
        String group = event.getSubcommandGroup();
        String route = group == null ? sub : group + ":" + sub;
        switch (route) {
            case "info" -> {
                event.replyEmbeds(settingsInfoEmbed(event.getGuild(), lang, "overview").build())
                        .addComponents(ActionRow.of(settingsInfoMenu(lang, "overview")))
                        .setEphemeral(true)
                        .queue();
            }
            case "reload" -> {
                settingsService.reload(guildId);
                event.replyEmbeds(new EmbedBuilder()
                                .setColor(new Color(46, 204, 113))
                                .setTitle(i18n.t(lang, "settings.info_title"))
                                .setDescription("✅ " + i18n.t(lang, "settings.reload_done"))
                                .build())
                        .setEphemeral(true)
                        .queue();
            }
            case "language" -> {
                String code = Objects.requireNonNull(event.getOption("code")).getAsString();
                if (!i18n.hasLanguage(code)) {
                    event.reply(i18n.t(lang, "settings.language_invalid", Map.of("language", code))).setEphemeral(true).queue();
                    return;
                }
                String normalized = i18n.normalizeLanguage(code);
                settingsService.updateSettings(guildId, s -> s.withLanguage(normalized));
                event.reply(i18n.t(normalized, "settings.language_updated", Map.of("language", normalized))).setEphemeral(true).queue();
            }
            case "template" -> openTemplateMenu(event, lang);
            case "module" -> openModuleMenu(event, lang);
            case "reset" -> openSettingsResetMenu(event, lang);
            case "logs" -> openLogsMenu(event, lang);
            case "music" -> openMusicMenu(event, lang);
            default -> event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }

    private void openTemplateMenu(SlashCommandInteractionEvent event, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        templateMenuRequests.put(token, new MenuRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(46, 204, 113))
                        .setTitle(i18n.t(lang, "settings.template_menu_title"))
                        .setDescription(i18n.t(lang, "settings.template_menu_desc"))
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

    private void handleTemplateMenuSelect(StringSelectInteractionEvent event, String lang) {
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
                    "{user} {username} {guild} {id} {tag} {isBot} {createdAt} {accountAgeDays}",
                    true,
                    settingsService.getNotifications(event.getGuild().getIdLong()).getMemberJoinColor(),
                    lang
            )).queue();
            case "member-leave" -> event.replyModal(buildTemplateModal(
                    "member-leave",
                    "{user} {username} {guild} {id} {tag} {isBot} {createdAt} {accountAgeDays}",
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

    private void openModuleMenu(SlashCommandInteractionEvent event, String lang) {
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
        return StringSelectMenu.create(SETTINGS_MODULE_SELECT_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "settings.module_menu_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.key_notifications_enabled"), "notifications-enable")
                                .withDescription(boolText(lang, s.getNotifications().isEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_messageLogs_enabled"), "message-log")
                                .withDescription(boolText(lang, s.getMessageLogs().isEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_notifications_memberJoinEnabled"), "member-join")
                                .withDescription(boolText(lang, s.getNotifications().isMemberJoinEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_notifications_memberLeaveEnabled"), "member-leave")
                                .withDescription(boolText(lang, s.getNotifications().isMemberLeaveEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_notifications_voiceLogEnabled"), "voice-log")
                                .withDescription(boolText(lang, s.getNotifications().isVoiceLogEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.info_key_log_command_usage"), "command-usage-log")
                                .withDescription(boolText(lang, s.getMessageLogs().isCommandUsageLogEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.info_key_log_channel_lifecycle"), "channel-events-log")
                                .withDescription(boolText(lang, s.getMessageLogs().isChannelLifecycleLogEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.info_key_log_role"), "role-events-log")
                                .withDescription(boolText(lang, s.getMessageLogs().isRoleLogEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.info_key_log_moderation"), "moderation-log")
                                .withDescription(boolText(lang, s.getMessageLogs().isModerationLogEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_music_autoLeaveEnabled"), "music-auto-leave")
                                .withDescription(boolText(lang, s.getMusic().isAutoLeaveEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_music_autoplayEnabled"), "music-autoplay")
                                .withDescription(boolText(lang, s.getMusic().isAutoplayEnabled())),
                        SelectOption.of(i18n.t(lang, "settings.key_privateRoom_enabled"), "private-room-enable")
                                .withDescription(boolText(lang, s.getPrivateRoom().isEnabled()))
                )
                .build();
    }

    private EmbedBuilder moduleMenuEmbed(Guild guild, String lang, String changedText) {
        GuildSettingsService.GuildSettings s = settingsService.getSettings(guild.getIdLong());
        String body = String.join("\n\n",
                moduleLine(lang, "settings.key_notifications_enabled", s.getNotifications().isEnabled()),
                moduleLine(lang, "settings.key_messageLogs_enabled", s.getMessageLogs().isEnabled()),
                moduleLine(lang, "settings.key_notifications_memberJoinEnabled", s.getNotifications().isMemberJoinEnabled()),
                moduleLine(lang, "settings.key_notifications_memberLeaveEnabled", s.getNotifications().isMemberLeaveEnabled()),
                moduleLine(lang, "settings.key_notifications_voiceLogEnabled", s.getNotifications().isVoiceLogEnabled()),
                moduleLine(lang, "settings.info_key_log_command_usage", s.getMessageLogs().isCommandUsageLogEnabled()),
                moduleLine(lang, "settings.info_key_log_channel_lifecycle", s.getMessageLogs().isChannelLifecycleLogEnabled()),
                moduleLine(lang, "settings.info_key_log_role", s.getMessageLogs().isRoleLogEnabled()),
                moduleLine(lang, "settings.info_key_log_moderation", s.getMessageLogs().isModerationLogEnabled()),
                moduleLine(lang, "settings.key_music_autoLeaveEnabled", s.getMusic().isAutoLeaveEnabled()),
                moduleLine(lang, "settings.key_music_autoplayEnabled", s.getMusic().isAutoplayEnabled()),
                moduleLine(lang, "settings.key_privateRoom_enabled", s.getPrivateRoom().isEnabled())
        );
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(52, 152, 219))
                .setTitle(i18n.t(lang, "settings.module_menu_title"))
                .setDescription(i18n.t(lang, "settings.module_menu_desc"))
                .addField(i18n.t(lang, "settings.info_module"), body, false);
        if (changedText != null && !changedText.isBlank()) {
            eb.addField(i18n.t(lang, "settings.template_updated"), changedText, false);
        }
        return eb;
    }

    private String moduleLine(String lang, String key, boolean value) {
        return keyIcon(key) + " " + i18n.t(lang, key) + "\n> " + i18n.t(lang, "settings.status_label") + ": " + boolText(lang, value);
    }

    private String quotedSettingLine(String lang, String key, String labelKey, String value) {
        return keyIcon(key) + " " + i18n.t(lang, key) + "\n> " + i18n.t(lang, labelKey) + ": " + value;
    }

    private void handleModuleMenuSelect(StringSelectInteractionEvent event, String lang) {
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
        String changed = i18n.t(lang, "general.settings_saved", Map.of("key", keyText, "value", boolText(lang, result.value)));
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
            case "private-room-enable" -> {
                boolean value = !settingsService.getPrivateRoom(guildId).isEnabled();
                settingsService.updateSettings(guildId, s -> s.withPrivateRoom(s.getPrivateRoom().withEnabled(value)));
                return new ToggleResult("settings.key_privateRoom_enabled", value);
            }
            default -> {
                return null;
            }
        }
    }

    private void openLogsMenu(SlashCommandInteractionEvent event, String lang) {
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

    private void handleLogsMenuSelect(StringSelectInteractionEvent event, String lang) {
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
        BotConfig.Notifications n = settingsService.getNotifications(guildId);
        boolean split = n.getMemberJoinChannelId() != null || n.getMemberLeaveChannelId() != null;
        return StringSelectMenu.create(SETTINGS_LOGS_MEMBER_MODE_PREFIX + token)
                .setPlaceholder(i18n.t(lang, "settings.logs_member_mode_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.logs_member_mode_shared"), "member-channel-shared")
                                .withDefault(!split),
                        SelectOption.of(i18n.t(lang, "settings.logs_member_mode_split"), "member-channel-split")
                                .withDefault(split)
                )
                .build();
    }

    private StringSelectMenu settingsMemberSplitMenu(String token, long guildId, String lang) {
        BotConfig.Notifications n = settingsService.getNotifications(guildId);
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

    private void handleLogsMemberModeSelect(StringSelectInteractionEvent event, String lang) {
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

    private void handleLogsMemberSplitSelect(StringSelectInteractionEvent event, String lang) {
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

    private void handleLogsChannelSelect(EntitySelectInteractionEvent event, String lang) {
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

    private void openMusicMenu(SlashCommandInteractionEvent event, String lang) {
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
        BotConfig.Music music = settingsService.getMusic(guildId);
        BotConfig.PrivateRoom room = settingsService.getPrivateRoom(guildId);
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
        BotConfig.Music music = settingsService.getMusic(guildId);
        BotConfig.PrivateRoom room = settingsService.getPrivateRoom(guildId);
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

    private void handleMusicMenuSelect(StringSelectInteractionEvent event, String lang) {
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

    private void handleMusicMenuModal(ModalInteractionEvent event, String lang) {
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

    private void handleMusicChannelSelect(EntitySelectInteractionEvent event, String lang) {
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

    private void handleDeleteSlash(SlashCommandInteractionEvent event, String lang) {
        if (!has(event.getMember(), Permission.MESSAGE_MANAGE)) {
            event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MESSAGE_MANAGE.getName()))).setEphemeral(true).queue();
            return;
        }

        var amountOption = event.getOption("amount");
        int amount = amountOption == null ? 99 : (int) amountOption.getAsLong();
        if (amount < 1 || amount > 99) {
            event.reply(i18n.t(lang, "delete.amount_range")).setEphemeral(true).queue();
            return;
        }

        String sub = Objects.requireNonNull(event.getSubcommandName());
        TextChannel channel;
        Long targetUserId = null;
        String scope;
        StringBuilder extraNotice = new StringBuilder();
        if ("channel".equals(sub)) {
            var channelOption = event.getOption("channel");
            if (channelOption == null) {
                if (event.getChannelType() != ChannelType.TEXT) {
                    event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                    return;
                }
                channel = event.getChannel().asTextChannel();
                extraNotice.append(i18n.t(lang, "delete.default_channel_notice", Map.of("channel", channel.getAsMention())));
            } else {
                if (channelOption.getAsChannel().getType() != ChannelType.TEXT) {
                    event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                    return;
                }
                channel = channelOption.getAsChannel().asTextChannel();
            }
            scope = channel.getAsMention();
        } else {
            channel = event.getChannel().asTextChannel();
            targetUserId = Objects.requireNonNull(event.getOption("user")).getAsUser().getIdLong();
            scope = Objects.requireNonNull(event.getOption("user")).getAsUser().getAsMention();
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        deleteRequests.put(token, new DeleteRequest(event.getUser().getIdLong(), channel.getIdLong(), targetUserId, amount));

                event.replyEmbeds(new EmbedBuilder()
                        .setTitle(i18n.t(lang, "delete.confirm_title"))
                        .setDescription(i18n.t(lang, "delete.confirm_body", Map.of("count", String.valueOf(amount), "scope", scope))
                                + (amountOption == null ? "\n" + i18n.t(lang, "delete.default_amount_notice", Map.of("count", "99")) : "")
                                + (extraNotice.isEmpty() ? "" : "\n" + extraNotice))
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

    private void handleDeleteButtons(ButtonInteractionEvent event, String lang) {
        String id = event.getComponentId();
        String token = id.substring(id.lastIndexOf(':') + 1);
        DeleteRequest req = deleteRequests.get(token);
        if (req == null) {
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
                            TextChannel channel = guild.getTextChannelById(req.channelId);
                            if (channel == null) {
                                deleteRequests.remove(token);
                                event.getHook().editOriginal(i18n.t(lang, "general.invalid_channel")).queue();
                                return;
                            }
                            List<Message> targets = findMessagesForDeletion(channel, req.targetUserId, req.amount, 25);
                            if (targets.isEmpty()) {
                                deleteRequests.remove(token);
                                event.getHook().editOriginal(i18n.t(lang, "delete.no_target")).queue();
                                return;
                            }
                            int deleted = performDelete(channel, targets);
                            deleteRequests.remove(token);
                            event.getHook().editOriginal(i18n.t(lang, "delete.processed", Map.of("count", String.valueOf(deleted)))).queue();
                        } catch (Exception ex) {
                            deleteRequests.remove(token);
                            event.getHook().editOriginal(i18n.t(lang, "delete.failed")).queue();
                        }
                    });
                },
                failure -> event.reply(i18n.t(lang, "delete.failed")).setEphemeral(true).queue()
        );
    }

    private void directPlay(Guild guild, Member member, String query, TextSink sink, TextChannel panelChannel) {
        String lang = lang(guild.getIdLong());
        if (query == null || query.isBlank()) {
            sink.send(i18n.t(lang, "music.not_found", Map.of("query", "")));
            return;
        }
        if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
            sink.send(i18n.t(lang, "music.join_first"));
            return;
        }

        AudioChannel memberChannel = member.getVoiceState().getChannel();
        AudioChannel botConnected = guild.getAudioManager().getConnectedChannel();
        if (botConnected != null && botConnected.getIdLong() != memberChannel.getIdLong()) {
            sink.send(i18n.t(lang, "music.join_bot_voice_channel",
                    Map.of("channel", botConnected.getAsMention())));
            return;
        }
        if (!guild.getSelfMember().hasPermission(memberChannel, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)) {
            String missing = formatMissingPermissions(guild.getSelfMember(), memberChannel, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK);
            sink.send(i18n.t(lang, "general.missing_permissions", Map.of("permissions", missing)));
            return;
        }
        if (botConnected == null) {
            musicService.joinChannel(guild, memberChannel);
        }
        if (panelChannel != null) {
            musicService.rememberCommandChannel(guild.getIdLong(), panelChannel.getIdLong());
        }
        musicService.setGuildStateListener(guild.getIdLong(), () -> refreshPanel(guild.getIdLong()));
        musicService.loadAndPlay(guild, response -> {
            if ("NO_MATCH".equals(response)) {
                sink.send(i18n.t(lang, "music.not_found", Map.of("query", query)));
            } else if (response.startsWith("LOAD_FAILED:")) {
                sink.send(i18n.t(lang, "music.load_failed", Map.of("error", response.substring("LOAD_FAILED:".length()))));
            } else {
                sink.send(i18n.t(lang, "music.queue_added", Map.of("title", response)));
                if (panelChannel != null) {
                    recreatePanelForChannel(guild, panelChannel, lang);
                }
            }
            refreshPanel(guild.getIdLong());
        }, query, member.getIdLong(), member.getEffectiveName());
    }

    private void recreatePanelForChannel(Guild guild, TextChannel channel, String lang) {
        PanelRef old = panelByGuild.remove(guild.getIdLong());
        if (old != null) {
            TextChannel oldChannel = guild.getTextChannelById(old.channelId);
            if (oldChannel != null) {
                oldChannel.deleteMessageById(old.messageId).queue(success -> {
                }, error -> {
                });
            }
        }
        createPanelMessageWithFeedback(guild, channel, lang, () -> {
        }, error -> {
        });
    }

    private void handleJoin(Guild guild, Member member, TextSink sink) {
        String lang = lang(guild.getIdLong());
        if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
            sink.send(i18n.t(lang, "music.join_first"));
            return;
        }
        AudioChannel voice = member.getVoiceState().getChannel();
        AudioChannel botConnected = guild.getAudioManager().getConnectedChannel();
        if (botConnected != null && botConnected.getIdLong() != voice.getIdLong()) {
            sink.send(i18n.t(lang, "music.not_same_voice_channel"));
            return;
        }
        if (!guild.getSelfMember().hasPermission(voice, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)) {
            String missing = formatMissingPermissions(guild.getSelfMember(), voice, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK);
            sink.send(i18n.t(lang, "general.missing_permissions", Map.of("permissions", missing)));
            return;
        }
        musicService.joinChannel(guild, voice);
        musicService.setGuildStateListener(guild.getIdLong(), () -> refreshPanel(guild.getIdLong()));
        sink.send(i18n.t(lang, "music.joined", Map.of("channel", voice.getAsMention())));
    }

    private void handleSkip(Guild guild, TextSink sink) {
        String lang = lang(guild.getIdLong());
        if (guild.getAudioManager().getConnectedChannel() == null) {
            sink.send(i18n.t(lang, "music.not_connected"));
            return;
        }
        musicService.skip(guild);
        sink.send(i18n.t(lang, "music.skipped"));
        refreshPanel(guild.getIdLong());
    }

    private void handleStop(Guild guild, TextSink sink) {
        String lang = lang(guild.getIdLong());
        if (guild.getAudioManager().getConnectedChannel() == null) {
            sink.send(i18n.t(lang, "music.not_connected"));
            return;
        }
        musicService.stop(guild);
        sink.send(i18n.t(lang, "music.stopped"));
        refreshPanel(guild.getIdLong());
    }

    private void handleLeave(Guild guild, TextSink sink) {
        String lang = lang(guild.getIdLong());
        if (guild.getAudioManager().getConnectedChannel() == null) {
            sink.send(i18n.t(lang, "music.not_connected"));
            return;
        }
        musicService.stop(guild);
        musicService.leaveChannel(guild);
        sink.send(i18n.t(lang, "music.left"));
        refreshPanel(guild.getIdLong());
    }

    private void setRepeat(Guild guild, String input) {
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

    private void toggleAutoplay(long guildId) {
        settingsService.updateSettings(guildId, s -> s.withMusic(s.getMusic().withAutoplayEnabled(!s.getMusic().isAutoplayEnabled())));
        musicService.clearAutoplayNotice(guildId);
    }

    private void sendHelp(TextChannel channel, Guild guild, String lang) {
        channel.sendMessageEmbeds(helpEmbed(guild, lang, "general").build())
                .setComponents(ActionRow.of(helpMenu(lang)), ActionRow.of(helpButtons(lang, "general")))
                .queue();
    }

    private EmbedBuilder helpEmbed(Guild guild, String lang, String category) {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(new Color(52, 152, 219));
        eb.setTitle("NoRule Help Center");
        String botDesc = config.getBotProfile().getDescription();
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
            default -> eb.addField(i18n.t(lang, "help.category_general"), i18n.t(lang, "help.content_general"), false);
        }
        eb.addField(i18n.t(lang, "help.tip_title"), i18n.t(lang, "help.tip_body"), false);
        return eb;
    }

    private StringSelectMenu helpMenu(String lang) {
        return StringSelectMenu.create(HELP_SELECT_ID)
                .setPlaceholder(i18n.t(lang, "help.select_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "help.category_general"), "general"),
                        SelectOption.of(i18n.t(lang, "help.category_music"), "music"),
                        SelectOption.of(i18n.t(lang, "help.category_settings"), "settings"),
                        SelectOption.of(i18n.t(lang, "help.category_moderation"), "moderation"),
                        SelectOption.of(i18n.t(lang, "help.category_private_room"), "private-room")
                )
                .build();
    }

    private List<Button> helpButtons(String lang, String selectedCategory) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(categoryButton(lang, "general", selectedCategory, i18n.t(lang, "help.category_general")));
        buttons.add(categoryButton(lang, "music", selectedCategory, i18n.t(lang, "help.category_music")));
        buttons.add(categoryButton(lang, "settings", selectedCategory, i18n.t(lang, "help.category_settings")));
        buttons.add(categoryButton(lang, "moderation", selectedCategory, i18n.t(lang, "help.category_moderation")));
        buttons.add(categoryButton(lang, "private-room", selectedCategory, i18n.t(lang, "help.category_private_room")));
        return buttons;
    }

    private Button categoryButton(String lang, String category, String selectedCategory, String label) {
        if (category.equals(selectedCategory)) {
            return Button.success(HELP_BUTTON_PREFIX + category, label).asDisabled();
        }
        return Button.secondary(HELP_BUTTON_PREFIX + category, label);
    }

    private EmbedBuilder settingsInfoEmbed(Guild guild, String lang, String section) {
        long guildId = guild.getIdLong();
        String currentSection = section == null || section.isBlank() ? "overview" : section;
        GuildSettingsService.GuildSettings settings = settingsService.getSettings(guildId);
        BotConfig.Notifications n = settings.getNotifications();
        BotConfig.Notifications nDef = config.getNotifications();
        BotConfig.MessageLogs logs = settings.getMessageLogs();
        BotConfig.MessageLogs logsDef = config.getMessageLogs();
        BotConfig.Music music = settings.getMusic();
        BotConfig.Music musicDef = config.getMusic();
        BotConfig.PrivateRoom room = settings.getPrivateRoom();
        BotConfig.PrivateRoom roomDef = config.getPrivateRoom();

        String notifications = joinLines(
                line(lang, "settings.info_key_enabled", compare(lang, boolText(lang, n.isEnabled()), boolText(lang, nDef.isEnabled()))),
                line(lang, "settings.info_key_member_join_enabled", compare(lang, boolText(lang, n.isMemberJoinEnabled()), boolText(lang, nDef.isMemberJoinEnabled()))),
                line(lang, "settings.info_key_member_leave_enabled", compare(lang, boolText(lang, n.isMemberLeaveEnabled()), boolText(lang, nDef.isMemberLeaveEnabled()))),
                line(lang, "settings.info_key_voice_log_enabled", compare(lang, boolText(lang, n.isVoiceLogEnabled()), boolText(lang, nDef.isVoiceLogEnabled()))),
                line(lang, "settings.info_key_member_channel_mode", compare(lang,
                        formatMemberChannelMode(lang, n),
                        formatMemberChannelMode(lang, nDef))),
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
        String notificationTemplates = joinLines(
                templateCompareMarkdown(lang, "settings.info_key_member_join_template", n.getMemberJoinMessage(), nDef.getMemberJoinMessage()),
                line(lang, "settings.info_key_member_join_color", compare(lang, formatColor(n.getMemberJoinColor()), formatColor(nDef.getMemberJoinColor()))),
                templateCompareMarkdown(lang, "settings.info_key_member_leave_template", n.getMemberLeaveMessage(), nDef.getMemberLeaveMessage()),
                line(lang, "settings.info_key_member_leave_color", compare(lang, formatColor(n.getMemberLeaveColor()), formatColor(nDef.getMemberLeaveColor()))),
                templateCompareMarkdown(lang, "settings.info_key_voice_join_template", n.getVoiceJoinMessage(), nDef.getVoiceJoinMessage()),
                templateCompareMarkdown(lang, "settings.info_key_voice_leave_template", n.getVoiceLeaveMessage(), nDef.getVoiceLeaveMessage()),
                templateCompareMarkdown(lang, "settings.info_key_voice_move_template", n.getVoiceMoveMessage(), nDef.getVoiceMoveMessage())
        );
        String messageLogs = joinLines(
                line(lang, "settings.info_key_enabled", compare(lang, boolText(lang, logs.isEnabled()), boolText(lang, logsDef.isEnabled()))),
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
                line(lang, "settings.info_key_log_role", compare(lang, boolText(lang, logs.isRoleLogEnabled()), boolText(lang, logsDef.isRoleLogEnabled()))),
                line(lang, "settings.info_key_log_channel_lifecycle", compare(lang, boolText(lang, logs.isChannelLifecycleLogEnabled()), boolText(lang, logsDef.isChannelLifecycleLogEnabled()))),
                line(lang, "settings.info_key_log_moderation", compare(lang, boolText(lang, logs.isModerationLogEnabled()), boolText(lang, logsDef.isModerationLogEnabled()))),
                line(lang, "settings.info_key_log_command_usage", compare(lang, boolText(lang, logs.isCommandUsageLogEnabled()), boolText(lang, logsDef.isCommandUsageLogEnabled())))
        );
        String musicInfo = joinLines(
                line(lang, "settings.info_key_auto_leave_enabled", compare(lang, boolText(lang, music.isAutoLeaveEnabled()), boolText(lang, musicDef.isAutoLeaveEnabled()))),
                line(lang, "settings.info_key_auto_leave_minutes", compare(lang, String.valueOf(music.getAutoLeaveMinutes()), String.valueOf(musicDef.getAutoLeaveMinutes()))),
                line(lang, "settings.info_key_autoplay_enabled", compare(lang, boolText(lang, isAutoplayEnabled(guildId)), boolText(lang, true))),
                line(lang, "settings.info_key_default_repeat_mode", compare(lang, music.getDefaultRepeatMode().name(), musicDef.getDefaultRepeatMode().name())),
                line(lang, "settings.info_key_music_command_channel", compare(lang,
                        formatTextChannelInfo(guild, music.getCommandChannelId()),
                        formatTextChannelInfo(guild, musicDef.getCommandChannelId())))
        );
        String privateRoom = joinLines(
                line(lang, "settings.info_key_enabled", compare(lang, boolText(lang, room.isEnabled()), boolText(lang, roomDef.isEnabled()))),
                line(lang, "settings.info_key_trigger_channel", compare(lang,
                        formatVoiceChannelInfo(guild, room.getTriggerVoiceChannelId()),
                        formatVoiceChannelInfo(guild, roomDef.getTriggerVoiceChannelId()))),
                line(lang, "settings.info_key_category_auto", compare(lang,
                        resolveTriggerCategoryWithSource(guild, room.getTriggerVoiceChannelId()),
                        resolveTriggerCategoryWithSource(guild, roomDef.getTriggerVoiceChannelId()))),
                line(lang, "settings.info_key_user_limit", compare(lang, String.valueOf(room.getUserLimit()), String.valueOf(roomDef.getUserLimit())))
        );
        String moduleInfo = joinLines(
                line(lang, "settings.key_notifications_enabled", boolText(lang, n.isEnabled())),
                line(lang, "settings.key_messageLogs_enabled", boolText(lang, logs.isEnabled())),
                line(lang, "settings.key_notifications_memberJoinEnabled", boolText(lang, n.isMemberJoinEnabled())),
                line(lang, "settings.key_notifications_memberLeaveEnabled", boolText(lang, n.isMemberLeaveEnabled())),
                line(lang, "settings.key_notifications_voiceLogEnabled", boolText(lang, n.isVoiceLogEnabled())),
                line(lang, "settings.info_key_log_command_usage", boolText(lang, logs.isCommandUsageLogEnabled())),
                line(lang, "settings.info_key_log_channel_lifecycle", boolText(lang, logs.isChannelLifecycleLogEnabled())),
                line(lang, "settings.info_key_log_role", boolText(lang, logs.isRoleLogEnabled())),
                line(lang, "settings.info_key_log_moderation", boolText(lang, logs.isModerationLogEnabled())),
                line(lang, "settings.key_music_autoLeaveEnabled", boolText(lang, music.isAutoLeaveEnabled())),
                line(lang, "settings.key_music_autoplayEnabled", boolText(lang, music.isAutoplayEnabled())),
                line(lang, "settings.key_privateRoom_enabled", boolText(lang, room.isEnabled()))
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
            case "module" -> eb.addField(infoSectionTitle(lang, "settings.info_module"), moduleInfo, false);
            default -> {
                String overviewSummary = joinLines(
                        line(lang, "settings.info_language", compare(lang, settings.getLanguage(), config.getDefaultLanguage())),
                        line(lang, "settings.info_key_enabled", compare(lang, boolText(lang, n.isEnabled()), boolText(lang, nDef.isEnabled()))),
                        line(lang, "settings.key_messageLogs_enabled", compare(lang, boolText(lang, logs.isEnabled()), boolText(lang, logsDef.isEnabled()))),
                        line(lang, "settings.info_key_auto_leave_enabled", compare(lang, boolText(lang, music.isAutoLeaveEnabled()), boolText(lang, musicDef.isAutoLeaveEnabled()))),
                        line(lang, "settings.info_key_autoplay_enabled", compare(lang, boolText(lang, isAutoplayEnabled(guildId)), boolText(lang, musicDef.isAutoplayEnabled()))),
                        line(lang, "settings.key_privateRoom_enabled", compare(lang, boolText(lang, room.isEnabled()), boolText(lang, roomDef.isEnabled())))
                );
                eb.addField(infoSectionTitle(lang, "settings.info_section_overview"),
                        overviewSummary,
                        false);
                eb.addField(infoSectionTitle(lang, "settings.info_notifications"), notifications, false);
                eb.addField(infoSectionTitle(lang, "settings.info_message_logs"), messageLogs, false);
                eb.addField(infoSectionTitle(lang, "settings.info_music"), musicInfo, false);
            }
        }
        return eb;
    }

    private StringSelectMenu settingsInfoMenu(String lang, String selected) {
        return StringSelectMenu.create(SETTINGS_INFO_SELECT_ID)
                .setPlaceholder(i18n.t(lang, "settings.info_select_placeholder"))
                .addOptions(
                        SelectOption.of(i18n.t(lang, "settings.info_section_overview"), "overview").withDefault("overview".equals(selected)),
                        SelectOption.of(i18n.t(lang, "settings.info_notifications"), "notifications").withDefault("notifications".equals(selected)),
                        SelectOption.of(i18n.t(lang, "settings.info_notification_templates"), "templates").withDefault("templates".equals(selected)),
                        SelectOption.of(i18n.t(lang, "settings.info_message_logs"), "logs").withDefault("logs".equals(selected)),
                        SelectOption.of(i18n.t(lang, "settings.info_music"), "music").withDefault("music".equals(selected)),
                        SelectOption.of(i18n.t(lang, "settings.info_private_room"), "private-room").withDefault("private-room".equals(selected)),
                        SelectOption.of(i18n.t(lang, "settings.info_module"), "module").withDefault("module".equals(selected))
                )
                .build();
    }

    private StringSelectMenu buildSearchMenu(String token, List<AudioTrack> tracks) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(PLAY_PICK_PREFIX + token)
                .setPlaceholder("Select one track (30s)");
        for (int i = 0; i < tracks.size() && i < 10; i++) {
            AudioTrack track = tracks.get(i);
            String source = detectSource(track);
            String duration = formatDuration(track.getDuration());
            String desc = safe(source + " | " + duration + " | " + track.getInfo().author, 100);
            menu.addOption(safe(track.getInfo().title, 100), String.valueOf(i), desc);
        }
        return menu.build();
    }

    private void expireSearchMenu(String token, long guildId, long messageId) {
        SearchRequest request = searchRequests.remove(token);
        if (request == null || jda == null) {
            return;
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            return;
        }
        String lang = lang(guildId);
        TextChannel channel = guild.getTextChannelById(request.channelId);
        if (channel == null) {
            return;
        }
        channel.editMessageById(messageId, i18n.t(lang, "music.search_timeout"))
                .setComponents(List.of())
                .queue(success -> {
                }, error -> {
                });
    }

    private void openSettingsResetMenu(SlashCommandInteractionEvent event, String lang) {
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
                        SelectOption.of(i18n.t(lang, "settings.reset_option_all"), "all")
                )
                .build();
    }

    private void handleSettingsResetSelect(StringSelectInteractionEvent event, String lang) {
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

    private void handleSettingsResetConfirmButtons(ButtonInteractionEvent event, String lang) {
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
                            .setDescription("ℹ️ " + i18n.t(lang, "settings.reset_cancelled"))
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
                        .setDescription("✅ " + i18n.t(lang, "settings.reset_done",
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
                || "all".equals(selection);
    }

    private boolean applyResetSelection(long guildId, String selection) {
        switch (selection) {
            case "language" -> settingsService.updateSettings(guildId, s -> s.withLanguage(config.getDefaultLanguage()));
            case "notifications" -> settingsService.updateSettings(guildId, s -> s.withNotifications(config.getNotifications()));
            case "message-logs" -> settingsService.updateSettings(guildId, s -> s.withMessageLogs(config.getMessageLogs()));
            case "music" -> settingsService.updateSettings(guildId, s -> s.withMusic(config.getMusic()));
            case "private-room" -> settingsService.updateSettings(guildId, s -> s.withPrivateRoom(config.getPrivateRoom()));
            case "all" -> settingsService.updateSettings(guildId, s -> new GuildSettingsService.GuildSettings(
                    config.getDefaultLanguage(),
                    config.getNotifications(),
                    config.getMessageLogs(),
                    config.getMusic(),
                    config.getPrivateRoom()
            ));
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handlePrivateRoomSettingsCommand(SlashCommandInteractionEvent event, String lang) {
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
                        SelectOption.of(i18n.t(lang, "room_settings.option_rename"), "rename")
                )
                .build();
    }

    private EmbedBuilder privateRoomSettingsEmbed(VoiceChannel room, String lang) {
        boolean locked = isRoomLocked(room);
        return new EmbedBuilder()
                .setColor(new Color(155, 89, 182))
                .setTitle(i18n.t(lang, "room_settings.title"))
                .setDescription(i18n.t(lang, "room_settings.desc"))
                .addField(i18n.t(lang, "room_settings.field_channel"), room.getAsMention(), true)
                .addField(i18n.t(lang, "room_settings.field_name"), room.getName(), true)
                .addField(i18n.t(lang, "room_settings.field_limit"), room.getUserLimit() <= 0 ? i18n.t(lang, "room_settings.unlimited") : String.valueOf(room.getUserLimit()), true)
                .addField(i18n.t(lang, "room_settings.field_lock"), locked ? i18n.t(lang, "settings.info_bool_on") : i18n.t(lang, "settings.info_bool_off"), true);
    }

    private void handleRoomSettingsSelect(StringSelectInteractionEvent event, String lang) {
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
            default -> event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
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

    private void handleRoomSettingsModal(ModalInteractionEvent event) {
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
            int applied = limit;
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

    private String applyTemplate(long guildId, String templateType, String template, Integer color, String lang) {
        switch (templateType) {
            case "member-join" -> {
                settingsService.updateSettings(guildId, s -> {
                    BotConfig.Notifications notifications = s.getNotifications().withMemberJoinMessage(template);
                    if (color != null) {
                        notifications = notifications.withMemberJoinColor(color);
                    }
                    return s.withNotifications(notifications);
                });
                return i18n.t(lang, "settings.info_key_member_join_template");
            }
            case "member-leave" -> {
                settingsService.updateSettings(guildId, s -> {
                    BotConfig.Notifications notifications = s.getNotifications().withMemberLeaveMessage(template);
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

    private String renderTemplatePreview(String template, String guildName) {
        return template
                .replace("{user}", "@NoRuleUser")
                .replace("{username}", "NoRuleUser")
                .replace("{guild}", guildName)
                .replace("{id}", "123456789012345678")
                .replace("{tag}", "NoRuleUser#0001")
                .replace("{isBot}", "false")
                .replace("{createdAt}", "2024-01-01 12:00:00 UTC")
                .replace("{accountAgeDays}", "999")
                .replace("{channel}", "General Voice")
                .replace("{from}", "Lobby")
                .replace("{to}", "Gaming");
    }

    private void createPanelMessageWithFeedback(Guild guild, TextChannel channel, String lang, Runnable onSuccess, java.util.function.Consumer<String> onError) {
        EmbedBuilder panel = panelEmbed(guild, lang);
        channel.sendMessageEmbeds(panel.build())
                .setComponents(panelRows(lang, guild.getIdLong()))
                .queue(message -> {
                    panelByGuild.put(guild.getIdLong(), new PanelRef(channel.getIdLong(), message.getIdLong()));
                    panelLastSignature.put(guild.getIdLong(), panelSignature(guild));
                    panelLastRefreshAt.put(guild.getIdLong(), System.currentTimeMillis());
                    musicService.setGuildStateListener(guild.getIdLong(), () -> refreshPanel(guild.getIdLong()));
                    onSuccess.run();
                }, error -> onError.accept(error.getMessage()));
    }

    private void refreshPanel(long guildId) {
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

    private void refreshPanelMessage(Guild guild, TextChannel channel, long messageId) {
        long guildId = guild.getIdLong();
        String signature = panelSignature(guild);
        if (signature.equals(panelLastSignature.get(guildId))) {
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

    private boolean isPanelButton(String componentId) {
        return PANEL_PLAY_PAUSE.equals(componentId)
                || PANEL_SKIP.equals(componentId)
                || PANEL_STOP.equals(componentId)
                || PANEL_LEAVE.equals(componentId)
                || PANEL_REPEAT_SINGLE.equals(componentId)
                || PANEL_REPEAT_ALL.equals(componentId)
                || PANEL_REPEAT_OFF.equals(componentId)
                || PANEL_AUTOPLAY_TOGGLE.equals(componentId);
    }

    private EmbedBuilder panelEmbed(Guild guild, String lang) {
        long guildId = guild.getIdLong();
        String current = musicService.getCurrentTitle(guild);
        long duration = musicService.getCurrentDurationMillis(guild);
        long position = musicService.getCurrentPositionMillis(guild);
        String progress = current == null ? i18n.t(lang, "music.panel_none") : buildProgressBar(position, duration);
        String requester = current == null ? i18n.t(lang, "music.panel_none") : musicService.getCurrentRequesterDisplay(guild);
        String artwork = musicService.getCurrentArtworkUrl(guild);
        String state = current == null ? i18n.t(lang, "music.panel_idle") : (musicService.isPaused(guild)
                ? i18n.t(lang, "music.panel_paused") : i18n.t(lang, "music.panel_playing"));
        List<AudioTrack> queue = musicService.getQueueSnapshot(guild);
        String queueText = queue.isEmpty() ? i18n.t(lang, "music.panel_none") : formatQueue(queue);
        String connected = guild.getAudioManager().getConnectedChannel() == null
                ? i18n.t(lang, "music.panel_none")
                : "<#" + guild.getAudioManager().getConnectedChannel().getId() + ">";
        String source = musicService.getCurrentSource(guild);
        if (source == null || source.isBlank()) {
            source = i18n.t(lang, "music.panel_none");
        }
        String autoplayState = isAutoplayEnabled(guildId) ? i18n.t(lang, "music.autoplay_on") : i18n.t(lang, "music.autoplay_off");
        String autoplayNotice = musicService.getAutoplayNotice(guildId);
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(new Color(22, 160, 133))
                .setTitle("\uD83C\uDFB5 " + i18n.t(lang, "music.panel_title"))
                .setDescription("**" + i18n.t(lang, "music.panel_state") + "**: " + state
                        + " | **" + i18n.t(lang, "music.panel_repeat") + "**: " + mapRepeatLabel(lang, musicService.getRepeatMode(guild))
                        + "\n**" + i18n.t(lang, "music.panel_channel") + "**: " + connected + " | **" + i18n.t(lang, "music.panel_queue") + "**: " + queue.size()
                        + " | **AutoPlay**: " + autoplayState)
                .addField(i18n.t(lang, "music.panel_current"), current == null ? i18n.t(lang, "music.panel_none") : ("`" + current + "`"), false)
                .addField(i18n.t(lang, "music.panel_requester"), requester, true)
                .addField(i18n.t(lang, "music.panel_source"), source, true)
                .addField(i18n.t(lang, "music.panel_progress"), progress, false)
                .addField(i18n.t(lang, "music.panel_queue"), queueText, false)
                .setTimestamp(Instant.now());
        if (autoplayNotice != null && !autoplayNotice.isBlank()) {
            builder.addField(i18n.t(lang, "music.panel_autoplay_notice"), formatAutoplayNotice(lang, autoplayNotice), false);
        }
        if (artwork != null && !artwork.isBlank()) {
            builder.setThumbnail(artwork);
        }
        return builder;
    }

    private List<Button> panelButtons(String lang, long guildId) {
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.primary(PANEL_PLAY_PAUSE, i18n.t(lang, "music.btn_play_pause")));
        buttons.add(Button.primary(PANEL_SKIP, i18n.t(lang, "music.btn_skip")));
        buttons.add(Button.danger(PANEL_STOP, i18n.t(lang, "music.btn_stop")));
        buttons.add(Button.secondary(PANEL_LEAVE, i18n.t(lang, "music.btn_leave")));
        buttons.add(Button.success(PANEL_REPEAT_SINGLE, i18n.t(lang, "music.btn_repeat_single")));
        buttons.add(Button.success(PANEL_REPEAT_ALL, i18n.t(lang, "music.btn_repeat_all")));
        buttons.add(Button.secondary(PANEL_REPEAT_OFF, i18n.t(lang, "music.btn_repeat_off")));
        buttons.add(isAutoplayEnabled(guildId)
                ? Button.success(PANEL_AUTOPLAY_TOGGLE, i18n.t(lang, "music.btn_autoplay_on"))
                : Button.secondary(PANEL_AUTOPLAY_TOGGLE, i18n.t(lang, "music.btn_autoplay_off")));
        return buttons;
    }

    private List<ActionRow> panelRows(String lang, long guildId) {
        List<Button> buttons = panelButtons(lang, guildId);
        return List.of(
                ActionRow.of(buttons.subList(0, 4)),
                ActionRow.of(buttons.subList(4, 8))
        );
    }

    private List<Message> findMessagesForDeletion(TextChannel channel, Long targetUserId, int amount, int maxPages) {
        List<Message> matched = new ArrayList<>();
        MessageHistory history = channel.getHistory();
        List<Message> page = history.retrievePast(100).complete();
        for (int i = 0; i < maxPages && !page.isEmpty() && matched.size() < amount; i++) {
            for (Message message : page) {
                if (message.getAuthor().isBot()) {
                    continue;
                }
                if (message.getTimeCreated().toInstant().isBefore(Instant.now().minus(Duration.ofDays(14)))) {
                    continue;
                }
                if (targetUserId != null && message.getAuthor().getIdLong() != targetUserId) {
                    continue;
                }
                matched.add(message);
                if (matched.size() >= amount) {
                    break;
                }
            }
            if (matched.size() >= amount) {
                break;
            }
            String before = page.get(page.size() - 1).getId();
            page = MessageHistory.getHistoryBefore(channel, before).limit(100).complete().getRetrievedHistory();
        }
        return matched;
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

    private List<CommandData> buildCommands() {
        List<CommandData> commands = new ArrayList<>();
        commands.add(Commands.slash("help", cd("help", "Show bot help"))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("join", cd("join", "Join your voice channel"))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("play", cd("play", "Play music"))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.STRING, "query", cd("play.query", "URL / keywords / Spotify URL"), true)));
        commands.add(Commands.slash("skip", cd("skip", "Skip current track"))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("stop", cd("stop", "Stop playback and clear queue"))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("leave", cd("leave", "Leave voice channel"))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("music-panel", cd("music-panel", "Create music control panel"))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("private-room-settings", cd("private-room-settings", "Manage your private room"))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CMD_ROOM_SETTINGS_ZH, cd("private-room-settings", "Manage your private room"))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash("repeat", cd("repeat", "Set repeat mode"))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.STRING, "mode", cd("repeat.mode", "off/single/all"), true)
                        .addChoice("off", "OFF")
                        .addChoice("single", "SINGLE")
                        .addChoice("all", "ALL")));
        commands.add(buildSettingsCommand());
        commands.add(buildDeleteEnCommand());
        commands.add(buildDeleteZhCommand());
        return commands;
    }

    private SlashCommandData buildDeleteZhCommand() {
        return Commands.slash(CMD_DELETE_ZH, cd("delete", "Delete messages"))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE))
                .addSubcommands(
                        new SubcommandData("channel", cd("delete.channel", "Delete messages in selected channel"))
                                .addOptions(
                                        new OptionData(OptionType.CHANNEL, "channel", cd("delete.channel.channel", "Text channel"), false).setChannelTypes(ChannelType.TEXT),
                                        new OptionData(OptionType.INTEGER, "amount", cd("delete.channel.amount", "1-99"), false).setRequiredRange(1, 99)
                                ),
                        new SubcommandData("user", cd("delete.user", "Delete messages by selected user"))
                                .addOptions(
                                        new OptionData(OptionType.USER, "user", cd("delete.user.user", "Target user"), true),
                                        new OptionData(OptionType.INTEGER, "amount", cd("delete.user.amount", "1-99"), false).setRequiredRange(1, 99)
                                )
                );
    }

    private SlashCommandData buildDeleteEnCommand() {
        return Commands.slash("delete-messages", cd("delete-en", "Delete messages"))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE))
                .addSubcommands(
                        new SubcommandData("channel", cd("delete.channel", "Delete messages in selected channel"))
                                .addOptions(
                                        new OptionData(OptionType.CHANNEL, "channel", cd("delete.channel.channel", "Text channel"), false).setChannelTypes(ChannelType.TEXT),
                                        new OptionData(OptionType.INTEGER, "amount", cd("delete.channel.amount", "1-99"), false).setRequiredRange(1, 99)
                                ),
                        new SubcommandData("user", cd("delete.user", "Delete messages by selected user"))
                                .addOptions(
                                        new OptionData(OptionType.USER, "user", cd("delete.user.user", "Target user"), true),
                                        new OptionData(OptionType.INTEGER, "amount", cd("delete.user.amount", "1-99"), false).setRequiredRange(1, 99)
                                )
                );
    }

    private SlashCommandData buildSettingsCommand() {
        return Commands.slash("settings", cd("settings", "Guild settings"))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addSubcommands(
                        new SubcommandData("info", cd("settings.info", "Show current guild settings")),
                        new SubcommandData("reload", cd("settings.reload", "Reload guild settings")),
                        new SubcommandData("reset", cd("settings.reset", "Reset guild settings by section")),
                        new SubcommandData("template", cd("settings.template", "Open template edit menu")),
                        new SubcommandData("module", cd("settings.module", "Open module toggle menu")),
                        new SubcommandData("logs", cd("settings.logs", "Open logs channel menu")),
                        new SubcommandData("music", cd("settings.music", "Open music settings menu")),
                        new SubcommandData("language", cd("settings.language", "Set language"))
                                .addOption(OptionType.STRING, "code", cd("settings.language.code", "en or zh-TW"), true));
    }

    private String cd(String key, String fallback) {
        return config.getCommandDescription(key, fallback);
    }

    private String lang(long guildId) {
        return settingsService.getLanguage(guildId);
    }

    private boolean isPrefixMusicCommand(String cmd) {
        return "join".equals(cmd)
                || "play".equals(cmd)
                || "skip".equals(cmd)
                || "stop".equals(cmd)
                || "leave".equals(cmd)
                || "repeat".equals(cmd);
    }

    private boolean isKnownPrefixCommand(String cmd) {
        return "help".equals(cmd)
                || "join".equals(cmd)
                || "play".equals(cmd)
                || "skip".equals(cmd)
                || "stop".equals(cmd)
                || "leave".equals(cmd)
                || "repeat".equals(cmd);
    }

    private boolean isSlashMusicCommand(String name) {
        return "join".equals(name)
                || "play".equals(name)
                || "skip".equals(name)
                || "stop".equals(name)
                || "leave".equals(name)
                || "repeat".equals(name)
                || "music-panel".equals(name);
    }

    private boolean isKnownSlashCommand(String name) {
        return "help".equals(name)
                || "join".equals(name)
                || "play".equals(name)
                || "skip".equals(name)
                || "stop".equals(name)
                || "leave".equals(name)
                || "music-panel".equals(name)
                || "repeat".equals(name)
                || "settings".equals(name)
                || "delete-messages".equals(name)
                || CMD_DELETE_ZH.equals(name)
                || "private-room-settings".equals(name)
                || CMD_ROOM_SETTINGS_ZH.equals(name);
    }

    private boolean isMusicCommandChannelAllowed(Guild guild, long channelId) {
        Long configured = settingsService.getMusic(guild.getIdLong()).getCommandChannelId();
        return configured == null || configured == channelId;
    }

    private boolean has(Member member, Permission permission) {
        return member != null && member.hasPermission(permission);
    }

    private String buildSlashRoute(SlashCommandInteractionEvent event) {
        String group = event.getSubcommandGroup();
        String sub = event.getSubcommandName();
        if (group != null && sub != null) {
            return event.getName() + " " + group + " " + sub;
        }
        if (sub != null) {
            return event.getName() + " " + sub;
        }
        return event.getName();
    }

    private void logCommandUsage(Guild guild, Member member, String commandText, long channelId) {
        BotConfig.MessageLogs logs = settingsService.getMessageLogs(guild.getIdLong());
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

    private boolean canControlPanel(Guild guild, Member member) {
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

    private long acquireCooldown(long userId) {
        int cooldownSeconds = Math.max(0, config.getCommandCooldownSeconds());
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

    private long toCooldownSeconds(long remainingMillis) {
        return Math.max(1L, (remainingMillis + 999L) / 1000L);
    }

    private boolean looksLikeUrl(String input) {
        return input.startsWith("http://") || input.startsWith("https://");
    }

    private String detectSource(AudioTrack track) {
        String uri = track.getInfo().uri == null ? "" : track.getInfo().uri.toLowerCase();
        if (uri.contains("spotify")) {
            return "spotify";
        }
        if (uri.contains("youtube") || uri.contains("youtu.be")) {
            return "youtube";
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

    private String mapRepeatLabel(String lang, String mode) {
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
            String error = notice.substring("LOAD_FAILED:".length()).trim();
            if (error.isBlank()) {
                error = "-";
            }
            return i18n.t(lang, "music.autoplay_notice_load_failed", Map.of("error", safe(error, 140)));
        }
        return safe(notice, 160);
    }

    private String safe(String s, int max) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1);
    }

    private String formatColor(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    private Integer parseHexColor(String raw) {
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

    private int resolveTemplateColor(long guildId, String templateType) {
        BotConfig.Notifications notifications = settingsService.getNotifications(guildId);
        return switch (templateType) {
            case "member-join" -> notifications.getMemberJoinColor();
            case "member-leave" -> notifications.getMemberLeaveColor();
            default -> 0x3498DB;
        };
    }

    private String boolText(String lang, boolean value) {
        return value ? i18n.t(lang, "settings.info_bool_on") : i18n.t(lang, "settings.info_bool_off");
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

    private String formatMemberChannelMode(String lang, BotConfig.Notifications notifications) {
        boolean split = notifications.getMemberJoinChannelId() != null || notifications.getMemberLeaveChannelId() != null;
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
        String defaultText = localizeTemplateForDisplay(lang, trimTemplate(defaults));
        return "**" + i18n.t(lang, titleKey) + "**\n`" + effectiveText + "`\n"
                + "> " + i18n.t(lang, "settings.info_default_prefix") + "：`" + defaultText + "`";
    }

    private String localizeTemplateForDisplay(String lang, String template) {
        return template;
    }

    private String line(String lang, String key, String value) {
        String icon = keyIcon(key);
        return icon + " " + i18n.t(lang, key) + ": " + value;
    }

    private String joinLines(String... values) {
        return String.join("\n", values);
    }

    private String compare(String lang, String effective, String defaults) {
        return i18n.t(lang, "settings.info_compare_format",
                Map.of("effective", safe(effective, 160), "default", safe(defaults, 160)));
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
        BotConfig.MessageLogs logs = s.getMessageLogs();
        BotConfig.Notifications n = s.getNotifications();
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
            case "settings.info_key_default_repeat_mode" -> "\uD83D\uDD02";
            case "settings.key_music_commandChannelId", "settings.info_key_music_command_channel" -> "\uD83C\uDFB6";
            case "settings.key_privateRoom_triggerVoiceChannelId", "settings.info_key_trigger_channel" -> "\uD83C\uDFA4";
            case "settings.info_key_category_auto", "settings.info_key_category" -> "\uD83D\uDCC2";
            case "settings.key_privateRoom_userLimit", "settings.info_key_user_limit" -> "\uD83D\uDC64";
            default -> "\u25AB\uFE0F";
        };
    }

    @FunctionalInterface
    private interface TextSink {
        void send(String text);
    }

    private static class PanelRef {
        private final long channelId;
        private final long messageId;

        private PanelRef(long channelId, long messageId) {
            this.channelId = channelId;
            this.messageId = messageId;
        }
    }

    private static class SearchRequest {
        private final long requestUserId;
        private final long channelId;
        private final String query;
        private final List<AudioTrack> results;
        private final Instant expiresAt;

        private SearchRequest(long requestUserId, long channelId, String query, List<AudioTrack> results, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.channelId = channelId;
            this.query = query;
            this.results = results;
            this.expiresAt = expiresAt;
        }
    }

    private static class DeleteRequest {
        private final long requestUserId;
        private final long channelId;
        private final Long targetUserId;
        private final int amount;

        private DeleteRequest(long requestUserId, long channelId, Long targetUserId, int amount) {
            this.requestUserId = requestUserId;
            this.channelId = channelId;
            this.targetUserId = targetUserId;
            this.amount = amount;
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

