package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.web.*;

import com.norule.musicbot.*;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    void clearGlobalCommands(JDA jda) {
        jda.retrieveCommands().queue(globalCommands -> {
            if (globalCommands == null || globalCommands.isEmpty()) {
                return;
            }
            jda.updateCommands().queue(
                    success -> System.out.println("[NoRule] Cleared stale global slash commands: " + globalCommands.size()),
                    error -> System.out.println("[NoRule] Failed to clear global slash commands: " + error.getMessage())
            );
        }, error -> System.out.println("[NoRule] Failed to retrieve global commands: " + error.getMessage()));
    }

    void updateGuildCommandsAndPermissions(Guild guild, List<CommandData> commands) {
        guild.updateCommands().addCommands(commands).queue(
                success -> System.out.println("[NoRule] Registered slash commands for guild " + guild.getId() + ": " + success.size()),
                failure -> System.out.println("[NoRule] Failed to register slash commands for guild "
                        + guild.getId() + ": " + failure.getMessage())
        );
    }

    void enforceCommandPermissions(Guild guild) {
        Set<String> publicCommands = new HashSet<>(Set.of(
                "help", "ping", "join", "play", "skip", "stop", "leave", "music-panel", "repeat",
                "volume", MusicCommandListener.CMD_VOLUME_ZH, "history", MusicCommandListener.CMD_HISTORY_ZH,
                "music", MusicCommandListener.CMD_MUSIC_ZH, "playlist", MusicCommandListener.CMD_PLAYLIST_ZH,
                "ticket", MusicCommandListener.CMD_TICKET_ZH,
                "private-room-settings", MusicCommandListener.CMD_ROOM_SETTINGS_ZH,
                MusicCommandListener.CMD_HELP_ZH, MusicCommandListener.CMD_PING_ZH, MusicCommandListener.CMD_JOIN_ZH,
                MusicCommandListener.CMD_PLAY_ZH, MusicCommandListener.CMD_SKIP_ZH, MusicCommandListener.CMD_STOP_ZH,
                MusicCommandListener.CMD_LEAVE_ZH, MusicCommandListener.CMD_MUSIC_PANEL_ZH, MusicCommandListener.CMD_REPEAT_ZH
        ));
        Set<String> adminCommands = Set.of("settings", MusicCommandListener.CMD_SETTINGS_ZH,
                "anti-duplicate", MusicCommandListener.CMD_ANTI_DUPLICATE_ZH,
                "honeypot-channel", MusicCommandListener.CMD_HONEYPOT_ZH);
        Set<String> modCommands = Set.of("delete-messages", MusicCommandListener.CMD_DELETE_ZH, "warnings", MusicCommandListener.CMD_WARNINGS_ZH);

        guild.retrieveCommands().queue(commands -> {
            for (Command command : commands) {
                String name = command.getName();
                DefaultMemberPermissions target = null;
                if (publicCommands.contains(name)) {
                    target = DefaultMemberPermissions.ENABLED;
                } else if (adminCommands.contains(name)) {
                    target = DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER);
                } else if (modCommands.contains(name)) {
                    target = DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE);
                }
                if (target != null) {
                    command.editCommand().setDefaultPermissions(target).queue(
                            ignored -> {
                            },
                            error -> System.out.println("[NoRule] Failed to set command permissions for "
                                    + command.getName() + " in guild " + guild.getId() + ": " + error.getMessage())
                    );
                }
            }
        }, error -> System.out.println("[NoRule] Failed to retrieve guild commands for permission sync in guild "
                + guild.getId() + ": " + error.getMessage()));
    }
}


