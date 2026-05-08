package com.norule.musicbot.discord.bot.gateway.command.privateroom;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.CommandOptions;
import com.norule.musicbot.discord.bot.gateway.component.ComponentIds;
import com.norule.musicbot.discord.bot.gateway.listener.PrivateRoomListener;
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
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PrivateRoomSettingsCommandHandler {
    private static final String ROOM_SETTINGS_MENU_PREFIX = ComponentIds.ROOM_SETTINGS_MENU_PREFIX;
    private static final String ROOM_LIMIT_MODAL_PREFIX   = ComponentIds.ROOM_LIMIT_MODAL_PREFIX;
    private static final String ROOM_RENAME_MODAL_PREFIX  = ComponentIds.ROOM_RENAME_MODAL_PREFIX;
    private static final String ROOM_TRANSFER_SELECT_PREFIX = ComponentIds.ROOM_TRANSFER_SELECT_PREFIX;

    private static final String OPTION_CHANNEL            = CommandOptions.CHANNEL;
    private static final String KEY_DELETE_ONLY_REQUESTER = "delete.only_requester";
    private static final String KEY_UNKNOWN_COMMAND       = "general.unknown_command";

    private final MusicCommandService owner;
    private final ConcurrentHashMap<String, RoomSettingsRequest> roomSettingRequests = new ConcurrentHashMap<>();

    public PrivateRoomSettingsCommandHandler(MusicCommandService owner) {
        this.owner = owner;
    }

    public void cleanupExpiredRequests(Instant now) {
        Instant cutoff = now == null ? Instant.now() : now;
        roomSettingRequests.entrySet().removeIf(e -> e.getValue() == null || cutoff.isAfter(e.getValue().expiresAt));
    }

    public void handlePrivateRoomSettingsCommand(SlashCommandInteractionEvent event, String lang) {
        Member member = event.getMember();
        if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
            event.reply(owner.i18nService().t(lang, "room_settings.must_join_private_room"))
                    .setEphemeral(true).queue();
            return;
        }
        AudioChannel current = member.getVoiceState().getChannel();
        if (!(current instanceof VoiceChannel voiceChannel)
                || !isUserOwnedPrivateRoom(event.getGuild(), voiceChannel, member.getIdLong())) {
            event.reply(owner.i18nService().t(lang, "room_settings.must_join_private_room"))
                    .setEphemeral(true).queue();
            return;
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        roomSettingRequests.put(token, new RoomSettingsRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                voiceChannel.getIdLong(),
                Instant.now().plusSeconds(120)
        ));
        event.replyEmbeds(privateRoomSettingsEmbed(voiceChannel, lang).build())
                .addComponents(ActionRow.of(privateRoomSettingsMenu(token, lang)))
                .setEphemeral(true)
                .queue();
    }

    public void handleRoomSettingsSelect(StringSelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(ROOM_SETTINGS_MENU_PREFIX.length());
        RoomSettingsRequest request = roomSettingRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            roomSettingRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "room_settings.expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }

        VoiceChannel room = event.getGuild().getVoiceChannelById(request.roomChannelId);
        if (room == null || !isUserOwnedPrivateRoom(event.getGuild(), room, request.requestUserId)) {
            roomSettingRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "room_settings.room_not_found")).setEphemeral(true).queue();
            return;
        }

        String action = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        switch (action) {
            case "lock" -> {
                String missing = formatMissingPermissions(event.getGuild().getSelfMember(), room,
                        Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS);
                if (!"-".equals(missing)) {
                    event.reply(owner.i18nService().t(lang, "general.missing_permissions",
                                    Map.of("permissions", missing)))
                            .setEphemeral(true).queue();
                    return;
                }
                boolean currentlyLocked = isRoomLocked(room);
                var overrideAction = room.upsertPermissionOverride(event.getGuild().getPublicRole());
                if (currentlyLocked) {
                    overrideAction.clear(Permission.VOICE_CONNECT, Permission.VIEW_CHANNEL);
                    overrideAction.grant(Permission.VOICE_CONNECT, Permission.VIEW_CHANNEL);
                } else {
                    overrideAction.deny(Permission.VOICE_CONNECT, Permission.VIEW_CHANNEL);
                }
                overrideAction.queue(
                        success -> event.editMessageEmbeds(privateRoomSettingsEmbed(room, lang).build())
                                .setComponents(ActionRow.of(privateRoomSettingsMenu(token, lang)))
                                .queue(),
                        error -> event.reply(owner.i18nService().t(lang, "room_settings.action_failed"))
                                .setEphemeral(true).queue());
            }
            case "limit"    -> openRoomLimitModal(event, token, room, lang);
            case "rename"   -> openRoomRenameModal(event, token, room, lang);
            case "transfer" -> openRoomTransferMenu(event, token, room, lang);
            default -> event.reply(owner.i18nService().t(lang, KEY_UNKNOWN_COMMAND)).setEphemeral(true).queue();
        }
    }

    public void handleRoomSettingsModal(ModalInteractionEvent event) {
        String lang    = owner.lang(event.getGuild().getIdLong());
        String modalId = event.getModalId();
        boolean isLimit = modalId.startsWith(ROOM_LIMIT_MODAL_PREFIX);
        String token = modalId.substring((isLimit ? ROOM_LIMIT_MODAL_PREFIX : ROOM_RENAME_MODAL_PREFIX).length());
        RoomSettingsRequest request = roomSettingRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            roomSettingRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "room_settings.expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }

        VoiceChannel room = event.getGuild().getVoiceChannelById(request.roomChannelId);
        if (room == null || !isUserOwnedPrivateRoom(event.getGuild(), room, request.requestUserId)) {
            roomSettingRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "room_settings.room_not_found")).setEphemeral(true).queue();
            return;
        }

        String missing = formatMissingPermissions(event.getGuild().getSelfMember(), room, Permission.MANAGE_CHANNEL);
        if (!"-".equals(missing)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions", Map.of("permissions", missing)))
                    .setEphemeral(true).queue();
            return;
        }

        if (isLimit) {
            String raw = event.getValue("limit") == null ? "" : event.getValue("limit").getAsString().trim();
            int limit;
            if (raw.isBlank()) {
                limit = 0;
            } else {
                try {
                    limit = Integer.parseInt(raw);
                } catch (NumberFormatException e) {
                    event.reply(owner.i18nService().t(lang, "room_settings.limit_invalid"))
                            .setEphemeral(true).queue();
                    return;
                }
                if (limit < 1 || limit > 99) {
                    event.reply(owner.i18nService().t(lang, "room_settings.limit_invalid"))
                            .setEphemeral(true).queue();
                    return;
                }
            }
            room.getManager().setUserLimit(limit).queue(
                    success -> {
                        roomSettingRequests.put(token, new RoomSettingsRequest(
                                request.requestUserId, request.guildId, request.roomChannelId,
                                Instant.now().plusSeconds(120)
                        ));
                        event.replyEmbeds(privateRoomSettingsEmbed(room, lang).build())
                                .addComponents(ActionRow.of(privateRoomSettingsMenu(token, lang)))
                                .setEphemeral(true).queue();
                    },
                    error -> event.reply(owner.i18nService().t(lang, "room_settings.action_failed"))
                            .setEphemeral(true).queue()
            );
            return;
        }

        String name = event.getValue("name") == null ? "" : event.getValue("name").getAsString().trim();
        if (name.isBlank() || name.length() > 10) {
            event.reply(owner.i18nService().t(lang, "room_settings.rename_invalid")).setEphemeral(true).queue();
            return;
        }
        room.getManager().setName(name).queue(
                success -> {
                    roomSettingRequests.put(token, new RoomSettingsRequest(
                            request.requestUserId, request.guildId, request.roomChannelId,
                            Instant.now().plusSeconds(120)
                    ));
                    event.replyEmbeds(privateRoomSettingsEmbed(room, lang).build())
                            .addComponents(ActionRow.of(privateRoomSettingsMenu(token, lang)))
                            .setEphemeral(true).queue();
                },
                error -> event.reply(owner.i18nService().t(lang, "room_settings.action_failed"))
                        .setEphemeral(true).queue()
        );
    }

    public void handleRoomTransferSelect(EntitySelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(ROOM_TRANSFER_SELECT_PREFIX.length());
        RoomSettingsRequest request = roomSettingRequests.get(token);
        if (request == null || Instant.now().isAfter(request.expiresAt)) {
            roomSettingRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "room_settings.expired")).setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != request.requestUserId) {
            event.reply(owner.i18nService().t(lang, KEY_DELETE_ONLY_REQUESTER)).setEphemeral(true).queue();
            return;
        }

        VoiceChannel room = event.getGuild().getVoiceChannelById(request.roomChannelId);
        if (room == null || !isUserOwnedPrivateRoom(event.getGuild(), room, request.requestUserId)) {
            roomSettingRequests.remove(token);
            event.reply(owner.i18nService().t(lang, "room_settings.room_not_found")).setEphemeral(true).queue();
            return;
        }

        String missing = formatMissingPermissions(event.getGuild().getSelfMember(), room,
                Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS);
        if (!"-".equals(missing)) {
            event.reply(owner.i18nService().t(lang, "general.missing_permissions", Map.of("permissions", missing)))
                    .setEphemeral(true).queue();
            return;
        }

        List<Member> members = event.getMentions().getMembers();
        Member target = members.isEmpty() ? null : members.get(0);
        if (target == null
                || target.getUser().isBot()
                || target.getIdLong() == request.requestUserId
                || target.getVoiceState() == null
                || target.getVoiceState().getChannel() == null
                || target.getVoiceState().getChannel().getIdLong() != room.getIdLong()) {
            event.reply(owner.i18nService().t(lang, "room_settings.transfer_invalid"))
                    .setEphemeral(true).queue();
            return;
        }

        long oldOwnerId = request.requestUserId;
        room.upsertPermissionOverride(target)
                .grant(Permission.getPermissions(PrivateRoomListener.getRoomOwnerPermissionRaw()))
                .queue(success -> removeOldRoomOwnerOverride(room, oldOwnerId, () -> {
                            PrivateRoomListener.setRoomOwner(
                                    event.getGuild().getIdLong(), room.getIdLong(), target.getIdLong());
                            roomSettingRequests.remove(token);
                            event.editMessageEmbeds(new EmbedBuilder()
                                            .setColor(new Color(46, 204, 113))
                                            .setTitle(owner.i18nService().t(lang, "room_settings.title"))
                                            .setDescription(owner.i18nService().t(lang, "room_settings.transfer_success",
                                                    Map.of("user", target.getAsMention())))
                                            .build())
                                    .setComponents(List.of())
                                    .queue();
                        },
                        () -> event.reply(owner.i18nService().t(lang, "room_settings.action_failed"))
                                .setEphemeral(true).queue()),
                error -> event.reply(owner.i18nService().t(lang, "room_settings.action_failed"))
                        .setEphemeral(true).queue());
    }

    private StringSelectMenu privateRoomSettingsMenu(String token, String lang) {
        return StringSelectMenu.create(ROOM_SETTINGS_MENU_PREFIX + token)
                .setPlaceholder(owner.i18nService().t(lang, "room_settings.select_placeholder"))
                .addOptions(
                        SelectOption.of(owner.i18nService().t(lang, "room_settings.option_lock"),     "lock"),
                        SelectOption.of(owner.i18nService().t(lang, "room_settings.option_limit"),    "limit"),
                        SelectOption.of(owner.i18nService().t(lang, "room_settings.option_rename"),   "rename"),
                        SelectOption.of(owner.i18nService().t(lang, "room_settings.option_transfer"), "transfer")
                )
                .build();
    }

    private EmbedBuilder privateRoomSettingsEmbed(VoiceChannel room, String lang) {
        boolean locked  = isRoomLocked(room);
        Long ownerId    = PrivateRoomListener.getRoomOwnerId(room.getGuild().getIdLong(), room.getIdLong());
        Member roomOwner = ownerId == null ? null : room.getGuild().getMemberById(ownerId);
        String ownerText = roomOwner == null
                ? owner.i18nService().t(lang, "room_settings.owner_unknown")
                : roomOwner.getAsMention();
        return new EmbedBuilder()
                .setColor(new Color(155, 89, 182))
                .setTitle(owner.i18nService().t(lang, "room_settings.title"))
                .setDescription(owner.i18nService().t(lang, "room_settings.desc"))
                .addField(owner.i18nService().t(lang, "room_settings.field_channel"), room.getAsMention(), true)
                .addField(owner.i18nService().t(lang, "room_settings.field_name"), room.getName(), true)
                .addField(owner.i18nService().t(lang, "room_settings.field_limit"),
                        room.getUserLimit() <= 0
                                ? owner.i18nService().t(lang, "room_settings.unlimited")
                                : String.valueOf(room.getUserLimit()),
                        true)
                .addField(owner.i18nService().t(lang, "room_settings.field_lock"),
                        locked ? owner.i18nService().t(lang, "settings.info_bool_on")
                               : owner.i18nService().t(lang, "settings.info_bool_off"),
                        true)
                .addField(owner.i18nService().t(lang, "room_settings.field_owner"), ownerText, true);
    }

    private void openRoomTransferMenu(StringSelectInteractionEvent event, String token,
                                      VoiceChannel room, String lang) {
        EntitySelectMenu memberMenu = EntitySelectMenu
                .create(ROOM_TRANSFER_SELECT_PREFIX + token, EntitySelectMenu.SelectTarget.USER)
                .setPlaceholder(owner.i18nService().t(lang, "room_settings.transfer_placeholder"))
                .setRequiredRange(1, 1)
                .build();
        event.editMessageEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle(owner.i18nService().t(lang, "room_settings.transfer_title"))
                        .setDescription(owner.i18nService().t(lang, "room_settings.transfer_desc",
                                Map.of(OPTION_CHANNEL, room.getAsMention())))
                        .build())
                .setComponents(ActionRow.of(memberMenu))
                .queue();
    }

    private void openRoomLimitModal(StringSelectInteractionEvent event, String token,
                                    VoiceChannel room, String lang) {
        TextInput input = TextInput.create("limit", TextInputStyle.SHORT)
                .setPlaceholder(owner.i18nService().t(lang, "room_settings.limit_placeholder"))
                .setRequired(false)
                .setMaxLength(2)
                .build();
        Modal modal = Modal.create(ROOM_LIMIT_MODAL_PREFIX + token,
                        owner.i18nService().t(lang, "room_settings.limit_title"))
                .addComponents(Label.of(owner.i18nService().t(lang, "room_settings.limit_label"), input))
                .build();
        event.replyModal(modal).queue();
    }

    private void openRoomRenameModal(StringSelectInteractionEvent event, String token,
                                     VoiceChannel room, String lang) {
        TextInput input = TextInput.create("name", TextInputStyle.SHORT)
                .setPlaceholder(owner.i18nService().t(lang, "room_settings.rename_placeholder",
                        Map.of("name", room.getName())))
                .setRequired(true)
                .setMinLength(1)
                .setMaxLength(10)
                .build();
        Modal modal = Modal.create(ROOM_RENAME_MODAL_PREFIX + token,
                        owner.i18nService().t(lang, "room_settings.rename_title"))
                .addComponents(Label.of(owner.i18nService().t(lang, "room_settings.rename_label"), input))
                .build();
        event.replyModal(modal).queue();
    }

    private void removeOldRoomOwnerOverride(VoiceChannel room, long oldOwnerId,
                                            Runnable onSuccess, Runnable onError) {
        var oldOverride = room.getMemberPermissionOverrides().stream()
                .filter(o -> o.getIdLong() == oldOwnerId)
                .findFirst()
                .orElse(null);
        if (oldOverride == null) {
            onSuccess.run();
            return;
        }
        oldOverride.delete().queue(success -> onSuccess.run(), error -> onError.run());
    }

    private boolean isRoomLocked(VoiceChannel room) {
        var override = room.getPermissionOverride(room.getGuild().getPublicRole());
        return override != null && (override.getDenied().contains(Permission.VOICE_CONNECT)
                || override.getDenied().contains(Permission.VIEW_CHANNEL));
    }

    private boolean isUserOwnedPrivateRoom(Guild guild, VoiceChannel room, long userId) {
        if (PrivateRoomListener.isManagedPrivateRoom(guild.getIdLong(), room.getIdLong())
                && PrivateRoomListener.isRoomOwner(guild.getIdLong(), room.getIdLong(), userId)) {
            return true;
        }
        var override = room.getMemberPermissionOverrides().stream()
                .filter(o -> o.getIdLong() == userId)
                .findFirst()
                .orElse(null);
        if (override == null) {
            return false;
        }
        var allowed = override.getAllowed();
        return allowed.contains(Permission.MANAGE_CHANNEL)
                && allowed.contains(Permission.VOICE_MOVE_OTHERS)
                && allowed.contains(Permission.VOICE_MUTE_OTHERS);
    }

    private static String formatMissingPermissions(Member member, GuildChannel channel,
                                                    Permission... permissions) {
        EnumSet<Permission> missing = EnumSet.noneOf(Permission.class);
        for (Permission p : permissions) {
            if (!member.hasPermission(channel, p)) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            return "-";
        }
        List<String> names = new ArrayList<>();
        for (Permission p : missing) {
            names.add(p.getName());
        }
        return String.join(", ", names);
    }

    private static final class RoomSettingsRequest {
        final long requestUserId;
        final long guildId;
        final long roomChannelId;
        final Instant expiresAt;

        RoomSettingsRequest(long requestUserId, long guildId, long roomChannelId, Instant expiresAt) {
            this.requestUserId  = requestUserId;
            this.guildId        = guildId;
            this.roomChannelId  = roomChannelId;
            this.expiresAt      = expiresAt;
        }
    }
}
