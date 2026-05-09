package com.norule.musicbot.discord.bot.gateway.command.welcome;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;

import java.time.Duration;
import java.time.Instant;

public final class WelcomeTextPreviewer {
    public String previewText(String text, Guild guild, User user) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replace("{user}", user.getAsMention())
                .replace("{用戶}", user.getAsMention())
                .replace("{username}", user.getName())
                .replace("{用戶名稱}", user.getName())
                .replace("{guild}", guild.getName())
                .replace("{伺服器}", guild.getName())
                .replace("{公會}", guild.getName())
                .replace("{id}", user.getId())
                .replace("{tag}", user.getAsTag())
                .replace("{isBot}", String.valueOf(user.isBot()))
                .replace("{createdAt}", "<t:" + user.getTimeCreated().toInstant().getEpochSecond() + ":F>")
                .replace("{accountAgeDays}", String.valueOf(Math.max(0L, Duration.between(user.getTimeCreated().toInstant(), Instant.now()).toDays())));
    }
}
