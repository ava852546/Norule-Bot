package com.norule.musicbot.config.loader;

import com.norule.musicbot.config.lang.LanguageManager;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

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
import java.util.regex.Pattern;

public final class ConfigInitializer {
    private static final Pattern QUOTED_SIMPLE_KEY = Pattern.compile("(?m)^(\\s*)'([A-Za-z0-9_-]+)':");
    private static final Pattern TAGGED_BOOL = Pattern.compile("!!bool\\s*'(?i:(true|false))'");
    private static final Pattern TAGGED_INT = Pattern.compile("!!int\\s*'(-?\\d+)'");
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
                    writeYaml(configPath, dumpYaml(defaultConfig));
                }
            }

            Map<String, Object> currentConfig = readYamlMap(configPath);
            if (currentConfig == null) {
                return;
            }
            if (currentConfig.isEmpty()) {
                currentConfig = new LinkedHashMap<>();
            }
            boolean migrated = migrateLegacyConfig(currentConfig);

            String languageDir = resolveConfiguredPath(currentConfig, defaultConfig, "languageDir", "lang");
            Path baseDir = parent == null ? Path.of(".") : parent;
            Path languagePath = resolvePath(baseDir, languageDir);
            languageManager.ensureLanguageResources(languagePath);
            ensureWebCertificateDirectory(baseDir, currentConfig, defaultConfig);

            Map<String, Object> merged = deepMerge(defaultConfig, currentConfig);
            pruneGuildScopedRootSettings(merged);
            String rendered = dumpYaml(merged);
            if (migrated || !merged.equals(currentConfig)) {
                backupConfig(configPath);
                writeYaml(configPath, rendered);
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

    private void writeYaml(Path file, String yamlText) throws IOException {
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(yamlText);
        }
    }

    private String dumpYaml(Map<String, Object> root) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        Representer representer = new SingleQuotedStringRepresenter(options);
        Yaml yaml = new Yaml(representer, options);
        String rendered = yaml.dump(root);
        rendered = QUOTED_SIMPLE_KEY.matcher(rendered).replaceAll("$1$2:");
        rendered = TAGGED_BOOL.matcher(rendered).replaceAll("$1");
        rendered = TAGGED_INT.matcher(rendered).replaceAll("$1");
        return rendered;
    }

    private static final class SingleQuotedStringRepresenter extends Representer {
        private SingleQuotedStringRepresenter(DumperOptions options) {
            super(options);
            addClassTag(String.class, Tag.STR);
            this.representers.put(String.class, new RepresentSingleQuotedString());
        }

        private final class RepresentSingleQuotedString implements Represent {
            @Override
            public Node representData(Object data) {
                String text = data == null ? "" : String.valueOf(data);
                return SingleQuotedStringRepresenter.this.representScalar(
                        Tag.STR,
                        text,
                        DumperOptions.ScalarStyle.SINGLE_QUOTED
                );
            }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Object object) {
        if (object instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private boolean migrateLegacyConfig(Map<String, Object> config) {
        boolean changed = false;

        Map<String, Object> web = mutableMap(config.get("web"));
        if (web != null && !web.isEmpty()) {
            changed |= migrateBindAndPublicUrl(web, "host", "port", "baseUrl", null);
        }

        Map<String, Object> shortUrl = mutableMap(config.get("shortUrl"));
        if (shortUrl != null && !shortUrl.isEmpty()) {
            changed |= migrateBindAndPublicUrl(shortUrl, "host", "port", "baseUrl", "domain");

            String storage = getString(shortUrl, "storage", "");
            if ("db".equalsIgnoreCase(storage)) {
                shortUrl.put("storage", "sqlite");
                changed = true;
            }

            Map<String, Object> legacyDb = mutableMap(shortUrl.get("db"));
            Map<String, Object> sqlite = mutableMap(shortUrl.get("sqlite"));
            String sqlitePath = sqlite == null ? "" : getString(sqlite, "path", "");
            String legacyDbPath = legacyDb == null ? "" : getString(legacyDb, "path", "");
            if (sqlitePath.isBlank() && !legacyDbPath.isBlank()) {
                if (sqlite == null) {
                    sqlite = new LinkedHashMap<>();
                    shortUrl.put("sqlite", sqlite);
                }
                sqlite.put("path", legacyDbPath);
                changed = true;
            }

            if (shortUrl.containsKey("db")) {
                shortUrl.remove("db");
                changed = true;
            }

            Map<String, Object> sqliteAfter = mutableMap(shortUrl.get("sqlite"));
            if (sqliteAfter != null) {
                String sqlitePathAfter = getString(sqliteAfter, "path", "");
                if ("db/short-url.db".equals(sqlitePathAfter)) {
                    sqliteAfter.put("path", "data/short-url.db");
                    changed = true;
                }
            }
        }

        Map<String, Object> stats = mutableMap(config.get("stats"));
        if (stats != null && !stats.isEmpty()) {
            Map<String, Object> sqlite = mutableMap(stats.get("sqlite"));
            if (sqlite != null) {
                String sqlitePath = getString(sqlite, "path", "");
                if ("stats/message-stats.db".equals(sqlitePath)) {
                    sqlite.put("path", "data/message-stats.db");
                    changed = true;
                }
            }
        }

        Map<String, Object> database = mutableMap(config.get("database"));
        if (database == null) {
            database = new LinkedHashMap<>();
            config.put("database", database);
            changed = true;
        }

        String resolvedStorage = firstNonBlank(
                getString(database, "storage", ""),
                stats == null ? "" : getString(stats, "storage", ""),
                shortUrl == null ? "" : getString(shortUrl, "storage", ""),
                "sqlite"
        );
        if ("db".equalsIgnoreCase(resolvedStorage)) {
            resolvedStorage = "sqlite";
        }
        if (!resolvedStorage.equalsIgnoreCase(getString(database, "storage", ""))) {
            database.put("storage", resolvedStorage.toLowerCase());
            changed = true;
        }

        Map<String, Object> existingDbMysql = mutableMap(database.get("mysql"));
        Map<String, Object> sourceMysql = firstNonEmptyMap(
                existingDbMysql,
                stats == null ? null : mutableMap(stats.get("mysql")),
                shortUrl == null ? null : mutableMap(shortUrl.get("mysql"))
        );
        if (sourceMysql != null) {
            Map<String, Object> normalizedMysql = new LinkedHashMap<>(sourceMysql);
            String jdbcUrl = getString(normalizedMysql, "jdbcUrl", "");
            if (jdbcUrl.isBlank()) {
                jdbcUrl = "jdbc:mysql://localhost:3306/data?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            } else if (jdbcUrl.contains("/discord_bot")) {
                jdbcUrl = jdbcUrl.replaceFirst("/discord_bot", "/data");
            }
            normalizedMysql.put("jdbcUrl", jdbcUrl);
            if (!normalizedMysql.equals(existingDbMysql)) {
                database.put("mysql", normalizedMysql);
                changed = true;
            }
        }

        Map<String, Object> existingDbSqlite = mutableMap(database.get("sqlite"));
        Map<String, Object> sourceSqlite = firstNonEmptyMap(
                existingDbSqlite,
                stats == null ? null : mutableMap(stats.get("sqlite")),
                shortUrl == null ? null : mutableMap(shortUrl.get("sqlite"))
        );
        Map<String, Object> normalizedSqlite = new LinkedHashMap<>();
        if (sourceSqlite != null) {
            normalizedSqlite.putAll(sourceSqlite);
        }
        String sqlitePath = normalizeSqlitePath(getString(normalizedSqlite, "path", ""));
        normalizedSqlite.put("path", sqlitePath);
        if (!normalizedSqlite.equals(existingDbSqlite)) {
            database.put("sqlite", normalizedSqlite);
            changed = true;
        }

        if (stats != null) {
            changed |= removeKey(stats, "storage");
            changed |= removeKey(stats, "mysql");
            changed |= removeKey(stats, "sqlite");
        }
        if (shortUrl != null) {
            changed |= removeKey(shortUrl, "storage");
            changed |= removeKey(shortUrl, "mysql");
            changed |= removeKey(shortUrl, "sqlite");
        }

        return changed;
    }

    private boolean migrateBindAndPublicUrl(Map<String, Object> section,
                                            String legacyHostKey,
                                            String legacyPortKey,
                                            String legacyBaseUrlKey,
                                            String legacyDomainKey) {
        boolean changed = false;
        if (section == null || section.isEmpty()) {
            return false;
        }

        Map<String, Object> bind = mutableMap(section.get("bind"));
        if (bind == null) {
            bind = new LinkedHashMap<>();
            section.put("bind", bind);
            changed = true;
        }
        if (!bind.containsKey("host") && section.containsKey(legacyHostKey)) {
            bind.put("host", section.get(legacyHostKey));
            changed = true;
        }
        if (!bind.containsKey("port") && section.containsKey(legacyPortKey)) {
            bind.put("port", section.get(legacyPortKey));
            changed = true;
        }

        Map<String, Object> publicMap = mutableMap(section.get("public"));
        if (publicMap == null) {
            publicMap = new LinkedHashMap<>();
            section.put("public", publicMap);
            changed = true;
        }

        String existingPublicBaseUrl = getString(publicMap, "baseUrl", "");
        if (existingPublicBaseUrl.isBlank()) {
            String fallback = getString(section, legacyBaseUrlKey, "");
            if (fallback.isBlank() && legacyDomainKey != null && !legacyDomainKey.isBlank()) {
                String domain = getString(section, legacyDomainKey, "");
                if (!domain.isBlank()) {
                    fallback = "https://" + domain.trim().toLowerCase();
                }
            }
            if (!fallback.isBlank()) {
                publicMap.put("baseUrl", fallback);
                changed = true;
            }
        }

        changed |= removeKey(section, legacyHostKey);
        changed |= removeKey(section, legacyPortKey);
        changed |= removeKey(section, legacyBaseUrlKey);
        if (legacyDomainKey != null && !legacyDomainKey.isBlank()) {
            changed |= removeKey(section, legacyDomainKey);
        }
        return changed;
    }

    private static boolean removeKey(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key)) {
            return false;
        }
        map.remove(key);
        return true;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    @SafeVarargs
    private static Map<String, Object> firstNonEmptyMap(Map<String, Object>... maps) {
        if (maps == null) {
            return null;
        }
        for (Map<String, Object> map : maps) {
            if (map != null && !map.isEmpty()) {
                return map;
            }
        }
        return null;
    }

    private static String normalizeSqlitePath(String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        if (path.isBlank()) {
            return "data/data.db";
        }
        if ("db/short-url.db".equals(path)
                || "data/short-url.db".equals(path)
                || "stats/message-stats.db".equals(path)
                || "data/message-stats.db".equals(path)) {
            return "data/data.db";
        }
        return path;
    }
}
