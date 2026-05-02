package com.norule.musicbot;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HoneypotService {
    private final Path dataFile;
    private final Map<Long, Long> channelByGuild = new ConcurrentHashMap<>();
    private final Yaml yaml;

    public HoneypotService(Path dataDir) {
        this.dataFile = dataDir.resolve("honeypots.yml");
        this.yaml = new Yaml(yamlOptions());
        try {
            Files.createDirectories(dataDir);
        } catch (Exception ignored) {
        }
        load();
    }

    public Long getChannelId(long guildId) {
        return channelByGuild.get(guildId);
    }

    public synchronized void reloadAll() {
        channelByGuild.clear();
        load();
    }

    public boolean isHoneypotChannel(long guildId, long channelId) {
        Long configured = channelByGuild.get(guildId);
        return configured != null && configured == channelId;
    }

    public synchronized void setChannel(long guildId, long channelId) {
        channelByGuild.put(guildId, channelId);
        save();
    }

    @SuppressWarnings("unchecked")
    private synchronized void load() {
        if (!Files.exists(dataFile)) {
            return;
        }
        try (InputStream in = Files.newInputStream(dataFile)) {
            Object rootObj = yaml.load(in);
            if (!(rootObj instanceof Map<?, ?> root)) {
                return;
            }
            Object entriesObj = root.get("entries");
            if (!(entriesObj instanceof Iterable<?> entries)) {
                return;
            }
            for (Object rowObj : entries) {
                if (!(rowObj instanceof Map<?, ?> row)) {
                    continue;
                }
                Long guildId = readLong(((Map<String, Object>) row).get("guildId"));
                Long channelId = readLong(((Map<String, Object>) row).get("channelId"));
                if (guildId != null && guildId > 0 && channelId != null && channelId > 0) {
                    channelByGuild.put(guildId, channelId);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            var entries = new java.util.ArrayList<Map<String, Object>>();
            for (Map.Entry<Long, Long> entry : channelByGuild.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("guildId", String.valueOf(entry.getKey()));
                row.put("channelId", String.valueOf(entry.getValue()));
                entries.add(row);
            }
            root.put("entries", entries);
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(dataFile), StandardCharsets.UTF_8)) {
                yaml.dump(root, writer);
            }
        } catch (Exception ignored) {
        }
    }

    private Long readLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return null;
        }
    }

    private DumperOptions yamlOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return options;
    }
}

