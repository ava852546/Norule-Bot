package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.ShortUrlService;
import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.config.GuildSettingsService;
import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.config.domain.RuntimeConfigSnapshot;
import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.domain.music.CipherPolicy;
import com.norule.musicbot.domain.music.MusicPanelMode;
import com.norule.musicbot.domain.music.MusicPlayerService;
import com.norule.musicbot.domain.music.YouTubePlaybackTrackFactory;
import com.norule.musicbot.gateway.youtube.YouTubePlaybackRuntimeFactory;
import com.norule.musicbot.service.music.MusicCommandChannelProvisioningService;
import com.norule.musicbot.service.music.MusicPanelBindingService;
import com.norule.musicbot.shorturl.SqliteShortUrlRepository;
import com.norule.musicbot.storage.sqlite.MusicCommandChannelProvisioningSqliteRepository;
import com.norule.musicbot.storage.sqlite.MusicPanelSqliteRepository;
import com.norule.musicbot.storage.sqlite.SqliteDatabase;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real refresh service, coordinator, renderer and durable bindings against delayed Discord actions. */
class MusicPanelLifecycleTest {
    @TempDir Path directory;
    final MusicPanelRefreshCoordinatorTest.ManualScheduler scheduler = new MusicPanelRefreshCoordinatorTest.ManualScheduler();
    final Discord discord = new Discord();
    final MusicPanelStateStore store = new MusicPanelStateStore();
    GuildSettingsService settings;
    MusicPlayerService music;
    MusicCommandService owner;
    MusicPanelRefreshService panels;
    MusicPanelModeResolver modes;
    MusicPanelSqliteRepository bindings;
    Runnable unsubscribe;
    boolean simulatedLoads;
    Consumer<String> loaded;
    int loadCalls;
    final com.sedmelluq.discord.lavaplayer.track.AudioTrack searchTrack = proxy(
            com.sedmelluq.discord.lavaplayer.track.AudioTrack.class, (o, m, a) -> switch (m.getName()) {
                case "getInfo" -> new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo(
                        "Test track", "Artist", 60_000, "track-id", false, "https://youtu.be/test-track1");
                case "getDuration" -> 60_000L;
                default -> null;
            });

