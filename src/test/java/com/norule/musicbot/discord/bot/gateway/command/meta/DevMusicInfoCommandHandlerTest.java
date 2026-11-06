package com.norule.musicbot.discord.bot.gateway.command.meta;

import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelModeResolver;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelRefreshService;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelStateStore;
import com.norule.musicbot.discord.bot.ops.music.MusicDiagnosticsOps;
import com.norule.musicbot.domain.music.MusicDiagnostics;
import com.norule.musicbot.domain.music.MusicPanelMode;
import com.norule.musicbot.i18n.I18nService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DevMusicInfoCommandHandlerTest {
    @TempDir Path directory;

    private DevMusicInfoCommandHandler handler() {
        var i18n = I18nService.load(directory, "en");
        JDA jda = proxy(JDA.class, (name, args) -> {
            if (name.equals("getGuildById")) return null;
            throw new AssertionError("Unexpected JDA call: " + name);
        });
        return new DevMusicInfoCommandHandler(() -> jda, () -> i18n, () -> "en", id -> "en", id -> true,
                null, null, null);
    }

    @Test void validatesMissingMalformedOverflowAndUnknownIdsWithoutRestCalls() {
        var handler = handler();
        assertTrue(handler.execute("").join().build().getDescription().contains("Usage"));
        for (String input : List.of("0", "-1", "123 extra", "abc", "18446744073709551616")) {
            assertTrue(handler.execute(input).join().build().getDescription().contains("Invalid Guild ID"));
        }
        assertTrue(handler.execute("123456789012345678").join().build().getDescription().contains("Guild not found"));
    }

    @Test void boundedLocalizedEmbedsKeepPanelErrorsSeparateAndDoNotTouchDiscord() {
        var handler = handler();
        AudioManager audio = proxy(AudioManager.class, (name, args) -> {
            if (name.equals("getConnectedChannel") || name.equals("getConnectionStatus")) return null;
            throw new AssertionError("Unexpected audio mutation: " + name);
        });
        Guild guild = proxy(Guild.class, (name, args) -> switch (name) {
            case "getName" -> "Guild".repeat(1000);
            case "getId" -> "123";
            case "getIdLong" -> 123L;
            case "getAudioManager" -> audio;
            case "getTextChannelById" -> null;
            default -> throw new AssertionError("Unexpected Discord call: " + name);
        });
        Instant now = Instant.parse("2026-11-06T15:00:00Z");
        for (String state : List.of("PLAYING", "PAUSED", "LOADING", "IDLE", "FAILED", "UNINITIALIZED")) {
            var snapshot = new MusicDiagnosticsOps.Snapshot(new MusicDiagnostics.Playback(state, "title".repeat(3000),
                    1500, 0, true, 80, "OFF", 5),
                    new MusicDiagnostics.Event(123, "REQUEST", now, 20L, 30L, "/play", null, null, "REJECTED"),
                    new MusicDiagnostics.Event(123, "START", now, null, 30L, "YouTube", "old".repeat(3000),
                            "https://cdn.test/audio?token=secret", "CONFIRMED"), null,
                    List.of(new MusicDiagnostics.Timer("EMPTY_CHANNEL", now.plusSeconds(7))));
            var panel = new MusicPanelRefreshService.Diagnostics(new MusicPanelModeResolver.Selection(MusicPanelMode.MODERN,
                    MusicPanelMode.LEGACY, 10L, null, "UNKNOWN_CHANNEL"), new MusicPanelStateStore.PanelRef(10, 11),
                    0, false, new MusicDiagnostics.Error(now, "PANEL_API_FAILED", "DISCORD_API_429"));
            var embed = handler.render(guild, "zh-TW", snapshot, panel, now).build();
            assertTrue(embed.getLength() <= 6000);
            assertTrue(embed.getFields().stream().allMatch(field -> field.getValue().length() <= 1024));
            String text = embed.getFields().toString();
            assertFalse(text.contains("secret"));
            assertFalse(text.contains("dev_musicinfo."));
            assertTrue(embed.getFields().get(2).getValue().contains("無公開網址"));
            assertTrue(embed.getFields().get(3).getValue().contains("直播／未知時長"));
            assertTrue(embed.getFields().get(5).getValue().contains("DISCORD_API_429"));
            assertNull(snapshot.playbackError());
        }
    }

    private interface Call { Object invoke(String name, Object[] args); }
    private <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> call.invoke(method.getName(), args)));
    }
}
