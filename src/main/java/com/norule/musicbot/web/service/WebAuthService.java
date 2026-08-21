package com.norule.musicbot.web.service;

import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.infra.WebSettings;
import com.norule.musicbot.web.security.ClientAddressResolver;
import com.norule.musicbot.web.session.WebSessionManager;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

public final class WebAuthService {
    private static final long OAUTH_STATE_TTL_MILLIS = 5 * 60_000L;
    private static final long SESSION_HANDOFF_TTL_MILLIS = 60_000L;
    private static final String ANONYMOUS_DEVICE_COOKIE = "nr_anon_device";

    private final WebControlServer owner;
    private final WebSessionService webSessionService;

    public WebAuthService(WebControlServer owner, WebSessionService webSessionService) {
        this.owner = owner;
        this.webSessionService = webSessionService;
    }

    public void handleAuthLogin(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        WebSettings web = owner.webSettings();
        Map<String, String> query = owner.parseUrlEncoded(exchange.getRequestURI().getRawQuery());
        WebSessionManager.PendingLogin pendingLogin = webSessionService.consumePendingLogin(
                query.getOrDefault("request", ""));
        String requestedReturnTo = pendingLogin == null
                ? query.getOrDefault("returnTo", "") : pendingLogin.returnTo;
        String returnTo = sanitizeReturnTo(
                requestedReturnTo,
                owner.resolveHomeUrl(web),
                owner.shortUrlService().publicBaseUrl()
        );
        String anonymousDeviceToken = pendingLogin == null ? "" : pendingLogin.anonymousDeviceToken;
        String state = UUID.randomUUID().toString().replace("-", "");
        webSessionService.putOAuthState(
                state, System.currentTimeMillis() + OAUTH_STATE_TTL_MILLIS, returnTo, anonymousDeviceToken);

        String authorizeUrl = "https://discord.com/oauth2/authorize"
                + "?response_type=code"
                + "&client_id=" + owner.encode(web.getDiscordClientId())
                + "&scope=" + owner.encode("identify guilds")
                + "&redirect_uri=" + owner.encode(web.getDiscordRedirectUri())
                + "&state=" + owner.encode(state);

        owner.redirect(exchange, authorizeUrl);
    }

    public void handleAuthCallback(HttpExchange exchange) throws IOException {
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

        WebSessionManager.OAuthState stateData = webSessionService.popOAuthState(state);
        if (stateData == null || stateData.expiresAtMillis < System.currentTimeMillis()) {
            owner.sendText(exchange, 401, "OAuth state expired");
            return;
        }

        WebSettings web = owner.webSettings();
        try {
            String accessToken = owner.discordOAuthClient().exchangeToken(
                    web.getDiscordClientId(),
                    web.getDiscordClientSecret(),
                    web.getDiscordRedirectUri(),
                    code
            );
            var me = owner.discordOAuthClient().fetchMe(accessToken);
            String userId = me.getString("id", "");
            String username = me.getString("username", "");
            String avatarUrl = owner.buildAvatarUrl(me);
            if (userId.isBlank()) {
                owner.sendText(exchange, 401, "Failed to get user profile");
                return;
            }

            try {
                var mediaIdentity = owner.shortUrlService().authenticateMediaIdentity(
                        stateData.anonymousDeviceToken.isBlank()
                                ? readCookie(exchange, ANONYMOUS_DEVICE_COOKIE)
                                : stateData.anonymousDeviceToken,
                        userId,
                        clientAddress(exchange));
                if (mediaIdentity != null && mediaIdentity.mergeStatus()
                        == com.norule.musicbot.shorturl.MediaSecurityRepository.IdentityMergeStatus.ACCOUNT_SWITCH_BLOCKED) {
                    System.err.println("[NoRule] MEDIA_DEVICE_ACCOUNT_SWITCH discordUserId=" + userId);
                }
            } catch (RuntimeException identityFailure) {
                System.err.println("[NoRule] Media identity merge failed after OAuth login: "
                        + identityFailure.getClass().getSimpleName());
            }

            long ttlMillis = Math.max(5, web.getSessionExpireMinutes()) * 60_000L;
            String sessionId = UUID.randomUUID().toString().replace("-", "");
            WebSessionManager.WebSession session = webSessionService.putSession(
                    exchange, sessionId, userId, username, avatarUrl, accessToken,
                    System.currentTimeMillis() + ttlMillis, owner.isSecureCookie(web), web.getSessionExpireMinutes());
            String destination = stateData.returnTo.isBlank() ? owner.resolveHomeUrl(web) : stateData.returnTo;
            if (requiresSessionHandoff(destination, owner.resolveHomeUrl(web),
                    owner.shortUrlService().publicBaseUrl())) {
                String ticket = webSessionService.issueSessionHandoff(
                        session, System.currentTimeMillis() + SESSION_HANDOFF_TTL_MILLIS);
                destination = appendQueryParameter(destination, "__nr_auth", ticket);
            }
            owner.redirect(exchange, destination);
        } catch (Exception e) {
            owner.sendText(exchange, 401, "OAuth failed: " + e.getMessage());
        }
    }

