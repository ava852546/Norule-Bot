package com.norule.musicbot.discord.bot.app.stats;

import com.norule.musicbot.domain.stats.MessageStatsService;
import com.norule.musicbot.domain.stats.UserMessageCount;
import com.norule.musicbot.domain.stats.UserVoiceTime;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.DiscordLocale;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageStatsEventService {
    private static final int PAGE_SIZE = 10;
    private static final int QUERY_LIMIT = 100;
    private static final String CMD_STATS_ZH = "\u7d71\u8a08";
    private static final String CMD_LEADERBOARD_ZH = "\u6392\u884c\u699c";
    private static final String LEADERBOARD_SELECT_ID = "stats:leaderboard:type";
    private static final String LEADERBOARD_PAGE_BTN_PREFIX = "stats:leaderboard:page:";
    private static final String LEADERBOARD_REFRESH_BTN_PREFIX = "stats:leaderboard:refresh:";
    private static final String TYPE_MESSAGES = "messages";
    private static final String TYPE_VOICE = "voice";

    private final MessageStatsService statsService;
    private final Map<String, Long> voiceSessionStartEpochMs = new ConcurrentHashMap<>();
    private final Map<Long, String> guildLang = new ConcurrentHashMap<>();

    public MessageStatsEventService(MessageStatsService statsService) {
        this.statsService = statsService;
    }

    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) {
            return;
        }
        User author = event.getAuthor();
        if (author.isBot() || event.isWebhookMessage()) {
            return;
        }
        statsService.trackMessage(event.getGuild().getIdLong(), author.getIdLong(), event.getMessageIdLong());
    }

    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (event.getEntity().getUser().isBot()) {
            return;
        }
        long guildId = event.getGuild().getIdLong();
        long userId = event.getEntity().getIdLong();
        String key = buildVoiceSessionKey(guildId, userId);

        if (event.getChannelLeft() != null) {
            Long startedAt = voiceSessionStartEpochMs.remove(key);
            if (startedAt != null) {
                long seconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
                statsService.trackVoiceDuration(guildId, userId, seconds);
            }
        }

        if (event.getChannelJoined() != null) {
            voiceSessionStartEpochMs.put(key, System.currentTimeMillis());
        }
    }

    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild() || event.getGuild() == null) {
            return;
        }
        rememberLang(event.getGuild().getIdLong(), event.getUserLocale());
        String command = event.getName();
        if ("stats".equals(command) || CMD_STATS_ZH.equals(command)) {
            handleStats(event);
            return;
        }
        if ("top".equals(command) || CMD_LEADERBOARD_ZH.equals(command)) {
            handleLeaderboard(event);
        }
    }

    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!LEADERBOARD_SELECT_ID.equals(event.getComponentId()) || event.getGuild() == null) {
            return;
        }
        rememberLang(event.getGuild().getIdLong(), event.getUserLocale());
        String selectedType = event.getValues().isEmpty() ? TYPE_MESSAGES : event.getValues().get(0);
        event.editMessageEmbeds(buildLeaderboardEmbed(event.getGuild(), selectedType, 1).build())
                .setComponents(buildLeaderboardComponents(event.getGuild().getIdLong(), selectedType, 1, computeTotalPages(event.getGuild(), selectedType)))
                .queue();
    }

    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (event.getGuild() == null) {
            return;
        }
        rememberLang(event.getGuild().getIdLong(), event.getUserLocale());
        if (id.startsWith(LEADERBOARD_REFRESH_BTN_PREFIX)) {
            String[] refreshParts = id.split(":");
            if (refreshParts.length != 7) {
                return;
            }
            String refreshType = refreshParts[4];
            int refreshPage = parsePositiveInt(refreshParts[6], 1);
            int refreshTotalPages = computeTotalPages(event.getGuild(), refreshType);
            int refreshClampedPage = Math.max(1, Math.min(refreshTotalPages, refreshPage));
            event.editMessageEmbeds(buildLeaderboardEmbed(event.getGuild(), refreshType, refreshClampedPage).build())
                    .setComponents(buildLeaderboardComponents(event.getGuild().getIdLong(), refreshType, refreshClampedPage, refreshTotalPages))
                    .queue();
            return;
        }
        if (!id.startsWith(LEADERBOARD_PAGE_BTN_PREFIX)) {
            return;
        }
        String[] parts = id.split(":");
        if (parts.length != 6) {
            return;
        }
        String type = parts[4];
        int page = parsePositiveInt(parts[5], 1);
        int totalPages = computeTotalPages(event.getGuild(), type);
        int clampedPage = Math.max(1, Math.min(totalPages, page));
        event.editMessageEmbeds(buildLeaderboardEmbed(event.getGuild(), type, clampedPage).build())
                .setComponents(buildLeaderboardComponents(event.getGuild().getIdLong(), type, clampedPage, totalPages))
                .queue();
    }

    private void handleStats(SlashCommandInteractionEvent event) {
        User target = event.getOption("user", event.getUser(), o -> o.getAsUser());
        long guildId = event.getGuild().getIdLong();
        long messageCount = statsService.getUserMessageCount(guildId, target.getIdLong());
        long voiceSeconds = statsService.getUserVoiceSeconds(guildId, target.getIdLong());

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(0x4E7BFF))
                .setTitle("User Stats")
                .setDescription(target.getAsMention())
                .addField("Messages", String.valueOf(messageCount), true)
                .addField("Voice Time", formatDuration(voiceSeconds), true);
        event.replyEmbeds(embed.build()).queue();
    }

    private void handleLeaderboard(SlashCommandInteractionEvent event) {
        String type = TYPE_MESSAGES;
        int page = 1;
        int totalPages = computeTotalPages(event.getGuild(), type);
        event.replyEmbeds(buildLeaderboardEmbed(event.getGuild(), type, page).build())
                .addComponents(buildLeaderboardComponents(event.getGuild().getIdLong(), type, page, totalPages))
                .queue();
    }

    private EmbedBuilder buildLeaderboardEmbed(Guild guild, String type, int page) {
        String lang = resolveLang(guild.getIdLong());
        String normalizedType = normalizeType(type);
        List<?> rows = TYPE_VOICE.equals(normalizedType)
                ? statsService.getTopVoiceUsers(guild.getIdLong(), QUERY_LIMIT)
                : statsService.getTopUsers(guild.getIdLong(), QUERY_LIMIT);

        int totalPages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int clampedPage = Math.max(1, Math.min(totalPages, page));

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(new Color(0x2B9D8F))
                .setTitle(t(lang, "title"))
                .addField(t(lang, "type"), TYPE_VOICE.equals(normalizedType) ? t(lang, "voice_time") : t(lang, "messages_count"), true)
                .addField(t(lang, "page"), clampedPage + "/" + totalPages, true);

        if (rows.isEmpty()) {
            embed.setDescription(t(lang, "no_data"));
            return embed;
        }

        int startIndex = (clampedPage - 1) * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, rows.size());
        StringBuilder content = new StringBuilder();
        if (TYPE_VOICE.equals(normalizedType)) {
            appendVoicePage(content, guild, castVoiceRows(rows), startIndex, endIndex);
        } else {
            appendMessagePage(content, guild, castMessageRows(rows), startIndex, endIndex);
        }
        embed.setDescription(content.toString());
        return embed;
    }

    private List<ActionRow> buildLeaderboardComponents(long guildId, String type, int page, int totalPages) {
        String lang = resolveLang(guildId);
        String normalizedType = normalizeType(type);
        StringSelectMenu selectMenu = StringSelectMenu.create(LEADERBOARD_SELECT_ID)
                .addOptions(
                        SelectOption.of(t(lang, "messages_rank"), TYPE_MESSAGES)
                                .withEmoji(Emoji.fromUnicode("\uD83D\uDCAC"))
                                .withDefault(TYPE_MESSAGES.equals(normalizedType)),
                        SelectOption.of(t(lang, "voice_rank"), TYPE_VOICE)
                                .withEmoji(Emoji.fromUnicode("\uD83C\uDFA4"))
                                .withDefault(TYPE_VOICE.equals(normalizedType))
                )
                .build();

        Button prev = Button.secondary(pageButtonId(normalizedType, page - 1), t(lang, "prev"))
                .withDisabled(page <= 1);
        Button next = Button.secondary(pageButtonId(normalizedType, page + 1), t(lang, "next"))
                .withDisabled(page >= totalPages);
        Button refresh = Button.success(refreshButtonId(normalizedType, page), t(lang, "refresh"));

        return List.of(
                ActionRow.of(selectMenu),
                ActionRow.of(prev, next, refresh)
        );
    }

    private int computeTotalPages(Guild guild, String type) {
        String normalizedType = normalizeType(type);
        int size = TYPE_VOICE.equals(normalizedType)
                ? statsService.getTopVoiceUsers(guild.getIdLong(), QUERY_LIMIT).size()
                : statsService.getTopUsers(guild.getIdLong(), QUERY_LIMIT).size();
        return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private static void appendMessagePage(StringBuilder builder, Guild guild, List<UserMessageCount> rows, int start, int end) {
        for (int i = start; i < end; i++) {
            UserMessageCount row = rows.get(i);
            int rank = i + 1;
            builder.append(rank)
                    .append(". ")
                    .append(resolveMention(guild, row.userId()))
                    .append(" - ")
                    .append(row.messageCount())
                    .append("\n");
        }
    }

    private static void appendVoicePage(StringBuilder builder, Guild guild, List<UserVoiceTime> rows, int start, int end) {
        for (int i = start; i < end; i++) {
            UserVoiceTime row = rows.get(i);
            int rank = i + 1;
            builder.append(rank)
                    .append(". ")
                    .append(resolveMention(guild, row.userId()))
                    .append(" - ")
                    .append(formatDuration(row.voiceSeconds()))
                    .append("\n");
        }
    }

    private static String resolveMention(Guild guild, long userId) {
        if (guild == null) {
            return "<@" + userId + ">";
        }
        Member member = guild.getMemberById(userId);
        return member == null ? "<@" + userId + ">" : member.getAsMention();
    }

    private static String formatDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        long hours = safe / 3600L;
        long minutes = (safe % 3600L) / 60L;
        long remain = safe % 60L;
        if (hours > 0) {
            return hours + "h " + minutes + "m " + remain + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + remain + "s";
        }
        return remain + "s";
    }

    private static String normalizeType(String type) {
        if (TYPE_VOICE.equalsIgnoreCase(type)) {
            return TYPE_VOICE;
        }
        return TYPE_MESSAGES;
    }

    private static String pageButtonId(String type, int page) {
        int safePage = Math.max(1, page);
        return LEADERBOARD_PAGE_BTN_PREFIX + normalizeType(type) + ":" + safePage;
    }

    private static String refreshButtonId(String type, int page) {
        int safePage = Math.max(1, page);
        return LEADERBOARD_REFRESH_BTN_PREFIX + normalizeType(type) + ":page:" + safePage;
    }

    private void rememberLang(long guildId, DiscordLocale locale) {
        if (locale == null) {
            return;
        }
        guildLang.put(guildId, locale.getLocale());
    }

    private String resolveLang(long guildId) {
        return guildLang.getOrDefault(guildId, "en");
    }

    private String t(String lang, String key) {
        boolean zhCn = lang != null && lang.startsWith("zh-CN");
        boolean zh = lang != null && lang.startsWith("zh");
        return switch (key) {
            case "title" -> zhCn ? "\uD83C\uDFC6 \u6392\u884C\u699C" : (zh ? "\uD83C\uDFC6 \u6392\u884c\u699c" : "Top Leaderboard");
            case "type" -> zhCn ? "\u7C7B\u578B" : (zh ? "\u985e\u578b" : "Type");
            case "page" -> zhCn ? "\u9875\u6570" : (zh ? "\u9801\u6578" : "Page");
            case "voice_time" -> zhCn ? "\u8BED\u97F3\u65F6\u957F" : (zh ? "\u8A9E\u97F3\u6642\u9577" : "Voice Time");
            case "messages_count" -> zhCn ? "\u6D88\u606F\u6570" : (zh ? "\u8A0A\u606F\u6578" : "Messages");
            case "no_data" -> zhCn ? "\u76EE\u524D\u65E0\u6570\u636E\u3002" : (zh ? "\u76EE\u524D\u7121\u8CC7\u6599\u3002" : "No data yet.");
            case "messages_rank" -> zhCn ? "\u6D88\u606F\u6392\u884C" : (zh ? "\u8A0A\u606F\u6392\u884C" : "Messages");
            case "voice_rank" -> zhCn ? "\u8BED\u97F3\u6392\u884C" : (zh ? "\u8A9E\u97F3\u6392\u884C" : "Voice Time");
            case "prev" -> zhCn ? "\u4E0A\u4E00\u9875" : (zh ? "\u4E0A\u4E00\u9801" : "Prev");
            case "next" -> zhCn ? "\u4E0B\u4E00\u9875" : (zh ? "\u4E0B\u4E00\u9801" : "Next");
            case "refresh" -> zhCn ? "\u5237\u65B0" : (zh ? "\u5237\u65B0" : "Refresh");
            default -> key;
        };
    }

    private static int parsePositiveInt(String raw, int fallback) {
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<UserMessageCount> castMessageRows(List<?> rows) {
        return (List<UserMessageCount>) rows;
    }

    @SuppressWarnings("unchecked")
    private static List<UserVoiceTime> castVoiceRows(List<?> rows) {
        return (List<UserVoiceTime>) rows;
    }

    private static String buildVoiceSessionKey(long guildId, long userId) {
        return guildId + ":" + userId;
    }
}
