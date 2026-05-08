package com.norule.musicbot.discord.bot.gateway.command.moderation;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DeleteMessagesCommandHandler {
    private static final String DELETE_CONFIRM_PREFIX = ComponentIds.DELETE_CONFIRM_PREFIX;
    private static final String DELETE_CANCEL_PREFIX  = ComponentIds.DELETE_CANCEL_PREFIX;
    private static final String OPTION_CHANNEL        = CommandOptions.CHANNEL;
    private static final String KEY_UNKNOWN_COMMAND       = "general.unknown_command";
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";

    private static final String SUB_DELETE_CHANNEL_ZH = "頻道";
    private static final String SUB_DELETE_USER_ZH    = "使用者";

    private static final Duration DELETE_REQUEST_TTL = Duration.ofMinutes(5);

    private final MusicCommandService owner;
    private final ConcurrentHashMap<String, DeleteRequest> deleteRequests = new ConcurrentHashMap<>();

    public DeleteMessagesCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        deleteRequests.entrySet().removeIf(e -> e.getValue() == null || cutoff.isAfter(e.getValue().expiresAt));
    }

    public void handleDeleteSlash(SlashCommandInteractionEvent event, String lang) {
        if (!owner.has(event.getMember(), Permission.MESSAGE_MANAGE)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions",
                            Map.of("permissions", Permission.MESSAGE_MANAGE.getName())))
                    .setEphemeral(true).queue();
            return;
        }

        var timeOption   = event.getOption("time");
        var amountOption = event.getOption("amount");
        boolean explicitTime   = timeOption != null;
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
            event.reply(owner.i18nService().t(lang, "delete.time_range")).setEphemeral(true).queue();
            return;
        }

        Integer amount = amountOption == null ? null : (int) amountOption.getAsLong();
        if (amount != null && (amount < 1 || amount > 99)) {
            event.reply(owner.i18nService().t(lang, "delete.amount_range")).setEphemeral(true).queue();
            return;
        }

        String sub = event.getSubcommandName();
        if (sub == null && event.getOption("type") != null) {
            sub = Objects.requireNonNull(event.getOption("type")).getAsString();
        }
        if (sub == null || sub.isBlank()) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        sub = canonicalDeleteSubcommand(sub);

        TextChannel channel = null;
        Long targetUserId = null;
        String scope;
        String extraNotice = "";

        if (OPTION_CHANNEL.equals(sub)) {
            var channelOption = event.getOption(OPTION_CHANNEL);
            if (channelOption == null) {
                if (event.getChannelType() != ChannelType.TEXT) {
                    event.reply(owner.i18nService().t(lang, "settings.validation_expected_text_channel"))
                            .setEphemeral(true).queue();
                    return;
                }
                channel = event.getChannel().asTextChannel();
                extraNotice = owner.i18nService().t(lang, "delete.default_channel_notice",
                        Map.of(OPTION_CHANNEL, channel.getAsMention()));
            } else {
                if (channelOption.getAsChannel().getType() != ChannelType.TEXT) {
                    event.reply(owner.i18nService().t(lang, "settings.validation_expected_text_channel"))
                            .setEphemeral(true).queue();
                    return;
                }
                channel = channelOption.getAsChannel().asTextChannel();
            }
            if (event.getOption("user") != null) {
                targetUserId = Objects.requireNonNull(event.getOption("user")).getAsUser().getIdLong();
            }
            scope = channel.getAsMention()
                    + (targetUserId == null ? ""
                    : " · " + Objects.requireNonNull(event.getOption("user")).getAsUser().getAsMention());
        } else {
            if (event.getOption("user") == null) {
                event.reply(owner.i18nService().t(lang, "general.invalid_user")).setEphemeral(true).queue();
                return;
            }
            var channelOption = event.getOption(OPTION_CHANNEL);
            if (channelOption != null) {
                if (channelOption.getAsChannel().getType() != ChannelType.TEXT) {
                    event.reply(owner.i18nService().t(lang, "settings.validation_expected_text_channel"))
                            .setEphemeral(true).queue();
                    return;
                }
                channel = channelOption.getAsChannel().asTextChannel();
            }
            targetUserId = Objects.requireNonNull(event.getOption("user")).getAsUser().getIdLong();
            scope = Objects.requireNonNull(event.getOption("user")).getAsUser().getAsMention()
                    + (channel == null ? " · 全部文字頻道"
                    : " · " + channel.getAsMention());
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
                        .setTitle(owner.i18nService().t(lang, "delete.confirm_title"))
                        .setDescription(
                                owner.i18nService().t(lang, "delete.confirm_body",
                                        Map.of("count", deleteAmountText(lang, amount), "scope", scope))
                                        + (explicitTime ? "\n" + deleteTimeNotice(lang, lookback) : "")
                                        + (!explicitTime && !explicitAmount
                                        ? "\n" + deleteDefaultTimeNotice(lang, "24h") : "")
                                        + (extraNotice.isBlank() ? "" : "\n" + extraNotice))
                        .addField("Info", owner.i18nService().t(lang, "delete.confirm_warning"), false)
                        .setColor(new Color(241, 196, 15))
                        .build())
                .addComponents(ActionRow.of(
                        Button.danger(DELETE_CONFIRM_PREFIX + token,
                                owner.i18nService().t(lang, "delete.confirm_button")),
                        Button.secondary(DELETE_CANCEL_PREFIX + token,
                                owner.i18nService().t(lang, "delete.cancel_button"))
                ))
                .setEphemeral(true)
                .queue();
    }

    public void handleDeleteButtons(ButtonInteractionEvent event, String lang) {
        String id    = event.getComponentId();
        String token = id.substring(id.lastIndexOf(':') + 1);
        DeleteRequest req = deleteRequests.get(token);
        if (req == null || Instant.now().isAfter(req.expiresAt)) {
            deleteRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "delete.cancelled")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != req.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }
        if (id.startsWith(DELETE_CANCEL_PREFIX)) {
            deleteRequests.remove(token);
            event.editMessage(owner.i18nService().t(lang, "delete.cancelled")).setComponents(List.of()).queue();
            return;
        }
        Guild guild = event.getGuild();
        event.deferEdit().queue(
                success -> {
                    event.getHook().editOriginal(owner.i18nService().t(lang, "delete.processing"))
                            .setComponents(List.of())
                            .queue();
                    owner.scheduler().execute(() -> {
                        try {
                            int deleted = performDeleteRequest(guild, req);
                            deleteRequests.remove(token);
                            if (deleted <= 0) {
                                event.getHook().editOriginal(
                                        owner.i18nService().t(lang, "delete.no_target")).queue();
                            } else {
                                event.getHook().editOriginal(
                                        owner.i18nService().t(lang, "delete.processed",
                                                Map.of("count", String.valueOf(deleted)))).queue();
                            }
                        } catch (Exception ex) {
                            deleteRequests.remove(token);
                            event.getHook().editOriginal(
                                    owner.i18nService().t(lang, "delete.failed")).queue();
                        }
                    });
                },
                failure -> event.reply(owner.i18nService().t(lang, "delete.failed"))
                        .setEphemeral(true).queue()
        );
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

    private List<Message> findMessagesForDeletion(TextChannel channel, Long targetUserId,
                                                   Integer amount, int maxPages, Duration lookback) {
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
        Duration result = Duration.ofSeconds(totalSeconds);
        return result.compareTo(Duration.ofDays(14)) <= 0 ? result : null;
    }

    private String formatDeleteLookback(Duration lookback) {
        long totalSeconds = lookback.getSeconds();
        long days    = totalSeconds / 86400L; totalSeconds %= 86400L;
        long hours   = totalSeconds / 3600L;  totalSeconds %= 3600L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        StringBuilder sb = new StringBuilder();
        if (days    > 0L) sb.append(days).append('d');
        if (hours   > 0L) sb.append(hours).append('h');
        if (minutes > 0L) sb.append(minutes).append('m');
        if (seconds > 0L || sb.length() == 0) sb.append(seconds).append('s');
        return sb.toString();
    }

    private String deleteAmountText(String lang, Integer amount) {
        if (amount != null) {
            return String.valueOf(amount);
        }
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "所有符合条件的消息";
        }
        if (lang != null && lang.toLowerCase(Locale.ROOT).startsWith("zh")) {
            return "所有符合條件的訊息";
        }
        return "all matching messages";
    }

    private String deleteTimeNotice(String lang, Duration lookback) {
        String formatted  = formatDeleteLookback(lookback);
        String translated = owner.i18nService().t(lang, "delete.time_notice", Map.of("time", formatted));
        if (!"delete.time_notice".equals(translated)) {
            return translated;
        }
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "搜索范围：最近 " + formatted + "。";
        }
        if ("zh-TW".equalsIgnoreCase(lang)) {
            return "搜尋範圍：最近 " + formatted + "。";
        }
        return "Search range: last " + formatted + ".";
    }

    private String deleteDefaultTimeNotice(String lang, String formatted) {
        String translated = owner.i18nService().t(lang, "delete.default_time_notice", Map.of("time", formatted));
        if (!"delete.default_time_notice".equals(translated)) {
            return translated;
        }
        if ("zh-CN".equalsIgnoreCase(lang)) {
            return "未提供时间，使用默认范围：最近 " + formatted + "。";
        }
        if ("zh-TW".equalsIgnoreCase(lang)) {
            return "未提供時間，使用預設範圍：最近 " + formatted + "。";
        }
        return "Time not provided. Using default range: last " + formatted + ".";
    }

    private boolean canManageMessages(TextChannel channel) {
        Member self = channel.getGuild().getSelfMember();
        return self.hasAccess(channel)
                && self.hasPermission(channel, Permission.MESSAGE_HISTORY, Permission.MESSAGE_MANAGE);
    }

    private static String canonicalDeleteSubcommand(String sub) {
        return switch (sub) {
            case SUB_DELETE_CHANNEL_ZH -> OPTION_CHANNEL;
            case SUB_DELETE_USER_ZH    -> "user";
            default -> sub;
        };
    }

    private static final class DeleteRequest {
        final long requestUserId;
        final Long channelId;
        final Long targetUserId;
        final Duration lookback;
        final Integer amount;
        final Instant expiresAt;

        DeleteRequest(long requestUserId, Long channelId, Long targetUserId,
                      Duration lookback, Integer amount, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.channelId     = channelId;
            this.targetUserId  = targetUserId;
            this.lookback      = lookback;
            this.amount        = amount;
            this.expiresAt     = expiresAt;
        }
    }
}
