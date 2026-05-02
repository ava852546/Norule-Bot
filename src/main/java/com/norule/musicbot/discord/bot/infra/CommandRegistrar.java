package com.norule.musicbot.discord.bot.infra;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

import java.util.List;

public final class CommandRegistrar {
    private final MusicCommandService owner;

    public CommandRegistrar(MusicCommandService owner) {
        this.owner = owner;
    }

    public void registerOnReady(JDA jda) {
        List<CommandData> commands = owner.buildCommands();
        jda.updateCommands().addCommands(commands).queue(
                success -> System.out.println("[NoRule] Registered global slash commands: " + success.size()),
                failure -> System.out.println("[NoRule] Failed to register global slash commands: " + failure.getMessage())
        );
    }

    public void syncCommands() {
        JDA current = owner.currentJda();
        if (current == null) {
            return;
        }
        List<CommandData> commands = owner.buildCommands();
        current.updateCommands().addCommands(commands).queue(
                success -> System.out.println("[NoRule] Synced global slash commands: " + success.size()),
                failure -> System.out.println("[NoRule] Failed to sync global slash commands: " + failure.getMessage())
        );
    }
}

