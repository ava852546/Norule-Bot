package com.norule.musicbot;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModerationService {
    public enum NumberChainType {
        IGNORED,
        IGNORED_TEXT,
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

    public static class NumberChainContributor {
        private final long userId;
        private final long count;

        private NumberChainContributor(long userId, long count) {
            this.userId = userId;
            this.count = count;
        }

        public long getUserId() {
            return userId;
        }

        public long getCount() {
            return count;
        }
    }

    private static class ExpressionEvaluationResult {
        private final boolean foundExpression;
        private final Long value;

        private ExpressionEvaluationResult(boolean foundExpression, Long value) {
            this.foundExpression = foundExpression;
            this.value = value;
        }
    }

    private static class GuildData {
        boolean duplicateDetectionEnabled;
        boolean numberChainEnabled;
        Long numberChainChannelId;
        long numberChainNext = 1L;
        Long numberChainLastUserId;
        long numberChainHighestNumber;
        Map<Long, Long> numberChainSuccessContributors = new LinkedHashMap<>();
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

    public void reloadAll() {
        cache.clear();
    }

    public void reload(long guildId) {
        cache.remove(guildId);
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

    public long getNumberChainHighestNumber(long guildId) {
        GuildData data = get(guildId);
        synchronized (data) {
            return Math.max(0L, data.numberChainHighestNumber);
        }
    }

    public List<NumberChainContributor> getTopNumberChainContributors(long guildId, int limit) {
        GuildData data = get(guildId);
        synchronized (data) {
            int safeLimit = Math.max(1, limit);
            return data.numberChainSuccessContributors.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getKey() > 0L
                            && entry.getValue() != null && entry.getValue() > 0L)
                    .sorted(Map.Entry.<Long, Long>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(safeLimit)
                    .map(entry -> new NumberChainContributor(entry.getKey(), entry.getValue()))
                    .toList();
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
            ExpressionEvaluationResult evaluation = evaluateExpressionAsInteger(contentRaw);
            if (!evaluation.foundExpression) {
                return new NumberChainResult(NumberChainType.IGNORED_TEXT, expected, null);
            }
            Long parsed = evaluation.value;

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
                data.numberChainHighestNumber = Math.max(data.numberChainHighestNumber, expected);
                if (userId > 0L) {
                    data.numberChainSuccessContributors.put(userId,
                            Math.max(0L, data.numberChainSuccessContributors.getOrDefault(userId, 0L)) + 1L);
                }
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
            defaults.numberChainHighestNumber = Math.max(0L, readLong(numberChain.get("highestNumber"), 0L));
            Map<String, Object> contributorsMap = asMap(numberChain.get("successContributors"));
            if (contributorsMap.isEmpty()) {
                contributorsMap = asMap(numberChain.get("contributors"));
            }
            for (Map.Entry<String, Object> entry : contributorsMap.entrySet()) {
                Long userId = readLong(entry.getKey());
                Long count = readLong(entry.getValue());
                if (userId != null && userId > 0L && count != null && count > 0L) {
                    defaults.numberChainSuccessContributors.put(userId, count);
                }
            }

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
        chain.put("highestNumber", Math.max(0L, data.numberChainHighestNumber));
        Map<String, Object> contributors = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> entry : data.numberChainSuccessContributors.entrySet()) {
            if (entry.getKey() != null && entry.getKey() > 0L && entry.getValue() != null && entry.getValue() > 0L) {
                contributors.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        chain.put("successContributors", contributors);
        chain.put("contributors", contributors);
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

    private ExpressionEvaluationResult evaluateExpressionAsInteger(String raw) {
        if (raw == null) {
            return new ExpressionEvaluationResult(false, null);
        }
        String text = extractSingleExpression(raw);
        if (text == null) {
            return new ExpressionEvaluationResult(false, null);
        }
        if (!containsDigit(text)) {
            return new ExpressionEvaluationResult(false, null);
        }
        // Number chain accepts pure integer or simple binary expression (e.g. 1+1, 1X1).
        if (text.matches("\\d+")) {
            if (text.length() > 18) {
                return new ExpressionEvaluationResult(true, null);
            }
            try {
                return new ExpressionEvaluationResult(true, Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                return new ExpressionEvaluationResult(true, null);
            }
        }
        // Decimal-only input should be treated as numeric and rounded to nearest integer.
        if (text.matches("\\d+\\.\\d+")) {
            try {
                return new ExpressionEvaluationResult(true, toRoundedLong(new BigDecimal(text)));
            } catch (Exception ignored) {
                return new ExpressionEvaluationResult(true, null);
            }
        }
        String normalizedOp = text.replace('X', '*').replace('x', '*').replace('?', '*');
        if (!normalizedOp.matches("\\d+(?:\\.\\d+)?[+\\-*/]\\d+(?:\\.\\d+)?")) {
            return new ExpressionEvaluationResult(false, null);
        }
        String[] parts = normalizedOp.split("([+\\-*/])", 2);
        if (parts.length != 2) {
            return new ExpressionEvaluationResult(true, null);
        }
        char op = normalizedOp.replaceAll("\\d", "").charAt(0);
        try {
            BigDecimal left = new BigDecimal(parts[0]);
            BigDecimal right = new BigDecimal(parts[1]);
            BigDecimal computed;
            switch (op) {
                case '+' -> computed = left.add(right);
                case '-' -> computed = left.subtract(right);
                case '*' -> computed = left.multiply(right);
                case '/' -> {
                    if (right.compareTo(BigDecimal.ZERO) == 0) {
                        return new ExpressionEvaluationResult(true, null);
                    }
                    computed = left.divide(right, 16, RoundingMode.HALF_UP);
                }
                default -> {
                    return new ExpressionEvaluationResult(true, null);
                }
            }
            return new ExpressionEvaluationResult(true, toRoundedLong(computed));
        } catch (Exception ignored) {
            return new ExpressionEvaluationResult(true, null);
        }
    }

    private Long toRoundedLong(BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException ignored) {
            return null;
        }
    }
    private boolean containsDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String extractSingleExpression(String raw) {
        String normalized = normalizeExpressionSource(raw);
        if (normalized == null) {
            return null;
        }
        String compact = normalized.replaceAll("\\s+", "");
        return compact.isBlank() ? null : compact;
    }

    private String normalizeExpressionSource(String raw) {
        String normalized = raw.trim()
                .replaceAll("https?://\\S+", " ")
                .replaceAll("<@!?\\d+>", " ")
                .replaceAll("<@&\\d+>", " ")
                .replaceAll("<#\\d+>", " ")
                .replaceAll("</[^:>]+:\\d+>", " ")
                .replaceAll("<a?:[^:>]+:\\d+>", " ")
                .replaceAll("<t:\\d+(?::[tTdDfFR])?>", " ")
                .replace('\u00A0', ' ')
                .replace('\\', '/');
        normalized = normalized.replaceAll("(?<=\\d)\\s*[xX]\\s*(?=\\d)", "*");
        return normalized;
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


