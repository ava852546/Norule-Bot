package com.norule.musicbot.web;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.discord.listeners.*;

import com.norule.musicbot.*;

import com.sun.net.httpserver.HttpExchange;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.util.Map;

final class WelcomePreviewService {
    private final WebControlServer owner;

    WelcomePreviewService(WebControlServer owner) {
        this.owner = owner;
    }

    void handleWelcomePreview(HttpExchange exchange, Guild guild, Member member) throws IOException {
        String lang = owner.settingsService().getLanguage(guild.getIdLong());
        String body = owner.readBody(exchange);
        Map<String, Object> root;
        try {
            root = owner.asMap(new Yaml().load(body));
        } catch (Exception e) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", owner.previewWelcomeInvalidPayload(lang)));
            return;
        }

        BotConfig.Welcome welcome = owner.settingsService().getWelcome(guild.getIdLong());
        Map<String, Object> wMap = owner.asMap(root.get("welcome"));
        if (wMap.isEmpty()) {
            wMap = root;
        }
        if (!wMap.isEmpty()) {
            welcome = welcome
                    .withEnabled(owner.boolOrDefault(wMap, "enabled", welcome.isEnabled()))
                    .withChannelId(owner.idOrDefault(wMap, "channelId", welcome.getChannelId()))
                    .withTitle(owner.stringOrDefault(wMap, "title", welcome.getTitle()))
                    .withMessage(owner.stringOrDefault(wMap, "message", welcome.getMessage()))
                    .withThumbnailUrl(owner.stringOrDefault(wMap, "thumbnailUrl", welcome.getThumbnailUrl()))
                    .withImageUrl(owner.stringOrDefault(wMap, "imageUrl", welcome.getImageUrl()));
        }

        if (welcome.getChannelId() == null || welcome.getChannelId() <= 0L) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", owner.previewWelcomeChannelRequired(lang)));
            return;
        }

        TextChannel channel = guild.getTextChannelById(welcome.getChannelId());
        if (channel == null) {
            owner.sendJson(exchange, 404, DataObject.empty().put("error", owner.previewWelcomeChannelMissing(lang)));
            return;
        }
        if (!guild.getSelfMember().hasPermission(channel,
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_EMBED_LINKS)) {
            owner.sendJson(exchange, 403, DataObject.empty().put("error", owner.previewWelcomeMissingPermission(lang)));
            return;
        }

        User user = member.getUser();
        String title = owner.formatWelcomeTemplate(owner.resolveWelcomeTitle(welcome, lang), user, guild);
        String message = owner.formatWelcomeTemplate(owner.resolveWelcomeMessage(welcome, lang), user, guild);
        if (message.isBlank()) {
            owner.sendJson(exchange, 400, DataObject.empty().put("error", owner.previewWelcomeEmptyMessage(lang)));
            return;
        }

        String thumbnailUrl = owner.sanitizeWelcomeUrl(welcome.getThumbnailUrl());
        String imageUrl = owner.sanitizeWelcomeUrl(welcome.getImageUrl());
        try {
            channel.sendMessageEmbeds(owner.buildWelcomeEmbed(guild, user, title, message, thumbnailUrl, imageUrl).build()).complete();
        } catch (Exception firstError) {
            try {
                channel.sendMessageEmbeds(owner.buildWelcomeEmbed(guild, user, title, message, null, null).build()).complete();
            } catch (Exception secondError) {
                owner.sendJson(exchange, 500, DataObject.empty().put("error", owner.previewWelcomeSendFailed(lang)));
                return;
            }
        }

        owner.sendJson(exchange, 200, DataObject.empty()
                .put("ok", true)
                .put("message", owner.previewWelcomeSent(lang, channel.getAsMention())));
    }
}


