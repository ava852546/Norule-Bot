package com.norule.musicbot.discord.bot.infra;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
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

    /**
     * Clears all guild‑specific slash commands for every guild the bot is currently in.
     * This is useful when the bot previously registered commands as guild commands (e.g. during development)
     * and they remain cached on Discord, causing duplicate command listings alongside the new global commands.
     */
    private void clearGuildCommands() {
        JDA current = owner.currentJda();
        if (current == null) {
            return;
        }
        // Iterate over all guilds the bot is a member of and clear their command list.
        current.getGuilds().forEach(guild -> {
            guild.updateCommands().queue(
                    success -> System.out.println("[NoRule] Cleared guild commands for guild " + guild.getId()),
                    failure -> System.out.println("[NoRule] Failed to clear guild commands for guild " + guild.getId() + ": " + failure.getMessage())
            );
        });
    }

    public void syncCommands() {
        // First, ensure any stale guild commands are removed to avoid duplicate listings.
        clearGuildCommands();

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


