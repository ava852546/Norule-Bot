package com.norule.musicbot.discord.bot.gateway.command.settings;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.CommandNames;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.util.Map;

public final class SettingsOptionValidator {
    private static final String ROUTE_ACTION = CommandOptions.ACTION;
    private static final String OPTION_CHANNEL = CommandOptions.CHANNEL;
    private static final String OPTION_VALUE = CommandOptions.VALUE;
    private static final String OPTION_RESET = CommandOptions.RESET;
    private static final String OPTION_LOG_SETTING = CommandOptions.LOG_SETTING;
    private static final String OPTION_PREFIX = CommandOptions.PREFIX;
    private static final String ROUTE_INFO = "info";
    private static final String ROUTE_RELOAD = "reload";
    private static final String ROUTE_TEMPLATE = "template";
    private static final String ROUTE_MODULE = "module";
    private static final String ROUTE_MUSIC = CommandNames.CMD_MUSIC;
    private static final String ROUTE_LANGUAGE = "language";
    private static final String ROUTE_NUMBER_CHAIN = CommandNames.CMD_NUMBER_CHAIN;
    private static final String ROUTE_WORD_CHAIN = CommandNames.CMD_WORD_CHAIN;
    private static final String ROUTE_LOG_SETTINGS = "log-settings";

    private final MusicCommandService owner;

    public SettingsOptionValidator(MusicCommandService owner) {
        this.owner = owner;
    }

    public String validate(SlashCommandInteractionEvent event, String route, String lang) {
        boolean hasCode = event.getOption("code") != null;
        boolean hasChannel = event.getOption(OPTION_CHANNEL) != null;
        boolean hasValue = event.getOption(OPTION_VALUE) != null;
        boolean hasReset = event.getOption(OPTION_RESET) != null;
        boolean hasLogSetting = event.getOption(OPTION_LOG_SETTING) != null;
        boolean hasUser = event.getOption("user") != null;
        boolean hasPrefix = event.getOption(OPTION_PREFIX) != null;
        String logSetting = event.getOption(OPTION_LOG_SETTING) == null ? null : event.getOption(OPTION_LOG_SETTING).getAsString();

        return switch (route) {
            case ROUTE_LANGUAGE -> {
                if (hasChannel) yield settingsActionOptionError(lang, route, OPTION_CHANNEL);
                if (hasValue) yield settingsActionOptionError(lang, route, OPTION_VALUE);
                if (hasReset) yield settingsActionOptionError(lang, route, OPTION_RESET);
                if (hasLogSetting) yield settingsActionOptionError(lang, route, OPTION_LOG_SETTING);
                if (hasUser) yield settingsActionOptionError(lang, route, "user");
                if (hasPrefix) yield settingsActionOptionError(lang, route, OPTION_PREFIX);
                yield null;
            }
            case ROUTE_NUMBER_CHAIN -> {
                if (hasCode) yield settingsActionOptionError(lang, route, "code");
                if (hasLogSetting) yield settingsActionOptionError(lang, route, OPTION_LOG_SETTING);
                if (hasUser) yield settingsActionOptionError(lang, route, "user");
                if (hasPrefix) yield settingsActionOptionError(lang, route, OPTION_PREFIX);
                yield null;
            }
            case ROUTE_WORD_CHAIN -> {
                if (hasCode) yield settingsActionOptionError(lang, route, "code");
                if (hasLogSetting) yield settingsActionOptionError(lang, route, OPTION_LOG_SETTING);
                if (hasUser) yield settingsActionOptionError(lang, route, "user");
                if (hasPrefix) yield settingsActionOptionError(lang, route, OPTION_PREFIX);
                yield null;
            }
            case ROUTE_LOG_SETTINGS -> {
                if (hasCode) yield settingsActionOptionError(lang, route, "code");
                if (hasValue) yield settingsActionOptionError(lang, route, OPTION_VALUE);
                if (hasReset) yield settingsActionOptionError(lang, route, OPTION_RESET);
                if (!hasLogSetting) yield null;
                switch (logSetting) {
                    case "ignore-prefix" -> {
                        if (hasUser) yield settingsActionOptionError(lang, route, "user");
                        if (hasChannel) yield settingsActionOptionError(lang, route, OPTION_CHANNEL);
                    }
                    case "ignore-member" -> {
                        if (hasPrefix) yield settingsActionOptionError(lang, route, OPTION_PREFIX);
                        if (hasChannel) yield settingsActionOptionError(lang, route, OPTION_CHANNEL);
                    }
                    case "ignore-channel" -> {
                        if (hasPrefix) yield settingsActionOptionError(lang, route, OPTION_PREFIX);
                        if (hasUser) yield settingsActionOptionError(lang, route, "user");
                    }
                    case "view-ignore" -> {
                        if (hasPrefix) yield settingsActionOptionError(lang, route, OPTION_PREFIX);
                        if (hasUser) yield settingsActionOptionError(lang, route, "user");
                        if (hasChannel) yield settingsActionOptionError(lang, route, OPTION_CHANNEL);
                    }
                    default -> {
                    }
                }
                yield null;
            }
            default -> {
                if (hasCode) yield settingsActionOptionError(lang, route, "code");
                if (hasChannel) yield settingsActionOptionError(lang, route, OPTION_CHANNEL);
                if (hasValue) yield settingsActionOptionError(lang, route, OPTION_VALUE);
                if (hasReset) yield settingsActionOptionError(lang, route, OPTION_RESET);
                if (hasLogSetting) yield settingsActionOptionError(lang, route, OPTION_LOG_SETTING);
                if (hasUser) yield settingsActionOptionError(lang, route, "user");
                if (hasPrefix) yield settingsActionOptionError(lang, route, OPTION_PREFIX);
                yield null;
            }
        };
    }

