package com.norule.musicbot.config.loader;

import com.norule.musicbot.config.lang.LanguageManager;
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
import java.util.List;
import java.util.Map;

public final class ConfigInitializer {
    private final LanguageManager languageManager;

    public ConfigInitializer(LanguageManager languageManager) {
        this.languageManager = languageManager;
    }

    public void initialize(Path configPath) {
        Map<String, Object> defaultConfig = readDefaultConfigMap();
        if (defaultConfig.isEmpty()) {
            return;
        }

        try {
            Path parent = configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (!Files.exists(configPath)) {
                writeResourceIfMissing("defaults/config.yml", configPath);
                if (!Files.exists(configPath)) {
                    writeYaml(configPath, defaultConfig);
                }
            }

            Map<String, Object> currentConfig = readYamlMap(configPath);
            if (currentConfig == null) {
                return;
            }
            if (currentConfig.isEmpty()) {
                currentConfig = new LinkedHashMap<>();
            }

            String languageDir = resolveConfiguredPath(currentConfig, defaultConfig, "languageDir", "lang");
            Path baseDir = parent == null ? Path.of(".") : parent;
            Path languagePath = resolvePath(baseDir, languageDir);
            languageManager.ensureLanguageResources(languagePath);
            ensureWebCertificateDirectory(baseDir, currentConfig, defaultConfig);

            Map<String, Object> merged = deepMerge(defaultConfig, currentConfig);
            pruneGuildScopedRootSettings(merged);
            if (!merged.equals(currentConfig)) {
                backupConfig(configPath);
                writeYaml(configPath, merged);
            }
        } catch (Exception ignored) {
        }
    }

    private void ensureWebCertificateDirectory(Path baseDir, Map<String, Object> currentConfig, Map<String, Object> defaultConfig) {
        try {
            Map<String, Object> currentWeb = asMap(currentConfig.get("web"));
            Map<String, Object> defaultWeb = asMap(defaultConfig.get("web"));
            Map<String, Object> currentSsl = asMap(currentWeb.get("ssl"));
            Map<String, Object> defaultSsl = asMap(defaultWeb.get("ssl"));
            String certDirRaw = getString(currentSsl, "certDir", getString(defaultSsl, "certDir", "certs"));
            if (certDirRaw.isBlank()) {
                certDirRaw = "certs";
            }
            Path certDir = resolvePath(baseDir, certDirRaw);
            Files.createDirectories(certDir);
        } catch (Exception ignored) {
        }
    }

    private static Path resolvePath(Path baseDir, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return baseDir.resolve("certs").normalize();
        }
        Path path = Path.of(rawPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return baseDir.resolve(path).normalize();
    }

    private String resolveConfiguredPath(Map<String, Object> root,
                                         Map<String, Object> defaultRoot,
                                         String key,
                                         String fallback) {
        Map<String, Object> data = asMap(root.get("data"));
        Map<String, Object> defaultData = asMap(defaultRoot.get("data"));
        String value = getString(data, key, "");
        if (!value.isBlank()) {
            return value;
        }
        value = getString(root, key, "");
        if (!value.isBlank()) {
            return value;
        }
        value = getString(defaultData, key, "");
        if (!value.isBlank()) {
            return value;
        }
        value = getString(defaultRoot, key, "");
        return value.isBlank() ? fallback : value;
    }

    private void pruneGuildScopedRootSettings(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return;
        }
        for (String key : List.of("notifications", "welcome", "messageLogs", "privateRoom", "ticket")) {
            config.remove(key);
        }
        Map<String, Object> music = asMap(config.get("music"));
        if (!music.isEmpty()) {
            Map<String, Object> youtube = asMap(music.get("youtube"));
            Map<String, Object> spotify = asMap(music.get("spotify"));
            Map<String, Object> globalMusic = new LinkedHashMap<>();
            if (!youtube.isEmpty()) {
                globalMusic.put("youtube", youtube);
            }
            if (!spotify.isEmpty()) {
                globalMusic.put("spotify", spotify);
            }
            config.put("music", globalMusic);
        }
    }

    private Map<String, Object> readDefaultConfigMap() {
        try (InputStream in = ConfigInitializer.class.getClassLoader().getResourceAsStream("defaults/config.yml")) {
            if (in == null) {
                return Map.of();
            }
            Object obj = new Yaml().load(in);
            return asMap(obj);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> readYamlMap(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object obj = new Yaml().load(reader);
            return asMap(obj);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeResourceIfMissing(String resourcePath, Path target) {
        if (Files.exists(target)) {
            return;
        }
        try (InputStream in = ConfigInitializer.class.getClassLoader().getResourceAsStream(resourcePath)) {
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

    private void backupConfig(Path configPath) {
        if (!Files.exists(configPath)) {
            return;
        }
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String fileName = configPath.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            String ext = dot > 0 ? fileName.substring(dot) : "";
            Path backup = configPath.resolveSibling(base + ".backup-" + timestamp + ext);
            Files.copy(configPath, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object object) {
        if (object instanceof Map) {
            return (Map<String, Object>) object;
        }
        return Map.of();
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        return String.valueOf(value).trim();
    }
}