    @BeforeEach
    void setup() {
        var defaults = new BotConfig("", null, "&", false, null, null, null, null, "en", 0, 0,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        settings = new GuildSettingsService(directory.resolve("guilds"), defaults);
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(10L)));
        var policy = new CipherPolicy(false);
        var config = MusicConfig.defaultValues();
        music = new MusicPlayerService(directory, id -> 100, id -> 365, id -> 100, config, policy,
                auth -> YouTubePlaybackRuntimeFactory.createSource(config.getCipher(), policy, auth, YouTubePlaybackTrackFactory.youtubeSource())) {
            @Override public void searchTopTracks(String query, int limit,
                    Consumer<List<com.sedmelluq.discord.lavaplayer.track.AudioTrack>> success, Consumer<String> failure) {
                if (!simulatedLoads) throw new AssertionError("Unexpected network search");
                success.accept(List.of(searchTrack));
            }
            @Override public void loadAndPlay(Guild guild, Consumer<String> reply, String input, Long userId, String userName) {
                if (!simulatedLoads) throw new AssertionError("Unexpected network load");
                loadCalls++; loaded = reply;
            }
            @Override public void queueTrackByIdentifier(Guild guild, String identifier, String source,
                    Consumer<String> reply, Long userId, String userName) {
                if (!simulatedLoads) throw new AssertionError("Unexpected network selection load");
                loadCalls++; loaded = reply;
            }
        };
        var database = new SqliteDatabase(directory.resolve("bindings.db"));
        owner = new MusicCommandService(music, RuntimeConfigSnapshot.from(defaults, directory), settings,
                new com.norule.musicbot.ModerationService(directory.resolve("moderation")), null,
                new ShortUrlService(new SqliteShortUrlRepository(directory.resolve("urls.db"))), null, null, null,
                new MusicCommandChannelProvisioningService(new MusicCommandChannelProvisioningSqliteRepository(database))) {
            @Override public JDA currentJda() { return discord.jda; }
            @Override public java.util.concurrent.ScheduledExecutorService scheduler() { return scheduler; }
        };
        owner.shutdown(); // Remove production timers/listener; tests drive the actual service with a virtual clock.
        bindings = new MusicPanelSqliteRepository(database);
        store.initializePersistence(new MusicPanelBindingService(bindings));
        modes = new MusicPanelModeResolver(settings);
        panels = new MusicPanelRefreshService(owner, store, new MusicPanelRenderer(owner, store), scheduler, 30_000, modes, scheduler::nanos);
        unsubscribe = settings.onMusicSettingsChanged(panels::settingsChanged);
    }

    @AfterEach
    void close() throws Exception {
        if (unsubscribe != null) unsubscribe.run();
        if (panels != null) panels.close();
        if (owner != null) owner.shutdown();
        scheduler.shutdownNow();
        if (music != null) {
            var field = MusicPlayerService.class.getDeclaredField("playerManager");
            field.setAccessible(true);
            ((AudioPlayerManager) field.get(music)).shutdown();
        }
    }

    void seed(long channel, long message) {
        discord.messages.put(message, discord.message(channel, message));
        store.activatePanelRef(1, new MusicPanelStateStore.PanelRef(channel, message), 0);
    }

    void drain() { scheduler.advance(3_000); }

    @ParameterizedTest
    @EnumSource(MusicPanelMode.class)
    void unsetConfiguredChannelCanReplaceOldPanelWithoutUnboxingNull(MusicPanelMode configured) {
        unsubscribe.run();
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(null).withPanelMode(configured)));
        seed(10, 100);
        var selection = modes.resolve(discord.guild);
        assertNull(selection.channelId());
        assertEquals(MusicPanelMode.LEGACY, selection.effective());
        var errors = new ArrayList<String>();
        panels.playQueued(discord.guild, discord.channels.get(20L), selection, errors::add);
        drain();
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(List.of(20L), discord.creates);
        assertEquals(20, store.getPanelRef(1).channelId);
    }

    @Test
    void modernTargetDoesNotRequireAnInteractionChannel() {
        var errors = new ArrayList<String>();
        panels.playQueued(discord.guild, (Long) null, modes.resolve(discord.guild), "/play", errors::add);
        drain();
        assertTrue(errors.isEmpty());
        assertEquals(List.of(10L), discord.creates);
    }

    @Test
    void clearingModernSettingDuringLoadStillCreatesLegacyAtValidSourceWhenNoPanelExists() {
        var initial = modes.resolve(discord.guild);
        unsubscribe.run();
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(null)));
        var errors = new ArrayList<String>();
        panels.playQueued(discord.guild, 20L, initial, "/play", errors::add);
        drain();
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(List.of(20L), discord.creates);
        assertTrue(modes.resolve(discord.guild).fallback());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/play", "search-selection"})
    void modernChannelDeletedDuringLoadUsesRetainedInteractionId(String source) {
        var modern = modes.resolve(discord.guild);
        seed(10, 100);
        discord.channels.remove(10L);
        var errors = new ArrayList<String>();
        panels.playQueued(discord.guild, 20L, modern, source, errors::add);
        drain();
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(modes.resolve(discord.guild).fallback());
        assertEquals(List.of(20L), discord.creates);
        assertEquals(20, store.getPanelRef(1).channelId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_INTERACTION_CHANNEL", "UNKNOWN_INTERACTION_CHANNEL", "MISSING_TARGET_PERMISSIONS"})
    void invalidLegacyTargetReportsDistinctReasonsAndAllResolutionFields(String reason) {
        unsubscribe.run();
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(null)));
        Long source = reason.equals("NO_INTERACTION_CHANNEL") ? null : Long.valueOf(20);
        if (reason.equals("UNKNOWN_INTERACTION_CHANNEL")) discord.channels.remove(20L);
        if (reason.equals("MISSING_TARGET_PERMISSIONS")) discord.denied = 20;
        var errors = new ArrayList<String>();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MusicPanelRefreshService.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            panels.playQueued(discord.guild, source, modes.resolve(discord.guild), "search-selection", errors::add);
            drain();
            assertEquals(1, errors.size());
            assertFalse(errors.getFirst().contains("NullPointerException"));
            assertFalse(errors.getFirst().contains("music.panel_target_unavailable"));
            assertTrue(discord.creates.isEmpty());
            String diagnostic = logs.list.stream().map(event -> event.getFormattedMessage())
                    .filter(text -> text.contains("event=MUSIC_PANEL_TARGET_RESOLUTION_FAILED")).findFirst().orElseThrow();
            for (String field : List.of("guildId=1", "configuredMode=MODERN", "effectiveMode=LEGACY",
                    "configuredChannelId=null", "selectionChannelId=null", "interactionChannelId=" + source,
                    "finalTargetChannelId=" + source, "selectionReason=NOT_CONFIGURED", "reason=" + reason,
                    "source=search-selection")) {
                assertTrue(diagnostic.contains(field), diagnostic);
            }
        } finally {
            logger.detachAppender(logs); logs.stop();
        }
    }

    @Test
    void cachedLegacyChannelRemovedBeforeDispatchIsRejected() {
        unsubscribe.run();
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withPanelMode(MusicPanelMode.LEGACY)));
        var errors = new ArrayList<String>();
        panels.playQueued(discord.guild, discord.channels.get(20L), modes.resolve(discord.guild), errors::add);
        discord.channels.remove(20L);
        drain();
        assertEquals(1, errors.size());
        assertTrue(discord.creates.isEmpty());
    }

    @Test
    void manualRecoveryWithoutConfiguredOrInteractionChannelFailsExplicitlyWithoutUnboxing() {
        unsubscribe.run();
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(null)));
        var recovery = panels.recoverPanel(discord.guild, null, "music-panel");
        drain();
        var error = assertThrows(java.util.concurrent.CompletionException.class, recovery::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains("target channel is unavailable"));
    }

    @Test
    void clearingConfiguredChannelLogsLifecycleAndLeavesSettingsRecoverable() {
        seed(10, 100);
        assertDoesNotThrow(() -> settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(null))));
        drain();
        var recovery = panels.synchronizeSettings(discord.guild);
        drain();
        assertTrue(recovery.isCompletedExceptionally());
        panels.playQueued(discord.guild, discord.channels.get(20L));
        drain();
        assertEquals(List.of(20L), discord.creates);
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(30L)));
        drain();
        panels.synchronizeSettings(discord.guild).join();
        assertEquals(30, store.getPanelRef(1).channelId);
    }

    @Test
    void consecutiveLegacyRequestsWithNullConfiguredIdCreateOnlyOnePanel() {
        unsubscribe.run();
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(null)));
        seed(10, 100);
        for (int i = 0; i < 100; i++) {
            panels.playQueued(discord.guild, 20L, modes.resolve(discord.guild), "/play", error -> fail(error));
        }
        drain();
        assertEquals(List.of(20L), discord.creates);
        assertEquals(List.of(100L), discord.deletes);
    }

    @Test
    void delayedPatchFailureAfterSuccessfulPlaybackNotifiesSeparatelyOnce() {
        seed(10, 100);
        discord.delayEdits = true;
        var errors = new ArrayList<String>();
        panels.playQueued(discord.guild, 20L, modes.resolve(discord.guild), "/play", errors::add);
        drain();
        assertTrue(errors.isEmpty());
        assertEquals(1, discord.editResults.size());
        discord.editResults.getFirst().completeExceptionally(new IllegalStateException("Discord edit unavailable"));
        assertEquals(List.of("Discord edit unavailable"), errors);
        drain();
        assertEquals(1, discord.editResults.size(), "A panel failure must not add application retries");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualSlashAndSearchCallbacksRetainInteractionSourceAcrossFallback(boolean search) {
        simulatedLoads = true;
        discord.connected = true;
        var interaction = new PlaybackInteraction(search ? "test song" : "https://youtu.be/test-track1");
        var controller = new MusicPanelController(owner, null, panels::refreshPanel, panels, modes);
        var handler = new com.norule.musicbot.discord.bot.gateway.command.music.MusicPlaybackCommandHandler(
                owner, controller, new com.norule.musicbot.discord.bot.gateway.command.music.MusicPlaybackText(owner::i18nService));
        handler.handlePlaySlash(new net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent(
                discord.jda, 1, interaction.slash()), "en");
        discord.channels.remove(10L); // MODERN was valid when /play started, before load/selection finishes.
        if (search) {
            assertNotNull(interaction.menuId);
            handler.handlePlayPick(new net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent(
                    discord.jda, 2, interaction.selection()), "en");
        }
        assertEquals(1, loadCalls);
        assertNotNull(loaded);
        loaded.accept("Test track");
        drain();
        assertEquals(List.of(20L), discord.creates);
        assertEquals(20, store.getPanelRef(1).channelId);
        assertEquals(1, loadCalls, "Panel recovery must not enqueue playback again");
        assertEquals(0, interaction.panelErrors);
    }

    @Test
    @SuppressWarnings("unchecked")
    void panelFailureCannotStopPlaybackClearQueueOrStartDuplicateTracks() throws Exception {
        var current = proxy(com.sedmelluq.discord.lavaplayer.track.AudioTrack.class, (o, m, a) -> null);
        var queued = proxy(com.sedmelluq.discord.lavaplayer.track.AudioTrack.class, (o, m, a) -> null);
        var player = proxy(com.sedmelluq.discord.lavaplayer.player.AudioPlayer.class, (o, m, a) -> switch (m.getName()) {
            case "getPlayingTrack" -> current;
            case "addListener" -> null;
            case "startTrack" -> false;
            default -> throw new AssertionError("Panel must not mutate player: " + m.getName());
        });
        var manager = new com.norule.musicbot.domain.music.GuildMusicManager(
                proxy(AudioPlayerManager.class, (o, m, a) -> player), null, null, null, null, null, null, () -> true);
        manager.getScheduler().queue(queued);
        var field = MusicPlayerService.class.getDeclaredField("musicManagers");
        field.setAccessible(true);
        ((Map<Long, com.norule.musicbot.domain.music.GuildMusicManager>) field.get(music)).put(1L, manager);
        unsubscribe.run();
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(null)));
        var errors = new ArrayList<String>();
        panels.playQueued(discord.guild, (Long) null, modes.resolve(discord.guild), "/play", errors::add);
        drain();
        assertEquals(1, errors.size());
        assertSame(current, music.getCurrentTrack(discord.guild));
        assertSame(queued, music.getQueueSnapshot(discord.guild).getFirst());
        assertEquals(1, music.getQueueSnapshot(discord.guild).size());
    }

    @Test
    void deletedAThenSettingsBRecoversAndPersistsModernWithoutRestart() {
        seed(10, 100);
        discord.channels.remove(10L);
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(20L)));
        assertFalse(store.isActivePanel(1, 10, 100));
        var completion = panels.synchronizeSettings(discord.guild);
        assertFalse(completion.isDone());
        drain();
        completion.join();
        assertEquals(List.of(20L), discord.creates);
        assertEquals(20, store.getPanelRef(1).channelId);
        assertEquals(20, bindings.findAll().getFirst().channelId());
        assertEquals(20L, settings.getMusic(1).getCommandChannelId());
        assertEquals(MusicPanelMode.MODERN, settings.getMusic(1).getPanelMode());
        assertTrue(discord.deletes.isEmpty());
    }

    @Test
    void deletedAAllowsManualFallbackAndThenSettingsBAdoptsTheSamePanel() {
        seed(10, 100);
        discord.channels.remove(10L);
        var recovery = panels.recoverPanel(discord.guild, discord.channels.get(20L), "music-panel");
        drain();
        recovery.join();
        long fallbackMessage = store.getPanelRef(1).messageId;
        assertEquals(20, store.getPanelRef(1).channelId);
        assertTrue(modes.resolve(discord.guild).fallback());
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(20L)));
        drain();
        panels.synchronizeSettings(discord.guild).join();
        assertEquals(fallbackMessage, store.getPanelRef(1).messageId);
        assertEquals(List.of(20L), discord.creates);
    }

    @Test
    void manualRecoveryDetectsDeletedMessageEvenWithAnUnchangedSnapshot() {
        var first = panels.recoverPanel(discord.guild, discord.channels.get(10L), "music-panel");
        drain(); first.join();
        long old = store.getPanelRef(1).messageId;
        discord.messages.remove(old);
        var second = panels.recoverPanel(discord.guild, discord.channels.get(10L), "music-panel");
        drain(); second.join();
        assertNotEquals(old, store.getPanelRef(1).messageId);
        assertEquals(List.of(10L, 10L), discord.creates);
    }

    @Test
    void existingManagedPanelIsRecoveredAndNormalUserMessagesAreNeverDeleted() {
        seed(10, 100);
        discord.messages.put(200L, discord.message(20, 200));
        discord.messages.put(201L, discord.message(20, 201, 999));
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(20L)));
        drain();
        panels.synchronizeSettings(discord.guild).join();
        assertTrue(discord.creates.isEmpty());
        assertEquals(List.of(100L), discord.deletes);
        assertTrue(store.isActivePanel(1, 20, 200));
        assertTrue(discord.messages.containsKey(201L));
    }

    @Test
    void delayedCreateFromBIsCleanedBeforeCStartsAndCannotOverwriteC() {
        seed(10, 100);
        discord.delayCreates = true;
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(20L)));
        drain();
        assertEquals(List.of(20L), discord.creates);
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(30L)));
        drain();
        assertEquals(List.of(20L), discord.creates, "The old REST flight still owns the guild barrier");
        discord.completeCreate(0);
        drain();
        assertEquals(List.of(20L, 30L), discord.creates);
        discord.completeCreate(1);
        drain();
        panels.synchronizeSettings(discord.guild).join();
        assertTrue(store.isActivePanel(1, 30, 1002));
        assertEquals(30, bindings.findAll().getFirst().channelId());
        assertFalse(discord.messages.containsKey(1001L), "Obsolete B creation must not become an orphan");
    }

    @Test
    void settingsFailureRetainsTheNewChannelAndDoesNotAutomaticallyRetry() {
        seed(10, 100);
        discord.createFailure = new IllegalStateException("Discord API unavailable");
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(20L)));
        drain();
        assertThrows(java.util.concurrent.CompletionException.class, () -> panels.synchronizeSettings(discord.guild).join());
        assertEquals(20L, settings.getMusic(1).getCommandChannelId());
        drain(); drain();
        assertEquals(List.of(20L), discord.creates);
        assertTrue(bindings.findAll().isEmpty());
    }

    @Test
    void explicitLegacySettingsChangeDoesNotCreateModernPanel() {
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withPanelMode(MusicPanelMode.LEGACY)));
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(20L)));
        drain();
        panels.synchronizeSettings(discord.guild).join();
        assertEquals(MusicPanelMode.LEGACY, settings.getMusic(1).getPanelMode());
        assertTrue(discord.creates.isEmpty());
        panels.playQueued(discord.guild, discord.channels.get(30L));
        drain();
        assertEquals(List.of(30L), discord.creates);
    }

    @Test
    void delayedFallbackPlayDoesNotDeleteARestoredModernPanel() {
        seed(10, 100);
        discord.denied = 10;
        var fallback = modes.resolve(discord.guild);
        assertTrue(fallback.fallback());
        discord.denied = 0;
        panels.playQueued(discord.guild, discord.channels.get(20L), fallback, error -> fail(error));
        drain();
        assertTrue(store.isActivePanel(1, 10, 100));
        assertTrue(discord.creates.isEmpty());
        assertTrue(discord.deletes.isEmpty());
    }

    @Test
    void latePlayFromADoesNotDeleteThePanelAlreadyCreatedInC() {
        seed(10, 100);
        var oldMode = modes.resolve(discord.guild);
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(30L)));
        drain();
        long currentMessage = store.getPanelRef(1).messageId;
        panels.playQueued(discord.guild, discord.channels.get(10L), oldMode, error -> fail(error));
        drain();
        assertTrue(store.isActivePanel(1, 30, currentMessage));
        assertEquals(List.of(30L), discord.creates);
        assertFalse(discord.deletes.contains(currentMessage));
    }

    @Test
    void oldUpdateFailureCannotRecreateAOrOverwriteB() {
        seed(10, 100);
        discord.delayEdits = true;
        panels.recoverPanel(discord.guild, discord.channels.get(10L), "music-panel");
        drain();
        assertEquals(1, discord.editResults.size());
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(20L)));
        drain();
        assertTrue(discord.creates.isEmpty());
        discord.editResults.getFirst().completeExceptionally(unknownMessage());
        drain();
        panels.synchronizeSettings(discord.guild).join();
        assertEquals(List.of(20L), discord.creates);
        assertEquals(20, store.getPanelRef(1).channelId);
        assertEquals(20, bindings.findAll().getFirst().channelId());
    }

    @Test
    void temporaryFallbackProtectsModernAAndRestoresItWithoutAnotherCreate() {
        seed(10, 100);
        discord.denied = 10;
        panels.playQueued(discord.guild, discord.channels.get(20L));
        drain();
        assertEquals(List.of(20L), discord.creates);
        assertTrue(discord.messages.containsKey(100L));
        assertTrue(discord.deletes.isEmpty());
        discord.denied = 0;
        panels.requestRefresh(1, RefreshReason.MANUAL);
        drain();
        assertTrue(store.isActivePanel(1, 10, 100));
        assertEquals(List.of(20L), discord.creates);
        assertEquals(MusicPanelMode.MODERN, settings.getMusic(1).getPanelMode());
    }

    @Test
    void restartWithAStalePersistedBindingUsesTheCurrentConfiguredChannel() {
        seed(10, 100);
        unsubscribe.run();
        panels.close();
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(20L)));
        discord.channels.remove(10L);
        var restarted = new MusicPanelStateStore();
        restarted.initializePersistence(new MusicPanelBindingService(bindings));
        panels = new MusicPanelRefreshService(owner, restarted, new MusicPanelRenderer(owner, restarted), scheduler,
                30_000, modes, scheduler::nanos);
        var recovery = panels.recoverPanel(discord.guild, discord.channels.get(20L), "music-panel");
        drain(); recovery.join();
        assertEquals(20, restarted.getPanelRef(1).channelId);
        assertEquals(20, bindings.findAll().getFirst().channelId());
        assertEquals(List.of(20L), discord.creates);
    }

    @Test
    void aRateLimitedCreateFailsWithoutApplicationRetriesOrSuccessFeedback() {
        discord.createFailure = new net.dv8tion.jda.api.exceptions.RateLimitedException("/channels/20/messages", 2_000);
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(20L)));
        drain();
        assertThrows(java.util.concurrent.CompletionException.class, () -> panels.synchronizeSettings(discord.guild).join());
        drain(); drain();
        assertEquals(List.of(20L), discord.creates);
        assertNull(store.getPanelRef(1));
        assertEquals(20L, settings.getMusic(1).getCommandChannelId());
    }

    @Test
    void aSettingsResponseForBMustNotReportSuccessForC() {
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(20L)));
        settings.updateSettings(1, s -> s.withMusic(s.getMusic().withCommandChannelId(30L)));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> panels.synchronizeSettings(discord.guild, 20L).join());
        drain();
        panels.synchronizeSettings(discord.guild, 30L).join();
        assertEquals(List.of(30L), discord.creates);
    }

    @Test
    void missingPermissionFailsManualRecoveryWithoutAnyCreateRequest() {
        discord.denied = 10L;
        var result = panels.recoverPanel(discord.guild, discord.channels.get(20L), "music-panel");
        // Invalid MODERN falls back to the actual command channel; denying that channel fails explicitly.
        discord.denied = 20L;
        discord.channels.remove(10L);
        drain();
        assertThrows(java.util.concurrent.CompletionException.class, result::join);
        assertTrue(discord.creates.isEmpty());
    }

    final class Discord {
        final Map<Long, TextChannel> channels = new HashMap<>();
        final Map<Long, Message> messages = new LinkedHashMap<>();
        final List<Long> creates = new ArrayList<>();
        final List<Long> deletes = new ArrayList<>();
        final List<CompletableFuture<Message>> createResults = new ArrayList<>();
        final List<Message> createdMessages = new ArrayList<>();
        final List<CompletableFuture<Message>> editResults = new ArrayList<>();
        boolean delayCreates;
        boolean delayEdits;
        boolean connected;
        Throwable createFailure;
        long denied;
        long nextId = 1000;
        final SelfMember self = proxy(SelfMember.class, (o, m, a) -> switch (m.getName()) {
            case "getIdLong" -> 500L;
            case "hasPermission" -> !(a[0] instanceof TextChannel text) || text.getIdLong() != denied;
            case "hasAccess" -> true;
            default -> null;
        });
        final net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion voice = proxy(
                net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion.class, (o, m, a) ->
                        m.getName().equals("getIdLong") ? 900L : null);
        final AudioManager audio = proxy(AudioManager.class, (o, m, a) ->
                m.getName().equals("getConnectedChannel") && connected ? voice : null);
        final Guild guild = proxy(Guild.class, (o, m, a) -> switch (m.getName()) {
            case "getIdLong" -> 1L;
            case "getSelfMember" -> self;
            case "getTextChannelById" -> channels.get(((Number) a[0]).longValue());
            case "getAudioManager" -> audio;
            case "getName" -> "Test guild";
            default -> null;
        });
        final JDA jda = proxy(JDA.class, (o, m, a) -> m.getName().equals("getGuildById") ? guild : null);

        Discord() { for (long id : List.of(10L, 20L, 30L)) channels.put(id, channel(id)); }

        TextChannel channel(long id) {
            return proxy(TextChannel.class, (o, m, a) -> switch (m.getName()) {
                case "getIdLong" -> id;
                case "getType" -> net.dv8tion.jda.api.entities.channel.ChannelType.TEXT;
                case "getId" -> Long.toString(id);
                case "getGuild" -> guild;
                case "getAsMention" -> "<#" + id + ">";
                case "getHistory" -> new net.dv8tion.jda.api.entities.MessageHistory((TextChannel) o) {
                    @Override @SuppressWarnings("unchecked")
                    public net.dv8tion.jda.api.requests.RestAction<List<Message>> retrievePast(int count) {
                        return (net.dv8tion.jda.api.requests.RestAction<List<Message>>) action(
                                net.dv8tion.jda.api.requests.RestAction.class,
                                () -> CompletableFuture.completedFuture(messages.values().stream()
                                        .filter(message -> message.getChannelIdLong() == id).toList()));
                    }
                };
                case "retrieveMessageById" -> action(m.getReturnType(), () -> {
                    var message = messages.get(((Number) a[0]).longValue());
                    return message == null ? CompletableFuture.failedFuture(unknownMessage()) : CompletableFuture.completedFuture(message);
                });
                case "sendMessageEmbeds" -> action(m.getReturnType(), () -> {
                    creates.add(id);
                    Message message = message(id, ++nextId);
                    createdMessages.add(message);
                    var result = new CompletableFuture<Message>();
                    createResults.add(result);
                    if (createFailure != null) result.completeExceptionally(createFailure);
                    else if (!delayCreates) { messages.put(nextId, message); result.complete(message); }
                    return result;
                });
                case "editMessageEmbedsById" -> action(m.getReturnType(), () -> {
                    var result = new CompletableFuture<Message>();
                    editResults.add(result);
                    if (!delayEdits) result.complete(messages.get(((Number) a[0]).longValue()));
                    return result;
                });
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }

        void completeCreate(int index) {
            var message = createdMessages.get(index);
            messages.put(message.getIdLong(), message);
            createResults.get(index).complete(message);
        }

        Message message(long channel, long id) { return message(channel, id, 500); }
        Message message(long channel, long id, long author) {
            User user = proxy(User.class, (o, m, a) -> author);
            return proxy(Message.class, (o, m, a) -> switch (m.getName()) {
                case "getIdLong" -> id;
                case "getChannelIdLong" -> channel;
                case "getAuthor" -> user;
                case "getEmbeds", "getComponents" -> List.of();
                case "getContentRaw" -> "";
                case "getComponentTree" -> MessageComponentTree.of(List.of(ActionRow.of(Button.primary("panel:playpause", "Play"))));
                case "delete" -> action(m.getReturnType(), () -> { deletes.add(id); messages.remove(id); return CompletableFuture.completedFuture(null); });
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }

        @SuppressWarnings("unchecked")
        Object action(Class<?> type, java.util.function.Supplier<CompletableFuture<?>> work) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (o, m, a) -> switch (m.getName()) {
                case "setComponents" -> o;
                case "submit" -> work.get();
                case "queue" -> {
                    work.get().whenComplete((value, failure) -> {
                        if (failure == null && a != null && a.length > 0 && a[0] != null) ((Consumer<Object>) a[0]).accept(value);
                        else if (failure != null && a != null && a.length > 1 && a[1] != null) ((Consumer<Throwable>) a[1]).accept(failure);
                    });
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    final class PlaybackInteraction {
        final String query;
        String menuId;
        int panelErrors;
        final User user = proxy(User.class, (o, m, a) -> m.getName().equals("getIdLong") ? 42L : "Requester");
        final net.dv8tion.jda.api.entities.GuildVoiceState voiceState = proxy(
                net.dv8tion.jda.api.entities.GuildVoiceState.class, (o, m, a) -> discord.voice);
        final net.dv8tion.jda.api.entities.Member member = proxy(net.dv8tion.jda.api.entities.Member.class,
                (o, m, a) -> switch (m.getName()) {
                    case "getVoiceState" -> voiceState;
                    case "getIdLong" -> 42L;
                    default -> "Requester";
                });
        final net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion channel = proxy(
                net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion.class, (o, m, a) -> switch (m.getName()) {
                    case "getIdLong" -> 20L;
                    case "asTextChannel" -> discord.channels.get(20L);
                    case "getType" -> net.dv8tion.jda.api.entities.channel.ChannelType.TEXT;
                    default -> null;
                });
        final net.dv8tion.jda.api.interactions.InteractionHook hook = proxy(
                net.dv8tion.jda.api.interactions.InteractionHook.class, (o, m, a) -> {
                    if (m.getName().equals("sendMessage")) panelErrors++;
                    return action(m.getReturnType(), proxy(Message.class, (msg, accessor, args) -> "12345"));
                });

        PlaybackInteraction(String query) { this.query = query; }

        net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction slash() {
            return proxy(net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction.class, this::interaction);
        }

        net.dv8tion.jda.api.interactions.components.selections.StringSelectInteraction selection() {
            return proxy(net.dv8tion.jda.api.interactions.components.selections.StringSelectInteraction.class, this::interaction);
        }

        Object interaction(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                case "getGuild" -> discord.guild;
                case "getChannel" -> channel;
                case "getChannelType" -> net.dv8tion.jda.api.entities.channel.ChannelType.TEXT;
                case "getUser" -> user;
                case "getMember" -> member;
                case "getHook" -> hook;
                case "getOptions" -> List.of(new net.dv8tion.jda.api.interactions.commands.OptionMapping(
                        net.dv8tion.jda.api.utils.data.DataObject.empty().put("name", "query").put("type", 3).put("value", query),
                        new gnu.trove.map.hash.TLongObjectHashMap<>(), null, discord.guild));
                case "getComponentId" -> menuId;
                case "getValues" -> List.of("0");
                case "deferReply", "deferEdit" -> action(method.getReturnType(), hook);
                default -> throw new AssertionError("Unexpected interaction method " + method.getName());
            };
        }

        @SuppressWarnings("unchecked")
        Object action(Class<?> type, Object result) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (o, m, a) -> {
                if (m.getName().equals("setComponents") && a[0] instanceof Object[] rows && rows.length > 0) {
                    var child = ((ActionRow) rows[0]).getComponents().getFirst();
                    if (child instanceof net.dv8tion.jda.api.components.selections.StringSelectMenu menu) menuId = menu.getCustomId();
                }
                if (m.getName().equals("setEphemeral")) assertEquals(true, a[0]);
                if (m.getName().equals("queue")) {
                    if (a != null && a.length > 0 && a[0] != null) ((Consumer<Object>) a[0]).accept(result);
                    return null;
                }
                return o;
            });
        }
    }

    static ErrorResponseException unknownMessage() {
        return ErrorResponseException.create(ErrorResponse.UNKNOWN_MESSAGE, new Response(new IOException("404"), Set.of()));
    }

    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
