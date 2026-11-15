package com.norule.musicbot.discord.bot.gateway.command.report;

import com.norule.musicbot.domain.discord.DiscordEmbedSanitizer;
import com.norule.musicbot.discord.bot.gateway.DeferredInteractionResponse;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Handles {@code /report} (and {@code /回報}): lets any member send a bug report or feedback to the
 * developer channel configured via {@code developers.developerChannelId}.
 *
 * <p>The handler intentionally does not depend on {@code MusicCommandService}; it receives an
 * {@code Supplier<I18nService>} for translations and a {@code Supplier<Long>} for the live developer
 * channel id (so config reloads are picked up automatically).
 */
public final class ReportCommandHandler {
    private static final String TYPE_BUG = "bug";
    private static final String TYPE_FEEDBACK = "feedback";

    private static final String FIELD_TITLE = "title";
    private static final String FIELD_DETAIL = "detail";
    private static final String FIELD_STEPS = "steps";
    private static final String FIELD_CONTACT = "contact";

    private static final int MAX_SHORT = 100;
    private static final int MAX_PARAGRAPH = 1000;
    private static final int MAX_EMBED_FIELD = 1024;

    private static final Duration REQUEST_TTL = Duration.ofMinutes(10);
    private static final long COOLDOWN_SECONDS = 60L;

    // bug -> red, feedback -> blue
    private static final Color COLOR_BUG = new Color(0xE74C3C);
    private static final Color COLOR_FEEDBACK = new Color(0x3498DB);

    private final java.util.concurrent.ScheduledExecutorService scheduler;
    private final Supplier<I18nService> i18nServiceSupplier;
    private final Supplier<Long> developerChannelIdSupplier;

    private final ConcurrentHashMap<String, ReportRequest> reportRequests = new ConcurrentHashMap<>();
    // Simple per-user anti-abuse cooldown: userId -> last successful submission (epoch millis).
    private final ConcurrentHashMap<Long, Long> cooldowns = new ConcurrentHashMap<>();

    public ReportCommandHandler(Supplier<I18nService> i18nServiceSupplier,
                                Supplier<Long> developerChannelIdSupplier,
                                java.util.concurrent.ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
        this.i18nServiceSupplier = i18nServiceSupplier;
        this.developerChannelIdSupplier = developerChannelIdSupplier;
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        reportRequests.entrySet().removeIf(e -> e.getValue() == null || cutoff.isAfter(e.getValue().expiresAt));
        long cooldownFloorMillis = cutoff.toEpochMilli() - COOLDOWN_SECONDS * 1000L;
        cooldowns.entrySet().removeIf(e -> e.getValue() == null || e.getValue() < cooldownFloorMillis);
    }

    public void handleReportSlash(SlashCommandInteractionEvent event, String lang) {
        I18nService i18n = i18n();
        User user = event.getUser();

        long remaining = cooldownRemainingSeconds(user.getIdLong(), System.currentTimeMillis());
        if (remaining > 0) {
            event.reply(i18n.t(lang, "report.cooldown", Map.of("seconds", String.valueOf(remaining))))
                    .setEphemeral(true).queue();
            return;
        }

        Long channelId = developerChannelId();
        if (channelId == null || channelId <= 0L) {
            event.reply(i18n.t(lang, "report.not_configured")).setEphemeral(true).queue();
            return;
        }

        String type = normalizeType(event.getOption(CommandOptions.TYPE) == null
                ? TYPE_FEEDBACK
                : event.getOption(CommandOptions.TYPE).getAsString());
        boolean bug = TYPE_BUG.equals(type);

        String token = UUID.randomUUID().toString().replace("-", "");
        reportRequests.put(token, new ReportRequest(
                user.getIdLong(),
                event.getChannel().getIdLong(),
                type,
                Instant.now().plus(REQUEST_TTL)
        ));

        TextInput titleInput = TextInput.create(FIELD_TITLE, TextInputStyle.SHORT)
                .setRequired(true)
                .setMaxLength(MAX_SHORT)
                .build();
        TextInput detailInput = TextInput.create(FIELD_DETAIL, TextInputStyle.PARAGRAPH)
                .setRequired(true)
                .setMaxLength(MAX_PARAGRAPH)
                .build();
        TextInput stepsInput = TextInput.create(FIELD_STEPS, TextInputStyle.PARAGRAPH)
                .setRequired(false)
                .setMaxLength(MAX_PARAGRAPH)
                .build();
        TextInput contactInput = TextInput.create(FIELD_CONTACT, TextInputStyle.SHORT)
                .setRequired(false)
                .setMaxLength(MAX_SHORT)
                .build();

        String stepsLabel = i18n.t(lang, bug ? "report.field_steps" : "report.field_extra");
        Modal modal = Modal.create(ComponentIds.REPORT_MODAL_PREFIX + token,
                        i18n.t(lang, bug ? "report.modal_title_bug" : "report.modal_title_feedback"))
                .addComponents(
                        Label.of(i18n.t(lang, "report.field_title"), titleInput),
                        Label.of(i18n.t(lang, "report.field_detail"), detailInput),
                        Label.of(stepsLabel, stepsInput),
                        Label.of(i18n.t(lang, "report.field_contact"), contactInput)
                )
                .build();
        event.replyModal(modal).queue();
    }

