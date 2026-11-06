package com.norule.musicbot.discord.bot.gateway.command.meta;

import com.norule.musicbot.i18n.I18nService;
import com.norule.musicbot.discord.bot.gateway.command.CommandNames;
import net.dv8tion.jda.api.JDA;
import java.util.function.Supplier;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelRefreshService;
import com.norule.musicbot.discord.bot.ops.music.MusicDiagnosticsOps;
import com.norule.musicbot.domain.music.MusicDiagnostics;
import com.norule.musicbot.service.music.MusicDiagnosticsHistoryService;
import com.norule.musicbot.storage.sqlite.MusicDiagnosticsSqliteRepository;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Read-only presentation, invoked after the existing Developer whitelist check. */
public final class DevMusicInfoCommandHandler implements AutoCloseable {
    private final Supplier<JDA> jda;
    private final Supplier<I18nService> i18n;
    private final Supplier<String> defaultLanguage;
    private final LongFunction<String> language;
    private final LongPredicate autoplay;
    private final MusicPanelRefreshService panels;
    private final MusicDiagnosticsHistoryService history;
    private final MusicDiagnosticsOps diagnostics;

    public DevMusicInfoCommandHandler(Supplier<JDA> jda, Supplier<I18nService> i18n, Supplier<String> defaultLanguage,
                                     LongFunction<String> language, LongPredicate autoplay, MusicPanelRefreshService panels,
                                     MusicDiagnosticsHistoryService history, MusicDiagnosticsOps diagnostics) {
        this.jda = jda;
        this.i18n = i18n;
        this.defaultLanguage = defaultLanguage;
        this.language = language;
        this.autoplay = autoplay;
        this.panels = panels;
        this.history = history;
        this.diagnostics = diagnostics;
    }

    public void initialize(MusicDiagnosticsSqliteRepository repository) { history.initialize(repository); }
    public void observeRateLimit(String method, String path) { panels.observeRateLimit(method, path); }
    @Override public void close() { history.close(); }

    public CompletableFuture<EmbedBuilder> execute(String argument) {
        String lang = defaultLanguage.get();
        try {
            if (argument == null || argument.isBlank()) return reply(error(lang, "usage", "Usage: &dev " + CommandNames.CMD_DEV_MUSICINFO + " <serverID>"));
            if (!argument.matches("[0-9]{1,20}")) return reply(error(lang, "invalid_id", "Invalid Guild ID. Use a numeric Discord Guild ID."));
            long id = Long.parseUnsignedLong(argument);
            if (id == 0) return reply(error(lang, "invalid_id", "Invalid Guild ID. Use a numeric Discord Guild ID."));
            var currentJda = jda.get();
            Guild guild = currentJda == null ? null : currentJda.getGuildById(id);
            if (guild == null) return reply(error(lang, "not_found", "Guild not found. The bot must be in that guild."));
            lang = language.apply(id);
            return reply(render(guild, lang, diagnostics.snapshot(id), panels.diagnostics(guild), Instant.now()));
        } catch (NumberFormatException invalid) {
            return reply(error(lang, "invalid_id", "Invalid Guild ID. Use a numeric Discord Guild ID."));
        } catch (RuntimeException unavailable) {
            // Never send exception messages: backend failures may contain credentials or signed URLs.
            return reply(error(lang, "unavailable", "Diagnostic data is currently unavailable."));
        }
    }

    private CompletableFuture<EmbedBuilder> reply(EmbedBuilder embed) { return CompletableFuture.completedFuture(embed); }

    private EmbedBuilder error(String lang, String key, String fallback) {
        return base(Instant.now()).setColor(0xe74c3c).setDescription(t(lang, key, fallback));
    }

    private EmbedBuilder base(Instant now) {
        return new EmbedBuilder().setTitle("Norule Bot | Music Information")
                .setFooter("Norule Bot • Developer Music Diagnostics").setTimestamp(now);
    }

