package com.norule.musicbot.discord.bot.ops.music;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public final class MusicOps {
    private final MusicCommandService owner;

    public MusicOps(MusicCommandService owner) {
        this.owner = owner;
    }

    public boolean handleSlash(String commandName, SlashCommandInteractionEvent event, String lang) {
        return switch (commandName) {
            case "volume" -> {
                owner.playbackCommandHandler().handleVolumeSlash(event, lang);
                yield true;
            }
            case "speed" -> {
                owner.playbackCommandHandler().handleSpeedSlash(event, lang);
                yield true;
            }
            case "join" -> {
                owner.playbackCommandHandler().handleJoinSlash(event);
                yield true;
            }
            case "play" -> {
                owner.playbackCommandHandler().handlePlaySlash(event, lang);
                yield true;
            }
            case "skip" -> {
                owner.playbackCommandHandler().handleSkipSlash(event);
                yield true;
            }
            case "stop" -> {
                owner.playbackCommandHandler().handleStopSlash(event);
                yield true;
            }
            case "leave" -> {
                owner.playbackCommandHandler().handleLeaveSlash(event);
                yield true;
            }
            case "repeat" -> {
                owner.playbackCommandHandler().handleRepeatSlash(event, lang);
                yield true;
            }
            case "music-panel" -> {
                owner.musicPanelController().handlePanelSlashCommand(event, lang);
                yield true;
            }
            case "music" -> {
                owner.handleMusicSlash(event, lang);
                yield true;
            }
            case "history" -> {
                owner.historyCommandHandler().handleHistorySlash(event, lang);
                yield true;
            }
            case "playlist" -> {
                owner.playlistCommandHandler().handlePlaylistSlash(event, lang);
                yield true;
            }
            default -> false;
        };
    }
}

