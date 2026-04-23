package com.norule.musicbot.discord.listeners;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.EnumSet;

final class HoneypotCommandHandler {
    private final MusicCommandListener owner;

    HoneypotCommandHandler(MusicCommandListener owner) {
        this.owner = owner;
    }

    void handleCreateSlash(SlashCommandInteractionEvent event, String lang) {
        Guild guild = event.getGuild();
        if (guild == null) {
            event.reply("Guild only.").setEphemeral(true).queue();
            return;
        }
        if (!owner.has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply("\u4f60\u9700\u8981\u300c\u7ba1\u7406\u4f3a\u670d\u5668\u300d\u6b0a\u9650\u624d\u80fd\u5efa\u7acb\u5bc6\u7f50\u983b\u9053\u3002")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String missing = missingBotPermissions(guild);
        if (!missing.isBlank()) {
            event.reply("\u6a5f\u5668\u4eba\u7f3a\u5c11\u5bc6\u7f50\u983b\u9053\u5fc5\u8981\u6b0a\u9650\uff1a" + missing
                            + "\n\u8acb\u7ba1\u7406\u54e1\u88dc\u4e0a\u9019\u4e9b\u6b0a\u9650\u5f8c\u518d\u57f7\u884c\u6307\u4ee4\u3002"
                            + "\n\u5176\u4e2d\u300c\u8b80\u53d6\u8a0a\u606f\u6b77\u53f2\u300d\u548c\u300c\u7ba1\u7406\u8a0a\u606f\u300d\u7528\u65bc\u6e05\u7406\u89f8\u767c\u8005 24 \u5c0f\u6642\u5167\u7684\u8a0a\u606f\uff0c\u300c\u8e22\u51fa\u6210\u54e1\u300d\u7528\u65bc\u79fb\u9664\u89f8\u767c\u8005\u3002")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        event.deferReply(true).queue(hook -> guild.createTextChannel(HoneypotListener.CHANNEL_NAME)
                .setTopic(HoneypotListener.CHANNEL_TOPIC)
                .queue(channel -> {
                    owner.honeypotService().setChannel(guild.getIdLong(), channel.getIdLong());
                    channel.sendMessageEmbeds(HoneypotListener.warningEmbed().build()).queue(ignored -> {
                    }, error -> {
                    });
                    EmbedBuilder eb = new EmbedBuilder()
                            .setColor(new Color(46, 204, 113))
                            .setTitle("\u5bc6\u7f50\u983b\u9053\u5df2\u5efa\u7acb")
                            .setDescription(channel.getAsMention() + "\n\n\u6b64\u983b\u9053\u5167\u4efb\u4f55\u975e\u672c\u6a5f\u5668\u4eba\u8a0a\u606f\u90fd\u6703\u88ab\u522a\u9664\uff0c\u767c\u9001\u8005\u6703\u88ab\u8e22\u51fa\u4f3a\u670d\u5668\u3002")
                            .setTimestamp(Instant.now());
                    hook.editOriginalEmbeds(eb.build()).queue();
                }, error -> hook.editOriginal("\u5efa\u7acb\u5bc6\u7f50\u983b\u9053\u5931\u6557\uff1a" + error.getMessage()).queue()));
    }

    private String missingBotPermissions(Guild guild) {
        EnumSet<Permission> required = EnumSet.of(
                Permission.MANAGE_CHANNEL,
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_EMBED_LINKS,
                Permission.MESSAGE_HISTORY,
                Permission.MESSAGE_MANAGE,
                Permission.KICK_MEMBERS
        );
        required.removeAll(guild.getSelfMember().getPermissions());
        if (required.isEmpty()) {
            return "";
        }
        return String.join(", ", required.stream().map(Permission::getName).toList());
    }
}
