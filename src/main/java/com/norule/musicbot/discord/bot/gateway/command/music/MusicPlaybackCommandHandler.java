package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelController;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class MusicPlaybackCommandHandler {
    public static final String PLAY_PICK_PREFIX = "play:pick:";

    private final MusicCommandService owner;
    private final MusicPanelController panelController;
    private final MusicPlaybackText playbackText;
    private final Map<String, SearchRequest> searchRequests = new ConcurrentHashMap<>();
    public MusicPlaybackCommandHandler(MusicCommandService owner, MusicPanelController panelController, MusicPlaybackText playbackText) {
        this.owner = owner;
        this.panelController = panelController;
        this.playbackText = playbackText;
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        for (Map.Entry<String, SearchRequest> entry : searchRequests.entrySet()) {
            SearchRequest request = entry.getValue();
            if (request == null) {
                searchRequests.remove(entry.getKey());
            } else if (!cutoff.isBefore(request.expiresAt)
                    && searchRequests.remove(entry.getKey(), request)) {
                editExpiredSearch(entry.getKey(), request);
            }
        }
    }

    public void handleVolumeSlash(SlashCommandInteractionEvent event, String lang) {
        long guildId = event.getGuild().getIdLong();
        OptionMapping volumeOption = event.getOption("value");
        if (volumeOption == null) {
            volumeOption = event.getOption(MusicCommandService.OPTION_VOLUME_VALUE_ZH);
        }
        Integer raw = volumeOption == null ? null : (int) volumeOption.getAsLong();
        if (raw == null) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        int applied = owner.musicService().setVolume(event.getGuild(), raw);
        panelController.refreshPanel(guildId);
        event.reply(owner.musicUx(lang, "volume_set", Map.of("value", String.valueOf(applied))))
                .setEphemeral(true)
                .queue();
    }
    public void handleSpeedSlash(SlashCommandInteractionEvent event, String lang) {
        long guildId = event.getGuild().getIdLong();
        OptionMapping speedOption = event.getOption("value");
        if (speedOption == null) {
            speedOption = event.getOption(MusicCommandService.OPTION_SPEED_VALUE_ZH);
        }
        Double raw = speedOption == null ? null : speedOption.getAsDouble();
        if (raw == null) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        double applied = owner.musicService().setPlaybackSpeed(event.getGuild(), raw);
        panelController.refreshPanel(guildId);
        event.reply(owner.musicUx(lang, "speed_set", Map.of("value", String.format(java.util.Locale.ROOT, "%.2f", applied))))
                .setEphemeral(true)
                .queue();
    }
    public void handleJoinSlash(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(success -> handleJoin(event.getGuild(), event.getMember(),
                text -> event.getHook().editOriginal(text).queue()), failure -> {
        });
    }
    public void handlePlaySlash(SlashCommandInteractionEvent event, String lang) {
        event.deferReply(true).queue(success -> {
            TextChannel fallback = event.getChannelType() == ChannelType.TEXT
                    ? event.getChannel().asTextChannel()
                    : null;
            panelController.resolveOrCreateCommandChannel(event.getGuild(), fallback)
                    .thenAccept(panelChannel -> handlePlaySlashDeferred(event, lang, panelChannel));
        }, failure -> {
        });
    }

    private void handlePlaySlashDeferred(SlashCommandInteractionEvent event, String lang, TextChannel panelChannel) {
        String query = getPlayQuery(event);
        if (query.isBlank()) {
            event.getHook().editOriginal(owner.i18nService().t(lang, "music.not_found", Map.of("query", ""))).queue();
            return;
        }
        if (owner.musicService().isUrlLikeInput(query)) {
            directPlay(
                    event.getGuild(),
                    event.getMember(),
                    query,
                    text -> event.getHook().editOriginal(text).queue(),
                    panelChannel
            );
            return;
        }

        owner.musicService().searchTopTracks(query, 10, results -> {
            if (results.isEmpty()) {
                event.getHook().editOriginal(owner.i18nService().t(lang, "music.not_found", Map.of("query", query))).queue();
                return;
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            SearchRequest request = new SearchRequest(
                    event.getUser().getIdLong(),
                    panelChannel == null ? null : panelChannel.getIdLong(),
                    query,
                    results,
                    Instant.now().plusSeconds(30),
                    event.getHook(),
                    lang
            );
            searchRequests.put(token, request);
            event.getHook().editOriginalEmbeds(new EmbedBuilder()
                            .setColor(new Color(52, 152, 219))
                            .setTitle(owner.i18nService().t(lang, "music.search_title"))
                            .setDescription(owner.i18nService().t(lang, "music.search_desc", Map.of("seconds", "30")))
                            .build())
                    .setComponents(ActionRow.of(buildSearchMenu(token, results, lang)))
                    .queue(message -> owner.scheduler().schedule(() -> expireSearchMenu(token),
                            30, TimeUnit.SECONDS));
        }, error -> event.getHook().editOriginal(playbackText.mapMusicLoadError(lang, error)).queue());
    }
    public void handleSkipSlash(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(success -> handleSkip(event.getGuild(),
                text -> event.getHook().editOriginal(text).queue()), failure -> {
        });
    }
    public void handleStopSlash(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(success -> handleStop(event.getGuild(),
                text -> event.getHook().editOriginal(text).queue()), failure -> {
        });
    }
    public void handleLeaveSlash(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(success -> handleLeave(event.getGuild(),
                text -> event.getHook().editOriginal(text).queue()), failure -> {
        });
    }
    public void handleRepeatSlash(SlashCommandInteractionEvent event, String lang) {
        String mode = Objects.requireNonNull(event.getOption("mode")).getAsString();
        owner.musicService().setRepeatMode(event.getGuild(), normalizeRepeat(mode));
        panelController.refreshPanel(event.getGuild().getIdLong());
        TextChannel panelChannel = event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null;
        event.reply(playbackText.mapRepeatLabel(lang, owner.musicService().getRepeatMode(event.getGuild())))
                .setEphemeral(true)
                .queue(success -> panelController.moveActivePanelToBottom(event.getGuild(), panelChannel), error -> {
                });
    }
    public void handleTextCommand(MessageReceivedEvent event, Guild guild, String cmd, String arg, String lang) {
        switch (cmd) {
            case "volume" -> handleTextVolume(event, guild, arg, lang);
            case "speed" -> handleTextSpeed(event, guild, arg, lang);
            case "join" -> handleJoin(guild, event.getMember(),
                    text -> event.getChannel().sendMessage(text)
                            .queue(success -> panelController.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                            }));
            case "play" -> directPlay(
                    guild,
                    event.getMember(),
                    arg,
                    text -> event.getChannel().sendMessage(text).queue(),
                    event.getChannel().asTextChannel()
            );
            case "skip" -> handleSkip(guild,
                    text -> event.getChannel().sendMessage(text)
                            .queue(success -> panelController.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                            }));
            case "stop" -> handleStop(guild,
                    text -> event.getChannel().sendMessage(text)
                            .queue(success -> panelController.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                            }));
            case "leave" -> handleLeave(guild,
                    text -> event.getChannel().sendMessage(text)
                            .queue(success -> panelController.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                            }));
            case "repeat" -> {
                owner.musicService().setRepeatMode(guild, normalizeRepeat(arg));
                event.getChannel().sendMessage(playbackText.mapRepeatLabel(lang, owner.musicService().getRepeatMode(guild)))
                        .queue(success -> panelController.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                        });
            }
            default -> event.getChannel().sendMessage(owner.i18nService().t(lang, "general.unknown_command")).queue();
        }
    }
    public void handlePlayPick(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(PLAY_PICK_PREFIX.length());
        SearchRequest request = searchRequests.remove(token);
        if (request == null) {
            event.editMessage(owner.i18nService().t(lang, "music.search_expired"))
                    .setComponents(List.of())
                    .queue();
            return;
        }
        if (Instant.now().isAfter(request.expiresAt)) {
            event.editMessage(owner.i18nService().t(lang, "music.search_expired")).setComponents(List.of()).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        int index = Integer.parseInt(event.getValues().get(0));
        if (index < 0 || index >= request.results.size()) {
            event.reply(owner.i18nService().t(lang, "music.not_found", Map.of("query", request.query))).setEphemeral(true).queue();
            return;
        }
        AudioTrack picked = request.results.get(index);
        Member member = event.getMember();
        if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
            event.editMessage(owner.i18nService().t(lang, "music.join_first")).setComponents(List.of()).queue();
            return;
        }
        AudioChannel memberChannel = member.getVoiceState().getChannel();
        AudioChannel botChannel = event.getGuild().getAudioManager().getConnectedChannel();
        if (botChannel != null && botChannel.getIdLong() != memberChannel.getIdLong()) {
            event.editMessage(owner.i18nService().t(lang, "music.join_bot_voice_channel",
                            Map.of("channel", botChannel.getAsMention())))
                    .setComponents(List.of())
                    .queue();
            return;
        }
        if (botChannel == null) {
            owner.musicService().joinChannel(event.getGuild(), memberChannel);
        }
        if (request.channelId != null) {
            owner.musicService().rememberCommandChannel(event.getGuild().getIdLong(), request.channelId);
        }
        String identifier = picked.getInfo().uri != null ? picked.getInfo().uri : picked.getInfo().title;
        String sourceLabel = playbackText.detectSource(picked);
        boolean wasIdle = owner.musicService().getCurrentTitle(event.getGuild()) == null;
        int queuedBefore = owner.musicService().getQueueSnapshot(event.getGuild()).size();
        event.deferEdit().queue(ignored -> owner.musicService().queueTrackByIdentifier(
                event.getGuild(),
                identifier,
                sourceLabel,
                response -> {
                    if ("NO_MATCH".equals(response)) {
                        event.getHook().editOriginal(owner.i18nService().t(
                                lang,
                                "music.not_found",
                                Map.of("query", request.query)
                        )).setComponents(List.of()).queue();
                        return;
                    }
                    if (response != null && response.startsWith("LOAD_FAILED:")) {
                        event.getHook().editOriginal(playbackText.mapMusicLoadError(
                                lang,
                                response.substring("LOAD_FAILED:".length())
                        )).setComponents(List.of()).queue();
                        return;
                    }
                    String title = response == null || response.isBlank() ? picked.getInfo().title : response;
                    event.getHook().editOriginal(playbackSuccessText(
                            event.getGuild(),
                            lang,
                            title,
                            wasIdle,
                            queuedBefore,
                            event.getUser().getIdLong()
                    )).setComponents(List.of()).queue();
                    TextChannel panelChannel = request.channelId == null
                            ? null
                            : event.getGuild().getTextChannelById(request.channelId);
                    if (panelChannel != null) {
                        panelController.ensurePanelForChannel(event.getGuild(), panelChannel, lang);
                    }
                    panelController.refreshPanel(event.getGuild().getIdLong());
                },
                event.getUser().getIdLong(),
                event.getUser().getName()
        ));
    }

    private void handleTextVolume(MessageReceivedEvent event, Guild guild, String arg, String lang) {
        Integer value = parseIntSafe(arg);
        if (value == null) {
            event.getChannel().sendMessage(owner.musicUx(lang, "volume_usage")).queue();
            return;
        }
        int applied = owner.musicService().setVolume(guild, value);
        event.getChannel().sendMessage(owner.musicUx(lang, "volume_set", Map.of("value", String.valueOf(applied))))
                .queue(success -> panelController.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                });
    }

    private void handleTextSpeed(MessageReceivedEvent event, Guild guild, String arg, String lang) {
        Double value = parseDoubleSafe(arg);
        if (value == null) {
            event.getChannel().sendMessage(owner.musicUx(lang, "speed_usage")).queue();
            return;
        }
        double applied = owner.musicService().setPlaybackSpeed(guild, value);
        event.getChannel().sendMessage(owner.musicUx(lang, "speed_set",
                        Map.of("value", String.format(java.util.Locale.ROOT, "%.2f", applied))))
                .queue(success -> panelController.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                });
    }

    private void directPlay(Guild guild, Member member, String query, MusicCommandService.TextSink sink, TextChannel panelChannel) {
        String lang = owner.lang(guild.getIdLong());
        if (query == null || query.isBlank()) {
            sink.send(owner.i18nService().t(lang, "music.not_found", Map.of("query", "")));
            return;
        }
        if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
            sink.send(owner.i18nService().t(lang, "music.join_first"));
            return;
        }

        AudioChannel memberChannel = member.getVoiceState().getChannel();
        AudioChannel botConnected = guild.getAudioManager().getConnectedChannel();
        if (botConnected != null && botConnected.getIdLong() != memberChannel.getIdLong()) {
            sink.send(owner.i18nService().t(lang, "music.join_bot_voice_channel",
                    Map.of("channel", botConnected.getAsMention())));
            return;
        }
        if (!guild.getSelfMember().hasPermission(memberChannel, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)) {
            String missing = formatMissingPermissions(guild.getSelfMember(), memberChannel, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK);
            sink.send(owner.i18nService().t(lang, "general.missing_permissions", Map.of("permissions", missing)));
            return;
        }
        if (botConnected == null) {
            owner.musicService().joinChannel(guild, memberChannel);
        }
        if (panelChannel != null) {
            owner.musicService().rememberCommandChannel(guild.getIdLong(), panelChannel.getIdLong());
        }
        owner.musicService().setGuildStateListener(guild.getIdLong(), () -> panelController.refreshPanel(guild.getIdLong()));
        boolean wasIdle = owner.musicService().getCurrentTitle(guild) == null;
        int queuedBefore = owner.musicService().getQueueSnapshot(guild).size();
        owner.musicService().loadAndPlay(guild, response -> {
            if ("NO_MATCH".equals(response)) {
                sink.send(owner.i18nService().t(lang, "music.not_found", Map.of("query", query)));
            } else if (response.startsWith("LOAD_FAILED:")) {
                sink.send(playbackText.mapMusicLoadError(lang, response.substring("LOAD_FAILED:".length())));
            } else {
                sink.send(playbackSuccessText(
                        guild,
                        lang,
                        response,
                        wasIdle,
                        queuedBefore,
                        member.getIdLong()
                ));
                if (panelChannel != null) {
                    panelController.ensurePanelForChannel(guild, panelChannel, lang);
                }
            }
            panelController.refreshPanel(guild.getIdLong());
        }, query, member.getIdLong(), member.getEffectiveName());
    }

    private String playbackSuccessText(Guild guild,
                                       String lang,
                                       String title,
                                       boolean wasIdle,
                                       int queuedBefore,
                                       Long requesterId) {
        String currentTitle = owner.musicService().getCurrentTitle(guild);
        if (wasIdle && title != null && title.equals(currentTitle)) {
            return owner.musicText(lang, "play_started", Map.of("title", safe(title, 180)));
        }
        int resolvedPosition = owner.musicService().findQueuePosition(guild, title, requesterId);
        return owner.musicText(lang, "queue_added_position", Map.of(
                "title", safe(title, 180),
                "position", String.valueOf(resolvedPosition > 0
                        ? resolvedPosition
                        : Math.max(1, queuedBefore + 1))
        ));
    }

    private void handleJoin(Guild guild, Member member, MusicCommandService.TextSink sink) {
        String lang = owner.lang(guild.getIdLong());
        if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
            sink.send(owner.i18nService().t(lang, "music.join_first"));
            return;
        }
        AudioChannel voice = member.getVoiceState().getChannel();
        AudioChannel botConnected = guild.getAudioManager().getConnectedChannel();
        if (botConnected != null && botConnected.getIdLong() != voice.getIdLong()) {
            sink.send(owner.i18nService().t(lang, "music.not_same_voice_channel"));
            return;
        }
        if (!guild.getSelfMember().hasPermission(voice, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)) {
            String missing = formatMissingPermissions(guild.getSelfMember(), voice, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK);
            sink.send(owner.i18nService().t(lang, "general.missing_permissions", Map.of("permissions", missing)));
            return;
        }
        owner.musicService().joinChannel(guild, voice);
        owner.musicService().setGuildStateListener(guild.getIdLong(), () -> panelController.refreshPanel(guild.getIdLong()));
        sink.send(owner.i18nService().t(lang, "music.joined", Map.of("channel", voice.getAsMention())));
    }

    private void handleSkip(Guild guild, MusicCommandService.TextSink sink) {
        String lang = owner.lang(guild.getIdLong());
        if (guild.getAudioManager().getConnectedChannel() == null) {
            sink.send(owner.i18nService().t(lang, "music.not_connected"));
            return;
        }
        owner.musicService().skip(guild);
        sink.send(owner.i18nService().t(lang, "music.skipped"));
        panelController.refreshPanel(guild.getIdLong());
    }

    private void handleStop(Guild guild, MusicCommandService.TextSink sink) {
        String lang = owner.lang(guild.getIdLong());
        if (guild.getAudioManager().getConnectedChannel() == null) {
            sink.send(owner.i18nService().t(lang, "music.not_connected"));
            return;
        }
        owner.musicService().stop(guild);
        sink.send(owner.i18nService().t(lang, "music.stopped"));
        panelController.refreshPanel(guild.getIdLong());
    }

    private void handleLeave(Guild guild, MusicCommandService.TextSink sink) {
        String lang = owner.lang(guild.getIdLong());
        if (guild.getAudioManager().getConnectedChannel() == null) {
            sink.send(owner.i18nService().t(lang, "music.not_connected"));
            return;
        }
        owner.musicService().stop(guild);
        owner.musicService().leaveChannel(guild);
        sink.send(owner.i18nService().t(lang, "music.left"));
        panelController.refreshPanel(guild.getIdLong());
    }

    private Integer parseIntSafe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double parseDoubleSafe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getPlayQuery(SlashCommandInteractionEvent event) {
        OptionMapping queryOption = event.getOption("query");
        if (queryOption == null) {
            queryOption = event.getOption(MusicCommandService.OPTION_QUERY_ZH);
        }
        return queryOption == null ? "" : queryOption.getAsString().trim();
    }

    private StringSelectMenu buildSearchMenu(String token, List<AudioTrack> tracks, String lang) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(PLAY_PICK_PREFIX + token)
                .setPlaceholder(owner.musicText(lang, "search_placeholder"));
        for (int i = 0; i < tracks.size() && i < 10; i++) {
            AudioTrack track = tracks.get(i);
            String source = playbackText.detectSource(track);
            String duration = formatDuration(track.getDuration());
            String desc = safe(source + " | " + duration + " | " + track.getInfo().author, 100);
            menu.addOption(safe(track.getInfo().title, 100), String.valueOf(i), desc);
        }
        return menu.build();
    }

    private void expireSearchMenu(String token) {
        SearchRequest request = searchRequests.remove(token);
        if (request == null) {
            return;
        }
        editExpiredSearch(token, request);
    }

    private void editExpiredSearch(String token, SearchRequest request) {
        request.hook.editOriginalEmbeds(new EmbedBuilder()
                        .setColor(new Color(149, 165, 166))
                        .setTitle(owner.musicText(request.lang, "search_expired_title"))
                        .setDescription(owner.i18nService().t(request.lang, "music.search_expired"))
                        .build())
                .setComponents(ActionRow.of(buildSearchMenu(token, request.results, request.lang).asDisabled()))
                .queue(success -> {
                }, error -> {
                });
    }

    private String safe(String s, int max) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1);
    }

    private String formatDuration(long millis) {
        if (millis <= 0) {
            return "--:--";
        }
        long totalSeconds = millis / 1000L;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
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

    private String formatMissingPermissions(Member member, AudioChannel channel, Permission... permissions) {
        if (member == null || channel == null || permissions == null || permissions.length == 0) {
            return "-";
        }
        return java.util.Arrays.stream(permissions)
                .filter(permission -> !member.hasPermission(channel, permission))
                .map(Permission::getName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("-");
    }

    private static class SearchRequest {
        private final long requestUserId;
        private final Long channelId;
        private final String query;
        private final List<AudioTrack> results;
        private final Instant expiresAt;
        private final InteractionHook hook;
        private final String lang;

        SearchRequest(long requestUserId,
                      Long channelId,
                      String query,
                      List<AudioTrack> results,
                      Instant expiresAt,
                      InteractionHook hook,
                      String lang) {
            this.requestUserId = requestUserId;
            this.channelId = channelId;
            this.query = query;
            this.results = results;
            this.expiresAt = expiresAt;
            this.hook = hook;
            this.lang = lang;
        }
    }
}





