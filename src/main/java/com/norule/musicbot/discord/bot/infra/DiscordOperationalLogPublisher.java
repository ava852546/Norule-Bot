package com.norule.musicbot.discord.bot.infra;

import com.norule.musicbot.config.domain.OperationalLoggingConfig;
import com.norule.musicbot.domain.monitoring.JvmResourceSample;
import com.norule.musicbot.domain.monitoring.VoiceAutoLeaveEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Discord REST and state I/O run outside sampling, voice timers and Gateway threads. */
public final class DiscordOperationalLogPublisher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordOperationalLogPublisher.class);
    private static final String TITLE = "JVM Resource Monitor";
    private static final String FOOTER = "NoRule Bot · JVM Monitoring";
    private final AtomicReference<JvmResourceSample> heartbeat = new AtomicReference<>();
    private final ArrayBlockingQueue<VoiceAutoLeaveEvent> voiceEvents = new ArrayBlockingQueue<>(64);
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "NoRuleOperationalLogs");
        thread.setDaemon(true);
        return thread;
    });
    private final OperationalLoggingConfig config;
    private final Path stateFile;
    private volatile JDA jda;
    private volatile boolean closed;
    // Confined to worker, including REST completions.
    private String messageId;
    private boolean inFlight;
    private boolean preferHeartbeat;
    private long lastWarning;

    public DiscordOperationalLogPublisher(OperationalLoggingConfig config, Path baseDir) {
        this.config = config;
        stateFile = baseDir.resolve("data/monitoring/jvm-heartbeat-" + config.jvmChannelId() + ".id");
        worker.execute(() -> {
            try {
                if (Files.exists(stateFile)) {
                    String saved = Files.readString(stateFile).strip();
                    if (saved.matches("[0-9]{1,20}") && Long.parseUnsignedLong(saved) != 0) messageId = saved;
                }
            } catch (Exception failure) {
                warn("STATE_READ_FAILED");
            }
        });
        // At most one REST operation at a time; no high-frequency PATCH or unbounded retries.
        worker.scheduleWithFixedDelay(this::drain, 5, 5, TimeUnit.SECONDS);
    }

    public void connected(JDA current) { jda = current; }

    public void publish(JvmResourceSample sample) {
        if (!closed && !"0".equals(config.jvmChannelId())) heartbeat.set(sample);
    }

    public void publish(VoiceAutoLeaveEvent event) {
        if (closed || "0".equals(config.voiceChannelId())) return;
        if (!voiceEvents.offer(event)) {
            voiceEvents.poll();
            voiceEvents.offer(event);
        }
    }

    private void drain() {
        if (closed || inFlight) return;
        try {
            JDA current = jda;
            if (current == null || current.getStatus() != JDA.Status.CONNECTED) {
                heartbeat.set(null);
                voiceEvents.clear();
                return;
            }
            JvmResourceSample sample = preferHeartbeat || voiceEvents.isEmpty() ? heartbeat.getAndSet(null) : null;
            if (sample != null) {
                preferHeartbeat = false;
                TextChannel channel = channel(config.jvmChannelId());
                if (channel != null) sendHeartbeat(channel, jvmEmbed(sample));
            } else {
                VoiceAutoLeaveEvent event = voiceEvents.poll();
                if (event == null || event.timestamp().isBefore(Instant.now().minusSeconds(300))) return;
                preferHeartbeat = true;
                TextChannel channel = channel(config.voiceChannelId());
                if (channel != null) {
                    inFlight = true;
                    channel.sendMessageEmbeds(voiceEmbed(event)).setAllowedMentions(List.of())
                            .queue(ignored -> complete(() -> {}), this::failed);
                }
            }
        } catch (RuntimeException failure) {
            inFlight = false;
            warn("DELIVERY_FAILED_" + failure.getClass().getSimpleName());
        }
    }

    private TextChannel channel(String id) {
        TextChannel channel = jda.getTextChannelById(id);
        if (channel == null || !channel.getGuild().getSelfMember().hasPermission(channel,
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)) {
            warn("CHANNEL_UNAVAILABLE_OR_MISSING_PERMISSIONS");
            return null;
        }
        return channel;
    }

    private void sendHeartbeat(TextChannel channel, MessageEmbed embed) {
        inFlight = true;
        if (config.deliveryMode() == OperationalLoggingConfig.DeliveryMode.NEW_MESSAGE || messageId == null) {
            createHeartbeat(channel, embed);
            return;
        }
        channel.retrieveMessageById(messageId).queue(message -> complete(() -> {
            // Never edit another author, or an unrelated message even if the state file is wrong.
            if (!ownedHeartbeat(message, jda.getSelfUser().getId())) {
                messageId = null;
                return;
            }
            inFlight = true;
            channel.editMessageEmbedsById(messageId, embed).setAllowedMentions(List.of())
                    .queue(ignored -> complete(() -> {}), this::heartbeatFailed);
        }), this::heartbeatFailed);
    }

    static boolean ownedHeartbeat(Message message, String botId) {
        return message.getAuthor().getId().equals(botId) && message.getEmbeds().size() == 1
                && TITLE.equals(message.getEmbeds().getFirst().getTitle())
                && message.getEmbeds().getFirst().getFooter() != null
                && FOOTER.equals(message.getEmbeds().getFirst().getFooter().getText());
    }

    private void createHeartbeat(TextChannel channel, MessageEmbed embed) {
        channel.sendMessageEmbeds(embed).setAllowedMentions(List.of()).queue(message -> complete(() -> {
            if (config.deliveryMode() == OperationalLoggingConfig.DeliveryMode.UPDATE_LATEST) {
                messageId = message.getId();
                try {
                    Files.createDirectories(stateFile.getParent());
                    Path temp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
                    Files.writeString(temp, messageId);
                    try {
                        Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                        Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception failure) {
                    warn("STATE_WRITE_FAILED");
                }
            }
        }), this::failed);
    }

    private void heartbeatFailed(Throwable failure) {
        complete(() -> {
            if (failure instanceof ErrorResponseException response
                    && response.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) messageId = null;
            warn("HEARTBEAT_DELIVERY_FAILED");
        });
    }

    private void failed(Throwable failure) { complete(() -> warn("DELIVERY_FAILED")); }

    private void complete(Runnable action) {
        try {
            worker.execute(() -> {
                inFlight = false;
                if (closed) return;
                try { action.run(); }
                catch (RuntimeException failure) { inFlight = false; warn("DELIVERY_FAILED"); }
            });
        } catch (RejectedExecutionException stopped) {
            // Shutdown discards outstanding operational log completions.
        }
    }

    private void warn(String reason) {
        long now = System.nanoTime();
        if (lastWarning == 0 || now - lastWarning >= TimeUnit.MINUTES.toNanos(5)) {
            lastWarning = now;
            // Never forward this logger to Discord or include REST payloads / credentials.
            LOGGER.warn("[OperationalLogs] event={} (further delivery warnings suppressed for 5 minutes)", reason);
        }
    }

    static MessageEmbed jvmEmbed(JvmResourceSample s) {
        StringBuilder states = new StringBuilder();
        for (Thread.State state : Thread.State.values()) {
            states.append('\n').append(state).append(": ").append(s.threadStates().getOrDefault(state, 0));
        }
        return new EmbedBuilder().setTitle(TITLE).setColor(0x3498DB).setTimestamp(s.timestamp()).setFooter(FOOTER)
                .addField("CPU", "Process CPU: " + cpu(s.processCpu()) + "\nSystem CPU: " + cpu(s.systemCpu()), true)
                .addField("Memory", "Heap Used: " + mib(s.heapUsed()) + "\nHeap Committed: " + mib(s.heapCommitted())
                        + "\nHeap Max: " + mib(s.heapMax()) + "\nNon-Heap Used: " + mib(s.nonHeapUsed()), false)
                .addField("Garbage Collection", "GC Collections: " + s.gcCollections() + "\nCollection Time: "
                        + s.gcCollectionMillis() + " ms\nPause Count: " + (s.pauseAvailable() ? s.pauseCount() : "N/A")
                        + "\nTotal Pause: " + pause(s.pauseAvailable(), s.pauseTotalMillis())
                        + "\nMax Pause: " + pause(s.pauseAvailable(), s.pauseMaxMillis()), true)
                .addField("Threads", "Live: " + s.liveThreads() + "\nDaemon: " + s.daemonThreads()
                        + "\nPeak: " + s.peakThreads() + states, true).build();
    }

    private static String cpu(double value) { return value < 0 || !Double.isFinite(value) ? "N/A" : String.format(Locale.ROOT, "%.2f%%", value * 100); }
    private static String mib(long value) { return value < 0 ? "N/A" : String.format(Locale.ROOT, "%.2f MiB", value / 1048576.0); }
    private static String pause(boolean available, double millis) { return available ? String.format(Locale.ROOT, "%.2f ms", millis) : "N/A"; }

    static MessageEmbed voiceEmbed(VoiceAutoLeaveEvent e) {
        int color = switch (e.event()) {
            case "TIMER_STARTED" -> 0x3498DB;
            case "TIMER_CANCELLED", "TIMER_CANCELED" -> 0x95A5A6;
            case "DISCONNECTED" -> 0x2ECC71;
            default -> 0xE74C3C;
        };
        EmbedBuilder builder = new EmbedBuilder().setTitle("Voice Auto Leave").setColor(color).setTimestamp(e.timestamp())
                .addField("Event", e.event(), true).addField("Reason", e.reason(), true)
                .addField("Guild", label(e.guildName(), e.guildId()), false);
        if (e.channelId() != 0) builder.addField("Voice Channel", label(e.channelName(), e.channelId()), false);
        if (e.humanCount() != null) builder.addField("Human Count", e.humanCount().toString(), true);
        if (e.idleSeconds() != null) builder.addField("Idle Seconds", e.idleSeconds().toString(), true);
        if (e.delaySeconds() != null) builder.addField("Delay Seconds", e.delaySeconds().toString(), true);
        return builder.build();
    }

    private static String label(String name, long id) { return (name == null ? "" : name + " ") + "(" + Long.toUnsignedString(id) + ")"; }

    @Override public void close() {
        closed = true;
        heartbeat.set(null);
        voiceEvents.clear();
        worker.shutdownNow();
    }
}
