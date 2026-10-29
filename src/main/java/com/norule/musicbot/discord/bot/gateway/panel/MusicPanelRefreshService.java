package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicCommandChannelProvisioner;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

public final class MusicPanelRefreshService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicPanelRefreshService.class);

    private final MusicCommandService owner;
    private final MusicPanelStateStore stateStore;
    private final MusicPanelRenderer panelRenderer;
    private final MusicCommandChannelProvisioner commandChannelProvisioner;
    private final MusicPanelRefreshCoordinator coordinator;
    private final long panelPeriodicRefreshMs;
    private final PanelRefreshFailurePolicy failurePolicy;
    private final Map<Long, CompletableFuture<MusicPanelStateStore.PanelRef>> panelResolutionByGuild =
            new ConcurrentHashMap<>();
    private volatile boolean closed;

    public MusicPanelRefreshService(MusicCommandService owner,
                                    MusicPanelStateStore stateStore,
                                    MusicPanelRenderer panelRenderer,
                                    MusicCommandChannelProvisioner commandChannelProvisioner,
                                    ScheduledExecutorService scheduler,
                                    long panelPeriodicRefreshMs) {
        this.owner = owner;
        this.stateStore = stateStore;
        this.panelRenderer = panelRenderer;
        this.commandChannelProvisioner = commandChannelProvisioner;
        this.coordinator = new MusicPanelRefreshCoordinator(scheduler, this::prepareRefresh);
        this.panelPeriodicRefreshMs = panelPeriodicRefreshMs;
        this.failurePolicy = new PanelRefreshFailurePolicy();
    }

    public void createPanelMessageWithFeedback(Guild guild, TextChannel channel, String lang, Runnable onSuccess, Consumer<String> onError) {
        if (guild == null || channel == null) {
            onError.accept(owner.musicText(lang, "panel_text_channel_only"));
            return;
        }
        coordinator.submit(guild.getIdLong(), () -> {
            if (stateStore.getPanelRef(guild.getIdLong()) != null) {
                return prepareRefresh(guild.getIdLong(), Set.of(RefreshReason.MANUAL));
            }
            CompletableFuture<MusicPanelRefreshCoordinator.Update> prepared = new CompletableFuture<>();
            resolvePanelWithFeedback(guild, channel, lang, () -> prepared.complete(null),
                    error -> prepared.completeExceptionally(new IllegalStateException(error)));
            return prepared;
        }).whenComplete((ignored, failure) -> {
            if (failure == null) {
                onSuccess.run();
            } else {
                onError.accept(safeErrorMessage(failure));
            }
        });
    }

    public void restorePanels() {
        JDA jda = owner.currentJda();
        for (long guildId : stateStore.snapshotGuildIds()) {
            if (jda.getGuildById(guildId) != null) {
                owner.musicService().setGuildStateChangeListener(guildId,
                        reason -> requestRefresh(guildId, RefreshReason.valueOf(reason.name())));
                requestRefresh(guildId, RefreshReason.MANUAL);
            }
        }
    }

    /** Never provisions or adopts a command channel. One existing guild binding remains authoritative. */
    public CompletableFuture<String> sendDeveloperPanel(TextChannel channel) {
        Guild guild = channel.getGuild();
        long guildId = guild.getIdLong();
        long[] originalMessageId = {0L};
        return coordinator.submit(guildId, () -> {
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
                return channel.getHistory().retrievePast(50).submit().thenCompose(messages -> {
                    Message recovered = messages.stream().filter(message -> isMusicPanelMessage(guild, message))
                            .max(Comparator.comparingLong(Message::getIdLong)).orElse(null);
                    if (recovered == null) {
                        return createPanelInChannel(guild, channel);
                    }
                    originalMessageId[0] = recovered.getIdLong();
                    MusicPanelStateStore.PanelRef recoveredRef = new MusicPanelStateStore.PanelRef(
                            channel.getIdLong(), recovered.getIdLong());
                    return CompletableFuture.completedFuture(prepareDeveloperUpdate(guild, channel, recoveredRef, recovered));
                });
            }
            return channel.retrieveMessageById(ref.messageId).submit().handle((message, failure) -> {
                if (failure != null) {
                    if (isUnknownMessage(failure)) {
                        return createPanelInChannel(guild, channel);
                    }
                    return CompletableFuture.<MusicPanelRefreshCoordinator.Update>failedFuture(failure);
                }
                if (!isMusicPanelMessage(guild, message)) {
                    return CompletableFuture.<MusicPanelRefreshCoordinator.Update>failedFuture(new IllegalStateException(
                            "The stored message is not a music panel owned by this bot; binding retained."));
                }
                return CompletableFuture.completedFuture(prepareDeveloperUpdate(guild, channel, ref, message));
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

    private MusicPanelRefreshCoordinator.Update prepareDeveloperUpdate(Guild guild, TextChannel channel,
                                                                       MusicPanelStateStore.PanelRef ref, Message message) {
        long guildId = guild.getIdLong();
        activatePanel(guild, ref, stateStore.getLastRefreshAt(guildId));
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

    private CompletableFuture<MusicPanelRefreshCoordinator.Update> createPanelInChannel(Guild guild, TextChannel channel) {
        CompletableFuture<MusicPanelStateStore.PanelRef> created = new CompletableFuture<>();
        sendNewPanel(guild, channel, owner.lang(guild.getIdLong()), created);
        return created.thenApply(ref -> null);
    }

    static boolean isUnknownMessage(Throwable failure) {
        while (failure.getCause() != null && failure.getCause() != failure) {
            failure = failure.getCause();
        }
        return failure instanceof ErrorResponseException response && response.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE;
    }

    private void resolvePanelWithFeedback(Guild guild, TextChannel channel, String lang, Runnable onSuccess, Consumer<String> onError) {
        if (closed) {
            onError.accept(owner.i18nService().t(lang, "general.action_failed"));
            return;
        }
        if (guild == null || channel == null) {
            onError.accept(owner.musicText(lang, "panel_text_channel_only"));
            return;
        }

        long guildId = guild.getIdLong();
        if (stateStore.getPanelRef(guildId) != null) {
            requestRefresh(guildId, RefreshReason.MANUAL);
            onSuccess.run();
            return;
        }

        CompletableFuture<MusicPanelStateStore.PanelRef> candidate = new CompletableFuture<>();
        CompletableFuture<MusicPanelStateStore.PanelRef> existing = panelResolutionByGuild.putIfAbsent(guildId, candidate);
        CompletableFuture<MusicPanelStateStore.PanelRef> future = existing == null ? candidate : existing;
        future.whenComplete((panelRef, failure) -> {
            panelResolutionByGuild.remove(guildId, future);
            if (failure != null) {
                onError.accept(safeErrorMessage(failure));
                return;
            }
            requestRefresh(guildId, RefreshReason.MANUAL);
            onSuccess.run();
        });
        if (existing == null) {
            if (closed) {
                candidate.cancel(false);
            } else {
                try {
                    resolveOrCreatePanel(guild, channel, lang, candidate);
                } catch (RuntimeException failure) {
                    candidate.completeExceptionally(failure);
                }
            }
        }
    }

    private void resolveOrCreatePanel(Guild guild, TextChannel channel, String lang,
                                      CompletableFuture<MusicPanelStateStore.PanelRef> result) {
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
            channel.getHistory().retrievePast(50).queue(
                    messages -> recoverPanelOrCreate(guild, channel, lang, messages, result),
                    failure -> {
                        LOGGER.debug(
                                "[NoRule] Music panel history recovery skipped: guildId={} channelId={} reason={}",
                                guild.getIdLong(),
                                channel.getIdLong(),
                                safeErrorMessage(failure)
                        );
                        sendNewPanel(guild, channel, lang, result);
                    }
            );
        } else {
            sendNewPanel(guild, channel, lang, result);
        }
    }

    private void recoverPanelOrCreate(Guild guild,
                                      TextChannel channel,
                                      String lang,
                                      java.util.List<Message> messages,
                                      CompletableFuture<MusicPanelStateStore.PanelRef> result) {
        if (closed || result.isDone()) {
            return;
        }
        Message recovered = messages.stream()
                .filter(message -> isMusicPanelMessage(guild, message))
                .max(Comparator.comparingLong(Message::getIdLong))
                .orElse(null);
        if (recovered == null) {
            sendNewPanel(guild, channel, lang, result);
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
                activatePanel(guild, panelRef, 0L);
                result.complete(panelRef);
            } catch (RuntimeException persistenceFailure) {
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

    private void sendNewPanel(Guild guild,
                              TextChannel channel,
                              String lang,
                              CompletableFuture<MusicPanelStateStore.PanelRef> result) {
        if (closed || result.isDone()) {
            return;
        }
        try {
            MusicPanelSnapshot snapshot = render(guild, lang);
            channel.sendMessageEmbeds(snapshot.embed())
                    .setComponents(snapshot.components())
                    .queue(message -> {
                        if (closed || result.isDone()) {
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
                                activatePanel(guild, panelRef, System.currentTimeMillis());
                            } catch (RuntimeException persistenceFailure) {
                                result.completeExceptionally(persistenceFailure);
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
                        handlePanelFailure(guild.getIdLong(), channel.getIdLong(), 0L, failure, false);
                        result.completeExceptionally(failure);
                    });
        } catch (RuntimeException failure) {
            handlePanelFailure(guild.getIdLong(), channel.getIdLong(), 0L, failure, false);
            result.completeExceptionally(failure);
        }
    }

    private void activatePanel(Guild guild,
                               MusicPanelStateStore.PanelRef panelRef,
                               long refreshedAt) {
        long guildId = guild.getIdLong();
        stateStore.activatePanelRef(guildId, panelRef, refreshedAt);
        owner.musicService().setGuildStateChangeListener(guildId,
                reason -> requestRefresh(guildId, RefreshReason.valueOf(reason.name())));
    }

    private boolean isMusicPanelMessage(Guild guild, Message message) {
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

    private CompletableFuture<MusicPanelRefreshCoordinator.Update> prepareRefresh(long guildId, Set<RefreshReason> reasons) {
        MusicPanelStateStore.PanelRef ref = stateStore.getPanelRef(guildId);
        JDA jda = owner.currentJda();
        if (jda == null) {
            return CompletableFuture.completedFuture(null);
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            return CompletableFuture.completedFuture(null);
        }
        boolean periodicOnly = reasons.equals(Set.of(RefreshReason.PERIODIC_REFRESH));
        if (ref == null) {
            if (periodicOnly) {
                return CompletableFuture.completedFuture(null);
            }
            return commandChannelProvisioner.ensureCommandChannel(guild).thenCompose(channel -> {
                if (closed || jda.getGuildById(guildId) == null) {
                    return CompletableFuture.completedFuture(null);
                }
                CompletableFuture<MusicPanelRefreshCoordinator.Update> created = new CompletableFuture<>();
                resolvePanelWithFeedback(guild, channel, owner.lang(guildId),
                        () -> created.complete(null),
                        error -> created.completeExceptionally(new IllegalStateException(error)));
                return created;
            });
        }
        if (periodicOnly && !progressRefreshDue(isProgressActive(guild), stateStore.getLastRefreshAt(guildId),
                System.currentTimeMillis(), panelPeriodicRefreshMs)) {
            return CompletableFuture.completedFuture(null);
        }
        TextChannel channel = guild.getTextChannelById(ref.channelId);
        if (channel == null) {
            logOperationalFailure(guildId, ref.channelId, ref.messageId, "UNKNOWN_CHANNEL", null);
            return CompletableFuture.completedFuture(null);
        }
        Permission missingPermission = missingRefreshPermission(guild, channel);
        if (missingPermission != null) {
            logOperationalFailure(guildId, ref.channelId, ref.messageId, "MISSING_PERMISSION", missingPermission);
            return CompletableFuture.completedFuture(null);
        }
        MusicPanelSnapshot snapshot = render(guild, owner.lang(guildId));
        return CompletableFuture.completedFuture(new MusicPanelRefreshCoordinator.Update(
                ref.channelId, ref.messageId, snapshot, () -> sendUpdate(guildId, channel, ref, snapshot)));
    }

    private MusicPanelSnapshot render(Guild guild, String lang) {
        return new MusicPanelSnapshot(panelRenderer.panelEmbed(guild, lang).build(),
                panelRenderer.panelRows(lang, guild.getIdLong()));
    }

    private CompletableFuture<Void> sendUpdate(long guildId, TextChannel channel,
                                               MusicPanelStateStore.PanelRef ref, MusicPanelSnapshot snapshot) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            channel.editMessageEmbedsById(ref.messageId, snapshot.embed())
                    .setComponents(snapshot.components())
                    .queue(success -> {
                        if (stateStore.isActivePanel(guildId, ref.channelId, ref.messageId)) {
                            stateStore.markRefreshed(guildId, ref, System.currentTimeMillis());
                            failurePolicy.clearChannel(guildId, ref.channelId);
                        }
                        result.complete(null);
                    }, failure -> {
                        if (isUnknownMessage(failure) && stateStore.isActivePanel(guildId, ref.channelId, ref.messageId)) {
                            createPanelInChannel(channel.getGuild(), channel).whenComplete((created, createFailure) -> {
                                if (createFailure == null) {
                                    result.complete(null);
                                } else {
                                    result.completeExceptionally(createFailure);
                                }
                            });
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
        CompletableFuture<MusicPanelStateStore.PanelRef> pending = panelResolutionByGuild.remove(guildId);
        if (pending != null) {
            synchronized (pending) {
                pending.cancel(false);
            }
        }
        stateStore.forgetGuild(guildId);
        coordinator.clear(guildId);
    }

    public void clearPanel(long guildId, long channelId, long messageId) {
        // Deletion events invalidate comparison, but only a REST 10008 authorizes replacement.
        if (stateStore.isActivePanel(guildId, channelId, messageId)) {
            coordinator.recordCreated(guildId, null);
        }
    }

    public void close() {
        closed = true;
        coordinator.close();
        panelResolutionByGuild.values().forEach(future -> future.cancel(false));
        panelResolutionByGuild.clear();
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
