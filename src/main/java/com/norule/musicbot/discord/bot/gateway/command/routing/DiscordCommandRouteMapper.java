package com.norule.musicbot.discord.bot.gateway.command.routing;

import com.norule.musicbot.discord.bot.gateway.command.CommandNames;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public class DiscordCommandRouteMapper {
    private static final String ROUTE_LANGUAGE    = "language";
    private static final String ROUTE_RELOAD      = "reload";
    private static final String ROUTE_TEMPLATE    = "template";
    private static final String ROUTE_MODULE      = "module";
    private static final String ROUTE_LOG_SETTINGS = "log-settings";
    private static final String ROUTE_ACTION      = CommandOptions.ACTION;
    private static final String OPTION_RESET      = CommandOptions.RESET;
    private static final String OPTION_CHANNEL    = CommandOptions.CHANNEL;

    public String canonicalSlashName(String name) {
        return switch (name) {
            case CommandNames.CMD_HELP_ZH           -> "help";
            case CommandNames.CMD_PING_ZH           -> "ping";
            case CommandNames.CMD_WELCOME_ZH        -> "welcome";
            case CommandNames.CMD_VOLUME_ZH         -> CommandNames.CMD_VOLUME;
            case CommandNames.CMD_HISTORY_ZH        -> CommandNames.CMD_HISTORY;
            case CommandNames.CMD_MUSIC_ZH          -> CommandNames.CMD_MUSIC;
            case CommandNames.CMD_PLAYLIST_ZH       -> CommandNames.CMD_PLAYLIST;
            case CommandNames.CMD_JOIN_ZH           -> "join";
            case CommandNames.CMD_PLAY_ZH           -> "play";
            case CommandNames.CMD_SKIP_ZH           -> "skip";
            case CommandNames.CMD_STOP_ZH           -> "stop";
            case CommandNames.CMD_LEAVE_ZH          -> CommandNames.CMD_LEAVE;
            case CommandNames.CMD_MUSIC_PANEL_ZH    -> "music-panel";
            case CommandNames.CMD_REPEAT_ZH         -> CommandNames.CMD_REPEAT;
            case CommandNames.CMD_SETTINGS_ZH       -> "settings";
            case CommandNames.CMD_DELETE_ZH         -> "delete-messages";
            case CommandNames.CMD_ROOM_SETTINGS_ZH  -> "private-room-settings";
            case CommandNames.CMD_WARNINGS_ZH       -> "warnings";
            case CommandNames.CMD_ANTI_DUPLICATE_ZH -> "anti-duplicate";
            case CommandNames.CMD_HONEYPOT_ZH       -> "honeypot-channel";
            case CommandNames.CMD_NUMBER_CHAIN_ZH   -> CommandNames.CMD_NUMBER_CHAIN;
            case CommandNames.CMD_WORD_CHAIN_ZH     -> CommandNames.CMD_WORD_CHAIN;
            case CommandNames.CMD_TICKET_ZH         -> "ticket";
            case CommandNames.CMD_USER_INFO_ZH      -> "user-info";
            case CommandNames.CMD_ROLE_INFO_ZH      -> "role-info";
            case CommandNames.CMD_SERVER_INFO_ZH    -> "server-info";
            case CommandNames.CMD_STATS_ZH          -> "stats";
            case CommandNames.CMD_LEADERBOARD_ZH    -> "top";
            case CommandNames.CMD_SHORT_URL_ZH      -> "url";
            case CommandNames.CMD_MINECRAFT_STATUS_ZH -> "mcstatus";
            default -> name;
        };
    }

    public String canonicalSettingsSubcommand(String sub) {
        return switch (sub) {
            case "詳細資訊" -> "info";
            case "重載設定" -> ROUTE_RELOAD;
            case "恢復預設" -> OPTION_RESET;
            case "模板編輯" -> ROUTE_TEMPLATE;
            case "模組開關" -> ROUTE_MODULE;
            case "日誌頻道" -> "logs";
            case "日誌忽略" -> ROUTE_LOG_SETTINGS;
            case "音樂設定" -> CommandNames.CMD_MUSIC;
            case "語言設置" -> ROUTE_LANGUAGE;
            case "接龍遊戲" -> CommandNames.CMD_NUMBER_CHAIN;
            case "英文接龍" -> CommandNames.CMD_WORD_CHAIN;
            default -> sub;
        };
    }

    public String canonicalMusicSubcommand(String sub) {
        return switch (sub) {
            case "統計" -> "stats";
            default -> sub;
        };
    }

    public String canonicalPlaylistSubcommand(String sub) {
        return switch (sub) {
            case "儲存"     -> "save";
            case "載入"     -> "load";
            case "新增歌曲" -> "add";
            case "刪除"     -> "delete";
            case "列表"     -> "list";
            case "查看"     -> "view";
            case "刪除歌曲" -> "remove-track";
            case "匯出"     -> "export";
            case "匯入"     -> "import";
            default -> sub;
        };
    }

    private String canonicalDeleteSubcommand(String sub) {
        return switch (sub) {
            case "頻道"     -> OPTION_CHANNEL;
            case "使用者" -> "user";
            default -> sub;
        };
    }

    public String buildSlashRoute(SlashCommandInteractionEvent event) {
        String command = canonicalSlashName(event.getName());
        String group   = event.getSubcommandGroup();
        String sub     = event.getSubcommandName();
        if ("settings".equals(command) && sub != null) {
            sub = canonicalSettingsSubcommand(sub);
        } else if ("settings".equals(command) && event.getOption(ROUTE_ACTION) != null) {
            sub = canonicalSettingsSubcommand(event.getOption(ROUTE_ACTION).getAsString());
        } else if (CommandNames.CMD_MUSIC.equals(command) && sub != null) {
            sub = canonicalMusicSubcommand(sub);
        } else if (CommandNames.CMD_PLAYLIST.equals(command) && sub != null) {
            sub = canonicalPlaylistSubcommand(sub);
        }
        if (("warnings".equals(command) || "anti-duplicate".equals(command) || "ticket".equals(command))
                && sub == null && event.getOption(ROUTE_ACTION) != null) {
            sub = event.getOption(ROUTE_ACTION).getAsString();
        }
        if ("delete-messages".equals(command) && sub != null) {
            sub = canonicalDeleteSubcommand(sub);
        }
        if (group != null && sub != null) {
            return command + " " + group + " " + sub;
        }
        if (sub != null) {
            return command + " " + sub;
        }
        return command;
    }

    public boolean isSlashMusicCommand(String name) {
        name = canonicalSlashName(name);
        return "join".equals(name)
                || "play".equals(name)
                || "skip".equals(name)
                || "stop".equals(name)
                || CommandNames.CMD_LEAVE.equals(name)
                || CommandNames.CMD_REPEAT.equals(name)
                || CommandNames.CMD_VOLUME.equals(name)
                || CommandNames.CMD_HISTORY.equals(name)
                || CommandNames.CMD_MUSIC.equals(name)
                || CommandNames.CMD_PLAYLIST.equals(name)
                || "music-panel".equals(name);
    }

    public boolean isKnownSlashCommand(String name) {
        name = canonicalSlashName(name);
        return "help".equals(name)
                || "ping".equals(name)
                || "welcome".equals(name)
                || CommandNames.CMD_VOLUME.equals(name)
                || CommandNames.CMD_HISTORY.equals(name)
                || CommandNames.CMD_MUSIC.equals(name)
                || CommandNames.CMD_PLAYLIST.equals(name)
                || "join".equals(name)
                || "play".equals(name)
                || "skip".equals(name)
                || "stop".equals(name)
                || CommandNames.CMD_LEAVE.equals(name)
                || "music-panel".equals(name)
                || CommandNames.CMD_REPEAT.equals(name)
                || "settings".equals(name)
                || "delete-messages".equals(name)
                || "private-room-settings".equals(name)
                || "warnings".equals(name)
                || "anti-duplicate".equals(name)
                || "honeypot-channel".equals(name)
                || CommandNames.CMD_NUMBER_CHAIN.equals(name)
                || CommandNames.CMD_WORD_CHAIN.equals(name)
                || "user-info".equals(name)
                || "role-info".equals(name)
                || "server-info".equals(name)
                || "url".equals(name)
                || "mcstatus".equals(name)
                || "stats".equals(name)
                || "top".equals(name);
    }
}
