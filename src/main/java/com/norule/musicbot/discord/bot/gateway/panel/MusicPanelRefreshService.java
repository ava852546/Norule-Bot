package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.exceptions.RateLimitedException;
import com.norule.musicbot.domain.music.MusicDiagnostics;
import com.norule.musicbot.domain.music.MusicPanelMode;
import com.norule.musicbot.discord.bot.gateway.DiscordRateLimitInterceptor;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicReference;

public final class MusicPanelRefreshService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicPanelRefreshService.class);

    private final MusicCommandService owner;
    private final MusicPanelStateStore stateStore;
    private final MusicPanelRenderer panelRenderer;
    private final MusicPanelRefreshCoordinator coordinator;
    private final long panelPeriodicRefreshMs;
    private final PanelRefreshFailurePolicy failurePolicy;
    private final MusicPanelModeResolver modes;
    private record PlayRequest(TextChannel channel, MusicPanelModeResolver.Selection mode, Consumer<String> onError) {}
    private final Map<Long, PlayRequest> playChannels = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public MusicPanelRefreshService(MusicCommandService owner,
                                    MusicPanelStateStore stateStore,
                                    MusicPanelRenderer panelRenderer,
                                    ScheduledExecutorService scheduler,
                                    long panelPeriodicRefreshMs, MusicPanelModeResolver modes) {
        this(owner, stateStore, panelRenderer, scheduler, panelPeriodicRefreshMs, modes, System::nanoTime);
    }

    MusicPanelRefreshService(MusicCommandService owner, MusicPanelStateStore stateStore, MusicPanelRenderer panelRenderer,
            ScheduledExecutorService scheduler, long panelPeriodicRefreshMs, MusicPanelModeResolver modes,
            LongSupplier nanoTime) {
        this.owner = owner;
        this.modes = modes;
        this.stateStore = stateStore;
        this.panelRenderer = panelRenderer;
        this.coordinator = new MusicPanelRefreshCoordinator(scheduler, this::prepareRefresh, nanoTime);
        this.panelPeriodicRefreshMs = panelPeriodicRefreshMs;
        this.failurePolicy = new PanelRefreshFailurePolicy();
    }

    private record Operation(long revision, MusicPanelModeResolver.Selection selection, String source) {}
    private record RecoveryAttempt(Operation operation, MusicPanelStateStore.PanelRef old, TextChannel target) {}
    private record SettingsSync(long revision, CompletableFuture<Void> result) {}
    private final Map<Long, SettingsSync> settingsSyncs = new ConcurrentHashMap<>();

    public void createPanelMessageWithFeedback(Guild guild, TextChannel channel, String lang, Runnable onSuccess, Consumer<String> onError) {
        recoverPanel(guild, channel, "music-panel").whenComplete((ignored, failure) -> {
            if (failure == null) onSuccess.run();
            else onError.accept(safeErrorMessage(failure));
        });
    }

    public CompletableFuture<Void> recoverPanel(Guild guild, TextChannel fallback, String source) {
        if (guild == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Guild is unavailable"));
        long guildId = guild.getIdLong();
        var attempt = new AtomicReference<RecoveryAttempt>();
        owner.backgroundRest().cancelPending("music-panel:" + guildId);
        return coordinator.submit(guildId, () -> {
            Operation operation = operation(guild, source);
            TextChannel target = operation.selection().modern() ? operation.selection().channel() : fallback;
            attempt.set(new RecoveryAttempt(operation, stateStore.getPanelRef(guildId), target));
            lifecycleLog("MUSIC_PANEL_RECOVERY_STARTED", guild, stateStore.getPanelRef(guildId), target, null, operation, "REQUESTED");
            return prepareRecovery(guild, target, operation);
        }).whenComplete((ignored, failure) -> {
            RecoveryAttempt completed = attempt.get();
            Operation current = completed == null ? operation(guild, source) : completed.operation();
            lifecycleLog(failure == null ? "MUSIC_PANEL_RECOVERY_SUCCEEDED" : "MUSIC_PANEL_RECOVERY_FAILED",
                    guild, completed == null ? null : completed.old(), completed == null ? fallback : completed.target(),
                    stateStore.getPanelRef(guildId), current,
                    failure == null ? "COMPLETED" : MusicPanelModeResolver.failureReason(failure) + ": " + safeErrorMessage(failure));
        });
    }

    private CompletableFuture<MusicPanelRefreshCoordinator.Update> prepareRecovery(Guild guild, TextChannel target, Operation operation) {
        requireCurrent(guild, operation);
        requireUsable(guild, target);
        long guildId = guild.getIdLong();
        var ref = stateStore.getPanelRef(guildId);
        if (ref != null && ref.channelId != target.getIdLong()) {
            return replaceManagedPanel(guild, target, !operation.selection().modern(), operation);
        }
        if (ref == null) {
            CompletableFuture<MusicPanelStateStore.PanelRef> resolved = new CompletableFuture<>();
            resolveOrCreatePanel(guild, target, owner.lang(guildId), resolved, operation);
            return resolved.thenApply(found -> recoveryUpdate(guild, target, found, operation));
        }
        // A manual recovery validates the actual message; unchanged snapshots cannot hide a deleted panel.
        coordinator.submitted(guildId);
        return DiscordRateLimitInterceptor.track(target.retrieveMessageById(ref.messageId),
                "MUSIC_PANEL_RECOVERY_VERIFY", false, false, "GET",
                "/channels/" + ref.channelId + "/messages/" + ref.messageId).submit()
                .handle((message, failure) -> {
                    requireCurrent(guild, operation);
                    if (failure != null) {
                        if (!isUnknownMessage(failure)) return CompletableFuture.<MusicPanelRefreshCoordinator.Update>failedFuture(failure);
                        invalidateReference(guild, ref, operation, "UNKNOWN_MESSAGE");
                        return createPanelInChannel(guild, target, operation);
                    }
                    if (!isMusicPanelMessage(guild, message)) return CompletableFuture.<MusicPanelRefreshCoordinator.Update>failedFuture(
                            new IllegalStateException("Stored message is not a managed music panel"));
                    return CompletableFuture.completedFuture(prepareVerifiedUpdate(guild, target, ref, message, operation));
                }).thenCompose(prepared -> prepared);
    }

    private MusicPanelRefreshCoordinator.Update recoveryUpdate(Guild guild, TextChannel target,
            MusicPanelStateStore.PanelRef ref, Operation operation) {
        requireCurrent(guild, operation);
        MusicPanelSnapshot snapshot = render(guild, owner.lang(guild.getIdLong()));
        return new MusicPanelRefreshCoordinator.Update(ref.channelId, ref.messageId, snapshot,
                () -> sendUpdate(guild.getIdLong(), target, ref, snapshot));
    }

    public void restorePanels() {
        JDA jda = owner.currentJda();
        if (jda != null) {
            MusicPanelController.initializeGuilds(jda.getGuilds(), guildId ->
                    owner.musicService().setGuildStateChangeListener(guildId,
                            reason -> requestRefresh(guildId, RefreshReason.valueOf(reason.name()))));
            LOGGER.info("[NoRule] Music panel startup: guilds={} restoredBindings={} startupRestTasks=0",
                    jda.getGuilds().size(), stateStore.snapshotGuildIds().size());
        }
    }

    /** Never provisions or adopts a command channel. One existing guild binding remains authoritative. */
    public CompletableFuture<String> sendDeveloperPanel(TextChannel channel) {
        Guild guild = channel.getGuild();
        long guildId = guild.getIdLong();
        owner.backgroundRest().cancelPending("music-panel:" + guildId);
        long[] originalMessageId = {0L};
        return coordinator.submit(guildId, () -> {
            Operation operation = operation(guild, "developer");
            Permission missing = missingRefreshPermission(guild, channel);
            if (missing == null && !guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_HISTORY)) {
                missing = Permission.MESSAGE_HISTORY;
            }
            if (missing != null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Missing permissions to send music panel in this channel: " + missing.getName()));
            }
            MusicPanelStateStore.PanelRef ref = stateStore.getPanelRef(guildId);
            if (ref != null && ref.channelId != channel.getIdLong()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "This guild already has a music panel bound to channel " + ref.channelId
                                + ". Use that channel; only one music panel per guild is supported."));
            }
            originalMessageId[0] = ref == null ? 0L : ref.messageId;
            if (ref == null) {
                // One-time migration for panels created before durable bindings existed.
                // A failed history read never authorizes another message.
                return DiscordRateLimitInterceptor.track(channel.getHistory().retrievePast(50),
                        "MUSIC_PANEL_DEV_RECOVERY", false, false,
                        "GET", "/channels/" + channel.getId() + "/messages").submit().thenCompose(messages -> {
                    requireCurrent(guild, operation);
                    Message recovered = messages.stream().filter(message -> isMusicPanelMessage(guild, message))
                            .max(Comparator.comparingLong(Message::getIdLong)).orElse(null);
                    if (recovered == null) {
                        return createPanelInChannel(guild, channel, operation);
                    }
                    originalMessageId[0] = recovered.getIdLong();
                    MusicPanelStateStore.PanelRef recoveredRef = new MusicPanelStateStore.PanelRef(
                            channel.getIdLong(), recovered.getIdLong());
                    return CompletableFuture.completedFuture(prepareVerifiedUpdate(guild, channel, recoveredRef, recovered, operation));
                });
            }
            return DiscordRateLimitInterceptor.track(channel.retrieveMessageById(ref.messageId),
                    "MUSIC_PANEL_DEV_VERIFY", false, false,
                    "GET", "/channels/" + ref.channelId + "/messages/" + ref.messageId).submit().handle((message, failure) -> {
                requireCurrent(guild, operation);
                if (failure != null) {
                    if (isUnknownMessage(failure)) {
                        return createPanelInChannel(guild, channel, operation);
                    }
                    return CompletableFuture.<MusicPanelRefreshCoordinator.Update>failedFuture(failure);
                }
                if (!isMusicPanelMessage(guild, message)) {
                    return CompletableFuture.<MusicPanelRefreshCoordinator.Update>failedFuture(new IllegalStateException(
                            "The stored message is not a music panel owned by this bot; binding retained."));
                }
                return CompletableFuture.completedFuture(prepareVerifiedUpdate(guild, channel, ref, message, operation));
            }).thenCompose(prepared -> prepared);
        }).thenApply(ignored -> {
            MusicPanelStateStore.PanelRef ref = stateStore.getPanelRef(guildId);
            String action = originalMessageId[0] == 0 ? "created"
                    : originalMessageId[0] == ref.messageId ? "updated" : "recreated";
            LOGGER.info("[NoRule] Music panel {}: guildId={} channelId={} oldMessageId={} messageId={} source=DEV_COMMAND",
                    action, guildId, ref.channelId, originalMessageId[0], ref.messageId);
            String summary = "recreated".equals(action)
                    ? "Previous music panel was deleted.\nA new music panel has been created successfully."
                    : "Music panel " + action + " successfully.";
            return "[NoRule] " + summary + "\nGuild: " + guild.getName() + "\nChannel: " + channel.getAsMention()
                    + "\nMessage ID: " + ref.messageId + "\nhttps://discord.com/channels/" + guildId + "/" + ref.channelId + "/" + ref.messageId;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                LOGGER.warn("[NoRule] Music panel command failed: guildId={} channelId={} reason={}",
                        guildId, channel.getIdLong(), safeErrorMessage(failure));
            }
        });
    }

    private MusicPanelRefreshCoordinator.Update prepareVerifiedUpdate(Guild guild, TextChannel channel,
                                                                       MusicPanelStateStore.PanelRef ref, Message message, Operation operation) {
        long guildId = guild.getIdLong();
        activatePanel(guild, ref, stateStore.getLastRefreshAt(guildId), operation);
        MusicPanelSnapshot snapshot = render(guild, owner.lang(guildId));
        // Seed comparison from the actual Discord payload, including after a restart.
        coordinator.recordCreated(guildId, null);
        if (message.getEmbeds().size() == 1 && message.getContentRaw().isEmpty()
                && message.getComponents().stream().allMatch(ActionRow.class::isInstance)
                && snapshot.sameContent(new MusicPanelSnapshot(message.getEmbeds().getFirst(), message.getComponents().stream()
                        .map(ActionRow.class::cast).toList()))) {
            coordinator.recordCreated(guildId, new MusicPanelRefreshCoordinator.Update(
                    ref.channelId, ref.messageId, snapshot, null));
        }
        return new MusicPanelRefreshCoordinator.Update(
                ref.channelId, ref.messageId, snapshot, () -> sendUpdate(guildId, channel, ref, snapshot));
    }

    private CompletableFuture<MusicPanelRefreshCoordinator.Update> createPanelInChannel(Guild guild, TextChannel channel,
            Operation operation) {
        CompletableFuture<MusicPanelStateStore.PanelRef> created = new CompletableFuture<>();
        sendNewPanel(guild, channel, owner.lang(guild.getIdLong()), created, operation);
        return created.thenApply(ref -> null);
    }

    static boolean isUnknownMessage(Throwable failure) {
        return hasResponse(failure, ErrorResponse.UNKNOWN_MESSAGE);
    }

    private static boolean hasResponse(Throwable failure, ErrorResponse expected) {
        while (failure != null) {
            if (failure instanceof ErrorResponseException response) {
                return response.getErrorResponse() == expected;
            }
            if (failure.getCause() == failure) {
                break;
            }
            failure = failure.getCause();
        }
        return false;
    }

    private void resolveOrCreatePanel(Guild guild, TextChannel channel, String lang,
            CompletableFuture<MusicPanelStateStore.PanelRef> result, Operation operation) {
        requireCurrent(guild, operation);
        Permission missingPermission = missingRefreshPermission(guild, channel);
        if (missingPermission != null) {
            logOperationalFailure(guild.getIdLong(), channel.getIdLong(), 0L,
                    "MISSING_PERMISSION", missingPermission);
            String missing = owner.formatMissingPermissionsForPanel(guild.getSelfMember(), channel,
                    Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
            result.completeExceptionally(new IllegalStateException(
                    owner.i18nService().t(lang, "general.missing_permissions", Map.of("permissions", missing))
            ));
            return;
        }
        if (guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_HISTORY)) {
            DiscordRateLimitInterceptor.track(
                    channel.getHistory().retrievePast(50), "MUSIC_PANEL_RECOVERY", false, false,
                    "GET", "/channels/" + channel.getId() + "/messages").queue(
                    messages -> recoverPanelOrCreate(guild, channel, lang, messages, result, operation),
                    failure -> {
                        LOGGER.debug(
                                "[NoRule] Music panel history recovery skipped: guildId={} channelId={} reason={}",
                                guild.getIdLong(),
                                channel.getIdLong(),
                                safeErrorMessage(failure)
                        );
                        result.completeExceptionally(failure);
                    }
            );
        } else {
            sendNewPanel(guild, channel, lang, result, operation);
        }
    }

    private void recoverPanelOrCreate(Guild guild,
                                      TextChannel channel,
                                      String lang,
                                      java.util.List<Message> messages,
                                      CompletableFuture<MusicPanelStateStore.PanelRef> result, Operation operation) {
        if (closed || result.isDone()) {
            return;
        }
        if (!current(guild, operation)) { result.completeExceptionally(superseded(guild)); return; }
        Message recovered = messages.stream()
                .filter(message -> isMusicPanelMessage(guild, message))
                .max(Comparator.comparingLong(Message::getIdLong))
                .orElse(null);
        if (recovered == null) {
            sendNewPanel(guild, channel, lang, result, operation);
            return;
        }

        MusicPanelStateStore.PanelRef panelRef = new MusicPanelStateStore.PanelRef(
                channel.getIdLong(),
                recovered.getIdLong()
        );
        synchronized (result) {
            if (closed || result.isDone()) {
                return;
            }
            try {
                activatePanel(guild, panelRef, 0L, operation);
                coordinator.recordCreated(guild.getIdLong(), null);
                result.complete(panelRef);
            } catch (RuntimeException persistenceFailure) {
                modes.log("MUSIC_PANEL_CREATE_FAILED", guild, panelRef.channelId, panelRef.messageId, "PERSISTENCE_FAILURE");
                result.completeExceptionally(persistenceFailure);
            }
        }
        LOGGER.debug(
                "[NoRule] Music panel recovered: guildId={} channelId={} messageId={}",
                guild.getIdLong(),
                channel.getIdLong(),
                recovered.getIdLong()
        );
    }

    private void sendNewPanel(Guild guild, TextChannel channel, String lang,
            CompletableFuture<MusicPanelStateStore.PanelRef> result, Operation operation) {
        if (closed || result.isDone()) {
            return;
        }
        try {
            requireCurrent(guild, operation);
            requireUsable(guild, channel);
            coordinator.submitted(guild.getIdLong());
            MusicPanelSnapshot snapshot = render(guild, lang);
            DiscordRateLimitInterceptor.track(
                    channel.sendMessageEmbeds(snapshot.embed()).setComponents(snapshot.components()),
                    "MUSIC_PANEL_CREATE", false, false, "POST", "/channels/" + channel.getId() + "/messages")
                    .queue(message -> {
                        if (!current(guild, operation) || result.isDone()) {
                            discardCreatedPanel(guild, message, operation, result);
                            return;
                        }
                        MusicPanelStateStore.PanelRef panelRef = new MusicPanelStateStore.PanelRef(
                                channel.getIdLong(),
                                message.getIdLong()
                        );
                        synchronized (result) {
                            if (closed || result.isDone()) {
                                return;
                            }
                            try {
                                activatePanel(guild, panelRef, System.currentTimeMillis(), operation);
                            } catch (RuntimeException persistenceFailure) {
                                modes.log("MUSIC_PANEL_CREATE_FAILED", guild, panelRef.channelId, panelRef.messageId, "PERSISTENCE_FAILURE");
                                discardCreatedPanel(guild, message, operation, result);
                                return;
                            }
                            coordinator.recordCreated(guild.getIdLong(), new MusicPanelRefreshCoordinator.Update(
                                    panelRef.channelId, panelRef.messageId, snapshot, null));
                            result.complete(panelRef);
                        }
                        failurePolicy.clearChannel(guild.getIdLong(), channel.getIdLong());
                        LOGGER.debug(
                                "[NoRule] Music panel created: guildId={} channelId={} messageId={}",
                                guild.getIdLong(),
                                channel.getIdLong(),
                                message.getIdLong()
                        );
                    }, failure -> {
                        modes.log("MUSIC_PANEL_CREATE_FAILED", guild, channel.getIdLong(), 0, MusicPanelModeResolver.failureReason(failure));
                        handlePanelFailure(guild.getIdLong(), channel.getIdLong(), 0L, failure, false);
                        result.completeExceptionally(failure);
                    });
        } catch (RuntimeException failure) {
            modes.log("MUSIC_PANEL_CREATE_FAILED", guild, channel.getIdLong(), 0, MusicPanelModeResolver.failureReason(failure));
            handlePanelFailure(guild.getIdLong(), channel.getIdLong(), 0L, failure, false);
            result.completeExceptionally(failure);
        }
    }

    private void activatePanel(Guild guild, MusicPanelStateStore.PanelRef ref, long refreshedAt, Operation operation) {
        requireCurrent(guild, operation);
        long guildId = guild.getIdLong();
        if (!stateStore.activateIfCurrent(guildId, operation.revision(), ref, refreshedAt)) throw superseded(guild);
        owner.musicService().setGuildStateChangeListener(guildId,
                reason -> requestRefresh(guildId, RefreshReason.valueOf(reason.name())));
    }

    private Operation operation(Guild guild, String source) {
        return new Operation(stateStore.revision(guild.getIdLong()), modes.resolve(guild), source);
    }

    private boolean current(Guild guild, Operation operation) {
        var selection = modes.inspect(guild);
        return !closed && stateStore.revision(guild.getIdLong()) == operation.revision()
                && selection.configured() == operation.selection().configured()
                && Objects.equals(selection.channelId(), operation.selection().channelId());
    }

    private void requireCurrent(Guild guild, Operation operation) {
        if (!current(guild, operation)) throw superseded(guild);
    }

    private IllegalStateException superseded(Guild guild) {
        return new IllegalStateException(owner.musicText(owner.lang(guild.getIdLong()), "panel_operation_superseded"));
    }

    private void requireUsable(Guild guild, TextChannel channel) {
        if (channel == null) throw new IllegalStateException(owner.musicText(owner.lang(guild.getIdLong()), "panel_text_channel_only"));
        if (!MusicPanelModeResolver.usable(channel)) throw new IllegalStateException(owner.i18nService().t(
                owner.lang(guild.getIdLong()), "general.missing_permissions", Map.of("permissions",
                owner.formatMissingPermissionsForPanel(guild.getSelfMember(), channel,
                        Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS))));
    }

    private void invalidateReference(Guild guild, MusicPanelStateStore.PanelRef ref, Operation operation, String reason) {
        if (stateStore.forgetPanel(guild.getIdLong(), ref)) {
            coordinator.recordCreated(guild.getIdLong(), null);
            lifecycleLog("MUSIC_PANEL_STATE_INVALIDATED", guild, ref, null, null, operation, reason);
        }
    }

    private void discardCreatedPanel(Guild guild, Message message, Operation operation,
            CompletableFuture<MusicPanelStateStore.PanelRef> result) {
        // This callback owns the just-created message; never delete a currently active binding.
        if (stateStore.isActivePanel(guild.getIdLong(), message.getChannelIdLong(), message.getIdLong())) {
            result.completeExceptionally(superseded(guild));
            return;
        }
        try {
            DiscordRateLimitInterceptor.track(message.delete(), "MUSIC_PANEL_STALE_CREATE_CLEANUP", false, false,
                    "DELETE", "/channels/" + message.getChannelIdLong() + "/messages/" + message.getIdLong()).submit()
                    .whenComplete((ignored, failure) -> {
                        lifecycleLog("MUSIC_PANEL_STATE_INVALIDATED", guild, null, null, null, operation,
                                failure == null ? "STALE_CREATE_REMOVED" : MusicPanelModeResolver.failureReason(failure));
                        if (failure != null) LOGGER.warn("Stale music panel cleanup failed: guildId={} messageId={}",
                                guild.getIdLong(), message.getIdLong(), failure);
                        result.completeExceptionally(superseded(guild));
                    });
        } catch (RuntimeException failure) {
            LOGGER.warn("Stale music panel cleanup failed: guildId={} messageId={}", guild.getIdLong(), message.getIdLong(), failure);
            result.completeExceptionally(failure);
        }
    }

    private void lifecycleLog(String event, Guild guild, MusicPanelStateStore.PanelRef old, TextChannel target,
            MusicPanelStateStore.PanelRef next, Operation operation, String reason) {
        LOGGER.info("event={} guildId={} oldChannelId={} newChannelId={} oldMessageId={} newMessageId={} configuredMode={} effectiveMode={} reason={} source={} revision={}",
                event, guild.getIdLong(), old == null ? 0 : old.channelId,
                target == null ? operation.selection().channelId() : target.getIdLong(), old == null ? 0 : old.messageId,
                next == null ? 0 : next.messageId, operation.selection().configured(), operation.selection().effective(),
                reason, operation.source(), operation.revision());
    }

    private static boolean isMusicPanelMessage(Guild guild, Message message) {
        return message != null
                && message.getAuthor().getIdLong() == guild.getSelfMember().getIdLong()
                && message.getComponentTree()
                .find(Button.class, button -> MusicCommandService.PANEL_PLAY_PAUSE.equals(button.getCustomId()))
                .isPresent();
    }

    public void refreshPanel(long guildId) {
        requestRefresh(guildId, RefreshReason.MANUAL);
    }

    public void requestRefresh(long guildId, RefreshReason reason) {
        if (reason.highPriority()) {
            // A queued progress update must not hold this guild's barrier ahead of /play.
            owner.backgroundRest().cancelPending("music-panel:" + guildId);
        }
        coordinator.requestRefresh(guildId, reason);
    }

    public void refreshPanelPeriodic(long guildId) {
        JDA jda = owner.currentJda();
        Guild guild = jda == null ? null : jda.getGuildById(guildId);
        if (guild == null) {
            // A cache miss during an outage is not evidence that the bot left the guild.
            return;
        }
        if (progressRefreshDue(isProgressActive(guild), stateStore.getLastRefreshAt(guildId),
                System.currentTimeMillis(), panelPeriodicRefreshMs)) {
            requestRefresh(guildId, RefreshReason.PERIODIC_REFRESH);
        }
    }

    static boolean progressRefreshDue(boolean active, long lastSuccessMillis, long nowMillis, long intervalMillis) {
        return active && nowMillis - lastSuccessMillis >= intervalMillis;
    }

    public void refreshAllPanelsSafely() {
        for (long guildId : stateStore.snapshotGuildIds()) {
            try {
                refreshPanelPeriodic(guildId);
            } catch (RuntimeException failure) {
                LOGGER.error("[NoRule] Music panel periodic refresh failed: guildId={}", guildId, failure);
            }
        }
    }

    private boolean isProgressActive(Guild guild) {
        return owner.musicService().getCurrentTitle(guild) != null
                && !owner.musicService().isPaused(guild)
                && guild.getAudioManager().getConnectedChannel() != null;
    }

    public CompletableFuture<Void> settingsChanged(long guildId) {
        JDA jda = owner.currentJda();
        Guild guild = jda == null ? null : jda.getGuildById(guildId);
        stateStore.invalidate(guildId);
        coordinator.clear(guildId); // Keeps the in-flight barrier until the old REST work and cleanup finish.
        playChannels.remove(guildId);
        if (guild == null) return CompletableFuture.completedFuture(null);
        owner.backgroundRest().cancelPending("music-panel:" + guildId);
        Operation operation = operation(guild, "settings");
        var old = stateStore.getPanelRef(guildId);
        lifecycleLog("MUSIC_PANEL_CHANNEL_CHANGED", guild, old, operation.selection().channel(), null, operation, "SETTINGS_UPDATED");
        if (old != null) lifecycleLog("MUSIC_PANEL_STATE_INVALIDATED", guild, old, operation.selection().channel(), null, operation, "SETTINGS_UPDATED");
        if (old != null && guild.getTextChannelById(old.channelId) == null) {
            invalidateReference(guild, old, operation, "UNKNOWN_CHANNEL");
        }
        CompletableFuture<Void> result = operation.selection().configured() == MusicPanelMode.MODERN
                ? recoverPanel(guild, operation.selection().channel(), "settings")
                : CompletableFuture.completedFuture(null);
        settingsSyncs.put(guildId, new SettingsSync(operation.revision(), result));
        return result;
    }

    public CompletableFuture<Void> synchronizeSettings(Guild guild) {
        return synchronizeSettings(guild, modes.resolve(guild).channelId());
    }

    public CompletableFuture<Void> synchronizeSettings(Guild guild, Long expectedChannelId) {
        var mode = modes.resolve(guild);
        if (!Objects.equals(mode.channelId(), expectedChannelId)) return CompletableFuture.failedFuture(superseded(guild));
        SettingsSync sync = settingsSyncs.get(guild.getIdLong());
        CompletableFuture<Void> result = sync != null && sync.revision() == stateStore.revision(guild.getIdLong())
                ? sync.result() : mode.configured() == MusicPanelMode.MODERN
                ? recoverPanel(guild, mode.channel(), "settings") : CompletableFuture.completedFuture(null);
        return result.thenRun(() -> {
            var current = modes.resolve(guild);
            if (!Objects.equals(current.channelId(), expectedChannelId) || current.configured() != mode.configured()) throw superseded(guild);
        });
    }

    public void playQueued(Guild guild, TextChannel channel) {
        playQueued(guild, channel, modes.resolve(guild), error -> {});
    }

    public void playQueued(Guild guild, TextChannel channel, MusicPanelModeResolver.Selection mode, Consumer<String> onError) {
        playChannels.put(guild.getIdLong(), new PlayRequest(channel, mode, onError));
        requestRefresh(guild.getIdLong(), RefreshReason.PLAY_ADDED);
    }

    private CompletableFuture<MusicPanelRefreshCoordinator.Update> prepareRefresh(long guildId, Set<RefreshReason> reasons) {
        PlayRequest play = reasons.contains(RefreshReason.PLAY_ADDED) ? playChannels.remove(guildId) : null;
        CompletableFuture<MusicPanelRefreshCoordinator.Update> result;
        try {
            result = prepareRefresh(guildId, reasons, play);
        } catch (RuntimeException failure) {
            result = CompletableFuture.failedFuture(failure);
        }
        return result.whenComplete((update, failure) -> {
            if (play != null && failure != null) {
                LOGGER.warn("Music panel after playback failed: guildId={} channelId={} reason={}", guildId,
                        play.channel() == null ? 0 : play.channel().getIdLong(), MusicPanelModeResolver.failureReason(failure), failure);
                play.onError().accept(safeErrorMessage(failure));
            }
        });
    }

    private CompletableFuture<MusicPanelRefreshCoordinator.Update> prepareRefresh(long guildId, Set<RefreshReason> reasons, PlayRequest play) {
        JDA jda = owner.currentJda();
        Guild guild = jda == null ? null : jda.getGuildById(guildId);
        if (guild == null || closed) {
            playChannels.remove(guildId);
            return CompletableFuture.completedFuture(null);
        }
        var mode = modes.resolve(guild);
        // Channel access can recover while the search/load callback is pending;
        // the resolver remains authoritative for the current effective mode.
        if (play != null && (play.mode().configured() != mode.configured()
                || !Objects.equals(play.mode().channelId(), mode.channelId()))) {
            // A late /play completion from an earlier configuration may refresh the
            // current panel, but must never delete or migrate its replacement.
            play = null;
        }
        MusicPanelStateStore.PanelRef ref = stateStore.getPanelRef(guildId);
        if (!mode.modern() && play != null) {
            TextChannel target = play.channel();
            if (!MusicPanelModeResolver.usable(target)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        target == null ? owner.musicText(owner.lang(guildId), "panel_text_channel_only")
                                : owner.i18nService().t(owner.lang(guildId), "general.missing_permissions", Map.of("permissions",
                                owner.formatMissingPermissionsForPanel(guild.getSelfMember(), target,
                                        Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)))));
            }
            return replaceManagedPanel(guild, target, true);
        }
        boolean periodicOnly = reasons.equals(Set.of(RefreshReason.PERIODIC_REFRESH));
        // Maintenance never provisions or migrates panels based on a cache miss.
        if (periodicOnly && mode.modern() && (ref == null || ref.channelId != mode.channelId())) {
            return CompletableFuture.completedFuture(null);
        }
        if (mode.modern() && (ref == null || ref.channelId != mode.channelId())) {
            return replaceManagedPanel(guild, mode.channel(), false);
        }
        // Legacy state changes may edit an existing panel, but never create one.
        if (ref == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (periodicOnly && !progressRefreshDue(isProgressActive(guild), stateStore.getLastRefreshAt(guildId),
                System.currentTimeMillis(), panelPeriodicRefreshMs)) {
            return CompletableFuture.completedFuture(null);
        }
        TextChannel channel = guild.getTextChannelById(ref.channelId);
        if (channel == null) {
            logOperationalFailure(guildId, ref.channelId, ref.messageId, "UNKNOWN_CHANNEL", null);
            invalidateReference(guild, ref, operation(guild, "background"), "UNKNOWN_CHANNEL");
            return CompletableFuture.completedFuture(null);
        }
        Permission missingPermission = missingRefreshPermission(guild, channel);
        if (missingPermission != null) {
            logOperationalFailure(guildId, ref.channelId, ref.messageId, "MISSING_PERMISSION", missingPermission);
            return CompletableFuture.completedFuture(null);
        }
        MusicPanelSnapshot snapshot = render(guild, owner.lang(guildId));
        boolean backgroundOnly = reasons.stream().noneMatch(RefreshReason::highPriority);
        return CompletableFuture.completedFuture(new MusicPanelRefreshCoordinator.Update(
                ref.channelId, ref.messageId, snapshot, () -> backgroundOnly
                        ? owner.backgroundRest().submit("music-panel:" + guildId,
                                () -> sendLatestBackground(guild, channel, ref, reasons))
                        : sendUpdate(guildId, channel, ref, snapshot, false, reasons)));
    }

    private CompletableFuture<Void> sendLatestBackground(Guild guild, TextChannel channel,
            MusicPanelStateStore.PanelRef ref, Set<RefreshReason> reasons) {
        long guildId = guild.getIdLong();
        if (closed || !stateStore.isActivePanel(guildId, ref.channelId, ref.messageId)
                || (reasons.equals(Set.of(RefreshReason.PERIODIC_REFRESH)) && !isProgressActive(guild))) {
            // Invalidate the queued payload; it was never delivered to Discord.
            coordinator.discardPrepared(guildId);
            return CompletableFuture.completedFuture(null);
        }
        // Admission may have waited for a shared/global cooldown. Never send the queued snapshot.
        MusicPanelSnapshot latest = render(guild, owner.lang(guildId));
        var delivered = new MusicPanelRefreshCoordinator.Update(ref.channelId, ref.messageId, latest, null);
        if (coordinator.sameContent(guildId, delivered)) {
            coordinator.recordCreated(guildId, delivered);
            return CompletableFuture.completedFuture(null);
        }
        return sendUpdate(guildId, channel, ref, latest, true, reasons).thenRun(() -> {
            if (stateStore.isActivePanel(guildId, ref.channelId, ref.messageId)) {
                coordinator.recordCreated(guildId, delivered);
            }
        });
    }

    private CompletableFuture<MusicPanelRefreshCoordinator.Update> replaceManagedPanel(Guild guild, TextChannel target, boolean legacy) {
        return replaceManagedPanel(guild, target, legacy, operation(guild, legacy ? "play" : "background"));
    }

    private CompletableFuture<MusicPanelRefreshCoordinator.Update> replaceManagedPanel(Guild guild, TextChannel target,
            boolean legacy, Operation operation) {
        requireCurrent(guild, operation);
        MusicPanelStateStore.PanelRef old = stateStore.getPanelRef(guild.getIdLong());
        coordinator.submitted(guild.getIdLong());
        return releasePanelForReplacement(guild, old, modes).thenCompose(deleted -> {
            requireCurrent(guild, operation);
            if (closed || !deleted) {
                return closed ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(
                        new IllegalStateException("Could not remove the previous managed music panel"));
            }
            // Recheck settings after asynchronous deletion: a switch must not create at a stale destination.
            var current = modes.resolve(guild);
            if (!current.modern() && !legacy) {
                return CompletableFuture.completedFuture(null);
            }
            TextChannel destination = current.modern() ? current.channel() : target;
            if (!MusicPanelModeResolver.usable(destination)) {
                modes.log("MUSIC_PANEL_CREATE_FAILED", guild, target.getIdLong(), 0, "MISSING_ACCESS");
                return CompletableFuture.failedFuture(new IllegalStateException("Music panel channel is unavailable"));
            }
            CompletableFuture<MusicPanelStateStore.PanelRef> created = new CompletableFuture<>();
            if (old != null && !(current.fallback() && Objects.equals(current.channelId(), old.channelId))) {
                invalidateReference(guild, old, operation, "REPLACED");
            }
            if (current.modern() && (old == null || old.channelId != destination.getIdLong())) {
                resolveOrCreatePanel(guild, destination, owner.lang(guild.getIdLong()), created, operation);
            } else {
                sendNewPanel(guild, destination, owner.lang(guild.getIdLong()), created, operation);
            }
            return created.thenApply(ref -> {
                if (legacy && !current.modern()) {
                    modes.log("MUSIC_PANEL_LEGACY_RECREATED", guild, ref.channelId, ref.messageId, "PLAY_ADDED");
                }
                return recoveryUpdate(guild, destination, ref, operation);
            });
        });
    }

    static CompletableFuture<Boolean> releasePanelForReplacement(Guild guild, MusicPanelStateStore.PanelRef old,
                                                                 MusicPanelModeResolver modes) {
        var current = modes.resolve(guild);
        // An unavailable binding cannot veto a new destination. Keep the configured
        // MODERN panel intact during fallback so it can be used again after recovery.
        if (old == null || guild.getTextChannelById(old.channelId) == null
                || current.fallback() && Objects.equals(current.channelId(), old.channelId)) {
            return CompletableFuture.completedFuture(true);
        }
        return deleteManagedPanel(guild, old, modes);
    }

    static CompletableFuture<Boolean> deleteManagedPanel(Guild guild, MusicPanelStateStore.PanelRef ref, MusicPanelModeResolver modes) {
        if (ref == null) {
            return CompletableFuture.completedFuture(true);
        }
        TextChannel channel = guild.getTextChannelById(ref.channelId);
        if (channel == null) {
            modes.log("MUSIC_PANEL_DELETE_FAILED", guild, ref.channelId, ref.messageId, "UNKNOWN_CHANNEL");
            return CompletableFuture.completedFuture(false);
        }
        try {
            // A persisted ID alone never authorizes deleting another user's message.
            return DiscordRateLimitInterceptor.track(
                    channel.retrieveMessageById(ref.messageId), "MUSIC_PANEL_DELETE_VERIFY", false, false,
                    "GET", "/channels/" + ref.channelId + "/messages/" + ref.messageId).submit().thenCompose(message -> {
                if (!isMusicPanelMessage(guild, message)) {
                    modes.log("MUSIC_PANEL_DELETE_FAILED", guild, ref.channelId, ref.messageId, "NOT_MANAGED_PANEL");
                    return CompletableFuture.completedFuture(false);
                }
                return DiscordRateLimitInterceptor.track(
                        message.delete(), "MUSIC_PANEL_DELETE", false, false,
                        "DELETE", "/channels/" + ref.channelId + "/messages/" + ref.messageId).submit().thenApply(ignored -> true);
            }).handle((deleted, failure) -> {
                if (failure != null && !isUnknownMessage(failure) && !hasResponse(failure, ErrorResponse.UNKNOWN_CHANNEL)) {
                    modes.log("MUSIC_PANEL_DELETE_FAILED", guild, ref.channelId, ref.messageId,
                            MusicPanelModeResolver.failureReason(failure));
                }
                return failure == null ? deleted : isUnknownMessage(failure) || hasResponse(failure, ErrorResponse.UNKNOWN_CHANNEL);
            });
        } catch (RuntimeException failure) {
            modes.log("MUSIC_PANEL_DELETE_FAILED", guild, ref.channelId, ref.messageId,
                    MusicPanelModeResolver.failureReason(failure));
            return CompletableFuture.completedFuture(isUnknownMessage(failure) || hasResponse(failure, ErrorResponse.UNKNOWN_CHANNEL));
        }
    }

    private MusicPanelSnapshot render(Guild guild, String lang) {
        return new MusicPanelSnapshot(panelRenderer.panelEmbed(guild, lang).build(),
                panelRenderer.panelRows(lang, guild.getIdLong()));
    }

    private CompletableFuture<Void> sendUpdate(long guildId, TextChannel channel,
                                               MusicPanelStateStore.PanelRef ref, MusicPanelSnapshot snapshot) {
        return sendUpdate(guildId, channel, ref, snapshot, false, Set.of(RefreshReason.MANUAL));
    }

    private CompletableFuture<Void> sendUpdate(long guildId, TextChannel channel,
            MusicPanelStateStore.PanelRef ref, MusicPanelSnapshot snapshot, boolean background, Set<RefreshReason> reasons) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        JDA jda = owner.currentJda();
        Guild guild = jda == null ? null : jda.getGuildById(guildId);
        if (guild == null || !stateStore.isActivePanel(guildId, ref.channelId, ref.messageId)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Music panel operation is stale"));
        }
        Operation operation = operation(guild, background ? "background" : "music-panel");
        try {
            // Measure from actual REST admission, including after a long background cooldown.
            coordinator.submitted(guildId);
            DiscordRateLimitInterceptor.track(
                    channel.editMessageEmbedsById(ref.messageId, snapshot.embed())
                            .setComponents(snapshot.components()), "MUSIC_PANEL_REFRESH", background, false,
                    "PATCH", "/channels/" + ref.channelId + "/messages/" + ref.messageId,
                    () -> "candidateGuildId=" + guildId + " candidateRefreshReason=" + reasons
                            + " " + coordinator.diagnostics(guildId)
                                    .replace("pendingRefresh=", "candidatePendingRefresh=")
                                    .replace("inFlight=", "candidateInFlight=")
                                    .replace("coalescedCount=", "candidateCoalescedCount="))
                    .queue(success -> {
                        if (current(guild, operation) && stateStore.isActivePanel(guildId, ref.channelId, ref.messageId)) {
                            stateStore.markRefreshed(guildId, ref, System.currentTimeMillis());
                            failurePolicy.clearChannel(guildId, ref.channelId);
                        }
                        result.complete(null);
                    }, failure -> {
                        JDA currentJda = owner.currentJda();
                        Guild currentGuild = currentJda == null ? null : currentJda.getGuildById(guildId);
                        var mode = currentGuild == null ? null : modes.resolve(currentGuild);
                        if (currentGuild == null || !current(currentGuild, operation)) {
                            result.completeExceptionally(superseded(guild));
                            return;
                        }
                        if (isUnknownMessage(failure) && stateStore.isActivePanel(guildId, ref.channelId, ref.messageId)
                                && !background && mode != null && mode.modern() && mode.channelId() == channel.getIdLong()) {
                            invalidateReference(currentGuild, ref, operation, "UNKNOWN_MESSAGE");
                            createPanelInChannel(currentGuild, channel, operation).whenComplete((created, createFailure) -> {
                                if (createFailure == null) {
                                    result.complete(null);
                                } else {
                                    result.completeExceptionally(createFailure);
                                }
                            });
                            return;
                        }
                        if (isUnknownMessage(failure)) {
                            if (currentGuild != null && current(currentGuild, operation)) {
                                invalidateReference(currentGuild, ref, operation, "UNKNOWN_MESSAGE");
                            }
                            result.completeExceptionally(failure);
                            return;
                        }
                        handlePanelFailure(guildId, ref.channelId, ref.messageId, failure, true);
                        result.completeExceptionally(failure);
                    });
        } catch (RuntimeException failure) {
            handlePanelFailure(guildId, ref.channelId, ref.messageId, failure, true);
            result.completeExceptionally(failure);
        }
        return result;
    }

    public void clearPanel(long guildId) {
        owner.backgroundRest().cancelPending("music-panel:" + guildId);
        playChannels.remove(guildId);
        settingsSyncs.remove(guildId);
        stateStore.forgetGuild(guildId);
        coordinator.clear(guildId);
    }

    public void clearPanel(long guildId, long channelId, long messageId) {
        // Deletion events invalidate comparison, but only a REST 10008 authorizes replacement.
        if (stateStore.isActivePanel(guildId, channelId, messageId)) {
            coordinator.recordCreated(guildId, null);
        }
    }

    public record Diagnostics(MusicPanelModeResolver.Selection mode, MusicPanelStateStore.PanelRef panel,
                              long refreshedAt, boolean pending, MusicDiagnostics.Error error) {}
    public void observeRateLimit(String method, String path) {
        if (!"PATCH".equals(method) || !path.matches("/channels/[0-9]+/messages/[0-9]+")) return;
        // This proves only a managed-message route hit 429; it cannot prove the originating feature.
        stateStore.panelRefs().forEach((guildId, ref) -> {
            if (path.equals("/channels/" + Long.toUnsignedString(ref.channelId) + "/messages/" + Long.toUnsignedString(ref.messageId))) {
                stateStore.recordDiagnosticError(guildId, "PANEL_ROUTE_429");
            }
        });
    }

    public Diagnostics diagnostics(Guild guild) {
        return new Diagnostics(modes.inspect(guild), stateStore.getPanelRef(guild.getIdLong()),
                stateStore.getLastRefreshAt(guild.getIdLong()), coordinator.hasPending(guild.getIdLong()),
                stateStore.diagnosticError(guild.getIdLong()));
    }

    public void close() {
        closed = true;
        coordinator.close();
        settingsSyncs.clear();
        playChannels.clear();
    }

    private Permission missingRefreshPermission(Guild guild, TextChannel channel) {
        return failurePolicy.firstMissingPermission(
                permission -> guild.getSelfMember().hasPermission(channel, permission)
        );
    }

    private void handlePanelFailure(long guildId,
                                       long channelId,
                                       long messageId,
                                       Throwable failure,
                                       boolean clearStaleState) {
        PanelRefreshFailurePolicy.PanelFailure classified = failurePolicy.classify(failure);
        Throwable diagnosticFailure = failure;
        while (diagnosticFailure.getCause() != null && diagnosticFailure.getCause() != diagnosticFailure) diagnosticFailure = diagnosticFailure.getCause();
        boolean limited = diagnosticFailure instanceof RateLimitedException
                || diagnosticFailure instanceof ErrorResponseException response && response.getResponse() != null && response.getResponse().isRateLimit();
        stateStore.recordDiagnosticError(guildId, limited ? "DISCORD_API_429" : classified.reason());
        if (classified.disposition() == PanelRefreshFailurePolicy.FailureDisposition.UNEXPECTED) {
            LOGGER.error(
                    "[NoRule] Music panel refresh failed unexpectedly: guildId={} channelId={} messageId={}",
                    guildId,
                    channelId,
                    messageId,
                    failure
            );
            return;
        }

        logOperationalFailure(guildId, channelId, messageId, classified.reason(), classified.permission());
        if (clearStaleState && (isUnknownMessage(failure) || hasResponse(failure, ErrorResponse.UNKNOWN_CHANNEL))) {
            var ref = stateStore.getPanelRef(guildId);
            if (ref != null && ref.channelId == channelId && ref.messageId == messageId && stateStore.forgetPanel(guildId, ref)) {
                coordinator.recordCreated(guildId, null);
            }
        }
        if (clearStaleState && classified.disposition() == PanelRefreshFailurePolicy.FailureDisposition.CLEAR_STATE) {
            clearPanel(guildId, channelId, messageId);
        }
    }

    private void logOperationalFailure(long guildId,
                                       long channelId,
                                       long messageId,
                                       String reason,
                                       Permission permission) {
        if (!failurePolicy.shouldLog(guildId, channelId, messageId, reason, System.currentTimeMillis())) {
            return;
        }
        if (permission != null) {
            LOGGER.warn(
                    "[NoRule] Music panel refresh skipped: guildId={} channelId={} messageId={} reason={} permission={}",
                    guildId,
                    channelId,
                    messageId,
                    reason,
                    permission
            );
            return;
        }
        LOGGER.warn(
                "[NoRule] Music panel refresh skipped: guildId={} channelId={} messageId={} reason={}",
                guildId,
                channelId,
                messageId,
                reason
        );
    }

    private String safeErrorMessage(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) {
            failure = failure.getCause();
        }
        return failure.getMessage() == null ? "unknown error" : failure.getMessage();
    }
}
