package com.norule.musicbot.discord.bot.gateway.command.settings.menu;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import com.norule.musicbot.discord.bot.gateway.command.settings.view.SettingsUiText;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SettingsMusicMenuHandler {
    private static final String SETTINGS_MUSIC_SELECT_PREFIX  = ComponentIds.SETTINGS_MUSIC_SELECT_PREFIX;
    private static final String SETTINGS_MUSIC_CHANNEL_PREFIX = ComponentIds.SETTINGS_MUSIC_CHANNEL_PREFIX;
    private static final String SETTINGS_MUSIC_MODAL_PREFIX   = ComponentIds.SETTINGS_MUSIC_MODAL_PREFIX;
    private static final String KEY_UNKNOWN_COMMAND      = "general.unknown_command";
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";
    private static final String OPTION_VALUE = CommandOptions.VALUE;

    private static final String K_AUTO_LEAVE_ENABLED  = "settings.key_music_autoLeaveEnabled";
    private static final String K_AUTO_LEAVE_MINUTES  = "settings.key_music_autoLeaveMinutes";
    private static final String K_AUTOPLAY_ENABLED    = "settings.key_music_autoplayEnabled";
    private static final String K_COMMAND_CHANNEL     = "settings.key_music_commandChannelId";
    private static final String K_PRIVATE_ROOM_CHANNEL = "settings.key_privateRoom_triggerVoiceChannelId";

    private static final String A_AUTO_LEAVE_TOGGLE  = "auto-leave-toggle";
    private static final String A_AUTOPLAY_TOGGLE    = "autoplay-toggle";
    private static final String A_AUTO_LEAVE_MINUTES = "auto-leave-minutes";
    private static final String A_COMMAND_CHANNEL    = "command-channel";
    private static final String A_PRIVATE_ROOM_CHANNEL = "private-room-channel";

    private static final String MSG_MENU_EXPIRED   = "settings.music_menu_expired";
    private static final String MSG_SETTINGS_SAVED = "general.settings_saved";
    private static final String MSG_MENU_CURRENT   = "settings.music_menu_current";
    private static final String LBL_STATUS = "settings.status_label";
    private static final String LBL_VALUE  = "settings.value_label";

    private final MusicCommandService owner;
    private final SettingsUiText uiText;
    private final Map<String, MusicMenuRequest> musicMenuRequests = new ConcurrentHashMap<>();

    public SettingsMusicMenuHandler(MusicCommandService owner) {
        this.owner = owner;
        this.uiText = new SettingsUiText(owner);
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        musicMenuRequests.entrySet().removeIf(e -> e.getValue() == null || cutoff.isAfter(e.getValue().expiresAt));
    }

    public void openMusicMenu(SlashCommandInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.replyEmbeds(musicMenuEmbed(event.getGuild(), lang, null).build())
                .addComponents(ActionRow.of(settingsMusicMenu(token, event.getGuild(), lang)))
                .setEphemeral(true)
                .queue();
    }

    public void openMusicMenu(StringSelectInteractionEvent event, String lang) {
        String token = registerMenuRequest(event.getUser().getIdLong(), event.getGuild().getIdLong());
        event.editMessageEmbeds(musicMenuEmbed(event.getGuild(), lang, null).build())
                .setComponents(ActionRow.of(settingsMusicMenu(token, event.getGuild(), lang)))
                .queue();
    }

    public void handleMusicMenuSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(SETTINGS_MUSIC_SELECT_PREFIX.length());
        MusicMenuRequest request = musicMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            musicMenuRequests.remove(token);
            event.reply(owner.i18nService().t(lang, MSG_MENU_EXPIRED)).setEphemeral(true).queue();
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
        long guildId = event.getGuild().getIdLong();
        String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        switch (action) {
            case A_AUTO_LEAVE_TOGGLE -> {
                boolean value = !owner.settingsService().getMusic(guildId).isAutoLeaveEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withMusic(s.getMusic().withAutoLeaveEnabled(value)));
                String changed = owner.i18nService().t(lang, MSG_SETTINGS_SAVED,
                        Map.of("key", owner.i18nService().t(lang, K_AUTO_LEAVE_ENABLED), OPTION_VALUE, owner.boolText(lang, value)));
                event.editMessageEmbeds(musicMenuEmbed(event.getGuild(), lang, changed).build())
                        .setComponents(ActionRow.of(settingsMusicMenu(token, event.getGuild(), lang)))
                        .queue();
            }
            case A_AUTOPLAY_TOGGLE -> {
                boolean value = !owner.settingsService().getMusic(guildId).isAutoplayEnabled();
                owner.settingsService().updateSettings(guildId, s -> s.withMusic(s.getMusic().withAutoplayEnabled(value)));
                if (!value) {
                    owner.musicService().clearAutoplayNotice(guildId);
                }
                String changed = owner.i18nService().t(lang, MSG_SETTINGS_SAVED,
                        Map.of("key", owner.i18nService().t(lang, K_AUTOPLAY_ENABLED), OPTION_VALUE, owner.boolText(lang, value)));
                event.editMessageEmbeds(musicMenuEmbed(event.getGuild(), lang, changed).build())
                        .setComponents(ActionRow.of(settingsMusicMenu(token, event.getGuild(), lang)))
                        .queue();
            }
            case A_AUTO_LEAVE_MINUTES -> {
                int currentMinutes = owner.settingsService().getMusic(guildId).getAutoLeaveMinutes();
                TextInput input = TextInput.create("minutes", TextInputStyle.SHORT)
                        .setRequired(true)
                        .setPlaceholder("1-60")
                        .setValue(String.valueOf(currentMinutes))
                        .build();
                Modal modal = Modal.create(SETTINGS_MUSIC_MODAL_PREFIX + token + ":" + A_AUTO_LEAVE_MINUTES,
                                owner.i18nService().t(lang, "settings.music_menu_minutes_title"))
                        .addComponents(Label.of(owner.i18nService().t(lang, "settings.music_menu_minutes_hint"), input))
                        .build();
                event.replyModal(modal).queue();
            }
            case A_COMMAND_CHANNEL, A_PRIVATE_ROOM_CHANNEL -> {
                String componentId = SETTINGS_MUSIC_CHANNEL_PREFIX + token + ":" + action;
                EntitySelectMenu.Builder channelBuilder = EntitySelectMenu
                        .create(componentId, EntitySelectMenu.SelectTarget.CHANNEL)
                        .setRequiredRange(1, 1)
                        .setPlaceholder(owner.i18nService().t(lang, "settings.music_menu_channel_placeholder"));
                if (A_COMMAND_CHANNEL.equals(action)) {
                    channelBuilder.setChannelTypes(ChannelType.TEXT);
                } else {
                    channelBuilder.setChannelTypes(ChannelType.VOICE, ChannelType.STAGE);
                }
                String key = musicTargetKey(action);
                String keyText = key == null ? action : owner.i18nService().t(lang, key);
                event.editMessageEmbeds(new EmbedBuilder()
                                .setColor(new Color(155, 89, 182))
                                .setTitle(owner.i18nService().t(lang, "settings.music_menu_pick_channel_title"))
                                .setDescription(owner.i18nService().t(lang, "settings.music_menu_pick_channel_desc", Map.of("target", keyText)))
                                .build())
                        .setComponents(ActionRow.of(channelBuilder.build()))
                        .queue();
            }
            default -> event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
        }
    }

    public void handleMusicMenuModal(ModalInteractionEvent event, String lang) {
        if (!owner.has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions",
                            Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String suffix = event.getModalId().substring(SETTINGS_MUSIC_MODAL_PREFIX.length());
        int idx = suffix.indexOf(':');
        if (idx <= 0 || idx >= suffix.length() - 1) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String token = suffix.substring(0, idx);
        String action = suffix.substring(idx + 1);
        MusicMenuRequest request = musicMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            musicMenuRequests.remove(token);
            event.reply(owner.i18nService().t(lang, MSG_MENU_EXPIRED)).setEphemeral(true).queue();
            return;
        }
        if (event.getGuild().getIdLong() != request.guildId || event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }
        if (!A_AUTO_LEAVE_MINUTES.equals(action)) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String text = Objects.requireNonNull(event.getValue("minutes")).getAsString().trim();
        int minutes;
        try {
            minutes = Integer.parseInt(text);
        } catch (Exception ignored) {
            event.reply(owner.i18nService().t(lang, "settings.music_menu_minutes_invalid")).setEphemeral(true).queue();
            return;
        }
        if (minutes < 1 || minutes > 60) {
            event.reply(owner.i18nService().t(lang, "settings.music_menu_minutes_invalid")).setEphemeral(true).queue();
            return;
        }
        owner.settingsService().updateSettings(event.getGuild().getIdLong(),
                s -> s.withMusic(s.getMusic().withAutoLeaveMinutes(minutes)));
        String changed = owner.i18nService().t(lang, MSG_SETTINGS_SAVED,
                Map.of("key", owner.i18nService().t(lang, K_AUTO_LEAVE_MINUTES), OPTION_VALUE, String.valueOf(minutes)));
        event.replyEmbeds(musicMenuEmbed(event.getGuild(), lang, changed).build())
                .addComponents(ActionRow.of(settingsMusicMenu(token, event.getGuild(), lang)))
                .setEphemeral(true)
                .queue();
    }

    public void handleMusicChannelSelect(EntitySelectInteractionEvent event, String lang) {
        String suffix = event.getComponentId().substring(SETTINGS_MUSIC_CHANNEL_PREFIX.length());
        int idx = suffix.indexOf(':');
        if (idx <= 0 || idx >= suffix.length() - 1) {
            event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
            return;
        }
        String token = suffix.substring(0, idx);
        String target = suffix.substring(idx + 1);
        MusicMenuRequest request = musicMenuRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            musicMenuRequests.remove(token);
            event.reply(owner.i18nService().t(lang, MSG_MENU_EXPIRED)).setEphemeral(true).queue();
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
        List<GuildChannel> channels = event.getMentions().getChannels();
        if (channels.isEmpty()) {
            event.reply(owner.i18nService().t(lang, "general.invalid_channel")).setEphemeral(true).queue();
            return;
        }
        GuildChannel selected = channels.get(0);
        long guildId = event.getGuild().getIdLong();
        String displayValue;
        switch (target) {
            case A_COMMAND_CHANNEL -> {
                if (selected.getType() != ChannelType.TEXT) {
                    event.reply(owner.i18nService().t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                    return;
                }
                owner.settingsService().updateSettings(guildId,
                        s -> s.withMusic(s.getMusic().withCommandChannelId(selected.getIdLong())));
                displayValue = "<#" + selected.getId() + ">";
            }
            case A_PRIVATE_ROOM_CHANNEL -> {
                if (selected.getType() != ChannelType.VOICE && selected.getType() != ChannelType.STAGE) {
                    event.reply(owner.i18nService().t(lang, "settings.validation_expected_voice_channel")).setEphemeral(true).queue();
                    return;
                }
                owner.settingsService().updateSettings(guildId, s -> s.withPrivateRoom(
                        s.getPrivateRoom()
                                .withTriggerVoiceChannelId(selected.getIdLong())
                                .withEnabled(true)
                ));
                displayValue = "<#" + selected.getId() + ">";
            }
            default -> {
                event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
                return;
            }
        }
        String key = musicTargetKey(target);
        String keyText = key == null ? target : owner.i18nService().t(lang, key);
        String changed = owner.i18nService().t(lang, MSG_SETTINGS_SAVED, Map.of("key", keyText, OPTION_VALUE, displayValue));
        if (A_PRIVATE_ROOM_CHANNEL.equals(target)) {
            changed = changed + "\n" + owner.i18nService().t(lang, "settings.private_room_auto_enabled_notice");
        }
        event.editMessageEmbeds(musicMenuEmbed(event.getGuild(), lang, changed).build())
                .setComponents(ActionRow.of(settingsMusicMenu(token, event.getGuild(), lang)))
                .queue();
    }

    private StringSelectMenu settingsMusicMenu(String token, Guild guild, String lang) {
        long guildId = guild.getIdLong();
        var music = owner.settingsService().getMusic(guildId);
        var room = owner.settingsService().getPrivateRoom(guildId);
        return StringSelectMenu.create(SETTINGS_MUSIC_SELECT_PREFIX + token)
                .setPlaceholder(owner.i18nService().t(lang, "settings.music_menu_placeholder"))
                .addOptions(
                        SelectOption.of(owner.i18nService().t(lang, K_AUTO_LEAVE_ENABLED), A_AUTO_LEAVE_TOGGLE)
                                .withDescription(owner.i18nService().t(lang, MSG_MENU_CURRENT,
                                        Map.of(OPTION_VALUE, owner.boolText(lang, music.isAutoLeaveEnabled())))),
                        SelectOption.of(owner.i18nService().t(lang, K_AUTO_LEAVE_MINUTES), A_AUTO_LEAVE_MINUTES)
                                .withDescription(owner.i18nService().t(lang, MSG_MENU_CURRENT,
                                        Map.of(OPTION_VALUE, String.valueOf(music.getAutoLeaveMinutes())))),
                        SelectOption.of(owner.i18nService().t(lang, K_AUTOPLAY_ENABLED), A_AUTOPLAY_TOGGLE)
                                .withDescription(owner.i18nService().t(lang, MSG_MENU_CURRENT,
                                        Map.of(OPTION_VALUE, owner.boolText(lang, music.isAutoplayEnabled())))),
                        SelectOption.of(owner.i18nService().t(lang, K_COMMAND_CHANNEL), A_COMMAND_CHANNEL)
                                .withDescription(owner.i18nService().t(lang, MSG_MENU_CURRENT,
                                        Map.of(OPTION_VALUE, uiText.limitText(uiText.formatTextChannel(guild, music.getCommandChannelId(), lang), 60)))),
                        SelectOption.of(owner.i18nService().t(lang, K_PRIVATE_ROOM_CHANNEL), A_PRIVATE_ROOM_CHANNEL)
                                .withDescription(owner.i18nService().t(lang, MSG_MENU_CURRENT,
                                        Map.of(OPTION_VALUE, uiText.limitText(formatVoiceChannel(guild, room.getTriggerVoiceChannelId(), lang), 60))))
                )
                .build();
    }

    private EmbedBuilder musicMenuEmbed(Guild guild, String lang, String changedText) {
        long guildId = guild.getIdLong();
        var music = owner.settingsService().getMusic(guildId);
        var room = owner.settingsService().getPrivateRoom(guildId);
        String body = String.join("\n\n",
                uiText.quotedSettingLine(lang, K_AUTO_LEAVE_ENABLED, LBL_STATUS,
                        owner.boolText(lang, music.isAutoLeaveEnabled())),
                uiText.quotedSettingLine(lang, K_AUTO_LEAVE_MINUTES, LBL_VALUE,
                        String.valueOf(music.getAutoLeaveMinutes())),
                uiText.quotedSettingLine(lang, K_AUTOPLAY_ENABLED, LBL_STATUS,
                        owner.boolText(lang, music.isAutoplayEnabled())),
                uiText.quotedSettingLine(lang, K_COMMAND_CHANNEL, LBL_VALUE,
                        uiText.formatTextChannel(guild, music.getCommandChannelId(), lang)),
                uiText.quotedSettingLine(lang, K_PRIVATE_ROOM_CHANNEL, LBL_VALUE,
                        formatVoiceChannel(guild, room.getTriggerVoiceChannelId(), lang))
        );
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(155, 89, 182))
                .setTitle("🎵 " + owner.i18nService().t(lang, "settings.music_menu_title"))
                .setDescription(owner.i18nService().t(lang, "settings.music_menu_desc"))
                .addField("🎼 " + owner.i18nService().t(lang, "settings.info_music"), body, false);
        if (changedText != null && !changedText.isBlank()) {
            eb.addField(owner.i18nService().t(lang, "settings.template_updated"), changedText, false);
        }
        return eb;
    }

    private String musicTargetKey(String target) {
        return switch (target) {
            case A_COMMAND_CHANNEL -> K_COMMAND_CHANNEL;
            case A_PRIVATE_ROOM_CHANNEL -> K_PRIVATE_ROOM_CHANNEL;
            default -> null;
        };
    }

    private String formatVoiceChannel(Guild guild, Long id, String lang) {
        if (id == null) {
            return owner.i18nService().t(lang, "settings.info_channels_none");
        }
        AudioChannel channel = guild.getVoiceChannelById(id);
        if (channel == null) {
            channel = guild.getStageChannelById(id);
        }
        return channel == null ? "#" + id : "<#" + id + "> (" + id + ")";
    }

    private String registerMenuRequest(long requestUserId, long guildId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        musicMenuRequests.put(token, new MusicMenuRequest(requestUserId, guildId, Instant.now().plusSeconds(120)));
        return token;
    }

    private static class MusicMenuRequest {
        private final long requestUserId;
        private final long guildId;
        private final Instant expiresAt;

        private MusicMenuRequest(long requestUserId, long guildId, Instant expiresAt) {
            this.requestUserId = requestUserId;
            this.guildId = guildId;
            this.expiresAt = expiresAt;
        }
    }
}
