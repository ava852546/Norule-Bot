package com.norule.musicbot;

import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.discord.listeners.*;
import com.norule.musicbot.web.*;


import com.norule.musicbot.config.*;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TicketService {
    private static final int TRANSCRIPT_RETENTION_DAYS = 90;

    public static class TranscriptFile {
        private final String fileName;
        private final long size;
        private final long lastModifiedAt;
        private final long channelId;

        private TranscriptFile(String fileName, long size, long lastModifiedAt, long channelId) {
            this.fileName = fileName;
            this.size = size;
            this.lastModifiedAt = lastModifiedAt;
            this.channelId = channelId;
        }

        public String getFileName() {
            return fileName;
        }

        public long getSize() {
            return size;
        }

        public long getLastModifiedAt() {
            return lastModifiedAt;
        }

        public long getChannelId() {
            return channelId;
        }
    }

    public static class TicketRecord {
        private final long channelId;
        private final long ownerId;
        private final String typeLabel;
        private final String summary;
        private final long openedAt;
        private final long lastInteractionAt;
        private final boolean closed;
        private final long closedAt;
        private final String closeReason;
        private final Set<Long> participants;

        private TicketRecord(long channelId,
                             long ownerId,
                             String typeLabel,
                             String summary,
                             long openedAt,
                             long lastInteractionAt,
                             boolean closed,
                             long closedAt,
                             String closeReason,
                             Set<Long> participants) {
            this.channelId = channelId;
            this.ownerId = ownerId;
            this.typeLabel = typeLabel == null ? "" : typeLabel;
            this.summary = summary == null ? "" : summary;
            this.openedAt = openedAt;
            this.lastInteractionAt = lastInteractionAt;
            this.closed = closed;
            this.closedAt = closedAt;
            this.closeReason = closeReason == null ? "" : closeReason;
            this.participants = participants == null ? Set.of() : Set.copyOf(participants);
        }

        public long getChannelId() {
            return channelId;
        }

        public long getOwnerId() {
            return ownerId;
        }

        public String getTypeLabel() {
            return typeLabel;
        }

        public String getSummary() {
            return summary;
        }

        public long getOpenedAt() {
            return openedAt;
        }

        public long getLastInteractionAt() {
            return lastInteractionAt;
        }

        public boolean isClosed() {
            return closed;
        }

        public long getClosedAt() {
            return closedAt;
        }

        public String getCloseReason() {
            return closeReason;
        }

        public Set<Long> getParticipants() {
            return participants;
        }

        private TicketRecord touch(long userId, long timestamp) {
            Set<Long> nextParticipants = new LinkedHashSet<>(participants);
            if (userId > 0L) {
                nextParticipants.add(userId);
            }
            return new TicketRecord(channelId, ownerId, typeLabel, summary, openedAt, timestamp, closed, closedAt, closeReason, nextParticipants);
        }

        private TicketRecord close(String reason, long timestamp) {
            return new TicketRecord(channelId, ownerId, typeLabel, summary, openedAt, timestamp, true, timestamp, reason, participants);
        }

        private TicketRecord reopen(long timestamp) {
            return new TicketRecord(channelId, ownerId, typeLabel, summary, openedAt, timestamp, false, 0L, "", participants);
        }
    }

    private final Path storageDir;
    private final Path transcriptDir;
    private final Map<Long, Map<Long, TicketRecord>> guildCache = new ConcurrentHashMap<>();
    private final Yaml yaml;

    public TicketService(Path storageDir, Path transcriptDir) {
        this.storageDir = storageDir;
        this.transcriptDir = transcriptDir;
        this.yaml = new Yaml(yamlOptions());
        try {
            Files.createDirectories(storageDir);
            Files.createDirectories(transcriptDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize ticket directories", e);
        }
    }

    public void reloadAll() {
        guildCache.clear();
    }

    public void reload(long guildId) {
        guildCache.remove(guildId);
    }

    public TicketRecord createTicket(long guildId, long channelId, long ownerId, String typeLabel, String summary) {
        Map<Long, TicketRecord> map = ticketsForGuild(guildId);
        synchronized (map) {
            long now = System.currentTimeMillis();
            Set<Long> participants = new LinkedHashSet<>();
            participants.add(ownerId);
            TicketRecord created = new TicketRecord(
                    channelId,
                    ownerId,
                    typeLabel,
                    summary,
                    now,
                    now,
                    false,
                    0L,
                    "",
                    participants
            );
            map.put(channelId, created);
            saveGuild(guildId, map);
            return created;
        }
    }

    public TicketRecord getTicket(long guildId, long channelId) {
        return ticketsForGuild(guildId).get(channelId);
    }

    public boolean isTicketChannel(long guildId, long channelId) {
        return ticketsForGuild(guildId).containsKey(channelId);
    }

    public Collection<TicketRecord> getOpenTickets(long guildId) {
        List<TicketRecord> list = new ArrayList<>();
        for (TicketRecord record : ticketsForGuild(guildId).values()) {
            if (!record.isClosed()) {
                list.add(record);
            }
        }
        return list;
    }

    public int countOpenTicketsByOwner(long guildId, long ownerId) {
        if (ownerId <= 0L) {
            return 0;
        }
        int count = 0;
        for (TicketRecord record : ticketsForGuild(guildId).values()) {
            if (!record.isClosed() && record.getOwnerId() == ownerId) {
                count++;
            }
        }
        return count;
    }

    public void touchTicketMessage(long guildId, long channelId, long userId) {
        Map<Long, TicketRecord> map = ticketsForGuild(guildId);
        synchronized (map) {
            TicketRecord record = map.get(channelId);
            if (record == null || record.isClosed()) {
                return;
            }
            map.put(channelId, record.touch(userId, System.currentTimeMillis()));
            saveGuild(guildId, map);
        }
    }

    public TicketRecord closeTicket(long guildId, long channelId, String reason) {
        Map<Long, TicketRecord> map = ticketsForGuild(guildId);
        synchronized (map) {
            TicketRecord record = map.get(channelId);
            if (record == null) {
                return null;
            }
            TicketRecord closed = record.isClosed() ? record : record.close(reason, System.currentTimeMillis());
            map.put(channelId, closed);
            saveGuild(guildId, map);
            return closed;
        }
    }

    public TicketRecord reopenTicket(long guildId, long channelId) {
        Map<Long, TicketRecord> map = ticketsForGuild(guildId);
        synchronized (map) {
            TicketRecord record = map.get(channelId);
            if (record == null) {
                return null;
            }
            TicketRecord reopened = record.isClosed() ? record.reopen(System.currentTimeMillis()) : record;
            map.put(channelId, reopened);
            saveGuild(guildId, map);
            return reopened;
        }
    }

    public TicketRecord deleteTicket(long guildId, long channelId) {
        Map<Long, TicketRecord> map = ticketsForGuild(guildId);
        synchronized (map) {
            TicketRecord removed = map.remove(channelId);
            if (removed != null) {
                saveGuild(guildId, map);
            }
            return removed;
        }
    }

    public Path writeTranscriptHtml(long guildId,
                                    String guildName,
                                    TextChannel channel,
                                    TicketRecord record,
                                    String closedByTag) {
        List<Message> messages = fetchMessages(channel, 1500);
        String html = buildTranscriptHtml(guildName, channel, record, messages, closedByTag);
        Path dir = transcriptDir.resolve(String.valueOf(guildId));
        try {
            cleanupOldTranscripts(guildId, TRANSCRIPT_RETENTION_DAYS);
            Files.createDirectories(dir);
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            Path file = dir.resolve(channel.getId() + "-" + timestamp + ".html");
            Files.writeString(file, html, StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    public List<TranscriptFile> listTranscripts(long guildId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        cleanupOldTranscripts(guildId, TRANSCRIPT_RETENTION_DAYS);
        Path dir = transcriptDir.resolve(String.valueOf(guildId));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        List<TranscriptFile> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.html")) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                long size = Files.size(path);
                long lastModified = Files.getLastModifiedTime(path).toMillis();
                long channelId = parseChannelIdFromFileName(fileName);
                files.add(new TranscriptFile(fileName, size, lastModified, channelId));
            }
        } catch (Exception ignored) {
        }

        files.sort((a, b) -> Long.compare(b.getLastModifiedAt(), a.getLastModifiedAt()));
        if (files.size() > limit) {
            return List.copyOf(files.subList(0, limit));
        }
        return List.copyOf(files);
    }

    public Path resolveTranscriptFile(long guildId, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String normalized = fileName.trim();
        if (!normalized.endsWith(".html")
                || normalized.contains("..")
                || normalized.contains("/")
                || normalized.contains("\\")) {
            return null;
        }

        Path dir = transcriptDir.resolve(String.valueOf(guildId));
        Path file = dir.resolve(normalized).normalize();
        if (!file.startsWith(dir.normalize())) {
            return null;
        }
        return file;
    }

    public boolean deleteTranscript(long guildId, String fileName) {
        Path file = resolveTranscriptFile(guildId, fileName);
        if (file == null || !Files.exists(file) || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            return Files.deleteIfExists(file);
        } catch (Exception ignored) {
            return false;
        }
    }

    public int cleanupOldTranscripts(long guildId, int retentionDays) {
        if (retentionDays <= 0) {
            return 0;
        }
        Path dir = transcriptDir.resolve(String.valueOf(guildId));
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        long threshold = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L);
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.html")) {
            for (Path path : stream) {
                try {
                    long modified = Files.getLastModifiedTime(path).toMillis();
                    if (modified < threshold) {
                        Files.deleteIfExists(path);
                        removed++;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return removed;
    }

    private long parseChannelIdFromFileName(String fileName) {
        if (fileName == null) {
            return 0L;
        }
        int idx = fileName.indexOf('-');
        if (idx <= 0) {
            return 0L;
        }
        try {
            return Long.parseLong(fileName.substring(0, idx));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private List<Message> fetchMessages(TextChannel channel, int max) {
        List<Message> all = new ArrayList<>();
        try {
            var history = channel.getHistory();
            while (all.size() < max) {
                List<Message> batch = history.retrievePast(Math.min(100, max - all.size())).complete();
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                all.addAll(batch);
                if (batch.size() < 100) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        Collections.reverse(all);
        return all;
    }

    private String buildTranscriptHtml(String guildName,
                                       TextChannel channel,
                                       TicketRecord record,
                                       List<Message> messages,
                                       String closedByTag) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\"/>")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>")
                .append("<title>Ticket Transcript</title>")
                .append("<style>")
                .append("body{margin:0;background:#1e1f22;color:#dbdee1;font:14px/1.5 Segoe UI,Arial,sans-serif;}")
                .append(".wrap{max-width:980px;margin:24px auto;padding:0 16px;}")
                .append(".card{background:#2b2d31;border:1px solid #3f4147;border-radius:12px;padding:14px;}")
                .append(".meta{color:#b5bac1;font-size:13px;margin-bottom:12px;}")
                .append(".msg{padding:8px 0;border-top:1px solid #35373c;}")
                .append(".author{font-weight:600;color:#f2f3f5;}")
                .append(".time{color:#9aa0a6;font-size:12px;margin-left:8px;}")
                .append(".content{white-space:pre-wrap;word-break:break-word;margin-top:2px;}")
                .append(".attach a{color:#7ab7ff;text-decoration:none;}")
                .append("</style></head><body><div class=\"wrap\"><div class=\"card\">");

        sb.append("<h2>Ticket Transcript</h2>");
        sb.append("<div class=\"meta\">Guild: ").append(escape(guildName))
                .append(" | Channel: #").append(escape(channel.getName()))
                .append(" | Type: ").append(escape(record.getTypeLabel()))
                .append(" | Owner: ").append(record.getOwnerId())
                .append(" | Closed By: ").append(escape(closedByTag))
                .append(" | Opened: ").append(formatTs(record.getOpenedAt()))
                .append(" | Closed: ").append(formatTs(record.getClosedAt() > 0 ? record.getClosedAt() : System.currentTimeMillis()))
                .append("</div>");

        if (!record.getSummary().isBlank()) {
            sb.append("<div class=\"meta\">Summary: ").append(escape(record.getSummary())).append("</div>");
        }
        if (!record.getCloseReason().isBlank()) {
            sb.append("<div class=\"meta\">Close reason: ").append(escape(record.getCloseReason())).append("</div>");
        }

        for (Message message : messages) {
            sb.append("<div class=\"msg\">");
            sb.append("<span class=\"author\">").append(escape(message.getAuthor().getAsTag())).append("</span>");
            sb.append("<span class=\"time\">").append(escape(formatTs(message.getTimeCreated().toInstant().toEpochMilli()))).append("</span>");
            String content = message.getContentDisplay();
            if (content != null && !content.isBlank()) {
                sb.append("<div class=\"content\">").append(escape(content)).append("</div>");
            }
            if (!message.getAttachments().isEmpty()) {
                sb.append("<div class=\"attach\">");
                for (Message.Attachment attachment : message.getAttachments()) {
                    sb.append("<div><a href=\"").append(escapeAttr(attachment.getUrl())).append("\" target=\"_blank\" rel=\"noopener\">")
                            .append(escape(attachment.getFileName()))
                            .append("</a></div>");
                }
                sb.append("</div>");
            }
            sb.append("</div>");
        }

        sb.append("</div></div></body></html>");
        return sb.toString();
    }

    private String formatTs(long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String escapeAttr(String text) {
        return escape(text).replace("\"", "&quot;");
    }

    private Map<Long, TicketRecord> ticketsForGuild(long guildId) {
        return guildCache.computeIfAbsent(guildId, this::loadGuild);
    }

    private Map<Long, TicketRecord> loadGuild(long guildId) {
        Path file = file(guildId);
        if (!Files.exists(file)) {
            return new ConcurrentHashMap<>();
        }
        Map<Long, TicketRecord> loaded = new ConcurrentHashMap<>();
        try (var in = Files.newInputStream(file)) {
            Object rootObj = yaml.load(in);
            Map<String, Object> root = asMap(rootObj);
            Object ticketsObj = root.get("tickets");
            if (!(ticketsObj instanceof Iterable<?> rows)) {
                return loaded;
            }
            for (Object rowObj : rows) {
                Map<String, Object> row = asMap(rowObj);
                long channelId = readLong(row.get("channelId"), 0L);
                long ownerId = readLong(row.get("ownerId"), 0L);
                if (channelId <= 0L || ownerId <= 0L) {
                    continue;
                }
                Set<Long> participants = new LinkedHashSet<>();
                Object participantsObj = row.get("participants");
                if (participantsObj instanceof Iterable<?> iterable) {
                    for (Object p : iterable) {
                        long id = readLong(p, 0L);
                        if (id > 0L) {
                            participants.add(id);
                        }
                    }
                }
                if (participants.isEmpty()) {
                    participants.add(ownerId);
                }
                TicketRecord record = new TicketRecord(
                        channelId,
                        ownerId,
                        readString(row.get("typeLabel")),
                        readString(row.get("summary")),
                        readLong(row.get("openedAt"), System.currentTimeMillis()),
                        readLong(row.get("lastInteractionAt"), System.currentTimeMillis()),
                        readBoolean(row.get("closed")),
                        readLong(row.get("closedAt"), 0L),
                        readString(row.get("closeReason")),
                        participants
                );
                loaded.put(channelId, record);
            }
        } catch (Exception ignored) {
        }
        return loaded;
    }

    private void saveGuild(long guildId, Map<Long, TicketRecord> map) {
        Path file = file(guildId);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("guildId", String.valueOf(guildId));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TicketRecord record : map.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channelId", String.valueOf(record.getChannelId()));
            row.put("ownerId", String.valueOf(record.getOwnerId()));
            row.put("typeLabel", record.getTypeLabel());
            row.put("summary", record.getSummary());
            row.put("openedAt", record.getOpenedAt());
            row.put("lastInteractionAt", record.getLastInteractionAt());
            row.put("closed", record.isClosed());
            row.put("closedAt", record.getClosedAt());
            row.put("closeReason", record.getCloseReason());
            List<String> participants = record.getParticipants().stream().map(String::valueOf).toList();
            row.put("participants", participants);
            rows.add(row);
        }
        root.put("tickets", rows);

        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                yaml.dump(root, writer);
            }
        } catch (Exception ignored) {
        }
    }

    private Path file(long guildId) {
        return storageDir.resolve(guildId + ".yml");
    }

    private DumperOptions yamlOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return options;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String readString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private long readLong(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean readBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
