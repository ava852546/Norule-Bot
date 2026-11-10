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
    private final Map<Long, Probe> probes = new ConcurrentHashMap<>();
    private final java.util.function.BiFunction<Guild, Long, java.util.concurrent.CompletableFuture<GuildMessageChannel>> retrieve;
    private final java.util.function.LongConsumer recovered;
    private final java.util.function.LongSupplier nanoTime;
    private volatile boolean closed;
    private static final long PROBE_CACHE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    private static final long FAILURE_CACHE_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
    private static final class Probe {
        final long channelId;
        volatile long expiresAt = Long.MAX_VALUE;
        volatile GuildMessageChannel channel;
        volatile String reason = "CHANNEL_VERIFICATION_PENDING";
        Probe(long channelId) { this.channelId = channelId; }
    }

    // channelId is the nullable configured ID, not the resolved playback target.
    // null means NOT_CONFIGURED (or an unset ID in explicit LEGACY); missing/access-denied
    // configured channels retain their ID and are distinguished by reason and effective mode.
    public record Selection(MusicPanelMode configured, MusicPanelMode effective,
                            Long channelId, GuildMessageChannel channel, String reason) {
        public boolean modern() { return effective == MusicPanelMode.MODERN; }
        public boolean fallback() { return configured == MusicPanelMode.MODERN && !modern(); }
    }

    public MusicPanelModeResolver(GuildSettingsService settings) {
        this(settings, ignored -> {});
    }

    public MusicPanelModeResolver(GuildSettingsService settings, java.util.function.LongConsumer recovered) {
        this(settings, recovered, MusicPanelTargetResolver::retrieve, System::nanoTime);
    }

    MusicPanelModeResolver(GuildSettingsService settings, java.util.function.LongConsumer recovered,
            java.util.function.BiFunction<Guild, Long, java.util.concurrent.CompletableFuture<GuildMessageChannel>> retrieve,
            java.util.function.LongSupplier nanoTime) {
        this.settings = settings;
        this.recovered = recovered;
        this.retrieve = retrieve;
        this.nanoTime = nanoTime;
    }

    /** Read-only local evidence, including a recent REST proof; never initiates HTTP or transitions. */
    public synchronized Selection inspect(Guild guild) {
        Selection current = selection(guild, settings.readSettings(guild.getIdLong()).getMusic());
        return "CHANNEL_NOT_CACHED".equals(current.reason())
                ? verifiedSelection(guild, current, probes.get(guild.getIdLong())) : current;
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
        verifyMissing(guild, selection(guild, settings.getMusic(guild.getIdLong())));
        synchronized (this) {
            // A synchronous or concurrent completion may have replaced the initial evidence.
            Selection current = inspect(guild);
            return closed ? current : recordSelection(guild, current);
        }
    }

    /** Progress updates consume local evidence without starting a channel GET. */
    public synchronized Selection resolveLocal(Guild guild) {
        return recordSelection(guild, inspect(guild));
    }

    /** Explicit recovery bypasses completed proof TTL, retaining any in-flight probe. */
    public synchronized void revalidate(long guildId) {
        Probe probe = probes.get(guildId);
        if (probe != null && probe.expiresAt != Long.MAX_VALUE) probe.expiresAt = Long.MIN_VALUE;
    }

    /** Invalidate identity even for A -> B -> A configuration changes. */
    public synchronized void settingsChanged(long guildId) {
        probes.remove(guildId);
        selections.remove(guildId);
    }

    private Selection verifyMissing(Guild guild, Selection current) {
        if (closed) return current;
        if (!"CHANNEL_NOT_CACHED".equals(current.reason())) {
            // Gateway evidence and explicit configuration supersede an older REST proof.
            probes.remove(guild.getIdLong());
            return current;
        }
        long guildId = guild.getIdLong();
        Probe probe = probes.get(guildId);
        if (probe == null || probe.channelId != current.channelId() || nanoTime.getAsLong() >= probe.expiresAt) {
            Probe fresh = new Probe(current.channelId());
            Selection previous = selections.get(guildId);
            if (previous != null && previous.fallback() && previous.configured() == current.configured()
                    && Objects.equals(previous.channelId(), current.channelId())) fresh.reason = previous.reason();
            boolean installed = probe == null ? probes.putIfAbsent(guildId, fresh) == null
                    : probes.replace(guildId, probe, fresh);
            if (installed) {
                probe = fresh;
                try {
                    retrieve.apply(guild, fresh.channelId).whenComplete((channel, failure) -> {
                        boolean notifyRecovery;
                        synchronized (MusicPanelModeResolver.this) {
                            if (closed || probes.get(guildId) != fresh) return;
                            var music = settings.readSettings(guildId).getMusic();
                            if (music.getPanelMode() != MusicPanelMode.MODERN
                                    || !Objects.equals(music.getCommandChannelId(), fresh.channelId)) {
                                probes.remove(guildId, fresh);
                                return;
                            }
                            // Expire even if the guild/channel detaches while validating its permissions.
                            fresh.expiresAt = nanoTime.getAsLong() + PROBE_CACHE_NANOS;
                            try {
                                fresh.reason = failure == null ? channel == null ? "UNSUPPORTED_CHANNEL_TYPE"
                                        : MusicPanelTargetResolver.unavailableReason(channel) : failureReason(failure);
                                fresh.channel = channel;
                            } catch (RuntimeException unavailable) {
                                fresh.reason = failureReason(unavailable);
                            }
                            fresh.expiresAt = nanoTime.getAsLong() + ("AVAILABLE".equals(fresh.reason)
                                    ? PROBE_CACHE_NANOS : FAILURE_CACHE_NANOS);
                            Selection before = selections.get(guildId);
                            Selection verified = verifiedSelection(guild, current, fresh);
                            recordSelection(guild, verified);
                            notifyRecovery = verified.modern() && (before == null || !before.modern());
                        }
                        // Coordinator delivery can read mode evidence under its own flight lock.
                        // Never acquire that lock while holding the resolver monitor.
                        if (notifyRecovery) recovered.accept(guildId);
                    });
                } catch (RuntimeException failure) {
                    fresh.reason = failureReason(failure);
                    fresh.expiresAt = nanoTime.getAsLong() + FAILURE_CACHE_NANOS;
                }
            } else probe = probes.get(guildId);
        }
        return verifiedSelection(guild, current, probe);
    }

    private Selection verifiedSelection(Guild guild, Selection current, Probe probe) {
        // Expiration permits a new probe; it does not erase confirmed failure evidence.
        if (probe == null || probe.channelId != current.channelId()) return current;
        var target = MusicPanelTargetResolver.resolve(guild, current.channelId(), probe.channel);
        String reason = target.channel() == null
                ? "AVAILABLE".equals(probe.reason) ? "CHANNEL_VERIFICATION_PENDING" : probe.reason : target.reason();
        return new Selection(current.configured(), current.configured().effective("AVAILABLE".equals(reason)),
                current.channelId(), "AVAILABLE".equals(reason) ? target.channel() : null, reason);
    }

    private Selection recordSelection(Guild guild, Selection current) {
        // Pending evidence must not reset the last confirmed transition.
        if ("CHANNEL_VERIFICATION_PENDING".equals(current.reason()) || "CHANNEL_NOT_CACHED".equals(current.reason())) return current;
        selections.compute(guild.getIdLong(), (id, previous) -> {
            boolean configurationChanged = previous != null && (previous.configured() != current.configured()
                    || !Objects.equals(previous.channelId(), current.channelId()));
            if (configurationChanged) log("MUSIC_PANEL_MODE_CHANGED", id, current.channelId(), 0, current, "SETTINGS_UPDATED");
            boolean changed = previous == null || configurationChanged
                    || previous.effective() != current.effective() || !previous.reason().equals(current.reason());
            if (current.fallback() && changed) {
                log(previous != null && previous.fallback() && !configurationChanged
                        ? "MUSIC_PANEL_CHANNEL_VERIFICATION_FAILED" : "FALLBACK_LEGACY",
                        id, current.channelId(), 0, current, current.reason());
            } else if (current.modern() && previous != null && previous.fallback()) {
                log("MODERN_RECOVERED", id, current.channelId(), 0, current, "CHANNEL_AVAILABLE");
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
                : "NOT_CONFIGURED".equals(reason) || "CHANNEL_VERIFICATION_PENDING".equals(reason)
                        || "MUSIC_PANEL_LEGACY_RECREATED".equals(event) ? org.slf4j.event.Level.DEBUG
                : "FALLBACK_LEGACY".equals(event) && !expectedUnavailable(reason)
                        ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.INFO;
        LOGGER.atLevel(level).log("event={} guildId={} channelId={} messageId={} configuredMode={} effectiveMode={} reason={}",
                event, guildId, channelId, messageId, selection.configured(), selection.effective(), reason);
    }

    private static boolean expectedUnavailable(String reason) {
        return switch (reason) {
            case "UNKNOWN_CHANNEL", "MISSING_ACCESS", "MISSING_PERMISSIONS", "MISSING_VIEW_PERMISSION",
                    "MISSING_SEND_PERMISSION", "MISSING_EMBED_PERMISSION", "UNSUPPORTED_CHANNEL_TYPE",
                    "THREAD_LOCKED", "THREAD_ARCHIVED", "CHANNEL_NOT_WRITABLE", "NOT_CONFIGURED" -> true;
            default -> false;
        };
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

    public synchronized void forget(long guildId) { selections.remove(guildId); probes.remove(guildId); }

    public synchronized void close() { closed = true; probes.clear(); selections.clear(); }
}
