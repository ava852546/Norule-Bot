package com.norule.musicbot.web.infra;

import com.norule.musicbot.web.controller.GuildSettingsController;
import com.norule.musicbot.web.controller.WebAuthController;
import com.norule.musicbot.web.controller.WebMetadataController;
import com.norule.musicbot.web.controller.WebStaticAssetController;
import com.sun.net.httpserver.HttpServer;

final class WebRouteBinder {
    private final WebAuthController authController;
    private final WebMetadataController metadataController;
    private final GuildSettingsController guildSettingsController;
    private final WebStaticAssetController staticAssetController;

    WebRouteBinder(WebAuthController authController,
                   WebMetadataController metadataController,
                   GuildSettingsController guildSettingsController,
                   WebStaticAssetController staticAssetController) {
        this.authController = authController;
        this.metadataController = metadataController;
        this.guildSettingsController = guildSettingsController;
        this.staticAssetController = staticAssetController;
    }

    void bind(HttpServer server) {
        server.createContext("/auth/login", authController::handleAuthLogin);
        server.createContext("/auth/callback", authController::handleAuthCallback);
        server.createContext("/auth/logout", authController::handleAuthLogout);
        server.createContext("/api/me", authController::handleApiMe);
        server.createContext("/api/guilds", metadataController::handleApiGuilds);
        server.createContext("/api/web/i18n", metadataController::handleApiWebI18n);
        server.createContext("/api/guild/", guildSettingsController::handleApiGuildRoute);
        server.createContext("/web/", staticAssetController::handleWebAsset);
        server.createContext("/", staticAssetController::handleRoot);
    }
}
