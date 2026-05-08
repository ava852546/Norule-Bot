package com.norule.musicbot.discord.bot.gateway.command.moderation;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;

import java.awt.Color;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WarningCommandHandler {
    private static final String WARNING_REASON_MODAL_PREFIX = ComponentIds.WARNING_REASON_MODAL_PREFIX;
    private static final String ROUTE_ACTION              = CommandOptions.ACTION;
    private static final String KEY_UNKNOWN_COMMAND       = "general.unknown_command";
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";

    private final MusicCommandService owner;
    private final ConcurrentHashMap<String, WarningActionRequest> warningActionRequests = new ConcurrentHashMap<>();

    public WarningCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        warningActionRequests.entrySet().removeIf(e -> e.getValue() == null || cutoff.isAfter(e.getValue().expiresAt));
    }

    public void handleWarningsSlash(SlashCommandInteractionEvent event, String lang) {
        if (!owner.has(event.getMember(), Permission.MODERATE_MEMBERS)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions",
                            Map.of("permissions", Permission.MODERATE_MEMBERS.getName())))
                    .setEphemeral(true).queue();
            return;
        }

        String sub = event.getSubcommandName();
        if ((sub == null || sub.isBlank()) && event.getOption(ROUTE_ACTION) != null) {
            sub = event.getOption(ROUTE_ACTION).getAsString();
        }
        if (sub == null) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }

        User target = event.getOption("user") == null
                ? event.getUser()
                : event.getOption("user").getAsUser();
        int amount = event.getOption("amount") == null
                ? 1
                : Math.max(1, (int) event.getOption("amount").getAsLong());
        long guildId = event.getGuild().getIdLong();

        switch (sub) {
            case "add"    -> openWarningReasonModal(event, sub, target, amount, lang);
            case "remove" -> openWarningReasonModal(event, sub, target, amount, lang);
            case "clear"  -> openWarningReasonModal(event, sub, target, amount, lang);
            case "view" -> {
                int count = owner.moderationService().getWarnings(guildId, target.getIdLong());
                event.replyEmbeds(warningsResultEmbed(owner.i18nService().t(lang, "warnings.result_view",
                                Map.of("user", target.getAsMention(), "count", String.valueOf(count)))).build())
                        .queue();
            }
            default -> event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
        }
    }

    public void handleWarningReasonModal(ModalInteractionEvent event, String lang) {
        String token = event.getModalId().substring(WARNING_REASON_MODAL_PREFIX.length());
        WarningActionRequest request = warningActionRequests.remove(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId
                || event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }

        String reason = Objects.requireNonNull(event.getValue("reason")).getAsString().trim();
        if (reason.isBlank()) {
            event.replyEmbeds(warningsResultEmbed(
                            owner.i18nService().t(lang, "warnings.reason_required")).build())
                    .setEphemeral(true).queue();
            return;
        }

        JDA currentJda = owner.currentJda();
        User target = currentJda == null ? null : currentJda.getUserById(request.targetUserId);
        String userText = target == null ? "<@" + request.targetUserId + ">" : target.getAsMention();

        String result;
        switch (request.action) {
            case "add" -> {
                int count = owner.moderationService().addWarnings(
                        request.guildId, request.targetUserId, request.amount,
                        event.getUser().getIdLong(), reason);
                result = owner.i18nService().t(lang, "warnings.result_add",
                        Map.of("user", userText, "count", String.valueOf(count)));
            }
            case "remove" -> {
                int count = owner.moderationService().removeWarnings(
                        request.guildId, request.targetUserId, request.amount,
                        event.getUser().getIdLong(), reason);
                result = owner.i18nService().t(lang, "warnings.result_remove",
                        Map.of("user", userText, "count", String.valueOf(count)));
            }
            case "clear" -> {
                owner.moderationService().clearWarnings(
                        request.guildId, request.targetUserId,
                        event.getUser().getIdLong(), reason);
                result = owner.i18nService().t(lang, "warnings.result_clear", Map.of("user", userText));
            }
            default -> {
                event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
                return;
            }
        }

        result = result + "\n" + owner.i18nService().t(lang, "warnings.reason_line", Map.of("reason", reason));
        event.replyEmbeds(warningsResultEmbed(result).build()).queue();
    }

    private void openWarningReasonModal(SlashCommandInteractionEvent event, String action,
                                        User target, int amount, String lang) {
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
                .setPlaceholder(owner.i18nService().t(lang, "warnings.reason_placeholder"))
                .setMaxLength(500)
                .build();
        Modal modal = Modal.create(WARNING_REASON_MODAL_PREFIX + token,
                        owner.i18nService().t(lang, "warnings.reason_modal_title"))
                .addComponents(Label.of(owner.i18nService().t(lang, "warnings.reason_modal_label"), reasonInput))
                .build();
        event.replyModal(modal).queue();
    }

    private EmbedBuilder warningsResultEmbed(String content) {
        return new EmbedBuilder()
                .setColor(new Color(241, 196, 15))
                .setDescription(content);
    }

    private static final class WarningActionRequest {
        final long requestUserId;
        final long guildId;
        final String action;
        final long targetUserId;
        final int amount;
        final Instant expiresAt;

        WarningActionRequest(long requestUserId, long guildId, String action,
                             long targetUserId, int amount, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId       = guildId;
            this.action        = action;
            this.targetUserId  = targetUserId;
            this.amount        = amount;
            this.expiresAt     = expiresAt;
        }
    }
}