    public void handleAuthLogout(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        webSessionService.clearSessionCookie(exchange, owner.isSecureCookie(owner.webSettings()));
        owner.redirect(exchange, "/");
    }

    public void handleApiMe(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendJson(exchange, 405, net.dv8tion.jda.api.utils.data.DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        WebSessionManager.WebSession session = webSessionService.requireSession(exchange);
        if (session == null) {
            owner.sendJson(exchange, 401, net.dv8tion.jda.api.utils.data.DataObject.empty().put("error", "Unauthorized"));
            return;
        }
        owner.sendJson(exchange, 200, net.dv8tion.jda.api.utils.data.DataObject.empty()
                .put("id", session.userId)
                .put("username", session.username)
                .put("avatarUrl", session.avatarUrl)
                .put("csrfToken", session.csrfToken));
    }

    private String readCookie(HttpExchange exchange, String name) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null || header.isBlank()) {
            return "";
        }
        for (String part : header.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && name.equals(pair[0].trim())) {
                return pair[1].trim();
            }
        }
        return "";
    }

    private String clientAddress(HttpExchange exchange) {
        return ClientAddressResolver.resolve(exchange);
    }

    static String sanitizeReturnTo(String requested, String dashboardBaseUrl, String shortUrlBaseUrl) {
        String fallback = dashboardBaseUrl == null || dashboardBaseUrl.isBlank() ? "/" : dashboardBaseUrl;
        if (requested == null || requested.isBlank() || requested.contains("\r") || requested.contains("\n")) {
            return fallback;
        }
        try {
            URI candidate = URI.create(requested.trim());
            if (!candidate.isAbsolute()) {
                return candidate.getRawAuthority() == null
                        && candidate.getRawPath() != null
                        && candidate.getRawPath().startsWith("/")
                        && !candidate.getRawPath().startsWith("//")
                        ? candidate.toString() : fallback;
            }
            if (sameOrigin(candidate, URI.create(fallback))) {
                return candidate.toString();
            }
            URI shortBase = URI.create(shortUrlBaseUrl == null ? "" : shortUrlBaseUrl);
            if (sameOrigin(candidate, shortBase) && isAllowedShortUrlReturn(candidate)) {
                return candidate.toString();
            }
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
        return fallback;
    }

    private static boolean requiresSessionHandoff(String destination,
                                                  String dashboardBaseUrl,
                                                  String shortUrlBaseUrl) {
        try {
            URI target = URI.create(destination);
            return target.isAbsolute()
                    && sameOrigin(target, URI.create(shortUrlBaseUrl))
                    && !sameOrigin(target, URI.create(dashboardBaseUrl));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isAllowedShortUrlReturn(URI uri) {
        String path = uri.getRawPath();
        if (uri.getRawFragment() != null) {
            return false;
        }
        if ("/".equals(path) && uri.getRawQuery() == null) {
            return true;
        }
        return path != null && path.matches("/[^/]+") && "stats".equals(uri.getRawQuery());
    }

    private static boolean sameOrigin(URI left, URI right) {
        if (left == null || right == null || left.getScheme() == null || right.getScheme() == null
                || left.getHost() == null || right.getHost() == null) {
            return false;
        }
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String appendQueryParameter(String url, String name, String value) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + name + "=" + value;
    }
}
