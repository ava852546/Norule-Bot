package com.norule.musicbot.web;

import com.norule.musicbot.ModerationService;
import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class GuildSettingsApiController {
    private final WebControlServer owner;

    GuildSettingsApiController(WebControlServer owner) {
        this.owner = owner;
    }

    void handleApiGuildRoute(HttpExchange exchange) throws IOException {
        WebSessionManager.WebSession session = owner.sessionManager().requireSession(exchange);
        if (session == null) {
            owner.sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring("/api/guild/".length());
        String[] segments = suffix.split("/");
        if (segments.length < 2) {
            owner.sendJson(exchange, 404, DataObject.empty().put("error", "Not Found"));
            return;
        }

        long guildId = owner.parseLong(segments[0], -1L);
        if (guildId <= 0L) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", "Invalid guild id"));
            return;
        }

        Guild guild = owner.jda().getGuildById(guildId);
        if (guild == null) {
            owner.sendJson(exchange, 404, DataObject.empty().put("error", "Guild not found"));
            return;
        }

        Member member = owner.resolveMember(guild, owner.parseLong(session.userId, -1L));
        if (member == null) {
            owner.sendJson(exchange, 403, DataObject.empty().put("error", "You are not in this guild"));
            return;
        }
        if (!owner.hasControlPermission(member)) {
            owner.sendJson(exchange, 403, DataObject.empty().put("error", "Missing permission: Manage Server"));
            return;
        }

        String section = segments[1];
        if ("music".equals(section)) {
            handleMusicRoute(exchange, guild, member, segments);
            return;
        }
        if ("settings".equals(section)) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                if (segments.length >= 3) {
                    handleSettingsSectionGet(exchange, guild, segments[2]);
                } else {
                    handleSettingsGet(exchange, guild);
                }
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleSettingsSave(exchange, guild);
                return;
            }
            owner.sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        if ("ticket".equals(section)) {
            handleTicketRoute(exchange, guild, segments);
            return;
        }
        if ("welcome".equals(section)) {
            if (segments.length < 3) {
                owner.sendJson(exchange, 404, DataObject.empty().put("error", "Unknown welcome action"));
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && "preview".equals(segments[2])) {
                owner.welcomePreviewService().handleWelcomePreview(exchange, guild, member);
                return;
            }
            owner.sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        if ("number-chain".equals(section)) {
            if (segments.length < 3) {
                owner.sendJson(exchange, 404, DataObject.empty().put("error", "Unknown number chain action"));
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && "reset".equals(segments[2])) {
                owner.moderationService().resetNumberChain(guild.getIdLong());
                owner.sendJson(exchange, 200, DataObject.empty().put("ok", true).put("nextNumber", owner.moderationService().getNumberChainNext(guild.getIdLong())));
                return;
            }
            owner.sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        if ("channels".equals(section)) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleGuildChannelsGet(exchange, guild);
                return;
            }
            owner.sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        if ("roles".equals(section)) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleGuildRolesGet(exchange, guild);
                return;
            }
            owner.sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }

        owner.sendJson(exchange, 404, DataObject.empty().put("error", "Unknown section"));
    }

    private void handleMusicRoute(HttpExchange exchange, Guild guild, Member member, String[] segments) throws IOException {
        if (segments.length < 3) {
            owner.sendJson(exchange, 404, DataObject.empty().put("error", "Unknown action"));
            return;
        }
        String action = segments[2];
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && "state".equals(action)) {
            handleMusicState(exchange, guild, member);
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }

        Map<String, String> form = owner.parseUrlEncoded(owner.readBody(exchange));
        switch (action) {
            case "play" -> handleMusicPlay(exchange, guild, member, form);
            case "toggle-pause" -> owner.sendJson(exchange, 200, DataObject.empty().put("ok", true).put("paused", owner.musicService().togglePause(guild)));
            case "skip" -> {
                owner.musicService().skip(guild);
                owner.sendJson(exchange, 200, DataObject.empty().put("ok", true));
            }
            case "stop" -> {
                owner.musicService().stop(guild);
                owner.sendJson(exchange, 200, DataObject.empty().put("ok", true));
            }
            case "leave" -> {
                owner.musicService().leaveChannel(guild);
                owner.sendJson(exchange, 200, DataObject.empty().put("ok", true));
            }
            case "repeat" -> handleMusicRepeat(exchange, guild, form);
            case "autoplay" -> handleAutoplayToggle(exchange, guild, form);
            default -> owner.sendJson(exchange, 404, DataObject.empty().put("error", "Unknown action"));
        }
    }

    private void handleTicketRoute(HttpExchange exchange, Guild guild, String[] segments) throws IOException {
        if (segments.length < 3) {
            owner.sendJson(exchange, 404, DataObject.empty().put("error", "Unknown ticket action"));
            return;
        }
        String action = segments[2];
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && "transcripts".equals(action)) {
            owner.ticketTranscriptApiController().handleTicketTranscriptList(exchange, guild);
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && "transcript".equals(action)) {
            if (segments.length < 4) {
                owner.sendJson(exchange, 404, DataObject.empty().put("error", "Missing transcript name"));
                return;
            }
            owner.ticketTranscriptApiController().handleTicketTranscriptDownload(exchange, guild, segments[3]);
            return;
        }
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod()) && "transcript".equals(action)) {
            if (segments.length < 4) {
                owner.sendJson(exchange, 404, DataObject.empty().put("error", "Missing transcript name"));
                return;
            }
            owner.ticketTranscriptApiController().handleTicketTranscriptDelete(exchange, guild, segments[3]);
            return;
        }
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) && "panel".equals(action)) {
            owner.ticketTranscriptApiController().handleTicketPanelSend(exchange, guild);
            return;
        }
        owner.sendJson(exchange, 404, DataObject.empty().put("error", "Unknown ticket action"));
    }

    private void handleMusicState(HttpExchange exchange, Guild guild, Member member) throws IOException {
        AudioTrack current = owner.musicService().getCurrentTrack(guild);
        var botVoiceState = guild.getSelfMember().getVoiceState();
        var userVoiceState = member.getVoiceState();
        DataArray queue = DataArray.empty();
        for (AudioTrack track : owner.musicService().getQueueSnapshot(guild).stream().limit(8).toList()) {
            queue.add(DataObject.empty().put("title", track.getInfo().title == null ? "-" : track.getInfo().title).put("duration", owner.formatDuration(track.getDuration())));
        }
        String autoplayNotice = owner.musicService().getAutoplayNotice(guild.getIdLong());
        owner.sendJson(exchange, 200, DataObject.empty()
                .put("guildId", guild.getId())
                .put("guildName", guild.getName())
                .put("connected", botVoiceState != null && botVoiceState.inAudioChannel())
                .put("botChannel", botVoiceState != null && botVoiceState.inAudioChannel() ? botVoiceState.getChannel().getAsMention() : "-")
                .put("yourVoice", userVoiceState != null && userVoiceState.inAudioChannel() ? userVoiceState.getChannel().getAsMention() : "-")
                .put("title", current == null ? "-" : current.getInfo().title)
                .put("source", owner.musicService().getCurrentSource(guild))
                .put("requester", owner.musicService().getCurrentRequesterDisplay(guild))
                .put("position", owner.formatDuration(owner.musicService().getCurrentPositionMillis(guild)))
                .put("duration", owner.formatDuration(owner.musicService().getCurrentDurationMillis(guild)))
                .put("paused", owner.musicService().isPaused(guild))
                .put("repeatMode", owner.musicService().getRepeatMode(guild))
                .put("autoplayEnabled", owner.settingsService().getMusic(guild.getIdLong()).isAutoplayEnabled())
                .put("autoplayNotice", autoplayNotice == null ? "" : autoplayNotice)
                .put("queue", queue));
    }

    private void handleMusicPlay(HttpExchange exchange, Guild guild, Member member, Map<String, String> form) throws IOException {
        String query = form.getOrDefault("query", "").trim();
        if (query.isBlank()) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", "Missing query"));
            return;
        }
        var memberVoiceState = member.getVoiceState();
        if (memberVoiceState == null || !memberVoiceState.inAudioChannel()) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", "You must join a voice channel first"));
            return;
        }
        AudioChannel memberChannel = memberVoiceState.getChannel();
        var botVoiceState = guild.getSelfMember().getVoiceState();
        if (botVoiceState != null && botVoiceState.inAudioChannel() && !botVoiceState.getChannel().getId().equals(memberChannel.getId())) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", "Bot is in " + botVoiceState.getChannel().getAsMention() + ". Join that channel first."));
            return;
        }
        if (botVoiceState == null || !botVoiceState.inAudioChannel()) {
            owner.musicService().joinChannel(guild, memberChannel);
        }
        owner.musicService().loadAndPlay(guild, msg -> { }, query, member.getIdLong(), member.getEffectiveName());
        owner.sendJson(exchange, 200, DataObject.empty().put("ok", true).put("message", "Queued: " + query));
    }

    private void handleMusicRepeat(HttpExchange exchange, Guild guild, Map<String, String> form) throws IOException {
        String mode = form.getOrDefault("mode", "").trim().toLowerCase();
        if (!mode.equals("off") && !mode.equals("single") && !mode.equals("all")) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", "mode must be off/single/all"));
            return;
        }
        owner.musicService().setRepeatMode(guild, mode);
        owner.sendJson(exchange, 200, DataObject.empty().put("ok", true).put("repeatMode", owner.musicService().getRepeatMode(guild)));
    }

    private void handleAutoplayToggle(HttpExchange exchange, Guild guild, Map<String, String> form) throws IOException {
        String raw = form.getOrDefault("enabled", "").trim().toLowerCase();
        if (!raw.equals("true") && !raw.equals("false")) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", "enabled must be true/false"));
            return;
        }
        boolean enabled = Boolean.parseBoolean(raw);
        owner.settingsService().updateSettings(guild.getIdLong(), s -> s.withMusic(s.getMusic().withAutoplayEnabled(enabled)));
        owner.sendJson(exchange, 200, DataObject.empty().put("ok", true).put("autoplayEnabled", enabled));
    }

    private void handleGuildChannelsGet(HttpExchange exchange, Guild guild) throws IOException {
        DataArray textChannels = DataArray.empty();
        guild.getTextChannels().forEach(ch -> textChannels.add(DataObject.empty().put("id", ch.getId()).put("name", ch.getName()).put("mention", ch.getAsMention())));
        DataArray voiceChannels = DataArray.empty();
        guild.getVoiceChannels().forEach(ch -> voiceChannels.add(DataObject.empty().put("id", ch.getId()).put("name", ch.getName()).put("mention", ch.getAsMention())));
        owner.sendJson(exchange, 200, DataObject.empty().put("textChannels", textChannels).put("voiceChannels", voiceChannels));
    }

    private void handleGuildRolesGet(HttpExchange exchange, Guild guild) throws IOException {
        DataArray roles = DataArray.empty();
        guild.getRoles().stream().filter(role -> !role.isPublicRole()).sorted(Comparator.comparingInt(Role::getPositionRaw).reversed()).forEach(role -> roles.add(DataObject.empty().put("id", role.getId()).put("name", role.getName())));
        owner.sendJson(exchange, 200, DataObject.empty().put("roles", roles));
    }

    void handleSettingsGet(HttpExchange exchange, Guild guild) throws IOException {
        GuildSettingsService.GuildSettings settings = owner.settingsService().getSettings(guild.getIdLong());
        owner.sendJson(exchange, 200, buildFullSettingsPayload(guild, settings));
    }

    private void handleSettingsSectionGet(HttpExchange exchange, Guild guild, String sectionName) throws IOException {
        GuildSettingsService.GuildSettings settings = owner.settingsService().getSettings(guild.getIdLong());
        DataObject payload = buildSectionSettingsPayload(guild, settings, sectionName);
        if (payload == null) {
            owner.sendJson(exchange, 404, DataObject.empty().put("error", "Unknown settings section"));
            return;
        }
        owner.sendJson(exchange, 200, payload);
    }

    private DataObject buildFullSettingsPayload(Guild guild, GuildSettingsService.GuildSettings settings) {
        return DataObject.empty()
                .put("language", settings.getLanguage())
                .put("notifications", buildNotificationsPayload(settings))
                .put("welcome", buildWelcomePayload(settings))
                .put("messageLogs", buildMessageLogsPayload(settings))
                .put("music", buildMusicPayload(settings))
                .put("musicStats", buildMusicStatsPayload(guild))
                .put("privateRoom", buildPrivateRoomPayload(settings))
                .put("numberChain", buildNumberChainPayload(guild))
                .put("ticket", buildTicketPayload(settings));
    }

    private DataObject buildSectionSettingsPayload(Guild guild, GuildSettingsService.GuildSettings settings, String sectionName) {
        return switch (sectionName) {
            case "general" -> DataObject.empty().put("language", settings.getLanguage());
            case "notifications" -> DataObject.empty().put("notifications", buildNotificationsPayload(settings));
            case "welcome" -> DataObject.empty().put("welcome", buildWelcomePayload(settings));
            case "logs" -> DataObject.empty().put("messageLogs", buildMessageLogsPayload(settings));
            case "music" -> DataObject.empty()
                    .put("music", buildMusicPayload(settings))
                    .put("musicStats", buildMusicStatsPayload(guild));
            case "privateRoom" -> DataObject.empty().put("privateRoom", buildPrivateRoomPayload(settings));
            case "numberChain" -> DataObject.empty().put("numberChain", buildNumberChainPayload(guild));
            case "ticket" -> DataObject.empty().put("ticket", buildTicketPayload(settings));
            default -> null;
        };
    }

    private DataObject buildNotificationsPayload(GuildSettingsService.GuildSettings settings) {
        BotConfig.Notifications n = settings.getNotifications();
        return DataObject.empty()
                .put("enabled", n.isEnabled())
                .put("memberJoinEnabled", n.isMemberJoinEnabled())
                .put("memberLeaveEnabled", n.isMemberLeaveEnabled())
                .put("voiceLogEnabled", n.isVoiceLogEnabled())
                .put("memberChannelId", owner.toIdText(n.getMemberChannelId()))
                .put("memberJoinChannelId", owner.toIdText(n.getMemberJoinChannelId()))
                .put("memberLeaveChannelId", owner.toIdText(n.getMemberLeaveChannelId()))
                .put("memberJoinTitle", n.getMemberJoinTitle())
                .put("memberJoinMessage", n.getMemberJoinMessage())
                .put("memberJoinThumbnailUrl", n.getMemberJoinThumbnailUrl())
                .put("memberJoinImageUrl", n.getMemberJoinImageUrl())
                .put("memberLeaveMessage", n.getMemberLeaveMessage())
                .put("memberJoinColor", String.format("#%06X", n.getMemberJoinColor()))
                .put("memberLeaveColor", String.format("#%06X", n.getMemberLeaveColor()))
                .put("voiceChannelId", owner.toIdText(n.getVoiceChannelId()))
                .put("voiceJoinMessage", n.getVoiceJoinMessage())
                .put("voiceLeaveMessage", n.getVoiceLeaveMessage())
                .put("voiceMoveMessage", n.getVoiceMoveMessage())
                .put("voiceJoinColor", String.format("#%06X", n.getVoiceJoinColor()))
                .put("voiceLeaveColor", String.format("#%06X", n.getVoiceLeaveColor()))
                .put("voiceMoveColor", String.format("#%06X", n.getVoiceMoveColor()));
    }

    private DataObject buildWelcomePayload(GuildSettingsService.GuildSettings settings) {
        BotConfig.Welcome welcome = settings.getWelcome();
        return DataObject.empty()
                .put("enabled", welcome.isEnabled())
                .put("channelId", owner.toIdText(welcome.getChannelId()))
                .put("title", welcome.getTitle())
                .put("message", welcome.getMessage())
                .put("thumbnailUrl", welcome.getThumbnailUrl())
                .put("imageUrl", welcome.getImageUrl());
    }

    private DataObject buildMessageLogsPayload(GuildSettingsService.GuildSettings settings) {
        BotConfig.MessageLogs logs = settings.getMessageLogs();
        return DataObject.empty()
                .put("enabled", logs.isEnabled())
                .put("channelId", owner.toIdText(logs.getChannelId()))
                .put("messageLogChannelId", owner.toIdText(logs.getMessageLogChannelId()))
                .put("commandUsageChannelId", owner.toIdText(logs.getCommandUsageChannelId()))
                .put("channelLifecycleChannelId", owner.toIdText(logs.getChannelLifecycleChannelId()))
                .put("roleLogChannelId", owner.toIdText(logs.getRoleLogChannelId()))
                .put("moderationLogChannelId", owner.toIdText(logs.getModerationLogChannelId()))
                .put("roleLogEnabled", logs.isRoleLogEnabled())
                .put("channelLifecycleLogEnabled", logs.isChannelLifecycleLogEnabled())
                .put("moderationLogEnabled", logs.isModerationLogEnabled())
                .put("commandUsageLogEnabled", logs.isCommandUsageLogEnabled())
                .put("ignoredMemberIds", logs.getIgnoredMemberIds())
                .put("ignoredRoleIds", logs.getIgnoredRoleIds())
                .put("ignoredChannelIds", logs.getIgnoredChannelIds())
                .put("ignoredPrefixes", logs.getIgnoredPrefixes());
    }

    private DataObject buildMusicPayload(GuildSettingsService.GuildSettings settings) {
        BotConfig.Music music = settings.getMusic();
        return DataObject.empty()
                .put("autoLeaveEnabled", music.isAutoLeaveEnabled())
                .put("autoLeaveMinutes", music.getAutoLeaveMinutes())
                .put("autoplayEnabled", music.isAutoplayEnabled())
                .put("defaultRepeatMode", music.getDefaultRepeatMode().name())
                .put("commandChannelId", owner.toIdText(music.getCommandChannelId()))
                .put("historyLimit", music.getHistoryLimit())
                .put("statsRetentionDays", music.getStatsRetentionDays());
    }

    private DataObject buildMusicStatsPayload(Guild guild) {
        MusicDataService.MusicStatsSnapshot musicStats = owner.musicService().getStats(guild.getIdLong());
        String topRequesterDisplay = "";
        if (musicStats.topRequesterId() != null) {
            Member topRequester = guild.getMemberById(musicStats.topRequesterId());
            topRequesterDisplay = topRequester != null ? topRequester.getEffectiveName() : musicStats.topRequesterId().toString();
        }
        return DataObject.empty()
                .put("topSongLabel", musicStats.topSongLabel() == null ? "" : musicStats.topSongLabel())
                .put("topSongCount", musicStats.topSongCount())
                .put("topRequesterDisplay", topRequesterDisplay)
                .put("topRequesterCount", musicStats.topRequesterCount())
                .put("todayPlaybackMillis", musicStats.todayPlaybackMillis())
                .put("todayPlaybackDisplay", owner.formatDuration(musicStats.todayPlaybackMillis()))
                .put("historyCount", musicStats.historyCount());
    }

    private DataObject buildPrivateRoomPayload(GuildSettingsService.GuildSettings settings) {
        BotConfig.PrivateRoom privateRoom = settings.getPrivateRoom();
        return DataObject.empty()
                .put("enabled", privateRoom.isEnabled())
                .put("triggerVoiceChannelId", owner.toIdText(privateRoom.getTriggerVoiceChannelId()))
                .put("userLimit", privateRoom.getUserLimit());
    }

    private DataObject buildNumberChainPayload(Guild guild) {
        DataArray topContributors = DataArray.empty();
        for (ModerationService.NumberChainContributor contributor : owner.moderationService().getTopNumberChainContributors(guild.getIdLong(), 5)) {
            topContributors.add(DataObject.empty()
                    .put("userId", String.valueOf(contributor.getUserId()))
                    .put("count", contributor.getCount()));
        }
        return DataObject.empty()
                .put("enabled", owner.moderationService().isNumberChainEnabled(guild.getIdLong()))
                .put("channelId", owner.toIdText(owner.moderationService().getNumberChainChannelId(guild.getIdLong())))
                .put("nextNumber", owner.moderationService().getNumberChainNext(guild.getIdLong()))
                .put("highestNumber", owner.moderationService().getNumberChainHighestNumber(guild.getIdLong()))
                .put("topContributors", topContributors);
    }

    private DataObject buildTicketPayload(GuildSettingsService.GuildSettings settings) {
        BotConfig.Ticket ticket = settings.getTicket();
        return DataObject.empty()
                .put("enabled", ticket.isEnabled())
                .put("panelChannelId", owner.toIdText(ticket.getPanelChannelId()))
                .put("autoCloseDays", ticket.getAutoCloseDays())
                .put("maxOpenPerUser", ticket.getMaxOpenPerUser())
                .put("openUiMode", ticket.getOpenUiMode().name())
                .put("panelTitle", ticket.getPanelTitle())
                .put("panelDescription", ticket.getPanelDescription())
                .put("panelColor", String.format("#%06X", ticket.getPanelColor() & 0xFFFFFF))
                .put("panelButtonStyle", ticket.getPanelButtonStyle())
                .put("panelButtonLimit", ticket.getPanelButtonLimit())
                .put("preOpenFormEnabled", ticket.isPreOpenFormEnabled())
                .put("preOpenFormTitle", ticket.getPreOpenFormTitle())
                .put("preOpenFormLabel", ticket.getPreOpenFormLabel())
                .put("preOpenFormPlaceholder", ticket.getPreOpenFormPlaceholder())
                .put("welcomeMessage", ticket.getWelcomeMessage())
                .put("optionLabels", String.join(", ", ticket.getOptionLabels()))
                .put("options", DataArray.fromCollection(ticket.getOptions().stream().map(option -> DataObject.empty()
                        .put("id", option.getId())
                        .put("label", option.getLabel())
                        .put("panelTitle", option.getPanelTitle())
                        .put("panelDescription", option.getPanelDescription())
                        .put("panelButtonStyle", option.getPanelButtonStyle())
                        .put("welcomeMessage", option.getWelcomeMessage())
                        .put("preOpenFormEnabled", option.isPreOpenFormEnabled())
                        .put("preOpenFormTitle", option.getPreOpenFormTitle())
                        .put("preOpenFormLabel", option.getPreOpenFormLabel())
                        .put("preOpenFormPlaceholder", option.getPreOpenFormPlaceholder()))
                        .toList()))
                .put("supportRoleIds", ticket.getSupportRoleIds().stream().map(String::valueOf).toList())
                .put("blacklistedUserIds", ticket.getBlacklistedUserIds().stream().map(String::valueOf).toList());
    }

    void handleSettingsSave(HttpExchange exchange, Guild guild) throws IOException {
        String body = owner.readBody(exchange);
        Map<String, Object> root;
        try {
            root = owner.asMap(new Yaml().load(body));
        } catch (Exception e) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", "Invalid settings JSON"));
            return;
        }
        if (root.isEmpty()) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", "Empty settings payload"));
            return;
        }

        GuildSettingsService.GuildSettings updated = owner.settingsService().updateSettings(guild.getIdLong(), current -> {
            String oldLanguage = current.getLanguage();
            String language = owner.stringOrDefault(root, "language", current.getLanguage());
            boolean languageChanged = !owner.normalizeLang(oldLanguage).equalsIgnoreCase(owner.normalizeLang(language));

            BotConfig.Notifications n = current.getNotifications();
            Map<String, Object> nMap = owner.asMap(root.get("notifications"));
            if (!nMap.isEmpty()) {
                n = n.withEnabled(owner.boolOrDefault(nMap, "enabled", n.isEnabled()))
                        .withMemberJoinEnabled(owner.boolOrDefault(nMap, "memberJoinEnabled", n.isMemberJoinEnabled()))
                        .withMemberLeaveEnabled(owner.boolOrDefault(nMap, "memberLeaveEnabled", n.isMemberLeaveEnabled()))
                        .withVoiceLogEnabled(owner.boolOrDefault(nMap, "voiceLogEnabled", n.isVoiceLogEnabled()))
                        .withMemberChannelId(owner.idOrDefault(nMap, "memberChannelId", n.getMemberChannelId()))
                        .withMemberJoinChannelId(owner.idOrDefault(nMap, "memberJoinChannelId", n.getMemberJoinChannelId()))
                        .withMemberLeaveChannelId(owner.idOrDefault(nMap, "memberLeaveChannelId", n.getMemberLeaveChannelId()))
                        .withMemberJoinTitle(owner.stringOrDefault(nMap, "memberJoinTitle", n.getMemberJoinTitle()))
                        .withMemberJoinMessage(owner.stringOrDefault(nMap, "memberJoinMessage", n.getMemberJoinMessage()))
                        .withMemberJoinThumbnailUrl(owner.stringOrDefault(nMap, "memberJoinThumbnailUrl", n.getMemberJoinThumbnailUrl()))
                        .withMemberJoinImageUrl(owner.stringOrDefault(nMap, "memberJoinImageUrl", n.getMemberJoinImageUrl()))
                        .withMemberLeaveMessage(owner.stringOrDefault(nMap, "memberLeaveMessage", n.getMemberLeaveMessage()))
                        .withMemberJoinColor(owner.colorOrDefault(nMap, "memberJoinColor", n.getMemberJoinColor()))
                        .withMemberLeaveColor(owner.colorOrDefault(nMap, "memberLeaveColor", n.getMemberLeaveColor()))
                        .withVoiceChannelId(owner.idOrDefault(nMap, "voiceChannelId", n.getVoiceChannelId()))
                        .withVoiceJoinMessage(owner.stringOrDefault(nMap, "voiceJoinMessage", n.getVoiceJoinMessage()))
                        .withVoiceLeaveMessage(owner.stringOrDefault(nMap, "voiceLeaveMessage", n.getVoiceLeaveMessage()))
                        .withVoiceMoveMessage(owner.stringOrDefault(nMap, "voiceMoveMessage", n.getVoiceMoveMessage()))
                        .withVoiceJoinColor(owner.colorOrDefault(nMap, "voiceJoinColor", n.getVoiceJoinColor()))
                        .withVoiceLeaveColor(owner.colorOrDefault(nMap, "voiceLeaveColor", n.getVoiceLeaveColor()))
                        .withVoiceMoveColor(owner.colorOrDefault(nMap, "voiceMoveColor", n.getVoiceMoveColor()));
            }

            BotConfig.Welcome w = current.getWelcome();
            Map<String, Object> wMap = owner.asMap(root.get("welcome"));
            if (!wMap.isEmpty()) {
                w = w.withEnabled(owner.boolOrDefault(wMap, "enabled", w.isEnabled()))
                        .withChannelId(owner.idOrDefault(wMap, "channelId", w.getChannelId()))
                        .withTitle(owner.stringOrDefault(wMap, "title", w.getTitle()))
                        .withMessage(owner.stringOrDefault(wMap, "message", w.getMessage()))
                        .withThumbnailUrl(owner.stringOrDefault(wMap, "thumbnailUrl", w.getThumbnailUrl()))
                        .withImageUrl(owner.stringOrDefault(wMap, "imageUrl", w.getImageUrl()));
            }

            if (languageChanged) {
                BotConfig.Notifications localized = owner.notificationDefaultsForLanguage(language);
                if (owner.shouldAutoApplyTemplate(nMap, "memberJoinMessage", current.getNotifications().getMemberJoinMessage())) {
                    n = n.withMemberJoinMessage(localized.getMemberJoinMessage());
                }
                if (owner.shouldAutoApplyTemplate(nMap, "memberLeaveMessage", current.getNotifications().getMemberLeaveMessage())) {
                    n = n.withMemberLeaveMessage(localized.getMemberLeaveMessage());
                }
                if (owner.shouldAutoApplyTemplate(nMap, "voiceJoinMessage", current.getNotifications().getVoiceJoinMessage())) {
                    n = n.withVoiceJoinMessage(localized.getVoiceJoinMessage());
                }
                if (owner.shouldAutoApplyTemplate(nMap, "voiceLeaveMessage", current.getNotifications().getVoiceLeaveMessage())) {
                    n = n.withVoiceLeaveMessage(localized.getVoiceLeaveMessage());
                }
                if (owner.shouldAutoApplyTemplate(nMap, "voiceMoveMessage", current.getNotifications().getVoiceMoveMessage())) {
                    n = n.withVoiceMoveMessage(localized.getVoiceMoveMessage());
                }
            }

            BotConfig.MessageLogs l = current.getMessageLogs();
            Map<String, Object> lMap = owner.asMap(root.get("messageLogs"));
            if (!lMap.isEmpty()) {
                l = l.withEnabled(owner.boolOrDefault(lMap, "enabled", l.isEnabled()))
                        .withChannelId(owner.idOrDefault(lMap, "channelId", l.getChannelId()))
                        .withMessageLogChannelId(owner.idOrDefault(lMap, "messageLogChannelId", l.getMessageLogChannelId()))
                        .withCommandUsageChannelId(owner.idOrDefault(lMap, "commandUsageChannelId", l.getCommandUsageChannelId()))
                        .withChannelLifecycleChannelId(owner.idOrDefault(lMap, "channelLifecycleChannelId", l.getChannelLifecycleChannelId()))
                        .withRoleLogChannelId(owner.idOrDefault(lMap, "roleLogChannelId", l.getRoleLogChannelId()))
                        .withModerationLogChannelId(owner.idOrDefault(lMap, "moderationLogChannelId", l.getModerationLogChannelId()))
                        .withRoleLogEnabled(owner.boolOrDefault(lMap, "roleLogEnabled", l.isRoleLogEnabled()))
                        .withChannelLifecycleLogEnabled(owner.boolOrDefault(lMap, "channelLifecycleLogEnabled", l.isChannelLifecycleLogEnabled()))
                        .withModerationLogEnabled(owner.boolOrDefault(lMap, "moderationLogEnabled", l.isModerationLogEnabled()))
                        .withCommandUsageLogEnabled(owner.boolOrDefault(lMap, "commandUsageLogEnabled", l.isCommandUsageLogEnabled()))
                        .withIgnoredMemberIds(owner.parseLongCsvOrDefault(lMap, "ignoredMemberIds", l.getIgnoredMemberIds()))
                        .withIgnoredRoleIds(owner.parseLongCsvOrDefault(lMap, "ignoredRoleIds", l.getIgnoredRoleIds()))
                        .withIgnoredChannelIds(owner.parseLongCsvOrDefault(lMap, "ignoredChannelIds", l.getIgnoredChannelIds()))
                        .withIgnoredPrefixes(owner.parseCsvOrDefault(lMap, "ignoredPrefixes", l.getIgnoredPrefixes()));
            }

            BotConfig.Music m = current.getMusic();
            Map<String, Object> mMap = owner.asMap(root.get("music"));
            if (!mMap.isEmpty()) {
                m = m.withAutoLeaveEnabled(owner.boolOrDefault(mMap, "autoLeaveEnabled", m.isAutoLeaveEnabled()))
                        .withAutoLeaveMinutes(owner.intOrDefault(mMap, "autoLeaveMinutes", m.getAutoLeaveMinutes(), 1, 60))
                        .withAutoplayEnabled(owner.boolOrDefault(mMap, "autoplayEnabled", m.isAutoplayEnabled()))
                        .withDefaultRepeatMode(owner.parseRepeatMode(owner.stringOrDefault(mMap, "defaultRepeatMode", m.getDefaultRepeatMode().name())))
                        .withCommandChannelId(owner.idOrDefault(mMap, "commandChannelId", m.getCommandChannelId()))
                        .withHistoryLimit(owner.intOrDefault(mMap, "historyLimit", m.getHistoryLimit(), 1, 500))
                        .withStatsRetentionDays(owner.intOrDefault(mMap, "statsRetentionDays", m.getStatsRetentionDays(), 0, 3650));
            }

            BotConfig.PrivateRoom p = current.getPrivateRoom();
            Map<String, Object> pMap = owner.asMap(root.get("privateRoom"));
            if (!pMap.isEmpty()) {
                p = p.withEnabled(owner.boolOrDefault(pMap, "enabled", p.isEnabled()))
                        .withTriggerVoiceChannelId(owner.idOrDefault(pMap, "triggerVoiceChannelId", p.getTriggerVoiceChannelId()))
                        .withUserLimit(owner.intOrDefault(pMap, "userLimit", p.getUserLimit(), 0, 99));
            }

            Map<String, Object> ncMap = owner.asMap(root.get("numberChain"));
            if (!ncMap.isEmpty()) {
                owner.moderationService().setNumberChainEnabled(
                        guild.getIdLong(),
                        owner.boolOrDefault(ncMap, "enabled", owner.moderationService().isNumberChainEnabled(guild.getIdLong()))
                );
                Long currentChannelId = owner.moderationService().getNumberChainChannelId(guild.getIdLong());
                Long nextChannelId = owner.idOrDefault(ncMap, "channelId", currentChannelId);
                if (!Objects.equals(currentChannelId, nextChannelId)) {
                    owner.moderationService().setNumberChainChannelId(guild.getIdLong(), nextChannelId);
                }
            }

            BotConfig.Ticket t = current.getTicket();
            Map<String, Object> tMap = owner.asMap(root.get("ticket"));
            if (!tMap.isEmpty()) {
                List<BotConfig.Ticket.TicketOption> options = owner.parseTicketOptions(tMap, t.getOptions());
                t = t.withEnabled(owner.boolOrDefault(tMap, "enabled", t.isEnabled()))
                        .withPanelChannelId(owner.idOrDefault(tMap, "panelChannelId", t.getPanelChannelId()))
                        .withAutoCloseDays(owner.intOrDefault(tMap, "autoCloseDays", t.getAutoCloseDays(), 1, 365))
                        .withMaxOpenPerUser(owner.intOrDefault(tMap, "maxOpenPerUser", t.getMaxOpenPerUser(), 1, 20))
                        .withOpenUiMode(owner.parseTicketOpenUiMode(owner.stringOrDefault(tMap, "openUiMode", t.getOpenUiMode().name()), t.getOpenUiMode()))
                        .withPanelTitle(owner.stringOrDefault(tMap, "panelTitle", t.getPanelTitle()))
                        .withPanelDescription(owner.stringOrDefault(tMap, "panelDescription", t.getPanelDescription()))
                        .withPanelColor(owner.colorOrDefault(tMap, "panelColor", t.getPanelColor()))
                        .withPanelButtonStyle(owner.stringOrDefault(tMap, "panelButtonStyle", t.getPanelButtonStyle()))
                        .withPanelButtonLimit(owner.intOrDefault(tMap, "panelButtonLimit", t.getPanelButtonLimit(), 1, 25))
                        .withPreOpenFormEnabled(owner.boolOrDefault(tMap, "preOpenFormEnabled", t.isPreOpenFormEnabled()))
                        .withPreOpenFormTitle(owner.stringOrDefault(tMap, "preOpenFormTitle", t.getPreOpenFormTitle()))
                        .withPreOpenFormLabel(owner.stringOrDefault(tMap, "preOpenFormLabel", t.getPreOpenFormLabel()))
                        .withPreOpenFormPlaceholder(owner.stringOrDefault(tMap, "preOpenFormPlaceholder", t.getPreOpenFormPlaceholder()))
                        .withWelcomeMessage(owner.stringOrDefault(tMap, "welcomeMessage", t.getWelcomeMessage()))
                        .withOptionLabels(owner.parseCsvOrDefault(tMap, "optionLabels", t.getOptionLabels()))
                        .withOptions(options)
                        .withSupportRoleIds(owner.parseLongCsvOrDefault(tMap, "supportRoleIds", t.getSupportRoleIds()))
                        .withBlacklistedUserIds(owner.parseLongCsvOrDefault(tMap, "blacklistedUserIds", t.getBlacklistedUserIds()));
            }

            return current.withLanguage(language)
                    .withNotifications(n)
                    .withWelcome(w)
                    .withMessageLogs(l)
                    .withMusic(m)
                    .withPrivateRoom(p)
                    .withTicket(t);
        });

        owner.sendJson(exchange, 200, DataObject.empty().put("ok", true).put("language", updated.getLanguage()));
    }
}


