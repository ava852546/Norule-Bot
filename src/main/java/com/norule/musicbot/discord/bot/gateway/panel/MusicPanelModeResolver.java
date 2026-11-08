package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.domain.music.MusicPanelMode;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
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

    // channelId is the nullable configured ID, not the resolved playback target.
    // null means NOT_CONFIGURED (or an unset ID in explicit LEGACY); missing/access-denied
    // configured channels retain their ID and are distinguished by reason and effective mode.
    public record Selection(MusicPanelMode configured, MusicPanelMode effective,
                            Long channelId, GuildMessageChannel channel, String reason) {
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
        var target = legacy || id == null ? null : MusicPanelTargetResolver.resolve(guild, id, null);
        String reason = legacy ? "LEGACY_MODE" : id == null ? "NOT_CONFIGURED"
                : target.reason();
        return new Selection(music.getPanelMode(), music.getPanelMode().effective("AVAILABLE".equals(reason)), id,
                "AVAILABLE".equals(reason) ? target.channel() : null, reason);
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

    public static boolean usable(GuildMessageChannel channel) {
        return "AVAILABLE".equals(MusicPanelTargetResolver.unavailableReason(channel));
    }

    static Permission sendPermission(GuildMessageChannel channel) {
        return channel instanceof ThreadChannel ? Permission.MESSAGE_SEND_IN_THREADS : Permission.MESSAGE_SEND;
    }

    public void log(String event, Guild guild, long channelId, long messageId, String reason) {
        log(event, guild.getIdLong(), channelId, messageId, resolve(guild), reason);
    }

    private static void log(String event, long guildId, Long channelId, long messageId,
                            Selection selection, String reason) {
        var level = event.endsWith("_FAILED") ? org.slf4j.event.Level.WARN
                : "MUSIC_PANEL_LEGACY_RECREATED".equals(event) ? org.slf4j.event.Level.DEBUG : org.slf4j.event.Level.INFO;
        LOGGER.atLevel(level).log("event={} guildId={} channelId={} messageId={} configuredMode={} effectiveMode={} reason={}",
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
