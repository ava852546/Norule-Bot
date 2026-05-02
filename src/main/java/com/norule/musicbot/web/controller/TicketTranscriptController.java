package com.norule.musicbot.web.controller;

import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.service.TicketTranscriptWebService;
import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.entities.Guild;

import java.io.IOException;

public final class TicketTranscriptController {
    private final TicketTranscriptWebService ticketTranscriptWebService;

    public TicketTranscriptController(WebControlServer owner) {
        this.ticketTranscriptWebService = new TicketTranscriptWebService(owner);
    }

    public void handleTicketPanelSend(HttpExchange exchange, Guild guild) throws IOException {
        ticketTranscriptWebService.handleTicketPanelSend(exchange, guild);
    }

    public void handleTicketTranscriptList(HttpExchange exchange, Guild guild) throws IOException {
        ticketTranscriptWebService.handleTicketTranscriptList(exchange, guild);
    }

    public void handleTicketTranscriptDownload(HttpExchange exchange, Guild guild, String encodedFileName) throws IOException {
        ticketTranscriptWebService.handleTicketTranscriptDownload(exchange, guild, encodedFileName);
    }

    public void handleTicketTranscriptDelete(HttpExchange exchange, Guild guild, String encodedFileName) throws IOException {
        ticketTranscriptWebService.handleTicketTranscriptDelete(exchange, guild, encodedFileName);
    }
}
