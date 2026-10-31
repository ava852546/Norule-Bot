package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.domain.music.MusicPanelMode;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves current Discord channel access; never provisions channels or changes settings. */
public final class MusicPanelModeResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicPanelModeResolver.class);
    private final GuildSettingsService settings;
    private final Map<Long, Selection> selections = new ConcurrentHashMap<>();

    public record Selection(MusicPanelMode configured, MusicPanelMode effective,
                            Long channelId, TextChannel channel, String reason) {
        public boolean modern() { return effective == MusicPanelMode.MODERN; }
        public boolean fallback() { return configured == MusicPanelMode.MODERN && !modern(); }
    }

    public MusicPanelModeResolver(GuildSettingsService settings) {
        this.settings = settings;
    }

    public Selection resolve(Guild guild) {
        var music = settings.getMusic(guild.getIdLong());
        Long channelId = music.getCommandChannelId();
        boolean legacy = music.getPanelMode() == MusicPanelMode.LEGACY;
        TextChannel channel = legacy || channelId == null ? null : guild.getTextChannelById(channelId);
        String reason = legacy ? "LEGACY_MODE" : channelId == null ? "NOT_CONFIGURED"
                : channel == null ? "UNKNOWN_CHANNEL" : usable(channel) ? "AVAILABLE" : "MISSING_ACCESS";
        boolean available = "AVAILABLE".equals(reason);
        Selection current = new Selection(music.getPanelMode(), music.getPanelMode().effective(available),
                channelId, available ? channel : null, reason);
        selections.compute(guild.getIdLong(), (id, previous) -> {
            if (previous != null && previous.configured() != current.configured()) {
                log("MUSIC_PANEL_MODE_CHANGED", id, channelId, 0, current, "SETTINGS_UPDATED");
            }
            boolean changed = previous == null || previous.configured() != current.configured()
                    || previous.effective() != current.effective()
                    || !Objects.equals(previous.channelId(), channelId) || !previous.reason().equals(reason);
            if (current.fallback() && changed) {
                log("MUSIC_PANEL_CHANNEL_INVALID", id, channelId, 0, current, reason);
                log("MUSIC_PANEL_FALLBACK_LEGACY", id, channelId, 0, current, reason);
            } else if (current.modern() && previous != null && previous.fallback()) {
                log("MUSIC_PANEL_MODERN_RESTORED", id, channelId, 0, current, "CHANNEL_AVAILABLE");
            }
            return current;
        });
        return current;
    }

    public static boolean usable(TextChannel channel) {
        return channel != null && channel.getGuild().getSelfMember().hasPermission(channel,
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
    }

    public void log(String event, Guild guild, long channelId, long messageId, String reason) {
        log(event, guild.getIdLong(), channelId, messageId, resolve(guild), reason);
    }

    private static void log(String event, long guildId, Long channelId, long messageId,
                            Selection selection, String reason) {
        LOGGER.info("event={} guildId={} channelId={} messageId={} configuredMode={} effectiveMode={} reason={}",
                event, guildId, channelId, messageId, selection.configured(), selection.effective(), reason);
    }

    public static String failureReason(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) {
            if (failure instanceof ErrorResponseException response) {
                return response.getErrorResponse().name();
            }
            failure = failure.getCause();
        }
        return failure instanceof ErrorResponseException response
                ? response.getErrorResponse().name() : failure.getClass().getSimpleName();
    }

    public void forget(long guildId) { selections.remove(guildId); }
}
