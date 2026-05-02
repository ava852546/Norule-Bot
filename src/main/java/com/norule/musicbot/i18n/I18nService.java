package com.norule.musicbot.i18n;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class I18nService {
    private final String defaultLanguage;
    private final Map<String, Map<String, String>> bundles;

    private I18nService(String defaultLanguage, Map<String, Map<String, String>> bundles) {
        this.defaultLanguage = normalize(defaultLanguage);
        this.bundles = bundles;
    }

    public static I18nService load(Path languageDir, String defaultLanguage) {
        Map<String, Map<String, String>> bundles = new HashMap<>();
        putDefaultBundle(bundles, "zh-TW", "defaults/lang/zh-TW.yml");
        putDefaultBundle(bundles, "zh-CN", "defaults/lang/zh-CN.yml");
        putDefaultBundle(bundles, "en", "defaults/lang/en.yml");
        try {
            Files.createDirectories(languageDir);
            Files.list(languageDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        int dot = fileName.lastIndexOf('.');
                        String language = dot > 0 ? fileName.substring(0, dot) : fileName;
                        Map<String, String> bundle = readBundle(path);
                        if (!bundle.isEmpty()) {
                            String normalizedLanguage = normalize(language);
                            bundles.put(normalizedLanguage, mergeBundle(bundles.get(normalizedLanguage), bundle));
                        }
                    });
        } catch (IOException ignored) {
        }
        return new I18nService(defaultLanguage, bundles);
    }

    public String normalizeLanguage(String language) {
        return normalize(language);
    }

    public boolean hasLanguage(String language) {
        String normalized = normalize(language);
        if (!isBotLanguage(normalized)) {
            return false;
        }
        return isStandardLanguage(normalized) || bundles.containsKey(normalized);
    }

    public String t(String language, String key) {
        String normalized = normalize(language);
        String value = lookup(normalized, key);
        if (value != null) {
            return value;
        }
        if (!normalized.equals(defaultLanguage)) {
            value = lookup(defaultLanguage, key);
            if (value != null) {
                return value;
            }
        }
        if (!"en".equals(normalized)) {
            value = lookup("en", key);
            if (value != null) {
                return value;
            }
        }
        if (!"zh-TW".equalsIgnoreCase(normalized)) {
            value = lookup("zh-TW", key);
            if (value != null) {
                return value;
            }
        }
        value = lookup("zh-TW", key);
        if (value != null) {
            return value;
        }
        return key;
    }

    public String t(String language, String key, Map<String, String> placeholders) {
        String text = t(language, key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return text;
    }

    public Map<String, String> getAvailableLanguages() {
        LinkedHashMap<String, String> languages = new LinkedHashMap<>();
        addAvailableLanguage(languages, "zh-TW");
        addAvailableLanguage(languages, "zh-CN");
        addAvailableLanguage(languages, "en");
        bundles.keySet().stream()
                .filter(I18nService::isBotLanguage)
                .filter(lang -> !languages.containsKey(lang))
                .sorted()
                .forEach(lang -> languages.put(lang, resolveLanguageDisplayName(lang)));
        return languages;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readBundle(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            Object root = new Yaml().load(in);
            if (root instanceof Map<?, ?> rootMap) {
                Map<String, String> values = new HashMap<>();
                flatten("", (Map<String, Object>) rootMap, values);
                return values;
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readBundleResource(String resourcePath) {
        try (InputStream in = I18nService.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Map.of();
            }
            Object root = new Yaml().load(in);
            if (root instanceof Map<?, ?> rootMap) {
                Map<String, String> values = new HashMap<>();
                flatten("", (Map<String, Object>) rootMap, values);
                return values;
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> source, Map<String, String> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                flatten(key, (Map<String, Object>) child, target);
            } else {
                target.put(key, value == null ? "" : String.valueOf(value));
            }
        }
    }

    private String lookup(String language, String key) {
        Map<String, String> bundle = bundles.get(language);
        return bundle == null ? null : bundle.get(key);
    }

    private String resolveLanguageDisplayName(String language) {
        String normalized = normalize(language);
        if ("zh-TW".equals(normalized)) {
            return "\u7e41\u9ad4\u4e2d\u6587";
        }
        if ("zh-CN".equals(normalized)) {
            return "\u7b80\u4f53\u4e2d\u6587";
        }
        if ("en".equals(normalized)) {
            return "English";
        }
        Map<String, String> bundle = bundles.get(language);
        if (bundle == null || bundle.isEmpty()) {
            return language;
        }
        String byLang = bundle.get("lang");
        if (byLang != null && !byLang.isBlank()) {
            return byLang;
        }
        String byNested = bundle.get("language.name");
        if (byNested != null && !byNested.isBlank()) {
            return byNested;
        }
        return language;
    }

    private static void putDefaultBundle(Map<String, Map<String, String>> bundles, String language, String resourcePath) {
        Map<String, String> defaults = readBundleResource(resourcePath);
        if (!defaults.isEmpty()) {
            bundles.put(normalize(language), defaults);
        }
    }

    private static Map<String, String> mergeBundle(Map<String, String> base, Map<String, String> override) {
        if ((base == null || base.isEmpty()) && (override == null || override.isEmpty())) {
            return Map.of();
        }
        Map<String, String> merged = new HashMap<>();
        if (base != null && !base.isEmpty()) {
            merged.putAll(base);
        }
        if (override != null && !override.isEmpty()) {
            merged.putAll(override);
        }
        return merged;
    }

    private static String normalize(String language) {
        if (language == null || language.isBlank()) {
            return "en";
        }
        String trimmed = language.trim();
        String normalized = trimmed.replace('_', '-').toLowerCase();
        if (normalized.startsWith("en")) {
            return "en";
        }
        if (normalized.equals("zh-tw") || normalized.equals("zh-hant") || normalized.equals("zh-hk")) {
            return "zh-TW";
        }
        if (normalized.equals("zh-cn") || normalized.equals("zh-hans") || normalized.equals("zh-sg")) {
            return "zh-CN";
        }
        if (normalized.equals("zh")) {
            return "zh-TW";
        }
        return trimmed;
    }

    private static boolean isBotLanguage(String language) {
        return !language.toLowerCase().startsWith("web-");
    }

    private void addAvailableLanguage(Map<String, String> languages, String language) {
        languages.put(language, resolveLanguageDisplayName(language));
    }

    private static boolean isStandardLanguage(String language) {
        return "zh-TW".equals(language) || "zh-CN".equals(language) || "en".equals(language);
    }
}



