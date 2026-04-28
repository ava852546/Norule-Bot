package com.norule.musicbot.discord.listeners;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

import java.util.List;

final class CommandRegistrar {
    private final MusicCommandListener owner;

    CommandRegistrar(MusicCommandListener owner) {
        this.owner = owner;
    }

    void registerOnReady(JDA jda) {
        List<CommandData> commands = owner.buildCommands();
        jda.updateCommands().addCommands(commands).queue(
                success -> System.out.println("[NoRule] Registered global slash commands: " + success.size()),
                failure -> System.out.println("[NoRule] Failed to register global slash commands: " + failure.getMessage())
        );
    }

    void syncCommands() {
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


