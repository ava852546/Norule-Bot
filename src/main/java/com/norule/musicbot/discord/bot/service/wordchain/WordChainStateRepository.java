package com.norule.musicbot.discord.bot.service.wordchain;

import com.norule.musicbot.domain.wordchain.WordChainState;
import com.norule.musicbot.domain.wordchain.WordChainPlayerStats;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class WordChainStateRepository {
    private final Path dir;

    public WordChainStateRepository(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create wordchain directory: " + dir.toAbsolutePath(), e);
        }
    }

    public WordChainState load(long guildId) {
        Path file = file(guildId);
        if (!Files.exists(file)) {
            return WordChainState.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            Object rootObj = new Yaml().load(in);
            Map<String, Object> root = asMap(rootObj);
            boolean enabled = readBoolean(root.get("enabled"), false);
            Long channelId = readLong(root.get("channelId"));
            String lastWord = readString(root.get("lastWord"));
            int chainCount = Math.max(0, readInt(root.get("chainCount"), 0));
            LinkedHashSet<String> usedWords = new LinkedHashSet<>();
            for (Object one : readList(root.get("usedWords"))) {
                String value = readString(one);
                if (!value.isBlank()) {
                    usedWords.add(value);
                }
            }
            LinkedHashMap<Long, WordChainPlayerStats> playerStats = new LinkedHashMap<>();
            Map<String, Object> statsMap = asMap(root.get("playerStats"));
            for (Map.Entry<String, Object> entry : statsMap.entrySet()) {
                Long userId = readLong(entry.getKey());
                if (userId == null || userId <= 0L) {
                    continue;
                }
                Map<String, Object> one = asMap(entry.getValue());
                long totalMessages = Math.max(0L, readLong(one.get("totalMessages"), 0L));
                long successCount = Math.max(0L, readLong(one.get("successCount"), 0L));
                long invalidCount = Math.max(0L, readLong(one.get("invalidCount"), 0L));
                playerStats.put(userId, new WordChainPlayerStats(totalMessages, successCount, invalidCount));
            }
            return new WordChainState(enabled, channelId, lastWord, chainCount, usedWords, playerStats);
        } catch (Exception ignored) {
            return WordChainState.empty();
        }
    }

    public void save(long guildId, WordChainState state) {
        Path file = file(guildId);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("guildId", String.valueOf(guildId));
        root.put("enabled", state.isEnabled());
        root.put("channelId", state.getChannelId() == null ? "" : String.valueOf(state.getChannelId()));
        root.put("lastWord", state.getLastWord());
        root.put("chainCount", state.getChainCount());
        root.put("usedWords", new ArrayList<>(state.getUsedWords()));
        Map<String, Object> statsMap = new LinkedHashMap<>();
        for (Map.Entry<Long, WordChainPlayerStats> entry : state.getPlayerStats().entrySet()) {
            if (entry.getKey() == null || entry.getKey() <= 0L || entry.getValue() == null) {
                continue;
            }
            WordChainPlayerStats stats = entry.getValue();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("totalMessages", Math.max(0L, stats.totalMessages()));
            one.put("successCount", Math.max(0L, stats.successCount()));
            one.put("invalidCount", Math.max(0L, stats.invalidCount()));
            statsMap.put(String.valueOf(entry.getKey()), one);
        }
        root.put("playerStats", statsMap);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        Yaml yaml = new Yaml(options);
        try (Writer writer = Files.newBufferedWriter(file)) {
            yaml.dump(root, writer);
        } catch (Exception ignored) {
        }
    }

    private Path file(long guildId) {
        return dir.resolve(guildId + ".yml");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private List<Object> readList(Object obj) {
        if (obj instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private boolean readBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
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

    private int readInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private long readLong(Object value, long defaultValue) {
        Long parsed = readLong(value);
        return parsed == null ? defaultValue : parsed;
    }

    private String readString(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }
}
