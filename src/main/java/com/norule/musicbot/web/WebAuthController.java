package com.norule.musicbot.web;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.discord.listeners.*;

import com.norule.musicbot.*;

import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

final class WebAuthController {
    private static final long OAUTH_STATE_TTL_MILLIS = 5 * 60_000L;
    private final WebControlServer owner;

    WebAuthController(WebControlServer owner) {
        this.owner = owner;
    }

    void handleAuthLogin(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        BotConfig.Web web = owner.configSupplier().get().getWeb();
        String state = UUID.randomUUID().toString().replace("-", "");
        owner.oauthStates().put(state, new WebControlServer.OAuthState(System.currentTimeMillis() + OAUTH_STATE_TTL_MILLIS));

        String authorizeUrl = "https://discord.com/oauth2/authorize"
                + "?response_type=code"
                + "&client_id=" + owner.encode(web.getDiscordClientId())
                + "&scope=" + owner.encode("identify guilds")
                + "&redirect_uri=" + owner.encode(web.getDiscordRedirectUri())
                + "&state=" + owner.encode(state);

        owner.redirect(exchange, authorizeUrl);
    }

    void handleAuthCallback(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        Map<String, String> query = owner.parseUrlEncoded(exchange.getRequestURI().getRawQuery());
        String state = query.getOrDefault("state", "");
        String code = query.getOrDefault("code", "");
        if (code.isBlank() || state.isBlank()) {
            owner.sendText(exchange, 400, "Missing code/state");
            return;
        }

        WebControlServer.OAuthState stateData = owner.oauthStates().remove(state);
        if (stateData == null || stateData.expiresAtMillis < System.currentTimeMillis()) {
            owner.sendText(exchange, 401, "OAuth state expired");
            return;
        }

        BotConfig.Web web = owner.configSupplier().get().getWeb();
        try {
            String accessToken = owner.exchangeToken(web, code);
            DataObject me = owner.fetchMe(accessToken);
            String userId = me.getString("id", "");
            String username = me.getString("username", "");
            String avatarUrl = owner.buildAvatarUrl(me);
            if (userId.isBlank()) {
                owner.sendText(exchange, 401, "Failed to get user profile");
                return;
            }

            long ttlMillis = Math.max(5, web.getSessionExpireMinutes()) * 60_000L;
            String sessionId = UUID.randomUUID().toString().replace("-", "");
            owner.sessions().put(sessionId, new WebControlServer.WebSession(
                    userId,
                    username,
                    avatarUrl,
                    accessToken,
                    System.currentTimeMillis() + ttlMillis
            ));
            owner.setSessionCookie(exchange, sessionId, web);
            owner.redirect(exchange, owner.resolveHomeUrl(web));
        } catch (Exception e) {
            owner.sendText(exchange, 401, "OAuth failed: " + e.getMessage());
        }
    }

    void handleAuthLogout(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        owner.clearSessionCookie(exchange, owner.configSupplier().get().getWeb());
        owner.redirect(exchange, "/");
    }

    void handleApiMe(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        WebControlServer.WebSession session = owner.requireSession(exchange);
        if (session == null) {
            owner.sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
            return;
        }
        owner.sendJson(exchange, 200, DataObject.empty()
                .put("id", session.userId)
                .put("username", session.username)
                .put("avatarUrl", session.avatarUrl));
    }
}


