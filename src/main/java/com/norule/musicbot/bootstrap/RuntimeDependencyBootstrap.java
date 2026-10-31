package com.norule.musicbot.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RuntimeDependencyBootstrap {
    static final String DEPENDENCY_MANIFEST_RESOURCE = "/bootstrap/runtime-dependencies.txt";
    static final String CLEANUP_OBSOLETE_PROPERTY = "norule.bootstrap.cleanup-obsolete";
    static final String VERIFY_CHECKSUMS_PROPERTY = "norule.bootstrap.verify-checksums";
    static final String FORCE_REDOWNLOAD_PROPERTY = "norule.bootstrap.force-redownload";
    static final String PROGRESS_ENABLED_PROPERTY = "norule.bootstrap.progress-enabled";
    static final String PROGRESS_INTERVAL_PROPERTY = "norule.bootstrap.progress-interval-ms";
    static final String CONNECT_TIMEOUT_PROPERTY = "norule.bootstrap.connect-timeout-ms";
    static final String READ_TIMEOUT_PROPERTY = "norule.bootstrap.read-timeout-ms";
    static final String STALL_TIMEOUT_PROPERTY = "norule.bootstrap.stall-timeout-ms";
    static final String MAX_RETRIES_PROPERTY = "norule.bootstrap.max-retries";
    static final String RUNTIME_LIB_DIRECTORY = "runtime-libs";
    private static final String APPLICATION_MAIN = "com.norule.musicbot.bootstrap.BotMain";
    // Intentionally retained until JVM termination: BotMain returns while JDA is still running.
    private static URLClassLoader runtimeLoader;
    private static CacheLease runtimeLease;
    private static final String SHA_256 = "SHA-256";
    private static final System.Logger LOGGER = System.getLogger(RuntimeDependencyBootstrap.class.getName());

    private static final Set<String> CONFLICTING_SLF4J_PROVIDERS = Set.of(
            "slf4j-simple",
            "log4j-slf4j2-impl",
            "slf4j-reload4j"
    );

    private RuntimeDependencyBootstrap() {
    }

    static void launch(String[] args) throws Throwable {
        Path launcherPath = findLauncherPath();
        if (Files.isDirectory(launcherPath)) {
            // IDE / exec:java already supplies the Maven runtime classpath.
            invokeMain(Main.class.getClassLoader(), APPLICATION_MAIN, args);
            return;
        }
        List<DependencyArtifact> artifacts = loadRuntimeDependencyManifest();
        if (artifacts.isEmpty()) {
            throw new IllegalStateException("Packaged runtime dependency manifest is empty");
        }
        Path workingDir = Path.of(".").toAbsolutePath().normalize();
        Path libDir = workingDir.resolve(RUNTIME_LIB_DIRECTORY);
        try {
            String configuredPath = System.getenv("BOT_CONFIG_PATH");
            Path configPath = configuredPath == null || configuredPath.isBlank()
                    ? workingDir.resolve("config.yml") : Path.of(configuredPath);
            BootstrapSettings settings = BootstrapSettings.fromConfig(configPath);
            runtimeLease = CacheLease.acquire(libDir);
            synchronizeRuntimeDependencies(libDir, artifacts, settings,
                    new RuntimeDependencyDownloader(settings,
                            message -> LOGGER.log(System.Logger.Level.INFO, message)));
            runtimeLoader = createRuntimeLoader(launcherPath, libDir, artifacts);
        } catch (IOException e) {
            if (runtimeLease != null) {
                runtimeLease.close();
                runtimeLease = null;
            }
            throw new UncheckedIOException("Failed to synchronize runtime dependencies in ./" + RUNTIME_LIB_DIRECTORY, e);
        }
        LOGGER.log(System.Logger.Level.INFO, "[NoRule] Starting bot in the current JVM with verified runtime dependencies.");
        // Do not close the loader or lease here, including on an application failure:
        // shutdown hooks and existing workers may still need classes/resources.
        invokeMain(runtimeLoader, APPLICATION_MAIN, args);
    }

    static URLClassLoader createRuntimeLoader(Path launcher, Path libDir, List<DependencyArtifact> artifacts)
            throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(launcher.toUri().toURL());
        for (DependencyArtifact artifact : removeConflictingProviders(artifacts)) {
            urls.add(artifactPath(libDir, artifact).toUri().toURL());
        }
        return new URLClassLoader("NoRuleRuntime", urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    static void invokeMain(ClassLoader loader, String className, String[] args) throws Throwable {
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class.forName(className, true, loader).getMethod("main", String[].class)
                    .invoke(null, (Object) args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    static List<DependencyArtifact> parseManifestLines(List<String> lines) {
        Map<String, DependencyArtifact> unique = new LinkedHashMap<>();
        for (String line : lines) {
            if (line == null || line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            DependencyArtifact artifact = parseManifestLine(line.trim());
            String fileName = buildJarFileName(artifact);
            if (unique.putIfAbsent(normalizeFileName(fileName), artifact) != null) {
                throw new IllegalArgumentException("Duplicate runtime dependency target file: " + fileName);
            }
        }
        return new ArrayList<>(unique.values());
    }

    static String buildJarFileName(DependencyArtifact artifact) {
        if (artifact.classifier() == null || artifact.classifier().isBlank()) {
            return artifact.artifactId() + "-" + artifact.version() + "." + artifact.extension();
        }
        return artifact.artifactId() + "-" + artifact.version() + "-" + artifact.classifier()
                + "." + artifact.extension();
    }

    private static DependencyArtifact parseManifestLine(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != 7) {
            throw new IllegalArgumentException("Invalid runtime dependency manifest entry; expected repository and "
                    + "SHA-256 fields: " + line);
        }
        String groupId = requireManifestValue(parts[0], "groupId", line);
        String artifactId = requireManifestValue(parts[1], "artifactId", line);
        String version = requireManifestValue(parts[2], "version", line);
        String classifier = parts[3].trim();
        String extension = requireManifestValue(parts[4], "extension", line).toLowerCase(Locale.ROOT);
        if (!"jar".equals(extension)) {
            throw new IllegalArgumentException("Unsupported runtime dependency extension '" + extension + "': "
                    + line);
        }
        RuntimeRepository repository = RuntimeRepository.fromId(requireManifestValue(parts[5], "repository", line));
        String sha256 = requireManifestValue(parts[6], "sha256", line).toLowerCase(Locale.ROOT);
        if (!isSha256(sha256)) {
            throw new IllegalArgumentException("Invalid SHA-256 in runtime dependency manifest: " + line);
        }
        return new DependencyArtifact(groupId, artifactId, version, classifier, extension, repository, sha256);
    }

    private static String requireManifestValue(String value, String field, String line) {
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Missing " + field + " in runtime dependency manifest: " + line);
        }
        return trimmed;
    }

    private static List<DependencyArtifact> loadRuntimeDependencyManifest() {
        InputStream input = RuntimeDependencyBootstrap.class.getResourceAsStream(DEPENDENCY_MANIFEST_RESOURCE);
        if (input == null) {
            throw new IllegalStateException("Packaged runtime dependency manifest is missing");
        }
        try (input) {
            List<String> lines = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            return parseManifestLines(lines);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read runtime dependency manifest: "
                    + DEPENDENCY_MANIFEST_RESOURCE, e);
        }
    }

    static void synchronizeRuntimeDependencies(Path libDir, List<DependencyArtifact> artifacts,
            BootstrapSettings settings, ArtifactDownloader downloader) throws IOException {
        synchronizeRuntimeDependencies(libDir, artifacts, settings,
                (artifact, destination, index, total) -> downloader.download(artifact, destination));
    }

    static void synchronizeRuntimeDependencies(Path libDir, List<DependencyArtifact> artifacts,
            BootstrapSettings settings, ProgressArtifactDownloader downloader) throws IOException {
        List<DependencyArtifact> activeArtifacts = removeConflictingProviders(artifacts);
        validateLogbackVersions(activeArtifacts);
        validateChecksums(activeArtifacts, settings);
        Files.createDirectories(libDir);

        SyncStats stats = new SyncStats(activeArtifacts.size(), System.nanoTime());
        LOGGER.log(System.Logger.Level.INFO,
                "[NoRule] Preparing runtime dependencies: total=" + activeArtifacts.size());
        try {
            downloadRequiredArtifacts(libDir, activeArtifacts, settings, downloader, stats);
            // Only manifest URLs are loaded. Preserve old/extra JARs for rollback.
            verifySynchronizedArtifacts(libDir, activeArtifacts, settings);
        } catch (IOException e) {
            logSummary(stats);
            throw e;
        }
        logSummary(stats);
    }

    private static List<DependencyArtifact> removeConflictingProviders(List<DependencyArtifact> artifacts) {
        List<DependencyArtifact> active = new ArrayList<>();
        for (DependencyArtifact artifact : artifacts) {
            if (isConflictingProviderArtifact(artifact.artifactId())) {
                LOGGER.log(System.Logger.Level.WARNING, "[NoRule] Ignoring conflicting SLF4J provider from runtime index: "
                        + buildJarFileName(artifact));
            } else {
                active.add(artifact);
            }
        }
        return active;
    }

    private static void validateLogbackVersions(List<DependencyArtifact> artifacts) throws IOException {
        Set<String> classicVersions = versionsOf(artifacts, "logback-classic");
        Set<String> coreVersions = versionsOf(artifacts, "logback-core");
        if (classicVersions.isEmpty() && coreVersions.isEmpty()) {
            return;
        }
        if (classicVersions.size() != 1 || coreVersions.size() != 1
                || !classicVersions.iterator().next().equals(coreVersions.iterator().next())) {
            throw new IOException("Runtime dependency index must define exactly one matching version of "
                    + "logback-classic and logback-core (classic=" + classicVersions + ", core=" + coreVersions + ")");
        }
    }

    private static Set<String> versionsOf(List<DependencyArtifact> artifacts, String artifactId) {
        Set<String> versions = new HashSet<>();
        for (DependencyArtifact artifact : artifacts) {
            if (artifactId.equals(artifact.artifactId())) {
                versions.add(artifact.version());
            }
        }
        return versions;
    }

    private static void validateChecksums(List<DependencyArtifact> artifacts, BootstrapSettings settings)
            throws IOException {
        if (!settings.verifyChecksums()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (DependencyArtifact artifact : artifacts) {
            String fileName = buildJarFileName(artifact);
            if (!isSha256(artifact.sha256())) {
                missing.add(fileName);
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("Missing or invalid SHA-256 checksums for runtime dependencies: " + missing);
        }
    }

    private static void downloadRequiredArtifacts(Path libDir, List<DependencyArtifact> artifacts,
            BootstrapSettings settings, ProgressArtifactDownloader downloader, SyncStats stats) throws IOException {
        for (int artifactIndex = 0; artifactIndex < artifacts.size(); artifactIndex++) {
            DependencyArtifact artifact = artifacts.get(artifactIndex);
            int displayIndex = artifactIndex + 1;
            String fileName = buildJarFileName(artifact);
            Path target = artifactPath(libDir, artifact);
            if (!requiresDownload(target, artifact.sha256(), settings)) {
                Files.deleteIfExists(partFile(target));
                stats.reused++;
                continue;
            }
            logReplacementIfNeeded(target, artifact, settings);
            long startedAt = System.nanoTime();
            try {
                long size = downloadAndReplace(artifact, target, artifact.sha256(), settings.verifyChecksums(),
                        downloader, displayIndex, artifacts.size());
                stats.downloaded++;
                stats.totalBytes += size;
                LOGGER.log(System.Logger.Level.INFO, String.format(Locale.ROOT,
                        "[NoRule] Downloaded %d/%d: %s repository=%s size=%.1f MB duration=%.1fs",
                        displayIndex, artifacts.size(), fileName, artifact.repository().id(), bytesToMegabytes(size),
                        elapsedSeconds(startedAt, System.nanoTime())));
            } catch (IOException e) {
                stats.failed++;
                throw e;
            }
        }
    }

    private static void logReplacementIfNeeded(Path target, DependencyArtifact artifact, BootstrapSettings settings)
            throws IOException {
        if (!settings.verifyChecksums() || !Files.isRegularFile(target)
                || artifact.sha256().equals(sha256(target))) {
            return;
        }
        LOGGER.log(System.Logger.Level.INFO,
                "[NoRule] Replacing runtime dependency with " + artifact.repository().id() + " version: "
                        + target.getFileName());
    }

    private static boolean requiresDownload(Path target, String expectedChecksum, BootstrapSettings settings)
            throws IOException {
        if (settings.forceRedownload() || !Files.isRegularFile(target)) {
            return true;
        }
        return settings.verifyChecksums() && !expectedChecksum.equals(sha256(target));
    }

    private static long downloadAndReplace(DependencyArtifact artifact, Path target, String expectedChecksum,
            boolean verifyChecksum, ProgressArtifactDownloader downloader, int index, int total) throws IOException {
        Path tempFile = partFile(target);
        Files.deleteIfExists(tempFile);
        try {
            downloader.download(artifact, tempFile, index, total);
            if (!Files.isRegularFile(tempFile)) {
                throw new IOException("Dependency downloader did not create a file for " + artifact);
            }
            if (verifyChecksum) {
                String actualChecksum = sha256(tempFile);
                if (!expectedChecksum.equals(actualChecksum)) {
                    throw new IOException("SHA-256 mismatch for downloaded dependency " + buildJarFileName(artifact)
                            + " (expected=" + expectedChecksum + ", actual=" + actualChecksum + ")");
                }
            }
            moveAtomically(tempFile, target);
            return Files.size(target);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("Atomic replacement is not supported for runtime dependency: " + target, e);
        }
    }

    private static Path partFile(Path target) {
        return target.resolveSibling(target.getFileName().toString() + ".part");
    }

    private static void verifySynchronizedArtifacts(Path libDir, List<DependencyArtifact> artifacts,
            BootstrapSettings settings) throws IOException {
        for (DependencyArtifact artifact : artifacts) {
            String fileName = buildJarFileName(artifact);
            Path target = artifactPath(libDir, artifact);
            if (!Files.isRegularFile(target)) {
                throw new IOException("Runtime dependency is missing after synchronization: " + fileName);
            }
            if (settings.verifyChecksums() && !artifact.sha256().equals(sha256(target))) {
                throw new IOException("Runtime dependency checksum failed after synchronization: " + fileName);
            }
        }
    }

    private static Path findLauncherPath() {
        try {
            ProtectionDomain domain = Main.class.getProtectionDomain();
            if (domain == null) {
                throw new IllegalStateException("Launcher protection domain is unavailable");
            }
            CodeSource source = domain.getCodeSource();
            if (source == null) {
                throw new IllegalStateException("Launcher code source is unavailable");
            }
            URI uri = source.getLocation().toURI();
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate launcher", e);
        }
    }

    private static Path artifactPath(Path libDir, DependencyArtifact artifact) throws IOException {
        Path root = libDir.toAbsolutePath().normalize();
        Path target = root.resolve(buildJarFileName(artifact)).normalize();
        if (!root.equals(target.getParent()) || Files.isSymbolicLink(target)
                || (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target))) {
            throw new IOException("Unsafe runtime dependency cache target: " + target.getFileName());
        }
        return target;
    }

    /** An exclusive process-lifetime lease; the OS releases it after shutdown hooks finish. */
    static final class CacheLease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private CacheLease(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        static CacheLease acquire(Path directory) throws IOException {
            if (Files.isSymbolicLink(directory)) {
                throw new IOException("Runtime dependency directory must not be a symbolic link");
            }
            Files.createDirectories(directory);
            Path lockPath = directory.resolve(".bootstrap.lock");
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new IOException("Runtime dependency cache is in use; use a separate working directory");
                }
                return new CacheLease(channel, lock);
            } catch (IOException | OverlappingFileLockException failure) {
                channel.close();
                throw new IOException("Cannot acquire runtime dependency cache lease", failure);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    static String toRelativeArtifactPath(DependencyArtifact artifact) {
        String groupPath = artifact.groupId().replace('.', '/');
        String fileName = buildJarFileName(artifact);
        return groupPath + "/" + artifact.artifactId() + "/" + artifact.version() + "/" + fileName;
    }

    static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not supported by this Java runtime", e);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            value.append(Character.forDigit((current >>> 4) & 0x0f, 16));
            value.append(Character.forDigit(current & 0x0f, 16));
        }
        return value.toString();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static boolean isConflictingProviderArtifact(String artifactId) {
        return CONFLICTING_SLF4J_PROVIDERS.contains(artifactId.toLowerCase(Locale.ROOT));
    }

    private static String normalizeFileName(String fileName) {
        return isWindows() ? fileName.toLowerCase(Locale.ROOT) : fileName;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("win");
    }

    private static double bytesToMegabytes(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private static double elapsedSeconds(long startedAtNanos, long completedAtNanos) {
        return Math.max(0L, completedAtNanos - startedAtNanos) / 1_000_000_000.0;
    }

    private static void logSummary(SyncStats stats) {
        LOGGER.log(System.Logger.Level.INFO, String.format(Locale.ROOT,
                "[NoRule] Runtime dependencies summary: required=%d downloaded=%d reused=%d removed=%d failed=%d "
                        + "totalBytes=%d duration=%.1fs",
                stats.required, stats.downloaded, stats.reused, stats.removed, stats.failed, stats.totalBytes,
                elapsedSeconds(stats.startedAtNanos, System.nanoTime())));
    }

    record DependencyArtifact(String groupId, String artifactId, String version, String classifier, String extension,
            RuntimeRepository repository, String sha256) {
        DependencyArtifact {
            if (groupId == null || !groupId.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) {
                throw new IllegalArgumentException("Invalid runtime dependency groupId");
            }
            validateCoordinatePart(artifactId, "artifactId");
            validateCoordinatePart(version, "version");
            if (classifier == null) {
                throw new IllegalArgumentException("Invalid runtime dependency classifier");
            }
            if (!classifier.isEmpty()) {
                validateCoordinatePart(classifier, "classifier");
            }
            if (!"jar".equals(extension) || repository == null || !isSha256(sha256)) {
                throw new IllegalArgumentException("Invalid runtime dependency metadata");
            }
        }

        private static void validateCoordinatePart(String value, String field) {
            if (value == null || !value.matches("[A-Za-z0-9_][A-Za-z0-9_.+-]*")) {
                throw new IllegalArgumentException("Invalid runtime dependency " + field);
            }
        }
    }

    record BootstrapSettings(boolean cleanupObsolete, boolean verifyChecksums, boolean forceRedownload,
            boolean progressEnabled, int progressIntervalMs, int connectTimeoutMs, int readTimeoutMs,
            int stallTimeoutMs, int maxRetries) {
        private static final boolean DEFAULT_PROGRESS_ENABLED = true;
        private static final int DEFAULT_PROGRESS_INTERVAL_MS = 2_000;
        private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
        private static final int DEFAULT_READ_TIMEOUT_MS = 60_000;
        private static final int DEFAULT_STALL_TIMEOUT_MS = 15_000;
        private static final int DEFAULT_MAX_RETRIES = 3;

        BootstrapSettings(boolean cleanupObsolete, boolean verifyChecksums, boolean forceRedownload) {
            this(cleanupObsolete, verifyChecksums, forceRedownload, DEFAULT_PROGRESS_ENABLED,
                    DEFAULT_PROGRESS_INTERVAL_MS, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS,
                    DEFAULT_STALL_TIMEOUT_MS, DEFAULT_MAX_RETRIES);
        }

        BootstrapSettings {
            requirePositive("progress-interval-ms", progressIntervalMs);
            requirePositive("connect-timeout-ms", connectTimeoutMs);
            requirePositive("read-timeout-ms", readTimeoutMs);
            requirePositive("stall-timeout-ms", stallTimeoutMs);
            if (maxRetries < 0) {
                throw new IllegalStateException("runtime-dependencies.max-retries must be zero or greater");
            }
        }

        static BootstrapSettings fromSystemProperties() {
            return fromValues(Map.of());
        }

        static BootstrapSettings fromConfig(Path configPath) throws IOException {
            Map<String, String> values = Files.isRegularFile(configPath)
                    ? parseRuntimeDependencySettings(Files.readAllLines(configPath, StandardCharsets.UTF_8))
                    : Map.of();
            return fromValues(values);
        }

        private static BootstrapSettings fromValues(Map<String, String> values) {
            if (!readBooleanProperty(VERIFY_CHECKSUMS_PROPERTY, true)) {
                throw new IllegalStateException("Runtime SHA-256 verification cannot be disabled");
            }
            return new BootstrapSettings(
                    false,
                    true,
                    readBooleanProperty(FORCE_REDOWNLOAD_PROPERTY, false),
                    readBooleanSetting(values, "progress-enabled", PROGRESS_ENABLED_PROPERTY,
                            DEFAULT_PROGRESS_ENABLED),
                    readIntSetting(values, "progress-interval-ms", PROGRESS_INTERVAL_PROPERTY,
                            DEFAULT_PROGRESS_INTERVAL_MS),
                    readIntSetting(values, "connect-timeout-ms", CONNECT_TIMEOUT_PROPERTY,
                            DEFAULT_CONNECT_TIMEOUT_MS),
                    readIntSetting(values, "read-timeout-ms", READ_TIMEOUT_PROPERTY, DEFAULT_READ_TIMEOUT_MS),
                    readIntSetting(values, "stall-timeout-ms", STALL_TIMEOUT_PROPERTY, DEFAULT_STALL_TIMEOUT_MS),
                    readIntSetting(values, "max-retries", MAX_RETRIES_PROPERTY, DEFAULT_MAX_RETRIES));
        }

        private static boolean readBooleanProperty(String propertyName, boolean defaultValue) {
            String value = System.getProperty(propertyName);
            if (value == null) {
                return defaultValue;
            }
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                return Boolean.parseBoolean(value);
            }
            throw new IllegalStateException("Invalid boolean JVM property " + propertyName + ": " + value);
        }

        private static boolean readBooleanSetting(Map<String, String> values, String key, String propertyName,
                boolean defaultValue) {
            String value = configuredValue(values, key, propertyName);
            if (value == null) {
                return defaultValue;
            }
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                return Boolean.parseBoolean(value);
            }
            throw new IllegalStateException("Invalid boolean runtime dependency setting " + key + ": " + value);
        }

        private static int readIntSetting(Map<String, String> values, String key, String propertyName,
                int defaultValue) {
            String value = configuredValue(values, key, propertyName);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid integer runtime dependency setting " + key + ": " + value,
                        e);
            }
        }

        private static String configuredValue(Map<String, String> values, String key, String propertyName) {
            String propertyValue = System.getProperty(propertyName);
            return propertyValue == null ? values.get(key) : propertyValue;
        }

        private static void requirePositive(String key, int value) {
            if (value <= 0) {
                throw new IllegalStateException("runtime-dependencies." + key + " must be greater than zero");
            }
        }
    }

    static Map<String, String> parseRuntimeDependencySettings(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean inSection = false;
        int sectionIndent = -1;
        for (String line : lines) {
            if (line == null || line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int indent = leadingSpaces(line);
            String trimmed = line.trim();
            if (!inSection) {
                if (indent == 0 && trimmed.matches("runtime-dependencies\\s*:\\s*(?:#.*)?")) {
                    inSection = true;
                    sectionIndent = indent;
                }
                continue;
            }
            if (indent <= sectionIndent) {
                break;
            }
            int separator = trimmed.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String value = stripInlineComment(trimmed.substring(separator + 1)).trim();
            if (!key.isBlank() && !value.isBlank()) {
                values.put(key, unquote(value));
            }
        }
        return values;
    }

    private static int leadingSpaces(String value) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static String stripInlineComment(String value) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
            } else if (current == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
            } else if (current == '#' && !singleQuoted && !doubleQuoted) {
                return value.substring(0, index);
            }
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static final class SyncStats {
        private final int required;
        private final long startedAtNanos;
        private int downloaded;
        private int reused;
        private int removed;
        private int failed;
        private long totalBytes;

        private SyncStats(int required, long startedAtNanos) {
            this.required = required;
            this.startedAtNanos = startedAtNanos;
        }
    }

    @FunctionalInterface
    interface ArtifactDownloader {
        void download(DependencyArtifact artifact, Path destination) throws IOException;
    }

    @FunctionalInterface
    interface ProgressArtifactDownloader {
        void download(DependencyArtifact artifact, Path destination, int index, int total) throws IOException;
    }
}
