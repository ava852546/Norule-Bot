package com.norule.musicbot.discord.bot.ops.moderation;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public final class ModerationOps {
    private final MusicCommandService owner;

    public ModerationOps(MusicCommandService owner) {
        this.owner = owner;
    }

    public boolean handleSlash(String commandName, SlashCommandInteractionEvent event, String lang) {
        return switch (commandName) {
            case "delete-messages" -> {
                owner.handleDeleteSlash(event, lang);
                yield true;
            }
            case "warnings" -> {
                owner.handleWarningsSlash(event, lang);
                yield true;
            }
            case "anti-duplicate" -> {
                owner.handleAntiDuplicateSlash(event, lang);
                yield true;
            }
            case "honeypot-channel" -> {
                owner.honeypotCommandHandler().handleCreateSlash(event, lang);
                yield true;
            }
            default -> false;
        };
    }
}

