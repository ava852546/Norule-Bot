package com.norule.musicbot.web;

import com.norule.musicbot.config.BotConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

final class WebLanguageBundleService {
    private final Supplier<BotConfig> configSupplier;

    WebLanguageBundleService(Supplier<BotConfig> configSupplier) {
        this.configSupplier = configSupplier;
    }

    Map<String, Map<String, String>> loadWebBundles() {
        Map<String, Map<String, String>> bundles = new LinkedHashMap<>();
        String langDir = configSupplier.get().getLanguageDir();
        Path base = Path.of(langDir == null || langDir.isBlank() ? "lang" : langDir);
        Path webBase = base.resolve("web");
        if (Files.exists(webBase)) {
            try (var stream = Files.list(webBase)) {
                stream.filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(name -> name.startsWith("web-"))
                        .filter(name -> name.endsWith(".yml") || name.endsWith(".yaml"))
                        .sorted()
                        .forEach(fileName -> {
                            String code = parseWebLanguageCode(fileName);
                            if (code.isBlank()) {
                                return;
                            }
                            Map<String, String> bundle = readFlatYaml(webBase.resolve(fileName));
                            if (!bundle.isEmpty()) {
                                bundles.put(code, bundle);
                            }
                        });
            } catch (IOException ignored) {
            }
        }

        if (!bundles.containsKey("zh-TW")) {
            Map<String, String> zh = readFlatYamlResource("defaults/lang/web/web-zh-TW.yml");
            if (!zh.isEmpty()) {
                bundles.put("zh-TW", zh);
            }
        }
        if (!bundles.containsKey("zh-CN")) {
            Map<String, String> zhCn = readFlatYamlResource("defaults/lang/web/web-zh-CN.yml");
            if (!zhCn.isEmpty()) {
                bundles.put("zh-CN", zhCn);
            }
        }
        if (!bundles.containsKey("en")) {
            Map<String, String> en = readFlatYamlResource("defaults/lang/web/web-en.yml");
            if (!en.isEmpty()) {
                bundles.put("en", en);
            }
        }
        if (bundles.isEmpty()) {
            bundles.put("zh-TW", Map.of());
            bundles.put("en", Map.of());
        }
        return bundles;
    }

    private String parseWebLanguageCode(String fileName) {
        if (fileName == null || !fileName.startsWith("web-")) {
            return "";
        }
        String name = fileName;
        if (name.endsWith(".yml")) {
            name = name.substring(0, name.length() - 4);
        } else if (name.endsWith(".yaml")) {
            name = name.substring(0, name.length() - 5);
        }
        if (name.length() <= 4) {
            return "";
        }
        return name.substring(4);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readFlatYaml(Path file) {
        if (file == null || !Files.exists(file)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            if (!(root instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            flattenMap("", (Map<String, Object>) map, out);
            return out;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readFlatYamlResource(String resourcePath) {
        try (InputStream in = WebControlServer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Map.of();
            }
            Object root = new Yaml().load(in);
            if (!(root instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            flattenMap("", (Map<String, Object>) map, out);
            return out;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenMap(String prefix, Map<String, Object> source, Map<String, String> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                flattenMap(key, (Map<String, Object>) child, target);
            } else {
                target.put(key, value == null ? "" : String.valueOf(value));
            }
        }
    }
}
