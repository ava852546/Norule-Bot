package com.norule.musicbot.discord.bot.app;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.awt.Color;
import java.time.Instant;

public final class PingCommandHandler {
    private final MusicCommandService owner;
    public PingCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }
    public void handlePingSlash(SlashCommandInteractionEvent event, String lang) {
        long start = System.currentTimeMillis();
        event.deferReply().queue(hook -> {
            long responseMs = Math.max(1L, System.currentTimeMillis() - start);
            long gatewayMs = event.getJDA().getGatewayPing();
            EmbedBuilder eb = new EmbedBuilder()
                    .setColor(new Color(52, 152, 219))
                    .setTitle(pingText(lang, "title"))
                    .setDescription(pingText(lang, "description"))
                    .addField(pingText(lang, "gateway"), "`" + gatewayMs + " ms`", true)
                    .addField(pingText(lang, "response"), "`" + responseMs + " ms`", true)
                    .setTimestamp(Instant.now());
            hook.editOriginalEmbeds(eb.build()).queue();
        }, error -> event.reply("Pong").queue());
    }

    private String pingText(String lang, String key) {
        String fullKey = "ping." + key;
        String value = owner.i18nService().t(lang, fullKey);
        return isMissingTranslation(value, fullKey) ? pingUx(lang, key) : value;
    }

    private boolean isMissingTranslation(String value, String key) {
        return value == null || value.isBlank() || value.equals(key);
    }

    private String pingUx(String lang, String key) {
        boolean zhCn = lang != null && lang.startsWith("zh-CN");
        boolean zh = lang != null && lang.startsWith("zh");
        return switch (key) {
            case "title" -> zhCn ? "\u5EF6\u8FDF\u6D4B\u8BD5" : (zh ? "\u5EF6\u9072\u6E2C\u8A66" : "Ping");
            case "description" -> zhCn ? "\u4EE5\u4E0B\u4E3A\u5F53\u524D\u673A\u5668\u4EBA\u7684\u5B9E\u65F6\u5EF6\u8FDF\u4FE1\u606F\u3002" : (zh ? "\u4EE5\u4E0B\u70BA\u76EE\u524D\u6A5F\u5668\u4EBA\u7684\u5373\u6642\u5EF6\u9072\u8CC7\u8A0A\u3002" : "Current live latency information for the bot.");
            case "gateway" -> zhCn ? "Gateway \u5EF6\u8FDF" : (zh ? "Gateway \u5EF6\u9072" : "Gateway Latency");
            case "response" -> zhCn ? "\u4EA4\u4E92\u54CD\u5E94" : (zh ? "\u4E92\u52D5\u56DE\u61C9" : "Interaction Response");
            default -> key;
        };
    }
}




