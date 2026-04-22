package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.domain.music.MusicDataService;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HistoryCommandHandler {
    static final String HISTORY_BUTTON_PREFIX = "history:";
    private static final int HISTORY_FETCH_LIMIT = 50;

    private final MusicCommandListener owner;

    HistoryCommandHandler(MusicCommandListener owner) {
        this.owner = owner;
    }

    void handleHistorySlash(SlashCommandInteractionEvent event, String lang) {
        int totalPages = historyTotalPages(event.getGuild(), lang);
        var reply = event.replyEmbeds(historyEmbed(event.getGuild(), lang, 0).build());
        if (totalPages > 1) {
            reply.addComponents(ActionRow.of(historyButtons(lang, event.getUser().getIdLong(), 0, totalPages)));
        }
        reply.queue();
    }

    void handleHistoryButtons(ButtonInteractionEvent event, String lang) {
        String[] parts = event.getComponentId().split(":");
        if (parts.length < 4) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        int page;
        long requesterId;
        try {
            page = Integer.parseInt(parts[2]);
            requesterId = Long.parseLong(parts[3]);
        } catch (NumberFormatException ex) {
            event.reply(owner.i18nService().t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != requesterId) {
            event.reply(owner.i18nService().t(lang, "delete.only_requester")).setEphemeral(true).queue();
            return;
        }

        int totalPages = historyTotalPages(event.getGuild(), lang);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        event.editMessageEmbeds(historyEmbed(event.getGuild(), lang, safePage).build())
                .setComponents(totalPages > 1 ? List.of(ActionRow.of(historyButtons(lang, requesterId, safePage, totalPages))) : List.of())
                .queue();
    }

    EmbedBuilder historyEmbed(Guild guild, String lang) {
        return historyEmbed(guild, lang, 0);
    }

    private EmbedBuilder historyEmbed(Guild guild, String lang, int page) {
        List<String> pages = historyPageBodies(guild, lang);
        int totalPages = pages.size();
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(52, 152, 219))
                .setTitle("\uD83D\uDD58 " + owner.musicText(lang, "history_title"))
                .setDescription(owner.musicText(lang, "history_desc"))
                .addField(owner.musicText(lang, "history_field"), pages.get(safePage), false)
                .setTimestamp(Instant.now());
        if (totalPages > 1) {
            embed.setFooter(owner.musicText(lang, "playlist_page_indicator", Map.of(
                    "current", String.valueOf(safePage + 1),
                    "total", String.valueOf(totalPages)
            )));
        }
        return embed;
    }

    private int historyTotalPages(Guild guild, String lang) {
        return historyPageBodies(guild, lang).size();
    }

    private List<Button> historyButtons(String lang, long requesterId, int page, int totalPages) {
        int lastPage = Math.max(0, totalPages - 1);
        int prevPage = Math.max(0, page - 1);
        int nextPage = Math.min(lastPage, page + 1);
        return List.of(
                Button.secondary(HISTORY_BUTTON_PREFIX + "prev:" + prevPage + ":" + requesterId, owner.musicText(lang, "playlist_prev_page"))
                        .withDisabled(page <= 0),
                Button.secondary(HISTORY_BUTTON_PREFIX + "next:" + nextPage + ":" + requesterId, owner.musicText(lang, "playlist_next_page"))
                        .withDisabled(page >= lastPage)
        );
    }

    private List<String> historyPageBodies(Guild guild, String lang) {
        List<HistoryDisplayEntry> history = compactHistoryEntries(owner.musicService().getRecentHistory(guild.getIdLong(), HISTORY_FETCH_LIMIT));
        if (history.isEmpty()) {
            return List.of(owner.musicText(lang, "history_empty"));
        }

        List<String> pages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            String line = historyLine(lang, history.get(i), i + 1);
            int separatorLength = current.isEmpty() ? 0 : 1;
            if (!current.isEmpty() && current.length() + separatorLength + line.length() > MessageEmbed.VALUE_MAX_LENGTH) {
                pages.add(current.toString().trim());
                current.setLength(0);
                separatorLength = 0;
            }
            if (line.length() > MessageEmbed.VALUE_MAX_LENGTH) {
                pages.add(safeEmbedFieldValue(line));
                continue;
            }
            if (separatorLength > 0) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            pages.add(current.toString().trim());
        }
        return pages.isEmpty() ? List.of(owner.musicText(lang, "history_empty")) : pages;
    }

    private List<HistoryDisplayEntry> compactHistoryEntries(List<MusicDataService.PlaybackEntry> history) {
        Map<String, HistoryDisplayEntry> compacted = new LinkedHashMap<>();
        for (MusicDataService.PlaybackEntry entry : history) {
            if (entry == null) {
                continue;
            }
            String key = historyCompactKey(entry);
            HistoryDisplayEntry current = compacted.get(key);
            compacted.put(key, current == null
                    ? new HistoryDisplayEntry(entry, 1)
                    : new HistoryDisplayEntry(current.entry(), current.count() + 1));
        }
        return new ArrayList<>(compacted.values());
    }

    private String historyCompactKey(MusicDataService.PlaybackEntry entry) {
        String requester = entry.requesterId() == null
                ? String.valueOf(entry.requesterName()).trim().toLowerCase(Locale.ROOT)
                : "id:" + entry.requesterId();
        return requester + "||" + entry.songKey();
    }

    private String historyLine(String lang, HistoryDisplayEntry displayEntry, int index) {
        MusicDataService.PlaybackEntry entry = displayEntry.entry();
        long epochSeconds = Math.max(0L, entry.playedAtEpochMillis() / 1000L);
        String requester = entry.requesterId() == null ? safe(entry.requesterName(), 40) : "<@" + entry.requesterId() + ">";
        return new StringBuilder()
                .append(index)
                .append(". ")
                .append(safe(entry.title(), 60))
                .append(" - ")
                .append(safe(entry.author(), 40))
                .append(displayEntry.count() > 1 ? " x" + displayEntry.count() : "")
                .append('\n')
                .append("   ")
                .append(owner.musicText(lang, "history_source"))
                .append(": ")
                .append(safe(entry.source(), 20))
                .append(" | ")
                .append(owner.musicText(lang, "history_duration"))
                .append(": ")
                .append(formatDuration(entry.durationMillis()))
                .append(" | ")
                .append(owner.musicText(lang, "history_requester"))
                .append(": ")
                .append(requester)
                .append(" | <t:")
                .append(epochSeconds)
                .append(":R>")
                .toString();
    }

    private String safe(String s, int max) {
        if (s == null || s.isBlank()) {
            return "-";
        }
        return s.length() <= max ? s : s.substring(0, max - 1);
    }

    private String safeEmbedFieldValue(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        int max = MessageEmbed.VALUE_MAX_LENGTH;
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 4).stripTrailing() + "\n...";
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

    private record HistoryDisplayEntry(MusicDataService.PlaybackEntry entry, int count) {
    }
}
