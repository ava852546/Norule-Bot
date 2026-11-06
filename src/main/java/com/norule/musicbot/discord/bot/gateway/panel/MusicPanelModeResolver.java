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

    /** Cache-only evaluation without logging transitions or updating the resolver state. */
    public Selection inspect(Guild guild) {
        return selection(guild, settings.readSettings(guild.getIdLong()).getMusic());
    }

    private Selection selection(Guild guild, com.norule.musicbot.config.BotConfig.Music music) {
        Long id = music.getCommandChannelId();
        boolean legacy = music.getPanelMode() == MusicPanelMode.LEGACY;
        TextChannel channel = legacy || id == null ? null : guild.getTextChannelById(id);
        String reason = legacy ? "LEGACY_MODE" : id == null ? "NOT_CONFIGURED"
                : channel == null ? "UNKNOWN_CHANNEL" : usable(channel) ? "AVAILABLE" : "MISSING_ACCESS";
        return new Selection(music.getPanelMode(), music.getPanelMode().effective("AVAILABLE".equals(reason)), id,
                "AVAILABLE".equals(reason) ? channel : null, reason);
    }

    public Selection resolve(Guild guild) {
        Selection current = selection(guild, settings.getMusic(guild.getIdLong()));
        Long channelId = current.channelId();
        String reason = current.reason();
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