    private String t(String lang, String key, String fallback) { return t(lang, key, fallback, Map.of()); }
    private String t(String lang, String key, String fallback, Map<String, String> values) {
        String full = "dev_musicinfo." + key;
        String text = i18n.get().t(lang, full, values);
        if (text == null || text.isBlank() || text.equals(full)) {
            text = fallback;
            for (var entry : values.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return text;
    }

    private String none(String lang) { return t(lang, "none", "No record"); }
    private String unknown(String lang) { return t(lang, "unknown", "Unknown / unavailable"); }
    private static String time(Instant at) { return at == null ? "-" : "<t:" + at.getEpochSecond() + ":F>"; }
    private static String channel(Guild guild, Long id, String missing) {
        if (id == null) return missing;
        return guild.getTextChannelById(id) == null ? Long.toUnsignedString(id) + " (unavailable)" : "<#" + Long.toUnsignedString(id) + ">";
    }

    public EmbedBuilder render(Guild guild, String lang, MusicDiagnosticsOps.Snapshot snapshot,
                               MusicPanelRefreshService.Diagnostics panel, Instant now) {
        var playback = snapshot.playback();
        int color = switch (playback.state()) {
            case "PLAYING" -> 0x2ecc71; case "PAUSED" -> 0xf1c40f;
            case "LOADING" -> 0x3498db; case "FAILED" -> 0xe74c3c; default -> 0x95a5a6;
        };
        var embed = base(now).setColor(color).setDescription(MusicDiagnostics.text(guild.getName(), 100) + "\nGuild ID: " + guild.getId());
        var ref = panel.panel();
        var mode = panel.mode();
        boolean failed = panel.error() != null && panel.error().at().toEpochMilli() >= panel.refreshedAt();
        String panelState = mode.fallback() ? "INVALID_CHANNEL" : failed ? "FAILED" : panel.pending() ? "PENDING"
                : ref == null ? "NOT_CREATED" : panel.refreshedAt() == 0 ? "UNVERIFIED" : "OK";
        String jump = ref == null || guild.getTextChannelById(ref.channelId) == null ? unknown(lang)
                : "https://discord.com/channels/" + guild.getId() + "/" + Long.toUnsignedString(ref.channelId) + "/" + Long.toUnsignedString(ref.messageId);
        field(embed, lang, "panel", "Music panel", t(lang, "panel_body",
                "Configured: {configured}\nEffective: {effective}\nReason: {reason}\nConfigured channel: {configuredChannel}\nActual channel: {actualChannel}\nMessage (cached): {jump}\nStatus: {status}\nLast update: {updated}",
                Map.of("configured", mode.configured().name(), "effective", mode.effective().name() + (mode.fallback() ? " (temporary)" : ""),
                        "reason", mode.reason(), "configuredChannel", channel(guild, mode.channelId(), none(lang)),
                        "actualChannel", channel(guild, ref == null ? null : ref.channelId, none(lang)), "jump", jump,
                        "status", t(lang, "panel_" + panelState, panelState), "updated", panel.refreshedAt() == 0 ? unknown(lang) : time(Instant.ofEpochMilli(panel.refreshedAt())))));
        var request = snapshot.request();
        field(embed, lang, "request", "Last request", request == null ? none(lang) : t(lang, "request_body",
                "Channel: {channel}\nRequester ID: {requester}\nTime: {time}\nEntry: {source}\nResult: {result}",
                Map.of("channel", channel(guild, request.channelId(), unknown(lang)), "requester", request.requesterId() == null ? unknown(lang) : Long.toUnsignedString(request.requesterId()),
                        "time", time(request.at()), "source", MusicDiagnostics.text(request.source(), 60), "result", MusicDiagnostics.text(request.outcome(), 80))));
        var started = snapshot.lastStarted();
        field(embed, lang, "started", "Last successful playback", started == null ? none(lang) : t(lang, "started_body",
                "Song: {title}\nSource: {source}\nPublic URL: {url}\nStarted: {time}",
                Map.of("title", MusicDiagnostics.text(started.title(), 240), "source", MusicDiagnostics.text(started.source(), 60),
                        "url", MusicDiagnostics.publicSongUrl(started.publicUrl()) == null ? t(lang, "no_url", "No public URL") : MusicDiagnostics.publicSongUrl(started.publicUrl()), "time", time(started.at()))));
        String progress = playback.title() == null ? "-" : playback.stream() || playback.durationMillis() <= 0
                ? t(lang, "stream", "Live / unknown duration") : duration(playback.positionMillis()) + " / " + duration(playback.durationMillis());
        field(embed, lang, "playback", "Playback", t(lang, "playback_body",
                "State: {state}\nSong: {title}\nProgress: {progress}\nVolume: {volume}\nRepeat: {repeat}\nAutoplay: {autoplay}\nQueued: {queued}",
                Map.of("state", t(lang, "state_" + playback.state(), playback.state()), "title", MusicDiagnostics.text(playback.title(), 240),
                        "progress", progress, "volume", playback.volume() == null ? unknown(lang) : playback.volume() + "%",
                        "repeat", playback.repeat() == null ? unknown(lang) : playback.repeat(), "autoplay", String.valueOf(autoplay.test(guild.getIdLong())),
                        "queued", String.valueOf(playback.queued()))));
        var audio = guild.getAudioManager();
        var voice = audio.getConnectedChannel();
        String timers = snapshot.timers() == null ? unknown(lang) : snapshot.timers().isEmpty() ? t(lang, "no_timer", "Not running")
                : snapshot.timers().stream().map(timer -> timer.reason() + ": " + Math.max(0, java.time.Duration.between(now, timer.deadline()).getSeconds()) + "s (" + time(timer.deadline()) + ")")
                .collect(java.util.stream.Collectors.joining("\n"));
        long humans = voice == null ? 0 : voice.getMembers().stream().filter(member -> !member.getUser().isBot()).count();
        long bots = voice == null ? 0 : voice.getMembers().stream().filter(member -> member.getUser().isBot()).count();
        field(embed, lang, "voice", "Voice connection", t(lang, "voice_body",
                "State: {state}\nChannel: {channel}\nHumans (cached): {humans}\nBots (cached): {bots}\nAuto-leave: {timers}",
                Map.of("state", String.valueOf(audio.getConnectionStatus()),
                        "channel", voice == null ? none(lang) : voice.getAsMention() + " (" + voice.getId() + ")",
                        "humans", String.valueOf(humans), "bots", String.valueOf(bots), "timers", timers)));
        field(embed, lang, "errors", "Recent errors", t(lang, "errors_body", "Playback: {playback}\nPanel / Discord API: {panel}\nRecovery: {recovery}",
                Map.of("playback", errorText(snapshot.playbackError(), lang), "panel", errorText(panel.error(), lang), "recovery", unknown(lang))));
        return embed;
    }

    private String errorText(MusicDiagnostics.Error error, String lang) {
        return error == null ? none(lang) : MusicDiagnostics.text(error.type(), 60) + " / " + MusicDiagnostics.text(error.reason(), 100) + " " + time(error.at());
    }

    private void field(EmbedBuilder embed, String lang, String key, String fallback, String body) {
        String title = MusicDiagnostics.text(t(lang, key, fallback), 80);
        embed.addField(title, body.length() > 850 ? body.substring(0, 849) + "…" : body, false);
    }

    private static String duration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
