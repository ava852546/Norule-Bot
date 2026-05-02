package com.norule.musicbot.config.lang;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LanguageManager {
    public void ensureLanguageResources(Path languageDir) {
        if (languageDir == null) {
            return;
        }
        try {
            Files.createDirectories(languageDir);
            Files.createDirectories(languageDir.resolve("web"));
            writeResourceIfMissing("defaults/lang/zh-TW.yml", languageDir.resolve("zh-TW.yml"));
            writeResourceIfMissing("defaults/lang/zh-CN.yml", languageDir.resolve("zh-CN.yml"));
            writeResourceIfMissing("defaults/lang/en.yml", languageDir.resolve("en.yml"));
            migrateLegacyWebLanguageFile(languageDir, "web-zh-TW.yml");
            migrateLegacyWebLanguageFile(languageDir, "web-zh-CN.yml");
            migrateLegacyWebLanguageFile(languageDir, "web-en.yml");
            writeResourceIfMissing("defaults/lang/web/web-zh-TW.yml", languageDir.resolve("web").resolve("web-zh-TW.yml"));
            writeResourceIfMissing("defaults/lang/web/web-zh-CN.yml", languageDir.resolve("web").resolve("web-zh-CN.yml"));
            writeResourceIfMissing("defaults/lang/web/web-en.yml", languageDir.resolve("web").resolve("web-en.yml"));
            mergeLanguageFile(languageDir.resolve("zh-TW.yml"), "defaults/lang/zh-TW.yml");
            mergeLanguageFile(languageDir.resolve("zh-CN.yml"), "defaults/lang/zh-CN.yml");
            mergeLanguageFile(languageDir.resolve("en.yml"), "defaults/lang/en.yml");
            mergeLanguageFile(languageDir.resolve("web").resolve("web-zh-TW.yml"), "defaults/lang/web/web-zh-TW.yml");
            mergeLanguageFile(languageDir.resolve("web").resolve("web-zh-CN.yml"), "defaults/lang/web/web-zh-CN.yml");
            mergeLanguageFile(languageDir.resolve("web").resolve("web-en.yml"), "defaults/lang/web/web-en.yml");
        } catch (Exception ignored) {
        }
    }

    private void mergeLanguageFile(Path targetFile, String resourcePath) {
        Map<String, Object> defaults = readYamlResourceMap(resourcePath);
        if (defaults.isEmpty()) {
            return;
        }
        YamlReadResult existingResult = readYamlMapWithStatus(targetFile);
        if (existingResult.parseError) {
            backupCorruptedLanguageFile(targetFile);
            writeQuietly(targetFile, defaults);
            return;
        }
        Map<String, Object> existing = existingResult.map;
        if (existing == null || existing.isEmpty()) {
            writeQuietly(targetFile, defaults);
            return;
        }
        Map<String, Object> merged = deepMerge(defaults, existing);
        if (!merged.equals(existing)) {
            writeQuietly(targetFile, merged);
        }
    }

    @SuppressWarnings("unchecked")
    private YamlReadResult readYamlMapWithStatus(Path file) {
        if (file == null || !Files.exists(file)) {
            return new YamlReadResult(Map.of(), false);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object obj = new Yaml().load(reader);
            if (obj == null) {
                return new YamlReadResult(Map.of(), false);
            }
            if (obj instanceof Map<?, ?> map) {
                return new YamlReadResult((Map<String, Object>) map, false);
            }
            return new YamlReadResult(Map.of(), true);
        } catch (Exception ignored) {
            return new YamlReadResult(Map.of(), true);
        }
    }

    private Map<String, Object> readYamlResourceMap(String resourcePath) {
        try (InputStream in = LanguageManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Map.of();
            }
            Object obj = new Yaml().load(in);
            return asMap(obj);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private void writeQuietly(Path file, Map<String, Object> root) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writeYaml(file, root);
        } catch (Exception ignored) {
        }
    }

    private void writeResourceIfMissing(String resourcePath, Path target) {
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = LanguageManager.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return;
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMerge(Map<String, Object> defaults, Map<String, Object> existing) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            Object defaultValue = entry.getValue();
            if (!existing.containsKey(key)) {
                result.put(key, defaultValue);
                continue;
            }
            Object existingValue = existing.get(key);
            if (defaultValue instanceof Map<?, ?> defaultMap && existingValue instanceof Map<?, ?> existingMap) {
                result.put(key, deepMerge((Map<String, Object>) defaultMap, (Map<String, Object>) existingMap));
            } else {
                result.put(key, existingValue);
            }
        }
        for (Map.Entry<String, Object> entry : existing.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private void migrateLegacyWebLanguageFile(Path languageDir, String fileName) {
        Path legacy = languageDir.resolve(fileName);
        Path target = languageDir.resolve("web").resolve(fileName);
        try {
            if (!Files.exists(legacy) || Files.exists(target)) {
                return;
            }
            Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private void backupCorruptedLanguageFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            String fileName = path.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            String ext = dot > 0 ? fileName.substring(dot) : "";
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path backup = path.resolveSibling(base + ".corrupt-" + timestamp + ext + ".bak");
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[NoRule] Corrupted language file backed up: " + backup.getFileName());
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object object) {
        if (object instanceof Map) {
            return (Map<String, Object>) object;
        }
        return Map.of();
    }

    private void writeYaml(Path file, Map<String, Object> root) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        Yaml yaml = new Yaml(options);
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            yaml.dump(root, writer);
        }
    }

    private static class YamlReadResult {
        private final Map<String, Object> map;
        private final boolean parseError;

        private YamlReadResult(Map<String, Object> map, boolean parseError) {
            this.map = map == null ? Map.of() : map;
            this.parseError = parseError;
        }
    }
}
