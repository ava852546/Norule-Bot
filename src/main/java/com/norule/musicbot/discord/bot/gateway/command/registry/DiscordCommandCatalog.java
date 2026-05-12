package com.norule.musicbot.discord.bot.gateway.command.registry;

import com.norule.musicbot.discord.bot.gateway.command.CommandNames;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.interactions.DiscordLocale;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.ArrayList;
import java.util.List;

public final class DiscordCommandCatalog {
    private static final String SUB_GENERIC_ENABLE_ZH = "\u555f\u7528";
    private static final String SUB_GENERIC_STATUS_ZH = "\u72c0\u614b";
    private static final String SUB_MUSIC_STATS_ZH = "\u7d71\u8a08";
    private static final String SUB_PLAYLIST_VIEW_ZH = "\u67e5\u770b";
    private static final String SUB_PLAYLIST_REMOVE_TRACK_ZH = "\u522a\u9664\u6b4c\u66f2";
    private static final String SUB_PLAYLIST_ADD_ZH = "\u65b0\u589e\u6b4c\u66f2";
    private static final String SUB_DELETE_CHANNEL_ZH = "\u983b\u9053";
    private static final String SUB_DELETE_USER_ZH = "\u4f7f\u7528\u8005";
    private static final String OPTION_WELCOME_CHANNEL_ZH = "\u983b\u9053";
    private static final String OPTION_VOLUME_VALUE_ZH = "\u97f3\u91cf";
    private static final String PLAYLIST_SCOPE_MINE = "mine";
    private static final String PLAYLIST_SCOPE_ALL = "all";

