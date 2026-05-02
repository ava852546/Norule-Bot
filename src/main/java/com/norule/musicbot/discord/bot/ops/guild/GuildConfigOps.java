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
            case "number-chain" -> {
                owner.settingsCommandHandler().handleSettingsNumberChain(event, lang);
                yield true;
            }
            case "private-room-settings" -> {
                owner.handlePrivateRoomSettingsCommand(event, lang);
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

