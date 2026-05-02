package com.norule.musicbot.web.controller;

import com.norule.musicbot.web.infra.WebControlServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class WebStaticAssetController {
    private final WebControlServer owner;

    public WebStaticAssetController(WebControlServer owner) {
        this.owner = owner;
    }

    public void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
            owner.sendText(exchange, 404, "Not Found");
            return;
        }
        sendHtml(exchange, 200, buildRootHtml());
    }

    public void handleWebAsset(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath == null || !requestPath.startsWith("/web/") || requestPath.contains("..")) {
            owner.sendText(exchange, 404, "Not Found");
            return;
        }
        String resourcePath = requestPath;
        try (InputStream in = WebControlServer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                owner.sendText(exchange, 404, "Not Found");
                return;
            }
            byte[] body = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", webAssetContentType(resourcePath));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }
    }

    private String buildRootHtml() {
        String botName = (owner.jda() != null && owner.jda().getSelfUser() != null) ? owner.jda().getSelfUser().getName() : "NoRule Bot";
        String botAvatarUrl = resolveBotAvatarUrl();
        String faviconUrl = botAvatarUrl.isBlank()
                ? "https://cdn.discordapp.com/embed/avatars/0.png"
                : botAvatarUrl;
        String botAvatarBlock = botAvatarUrl.isBlank()
                ? "<div class=\"bot-avatar-fallback\" aria-label=\"" + escapeHtmlAttr(botName) + "\">NR</div>"
                : "<img class=\"bot-avatar\" src=\"" + escapeHtmlAttr(botAvatarUrl) + "\" alt=\"" + escapeHtmlAttr(botName) + "\" loading=\"lazy\" referrerpolicy=\"no-referrer\" />";

        return renderTemplate("web/index.html", Map.of(
                "__FAVICON_URL__", escapeHtmlAttr(faviconUrl),
                "__WEB_APP_CSS_URL__", "/web/app.css?v=" + owner.webAssetVersion(),
                "__WEB_APP_JS_URL__", "/web/app.js?v=" + owner.webAssetVersion(),
                "__HERO_SECTION__", buildHeroSection(botAvatarBlock),
                "__SIDEBAR_SECTION__", buildSidebarSection(),
                "__SETTINGS_SECTION__", buildSettingsSection()
        ));
    }

    private String buildHeroSection(String botAvatarBlock) {
        String heroCopy = loadWebTemplate("web/templates/partials/components/hero-copy.html");
        return renderTemplate("web/templates/shell/hero.html", Map.of(
                "__HERO_COPY__", heroCopy,
                "__HERO_PANEL__", renderTemplate("web/templates/partials/components/hero-panel.html", Map.of(
                        "__BOT_AVATAR_BLOCK__", botAvatarBlock
                ))
        ));
    }

    private String buildSidebarSection() {
        return renderTemplate("web/templates/shell/sidebar.html", Map.of(
                "__SIDEBAR_HEAD__", loadWebTemplate("web/templates/partials/components/sidebar-head.html")
        ));
    }

    private String buildSettingsSection() {
        return renderTemplate("web/templates/shell/settings-shell.html", Map.of(
                "__SETTINGS_HEAD__", loadWebTemplate("web/templates/partials/components/settings-head.html"),
                "__SETTINGS_TOOLBAR__", loadWebTemplate("web/templates/partials/components/settings-toolbar.html"),
                "__TAB_BUTTONS__", loadWebTemplate("web/templates/shell/tab-buttons.html"),
                "__TAB_PANES__", buildTabPanesHtml()
        ));
    }

    private String buildTabPanesHtml() {
        List<String> tabIds = List.of(
                "general",
                "notifications",
                "logs",
                "music",
                "private-room",
                "welcome",
                "number-chain",
                "ticket"
        );
        StringBuilder html = new StringBuilder();
        for (String tabId : tabIds) {
            if (!html.isEmpty()) {
                html.append(System.lineSeparator()).append(System.lineSeparator());
            }
            html.append(buildTabPaneHtml(tabId));
        }
        return html.toString();
    }

    private String buildTabPaneHtml(String tabId) {
        return switch (tabId) {
            case "notifications" -> buildNotificationsTabHtml();
            case "welcome" -> buildWelcomeTabHtml();
            case "ticket" -> buildTicketTabHtml();
            default -> loadWebTemplate("web/templates/tabs/" + tabId + ".html");
        };
    }

    private String buildNotificationsTabHtml() {
        return renderTemplate("web/templates/tabs/notifications.html", Map.of(
                "__PANE_HEAD__", buildPaneHead(
                        "notifications_group_title",
                        "section_notifications",
                        "notifications.*",
                        ""
                ),
                "__OVERVIEW_GROUP__", loadWebTemplate("web/templates/tabs/components/notifications-overview-group.html"),
                "__MODAL_HEAD__", buildModalHead(
                        "notificationEditorTitle",
                        "notificationEditorTitle",
                        "Configure Notification Embeds",
                        "closeNotificationEditorBtn"
                ),
                "__MEMBER_GROUP__", loadWebTemplate("web/templates/tabs/components/notifications-member-group.html"),
                "__VOICE_GROUP__", loadWebTemplate("web/templates/tabs/components/notifications-voice-group.html"),
                "__PREVIEW_GROUP__", loadWebTemplate("web/templates/tabs/components/notifications-preview-group.html"),
                "__MODAL_ACTIONS__", buildModalSaveActions("saveNotificationSettingsBtn")
        ));
    }

    private String buildWelcomeTabHtml() {
        return renderTemplate("web/templates/tabs/welcome.html", Map.of(
                "__PANE_HEAD__", buildPaneHead(
                        "welcome_group_title",
                        "welcome_group_title",
                        "welcome.*",
                        buildResetButton("resetWelcomeBtn")
                ),
                "__OVERVIEW_GROUP__", loadWebTemplate("web/templates/tabs/components/welcome-overview-group.html"),
                "__MODAL_HEAD__", buildModalHead(
                        "welcomeEditorTitle",
                        "welcomeEditorTitle",
                        "Configure Welcome Message",
                        "closeWelcomeEditorBtn"
                ),
                "__MESSAGE_GROUP__", loadWebTemplate("web/templates/tabs/components/welcome-message-group.html"),
                "__MEDIA_GROUP__", loadWebTemplate("web/templates/tabs/components/welcome-media-group.html"),
                "__PREVIEW_GROUP__", loadWebTemplate("web/templates/tabs/components/welcome-preview-group.html"),
                "__MODAL_ACTIONS__", buildModalSaveActions("saveWelcomeSettingsBtn")
        ));
    }

    private String buildTicketTabHtml() {
        return renderTemplate("web/templates/tabs/ticket.html", Map.of(
                "__PANE_HEAD__", buildPaneHead(
                        null,
                        "section_ticket",
                        "ticket.*",
                        buildResetButton("resetTicketBtn")
                ),
                "__BASIC_GROUP__", loadWebTemplate("web/templates/tabs/components/ticket-basic-group.html"),
                "__HISTORY_GROUP__", loadWebTemplate("web/templates/tabs/components/ticket-history-group.html"),
                "__ACCESS_GROUP__", loadWebTemplate("web/templates/tabs/components/ticket-access-group.html"),
                "__PANEL_GROUP__", loadWebTemplate("web/templates/tabs/components/ticket-panel-group.html"),
                "__FORM_GROUP__", loadWebTemplate("web/templates/tabs/components/ticket-form-group.html"),
                "__OPTIONS_GROUP__", loadWebTemplate("web/templates/tabs/components/ticket-options-group.html")
        ));
    }

    private String buildPaneHead(String titleId, String titleKey, String fallbackText, String actionsHtml) {
        String titleIdAttr = (titleId == null || titleId.isBlank())
                ? ""
                : " id=\"" + escapeHtmlAttr(titleId) + "\"";
        String titleHtml = "<h3" + titleIdAttr + " data-i18n=\"" + escapeHtmlAttr(titleKey) + "\">"
                + escapeHtmlAttr(fallbackText)
                + "</h3>";
        return renderTemplate("web/templates/partials/components/pane-head.html", Map.of(
                "__PANE_TITLE_HTML__", titleHtml,
                "__PANE_ACTIONS_HTML__", actionsHtml == null ? "" : actionsHtml
        ));
    }

    private String buildResetButton(String buttonId) {
        return "<button id=\"" + escapeHtmlAttr(buttonId)
                + "\" class=\"danger reset-btn\" type=\"button\" data-i18n=\"resetSectionBtn\">Reset Section</button>";
    }

    private String buildModalHead(String titleId, String titleKey, String fallbackText, String closeButtonId) {
        return renderTemplate("web/templates/partials/components/modal-head.html", Map.of(
                "__MODAL_TITLE_ID__", escapeHtmlAttr(titleId),
                "__MODAL_TITLE_KEY__", escapeHtmlAttr(titleKey),
                "__MODAL_TITLE_TEXT__", escapeHtmlAttr(fallbackText),
                "__MODAL_CLOSE_ID__", escapeHtmlAttr(closeButtonId)
        ));
    }

    private String buildModalSaveActions(String saveButtonId) {
        return renderTemplate("web/templates/partials/components/modal-save-actions.html", Map.of(
                "__SAVE_BUTTON_ID__", escapeHtmlAttr(saveButtonId)
        ));
    }

    private String renderTemplate(String resourcePath, Map<String, String> replacements) {
        return renderTemplateString(loadWebTemplate(resourcePath), replacements);
    }

    private String renderTemplateString(String template, Map<String, String> replacements) {
        String rendered = template;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            rendered = rendered.replace(entry.getKey(), entry.getValue());
        }
        return rendered;
    }

    private String loadWebTemplate(String resourcePath) {
        String normalizedPath = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        try (InputStream input = WebControlServer.class.getResourceAsStream(normalizedPath)) {
            if (input == null) {
                throw new IllegalStateException("Missing web template: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load web template: " + resourcePath, exception);
        }
    }

    private String webAssetContentType(String resourcePath) {
        if (resourcePath.endsWith(".js")) {
            return "text/javascript; charset=UTF-8";
        }
        if (resourcePath.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (resourcePath.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (resourcePath.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }
        return "text/plain; charset=UTF-8";
    }

    private String resolveBotAvatarUrl() {
        try {
            if (owner.jda() == null || owner.jda().getSelfUser() == null) {
                return "";
            }
            String url = owner.jda().getSelfUser().getEffectiveAvatarUrl();
            return url == null ? "" : url;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String escapeHtmlAttr(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void sendHtml(HttpExchange exchange, int statusCode, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(statusCode, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}


