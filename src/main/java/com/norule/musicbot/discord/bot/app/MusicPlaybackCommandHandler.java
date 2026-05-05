package com.norule.musicbot.discord.bot.app;

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
    private final Map<String, SearchRequest> searchRequests = new ConcurrentHashMap<>();
    public MusicPlaybackCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        searchRequests.entrySet().removeIf(entry -> entry.getValue() == null || cutoff.isAfter(entry.getValue().expiresAt));
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
        owner.refreshPanel(guildId);
        TextChannel panelChannel = event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null;
        event.reply(owner.musicUx(lang, "volume_set", Map.of("value", String.valueOf(applied))))
                .queue(success -> owner.moveActivePanelToBottom(event.getGuild(), panelChannel), error -> {
                });
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
        owner.refreshPanel(guildId);
        TextChannel panelChannel = event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null;
        event.reply(owner.musicUx(lang, "speed_set", Map.of("value", String.format(java.util.Locale.ROOT, "%.2f", applied))))
                .queue(success -> owner.moveActivePanelToBottom(event.getGuild(), panelChannel), error -> {
                });
    }
    public void handleJoinSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue(success -> handleJoin(event.getGuild(), event.getMember(),
                text -> event.getHook().sendMessage(text)
                        .queue(message -> owner.moveActivePanelToBottom(event.getGuild(),
                                        event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null),
                                error -> {
                                })), failure -> {
        });
    }
    public void handlePlaySlash(SlashCommandInteractionEvent event, String lang) {
        event.deferReply().queue(success -> handlePlaySlashDeferred(event, lang), failure -> {
        });
    }

    private void handlePlaySlashDeferred(SlashCommandInteractionEvent event, String lang) {
        String query = getPlayQuery(event);
        if (query.isBlank()) {
            event.getHook().sendMessage(owner.i18nService().t(lang, "music.not_found", Map.of("query", ""))).queue();
            return;
        }
        if (looksLikeUrl(query)) {
            directPlay(
                    event.getGuild(),
                    event.getMember(),
                    query,
                    text -> event.getHook().sendMessage(text).queue(),
                    event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null
            );
            return;
        }

        owner.musicService().searchTopTracks(query, 10, results -> {
            if (results.isEmpty()) {
                event.getHook().sendMessage(owner.i18nService().t(lang, "music.not_found", Map.of("query", query))).queue();
                return;
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            Long requestChannelId = resolveSearchRequestChannelId(event);
            SearchRequest request = new SearchRequest(
                    event.getUser().getIdLong(),
                    requestChannelId,
                    query,
                    results,
                    Instant.now().plusSeconds(30)
            );
            searchRequests.put(token, request);
            event.getHook().sendMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(52, 152, 219))
                            .setTitle(owner.i18nService().t(lang, "music.search_title"))
                            .setDescription(owner.i18nService().t(lang, "music.search_desc", Map.of("seconds", "30")))
                            .build())
                    .setComponents(ActionRow.of(buildSearchMenu(token, results)))
                    .queue(message -> owner.scheduler().schedule(() -> expireSearchMenu(token, event.getGuild().getIdLong(), message.getIdLong()),
                            30, TimeUnit.SECONDS));
        }, error -> event.getHook().sendMessage(owner.mapMusicLoadError(lang, error)).queue());
    }
    public void handleSkipSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue(success -> handleSkip(event.getGuild(),
                text -> event.getHook().sendMessage(text)
                        .queue(message -> owner.moveActivePanelToBottom(event.getGuild(),
                                        event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null),
                                error -> {
                                })), failure -> {
        });
    }
    public void handleStopSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue(success -> handleStop(event.getGuild(),
                text -> event.getHook().sendMessage(text)
                        .queue(message -> owner.moveActivePanelToBottom(event.getGuild(),
                                        event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null),
                                error -> {
                                })), failure -> {
        });
    }
    public void handleLeaveSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue(success -> handleLeave(event.getGuild(),
                text -> event.getHook().sendMessage(text)
                        .queue(message -> owner.moveActivePanelToBottom(event.getGuild(),
                                        event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null),
                                error -> {
                                })), failure -> {
        });
    }
    public void handleRepeatSlash(SlashCommandInteractionEvent event, String lang) {
        String mode = Objects.requireNonNull(event.getOption("mode")).getAsString();
        owner.musicService().setRepeatMode(event.getGuild(), normalizeRepeat(mode));
        owner.refreshPanel(event.getGuild().getIdLong());
        TextChannel panelChannel = event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null;
        event.reply(owner.mapRepeatLabel(lang, owner.musicService().getRepeatMode(event.getGuild())))
                .setEphemeral(true)
                .queue(success -> owner.moveActivePanelToBottom(event.getGuild(), panelChannel), error -> {
                });
    }
    public void handleTextCommand(MessageReceivedEvent event, Guild guild, String cmd, String arg, String lang) {
        switch (cmd) {
            case "volume" -> handleTextVolume(event, guild, arg, lang);
            case "speed" -> handleTextSpeed(event, guild, arg, lang);
            case "join" -> handleJoin(guild, event.getMember(),
                    text -> event.getChannel().sendMessage(text)
                            .queue(success -> owner.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
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
                            .queue(success -> owner.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                            }));
            case "stop" -> handleStop(guild,
                    text -> event.getChannel().sendMessage(text)
                            .queue(success -> owner.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                            }));
            case "leave" -> handleLeave(guild,
                    text -> event.getChannel().sendMessage(text)
                            .queue(success -> owner.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                            }));
            case "repeat" -> {
                owner.musicService().setRepeatMode(guild, normalizeRepeat(arg));
                event.getChannel().sendMessage(owner.mapRepeatLabel(lang, owner.musicService().getRepeatMode(guild)))
                        .queue(success -> owner.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                        });
            }
            default -> event.getChannel().sendMessage(owner.i18nService().t(lang, "general.unknown_command")).queue();
        }
    }
    public void handlePlayPick(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(PLAY_PICK_PREFIX.length());
        SearchRequest request = searchRequests.remove(token);
        if (request == null) {
            event.reply(owner.i18nService().t(lang, "music.search_expired")).setEphemeral(true).queue();
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
            event.reply(owner.i18nService().t(lang, "music.join_first")).setEphemeral(true).queue();
            return;
        }
        AudioChannel memberChannel = member.getVoiceState().getChannel();
        AudioChannel botChannel = event.getGuild().getAudioManager().getConnectedChannel();
        if (botChannel != null && botChannel.getIdLong() != memberChannel.getIdLong()) {
            event.reply(owner.i18nService().t(lang, "music.join_bot_voice_channel",
                            Map.of("channel", botChannel.getAsMention())))
                    .setEphemeral(true)
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
        String sourceLabel = owner.detectSource(picked);
        owner.musicService().queueTrackByIdentifier(
                event.getGuild(),
                identifier,
                sourceLabel,
                ignored -> owner.refreshPanel(event.getGuild().getIdLong()),
                event.getUser().getIdLong(),
                event.getUser().getName()
        );
        if (request.channelId != null) {
            TextChannel panelChannel = event.getGuild().getTextChannelById(request.channelId);
            if (panelChannel != null) {
                owner.recreatePanelForChannel(event.getGuild(), panelChannel, lang);
            }
        }
        owner.refreshPanel(event.getGuild().getIdLong());
        event.editMessage(owner.musicUx(lang, "queue_added", Map.of("title", picked.getInfo().title)))
                .setComponents(List.of())
                .queue();
    }

    private void handleTextVolume(MessageReceivedEvent event, Guild guild, String arg, String lang) {
        Integer value = parseIntSafe(arg);
        if (value == null) {
            event.getChannel().sendMessage(owner.musicUx(lang, "volume_usage")).queue();
            return;
        }
        int applied = owner.musicService().setVolume(guild, value);
        event.getChannel().sendMessage(owner.musicUx(lang, "volume_set", Map.of("value", String.valueOf(applied))))
                .queue(success -> owner.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
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
                .queue(success -> owner.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
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
        owner.musicService().setGuildStateListener(guild.getIdLong(), () -> owner.refreshPanel(guild.getIdLong()));
        owner.musicService().loadAndPlay(guild, response -> {
            if ("NO_MATCH".equals(response)) {
                sink.send(owner.i18nService().t(lang, "music.not_found", Map.of("query", query)));
            } else if (response.startsWith("LOAD_FAILED:")) {
                sink.send(owner.mapMusicLoadError(lang, response.substring("LOAD_FAILED:".length())));
            } else {
                sink.send(owner.musicUx(lang, "queue_added", Map.of("title", response)));
                if (panelChannel != null) {
                    owner.recreatePanelForChannel(guild, panelChannel, lang);
                }
            }
            owner.refreshPanel(guild.getIdLong());
        }, query, member.getIdLong(), member.getEffectiveName());
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
        owner.musicService().setGuildStateListener(guild.getIdLong(), () -> owner.refreshPanel(guild.getIdLong()));
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
        owner.refreshPanel(guild.getIdLong());
    }

    private void handleStop(Guild guild, MusicCommandService.TextSink sink) {
        String lang = owner.lang(guild.getIdLong());
        if (guild.getAudioManager().getConnectedChannel() == null) {
            sink.send(owner.i18nService().t(lang, "music.not_connected"));
            return;
        }
        owner.musicService().stop(guild);
        sink.send(owner.i18nService().t(lang, "music.stopped"));
        owner.refreshPanel(guild.getIdLong());
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
        owner.refreshPanel(guild.getIdLong());
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

    private boolean looksLikeUrl(String input) {
        if (input == null) {
            return false;
        }
        String v = input.trim().toLowerCase();
        return v.startsWith("http://") || v.startsWith("https://");
    }

    private String getPlayQuery(SlashCommandInteractionEvent event) {
        OptionMapping queryOption = event.getOption("query");
        if (queryOption == null) {
            queryOption = event.getOption(MusicCommandService.OPTION_QUERY_ZH);
        }
        return queryOption == null ? "" : queryOption.getAsString().trim();
    }

    private Long resolveSearchRequestChannelId(SlashCommandInteractionEvent event) {
        if (event == null || event.getGuild() == null) {
            return null;
        }
        if (event.getChannelType() == ChannelType.TEXT) {
            return event.getChannel().getIdLong();
        }
        var configuredMusic = owner.settingsService().getMusic(event.getGuild().getIdLong());
        if (configuredMusic != null && configuredMusic.getCommandChannelId() != null) {
            TextChannel configured = event.getGuild().getTextChannelById(configuredMusic.getCommandChannelId());
            if (configured != null) {
                return configured.getIdLong();
            }
        }
        Long remembered = owner.musicService().getLastCommandChannelId(event.getGuild().getIdLong());
        if (remembered != null && event.getGuild().getTextChannelById(remembered) != null) {
            return remembered;
        }
        return null;
    }

    private StringSelectMenu buildSearchMenu(String token, List<AudioTrack> tracks) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(PLAY_PICK_PREFIX + token)
                .setPlaceholder("Select one track (30s)");
        for (int i = 0; i < tracks.size() && i < 10; i++) {
            AudioTrack track = tracks.get(i);
            String source = owner.detectSource(track);
            String duration = formatDuration(track.getDuration());
            String desc = safe(source + " | " + duration + " | " + track.getInfo().author, 100);
            menu.addOption(safe(track.getInfo().title, 100), String.valueOf(i), desc);
        }
        return menu.build();
    }

    private void expireSearchMenu(String token, long guildId, long messageId) {
        SearchRequest request = searchRequests.remove(token);
        if (request == null || owner.currentJda() == null) {
            return;
        }
        Guild guild = owner.currentJda().getGuildById(guildId);
        if (guild == null) {
            return;
        }
        String lang = owner.lang(guildId);
        if (request.channelId == null) {
            return;
        }
        TextChannel channel = guild.getTextChannelById(request.channelId);
        if (channel == null) {
            return;
        }
        channel.editMessageById(messageId, owner.i18nService().t(lang, "music.search_timeout"))
                .setComponents(List.of())
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

        SearchRequest(long requestUserId, Long channelId, String query, List<AudioTrack> results, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.channelId = channelId;
            this.query = query;
            this.results = results;
            this.expiresAt = expiresAt;
        }
    }
}




