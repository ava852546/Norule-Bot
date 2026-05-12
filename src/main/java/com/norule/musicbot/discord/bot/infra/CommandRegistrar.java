package com.norule.musicbot.discord.bot.infra;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.dv8tion.jda.api.entities.Guild;

public final class CommandRegistrar {
    private static final Logger log = LoggerFactory.getLogger(CommandRegistrar.class);
    private final MusicCommandService owner;
    private final AtomicBoolean cleanupRunning = new AtomicBoolean(false);

    public CommandRegistrar(MusicCommandService owner) {
        this.owner = owner;
    }

    public void registerOnReady(JDA jda) {
        List<CommandData> commands = owner.buildCommands();
        jda.updateCommands().addCommands(commands).queue(
                success -> log.info("[NoRule] Registered global slash commands: {} command(s)", success.size()),
                failure -> log.warn("[NoRule] Failed to register global slash commands: {}", failure.getMessage())
        );
    }

    public void clearGuildCommandsThrottled() {
        if (!cleanupRunning.compareAndSet(false, true)) {
            log.warn("[NoRule] Guild command cleanup is already running.");
            return;
        }

        JDA current = owner.currentJda();
        if (current == null) {
            log.info("[NoRule] Guild command cleanup skipped: JDA is null.");
            cleanupRunning.set(false);
            return;
        }

        List<Guild> guilds = current.getGuilds();
        int total = guilds.size();
        if (total == 0) {
            log.info("[NoRule] Guild command cleanup skipped: no guilds.");
            cleanupRunning.set(false);
            return;
        }

        log.info("[NoRule] Guild command cleanup started: {} guild(s), delay=2s", total);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger index = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        scheduler.scheduleAtFixedRate(() -> {
            int i = index.getAndIncrement();
            if (i >= total) {
                log.info("[NoRule] Guild command cleanup completed: {}/{} guild(s), failed={}", total, total, failed.get());
                cleanupRunning.set(false);
                scheduler.shutdown();
                return;
            }

            if (i > 0 && i % 10 == 0) {
                log.info("[NoRule] Guild command cleanup progress: {}/{} guild(s)", i, total);
            }

            Guild guild = guilds.get(i);
            guild.updateCommands().queue(
                    success -> log.debug("[NoRule] Cleared guild commands for guild {}", guild.getId()),
                    failure -> {
                        failed.incrementAndGet();
                        log.warn("[NoRule] Failed to clear guild commands for guild {}: {}", guild.getId(), failure.getMessage());
                    }
            );
        }, 0, 2, TimeUnit.SECONDS);
    }

    public void syncCommands() {

        JDA current = owner.currentJda();
        if (current == null) {
            return;
        }
        List<CommandData> commands = owner.buildCommands();
        current.updateCommands().addCommands(commands).queue(
                success -> log.info("[NoRule] Slash commands synchronized: {} command(s)", success.size()),
                failure -> log.warn("[NoRule] Failed to sync global slash commands: {}", failure.getMessage())
        );
    }
}