    public List<CommandData> buildCommands() {
        List<CommandData> commands = new ArrayList<>();
        commands.add(Commands.slash(CommandNames.CMD_HELP, "Show bot help")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_HELP_ZH, "\u986f\u793a\u6a5f\u5668\u4eba\u8aaa\u660e")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_PING, "Check bot latency")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_PING_ZH, "\u6aa2\u67e5 Bot \u5ef6\u9072")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_SHORT_URL, "Create a short URL")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(
                        new OptionData(OptionType.STRING, "url", "Target URL (http/https)", true),
                        new OptionData(OptionType.STRING, "slug", "Custom slug (optional), e.g. discord", false)
                ));
        commands.add(Commands.slash(CommandNames.CMD_SHORT_URL_ZH, "\u5efa\u7acb\u77ed\u7db2\u5740")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(
                        new OptionData(OptionType.STRING, "url", "\u76ee\u6a19 URL\uff08http/https\uff09", true),
                        new OptionData(OptionType.STRING, "slug", "\u81ea\u8a02\u77ed\u78bc\uff08\u9078\u586b\uff09\uff0c\u4f8b\u5982 discord", false)
                ));
        commands.add(buildMinecraftStatusCommand(CommandNames.CMD_MINECRAFT_STATUS));
        commands.add(buildMinecraftStatusCommand(CommandNames.CMD_MINECRAFT_STATUS_ZH));
        commands.add(Commands.slash(CommandNames.CMD_WELCOME, "Edit member join welcome message")
                .addOptions(
                        buildWelcomeActionOption(false),
                        new OptionData(OptionType.CHANNEL, CommandOptions.CHANNEL, "Welcome message channel", false)
                                .setChannelTypes(ChannelType.TEXT)
                )
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(Commands.slash(CommandNames.CMD_WELCOME_ZH, "\u7de8\u8f2f\u6210\u54e1\u52a0\u5165\u6b61\u8fce\u8a0a\u606f")
                .addOptions(
                        buildWelcomeActionOption(true),
                        new OptionData(OptionType.CHANNEL, OPTION_WELCOME_CHANNEL_ZH, "\u6b61\u8fce\u8a0a\u606f\u983b\u9053", false)
                                .setChannelTypes(ChannelType.TEXT)
                )
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(Commands.slash(CommandNames.CMD_JOIN, "Join your voice channel")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_JOIN_ZH, "\u8b93 Bot \u52a0\u5165\u4f60\u7684\u8a9e\u97f3\u983b\u9053")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_PLAY, "Play music")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.STRING, "query", "URL / keywords / Spotify URL", true)));
        commands.add(Commands.slash(CommandNames.CMD_PLAY_ZH, "\u64ad\u653e\u97f3\u6a02")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.STRING, "query", "URL\uff0f\u95dc\u9375\u5b57\uff0fSpotify \u9023\u7d50", true)));
        commands.add(Commands.slash(CommandNames.CMD_SKIP, "Skip current track")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_SKIP_ZH, "\u8df3\u904e\u76ee\u524d\u6b4c\u66f2")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_STOP, "Stop playback and clear queue")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_STOP_ZH, "\u505c\u6b62\u64ad\u653e\u4e26\u6e05\u7a7a\u4f47\u5217")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_LEAVE, "Leave voice channel")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_LEAVE_ZH, "\u8b93 Bot \u96e2\u958b\u8a9e\u97f3\u983b\u9053")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_MUSIC_PANEL, "Create music control panel")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_MUSIC_PANEL_ZH, "\u5efa\u7acb\u97f3\u6a02\u63a7\u5236\u9762\u677f")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_ROOM_SETTINGS, "Manage your private room")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_ROOM_SETTINGS_ZH, "\u7ba1\u7406\u4f60\u7684\u79c1\u4eba\u5305\u5ec2")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_REPEAT, "Set repeat mode")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.STRING, "mode", "off/single/all", true)
                        .addChoice("off", "OFF")
                        .addChoice("single", "SINGLE")
                        .addChoice("all", "ALL")));
        commands.add(Commands.slash(CommandNames.CMD_REPEAT_ZH, "\u8a2d\u5b9a\u5faa\u74b0\u6a21\u5f0f")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.STRING, "mode", "off\uff08\u95dc\uff09 / single\uff08\u55ae\u66f2\uff09 / all\uff08\u4f47\u5217\uff09", true)
                        .addChoice("off", "OFF")
                        .addChoice("single", "SINGLE")
                        .addChoice("all", "ALL")));
        commands.add(Commands.slash(CommandNames.CMD_VOLUME, "Set playback volume")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.INTEGER, CommandOptions.VALUE, "1-100", true).setRequiredRange(1, 100)));
        commands.add(Commands.slash(CommandNames.CMD_VOLUME_ZH, "\u8a2d\u5b9a\u64ad\u653e\u97f3\u91cf")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(localizedOptionName(
                        new OptionData(OptionType.INTEGER, CommandOptions.VALUE, "1-100", true).setRequiredRange(1, 100),
                        OPTION_VOLUME_VALUE_ZH,
                        OPTION_VOLUME_VALUE_ZH
                )));
        commands.add(Commands.slash(CommandNames.CMD_HISTORY, "Show recently played tracks")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_HISTORY_ZH, "\u986f\u793a\u6700\u8fd1\u64ad\u653e\u7684\u6b4c\u66f2")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_MUSIC, "Music utility commands")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addSubcommands(
                        new SubcommandData("stats", "Show music statistics")
                ));
        commands.add(Commands.slash(CommandNames.CMD_MUSIC_ZH, "\u97f3\u6a02\u5de5\u5177\u6307\u4ee4")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addSubcommands(
                        localizedSubcommandName(
                                new SubcommandData("stats", "\u986f\u793a\u97f3\u6a02\u7d71\u8a08"),
                                SUB_MUSIC_STATS_ZH,
                                SUB_MUSIC_STATS_ZH
                        )
                ));
        commands.add(buildPlaylistCommand(CommandNames.CMD_PLAYLIST));
        commands.add(buildPlaylistCommand(CommandNames.CMD_PLAYLIST_ZH));
        // /settings and /設定 are now simple slash commands without any options
        commands.add(Commands.slash(CommandNames.CMD_SETTINGS, "Guild settings")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(Commands.slash(CommandNames.CMD_SETTINGS_ZH, "\u8a2d\u5b9a")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(buildDeleteCommand());
        commands.add(buildDeleteCommandZh());
        commands.add(buildWarningsCommand(CommandNames.CMD_WARNINGS));
        commands.add(buildWarningsCommand(CommandNames.CMD_WARNINGS_ZH));
        commands.add(buildAntiDuplicateCommand(CommandNames.CMD_ANTI_DUPLICATE));
        commands.add(buildAntiDuplicateCommand(CommandNames.CMD_ANTI_DUPLICATE_ZH));
        commands.add(buildHoneypotCommand(CommandNames.CMD_HONEYPOT));
        commands.add(buildHoneypotCommand(CommandNames.CMD_HONEYPOT_ZH));
        commands.add(buildNumberChainCommand(CommandNames.CMD_NUMBER_CHAIN));
        commands.add(buildNumberChainCommand(CommandNames.CMD_NUMBER_CHAIN_ZH));
        commands.add(buildWordChainCommand(CommandNames.CMD_WORD_CHAIN, false));
        commands.add(buildWordChainCommand(CommandNames.CMD_WORD_CHAIN_ZH, true));
        commands.add(buildTicketCommand(CommandNames.CMD_TICKET));
        commands.add(buildTicketCommand(CommandNames.CMD_TICKET_ZH));
        commands.add(buildUserInfoCommand(CommandNames.CMD_USER_INFO));
        commands.add(buildUserInfoCommand(CommandNames.CMD_USER_INFO_ZH));
        commands.add(buildRoleInfoCommand(CommandNames.CMD_ROLE_INFO));
        commands.add(buildRoleInfoCommand(CommandNames.CMD_ROLE_INFO_ZH));
        commands.add(buildServerInfoCommand(CommandNames.CMD_SERVER_INFO));
        commands.add(buildServerInfoCommand(CommandNames.CMD_SERVER_INFO_ZH));
        commands.add(Commands.slash(CommandNames.CMD_STATS, "Show message and voice stats for a user")
                .addOptions(new OptionData(OptionType.USER, "user", "Target user", false))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_STATS_ZH, "\u986f\u793a\u4f7f\u7528\u8005\u7684\u8a0a\u606f\u8207\u8a9e\u97f3\u7d71\u8a08")
                .addOptions(new OptionData(OptionType.USER, "user", "Target user", false))
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_LEADERBOARD, "Open leaderboard menu")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        commands.add(Commands.slash(CommandNames.CMD_LEADERBOARD_ZH, "\u958b\u555f\u6392\u884c\u699c\u9078\u55ae")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED));
        return commands;
    }

    private SlashCommandData buildNumberChainCommand(String commandName) {
        boolean zh = CommandNames.CMD_NUMBER_CHAIN_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u6578\u5b57\u63a5\u9f8d\u8a2d\u5b9a" : "Number chain settings")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED);
    }

    private SlashCommandData buildWordChainCommand(String commandName, boolean zh) {
        return Commands.slash(commandName, zh ? "\u82f1\u6587\u55ae\u5b57\u63a5\u9f8d" : "English word chain")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED);
    }

    private OptionData buildWelcomeActionOption(boolean zh) {
        OptionData option = new OptionData(OptionType.STRING, CommandOptions.ACTION,
                zh ? "\u6b61\u8fce\u8a0a\u606f\u64cd\u4f5c" : "Welcome message action", false);
        option.addChoices(
                new Command.Choice(zh ? SUB_GENERIC_ENABLE_ZH : "enable", "enable"),
                new Command.Choice(zh ? SUB_GENERIC_STATUS_ZH : "status", "status")
        );
        if (zh) {
            option.setNameLocalization(DiscordLocale.CHINESE_TAIWAN, "\u9078\u9805");
            option.setNameLocalization(DiscordLocale.CHINESE_CHINA, "\u9009\u9879");
        }
        return option;
    }

    private SlashCommandData buildTicketCommand(String commandName) {
        boolean zh = CommandNames.CMD_TICKET_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u5ba2\u670d\u55ae\u7cfb\u7d71" : "Ticket system")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED);
    }

    private SlashCommandData buildUserInfoCommand(String commandName) {
        boolean zh = CommandNames.CMD_USER_INFO_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u67e5\u8a62\u4f7f\u7528\u8005\u8cc7\u8a0a" : "Show user information")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.USER, "user", zh ? "\u8981\u67e5\u8a62\u7684\u4f7f\u7528\u8005" : "User to inspect", false));
    }

    private SlashCommandData buildRoleInfoCommand(String commandName) {
        boolean zh = CommandNames.CMD_ROLE_INFO_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u67e5\u8a62\u8eab\u5206\u7d44\u8cc7\u8a0a" : "Show role information")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(new OptionData(OptionType.ROLE, "role", zh ? "\u8981\u67e5\u8a62\u7684\u8eab\u5206\u7d44" : "Role to inspect", true));
    }

    private SlashCommandData buildServerInfoCommand(String commandName) {
        boolean zh = CommandNames.CMD_SERVER_INFO_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u67e5\u8a62\u4f3a\u670d\u5668\u8cc7\u8a0a" : "Show server information")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED);
    }

    private SlashCommandData buildPlaylistCommand(String commandName) {
        boolean zh = CommandNames.CMD_PLAYLIST_ZH.equals(commandName);
        SubcommandData save = new SubcommandData(zh ? "\u5132\u5b58" : "save",
                zh ? "\u5132\u5b58\u76ee\u524d\u4f47\u5217\u70ba\u6b4c\u55ae" : "Save current queue as playlist")
                .addOptions(new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                        .setAutoComplete(true));
        SubcommandData load = new SubcommandData(zh ? "\u8f09\u5165" : "load",
                zh ? "\u8f09\u5165\u5df2\u5132\u5b58\u6b4c\u55ae" : "Load saved playlist")
                .addOptions(new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                        .setAutoComplete(true));
        SubcommandData add = new SubcommandData(zh ? SUB_PLAYLIST_ADD_ZH : "add",
                zh ? "\u5c07\u6307\u5b9a URL \u6b4c\u66f2\u65b0\u589e\u5230\u6b4c\u55ae\uff08\u50c5\u9650\u6b4c\u55ae\u5efa\u7acb\u8005\uff09"
                        : "Add a track by URL to a playlist (playlist owner only)")
                .addOptions(
                        new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                                .setAutoComplete(true),
                        new OptionData(OptionType.STRING, "url", zh ? "URL" : "URL", true)
                );
        SubcommandData delete = new SubcommandData(zh ? "\u522a\u9664" : "delete",
                zh ? "\u522a\u9664\u5df2\u5132\u5b58\u6b4c\u55ae" : "Delete saved playlist")
                .addOptions(new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                        .setAutoComplete(true));
        SubcommandData list = new SubcommandData(zh ? "\u5217\u8868" : "list",
                zh ? "\u5217\u51fa\u5df2\u5132\u5b58\u6b4c\u55ae" : "List saved playlists")
                .addOptions(new OptionData(OptionType.STRING, "scope", zh ? "\u986f\u793a\u7bc4\u570d" : "Display scope", false)
                        .addChoice(zh ? "\u53ea\u986f\u793a\u81ea\u5df1\u7684" : "Only mine", PLAYLIST_SCOPE_MINE)
                        .addChoice(zh ? "\u5168\u90e8\u6b4c\u55ae" : "All playlists", PLAYLIST_SCOPE_ALL));
        SubcommandData view = new SubcommandData(zh ? SUB_PLAYLIST_VIEW_ZH : "view",
                zh ? "\u67e5\u770b\u6b4c\u55ae\u5167\u7684\u6b4c\u66f2" : "View tracks inside a playlist")
                .addOptions(new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                        .setAutoComplete(true));
        SubcommandData removeTrack = new SubcommandData(zh ? SUB_PLAYLIST_REMOVE_TRACK_ZH : "remove-track",
                zh ? "\u522a\u9664\u6b4c\u55ae\u5167\u7684\u6b4c\u66f2\uff08\u5148\u4f7f\u7528 /" + CommandNames.CMD_PLAYLIST_ZH + " " + SUB_PLAYLIST_VIEW_ZH + " \u67e5\u770b\u7de8\u865f\uff09"
                        : "Remove a track from a playlist (use /playlist view to find the track number)")
                .addOptions(
                        new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true).setAutoComplete(true),
                        new OptionData(OptionType.INTEGER, "index", zh ? "\u6b4c\u66f2\u7de8\u865f\uff081 \u958b\u59cb\uff09" : "Track number (starting at 1)", true)
                                .setRequiredRange(1, 10000)
                );
        SubcommandData export = new SubcommandData(zh ? "\u532f\u51fa" : "export",
                zh ? "\u7522\u751f 6 \u4f4d\u6578\u4ee3\u78bc\u4f9b\u8de8\u4f3a\u670d\u5668\u532f\u5165" : "Generate a 6-digit code for cross-server import")
                .addOptions(new OptionData(OptionType.STRING, "name", zh ? "\u6b4c\u55ae\u540d\u7a31" : "Playlist name", true)
                        .setAutoComplete(true));
        SubcommandData importSub = new SubcommandData(zh ? "\u532f\u5165" : "import",
                zh ? "\u4f7f\u7528 6 \u4f4d\u6578\u4ee3\u78bc\u532f\u5165\u6b4c\u55ae" : "Import playlist using a 6-digit code")
                .addOptions(
                        new OptionData(OptionType.STRING, "code", zh ? "\u516d\u4f4d\u6578\u4ee3\u78bc" : "6-digit code", true),
                        new OptionData(OptionType.STRING, "name", zh ? "\u65b0\u6b4c\u55ae\u540d\u7a31\uff08\u9078\u586b\uff09" : "New playlist name (optional)", false)
                );
        return Commands.slash(commandName, zh ? "\u6b4c\u55ae\u7ba1\u7406" : "Playlist management")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addSubcommands(save, load, add, delete, list, view, removeTrack, export, importSub);
    }

    private SlashCommandData buildDeleteCommand() {
        return Commands.slash(CommandNames.CMD_DELETE, "Delete messages")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE))
                .addOptions(
                        new OptionData(OptionType.STRING, "type", "Delete type", true)
                                .addChoices(
                                        new Command.Choice(CommandOptions.CHANNEL, CommandOptions.CHANNEL),
                                        new Command.Choice("user", "user")
                                ),
                        new OptionData(OptionType.CHANNEL, CommandOptions.CHANNEL, "Text channel", false)
                                .setChannelTypes(ChannelType.TEXT),
                        new OptionData(OptionType.USER, "user", "Target user", false),
                        new OptionData(OptionType.STRING, "time", "Duration like 24h or 13d23h59m59s (max 14d, default 24h)", false),
                        new OptionData(OptionType.INTEGER, "amount", "1-99", false).setRequiredRange(1, 99)
                );
    }

    private SlashCommandData buildDeleteCommandZh() {
        return Commands.slash(CommandNames.CMD_DELETE_ZH, "\u522a\u9664\u8a0a\u606f")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE))
                .addOptions(
                        new OptionData(OptionType.STRING, "type", "\u522a\u9664\u985e\u578b", true)
                                .addChoices(
                                        new Command.Choice(SUB_DELETE_CHANNEL_ZH, CommandOptions.CHANNEL),
                                        new Command.Choice(SUB_DELETE_USER_ZH, "user")
                                ),
                        new OptionData(OptionType.CHANNEL, CommandOptions.CHANNEL, "\u6587\u5b57\u983b\u9053", false)
                                .setChannelTypes(ChannelType.TEXT),
                        new OptionData(OptionType.USER, "user", "\u76ee\u6a19\u4f7f\u7528\u8005", false),
                        new OptionData(OptionType.STRING, "time", "\u6642\u9593\u7bc4\u570d\uff0c\u4f8b\u5982 24h \u6216 13d23h59m59s\uff08\u4e0a\u9650 14d\uff0c\u9810\u8a2d 24h\uff09", false),
                        new OptionData(OptionType.INTEGER, "amount", "1-99", false).setRequiredRange(1, 99)
                );
    }

    // The settings command is now defined directly in buildCommands() without any options.

    private static OptionData localizedOptionName(OptionData option, String zhTwName, String zhCnName) {
        return option
                .setNameLocalization(DiscordLocale.CHINESE_TAIWAN, zhTwName)
                .setNameLocalization(DiscordLocale.CHINESE_CHINA, zhCnName);
    }

    private static SubcommandData localizedSubcommandName(SubcommandData subcommand, String zhTwName, String zhCnName) {
        return subcommand
                .setNameLocalization(DiscordLocale.CHINESE_TAIWAN, zhTwName)
                .setNameLocalization(DiscordLocale.CHINESE_CHINA, zhCnName);
    }


    private OptionData buildWarningsActionOption(boolean zh) {
        OptionData option = new OptionData(OptionType.STRING, CommandOptions.ACTION,
                zh ? "\u8b66\u544a\u7ba1\u7406" : "Manage warning counts", true)
                .addChoices(
                        new Command.Choice(zh ? "\u589e\u52a0" : "add", "add"),
                        new Command.Choice(zh ? "\u6e1b\u5c11" : "remove", "remove"),
                        new Command.Choice(zh ? "\u67e5\u770b" : "view", "view"),
                        new Command.Choice(zh ? "\u6e05\u9664" : "clear", "clear")
                );
        if (zh) {
            option.setNameLocalization(DiscordLocale.CHINESE_TAIWAN, "\u9078\u9805");
            option.setNameLocalization(DiscordLocale.CHINESE_CHINA, "\u9009\u9879");
        }
        return option;
    }

    private OptionData buildAntiDuplicateActionOption(boolean zh) {
        OptionData option = new OptionData(OptionType.STRING, CommandOptions.ACTION,
                zh ? "\u9632\u91cd\u8907\u8a0a\u606f\u5075\u6e2c\u8a2d\u5b9a" : "Duplicate message detection settings", true)
                .addChoices(
                        new Command.Choice(zh ? SUB_GENERIC_ENABLE_ZH : "enable", "enable"),
                        new Command.Choice(zh ? SUB_GENERIC_STATUS_ZH : "status", "status")
                );
        if (zh) {
            option.setNameLocalization(DiscordLocale.CHINESE_TAIWAN, "\u9078\u9805");
            option.setNameLocalization(DiscordLocale.CHINESE_CHINA, "\u9009\u9879");
        }
        return option;
    }

    private SlashCommandData buildWarningsCommand(String commandName) {
        boolean zh = CommandNames.CMD_WARNINGS_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u7ba1\u7406\u8b66\u544a\u6b21\u6578" : "Manage warning counts")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MODERATE_MEMBERS))
                .addOptions(
                        buildWarningsActionOption(zh),
                        new OptionData(OptionType.USER, "user", zh ? "\u76ee\u6a19\u4f7f\u7528\u8005" : "Target user", false),
                        new OptionData(OptionType.INTEGER, "amount", zh ? "\u8b66\u544a\u6578\u91cf" : "Warning amount", false).setRequiredRange(1, 50)
                );
    }

    private SlashCommandData buildAntiDuplicateCommand(String commandName) {
        boolean zh = CommandNames.CMD_ANTI_DUPLICATE_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u91cd\u8907\u8a0a\u606f\u5075\u6e2c\u8a2d\u5b9a" : "Duplicate message detection settings")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .addOptions(
                        buildAntiDuplicateActionOption(zh),
                        new OptionData(OptionType.BOOLEAN, CommandOptions.VALUE, "true or false", false)
                );
    }

    private SlashCommandData buildHoneypotCommand(String commandName) {
        boolean zh = CommandNames.CMD_HONEYPOT_ZH.equals(commandName);
        return Commands.slash(commandName, zh ? "\u5efa\u7acb\u5bc6\u7f50\u6587\u5b57\u983b\u9053" : "Create a honeypot text channel")
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER));
    }

    private SlashCommandData buildMinecraftStatusCommand(String commandName) {
        boolean zh = CommandNames.CMD_MINECRAFT_STATUS_ZH.equals(commandName);
        OptionData addressOption = new OptionData(
                OptionType.STRING,
                "address",
                zh ? "\u4f3a\u670d\u5668\u4f4d\u5740\uff08\u4e0d\u8981\u542b http:// \u6216 https://\uff09" : "Server address (without http:// or https://)",
                true
        );
        OptionData typeOption = new OptionData(OptionType.STRING, "type", zh ? "\u4f3a\u670d\u5668\u985e\u578b" : "Server type", false)
                .addChoices(
                        new Command.Choice("JAVA", "JAVA"),
                        new Command.Choice("BEDROCK", "BEDROCK")
                );
        return Commands.slash(commandName, zh ? "\u67e5\u8a62 Minecraft \u4f3a\u670d\u5668\u72c0\u614b" : "Query Minecraft server status")
                .setDefaultPermissions(DefaultMemberPermissions.ENABLED)
                .addOptions(addressOption, typeOption);
    }
}