    private String settingsActionOptionError(String lang, String route, String option) {
        return owner.i18nService().t(lang, "settings.option_not_allowed",
                Map.of(
                        ROUTE_ACTION, settingsActionLabel(lang, route),
                        "option", settingsOptionLabel(lang, route, option)
                ));
    }

    private String settingsActionLabel(String lang, String route) {
        return switch (route) {
            case ROUTE_INFO -> owner.i18nService().t(lang, "settings.info");
            case ROUTE_RELOAD -> owner.i18nService().t(lang, "settings.reload");
            case OPTION_RESET -> owner.i18nService().t(lang, "settings.reset");
            case ROUTE_TEMPLATE -> owner.i18nService().t(lang, "settings.template");
            case ROUTE_MODULE -> owner.i18nService().t(lang, "settings.module");
            case "logs" -> owner.i18nService().t(lang, "settings.logs");
            case ROUTE_LOG_SETTINGS -> owner.i18nService().t(lang, "settings.log_settings.title");
            case ROUTE_MUSIC -> owner.i18nService().t(lang, "settings.music");
            case ROUTE_LANGUAGE -> owner.i18nService().t(lang, "settings.info_language");
            case ROUTE_NUMBER_CHAIN -> owner.i18nService().t(lang, "settings.info_number_chain");
            case ROUTE_WORD_CHAIN -> owner.i18nService().t(lang, "settings.info_word_chain");
            default -> route;
        };
    }

    private String settingsOptionLabel(String lang, String route, String option) {
        return switch (option) {
            case "code" -> owner.i18nService().t(lang, "settings.language_code_label");
            case OPTION_CHANNEL -> ROUTE_LOG_SETTINGS.equals(route)
                    ? owner.i18nService().t(lang, "settings.log_settings.channel")
                    : owner.i18nService().t(lang, "number_chain.channel");
            case OPTION_VALUE -> owner.i18nService().t(lang, "number_chain.value");
            case OPTION_RESET -> owner.i18nService().t(lang, "number_chain.reset");
            case OPTION_LOG_SETTING -> owner.i18nService().t(lang, "settings.log_settings.target");
            case "user" -> owner.i18nService().t(lang, "settings.log_settings.user");
            case OPTION_PREFIX -> owner.i18nService().t(lang, "settings.log_settings.prefix");
            default -> option;
        };
    }
}
