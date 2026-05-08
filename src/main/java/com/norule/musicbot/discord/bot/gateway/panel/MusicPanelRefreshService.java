package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class MusicPanelRefreshService {
    private final MusicCommandService owner;
    private final MusicPanelStateStore stateStore;
    private final MusicPanelRenderer panelRenderer;
    private final ScheduledExecutorService scheduler;
    private final long panelPeriodicRefreshMs;
    private final long panelMinEditIntervalMs;

    public MusicPanelRefreshService(MusicCommandService owner,
                                    MusicPanelStateStore stateStore,
                                    MusicPanelRenderer panelRenderer,
                                    ScheduledExecutorService scheduler,
                                    long panelPeriodicRefreshMs,
                                    long panelMinEditIntervalMs) {
        this.owner = owner;
        this.stateStore = stateStore;
        this.panelRenderer = panelRenderer;
        this.scheduler = scheduler;
        this.panelPeriodicRefreshMs = panelPeriodicRefreshMs;
        this.panelMinEditIntervalMs = panelMinEditIntervalMs;
    }

    public void createPanelMessageWithFeedback(Guild guild, TextChannel channel, String lang, Runnable onSuccess, Consumer<String> onError) {
        String missing = owner.formatMissingPermissionsForPanel(guild.getSelfMember(), channel,
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
        if (!"-".equals(missing)) {
            onError.accept(owner.i18nService().t(lang, "general.missing_permissions", java.util.Map.of("permissions", missing)));
            return;
        }
        try {
            channel.sendMessageEmbeds(panelRenderer.panelEmbed(guild, lang).build())
                    .setComponents(panelRenderer.panelRows(lang, guild.getIdLong()))
                    .queue(message -> {
                        long guildId = guild.getIdLong();
                        stateStore.putPanelRef(guildId, new MusicPanelStateStore.PanelRef(channel.getIdLong(), message.getIdLong()));
                        stateStore.putLastSignature(guildId, owner.panelSignature(guild));
                        stateStore.putLastRefreshAt(guildId, System.currentTimeMillis());
                        owner.musicService().setGuildStateListener(guildId, () -> refreshPanel(guildId));
                        onSuccess.run();
                    }, error -> onError.accept(error.getMessage()));
        } catch (Exception e) {
            onError.accept(e.getMessage() == null ? "unknown error" : e.getMessage());
        }
    }

    public void refreshPanel(long guildId) {
        refreshPanelInternal(guildId, true);
    }

    public void refreshPanelPeriodic(long guildId) {
        refreshPanelInternal(guildId, false);
    }

    public void refreshPanelMessage(Guild guild, TextChannel channel, long messageId, boolean force) {
        refreshPanelMessage(guild, channel, messageId, force, false);
    }

    public void refreshPanelMessage(Guild guild, TextChannel channel, long messageId, boolean force, boolean immediate) {
        long guildId = guild.getIdLong();
        MusicPanelStateStore.PanelRef active = stateStore.getPanelRef(guildId);
        if (active == null || active.channelId != channel.getIdLong() || active.messageId != messageId) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastRefresh = stateStore.getLastRefreshAt(guildId);
        if (!immediate && now - lastRefresh < panelMinEditIntervalMs) {
            scheduleDelayedPanelRefresh(guildId, panelMinEditIntervalMs - (now - lastRefresh));
            return;
        }
        String signature = owner.panelSignature(guild);
        if (!force && signature.equals(stateStore.getLastSignature(guildId))) {
            return;
        }
        String lang = owner.lang(guildId);
        channel.editMessageEmbedsById(messageId, panelRenderer.panelEmbed(guild, lang).build())
                .setComponents(panelRenderer.panelRows(lang, guildId))
                .queue(success -> {
                    stateStore.putLastSignature(guildId, signature);
                    stateStore.putLastRefreshAt(guildId, System.currentTimeMillis());
                }, error -> {
                    MusicPanelStateStore.PanelRef ref = stateStore.getPanelRef(guildId);
                    if (ref != null && ref.messageId == messageId) {
                        stateStore.clearPanelState(guildId);
                    }
                });
    }

    private void refreshPanelInternal(long guildId, boolean force) {
        MusicPanelStateStore.PanelRef ref = stateStore.getPanelRef(guildId);
        JDA currentJda = owner.currentJda();
        if (ref == null || currentJda == null) {
            return;
        }
        if (!stateStore.startRefreshing(guildId)) {
            return;
        }
        try {
            if (!force) {
                if (owner.musicService().getCurrentTitle(currentJda.getGuildById(guildId)) == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                long last = stateStore.getLastRefreshAt(guildId);
                if (now - last < panelPeriodicRefreshMs) {
                    return;
                }
            }
            long now = System.currentTimeMillis();
            long lastRefresh = stateStore.getLastRefreshAt(guildId);
            if (now - lastRefresh < panelMinEditIntervalMs) {
                scheduleDelayedPanelRefresh(guildId, panelMinEditIntervalMs - (now - lastRefresh));
                return;
            }
            Guild guild = currentJda.getGuildById(guildId);
            if (guild == null) {
                stateStore.clearPanelState(guildId);
                return;
            }
            TextChannel channel = guild.getTextChannelById(ref.channelId);
            if (channel == null) {
                stateStore.clearPanelState(guildId);
                return;
            }
            String signature = owner.panelSignature(guild);
            if (signature.equals(stateStore.getLastSignature(guildId))) {
                return;
            }
            String lang = owner.lang(guildId);
            channel.editMessageEmbedsById(ref.messageId, panelRenderer.panelEmbed(guild, lang).build())
                    .setComponents(panelRenderer.panelRows(lang, guildId))
                    .queue(success -> {
                        stateStore.putLastSignature(guildId, signature);
                        stateStore.putLastRefreshAt(guildId, System.currentTimeMillis());
                    }, error -> stateStore.clearPanelState(guildId));
        } finally {
            stateStore.finishRefreshing(guildId);
        }
    }

    private void scheduleDelayedPanelRefresh(long guildId, long delayMs) {
        if (delayMs <= 0L) {
            scheduler.execute(() -> refreshPanelInternal(guildId, true));
            return;
        }
        ScheduledFuture<?> existing = stateStore.getDelayedRefreshTask(guildId);
        if (existing != null && !existing.isDone()) {
            return;
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            stateStore.removeDelayedRefreshTask(guildId);
            refreshPanelInternal(guildId, true);
        }, delayMs, TimeUnit.MILLISECONDS);
        stateStore.putDelayedRefreshTask(guildId, future);
    }
}