    public void handleReportModal(ModalInteractionEvent event, String lang) {
        I18nService i18n = i18n();
        String token = event.getModalId().substring(ComponentIds.REPORT_MODAL_PREFIX.length());
        ReportRequest request = reportRequests.remove(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            event.reply(i18n.t(lang, "report.expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.userId) {
            event.reply(i18n.t(lang, "report.only_requester")).setEphemeral(true).queue();
            return;
        }

        Long channelId = developerChannelId();
        if (channelId == null || channelId <= 0L) {
            event.reply(i18n.t(lang, "report.not_configured")).setEphemeral(true).queue();
            return;
        }
        TextChannel channel = event.getJDA().getTextChannelById(channelId);
        if (channel == null) {
            event.reply(i18n.t(lang, "report.channel_not_found")).setEphemeral(true).queue();
            return;
        }

        DeferredInteractionResponse.reply(event, true, "report.submit", scheduler,
                i18n.t(lang, "report.send_failed"), response -> {
            boolean bug = TYPE_BUG.equals(request.type);
            EmbedBuilder embed = buildReportEmbed(event, lang, request, bug,
                    readValue(event, FIELD_TITLE), readValue(event, FIELD_DETAIL),
                    readValue(event, FIELD_STEPS), readValue(event, FIELD_CONTACT));
            long userId = event.getUser().getIdLong();
            channel.sendMessageEmbeds(embed.build()).timeout(45, java.util.concurrent.TimeUnit.SECONDS).queue(
                    success -> {
                        cooldowns.put(userId, System.currentTimeMillis());
                        response.text(i18n.t(lang, "report.sent"));
                    }, response::fail);
        });
    }

    private EmbedBuilder buildReportEmbed(ModalInteractionEvent event, String lang, ReportRequest request,
                                          boolean bug, String title, String detail, String steps, String contact) {
        I18nService i18n = i18n();
        User user = event.getUser();
        Guild guild = event.getGuild();

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(bug ? COLOR_BUG : COLOR_FEEDBACK)
                .setTitle(i18n.t(lang, bug ? "report.embed_bug_title" : "report.embed_feedback_title"))
                .addField(i18n.t(lang, "report.embed_type"),
                        i18n.t(lang, bug ? "report.type_bug" : "report.type_feedback"), true)
                .addField(i18n.t(lang, "report.embed_title"), trim(title), false)
                .addField(i18n.t(lang, "report.embed_detail"), trim(detail), false);
        if (!steps.isBlank()) {
            embed.addField(i18n.t(lang, "report.embed_steps"), trim(steps), false);
        }
        if (!contact.isBlank()) {
            embed.addField(i18n.t(lang, "report.embed_contact"), trim(contact), false);
        }
        embed.addField(i18n.t(lang, "report.embed_reporter"),
                user.getAsMention() + " (`" + user.getAsTag() + "`)\nID: `" + user.getId() + "`", false);

        String guildText = guild == null ? "-" : guild.getName() + " (`" + guild.getId() + "`)";
        embed.addField(i18n.t(lang, "report.embed_guild"), guildText, true);
        embed.addField(i18n.t(lang, "report.embed_channel"),
                "<#" + request.sourceChannelId + "> (`" + request.sourceChannelId + "`)", true);
        embed.addField(i18n.t(lang, "report.embed_time"),
                "<t:" + Instant.now().getEpochSecond() + ":F>", false);
        embed.setTimestamp(Instant.now());
        return embed;
    }

    private long cooldownRemainingSeconds(long userId, long nowMillis) {
        Long last = cooldowns.get(userId);
        if (last == null) {
            return 0L;
        }
        long elapsedMillis = nowMillis - last;
        long cooldownMillis = COOLDOWN_SECONDS * 1000L;
        if (elapsedMillis >= cooldownMillis) {
            return 0L;
        }
        return (cooldownMillis - elapsedMillis + 999L) / 1000L;
    }

    private Long developerChannelId() {
        return developerChannelIdSupplier.get();
    }

    private I18nService i18n() {
        return i18nServiceSupplier.get();
    }

    private static String readValue(ModalInteractionEvent event, String id) {
        var mapping = event.getValue(id);
        return mapping == null ? "" : mapping.getAsString().trim();
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return DiscordEmbedSanitizer.truncate(value, MAX_EMBED_FIELD);
    }

    private static String normalizeType(String raw) {
        return TYPE_BUG.equalsIgnoreCase(raw == null ? "" : raw.trim()) ? TYPE_BUG : TYPE_FEEDBACK;
    }

    private static final class ReportRequest {
        final long userId;
        final long sourceChannelId;
        final String type;
        final Instant expiresAt;

        ReportRequest(long userId, long sourceChannelId, String type, Instant expiresAt) {
            this.userId = userId;
            this.sourceChannelId = sourceChannelId;
            this.type = type;
            this.expiresAt = expiresAt;
        }
    }
}
