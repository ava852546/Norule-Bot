package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.bot.gateway.command.music.MusicCommandChannelProvisioner;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
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
            activatePanel(guild, panelRef, 0L);
            result.complete(panelRef);
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
                            activatePanel(guild, panelRef, System.currentTimeMillis());
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
            if (jda != null) {
                clearPanel(guildId);
            }
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
            clearPanel(guildId);
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
                createPanelMessageWithFeedback(guild, channel, owner.lang(guildId),
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
            clearPanel(guildId, ref.channelId, ref.messageId);
            return CompletableFuture.completedFuture(null);
        }
        Permission missingPermission = missingRefreshPermission(guild, channel);
        if (missingPermission != null) {
            logOperationalFailure(guildId, ref.channelId, ref.messageId, "MISSING_PERMISSION", missingPermission);
            clearPanel(guildId, ref.channelId, ref.messageId);
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
        stateStore.clearPanelState(guildId);
        coordinator.clear(guildId);
    }

    public void clearPanel(long guildId, long channelId, long messageId) {
        if (stateStore.compareAndClearPanelState(guildId, channelId, messageId)) {
            coordinator.clear(guildId);
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
        return failure.getMessage() == null ? "unknown error" : failure.getMessage();
    }
}
