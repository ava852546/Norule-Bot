package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.MusicPanelMode;
import com.norule.musicbot.service.music.MusicPanelBindingService;
import com.norule.musicbot.storage.sqlite.GuildSettingsSqliteRepository;
import com.norule.musicbot.storage.sqlite.MusicPanelSqliteRepository;
import com.norule.musicbot.storage.sqlite.SqliteDatabase;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MusicPanelModeTest {
    @Test
    void diagnosticInspectionDoesNotChangeModesOrResolverTransitionCache() throws Exception {
        var settings = settings();
        var fixture = new Discord();
        var modes = new MusicPanelModeResolver(settings);
        assertTrue(modes.inspect(fixture.guild).fallback());
        assertFalse(Files.exists(directory.resolve("guilds/1.yml")));
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(10L)));
        assertTrue(modes.inspect(fixture.guild).modern());
        fixture.channelExists = false;
        assertTrue(modes.inspect(fixture.guild).fallback());
        assertEquals(MusicPanelMode.MODERN, settings.getMusic(1).getPanelMode());
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withPanelMode(MusicPanelMode.LEGACY)));
        assertEquals(MusicPanelMode.LEGACY, modes.inspect(fixture.guild).effective());
        var field = MusicPanelModeResolver.class.getDeclaredField("selections");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(modes)).isEmpty());
    }
    @TempDir Path directory;

    private BotConfig defaults() {
        return new BotConfig("", null, "&", false, null, null, null, null, "en", 0, 0,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private GuildSettingsService settings() {
        return new GuildSettingsService(directory.resolve("guilds"), defaults());
    }

    @Test
    void missingSettingDefaultsToModernAndAllMusicEditsPreserveLegacy() throws Exception {
        var settings = settings();
        Files.writeString(directory.resolve("guilds/1.yml"), "music:\n  autoLeaveMinutes: 7\n");
        assertEquals(MusicPanelMode.MODERN, settings.getMusic(1).getPanelMode());
        var legacy = BotConfig.Music.fromMap(Map.of("musicPanelMode", "LEGACY"), null)
                .withCommandChannelId(10L).withHistoryLimit(20).withAutoLeaveEnabled(false)
                .withAutoLeaveMinutes(4).withAutoplayEnabled(false).withStatsRetentionDays(5)
                .withPlaylistTrackLimit(30).withDefaultRepeatMode(BotConfig.Music.RepeatMode.ALL);
        assertEquals(MusicPanelMode.LEGACY, legacy.getPanelMode());
        assertEquals(MusicPanelMode.LEGACY, new MusicConfig(legacy, null).getPanelMode());
        assertEquals(MusicPanelMode.MODERN, MusicPanelMode.parse("invalid"));
    }

    @Test
    void sqliteRestartPreservesIndependentGuildModesAndPanelBindings() {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("bot.db"));
        var repository = new GuildSettingsSqliteRepository(database);
        var first = new GuildSettingsService(directory.resolve("guilds"), defaults(), repository);
        AtomicInteger changes = new AtomicInteger();
        Runnable unsubscribe = first.onMusicSettingsChanged(id -> changes.incrementAndGet());
        first.updateSettings(1, s -> s.withMusic(s.getMusic().withPanelMode(MusicPanelMode.LEGACY)));
        first.updateSettings(2, s -> s.withMusic(s.getMusic().withCommandChannelId(20L)));
        first.updateSettings(2, s -> s.withMusic(s.getMusic().withHistoryLimit(30)));
        assertEquals(2, changes.get());
        unsubscribe.run();
        var panels = new MusicPanelStateStore();
        panels.initializePersistence(new MusicPanelBindingService(new MusicPanelSqliteRepository(database)));
        panels.activatePanelRef(1, new MusicPanelStateStore.PanelRef(10, 100), 0);
        var restarted = new GuildSettingsService(directory.resolve("guilds"), defaults(), repository);
        var restoredPanels = new MusicPanelStateStore();
        restoredPanels.initializePersistence(new MusicPanelBindingService(new MusicPanelSqliteRepository(database)));
        assertEquals(MusicPanelMode.LEGACY, restarted.getMusic(1).getPanelMode());
        assertEquals(MusicPanelMode.MODERN, restarted.getMusic(2).getPanelMode());
        assertEquals(20L, restarted.getMusic(2).getCommandChannelId());
        assertTrue(restoredPanels.isActivePanel(1, 10, 100));
        assertTrue(repository.findGuildPayload(1).contains("musicPanelMode: LEGACY"));
        assertFalse(repository.findGuildPayload(1).contains("autoLeaveEnabled"));
        assertFalse(repository.findGuildPayload(1).contains("autoLeaveMinutes"));
    }

    @Test
    void invalidModernAlwaysFallsBackWithoutWritingSettingsAndRestoresWhenFixed() {
        var settings = settings();
        var fixture = new Discord();
        var modes = new MusicPanelModeResolver(settings);
        assertTrue(modes.resolve(fixture.guild).fallback());
        assertTrue(modes.resolve(fixture.guild).fallback()); // No notice cooldown in the mode decision.
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(10L)));
        assertTrue(modes.resolve(fixture.guild).modern());
        fixture.channelExists = false;
        assertTrue(modes.resolve(fixture.guild).fallback());
        assertEquals(MusicPanelMode.MODERN, settings.getMusic(1).getPanelMode());
        assertEquals(10L, settings.getMusic(1).getCommandChannelId());
        fixture.channelExists = true;
        fixture.accessible = false;
        assertTrue(modes.resolve(fixture.guild).fallback());
        fixture.accessible = true;
        assertTrue(modes.resolve(fixture.guild).modern());
    }

    @Test
    void explicitLegacyDoesNotCheckDefaultChannelAndInvalidStartupDoesNotProvision() {
        var settings = settings();
        var fixture = new Discord();
        var modes = new MusicPanelModeResolver(settings);
        var controller = new MusicPanelController(null, null, id -> fail("Unexpected refresh"), null, modes);
        var initialized = new AtomicInteger();
        MusicPanelController.initializeGuilds(List.of(fixture.guild), id -> initialized.incrementAndGet());
        assertEquals(1, initialized.get()); // Startup installs listeners without channel provisioning.
        assertSame(fixture.channel, controller.resolveOrCreateCommandChannel(fixture.guild, fixture.channel).join());
        assertNull(settings.getMusic(1).getCommandChannelId());
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(999L).withPanelMode(MusicPanelMode.LEGACY)));
        int reads = fixture.channelReads;
        assertFalse(modes.resolve(fixture.guild).fallback());
        assertTrue(controller.isCommandChannelAllowed(fixture.guild, 1234));
        assertEquals(reads, fixture.channelReads);
    }

    @Test
    void onlyDeletesTheRecordedBotOwnedPanelAndWaitsForDeleteResult() {
        var fixture = new Discord();
        var modes = new MusicPanelModeResolver(settings());
        var deletion = MusicPanelRefreshService.deleteManagedPanel(fixture.guild, new MusicPanelStateStore.PanelRef(10, 100), modes);
        assertEquals(1, fixture.deletes);
        assertFalse(deletion.isDone());
        fixture.deleteResult.complete(null);
        assertTrue(deletion.join());
        fixture.authorId = 999;
        assertFalse(MusicPanelRefreshService.deleteManagedPanel(fixture.guild, new MusicPanelStateStore.PanelRef(10, 100), modes).join());
        fixture.authorId = 1000;
        fixture.panelComponents = false;
        assertFalse(MusicPanelRefreshService.deleteManagedPanel(fixture.guild, new MusicPanelStateStore.PanelRef(10, 100), modes).join());
        assertEquals(1, fixture.deletes);
    }

    @Test
    void missingOldPanelAndDiscordFailuresNeverFailThePlaybackContinuation() {
        for (Throwable failure : List.of(
                ErrorResponseException.create(ErrorResponse.MISSING_PERMISSIONS, new Response(new IOException("403"), Set.of())),
                ErrorResponseException.create(ErrorResponse.UNKNOWN_MESSAGE, new Response(new IOException("404"), Set.of())),
                new IllegalStateException("429"))) {
            var fixture = new Discord();
            fixture.retrieveFailure = failure;
            AtomicInteger continuation = new AtomicInteger();
            boolean deleted = MusicPanelRefreshService.deleteManagedPanel(fixture.guild, new MusicPanelStateStore.PanelRef(10, 100),
                    new MusicPanelModeResolver(settings())).thenApply(result -> {
                        continuation.incrementAndGet();
                        return result;
                    }).join();
            assertEquals(MusicPanelRefreshService.isUnknownMessage(failure), deleted);
            assertEquals(1, continuation.get());
            assertEquals(0, fixture.deletes);
        }
    }

    @Test
    void failedDeleteDoesNotAuthorizeReplacementAndMissingMessageDoes() {
        var fixture = new Discord();
        var modes = new MusicPanelModeResolver(settings());
        AtomicInteger creates = new AtomicInteger();
        var replacement = MusicPanelRefreshService.deleteManagedPanel(fixture.guild,
                new MusicPanelStateStore.PanelRef(10, 100), modes).thenAccept(deleted -> {
            if (deleted) creates.incrementAndGet();
        });
        fixture.deleteResult.completeExceptionally(new IllegalStateException("network unavailable"));
        replacement.join();
        assertEquals(0, creates.get());
        fixture.retrieveFailure = ErrorResponseException.create(ErrorResponse.UNKNOWN_MESSAGE,
                new Response(new IOException("404"), Set.of()));
        assertTrue(MusicPanelRefreshService.deleteManagedPanel(fixture.guild,
                new MusicPanelStateStore.PanelRef(10, 100), modes).join());
        fixture.channelExists = false;
        assertFalse(MusicPanelRefreshService.deleteManagedPanel(fixture.guild,
                new MusicPanelStateStore.PanelRef(10, 100), modes).join());
    }

    @Test
    void unknownMessageClassificationPreservesJdaResponseWithAnUnderlyingCause() {
        var failure = ErrorResponseException.create(ErrorResponse.UNKNOWN_MESSAGE,
                new Response(new IOException("transport context"), Set.of()));
        assertTrue(MusicPanelRefreshService.isUnknownMessage(new java.util.concurrent.CompletionException(failure)));
        assertEquals("UNKNOWN_MESSAGE", MusicPanelModeResolver.failureReason(failure));
    }

    @Test
    void concurrentPlayRequestsCoalesceAndHoldTheBarrierAcrossDeleteAndCreate() {
        var fixture = new Discord();
        var modes = new MusicPanelModeResolver(settings());
        var scheduler = new MusicPanelRefreshCoordinatorTest.ManualScheduler();
        var created = new ArrayList<CompletableFuture<MusicPanelRefreshCoordinator.Update>>();
        try (var coordinator = new MusicPanelRefreshCoordinator(scheduler, (guildId, reasons) ->
                MusicPanelRefreshService.deleteManagedPanel(fixture.guild, new MusicPanelStateStore.PanelRef(10, 100), modes)
                        .thenCompose(ignored -> {
                            var creation = new CompletableFuture<MusicPanelRefreshCoordinator.Update>();
                            created.add(creation);
                            return creation;
                        }))) {
            for (int i = 0; i < 100; i++) coordinator.requestRefresh(1, RefreshReason.PLAY_ADDED);
            scheduler.advance(750);
            assertEquals(1, fixture.deletes);
            assertTrue(created.isEmpty());
            coordinator.requestRefresh(1, RefreshReason.PLAY_ADDED);
            fixture.deleteResult.complete(null);
            assertEquals(1, created.size());
            scheduler.advance(10_000);
            assertEquals(1, fixture.deletes); // Creation is still outstanding.
            created.getFirst().complete(null);
            scheduler.advance(750);
            assertEquals(2, fixture.deletes);
            assertEquals(2, created.size());
            created.get(1).complete(null);
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static class Discord {
        boolean channelExists = true;
        boolean accessible = true;
        boolean panelComponents = true;
        long authorId = 1000;
        int channelReads;
        int deletes;
        Throwable retrieveFailure;
        final CompletableFuture<Void> deleteResult = new CompletableFuture<>();
        final SelfMember self = proxy(SelfMember.class, (o, m, a) -> switch (m.getName()) {
            case "getIdLong" -> 1000L;
            case "hasPermission" -> accessible;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        final Guild guild = proxy(Guild.class, (o, m, a) -> switch (m.getName()) {
            case "getIdLong" -> 1L;
            case "getSelfMember" -> self;
            case "getTextChannelById" -> {
                channelReads++;
                yield channelExists && ((Number) a[0]).longValue() == 10 ? this.channel : null;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
        final TextChannel channel = proxy(TextChannel.class, (o, m, a) -> switch (m.getName()) {
            case "getIdLong" -> 10L;
            case "getGuild" -> guild;
            case "retrieveMessageById" -> Proxy.newProxyInstance(m.getReturnType().getClassLoader(),
                    new Class<?>[]{m.getReturnType()}, (action, method, arguments) -> {
                        assertEquals("submit", method.getName());
                        return retrieveFailure == null ? CompletableFuture.completedFuture(message())
                                : CompletableFuture.failedFuture(retrieveFailure);
                    });
            default -> throw new UnsupportedOperationException(m.getName());
        });

        Message message() {
            User author = proxy(User.class, (o, m, a) -> authorId);
            return proxy(Message.class, (o, m, a) -> switch (m.getName()) {
                case "getAuthor" -> author;
                case "getComponentTree" -> MessageComponentTree.of(panelComponents
                        ? List.of(ActionRow.of(Button.primary("panel:playpause", "Play"))) : List.of());
                case "delete" -> Proxy.newProxyInstance(m.getReturnType().getClassLoader(),
                        new Class<?>[]{m.getReturnType()}, (action, method, arguments) -> {
                            assertEquals("submit", method.getName());
                            deletes++;
                            return deleteResult;
                        });
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
