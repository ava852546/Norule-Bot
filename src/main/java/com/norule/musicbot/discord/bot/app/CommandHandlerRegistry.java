package com.norule.musicbot.discord.bot.app;

import com.norule.musicbot.discord.bot.gateway.command.honeypot.HoneypotCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.meta.HelpCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.meta.InfoCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.meta.PingCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.minecraft.MinecraftStatusCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.moderation.AntiDuplicateCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.game.NumberChainCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.game.WordChainCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.moderation.DeleteMessagesCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.moderation.WarningCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.music.HistoryCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.settings.view.BoolTextHelper;
import com.norule.musicbot.discord.bot.gateway.command.welcome.WelcomeTextPreviewer;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicPlaybackCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicPlaybackText;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicStatsCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicTextResolver;
import com.norule.musicbot.discord.bot.gateway.command.music.PlaylistCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.privateroom.PrivateRoomSettingsCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.settings.SettingsCommandHandler;
import com.norule.musicbot.discord.bot.gateway.command.settings.menu.SettingsLanguageMenuHandler;
import com.norule.musicbot.discord.bot.gateway.command.settings.menu.SettingsNumberChainMenuHandler;
import com.norule.musicbot.discord.bot.gateway.command.settings.menu.SettingsWordChainMenuHandler;
import com.norule.musicbot.discord.bot.gateway.command.settings.view.SettingsUiText;
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
    private final MusicStatsCommandHandler musicStatsCommandHandler;
    private final NumberChainCommandHandler numberChainCommandHandler;
    private final WordChainCommandHandler wordChainCommandHandler;

    CommandHandlerRegistry(MusicCommandService service,
                           MusicPanelController musicPanelController,
                           MusicPlaybackText musicPlaybackText,
                           MinecraftStatusOps minecraftStatusOps) {
        BoolTextHelper boolTextHelper = new BoolTextHelper(service::i18nService);
        WelcomeTextPreviewer welcomeTextPreviewer = new WelcomeTextPreviewer();

        this.settingsCommandHandler = new SettingsCommandHandler(service);
        this.helpCommandHandler = new HelpCommandHandler(service);
        this.pingCommandHandler = new PingCommandHandler(service::i18nService);
        this.welcomeCommandHandler = new WelcomeCommandHandler(service.settingsService(), service::i18nService, boolTextHelper, welcomeTextPreviewer);
        this.historyCommandHandler = new HistoryCommandHandler(service);
        this.deleteMessagesCommandHandler = new DeleteMessagesCommandHandler(service);
        this.privateRoomSettingsCommandHandler = new PrivateRoomSettingsCommandHandler(service);
        this.antiDuplicateCommandHandler = new AntiDuplicateCommandHandler(service.moderationService(), service::i18nService, boolTextHelper);
        this.languageMenuHandler = new SettingsLanguageMenuHandler(service::i18nService, service.settingsService());
        this.numberChainMenuHandler = new SettingsNumberChainMenuHandler(service::i18nService, service.moderationService());
        this.wordChainMenuHandler = new SettingsWordChainMenuHandler(service::i18nService, service.wordChainOps(), service.moderationService());
        this.warningCommandHandler = new WarningCommandHandler(service);
        this.honeypotCommandHandler = new HoneypotCommandHandler(service.honeypotService(), service::i18nService);
        this.infoCommandHandler = new InfoCommandHandler(service::i18nService, service.settingsService());
        this.urlCommandHandler = new UrlCommandHandler(service.shortUrlService());
        this.playlistCommandHandler = new PlaylistCommandHandler(service, musicPanelController);
        this.playbackCommandHandler = new MusicPlaybackCommandHandler(service, musicPanelController, musicPlaybackText);
        this.minecraftStatusCommandHandler = new MinecraftStatusCommandHandler(minecraftStatusOps);
        MusicTextResolver musicTextResolver = new MusicTextResolver(service::i18nService);
        this.musicStatsCommandHandler = new MusicStatsCommandHandler(musicTextResolver, service::i18nService, service.musicService());
        SettingsUiText sharedUiText = new SettingsUiText(service::i18nService, service.moderationService());
        this.numberChainCommandHandler = new NumberChainCommandHandler(service.moderationService(), service::i18nService, sharedUiText);
        this.wordChainCommandHandler = service.wordChainOps() != null
                ? new WordChainCommandHandler(service.wordChainOps(), service::i18nService)
                : null;
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
    MusicStatsCommandHandler musicStatsCommandHandler() { return musicStatsCommandHandler; }
    NumberChainCommandHandler numberChainCommandHandler() { return numberChainCommandHandler; }
    WordChainCommandHandler wordChainCommandHandler() { return wordChainCommandHandler; }
}
