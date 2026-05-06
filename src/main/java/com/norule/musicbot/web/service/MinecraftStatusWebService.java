package com.norule.musicbot.web.service;

import com.norule.musicbot.domain.minecraft.MinecraftServerStatus;
import com.norule.musicbot.ops.minecraft.MinecraftStatusOps;
import com.norule.musicbot.service.minecraft.MinecraftStatusService;
import com.norule.musicbot.web.dto.MinecraftStatusWebResponse;
import com.norule.musicbot.web.infra.WebControlServer;
import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.util.Map;

public final class MinecraftStatusWebService {
    private final WebControlServer owner;
    private final MinecraftStatusOps minecraftStatusOps;

    public MinecraftStatusWebService(WebControlServer owner, MinecraftStatusOps minecraftStatusOps) {
        this.owner = owner;
        this.minecraftStatusOps = minecraftStatusOps;
    }

    public void handleApiMinecraftStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendJson(exchange, 405, DataObject.empty()
                    .put("error", "Method Not Allowed")
                    .put("errorCode", "METHOD_NOT_ALLOWED"));
            return;
        }

        Map<String, String> query = owner.parseUrlEncoded(exchange.getRequestURI().getRawQuery());
        String address = query.getOrDefault("address", "").trim();
        String type = query.getOrDefault("type", "JAVA").trim();
        MinecraftStatusService.QueryResult result = minecraftStatusOps.query(address, type);
        if (!result.success()) {
            owner.sendJson(exchange, result.statusCode(), DataObject.empty()
                    .put("error", result.errorMessage())
                    .put("errorCode", result.errorCode()));
            return;
        }

        MinecraftStatusWebResponse response = toWebResponse(result.status());
        owner.sendJson(exchange, 200, DataObject.empty()
                .put("online", response.online())
                .put("address", response.address())
                .put("ip", response.ip())
                .put("port", response.port())
                .put("version", response.version())
                .put("playersOnline", response.playersOnline())
                .put("playersMax", response.playersMax())
                .put("motd", response.motd())
                .put("iconUrl", response.iconUrl())
                .put("serverType", response.serverType().name())
                .put("cached", response.cached()));
    }

    private MinecraftStatusWebResponse toWebResponse(MinecraftServerStatus status) {
        return new MinecraftStatusWebResponse(
                status.online(),
                emptyIfNull(status.address()),
                emptyIfNull(status.ip()),
                status.port(),
                emptyIfNull(status.version()),
                status.playersOnline(),
                status.playersMax(),
                emptyIfNull(status.motd()),
                emptyIfNull(status.iconUrl()),
                status.serverType(),
                status.cached()
        );
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
