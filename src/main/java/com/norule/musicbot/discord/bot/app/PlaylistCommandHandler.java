package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.domain.music.MusicDataService;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.Command;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlaylistCommandHandler {
    public static final String LIST_BUTTON_PREFIX = "playlist:list:";
    public static final String VIEW_BUTTON_PREFIX = "playlist:view:";
    public static final String TRACK_REMOVE_CONFIRM_PREFIX = "playlist:track:remove:confirm:";
    public static final String TRACK_REMOVE_CANCEL_PREFIX = "playlist:track:remove:cancel:";
    private static final String SCOPE_MINE = "mine";
    private static final String SCOPE_ALL = "all";
    private static final int LIST_PAGE_SIZE = 10;

    private final MusicCommandService owner;
    private final Map<String, PlaylistViewRequest> playlistViewRequests = new ConcurrentHashMap<>();
    private final Map<String, PlaylistTrackRemoveRequest> playlistTrackRemoveRequests = new ConcurrentHashMap<>();
    public PlaylistCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }
    public void handlePlaylistSlash(SlashCommandInteractionEvent event, String lang) {
        String sub = owner.canonicalPlaylistSubcommand(event.getSubcommandName());
        if (sub == null || sub.isBlank()) {
            replyPlaylistListOverview(event, lang);
            return;
        }
        String playlistName = event.getOption("name") == null ? "" : event.getOption("name").getAsString().trim();
        String playlistUrl = event.getOption("url") == null ? "" : event.getOption("url").getAsString().trim();
        String playlistCode = event.getOption("code") == null ? "" : event.getOption("code").getAsString().trim();
        String playlistScope = event.getOption("scope") == null ? SCOPE_MINE : event.getOption("scope").getAsString().trim();
        switch (sub) {
            case "list" -> replyPlaylistList(event, lang, playlistScope);
            case "save" -> handleSlashSave(event, lang, playlistName);
            case "load" -> handleSlashLoad(event, lang, playlistName);
            case "add" -> handleSlashAdd(event, lang, playlistName, playlistUrl);
            case "delete" -> handleSlashDelete(event, lang, playlistName);
            case "remove-track" -> replyRemoveTrackConfirmation(event, lang, playlistName);
            case "export" -> handleSlashExport(event, lang, playlistName);
            case "import" -> handleSlashImport(event, lang, playlistCode, playlistName);
            case "view" -> replyPlaylistView(event, lang, playlistName);
            default -> event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
        }
    }
    public void handlePlaylistPrefix(MessageReceivedEvent event, Guild guild, String arg, String lang) {
        String[] parts = arg == null ? new String[0] : arg.trim().split("\\s+", 2);
        String action = parts.length == 0 ? "" : parts[0].toLowerCase(Locale.ROOT);
        String actionArg = parts.length > 1 ? parts[1].trim() : "";
        String playlistName = actionArg;
        switch (action) {
            case "", "list" -> sendPlaylistList(event, guild, lang, actionArg);
            case "save" -> handleTextSave(event, guild, lang, playlistName);
            case "load" -> handleTextLoad(event, guild, lang, playlistName);
            case "add" -> handleTextAdd(event, guild, lang, playlistName);
            case "delete" -> handleTextDelete(event, guild, lang, playlistName);
            case "view" -> sendPlaylistView(event, guild, lang, playlistName);
            case "export" -> handleTextExport(event, guild, lang, playlistName);
            case "import" -> handleTextImport(event, guild, lang, actionArg);
            default -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_usage")).queue();
        }
    }

    void replyPlaylistListOverview(SlashCommandInteractionEvent event, String lang) {
        event.replyEmbeds(playlistListEmbed(event.getGuild(), lang, null, 0).build())
                .setEphemeral(true)
                .queue();
    }

    void replyPlaylistList(SlashCommandInteractionEvent event, String lang, String scope) {
        Long ownerIdFilter = resolveOwnerFilter(scope, event.getUser().getIdLong());
        event.replyEmbeds(playlistListEmbed(event.getGuild(), lang, ownerIdFilter, 0).build())
                .addComponents(ActionRow.of(playlistListButtons(lang, scope, event.getUser().getIdLong(), 0, playlistListTotalPages(event.getGuild(), ownerIdFilter))))
                .setEphemeral(true)
                .queue();
    }

    void sendPlaylistList(MessageReceivedEvent event, Guild guild, String lang, String scopeArg) {
        Long ownerIdFilter = isOwnScope(scopeArg) ? event.getAuthor().getIdLong() : null;
        String scope = ownerIdFilter == null ? SCOPE_ALL : SCOPE_MINE;
        event.getChannel().sendMessageEmbeds(playlistListEmbed(guild, lang, ownerIdFilter, 0).build())
                .setComponents(ActionRow.of(playlistListButtons(lang, scope, event.getAuthor().getIdLong(), 0, playlistListTotalPages(guild, ownerIdFilter))))
                .queue();
    }

    void replyPlaylistView(SlashCommandInteractionEvent event, String lang, String playlistName) {
        if (playlistName.isBlank()) {
            event.reply(owner.musicText(lang, "playlist_name_required")).setEphemeral(true).queue();
            return;
        }
        String token = rememberPlaylistViewRequest(event.getGuild().getIdLong(), event.getUser().getIdLong(), playlistName);
        EmbedBuilder embed = playlistViewEmbed(event.getGuild(), lang, playlistName, 0);
        if (embed == null) {
            event.reply(owner.musicText(lang, "playlist_view_missing", Map.of("name", playlistName))).setEphemeral(true).queue();
            return;
        }
        event.replyEmbeds(embed.build())
                .addComponents(ActionRow.of(playlistViewButtons(lang, token, 0, playlistViewTotalPages(event.getGuild(), playlistName))))
                .setEphemeral(true)
                .queue();
    }

    void sendPlaylistView(MessageReceivedEvent event, Guild guild, String lang, String playlistName) {
        if (playlistName.isBlank()) {
            event.getChannel().sendMessage(owner.musicUx(lang, "playlist_usage")).queue();
            return;
        }
        String token = rememberPlaylistViewRequest(guild.getIdLong(), event.getAuthor().getIdLong(), playlistName);
        EmbedBuilder embed = playlistViewEmbed(guild, lang, playlistName, 0);
        if (embed == null) {
            event.getChannel().sendMessage(owner.musicUx(lang, "playlist_view_missing", Map.of("name", playlistName))).queue();
            return;
        }
        event.getChannel().sendMessageEmbeds(embed.build())
                .setComponents(ActionRow.of(playlistViewButtons(lang, token, 0, playlistViewTotalPages(guild, playlistName))))
                .queue();
    }

    void replyRemoveTrackConfirmation(SlashCommandInteractionEvent event, String lang, String playlistName) {
        if (playlistName.isBlank()) {
            event.reply(owner.musicText(lang, "playlist_name_required")).setEphemeral(true).queue();
            return;
        }
        long guildId = event.getGuild().getIdLong();
        int index = event.getOption("index") == null ? 0 : (int) event.getOption("index").getAsLong();
        MusicDataService.PlaylistSummary summary = owner.musicService().getPlaylistSummary(guildId, playlistName);
        if (summary == null) {
            event.reply(owner.musicText(lang, "playlist_view_missing", Map.of("name", playlistName))).setEphemeral(true).queue();
            return;
        }
        long requesterId = event.getUser().getIdLong();
        if (summary.ownerId() != null && summary.ownerId() != requesterId) {
            event.reply(owner.musicText(lang, "playlist_track_remove_not_owner", Map.of(
                            "name", summary.name(),
                            "owner", summary.ownerName() == null || summary.ownerName().isBlank() ? "-" : summary.ownerName()
                    )))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        List<MusicDataService.PlaybackEntry> tracks = owner.musicService().getPlaylistTracks(guildId, summary.name());
        if (tracks.isEmpty()) {
            event.reply(owner.musicText(lang, "playlist_view_empty")).setEphemeral(true).queue();
            return;
        }
        if (index <= 0 || index > tracks.size()) {
            event.reply(owner.musicText(lang, "playlist_track_remove_invalid_index", Map.of(
                            "index", String.valueOf(index),
                            "count", String.valueOf(tracks.size())
                    )) + "\n" + owner.musicText(lang, "playlist_track_remove_hint", Map.of("name", summary.name())))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        MusicDataService.PlaybackEntry target = tracks.get(index - 1);
        String title = target == null ? "-" : safe(target.title(), 80);
        String token = rememberPlaylistTrackRemoveRequest(guildId, requesterId, summary.name(), index);
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(231, 76, 60))
                        .setTitle("\uD83D\uDDD1\uFE0F " + owner.musicText(lang, "playlist_track_remove_confirm_title"))
                        .setDescription(owner.musicText(lang, "playlist_track_remove_confirm_desc", Map.of(
                                "name", summary.name(),
                                "index", String.valueOf(index),
                                "title", title
                        )))
                        .build())
                .addComponents(ActionRow.of(
                        Button.danger(TRACK_REMOVE_CONFIRM_PREFIX + token, owner.i18nService().t(lang, "delete.confirm_button")),
                        Button.secondary(TRACK_REMOVE_CANCEL_PREFIX + token, owner.i18nService().t(lang, "delete.cancel_button"))
                ))
                .setEphemeral(true)
                .queue();
    }

    private void handleSlashSave(SlashCommandInteractionEvent event, String lang, String playlistName) {
        if (playlistName.isBlank()) {
            event.reply(owner.musicText(lang, "playlist_name_required")).setEphemeral(true).queue();
            return;
        }
        MusicDataService.PlaylistSaveResult saved = owner.musicService().saveCurrentPlaylist(
                event.getGuild(),
                playlistName,
                event.getUser().getIdLong(),
                event.getMember() == null ? event.getUser().getName() : event.getMember().getEffectiveName()
        );
        switch (saved.status()) {
            case SUCCESS -> event.reply(owner.musicText(lang, "playlist_save_success", Map.of(
                            "name", saved.playlistName(),
                            "count", String.valueOf(saved.trackCount())
                    )))
                    .setEphemeral(true)
                    .queue();
            case NAME_CONFLICT -> event.reply(playlistNameConflictText(lang, saved.playlistName(), saved.ownerName())).setEphemeral(true).queue();
            case DUPLICATE -> event.reply(owner.musicText(lang, "playlist_save_duplicate", Map.of("name", saved.playlistName())))
                    .setEphemeral(true)
                    .queue();
            default -> event.reply(owner.musicText(lang, "playlist_save_empty")).setEphemeral(true).queue();
        }
    }

    private void handleSlashLoad(SlashCommandInteractionEvent event, String lang, String playlistName) {
        if (playlistName.isBlank()) {
            event.reply(owner.musicText(lang, "playlist_name_required")).setEphemeral(true).queue();
            return;
        }
        if (!ensureMemberReadyForPlaylistLoad(event.getGuild(), event.getMember(), text -> event.reply(text).setEphemeral(true).queue())) {
            return;
        }
        AudioChannel memberChannel = event.getMember().getVoiceState().getChannel();
        var botVoiceState = event.getGuild().getSelfMember().getVoiceState();
        if (botVoiceState == null || !botVoiceState.inAudioChannel()) {
            owner.musicService().joinChannel(event.getGuild(), memberChannel);
        }
        int queued = owner.musicService().loadPlaylist(
                event.getGuild(),
                playlistName,
                ignored -> { },
                event.getUser().getIdLong(),
                event.getMember().getEffectiveName(),
                owner.musicText(lang, "playlist_source")
        );
        if (queued <= 0) {
            event.reply(owner.musicText(lang, "playlist_load_missing", Map.of("name", playlistName))).setEphemeral(true).queue();
            return;
        }
        owner.refreshPanel(event.getGuild().getIdLong());
        TextChannel panelChannel = event.getChannelType() == ChannelType.TEXT ? event.getChannel().asTextChannel() : null;
        event.reply(owner.musicText(lang, "playlist_load_success", Map.of("name", playlistName, "count", String.valueOf(queued))))
                .setEphemeral(true)
                .queue(success -> owner.moveActivePanelToBottom(event.getGuild(), panelChannel), error -> {
                });
    }

    private void handleSlashDelete(SlashCommandInteractionEvent event, String lang, String playlistName) {
        boolean allowManageOverride = event.getMember() != null && event.getMember().hasPermission(Permission.MANAGE_SERVER);
        if (playlistName.isBlank()) {
            event.reply(owner.musicText(lang, "playlist_name_required")).setEphemeral(true).queue();
            return;
        }
        MusicDataService.PlaylistDeleteResult removed = owner.musicService().deletePlaylist(
                event.getGuild().getIdLong(),
                playlistName,
                event.getUser().getIdLong(),
                allowManageOverride
        );
        switch (removed.status()) {
            case SUCCESS -> event.reply(owner.musicText(lang, "playlist_delete_success", Map.of("name", removed.playlistName()))).setEphemeral(true).queue();
            case NOT_OWNER -> event.reply(playlistDeleteForbiddenText(lang, removed.playlistName(), removed.ownerName())).setEphemeral(true).queue();
            default -> event.reply(owner.musicText(lang, "playlist_delete_missing", Map.of("name", playlistName))).setEphemeral(true).queue();
        }
    }

    private void handleSlashAdd(SlashCommandInteractionEvent event, String lang, String playlistName, String playlistUrl) {
        if (playlistName.isBlank()) {
            event.reply(owner.musicText(lang, "playlist_name_required")).setEphemeral(true).queue();
            return;
        }
        if (playlistUrl.isBlank()) {
            event.reply(owner.musicText(lang, "playlist_add_no_track")).setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(hook -> owner.musicService().addTrackToPlaylistByInput(
                event.getGuild(),
                playlistName,
                playlistUrl,
                event.getUser().getIdLong(),
                event.getMember() == null ? event.getUser().getName() : event.getMember().getEffectiveName(),
                added -> hook.sendMessage(formatAddResult(lang, playlistName, added)).queue(),
                error -> hook.sendMessage(owner.musicText(lang, "playlist_add_failed", Map.of("reason", safe(error, 120)))).queue()
        ));
    }

    private void handleSlashExport(SlashCommandInteractionEvent event, String lang, String playlistName) {
        if (playlistName.isBlank()) {
            event.reply(owner.musicText(lang, "playlist_name_required")).setEphemeral(true).queue();
            return;
        }
        MusicDataService.PlaylistShareCode share = owner.musicService().exportPlaylist(event.getGuild().getIdLong(), playlistName);
        if (share == null) {
            event.reply(owner.musicText(lang, "playlist_export_missing", Map.of("name", playlistName))).setEphemeral(true).queue();
            return;
        }
        event.reply(owner.musicText(lang, "playlist_export_success", Map.of(
                        "name", share.playlistName(),
                        "count", String.valueOf(share.trackCount()),
                        "code", share.code(),
                        "minutes", "3"
                )))
                .setEphemeral(true)
                .queue();
    }

    private void handleSlashImport(SlashCommandInteractionEvent event, String lang, String playlistCode, String playlistName) {
        if (playlistCode.isBlank()) {
            event.reply(owner.musicText(lang, "playlist_code_required")).setEphemeral(true).queue();
            return;
        }
        MusicDataService.PlaylistImportResult imported = owner.musicService().importPlaylist(
                event.getGuild().getIdLong(),
                playlistCode,
                playlistName,
                event.getUser().getIdLong(),
                event.getMember() == null ? event.getUser().getName() : event.getMember().getEffectiveName()
        );
        switch (imported.status()) {
            case SUCCESS -> event.reply(owner.musicText(lang, "playlist_import_success", Map.of(
                            "name", imported.playlistName(),
                            "count", String.valueOf(imported.trackCount()),
                            "code", playlistCode
                    )))
                    .setEphemeral(true)
                    .queue();
            case NAME_CONFLICT -> event.reply(playlistNameConflictText(lang, imported.playlistName(), imported.ownerName())).setEphemeral(true).queue();
            default -> event.reply(owner.musicText(lang, "playlist_import_invalid_code", Map.of("code", playlistCode))).setEphemeral(true).queue();
        }
    }

    private void handleTextSave(MessageReceivedEvent event, Guild guild, String lang, String playlistName) {
        if (playlistName.isBlank()) {
            event.getChannel().sendMessage(owner.musicUx(lang, "playlist_usage")).queue();
            return;
        }
        MusicDataService.PlaylistSaveResult saved = owner.musicService().saveCurrentPlaylist(
                guild,
                playlistName,
                event.getAuthor().getIdLong(),
                event.getMember() == null ? event.getAuthor().getName() : event.getMember().getEffectiveName()
        );
        switch (saved.status()) {
            case SUCCESS -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_save_success", Map.of(
                    "name", saved.playlistName(),
                    "count", String.valueOf(saved.trackCount())
            ))).queue();
            case NAME_CONFLICT -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_name_conflict", Map.of(
                    "name", saved.playlistName(),
                    "owner", saved.ownerName()
            ))).queue();
            case DUPLICATE -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_save_duplicate", Map.of("name", saved.playlistName()))).queue();
            default -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_save_empty")).queue();
        }
    }

    private void handleTextLoad(MessageReceivedEvent event, Guild guild, String lang, String playlistName) {
        if (playlistName.isBlank()) {
            event.getChannel().sendMessage(owner.musicUx(lang, "playlist_usage")).queue();
            return;
        }
        if (!ensureMemberReadyForPlaylistLoad(guild, event.getMember(), text -> event.getChannel().sendMessage(text).queue())) {
            return;
        }
        AudioChannel memberChannel = event.getMember().getVoiceState().getChannel();
        var botVoiceState = guild.getSelfMember().getVoiceState();
        if (botVoiceState == null || !botVoiceState.inAudioChannel()) {
            owner.musicService().joinChannel(guild, memberChannel);
        }
        int queued = owner.musicService().loadPlaylist(guild, playlistName, ignored -> { }, event.getAuthor().getIdLong(), event.getMember().getEffectiveName());
        if (queued <= 0) {
            event.getChannel().sendMessage(owner.musicUx(lang, "playlist_load_missing", Map.of("name", playlistName))).queue();
            return;
        }
        owner.refreshPanel(guild.getIdLong());
        event.getChannel().sendMessage(owner.musicUx(lang, "playlist_load_success", Map.of("name", playlistName, "count", String.valueOf(queued))))
                .queue(success -> owner.moveActivePanelToBottom(guild, event.getChannel().asTextChannel()), error -> {
                });
    }

    private void handleTextDelete(MessageReceivedEvent event, Guild guild, String lang, String playlistName) {
        if (playlistName.isBlank()) {
            event.getChannel().sendMessage(owner.musicUx(lang, "playlist_usage")).queue();
            return;
        }
        boolean allowManageOverride = event.getMember() != null && event.getMember().hasPermission(Permission.MANAGE_SERVER);
        MusicDataService.PlaylistDeleteResult removed = owner.musicService().deletePlaylist(guild.getIdLong(), playlistName, event.getAuthor().getIdLong(), allowManageOverride);
        switch (removed.status()) {
            case SUCCESS -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_delete_success", Map.of("name", removed.playlistName()))).queue();
            case NOT_OWNER -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_delete_not_owner", Map.of(
                    "name", removed.playlistName(),
                    "owner", removed.ownerName()
            ))).queue();
            default -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_delete_missing", Map.of("name", playlistName))).queue();
        }
    }

    private void handleTextAdd(MessageReceivedEvent event, Guild guild, String lang, String playlistName) {
        String[] addParts = playlistName.isBlank() ? new String[0] : playlistName.split("\\s+", 2);
        String resolvedPlaylistName = addParts.length > 0 ? addParts[0].trim() : "";
        String playlistUrl = addParts.length > 1 ? addParts[1].trim() : "";
        if (resolvedPlaylistName.isBlank() || playlistUrl.isBlank()) {
            event.getChannel().sendMessage(owner.musicUx(lang, "playlist_usage")).queue();
            return;
        }
        owner.musicService().addTrackToPlaylistByInput(
                guild,
                resolvedPlaylistName,
                playlistUrl,
                event.getAuthor().getIdLong(),
                event.getMember() == null ? event.getAuthor().getName() : event.getMember().getEffectiveName(),
                added -> event.getChannel().sendMessage(formatAddResult(lang, resolvedPlaylistName, added)).queue(),
                error -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_add_failed", Map.of("reason", safe(error, 120)))).queue()
        );
    }

    private String formatAddResult(String lang, String playlistName, MusicDataService.PlaylistTrackAddResult added) {
        return switch (added.status()) {
            case SUCCESS -> owner.musicText(lang, "playlist_add_success", Map.of(
                    "name", added.playlistName(),
                    "title", added.addedTitle(),
                    "count", String.valueOf(added.trackCount())
            ));
            case NOT_OWNER -> owner.musicText(lang, "playlist_add_not_owner", Map.of(
                    "name", added.playlistName(),
                    "owner", added.ownerName()
            ));
            case LIMIT_REACHED -> owner.musicText(lang, "playlist_add_limit", Map.of(
                    "name", added.playlistName(),
                    "count", String.valueOf(added.trackCount())
            ));
            case DUPLICATE -> owner.musicText(lang, "playlist_add_duplicate", Map.of(
                    "name", added.playlistName(),
                    "title", added.addedTitle()
            ));
            case EMPTY -> owner.musicText(lang, "playlist_add_no_track");
            default -> owner.musicText(lang, "playlist_view_missing", Map.of("name", playlistName));
        };
    }

    private void handleTextExport(MessageReceivedEvent event, Guild guild, String lang, String playlistName) {
        if (playlistName.isBlank()) {
            event.getChannel().sendMessage(owner.musicUx(lang, "playlist_usage")).queue();
            return;
        }
        MusicDataService.PlaylistShareCode share = owner.musicService().exportPlaylist(guild.getIdLong(), playlistName);
        if (share == null) {
            event.getChannel().sendMessage(owner.musicUx(lang, "playlist_export_missing", Map.of("name", playlistName))).queue();
            return;
        }
        event.getChannel().sendMessage(owner.musicUx(lang, "playlist_export_success", Map.of(
                "name", share.playlistName(),
                "count", String.valueOf(share.trackCount()),
                "code", share.code(),
                "minutes", "3"
        ))).queue();
    }

    private void handleTextImport(MessageReceivedEvent event, Guild guild, String lang, String actionArg) {
        String[] importParts = actionArg.isBlank() ? new String[0] : actionArg.split("\\s+", 2);
        String code = importParts.length > 0 ? importParts[0].trim() : "";
        String targetName = importParts.length > 1 ? importParts[1].trim() : "";
        if (code.isBlank()) {
            event.getChannel().sendMessage(owner.musicUx(lang, "playlist_usage")).queue();
            return;
        }
        MusicDataService.PlaylistImportResult imported = owner.musicService().importPlaylist(
                guild.getIdLong(),
                code,
                targetName,
                event.getAuthor().getIdLong(),
                event.getMember() == null ? event.getAuthor().getName() : event.getMember().getEffectiveName()
        );
        switch (imported.status()) {
            case SUCCESS -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_import_success", Map.of(
                    "name", imported.playlistName(),
                    "count", String.valueOf(imported.trackCount()),
                    "code", code
            ))).queue();
            case NAME_CONFLICT -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_name_conflict", Map.of(
                    "name", imported.playlistName(),
                    "owner", imported.ownerName()
            ))).queue();
            default -> event.getChannel().sendMessage(owner.musicUx(lang, "playlist_import_invalid_code", Map.of("code", code))).queue();
        }
    }
    public void handlePlaylistListButtons(ButtonInteractionEvent event, String lang) {
        String[] parts = event.getComponentId().split(":");
        if (parts.length < 6) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        long requesterId;
        int page;
        try {
            requesterId = Long.parseLong(parts[5]);
            page = Integer.parseInt(parts[4]);
        } catch (NumberFormatException ex) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != requesterId) {
            event.reply(owner.i18nService().t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        String scope = parts[2];
        Long ownerIdFilter = resolveOwnerFilter(scope, requesterId);
        int totalPages = playlistListTotalPages(event.getGuild(), ownerIdFilter);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        event.editMessageEmbeds(playlistListEmbed(event.getGuild(), lang, ownerIdFilter, safePage).build())
                .setComponents(ActionRow.of(playlistListButtons(lang, scope, requesterId, safePage, totalPages)))
                .queue();
    }
    public void handlePlaylistViewButtons(ButtonInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(VIEW_BUTTON_PREFIX.length());
        String[] parts = token.split(":");
        if (parts.length < 3) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        PlaylistViewRequest request = playlistViewRequests.get(parts[0]);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            playlistViewRequests.remove(parts[0]);
            event.reply(owner.musicText(lang, "playlist_view_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        int page;
        try {
            page = Integer.parseInt(parts[2]);
        } catch (NumberFormatException ex) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        int totalPages = playlistViewTotalPages(event.getGuild(), request.playlistName);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        EmbedBuilder embed = playlistViewEmbed(event.getGuild(), lang, request.playlistName, safePage);
        if (embed == null) {
            playlistViewRequests.remove(parts[0]);
            event.editMessage(owner.musicText(lang, "playlist_view_missing", Map.of("name", request.playlistName)))
                    .setComponents(List.of())
                    .queue();
            return;
        }
        event.editMessageEmbeds(embed.build())
                .setComponents(ActionRow.of(playlistViewButtons(lang, parts[0], safePage, totalPages)))
                .queue();
    }
    public void handlePlaylistTrackRemoveButtons(ButtonInteractionEvent event, String lang) {
        String id = event.getComponentId();
        boolean confirm = id.startsWith(TRACK_REMOVE_CONFIRM_PREFIX);
        String token = id.substring(confirm ? TRACK_REMOVE_CONFIRM_PREFIX.length() : TRACK_REMOVE_CANCEL_PREFIX.length());
        PlaylistTrackRemoveRequest request = playlistTrackRemoveRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            playlistTrackRemoveRequests.remove(token);
            event.reply(owner.musicText(lang, "playlist_track_remove_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild() == null || event.getGuild().getIdLong() != request.guildId) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }
        if (!confirm) {
            playlistTrackRemoveRequests.remove(token);
            event.editMessage(owner.musicText(lang, "playlist_track_remove_cancelled")).setComponents(List.of()).queue();
            return;
        }
        MusicDataService.PlaylistTrackRemoveResult removed = owner.musicService().removePlaylistTrack(
                request.guildId,
                request.playlistName,
                request.index,
                request.requestUserId
        );
        playlistTrackRemoveRequests.remove(token);
        switch (removed.status()) {
            case SUCCESS -> event.editMessage(owner.musicText(lang, "playlist_track_remove_success", Map.of(
                            "name", removed.playlistName(),
                            "index", String.valueOf(removed.removedIndex()),
                            "title", removed.removedTitle() == null || removed.removedTitle().isBlank() ? "-" : removed.removedTitle(),
                            "count", String.valueOf(removed.trackCount())
                    )))
                    .setComponents(List.of())
                    .queue();
            case NOT_OWNER -> event.editMessage(owner.musicText(lang, "playlist_track_remove_not_owner", Map.of(
                            "name", removed.playlistName(),
                            "owner", removed.ownerName() == null || removed.ownerName().isBlank() ? "-" : removed.ownerName()
                    )))
                    .setComponents(List.of())
                    .queue();
            case INVALID_INDEX -> event.editMessage(owner.musicText(lang, "playlist_track_remove_invalid_index", Map.of(
                            "index", String.valueOf(removed.removedIndex()),
                            "count", String.valueOf(removed.trackCount())
                    )) + "\n" + owner.musicText(lang, "playlist_track_remove_hint", Map.of("name", removed.playlistName())))
                    .setComponents(List.of())
                    .queue();
            default -> event.editMessage(owner.musicText(lang, "playlist_view_missing", Map.of("name", request.playlistName)))
                    .setComponents(List.of())
                    .queue();
        }
    }
    public void handlePlaylistAutocomplete(CommandAutoCompleteInteractionEvent event) {
        String sub = owner.canonicalPlaylistSubcommand(event.getSubcommandName());
        if ("import".equals(sub)) {
            event.replyChoices(List.of()).queue();
            return;
        }
        String focused = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT).trim();
        long guildId = event.getGuild() == null ? 0L : event.getGuild().getIdLong();
        List<Command.Choice> choices = new ArrayList<>();
        for (MusicDataService.PlaylistSummary playlist : owner.musicService().listPlaylists(guildId)) {
            String label = playlist.name() + " (" + playlist.trackCount() + ")";
            String haystack = (playlist.name() + " " + label).toLowerCase(Locale.ROOT);
            if (!focused.isBlank() && !haystack.contains(focused)) {
                continue;
            }
            choices.add(new Command.Choice(label, playlist.name()));
            if (choices.size() >= 25) {
                break;
            }
        }
        event.replyChoices(choices).queue();
    }

    private Long resolveOwnerFilter(String scope, long userId) {
        return SCOPE_ALL.equalsIgnoreCase(scope) ? null : userId;
    }

    private boolean isOwnScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return false;
        }
        String normalized = scope.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("mine")
                || normalized.equals("my")
                || normalized.equals("me")
                || normalized.equals("\u6211\u7684")
                || normalized.equals("\u81ea\u5df1");
    }

    private boolean ensureMemberReadyForPlaylistLoad(Guild guild, Member member, java.util.function.Consumer<String> reply) {
        if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel()) {
            reply.accept(owner.i18nService().t(owner.lang(guild.getIdLong()), "music.join_first"));
            return false;
        }
        AudioChannel memberChannel = member.getVoiceState().getChannel();
        var botVoiceState = guild.getSelfMember().getVoiceState();
        if (botVoiceState != null && botVoiceState.inAudioChannel() && !botVoiceState.getChannel().getId().equals(memberChannel.getId())) {
            reply.accept(owner.i18nService().t(owner.lang(guild.getIdLong()), "music.same_voice_required"));
            return false;
        }
        return true;
    }

    private String playlistNameConflictText(String lang, String playlistName, String ownerName) {
        return owner.musicText(lang, "playlist_name_conflict", Map.of(
                "name", playlistName,
                "owner", ownerName == null || ownerName.isBlank() ? "-" : ownerName
        ));
    }

    private String playlistDeleteForbiddenText(String lang, String playlistName, String ownerName) {
        return owner.musicText(lang, "playlist_delete_not_owner", Map.of(
                "name", playlistName,
                "owner", ownerName == null || ownerName.isBlank() ? "-" : ownerName
        ));
    }

    private int playlistListTotalPages(Guild guild, Long ownerIdFilter) {
        int size = owner.musicService().listPlaylists(guild.getIdLong(), ownerIdFilter).size();
        return Math.max(1, (size + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
    }

    private List<Button> playlistListButtons(String lang, String scope, long requesterId, int page, int totalPages) {
        int lastPage = Math.max(0, totalPages - 1);
        int prevPage = Math.max(0, page - 1);
        int nextPage = Math.min(lastPage, page + 1);
        return List.of(
                Button.secondary(LIST_BUTTON_PREFIX + scope + ":prev:" + prevPage + ":" + requesterId, owner.musicText(lang, "playlist_prev_page"))
                        .withDisabled(page <= 0),
                Button.secondary(LIST_BUTTON_PREFIX + scope + ":next:" + nextPage + ":" + requesterId, owner.musicText(lang, "playlist_next_page"))
                        .withDisabled(page >= lastPage)
        );
    }

    private EmbedBuilder playlistListEmbed(Guild guild, String lang, Long ownerIdFilter, int page) {
        boolean ownOnly = ownerIdFilter != null;
        List<MusicDataService.PlaylistSummary> playlists = owner.musicService().listPlaylists(guild.getIdLong(), ownerIdFilter);
        String description = playlists.isEmpty()
                ? owner.musicText(lang, ownOnly ? "playlist_list_empty_mine" : "playlist_list_empty_all")
                : owner.musicText(lang, ownOnly ? "playlist_list_desc_mine" : "playlist_list_desc_all");
        String body;
        if (playlists.isEmpty()) {
            body = description;
        } else {
            StringBuilder sb = new StringBuilder();
            int totalPages = Math.max(1, (playlists.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
            int safePage = Math.max(0, Math.min(page, totalPages - 1));
            int startIndex = safePage * LIST_PAGE_SIZE;
            int endIndex = Math.min(startIndex + LIST_PAGE_SIZE, playlists.size());
            for (int i = startIndex; i < endIndex; i++) {
                MusicDataService.PlaylistSummary playlist = playlists.get(i);
                long epochSeconds = Math.max(0L, playlist.updatedAtEpochMillis() / 1000L);
                sb.append(i + 1)
                        .append(". ")
                        .append(safe(playlist.name(), 60))
                        .append(" (`")
                        .append(playlist.trackCount())
                        .append("`)\n   ")
                        .append(owner.musicText(lang, "playlist_owner"))
                        .append(": ")
                        .append(safe(playlist.ownerName(), 30))
                        .append(" | ")
                        .append(owner.musicText(lang, "playlist_updated"))
                        .append(": <t:")
                        .append(epochSeconds)
                        .append(":R>\n");
            }
            body = sb.toString().trim();
        }
        return new EmbedBuilder()
                .setColor(new Color(46, 204, 113))
                .setTitle("\uD83D\uDCC1 " + owner.musicText(lang, "playlist_title"))
                .setDescription(description)
                .addField(owner.musicText(lang, "playlist_field"), body, false)
                .setFooter(owner.musicText(lang, "playlist_page_indicator", Map.of(
                        "current", String.valueOf(Math.max(1, Math.min(page + 1, Math.max(1, (playlists.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE)))),
                        "total", String.valueOf(Math.max(1, (playlists.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE))
                )))
                .setTimestamp(Instant.now());
    }

    private String rememberPlaylistViewRequest(long guildId, long requestUserId, String playlistName) {
        String token = UUID.randomUUID().toString().replace("-", "");
        playlistViewRequests.put(token, new PlaylistViewRequest(requestUserId, guildId, playlistName, Instant.now().plusSeconds(120)));
        return token;
    }

    private String rememberPlaylistTrackRemoveRequest(long guildId, long requestUserId, String playlistName, int index) {
        String token = UUID.randomUUID().toString().replace("-", "");
        playlistTrackRemoveRequests.put(token, new PlaylistTrackRemoveRequest(requestUserId, guildId, playlistName, index, Instant.now().plusSeconds(120)));
        return token;
    }

    private int playlistViewTotalPages(Guild guild, String playlistName) {
        int size = owner.musicService().getPlaylistTracks(guild.getIdLong(), playlistName).size();
        return Math.max(1, (size + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
    }

    private List<Button> playlistViewButtons(String lang, String token, int page, int totalPages) {
        int lastPage = Math.max(0, totalPages - 1);
        int prevPage = Math.max(0, page - 1);
        int nextPage = Math.min(lastPage, page + 1);
        return List.of(
                Button.secondary(VIEW_BUTTON_PREFIX + token + ":prev:" + prevPage, owner.musicText(lang, "playlist_prev_page"))
                        .withDisabled(page <= 0),
                Button.secondary(VIEW_BUTTON_PREFIX + token + ":next:" + nextPage, owner.musicText(lang, "playlist_next_page"))
                        .withDisabled(page >= lastPage)
        );
    }

    private EmbedBuilder playlistViewEmbed(Guild guild, String lang, String playlistName, int page) {
        MusicDataService.PlaylistSummary summary = owner.musicService().getPlaylistSummary(guild.getIdLong(), playlistName);
        if (summary == null) {
            return null;
        }
        List<MusicDataService.PlaybackEntry> tracks = owner.musicService().getPlaylistTracks(guild.getIdLong(), playlistName);
        String body;
        if (tracks.isEmpty()) {
            body = owner.musicText(lang, "playlist_view_empty");
        } else {
            StringBuilder sb = new StringBuilder();
            int totalPages = Math.max(1, (tracks.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
            int safePage = Math.max(0, Math.min(page, totalPages - 1));
            int startIndex = safePage * LIST_PAGE_SIZE;
            int endIndex = Math.min(startIndex + LIST_PAGE_SIZE, tracks.size());
            int shown = 0;
            for (int i = startIndex; i < endIndex; i++) {
                MusicDataService.PlaybackEntry track = tracks.get(i);
                String author = track.author() == null || track.author().isBlank() ? "" : " - " + safe(track.author(), 30);
                String line = new StringBuilder()
                        .append(i + 1)
                        .append(". ")
                        .append(safe(track.title(), 60))
                        .append(author)
                        .append(" (`")
                        .append(formatDuration(track.durationMillis()))
                        .append("`)")
                        .toString();
                int hiddenByLimit = endIndex - (i + 1);
                String moreText = hiddenByLimit > 0
                        ? "\n\n" + owner.musicText(lang, "playlist_view_more", Map.of("count", String.valueOf(hiddenByLimit)))
                        : "";
                int requiredLength = sb.length() + line.length() + 1 + moreText.length();
                if (requiredLength > 1024) {
                    break;
                }
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(line);
                shown++;
            }
            int hiddenCount = endIndex - startIndex - shown;
            if (hiddenCount > 0) {
                if (!sb.isEmpty()) {
                    sb.append('\n').append('\n');
                }
                sb.append(owner.musicText(lang, "playlist_view_more", Map.of("count", String.valueOf(hiddenCount))));
            }
            body = sb.toString().trim();
        }
        long epochSeconds = Math.max(0L, summary.updatedAtEpochMillis() / 1000L);
        return new EmbedBuilder()
                .setColor(new Color(52, 152, 219))
                .setTitle("\uD83D\uDCC4 " + owner.musicText(lang, "playlist_view_title", Map.of("name", summary.name())))
                .setDescription(owner.musicText(lang, "playlist_view_desc", Map.of("name", summary.name())))
                .addField(owner.musicText(lang, "playlist_owner"), safe(summary.ownerName(), 30), true)
                .addField(owner.musicText(lang, "playlist_track_count"), String.valueOf(summary.trackCount()), true)
                .addField(owner.musicText(lang, "playlist_updated"), "<t:" + epochSeconds + ":R>", true)
                .addField(owner.musicText(lang, "playlist_track_list"), body, false)
                .setFooter(owner.musicText(lang, "playlist_page_indicator", Map.of(
                        "current", String.valueOf(Math.max(1, Math.min(page + 1, Math.max(1, (tracks.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE)))),
                        "total", String.valueOf(Math.max(1, (tracks.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE))
                )))
                .setTimestamp(Instant.now());
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
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private static class PlaylistViewRequest {
        private final long requestUserId;
        private final long guildId;
        private final String playlistName;
        private final Instant expiresAt;

        private PlaylistViewRequest(long requestUserId, long guildId, String playlistName, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.playlistName = playlistName;
            this.expiresAt = expiresAt;
        }
    }

    private static class PlaylistTrackRemoveRequest {
        private final long requestUserId;
        private final long guildId;
        private final String playlistName;
        private final int index;
        private final Instant expiresAt;

        private PlaylistTrackRemoveRequest(long requestUserId, long guildId, String playlistName, int index, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.playlistName = playlistName;
            this.index = index;
            this.expiresAt = expiresAt;
        }
    }
}




