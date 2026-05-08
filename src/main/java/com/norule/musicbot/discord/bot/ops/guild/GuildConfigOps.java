package com.norule.musicbot.discord.bot.ops.guild;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public final class GuildConfigOps {
    private final MusicCommandService owner;

    public GuildConfigOps(MusicCommandService owner) {
        this.owner = owner;
    }

    public boolean handleSlash(String commandName, SlashCommandInteractionEvent event, String lang) {
        return switch (commandName) {
            case "settings" -> {
                owner.settingsCommandHandler().handleSettings(event, lang);
                yield true;
            }
            case "number-chain", "\u6578\u5b57\u63a5\u9f8d" -> {
                owner.openNumberChainMenu(event, lang);
                yield true;
            }
            case "private-room-settings" -> {
                owner.privateRoomSettingsCommandHandler().handlePrivateRoomSettingsCommand(event, lang);
                yield true;
            }
            case "welcome" -> {
                owner.welcomeCommandHandler().handleWelcomeSlash(event, lang);
                yield true;
            }
            default -> false;
        };
    }
}

