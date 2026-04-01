package com.norule.musicbot;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModerationService {
    public enum NumberChainType {
        IGNORED,
        ACCEPTED,
        REJECT_SAME_USER,
        RESET
    }

    public static class NumberChainResult {
        private final NumberChainType type;
        private final long expected;
        private final Long parsedValue;

        private NumberChainResult(NumberChainType type, long expected, Long parsedValue) {
            this.type = type;
            this.expected = expected;
            this.parsedValue = parsedValue;
        }

        public NumberChainType getType() {
            return type;
        }

        public long getExpected() {
            return expected;
        }

        public Long getParsedValue() {
            return parsedValue;
        }
    }

    private static class GuildData {
        boolean duplicateDetectionEnabled;
        boolean numberChainEnabled;
        Long numberChainChannelId;
        long numberChainNext = 1L;
        Long numberChainLastUserId;
        Map<Long, Integer> warnings = new LinkedHashMap<>();
    }

    private final Path dir;
    private final Map<Long, GuildData> cache = new ConcurrentHashMap<>();

    public ModerationService(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create moderation data directory: " + dir.toAbsolutePath(), e);
        }
    }

    public int getWarnings(long guildId, long userId) {
        GuildData data = get(guildId);
        synchronized (data) {
            return Math.max(0, data.warnings.getOrDefault(userId, 0));
        }
    }

    public int addWarnings(long guildId, long userId, int amount) {
        GuildData data = get(guildId);
        synchronized (data) {
            int next = Math.max(0, data.warnings.getOrDefault(userId, 0) + Math.max(1, amount));
            data.warnings.put(userId, next);
            save(guildId, data);
            return next;
        }
    }

    public int removeWarnings(long guildId, long userId, int amount) {
        GuildData data = get(guildId);
        synchronized (data) {
            int next = Math.max(0, data.warnings.getOrDefault(userId, 0) - Math.max(1, amount));
            if (next <= 0) {
                data.warnings.remove(userId);
            } else {
                data.warnings.put(userId, next);
            }
            save(guildId, data);
            return next;
        }
    }

    public void clearWarnings(long guildId, long userId) {
        GuildData data = get(guildId);
        synchronized (data) {
            data.warnings.remove(userId);
            save(guildId, data);
        }
    }

    public boolean isDuplicateDetectionEnabled(long guildId) {
        GuildData data = get(guildId);
        synchronized (data) {
            return data.duplicateDetectionEnabled;
        }
    }

    public boolean setDuplicateDetectionEnabled(long guildId, boolean enabled) {
        GuildData data = get(guildId);
        synchronized (data) {
            data.duplicateDetectionEnabled = enabled;
            save(guildId, data);
            return data.duplicateDetectionEnabled;
        }
    }

    public boolean isNumberChainEnabled(long guildId) {
        GuildData data = get(guildId);
        synchronized (data) {
            return data.numberChainEnabled;
        }
    }

    public boolean setNumberChainEnabled(long guildId, boolean enabled) {
        GuildData data = get(guildId);
        synchronized (data) {
            data.numberChainEnabled = enabled;
            save(guildId, data);
            return data.numberChainEnabled;
        }
    }

    public Long getNumberChainChannelId(long guildId) {
        GuildData data = get(guildId);
        synchronized (data) {
            return data.numberChainChannelId;
        }
    }

    public Long setNumberChainChannelId(long guildId, Long channelId) {
        GuildData data = get(guildId);
        synchronized (data) {
            data.numberChainChannelId = channelId;
            data.numberChainNext = 1L;
            save(guildId, data);
            return data.numberChainChannelId;
        }
    }

    public long getNumberChainNext(long guildId) {
        GuildData data = get(guildId);
        synchronized (data) {
            return data.numberChainNext;
        }
    }

    public void resetNumberChain(long guildId) {
        GuildData data = get(guildId);
        synchronized (data) {
            data.numberChainNext = 1L;
            save(guildId, data);
        }
    }

    public NumberChainResult processNumberChainMessage(long guildId, long channelId, String contentRaw) {
        return processNumberChainMessage(guildId, channelId, 0L, contentRaw);
    }

    public NumberChainResult processNumberChainMessage(long guildId, long channelId, long userId, String contentRaw) {
        GuildData data = get(guildId);
        synchronized (data) {
            if (!data.numberChainEnabled || data.numberChainChannelId == null || data.numberChainChannelId != channelId) {
                return new NumberChainResult(NumberChainType.IGNORED, data.numberChainNext, null);
            }
            long expected = data.numberChainNext;
            Long parsed = evaluateExpressionAsInteger(contentRaw);

            if (parsed != null && parsed == expected) {
                if (userId > 0
                        && data.numberChainLastUserId != null
                        && data.numberChainLastUserId == userId
                        && expected > 1L) {
                    data.numberChainNext = 1L;
                    data.numberChainLastUserId = null;
                    save(guildId, data);
                    return new NumberChainResult(NumberChainType.REJECT_SAME_USER, expected, parsed);
                }
                data.numberChainNext = expected + 1L;
                data.numberChainLastUserId = userId;
                save(guildId, data);
                return new NumberChainResult(NumberChainType.ACCEPTED, expected, parsed);
            }

            data.numberChainNext = 1L;
            data.numberChainLastUserId = null;
            save(guildId, data);
            return new NumberChainResult(NumberChainType.RESET, expected, parsed);
        }
    }

    private GuildData get(long guildId) {
        return cache.computeIfAbsent(guildId, this::loadGuildData);
    }

    private GuildData loadGuildData(long guildId) {
        Path file = file(guildId);
        GuildData defaults = new GuildData();
        if (!Files.exists(file)) {
            return defaults;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Object rootObj = new Yaml().load(in);
            Map<String, Object> root = asMap(rootObj);
            defaults.duplicateDetectionEnabled = readBoolean(root.get("duplicateDetectionEnabled"), false);
            Map<String, Object> numberChain = asMap(root.get("numberChain"));
            defaults.numberChainEnabled = readBoolean(numberChain.get("enabled"), false);
            defaults.numberChainChannelId = readLong(numberChain.get("channelId"));
            defaults.numberChainNext = Math.max(1L, readLong(numberChain.get("nextNumber"), 1L));
            defaults.numberChainLastUserId = readLong(numberChain.get("lastUserId"));

            Map<String, Object> warningsMap = asMap(root.get("warnings"));
            for (Map.Entry<String, Object> entry : warningsMap.entrySet()) {
                Long userId = readLong(entry.getKey());
                Integer value = readInt(entry.getValue());
                if (userId != null && value != null && value > 0) {
                    defaults.warnings.put(userId, value);
                }
            }
        } catch (Exception ignored) {
        }
        return defaults;
    }

    private void save(long guildId, GuildData data) {
        Path file = file(guildId);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("guildId", String.valueOf(guildId));
        root.put("duplicateDetectionEnabled", data.duplicateDetectionEnabled);

        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("enabled", data.numberChainEnabled);
        chain.put("channelId", data.numberChainChannelId == null ? "" : String.valueOf(data.numberChainChannelId));
        chain.put("nextNumber", data.numberChainNext);
        chain.put("lastUserId", data.numberChainLastUserId == null ? "" : String.valueOf(data.numberChainLastUserId));
        root.put("numberChain", chain);

        Map<String, Object> warnings = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : data.warnings.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                warnings.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        root.put("warnings", warnings);

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

    private Long evaluateExpressionAsInteger(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().replace(" ", "");
        if (text.isBlank() || text.length() > 64) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isDigit(c) && c != '+' && c != '-' && c != '*' && c != '/' && c != '(' && c != ')') {
                return null;
            }
        }
        try {
            ExpressionParser parser = new ExpressionParser(text);
            double value = parser.parse();
            if (!Double.isFinite(value)) {
                return null;
            }
            long rounded = Math.round(value);
            if (Math.abs(value - rounded) > 1e-9) {
                return null;
            }
            return rounded;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static class ExpressionParser {
        private final String s;
        private int i = 0;

        private ExpressionParser(String s) {
            this.s = s;
        }

        private double parse() {
            double value = parseExpression();
            if (i != s.length()) {
                throw new IllegalArgumentException("Unexpected token");
            }
            return value;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '+') {
                    i++;
                    value += parseTerm();
                } else if (c == '-') {
                    i++;
                    value -= parseTerm();
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseTerm() {
            double value = parseFactor();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '*') {
                    i++;
                    value *= parseFactor();
                } else if (c == '/') {
                    i++;
                    double divisor = parseFactor();
                    if (Math.abs(divisor) < 1e-12) {
                        throw new IllegalArgumentException("Divide by zero");
                    }
                    value /= divisor;
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseFactor() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("Unexpected end");
            }
            char c = s.charAt(i);
            if (c == '+') {
                i++;
                return parseFactor();
            }
            if (c == '-') {
                i++;
                return -parseFactor();
            }
            if (c == '(') {
                i++;
                double value = parseExpression();
                if (i >= s.length() || s.charAt(i) != ')') {
                    throw new IllegalArgumentException("Missing )");
                }
                i++;
                return value;
            }
            return parseNumber();
        }

        private double parseNumber() {
            int start = i;
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("Expected number");
            }
            return Double.parseDouble(s.substring(start, i));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
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

    private long readLong(Object value, long defaultValue) {
        Long parsed = readLong(value);
        return parsed == null ? defaultValue : parsed;
    }

    private Integer readInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            return null;
        }
    }
}
