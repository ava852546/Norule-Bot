package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.discord.bot.gateway.command.honeypot.HoneypotCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.meta.HelpCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.meta.InfoCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.meta.PingCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.minecraft.MinecraftStatusCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.moderation.AntiDuplicateCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.moderation.DeleteMessagesCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.moderation.WarningCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.music.HistoryCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicPlaybackCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicPlaybackText;
import com.norule.musicbot.discord.bot.gateway.command.music.PlaylistCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.privateroom.PrivateRoomSettingsCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.settings.SettingsCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.settings.menu.SettingsLanguageMenuHandler;
import com.norule.musicbot.discord.bot.gateway.command.settings.menu.SettingsNumberChainMenuHandler;
import com.norule.musicbot.discord.bot.gateway.command.settings.menu.SettingsWordChainMenuHandler;
import com.norule.musicbot.discord.bot.gateway.command.shorturl.UrlCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.welcome.WelcomeCommandHandler;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelController;
import com.norule.musicbot.ops.minecraft.MinecraftStatusOps;

class CommandHandlerRegistry {
    private final SettingsCommandHandler settingsCommandHandler;
    private final HelpCommandHandler helpCommandHandler;
    private final PingCommandHandler pingCommandHandler;
    private final WelcomeCommandHandler welcomeCommandHandler;
    private final HistoryCommandHandler historyCommandHandler;
    private final PlaylistCommandHandler playlistCommandHandler;
    private final MusicPlaybackCommandHandler playbackCommandHandler;
    private final DeleteMessagesCommandHandler deleteMessagesCommandHandler;
    private final PrivateRoomSettingsCommandHandler privateRoomSettingsCommandHandler;
    private final AntiDuplicateCommandHandler antiDuplicateCommandHandler;
    private final SettingsLanguageMenuHandler languageMenuHandler;
    private final SettingsNumberChainMenuHandler numberChainMenuHandler;
    private final SettingsWordChainMenuHandler wordChainMenuHandler;
    private final WarningCommandHandler warningCommandHandler;
    private final HoneypotCommandHandler honeypotCommandHandler;
    private final InfoCommandHandler infoCommandHandler;
    private final UrlCommandHandler urlCommandHandler;
    private final MinecraftStatusCommandHandler minecraftStatusCommandHandler;

    CommandHandlerRegistry(MusicCommandService service,
                           MusicPanelController musicPanelController,
                           MusicPlaybackText musicPlaybackText,
                           MinecraftStatusOps minecraftStatusOps) {
        this.settingsCommandHandler = new SettingsCommandHandler(service);
        this.helpCommandHandler = new HelpCommandHandler(service);
        this.pingCommandHandler = new PingCommandHandler(service);
        this.welcomeCommandHandler = new WelcomeCommandHandler(service);
        this.historyCommandHandler = new HistoryCommandHandler(service);
        this.deleteMessagesCommandHandler = new DeleteMessagesCommandHandler(service);
        this.privateRoomSettingsCommandHandler = new PrivateRoomSettingsCommandHandler(service);
        this.antiDuplicateCommandHandler = new AntiDuplicateCommandHandler(service);
        this.languageMenuHandler = new SettingsLanguageMenuHandler(service);
        this.numberChainMenuHandler = new SettingsNumberChainMenuHandler(service);
        this.wordChainMenuHandler = new SettingsWordChainMenuHandler(service);
        this.warningCommandHandler = new WarningCommandHandler(service);
        this.honeypotCommandHandler = new HoneypotCommandHandler(service);
        this.infoCommandHandler = new InfoCommandHandler(service);
        this.urlCommandHandler = new UrlCommandHandler(service);
        this.playlistCommandHandler = new PlaylistCommandHandler(service, musicPanelController);
        this.playbackCommandHandler = new MusicPlaybackCommandHandler(service, musicPanelController, musicPlaybackText);
        this.minecraftStatusCommandHandler = new MinecraftStatusCommandHandler(service, minecraftStatusOps);
    }

    SettingsCommandHandler settingsCommandHandler() { return settingsCommandHandler; }
    HelpCommandHandler helpCommandHandler() { return helpCommandHandler; }
    PingCommandHandler pingCommandHandler() { return pingCommandHandler; }
    WelcomeCommandHandler welcomeCommandHandler() { return welcomeCommandHandler; }
    HistoryCommandHandler historyCommandHandler() { return historyCommandHandler; }
    PlaylistCommandHandler playlistCommandHandler() { return playlistCommandHandler; }
    MusicPlaybackCommandHandler playbackCommandHandler() { return playbackCommandHandler; }
    DeleteMessagesCommandHandler deleteMessagesCommandHandler() { return deleteMessagesCommandHandler; }
    PrivateRoomSettingsCommandHandler privateRoomSettingsCommandHandler() { return privateRoomSettingsCommandHandler; }
    AntiDuplicateCommandHandler antiDuplicateCommandHandler() { return antiDuplicateCommandHandler; }
    SettingsLanguageMenuHandler languageMenuHandler() { return languageMenuHandler; }
    SettingsNumberChainMenuHandler numberChainMenuHandler() { return numberChainMenuHandler; }
    SettingsWordChainMenuHandler wordChainMenuHandler() { return wordChainMenuHandler; }
    WarningCommandHandler warningCommandHandler() { return warningCommandHandler; }
    HoneypotCommandHandler honeypotCommandHandler() { return honeypotCommandHandler; }
    InfoCommandHandler infoCommandHandler() { return infoCommandHandler; }
    UrlCommandHandler urlCommandHandler() { return urlCommandHandler; }
    MinecraftStatusCommandHandler minecraftStatusCommandHandler() { return minecraftStatusCommandHandler; }
}
