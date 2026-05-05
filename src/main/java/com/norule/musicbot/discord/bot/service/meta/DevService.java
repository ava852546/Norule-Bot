package com.norule.musicbot.discord.bot.service.meta;

import com.norule.musicbot.config.domain.RuntimeConfigSnapshot;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DevService {
    public static final String DEV_COMMAND_PREFIX = "&dev";
    public static final String DEV_GUILDS_BUTTON_PREFIX = "dev:guilds:";
    public static final String DEV_INFO_REFRESH_BUTTON_PREFIX = "dev:info:refresh:";
    private static final int DEV_GUILDS_PAGE_SIZE = 10;
    private static final Pattern PROC_STATUS_KB_PATTERN = Pattern.compile("^(\\d+)\\s*kB$");

    private final IntSupplier activePlaybackGuildCountSupplier;
    private volatile RuntimeConfigSnapshot runtimeConfig;
    private final Instant startedAt = Instant.now();

    public DevService(RuntimeConfigSnapshot runtimeConfig, IntSupplier activePlaybackGuildCountSupplier) {
        this.runtimeConfig = runtimeConfig;
        this.activePlaybackGuildCountSupplier = activePlaybackGuildCountSupplier == null ? () -> 0 : activePlaybackGuildCountSupplier;
    }

    public void reloadRuntimeConfig(RuntimeConfigSnapshot newConfig) {
        if (newConfig == null) {
            return;
        }
        this.runtimeConfig = newConfig;
    }

    public boolean isDeveloperCommand(String raw) {
        return raw != null && (raw.equals(DEV_COMMAND_PREFIX) || raw.startsWith(DEV_COMMAND_PREFIX + " "));
    }

    public void handleMessage(MessageReceivedEvent event, String raw) {
        if (event.isFromGuild()) {
            logDeveloperCommand(event, "ignored", "guild-channel");
            return;
        }
        if (!isConfiguredDeveloper(event.getAuthor().getIdLong())) {
            logDeveloperCommand(event, "denied", "not-configured-developer");
            return;
        }

        String args = raw.length() == DEV_COMMAND_PREFIX.length()
                ? ""
                : raw.substring(DEV_COMMAND_PREFIX.length()).trim();
        String[] split = args.split("\\s+", 2);
        String subcommand = args.isBlank() ? "help" : split[0].toLowerCase(Locale.ROOT);
        logDeveloperCommand(event, subcommand, "dm");

        switch (subcommand) {
            case "help" -> sendDeveloperPrivateEmbed(event, developerHelpEmbed());
            case "ping" -> sendDeveloperPrivateEmbed(event, developerPingEmbed(event.getJDA()));
            case "info" -> sendDeveloperInfoMessage(event);
            case "guilds" -> sendDeveloperGuildsPage(event, 0);
            default -> sendDeveloperPrivateEmbed(event, developerErrorEmbed("Unknown developer command. Use `&dev help`."));
        }
    }

    public boolean handleButton(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith(DEV_GUILDS_BUTTON_PREFIX)) {
            handleDeveloperGuildsButton(event);
            return true;
        }
        if (id.startsWith(DEV_INFO_REFRESH_BUTTON_PREFIX)) {
            handleDeveloperInfoRefreshButton(event);
            return true;
        }
        return false;
    }

    private boolean isConfiguredDeveloper(long userId) {
        RuntimeConfigSnapshot config = runtimeConfig;
        return config != null && config.getDeveloperIds().contains(userId);
    }

    private boolean canUseDeveloperControl(long requesterId, User user) {
        long userId = user.getIdLong();
        return userId == requesterId && isConfiguredDeveloper(userId);
    }

    private Button developerInfoRefreshButton(long requesterId) {
        return Button.secondary(DEV_INFO_REFRESH_BUTTON_PREFIX + requesterId, "Refresh");
    }

    private void logDeveloperReplyError(User user, Throwable error) {
        System.out.println("[NoRule] Developer command reply failed: user="
                + user.getAsTag()
                + " (" + user.getId() + ") error=" + error.getMessage());
    }

    private void sendDeveloperPrivateEmbed(MessageReceivedEvent event, EmbedBuilder embed) {
        event.getChannel().sendMessageEmbeds(embed.build()).queue(
                ignored -> {
                },
                error -> logDeveloperReplyError(event.getAuthor(), error)
        );
    }

    private EmbedBuilder developerHelpEmbed() {
        return developerBaseEmbed("Developer Commands")
                .setDescription("Prefix: `&dev`")
                .addField("`&dev help`", "Show this command list.", false)
                .addField("`&dev ping`", "Check bot responsiveness.", false)
                .addField("`&dev info`", "Show runtime status.", false)
                .addField("`&dev guilds`", "Show joined guilds with pagination.", false);
    }

    private EmbedBuilder developerPingEmbed(JDA jda) {
        return developerBaseEmbed("Developer Ping")
                .addField("Result", "`Pong.`", true)
                .addField("JDA Status", "`" + jda.getStatus() + "`", true);
    }

    private EmbedBuilder developerInfoEmbed(JDA jda) {
        MemoryView memory = resolveMemoryView();
        List<Guild> guilds = jda.getGuilds();
        int guildCount = guilds.size();
        long totalMembers = guilds.stream()
                .mapToLong(Guild::getMemberCount)
                .sum();
        return developerBaseEmbed("Developer Info")
                .addField("Bot Version", "`" + developerBotVersion() + "`", true)
                .addField("JDA Status", "`" + jda.getStatus() + "`", true)
                .addField("Uptime", "`" + formatDuration(Duration.between(startedAt, Instant.now())) + "`", true)
                .addField("Guilds", "`" + guildCount + "`", true)
                .addField("Members", "`" + totalMembers + "`", true)
                .addField("Playing Guilds", "`" + activePlaybackGuildCountSupplier.getAsInt() + "`", true)
                .addField("Memory", "`RSS " + memory.rssUsedMb + " MB / Limit " + memory.limitLabel() + "`", false)
                .addField("Heap", "`" + memory.heapUsedMb + " / " + memory.heapMaxMb + " MB`", true)
                .addField("CPU", "`" + developerCpuInfo() + "`", false);
    }

    private MemoryView resolveMemoryView() {
        Runtime runtime = Runtime.getRuntime();
        long heapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
        long heapMaxBytes = runtime.maxMemory();

        Long cgroupLimitBytes = readFirstCgroupBytes(List.of(
                "/sys/fs/cgroup/memory.max",
                "/sys/fs/cgroup/memory/memory.limit_in_bytes"
        ));
        Long cgroupUsageBytes = readFirstCgroupBytes(List.of(
                "/sys/fs/cgroup/memory.current",
                "/sys/fs/cgroup/memory/memory.usage_in_bytes"
        ));
        Long processRssBytes = readProcessRssBytes();
        long rssBytes = processRssBytes != null
                ? processRssBytes
                : (cgroupUsageBytes != null ? cgroupUsageBytes : heapUsedBytes);

        if (cgroupLimitBytes != null) {
            return new MemoryView(
                    bytesToMb(rssBytes),
                    bytesToMb(cgroupLimitBytes),
                    bytesToMb(heapUsedBytes),
                    bytesToMb(heapMaxBytes)
            );
        }
        return new MemoryView(
                bytesToMb(rssBytes),
                -1L,
                bytesToMb(heapUsedBytes),
                bytesToMb(heapMaxBytes)
        );
    }

    private Long readFirstCgroupBytes(List<String> paths) {
        for (String rawPath : paths) {
            Long value = readCgroupBytes(rawPath);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Long readCgroupBytes(String rawPath) {
        try {
            String value = Files.readString(Path.of(rawPath)).trim();
            if (value.isBlank() || "max".equalsIgnoreCase(value)) {
                return null;
            }
            long bytes = Long.parseLong(value);
            if (bytes <= 0L) {
                return null;
            }
            // cgroup v1 often uses near-LONG_MAX for "unlimited"
            if (bytes >= 0x7fff_ffff_fff0_0000L) {
                return null;
            }
            return bytes;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private long bytesToMb(long bytes) {
        return Math.max(0L, bytes / 1024L / 1024L);
    }

    private Long readProcessRssBytes() {
        Path status = Path.of("/proc/self/status");
        try {
            for (String line : Files.readAllLines(status)) {
                if (!line.startsWith("VmRSS:")) {
                    continue;
                }
                String raw = line.substring("VmRSS:".length()).trim();
                Matcher matcher = PROC_STATUS_KB_PATTERN.matcher(raw);
                if (!matcher.matches()) {
                    return null;
                }
                long kb = Long.parseLong(matcher.group(1));
                return kb * 1024L;
            }
            return null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static final class MemoryView {
        private final long rssUsedMb;
        private final long limitMb;
        private final long heapUsedMb;
        private final long heapMaxMb;

        private MemoryView(long rssUsedMb, long limitMb, long heapUsedMb, long heapMaxMb) {
            this.rssUsedMb = rssUsedMb;
            this.limitMb = limitMb;
            this.heapUsedMb = heapUsedMb;
            this.heapMaxMb = heapMaxMb;
        }

        private String limitLabel() {
            return limitMb < 0 ? "n/a" : limitMb + " MB";
        }
    }

    private void sendDeveloperInfoMessage(MessageReceivedEvent event) {
        event.getChannel().sendMessageEmbeds(developerInfoEmbed(event.getJDA()).build())
                .setComponents(ActionRow.of(developerInfoRefreshButton(event.getAuthor().getIdLong())))
                .queue(
                        ignored -> {
                        },
                        error -> logDeveloperReplyError(event.getAuthor(), error)
                );
    }

    private String developerBotVersion() {
        String manifestVersion = DevService.class.getPackage().getImplementationVersion();
        if (manifestVersion != null && !manifestVersion.isBlank()) {
            return manifestVersion;
        }
        try (InputStream in = DevService.class.getClassLoader()
                .getResourceAsStream("META-INF/maven/com.norule/discord-music-bot/pom.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version");
                if (version != null && !version.isBlank()) {
                    return version.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "development";
    }

    private String developerCpuInfo() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        String loadAverage = os.getSystemLoadAverage() < 0
                ? "n/a"
                : String.format(Locale.ROOT, "%.2f", os.getSystemLoadAverage());
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            double processLoad = sunOs.getProcessCpuLoad();
            double systemLoad = sunOs.getCpuLoad();
            String process = processLoad < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", processLoad * 100.0);
            String system = systemLoad < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", systemLoad * 100.0);
            return "Process " + process + " / System " + system + " / Load Avg " + loadAverage;
        }
        return "Load Avg " + loadAverage + " / Cores " + os.getAvailableProcessors();
    }

    private String formatDuration(Duration duration) {
        long seconds = Math.max(0L, duration.getSeconds());
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        if (days > 0) {
            return String.format(Locale.ROOT, "%dd %02dh %02dm %02ds", days, hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02dh %02dm %02ds", hours, minutes, seconds);
    }

    private EmbedBuilder developerErrorEmbed(String message) {
        return developerBaseEmbed("Developer Command")
                .setColor(new Color(231, 76, 60))
                .setDescription(message);
    }

    private EmbedBuilder developerBaseEmbed(String title) {
        return new EmbedBuilder()
                .setColor(new Color(52, 152, 219))
                .setTitle(title)
                .setTimestamp(Instant.now());
    }

    private void sendDeveloperGuildsPage(MessageReceivedEvent event, int page) {
        List<Guild> guilds = event.getJDA().getGuilds();
        int totalPages = developerGuildTotalPages(guilds.size());
        int safePage = clampPage(page, totalPages);
        event.getChannel().sendMessageEmbeds(developerGuildsEmbed(event.getJDA(), safePage).build())
                .setComponents(ActionRow.of(developerGuildButtons(event.getAuthor().getIdLong(), safePage, totalPages)))
                .queue(
                        ignored -> {
                        },
                        error -> logDeveloperReplyError(event.getAuthor(), error)
                );
    }

    private void handleDeveloperGuildsButton(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        String[] parts = id.substring(DEV_GUILDS_BUTTON_PREFIX.length()).split(":", 2);
        if (parts.length != 2) {
            event.deferEdit().queue();
            return;
        }
        Long requesterId = parseLongOrNull(parts[0]);
        Integer page = parseIntOrNull(parts[1]);
        if (requesterId == null || page == null) {
            event.deferEdit().queue();
            return;
        }
        if (!canUseDeveloperControl(requesterId, event.getUser())) {
            event.reply("This developer control is not yours.").setEphemeral(true).queue();
            return;
        }

        int totalPages = developerGuildTotalPages(event.getJDA().getGuilds().size());
        int safePage = clampPage(page, totalPages);
        event.editMessageEmbeds(developerGuildsEmbed(event.getJDA(), safePage).build())
                .setComponents(ActionRow.of(developerGuildButtons(requesterId, safePage, totalPages)))
                .queue();
    }

    private void handleDeveloperInfoRefreshButton(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        Long requesterId = parseLongOrNull(id.substring(DEV_INFO_REFRESH_BUTTON_PREFIX.length()));
        if (requesterId == null) {
            event.deferEdit().queue();
            return;
        }
        if (!canUseDeveloperControl(requesterId, event.getUser())) {
            event.reply("This developer control is not yours.").setEphemeral(true).queue();
            return;
        }

        event.editMessageEmbeds(developerInfoEmbed(event.getJDA()).build())
                .setComponents(ActionRow.of(developerInfoRefreshButton(requesterId)))
                .queue();
    }

    private EmbedBuilder developerGuildsEmbed(JDA jda, int page) {
        List<Guild> guilds = jda.getGuilds();
        int totalPages = developerGuildTotalPages(guilds.size());
        int safePage = clampPage(page, totalPages);
        EmbedBuilder embed = developerBaseEmbed("Developer Guilds")
                .addField("Total", "`" + guilds.size() + "`", true)
                .addField("Page", "`" + (safePage + 1) + " / " + totalPages + "`", true);
        if (guilds.isEmpty()) {
            return embed.setDescription("No joined guilds.");
        }
        int from = safePage * DEV_GUILDS_PAGE_SIZE;
        int to = Math.min(guilds.size(), from + DEV_GUILDS_PAGE_SIZE);
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < to; i++) {
            Guild guild = guilds.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(guild.getName().replace("`", "'"))
                    .append(" (`")
                    .append(guild.getId())
                    .append("`)\n");
        }
        return embed.setDescription(builder.toString());
    }

    private List<Button> developerGuildButtons(long requesterId, int page, int totalPages) {
        return List.of(
                Button.secondary(DEV_GUILDS_BUTTON_PREFIX + requesterId + ":" + (page - 1), "Previous")
                        .withDisabled(page <= 0),
                Button.secondary(DEV_GUILDS_BUTTON_PREFIX + requesterId + ":" + (page + 1), "Next")
                        .withDisabled(page >= totalPages - 1)
        );
    }

    private int developerGuildTotalPages(int guildCount) {
        return Math.max(1, (int) Math.ceil(guildCount / (double) DEV_GUILDS_PAGE_SIZE));
    }

    private int clampPage(int page, int totalPages) {
        return Math.max(0, Math.min(page, Math.max(1, totalPages) - 1));
    }

    private Long parseLongOrNull(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Integer parseIntOrNull(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void logDeveloperCommand(MessageReceivedEvent event, String action, String result) {
        User user = event.getAuthor();
        String location = event.isFromGuild()
                ? "guild=" + event.getGuild().getId() + " channel=" + event.getChannel().getId()
                : "dm";
        System.out.println("[NoRule] Developer command " + result
                + ": action=" + action
                + " user=" + user.getAsTag()
                + " (" + user.getId() + ") "
                + location);
    }
}
