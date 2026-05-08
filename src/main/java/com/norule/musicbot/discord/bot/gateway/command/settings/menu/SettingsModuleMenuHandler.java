package com.norule.musicbot.discord.bot.gateway.command.settings.menu;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SettingsModuleMenuHandler {
    private static final String SETTINGS_MODULE_SELECT_PREFIX = ComponentIds.SETTINGS_MODULE_SELECT_PREFIX;
    private static final String KEY_UNKNOWN_COMMAND = "general.unknown_command";
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";
    private static final String OPTION_VALUE = "value";

    private static final String K_NOTIFICATIONS_ENABLED = "settings.key_notifications_enabled";
    private static final String K_MESSAGE_LOGS_ENABLED = "settings.key_messageLogs_enabled";
    private static final String K_MEMBER_JOIN_ENABLED = "settings.key_notifications_memberJoinEnabled";
    private static final String K_WELCOME_ENABLED = "settings.key_welcome_enabled";
    private static final String K_MEMBER_LEAVE_ENABLED = "settings.key_notifications_memberLeaveEnabled";
    private static final String K_VOICE_LOG_ENABLED = "settings.key_notifications_voiceLogEnabled";
    private static final String K_LOG_COMMAND_USAGE = "settings.info_key_log_command_usage";
    private static final String K_LOG_CHANNEL_LIFECYCLE = "settings.info_key_log_channel_lifecycle";
    private static final String K_LOG_ROLE = "settings.info_key_log_role";
    private static final String K_LOG_MODERATION = "settings.info_key_log_moderation";
    private static final String K_MUSIC_AUTO_LEAVE = "settings.key_music_autoLeaveEnabled";
    private static final String K_MUSIC_AUTOPLAY = "settings.key_music_autoplayEnabled";
    private static final String K_NUMBER_CHAIN_ENABLED = "settings.key_numberChain_enabled";
    private static final String K_TICKET_ENABLED = "settings.key_ticket_enabled";
    private static final String K_PRIVATE_ROOM_ENABLED = "settings.key_privateRoom_enabled";

    private static final String A_MESSAGE_LOG = "message-log";
    private static final String A_COMMAND_USAGE_LOG = "command-usage-log";
    private static final String A_CHANNEL_EVENTS_LOG = "channel-events-log";
    private static final String A_ROLE_EVENTS_LOG = "role-events-log";
    private static final String A_MODERATION_LOG = "moderation-log";

    private final MusicCommandService owner;
    private final Map<String, ModuleMenuRequest> moduleMenuRequests = new ConcurrentHashMap<>();

    public SettingsModuleMenuHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        moduleMenuRequests.entrySet().removeIf(entry -> entry.getValue() == null || cutoff.isAfter(entry.getValue().expiresAt));
    }

    public void openModuleMenu(SlashCommandInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.replyEmbeds(moduleMenuEmbed(event.getGuild(), lang, null).build())
                .addComponents(ActionRow.of(settingsModuleMenu(token, event.getGuild().getIdLong(), lang)))
                .setEphemeral(true)
                .queue();
    }

    public void openModuleMenu(StringSelectInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.editMessageEmbeds(moduleMenuEmbed(event.getGuild(), lang, null).build())
                .setComponents(ActionRow.of(settingsModuleMenu(token, event.getGuild().getIdLong(), lang)))
                .queue();
    }

    public void handleModuleMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_MODULE_SELECT_PREFIX.length());
        ModuleMenuRequest request = moduleMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            moduleMenuRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "settings.module_menu_expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }
        String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        if (needsDefaultLogChannel(action)
                && !owner.settingsService().getMessageLogs(event.getGuild().getIdLong()).isEnabled()
                && owner.settingsService().getMessageLogs(event.getGuild().getIdLong()).getChannelId() == null) {
            event.reply(owner.i18nService().t(lang, "settings.logs_default_required")).setEphemeral(true).queue();
            return;
        }
        ToggleResult result = toggleModuleValue(event.getGuild().getIdLong(), action);
        if (result == null) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String keyText = owner.i18nService().t(lang, result.key);
        String changed = owner.i18nService().t(lang, "general.settings_saved",
                Map.of("key", keyText, OPTION_VALUE, moduleSwitchTextCode(lang, result.value)));
        event.editMessageEmbeds(moduleMenuEmbed(event.getGuild(), lang, changed).build())
                .setComponents(ActionRow.of(settingsModuleMenu(token, event.getGuild().getIdLong(), lang)))
                .queue();
    }

    private StringSelectMenu settingsModuleMenu(String token, long guildId, String lang) {
        var s = owner.settingsService().getSettings(guildId);
        boolean numberChainEnabled = owner.moderationService().isNumberChainEnabled(guildId);
        return StringSelectMenu.create(SETTINGS_MODULE_SELECT_PREFIX + token)
                .setPlaceholder(owner.i18nService().t(lang, "settings.module_menu_placeholder"))
                .addOptions(
                        SelectOption.of(owner.i18nService().t(lang, K_NOTIFICATIONS_ENABLED), "notifications-enable")
                                .withDescription(moduleSwitchTextPlain(lang, s.getNotifications().isEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_MESSAGE_LOGS_ENABLED), A_MESSAGE_LOG)
                                .withDescription(moduleSwitchTextPlain(lang, s.getMessageLogs().isEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_MEMBER_JOIN_ENABLED), "member-join")
                                .withDescription(moduleSwitchTextPlain(lang, s.getNotifications().isMemberJoinEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_WELCOME_ENABLED), "welcome-enable")
                                .withDescription(moduleSwitchTextPlain(lang, s.getWelcome().isEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_MEMBER_LEAVE_ENABLED), "member-leave")
                                .withDescription(moduleSwitchTextPlain(lang, s.getNotifications().isMemberLeaveEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_VOICE_LOG_ENABLED), "voice-log")
                                .withDescription(moduleSwitchTextPlain(lang, s.getNotifications().isVoiceLogEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_LOG_COMMAND_USAGE), A_COMMAND_USAGE_LOG)
                                .withDescription(moduleSwitchTextPlain(lang, s.getMessageLogs().isCommandUsageLogEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_LOG_CHANNEL_LIFECYCLE), A_CHANNEL_EVENTS_LOG)
                                .withDescription(moduleSwitchTextPlain(lang, s.getMessageLogs().isChannelLifecycleLogEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_LOG_ROLE), A_ROLE_EVENTS_LOG)
                                .withDescription(moduleSwitchTextPlain(lang, s.getMessageLogs().isRoleLogEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_LOG_MODERATION), A_MODERATION_LOG)
                                .withDescription(moduleSwitchTextPlain(lang, s.getMessageLogs().isModerationLogEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_MUSIC_AUTO_LEAVE), "music-auto-leave")
                                .withDescription(moduleSwitchTextPlain(lang, s.getMusic().isAutoLeaveEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_MUSIC_AUTOPLAY), "music-autoplay")
                                .withDescription(moduleSwitchTextPlain(lang, s.getMusic().isAutoplayEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_NUMBER_CHAIN_ENABLED), "number-chain-enable")
                                .withDescription(moduleSwitchTextPlain(lang, numberChainEnabled)),
                        SelectOption.of(owner.i18nService().t(lang, K_TICKET_ENABLED), "ticket-enable")
                                .withDescription(moduleSwitchTextPlain(lang, s.getTicket().isEnabled())),
                        SelectOption.of(owner.i18nService().t(lang, K_PRIVATE_ROOM_ENABLED), "private-room-enable")
                                .withDescription(moduleSwitchTextPlain(lang, s.getPrivateRoom().isEnabled()))
                )
                .build();
    }

    private EmbedBuilder moduleMenuEmbed(Guild guild, String lang, String changedText) {
        var s = owner.settingsService().getSettings(guild.getIdLong());
        boolean numberChainEnabled = owner.moderationService().isNumberChainEnabled(guild.getIdLong());
        String overview = joinLines(
                "**" + owner.i18nService().t(lang, "settings.module_section_core") + "**",
                moduleLine(lang, K_NOTIFICATIONS_ENABLED, s.getNotifications().isEnabled()),
                moduleLine(lang, K_MESSAGE_LOGS_ENABLED, s.getMessageLogs().isEnabled()),
                moduleLine(lang, K_WELCOME_ENABLED, s.getWelcome().isEnabled()),
                "",
                "**" + owner.i18nService().t(lang, "settings.module_section_notifications") + "**",
                moduleLine(lang, K_MEMBER_JOIN_ENABLED, s.getNotifications().isMemberJoinEnabled()),
                moduleLine(lang, K_MEMBER_LEAVE_ENABLED, s.getNotifications().isMemberLeaveEnabled()),
                moduleLine(lang, K_VOICE_LOG_ENABLED, s.getNotifications().isVoiceLogEnabled()),
                "",
                "**" + owner.i18nService().t(lang, "settings.module_section_logs") + "**",
                moduleLine(lang, K_LOG_COMMAND_USAGE, s.getMessageLogs().isCommandUsageLogEnabled()),
                moduleLine(lang, K_LOG_CHANNEL_LIFECYCLE, s.getMessageLogs().isChannelLifecycleLogEnabled()),
                moduleLine(lang, K_LOG_ROLE, s.getMessageLogs().isRoleLogEnabled()),
                moduleLine(lang, K_LOG_MODERATION, s.getMessageLogs().isModerationLogEnabled()),
                "",
                "**" + owner.i18nService().t(lang, "settings.module_section_music_others") + "**",
                moduleLine(lang, K_MUSIC_AUTO_LEAVE, s.getMusic().isAutoLeaveEnabled()),
                moduleLine(lang, K_MUSIC_AUTOPLAY, s.getMusic().isAutoplayEnabled()),
                moduleLine(lang, K_NUMBER_CHAIN_ENABLED, numberChainEnabled),
                moduleLine(lang, K_TICKET_ENABLED, s.getTicket().isEnabled()),
                moduleLine(lang, K_PRIVATE_ROOM_ENABLED, s.getPrivateRoom().isEnabled())
        );
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(52, 152, 219))
                .setTitle(owner.i18nService().t(lang, "settings.module_menu_title"))
                .setDescription(owner.i18nService().t(lang, "settings.module_menu_desc"));
        eb.addField(owner.i18nService().t(lang, "settings.info_module"), overview, false);
        if (changedText != null && !changedText.isBlank()) {
            eb.addField(owner.i18nService().t(lang, "settings.template_updated"), changedText, false);
        }
        return eb;
    }

    private String moduleLine(String lang, String key, boolean value) {
        return keyIcon(key) + " " + owner.i18nService().t(lang, key) + ": " + moduleSwitchTextCode(lang, value);
    }

    private ToggleResult toggleModuleValue(long guildId, String action) {
        switch (action) {
            case "notifications-enable" -> {
                boolean value = !owner.settingsService().getNotifications(guildId).isEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withEnabled(value)));
                return new ToggleResult(K_NOTIFICATIONS_ENABLED, value);
            }
            case "voice-log" -> {
                boolean value = !owner.settingsService().getNotifications(guildId).isVoiceLogEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withVoiceLogEnabled(value)));
                return new ToggleResult(K_VOICE_LOG_ENABLED, value);
            }
            case A_MESSAGE_LOG -> {
                boolean value = !owner.settingsService().getMessageLogs(guildId).isEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withEnabled(value)));
                return new ToggleResult(K_MESSAGE_LOGS_ENABLED, value);
            }
            case "member-leave" -> {
                boolean value = !owner.settingsService().getNotifications(guildId).isMemberLeaveEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withMemberLeaveEnabled(value)));
                return new ToggleResult(K_MEMBER_LEAVE_ENABLED, value);
            }
            case "welcome-enable" -> {
                boolean value = !owner.settingsService().getWelcome(guildId).isEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withWelcome(s.getWelcome().withEnabled(value)));
                return new ToggleResult(K_WELCOME_ENABLED, value);
            }
            case "member-join" -> {
                boolean value = !owner.settingsService().getNotifications(guildId).isMemberJoinEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withNotifications(s.getNotifications().withMemberJoinEnabled(value)));
                return new ToggleResult(K_MEMBER_JOIN_ENABLED, value);
            }
            case A_COMMAND_USAGE_LOG -> {
                boolean value = !owner.settingsService().getMessageLogs(guildId).isCommandUsageLogEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withCommandUsageLogEnabled(value)));
                return new ToggleResult(K_LOG_COMMAND_USAGE, value);
            }
            case A_CHANNEL_EVENTS_LOG -> {
                boolean value = !owner.settingsService().getMessageLogs(guildId).isChannelLifecycleLogEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withChannelLifecycleLogEnabled(value)));
                return new ToggleResult(K_LOG_CHANNEL_LIFECYCLE, value);
            }
            case A_ROLE_EVENTS_LOG -> {
                boolean value = !owner.settingsService().getMessageLogs(guildId).isRoleLogEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withRoleLogEnabled(value)));
                return new ToggleResult(K_LOG_ROLE, value);
            }
            case A_MODERATION_LOG -> {
                boolean value = !owner.settingsService().getMessageLogs(guildId).isModerationLogEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withMessageLogs(s.getMessageLogs().withModerationLogEnabled(value)));
                return new ToggleResult(K_LOG_MODERATION, value);
            }
            case "music-auto-leave" -> {
                boolean value = !owner.settingsService().getMusic(guildId).isAutoLeaveEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withMusic(s.getMusic().withAutoLeaveEnabled(value)));
                return new ToggleResult(K_MUSIC_AUTO_LEAVE, value);
            }
            case "music-autoplay" -> {
                boolean value = !owner.settingsService().getMusic(guildId).isAutoplayEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withMusic(s.getMusic().withAutoplayEnabled(value)));
                if (!value) {
                    owner.musicService().clearAutoplayNotice(guildId);
                }
                return new ToggleResult(K_MUSIC_AUTOPLAY, value);
            }
            case "number-chain-enable" -> {
                boolean value = !owner.moderationService().isNumberChainEnabled(guildId);
                owner.moderationService().setNumberChainEnabled(guildId, value);
                return new ToggleResult(K_NUMBER_CHAIN_ENABLED, value);
            }
            case "private-room-enable" -> {
                boolean value = !owner.settingsService().getPrivateRoom(guildId).isEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withPrivateRoom(s.getPrivateRoom().withEnabled(value)));
                return new ToggleResult(K_PRIVATE_ROOM_ENABLED, value);
            }
            case "ticket-enable" -> {
                boolean value = !owner.settingsService().getTicket(guildId).isEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withTicket(s.getTicket().withEnabled(value)));
                return new ToggleResult(K_TICKET_ENABLED, value);
            }
            default -> {
                return null;
            }
        }
    }

    private String moduleSwitchTextPlain(String lang, boolean enabled) {
        String state = owner.boolText(lang, enabled)
                .replace("✅", "")
                .replace("❌", "")
                .replace("✔️", "")
                .replace("✖️", "")
                .trim();
        return enabled ? "🟢 " + state : "⚪ " + state;
    }

    private String moduleSwitchTextCode(String lang, boolean enabled) {
        return "`" + moduleSwitchTextPlain(lang, enabled) + "`";
    }

    private String joinLines(String... values) {
        return String.join("\n", values);
    }

    private boolean needsDefaultLogChannel(String action) {
        return A_MESSAGE_LOG.equals(action)
                || A_COMMAND_USAGE_LOG.equals(action)
                || A_CHANNEL_EVENTS_LOG.equals(action)
                || A_ROLE_EVENTS_LOG.equals(action)
                || A_MODERATION_LOG.equals(action);
    }

    private String keyIcon(String key) {
        return switch (key) {
            case "settings.info_key_enabled", K_MESSAGE_LOGS_ENABLED,
                 K_NOTIFICATIONS_ENABLED, K_PRIVATE_ROOM_ENABLED -> "⚙️";
            case K_MEMBER_JOIN_ENABLED -> "👋";
            case K_WELCOME_ENABLED -> "🎉";
            case K_MEMBER_LEAVE_ENABLED -> "🚪";
            case K_VOICE_LOG_ENABLED -> "🔊";
            case K_LOG_COMMAND_USAGE -> "🧭";
            case K_LOG_CHANNEL_LIFECYCLE -> "🏗️";
            case K_LOG_ROLE -> "🏷️";
            case K_LOG_MODERATION -> "🛡️";
            case K_MUSIC_AUTO_LEAVE -> "⏱️";
            case K_MUSIC_AUTOPLAY -> "🔁";
            case K_NUMBER_CHAIN_ENABLED -> "1⃣";
            case K_TICKET_ENABLED -> "🎫";
            default -> "▫️";
        };
    }

    private String registerMenuRequest(long requestUserId, long guildId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        moduleMenuRequests.put(token, new ModuleMenuRequest(
                requestUserId,
                guildId,
                Instant.now().plusSeconds(120)
        ));
        return token;
    }

    private static class ModuleMenuRequest {
        private final long requestUserId;
        private final long guildId;
        private final Instant expiresAt;

        private ModuleMenuRequest(long requestUserId, long guildId, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.expiresAt = expiresAt;
        }
    }

    private static class ToggleResult {
        private final String key;
        private final boolean value;

        private ToggleResult(String key, boolean value) {
            this.key = key;
            this.value = value;
        }
    }
}
