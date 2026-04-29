package com.norule.musicbot.web;

import com.norule.musicbot.TicketService;
import com.norule.musicbot.config.*;

import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class TicketTranscriptApiController {
    private final WebControlServer owner;

    TicketTranscriptApiController(WebControlServer owner) {
        this.owner = owner;
    }

    void handleTicketPanelSend(HttpExchange exchange, Guild guild) throws IOException {
        String lang = owner.settingsService().getLanguage(guild.getIdLong());
        BotConfig.Ticket ticket = owner.settingsService().getTicket(guild.getIdLong());
        if (!ticket.isEnabled()) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", owner.i18n().t(lang, "ticket.disabled")));
            return;
        }
        Long panelChannelId = ticket.getPanelChannelId();
        if (panelChannelId == null || panelChannelId <= 0L) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", owner.i18n().t(lang, "settings.validation_expected_text_channel")));
            return;
        }
        TextChannel target = guild.getTextChannelById(panelChannelId);
        if (target == null) {
            owner.sendJson(exchange, 404, DataObject.empty().put("error", owner.i18n().t(lang, "ticket.panel_send_failed")));
            return;
        }

        List<BotConfig.Ticket.TicketOption> options = resolveTicketOptions(ticket, lang);
        EmbedBuilder panel = new EmbedBuilder()
                .setColor(new java.awt.Color(ticket.getPanelColor()))
                .setTitle(resolvePublicPanelTitle(ticket, lang))
                .setDescription(resolvePublicPanelDescription(ticket, lang))
                .setTimestamp(Instant.now());
        if (options.size() > 1) {
            for (BotConfig.Ticket.TicketOption option : options) {
                panel.addField(option.getLabel(), resolvePanelDescription(ticket, option, lang), false);
            }
        }

        target.sendMessageEmbeds(panel.build())
                .setComponents(buildTicketPanelOpenComponents(ticket, lang))
                .queue(
                        ok -> {
                            try {
                                owner.sendJson(exchange, 200, DataObject.empty()
                                        .put("ok", true)
                                        .put("message", owner.i18n().t(lang, "ticket.panel_sent", java.util.Map.of("channel", target.getAsMention()))));
                            } catch (IOException ignored) {
                            }
                        },
                        err -> {
                            try {
                                owner.sendJson(exchange, 500, DataObject.empty()
                                        .put("error", owner.i18n().t(lang, "ticket.panel_send_failed")));
                            } catch (IOException ignored) {
                            }
                        }
                );
    }

    void handleTicketTranscriptList(HttpExchange exchange, Guild guild) throws IOException {
        long guildId = guild.getIdLong();
        int removed = owner.ticketService().cleanupOldTranscripts(guildId, 90);
        List<TicketService.TranscriptFile> files = owner.ticketService().listTranscripts(guildId, 500);

        DataArray rows = DataArray.empty();
        for (TicketService.TranscriptFile file : files) {
            rows.add(DataObject.empty()
                    .put("name", file.getFileName())
                    .put("size", file.getSize())
                    .put("lastModifiedAt", file.getLastModifiedAt())
                    .put("channelId", file.getChannelId())
                    .put("url", "/api/guild/" + guild.getId() + "/ticket/transcript/" + owner.encode(file.getFileName())));
        }

        owner.sendJson(exchange, 200, DataObject.empty()
                .put("retentionDays", 90)
                .put("cleaned", removed)
                .put("files", rows));
    }

    void handleTicketTranscriptDownload(HttpExchange exchange, Guild guild, String encodedFileName) throws IOException {
        String fileName = owner.decode(encodedFileName);
        Path file = owner.ticketService().resolveTranscriptFile(guild.getIdLong(), fileName);
        if (file == null || !Files.exists(file) || !Files.isRegularFile(file)) {
            owner.sendJson(exchange, 404, DataObject.empty().put("error", "Transcript not found"));
            return;
        }
        byte[] content = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + file.getFileName().toString().replace("\"", "") + "\"");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
        exchange.close();
    }

    void handleTicketTranscriptDelete(HttpExchange exchange, Guild guild, String encodedFileName) throws IOException {
        String lang = owner.settingsService().getLanguage(guild.getIdLong());
        String fileName = owner.decode(encodedFileName);
        if (fileName == null || fileName.isBlank()) {
            owner.sendJson(exchange, 404, DataObject.empty().put("error", owner.i18n().t(lang, "ticket.history_delete_failed")));
            return;
        }
        boolean deleted = owner.ticketService().deleteTranscript(guild.getIdLong(), fileName);
        if (!deleted) {
            owner.sendJson(exchange, 404, DataObject.empty().put("error", owner.i18n().t(lang, "ticket.history_delete_failed")));
            return;
        }
        owner.sendJson(exchange, 200, DataObject.empty()
                .put("ok", true)
                .put("message", owner.i18n().t(lang, "ticket.history_delete_success", java.util.Map.of("name", fileName))));
    }

    private List<BotConfig.Ticket.TicketOption> resolveTicketOptions(BotConfig.Ticket ticket, String lang) {
        List<BotConfig.Ticket.TicketOption> options = new ArrayList<>(ticket.getOptions());
        if (options.isEmpty()) {
            options = List.of(new BotConfig.Ticket.TicketOption(
                    "general",
                    owner.i18n().t(lang, "ticket.default_type_label"),
                    ticket.getPanelTitle(),
                    ticket.getPanelDescription(),
                    ticket.getPanelButtonStyle(),
                    ticket.getWelcomeMessage(),
                    ticket.isPreOpenFormEnabled(),
                    ticket.getPreOpenFormTitle(),
                    ticket.getPreOpenFormLabel(),
                    ticket.getPreOpenFormPlaceholder()
            ));
        }
        int limit = Math.max(1, Math.min(25, ticket.getPanelButtonLimit()));
        if (options.size() <= limit) {
            return options;
        }
        return new ArrayList<>(options.subList(0, limit));
    }

    private List<ActionRow> buildTicketPanelOpenComponents(BotConfig.Ticket ticket, String lang) {
        List<BotConfig.Ticket.TicketOption> options = resolveTicketOptions(ticket, lang);
        if (ticket.getOpenUiMode() == BotConfig.Ticket.OpenUiMode.SELECT) {
            StringSelectMenu.Builder menu = StringSelectMenu.create("ticket:open:panel-select")
                    .setPlaceholder(owner.i18n().t(lang, "ticket.select_placeholder"));
            for (BotConfig.Ticket.TicketOption option : options) {
                menu.addOptions(SelectOption.of(option.getLabel(), option.getId()));
            }
            return List.of(ActionRow.of(menu.build()));
        }

        List<Button> buttons = new ArrayList<>();
        if (options.size() == 1) {
            BotConfig.Ticket.TicketOption option = options.get(0);
            buttons.add(createOpenButton(option.getPanelButtonStyle(), "ticket:open", option.getLabel()));
        } else {
            for (BotConfig.Ticket.TicketOption option : options) {
                buttons.add(createOpenButton(option.getPanelButtonStyle(), "ticket:open:option:" + option.getId(), option.getLabel()));
            }
        }
        List<ActionRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += 5) {
            rows.add(ActionRow.of(buttons.subList(i, Math.min(i + 5, buttons.size()))));
        }
        if (rows.isEmpty()) {
            rows.add(ActionRow.of(createOpenButton(ticket.getPanelButtonStyle(), "ticket:open", owner.i18n().t(lang, "ticket.panel_open_button"))));
        }
        return rows;
    }

    private String resolvePublicPanelTitle(BotConfig.Ticket ticket, String lang) {
        String custom = ticket.getPanelTitle() == null ? "" : ticket.getPanelTitle().trim();
        return custom.isBlank() ? owner.i18n().t(lang, "ticket.panel_title") : custom;
    }

    private String resolvePublicPanelDescription(BotConfig.Ticket ticket, String lang) {
        String custom = ticket.getPanelDescription() == null ? "" : ticket.getPanelDescription().trim();
        return custom.isBlank() ? owner.i18n().t(lang, "ticket.panel_desc") : custom;
    }

    private String resolvePanelDescription(BotConfig.Ticket ticket, BotConfig.Ticket.TicketOption option, String lang) {
        String custom = option == null ? "" : option.getPanelDescription().trim();
        if (custom.isBlank()) {
            custom = ticket.getPanelDescription() == null ? "" : ticket.getPanelDescription().trim();
        }
        return custom.isBlank() ? owner.i18n().t(lang, "ticket.panel_desc") : custom;
    }

    private Button createOpenButton(String style, String id, String label) {
        String normalized = style == null ? "PRIMARY" : style.trim().toUpperCase();
        return switch (normalized) {
            case "SECONDARY" -> Button.secondary(id, label);
            case "SUCCESS" -> Button.success(id, label);
            case "DANGER" -> Button.danger(id, label);
            default -> Button.primary(id, label);
        };
    }
}


