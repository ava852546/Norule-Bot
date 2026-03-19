package com.norule.musicbot;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class I18nService {
    private final String defaultLanguage;
    private final Map<String, Map<String, String>> bundles;

    private I18nService(String defaultLanguage, Map<String, Map<String, String>> bundles) {
        this.defaultLanguage = normalize(defaultLanguage);
        this.bundles = bundles;
    }

    public static I18nService load(Path languageDir, String defaultLanguage) {
        Map<String, Map<String, String>> bundles = new HashMap<>();
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
                            bundles.put(normalize(language), bundle);
                        }
                    });
        } catch (IOException ignored) {
        }
        if (!bundles.containsKey("zh-TW") || bundles.get("zh-TW").isEmpty()) {
            Map<String, String> zhFallback = readBundleResource("defaults/lang/zh-TW.yml");
            if (!zhFallback.isEmpty()) {
                bundles.put("zh-TW", zhFallback);
            }
        }
        if (!bundles.containsKey("en") || bundles.get("en").isEmpty()) {
            Map<String, String> enFallback = readBundleResource("defaults/lang/en.yml");
            bundles.put("en", enFallback.isEmpty() ? Map.of() : enFallback);
        }
        return new I18nService(defaultLanguage, bundles);
    }

    public String normalizeLanguage(String language) {
        return normalize(language);
    }

    public boolean hasLanguage(String language) {
        String normalized = normalize(language);
        return isBotLanguage(normalized) && bundles.containsKey(normalized);
    }

    public String t(String language, String key) {
        String normalized = normalize(language);
        String value = lookup(normalized, key);
        if (value != null) {
            return value;
        }
        value = lookup("zh-TW", key);
        if (value != null) {
            return value;
        }
        value = lookup(defaultLanguage, key);
        if (value != null) {
            return value;
        }
        value = lookup("en", key);
        return value == null ? key : value;
    }

    public String t(String language, String key, Map<String, String> placeholders) {
        String text = t(language, key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return text;
    }

    public Map<String, String> getAvailableLanguages() {
        return bundles.keySet().stream()
                .sorted()
                .filter(I18nService::isBotLanguage)
                .collect(Collectors.toMap(
                        lang -> lang,
                        this::resolveLanguageDisplayName,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
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

    private static String normalize(String language) {
        if (language == null || language.isBlank()) {
            return "en";
        }
        return language.trim();
    }

    private static boolean isBotLanguage(String language) {
        return !language.toLowerCase().startsWith("web-");
    }
}
