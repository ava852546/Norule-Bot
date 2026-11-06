package com.norule.musicbot.discord.bot.ops.music;

import com.norule.musicbot.domain.music.MusicDiagnostics;
import com.norule.musicbot.domain.music.MusicPlayerService;
import com.norule.musicbot.service.music.MusicDiagnosticsHistoryService;
import java.util.List;

/** Collects local music snapshots without calling mutating player getters. */
public final class MusicDiagnosticsOps {
    public record Snapshot(MusicDiagnostics.Playback playback, MusicDiagnostics.Event request,
                           MusicDiagnostics.Event lastStarted, MusicDiagnostics.Error playbackError,
                           List<MusicDiagnostics.Timer> timers) {}
    private final MusicPlayerService player;
    private final MusicDiagnosticsHistoryService history;

    public MusicDiagnosticsOps(MusicPlayerService player, MusicDiagnosticsHistoryService history) {
        this.player = player;
        this.history = history;
    }

    public Snapshot snapshot(long guildId) {
        return new Snapshot(player.diagnosticPlayback(guildId), history.get(guildId, "REQUEST"),
                history.get(guildId, "START"), player.diagnosticError(guildId), player.diagnosticLeaveTimers(guildId));
    }
}
