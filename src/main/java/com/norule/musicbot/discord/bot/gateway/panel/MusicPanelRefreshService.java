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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class MusicPanelRefreshService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicPanelRefreshService.class);

    private final MusicCommandService owner;
    private final MusicPanelStateStore stateStore;
    private final MusicPanelRenderer panelRenderer;
    private final MusicCommandChannelProvisioner commandChannelProvisioner;
    private final ScheduledExecutorService scheduler;
    private final long panelPeriodicRefreshMs;
    private final long panelMinEditIntervalMs;
    private final PanelRefreshFailurePolicy failurePolicy;
    private final Map<Long, CompletableFuture<MusicPanelStateStore.PanelRef>> panelResolutionByGuild =
            new ConcurrentHashMap<>();

    public MusicPanelRefreshService(MusicCommandService owner,
                                    MusicPanelStateStore stateStore,
                                    MusicPanelRenderer panelRenderer,
                                    MusicCommandChannelProvisioner commandChannelProvisioner,
                                    ScheduledExecutorService scheduler,
                                    long panelPeriodicRefreshMs,
                                    long panelMinEditIntervalMs) {
        this.owner = owner;
        this.stateStore = stateStore;
        this.panelRenderer = panelRenderer;
        this.commandChannelProvisioner = commandChannelProvisioner;
        this.scheduler = scheduler;
        this.panelPeriodicRefreshMs = panelPeriodicRefreshMs;
        this.panelMinEditIntervalMs = panelMinEditIntervalMs;
        this.failurePolicy = new PanelRefreshFailurePolicy();
    }

    public void createPanelMessageWithFeedback(Guild guild, TextChannel channel, String lang, Runnable onSuccess, Consumer<String> onError) {
        if (guild == null || channel == null) {
            onError.accept(owner.musicText(lang, "panel_text_channel_only"));
            return;
        }

        long guildId = guild.getIdLong();
        if (stateStore.getPanelRef(guildId) != null) {
            requestRefresh(guildId, false, false, false);
            onSuccess.run();
            return;
        }

        CompletableFuture<MusicPanelStateStore.PanelRef> future = panelResolutionByGuild.computeIfAbsent(
                guildId,
                ignored -> resolveOrCreatePanel(guild, channel, lang)
        );
        future.whenComplete((panelRef, failure) -> {
            panelResolutionByGuild.remove(guildId, future);
            if (failure != null) {
                onError.accept(safeErrorMessage(failure));
                return;
            }
            requestRefresh(guildId, false, true, false);
            onSuccess.run();
        });
    }

    private CompletableFuture<MusicPanelStateStore.PanelRef> resolveOrCreatePanel(Guild guild,
                                                                                   TextChannel channel,
                                                                                   String lang) {
        Permission missingPermission = missingRefreshPermission(guild, channel);
        if (missingPermission != null) {
            logOperationalFailure(guild.getIdLong(), channel.getIdLong(), 0L,
                    "MISSING_PERMISSION", missingPermission);
            String missing = owner.formatMissingPermissionsForPanel(guild.getSelfMember(), channel,
                    Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
            return CompletableFuture.failedFuture(new IllegalStateException(
                    owner.i18nService().t(lang, "general.missing_permissions", Map.of("permissions", missing))
            ));
        }

        CompletableFuture<MusicPanelStateStore.PanelRef> result = new CompletableFuture<>();
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
        return result;
    }

    private void recoverPanelOrCreate(Guild guild,
                                      TextChannel channel,
                                      String lang,
                                      java.util.List<Message> messages,
                                      CompletableFuture<MusicPanelStateStore.PanelRef> result) {
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
        activatePanel(guild, panelRef, null, 0L);
        LOGGER.debug(
                "[NoRule] Music panel recovered: guildId={} channelId={} messageId={}",
                guild.getIdLong(),
                channel.getIdLong(),
                recovered.getIdLong()
        );
        result.complete(panelRef);
    }

    private void sendNewPanel(Guild guild,
                              TextChannel channel,
                              String lang,
                              CompletableFuture<MusicPanelStateStore.PanelRef> result) {
        try {
            String renderedSignature = owner.panelSignature(guild);
            channel.sendMessageEmbeds(panelRenderer.panelEmbed(guild, lang).build())
                    .setComponents(panelRenderer.panelRows(lang, guild.getIdLong()))
                    .queue(message -> {
                        MusicPanelStateStore.PanelRef panelRef = new MusicPanelStateStore.PanelRef(
                                channel.getIdLong(),
                                message.getIdLong()
                        );
                        activatePanel(guild, panelRef, renderedSignature, System.currentTimeMillis());
                        failurePolicy.clearChannel(guild.getIdLong(), channel.getIdLong());
                        LOGGER.debug(
                                "[NoRule] Music panel created: guildId={} channelId={} messageId={}",
                                guild.getIdLong(),
                                channel.getIdLong(),
                                message.getIdLong()
                        );
                        result.complete(panelRef);
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
                               String signature,
                               long refreshedAt) {
        long guildId = guild.getIdLong();
        stateStore.activatePanelRef(guildId, panelRef, signature, refreshedAt);
        owner.musicService().setGuildStateListener(guildId, () -> refreshPanel(guildId));
    }

    private boolean isMusicPanelMessage(Guild guild, Message message) {
        return message != null
                && message.getAuthor().getIdLong() == guild.getSelfMember().getIdLong()
                && message.getComponentTree()
                .find(Button.class, button -> MusicCommandService.PANEL_PLAY_PAUSE.equals(button.getCustomId()))
                .isPresent();
    }

    public void refreshPanel(long guildId) {
        requestRefresh(guildId, false, false, false);
    }

    public void refreshPanelPeriodic(long guildId) {
        requestRefresh(guildId, false, false, true);
    }

    public void refreshPanelMessage(Guild guild, TextChannel channel, long messageId, boolean force) {
        refreshPanelMessage(guild, channel, messageId, force, false);
    }

    public void refreshPanelMessage(Guild guild, TextChannel channel, long messageId, boolean force, boolean immediate) {
        long guildId = guild.getIdLong();
        if (!stateStore.isActivePanel(guildId, channel.getIdLong(), messageId)) {
            return;
        }
        requestRefresh(guildId, force, immediate, false);
    }

    private void requestRefresh(long guildId, boolean force, boolean immediate, boolean periodicOnly) {
        stateStore.requestRefresh(guildId, force, immediate, periodicOnly);
        startRefreshDrain(guildId);
    }

    private void startRefreshDrain(long guildId) {
        if (!stateStore.startRefreshing(guildId)) {
            return;
        }
        drainNextRefresh(guildId);
    }

    private void drainNextRefresh(long guildId) {
        MusicPanelStateStore.RefreshRequest request = stateStore.pollRefreshRequest(guildId);
        if (request == null) {
            stateStore.finishRefreshing(guildId);
            if (stateStore.hasPendingRefresh(guildId)) {
                startRefreshDrain(guildId);
            }
            return;
        }
        runRefreshSafely(guildId, () -> refreshPanelInternal(guildId, request, () -> drainNextRefresh(guildId)));
    }

    private void refreshPanelInternal(long guildId,
                                      MusicPanelStateStore.RefreshRequest request,
                                      Runnable completion) {
        MusicPanelStateStore.PanelRef ref = stateStore.getPanelRef(guildId);
        JDA currentJda = owner.currentJda();
        if (currentJda == null) {
            completion.run();
            return;
        }

        Guild guild = currentJda.getGuildById(guildId);
        if (guild == null) {
            if (ref != null) {
                stateStore.compareAndClearPanelState(guildId, ref.channelId, ref.messageId);
            }
            completion.run();
            return;
        }

        if (ref == null) {
            if (request.periodicOnly()) {
                completion.run();
                return;
            }
            commandChannelProvisioner.ensureCommandChannel(guild).whenComplete((channel, failure) -> {
                if (failure != null) {
                    commandChannelProvisioner.logProvisioningFailure(guild, failure);
                    completion.run();
                    return;
                }
                createPanelMessageWithFeedback(
                        guild,
                        channel,
                        owner.lang(guildId),
                        completion,
                        ignored -> completion.run()
                );
            });
            return;
        }

        if (request.periodicOnly()) {
            if (owner.musicService().getCurrentTitle(guild) == null) {
                completion.run();
                return;
            }
            long now = System.currentTimeMillis();
            long last = stateStore.getLastRefreshAt(guildId);
            if (now - last < panelPeriodicRefreshMs) {
                completion.run();
                return;
            }
        }

        long now = System.currentTimeMillis();
        long lastRefresh = stateStore.getLastRefreshAt(guildId);
        if (!request.immediate() && now - lastRefresh < panelMinEditIntervalMs) {
            scheduleDelayedPanelRefresh(guildId, panelMinEditIntervalMs - (now - lastRefresh), request.force());
            completion.run();
            return;
        }

        TextChannel channel = guild.getTextChannelById(ref.channelId);
        if (channel == null) {
            logOperationalFailure(guildId, ref.channelId, ref.messageId, "UNKNOWN_CHANNEL", null);
            if (stateStore.compareAndClearPanelState(guildId, ref.channelId, ref.messageId)) {
                requestRefresh(guildId, true, false, false);
            }
            completion.run();
            return;
        }

        Permission missingPermission = missingRefreshPermission(guild, channel);
        if (missingPermission != null) {
            logOperationalFailure(guildId, channel.getIdLong(), ref.messageId,
                    "MISSING_PERMISSION", missingPermission);
            completion.run();
            return;
        }

        String signature = owner.panelSignature(guild);
        if (!request.force() && signature.equals(stateStore.getLastSignature(guildId))) {
            completion.run();
            return;
        }

        String lang = owner.lang(guildId);
        channel.editMessageEmbedsById(ref.messageId, panelRenderer.panelEmbed(guild, lang).build())
                .setComponents(panelRenderer.panelRows(lang, guildId))
                .queue(success -> {
                    if (stateStore.isActivePanel(guildId, ref.channelId, ref.messageId)) {
                        stateStore.putLastSignature(guildId, signature);
                        stateStore.putLastRefreshAt(guildId, System.currentTimeMillis());
                        failurePolicy.clearChannel(guildId, channel.getIdLong());
                    }
                    completion.run();
                }, error -> {
                    boolean cleared = handlePanelFailure(
                            guildId,
                            channel.getIdLong(),
                            ref.messageId,
                            error,
                            true
                    );
                    if (cleared) {
                        requestRefresh(guildId, true, false, false);
                    }
                    completion.run();
                });
    }

    private void scheduleDelayedPanelRefresh(long guildId, long delayMs, boolean force) {
        stateStore.mergeDelayedRefreshForce(guildId, force);
        if (delayMs <= 0L) {
            boolean delayedForce = stateStore.pollDelayedRefreshForce(guildId);
            scheduler.execute(() -> requestRefresh(guildId, delayedForce, false, false));
            return;
        }
        ScheduledFuture<?> existing = stateStore.getDelayedRefreshTask(guildId);
        if (existing != null && !existing.isDone()) {
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            stateStore.removeDelayedRefreshTask(guildId);
            boolean delayedForce = stateStore.pollDelayedRefreshForce(guildId);
            requestRefresh(guildId, delayedForce, false, false);
        }, delayMs, TimeUnit.MILLISECONDS);
        stateStore.putDelayedRefreshTask(guildId, future);
    }

    private Permission missingRefreshPermission(Guild guild, TextChannel channel) {
        return failurePolicy.firstMissingPermission(
                permission -> guild.getSelfMember().hasPermission(channel, permission)
        );
    }

    private void runRefreshSafely(long guildId, Runnable refreshAction) {
        try {
            refreshAction.run();
        } catch (RuntimeException failure) {
            MusicPanelStateStore.PanelRef ref = stateStore.getPanelRef(guildId);
            long channelId = ref == null ? 0L : ref.channelId;
            long messageId = ref == null ? 0L : ref.messageId;
            boolean cleared = handlePanelFailure(guildId, channelId, messageId, failure, true);
            if (cleared) {
                requestRefresh(guildId, true, false, false);
            }
            stateStore.finishRefreshing(guildId);
            if (stateStore.hasPendingRefresh(guildId)) {
                startRefreshDrain(guildId);
            }
        }
    }

    private boolean handlePanelFailure(long guildId,
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
            return false;
        }

        logOperationalFailure(guildId, channelId, messageId, classified.reason(), classified.permission());
        return clearStaleState
                && classified.disposition() == PanelRefreshFailurePolicy.FailureDisposition.CLEAR_STATE
                && stateStore.compareAndClearPanelState(guildId, channelId, messageId);
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
