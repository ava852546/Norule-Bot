package com.norule.musicbot.discord.bot.ops.meta;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public final class MetaOps {
    private final MusicCommandService owner;

    public MetaOps(MusicCommandService owner) {
        this.owner = owner;
    }

    public boolean handleSlash(String commandName, SlashCommandInteractionEvent event, String lang) {
        return switch (commandName) {
            case "help" -> {
                owner.helpCommandHandler().handleHelpSlash(event, lang);
                yield true;
            }
            case "ping" -> {
                owner.pingCommandHandler().handlePingSlash(event, lang);
                yield true;
            }
            case "user-info" -> {
                owner.infoCommandHandler().handleUserInfo(event, lang);
                yield true;
            }
            case "role-info" -> {
                owner.infoCommandHandler().handleRoleInfo(event, lang);
                yield true;
            }
            case "server-info" -> {
                owner.infoCommandHandler().handleServerInfo(event, lang);
                yield true;
            }
            case "url" -> {
                owner.urlCommandHandler().handleUrlSlash(event, lang);
                yield true;
            }
            default -> false;
        };
    }
}

