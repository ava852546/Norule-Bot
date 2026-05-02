package com.norule.musicbot.web.controller;

import com.norule.musicbot.web.infra.WebControlServer;
import com.norule.musicbot.web.session.WebSessionManager;
import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.util.Map;

public final class WebMetadataController {
    private final WebControlServer owner;

    public WebMetadataController(WebControlServer owner) {
        this.owner = owner;
    }

    public void handleApiWebI18n(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        Map<String, Map<String, String>> bundles = owner.webLanguageService().loadWebBundles();
        DataArray uiLanguages = DataArray.empty();
        for (Map.Entry<String, Map<String, String>> entry : bundles.entrySet()) {
            String code = entry.getKey();
            Map<String, String> bundle = entry.getValue();
            String label = bundle.getOrDefault("lang", bundle.getOrDefault("language.name", code));
            uiLanguages.add(DataObject.empty()
                    .put("code", code)
                    .put("label", label));
        }
        DataArray botLanguages = DataArray.empty();
        for (Map.Entry<String, String> entry : owner.i18n().getAvailableLanguages().entrySet()) {
            botLanguages.add(DataObject.empty()
                    .put("code", entry.getKey())
                    .put("label", entry.getValue()));
        }
        DataObject bundlesJson = DataObject.empty();
        for (Map.Entry<String, Map<String, String>> entry : bundles.entrySet()) {
            DataObject one = DataObject.empty();
            for (Map.Entry<String, String> kv : entry.getValue().entrySet()) {
                one.put(kv.getKey(), kv.getValue());
            }
            bundlesJson.put(entry.getKey(), one);
        }
        String defaultUiLang = bundles.containsKey("zh-TW")
                ? "zh-TW"
                : (bundles.isEmpty() ? "en" : bundles.keySet().iterator().next());
        owner.sendJson(exchange, 200, DataObject.empty()
                .put("defaultLanguage", defaultUiLang)
                .put("uiLanguages", uiLanguages)
                .put("botLanguages", botLanguages)
                .put("bundles", bundlesJson));
    }

    public void handleApiGuilds(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            owner.sendJson(exchange, 405, DataObject.empty().put("error", "Method Not Allowed"));
            return;
        }
        WebSessionManager.WebSession session = owner.sessionManager().requireSession(exchange);
        if (session == null || session.accessToken == null || session.accessToken.isBlank()) {
            owner.sendJson(exchange, 401, DataObject.empty().put("error", "Unauthorized"));
            return;
        }

        try {
            DataArray userGuilds = owner.discordOAuthClient().fetchUserGuilds(session.accessToken);
            DataArray guilds = DataArray.empty();
            for (int i = 0; i < userGuilds.length(); i++) {
                DataObject rawGuild = userGuilds.getObject(i);
                String guildId = rawGuild.getString("id", "");
                if (guildId.isBlank()) {
                    continue;
                }
                String permissions = rawGuild.getString("permissions_new", rawGuild.getString("permissions", "0"));
                if (!owner.hasManagePermissionInGuild(permissions)) {
                    continue;
                }
                String guildName = rawGuild.getString("name", "Unknown Guild");
                String icon = rawGuild.getString("icon", "");
                Guild botGuild = owner.jda().getGuildById(guildId);
                boolean botInGuild = botGuild != null;
                boolean botCanManage = botInGuild && botGuild.getSelfMember().hasPermission(Permission.MANAGE_SERVER);

                guilds.add(DataObject.empty()
                        .put("id", guildId)
                        .put("name", guildName)
                        .put("iconUrl", owner.buildGuildIconUrl(guildId, icon))
                        .put("botInGuild", botInGuild)
                        .put("botCanManage", botCanManage)
                        .put("manageUrl", "/?guild=" + guildId)
                        .put("inviteUrl", owner.buildBotInviteUrl(guildId)));
            }
            owner.sendJson(exchange, 200, DataObject.empty().put("guilds", guilds));
        } catch (Exception e) {
            owner.sendJson(exchange, 401, DataObject.empty().put("error", "Failed to load user guilds. Please login again."));
        }
    }
}



