package com.norule.musicbot.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RuntimeDependencyBootstrap {
    static final String RELAUNCHED_PROPERTY = "norule.bootstrap.relaunched";
    static final String DEPENDENCY_INDEX_RESOURCE = "/bootstrap/runtime-dependencies.txt";
    private static final String ENABLE_NATIVE_ACCESS_ARG = "--enable-native-access=ALL-UNNAMED";

    private static final List<String> REMOTE_REPOSITORIES = List.of(
            "https://repo.maven.apache.org/maven2",
            "https://maven.lavalink.dev/releases",
            "https://maven.topi.wtf/releases"
    );

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private RuntimeDependencyBootstrap() {
    }

    static boolean ensureDependenciesAndRelaunchIfNeeded(String[] args) {
        if (Boolean.getBoolean(RELAUNCHED_PROPERTY)) {
            return false;
        }

        Path launcherPath = findLauncherPath();
        if (launcherPath == null || !Files.isRegularFile(launcherPath)) {
            return false;
        }

        List<DependencyArtifact> artifacts = loadRuntimeDependencies();
        if (artifacts.isEmpty()) {
            System.out.println("[NoRule] Runtime dependency index is empty, skip lib bootstrap.");
            return false;
        }

        Path workingDir = Path.of(".").toAbsolutePath().normalize();
        Path libDir = workingDir.resolve("lib");
        try {
            Files.createDirectories(libDir);
            downloadMissingArtifacts(libDir, artifacts);
            int exitCode = relaunchWithLibClasspath(launcherPath, libDir, args);
            System.exit(exitCode);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to bootstrap runtime dependencies in ./lib", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while relaunching with lib classpath", e);
        }
    }

    static List<DependencyArtifact> parseDependencyLines(List<String> lines) {
        Map<String, DependencyArtifact> unique = new LinkedHashMap<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = stripAnsi(line).trim();
            if (trimmed.isBlank() || !trimmed.contains(":")) {
                continue;
            }
            DependencyArtifact artifact = parseLine(trimmed);
            if (artifact == null) {
                continue;
            }
            unique.putIfAbsent(buildJarFileName(artifact), artifact);
        }
        return new ArrayList<>(unique.values());
    }

    static String buildJarFileName(DependencyArtifact artifact) {
        if (artifact.classifier() == null || artifact.classifier().isBlank()) {
            return artifact.artifactId() + "-" + artifact.version() + ".jar";
        }
        return artifact.artifactId() + "-" + artifact.version() + "-" + artifact.classifier() + ".jar";
    }

    private static List<DependencyArtifact> loadRuntimeDependencies() {
        InputStream input = RuntimeDependencyBootstrap.class.getResourceAsStream(DEPENDENCY_INDEX_RESOURCE);
        if (input == null) {
            return List.of();
        }
        try (input) {
            List<String> lines = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            return parseDependencyLines(lines);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read runtime dependency index: " + DEPENDENCY_INDEX_RESOURCE, e);
        }
    }

    private static DependencyArtifact parseLine(String line) {
        String[] parts = line.split(":");
        if (parts.length == 5) {
            String groupId = parts[0].trim();
            String artifactId = parts[1].trim();
            String type = parts[2].trim();
            String version = parts[3].trim();
            if (!"jar".equalsIgnoreCase(type) || groupId.isBlank() || artifactId.isBlank() || version.isBlank()) {
                return null;
            }
            return new DependencyArtifact(groupId, artifactId, version, "");
        }
        if (parts.length == 6) {
            String groupId = parts[0].trim();
            String artifactId = parts[1].trim();
            String type = parts[2].trim();
            String classifier = parts[3].trim();
            String version = parts[4].trim();
            if (!"jar".equalsIgnoreCase(type) || groupId.isBlank() || artifactId.isBlank() || version.isBlank()) {
                return null;
            }
            return new DependencyArtifact(groupId, artifactId, version, classifier);
        }
        return null;
    }

    private static void downloadMissingArtifacts(Path libDir, List<DependencyArtifact> artifacts) throws IOException {
        int downloaded = 0;
        for (DependencyArtifact artifact : artifacts) {
            String fileName = buildJarFileName(artifact);
            Path target = libDir.resolve(fileName);
            if (Files.isRegularFile(target)) {
                continue;
            }
            downloadArtifact(artifact, target);
            downloaded++;
            System.out.println("[NoRule] Downloaded dependency: " + fileName);
        }
        if (downloaded > 0) {
            System.out.println("[NoRule] Runtime dependencies downloaded to: " + libDir.toAbsolutePath());
        }
    }

    private static void downloadArtifact(DependencyArtifact artifact, Path target) throws IOException {
        List<String> attemptedUrls = new ArrayList<>();
        IOException lastException = null;
        for (String repo : REMOTE_REPOSITORIES) {
            String relativePath = toRelativeArtifactPath(artifact);
            String url = trimTrailingSlash(repo) + "/" + relativePath;
            attemptedUrls.add(url);
            try {
                if (downloadFrom(url, target)) {
                    return;
                }
            } catch (IOException e) {
                lastException = e;
            }
        }
        IOException failure = new IOException("Unable to download artifact " + artifact + " from repositories: " + attemptedUrls);
        if (lastException != null) {
            failure.addSuppressed(lastException);
        }
        throw failure;
    }

    private static boolean downloadFrom(String url, Path target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            return false;
        }

        Path tempFile = target.resolveSibling(target.getFileName().toString() + ".part");
        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } finally {
            connection.disconnect();
            Files.deleteIfExists(tempFile);
        }
    }

    private static int relaunchWithLibClasspath(Path launcherJar, Path libDir, String[] appArgs) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(resolveJavaExecutable().toString());
        command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        if (shouldAppendNativeAccessArg(command)) {
            command.add(ENABLE_NATIVE_ACCESS_ARG);
        }
        command.add("-D" + RELAUNCHED_PROPERTY + "=true");
        command.add("-cp");
        command.add(launcherJar.toAbsolutePath() + java.io.File.pathSeparator + libDir.toAbsolutePath() + java.io.File.separator + "*");
        command.add(Main.class.getName());
        for (String arg : appArgs) {
            command.add(arg);
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.inheritIO();
        Process child = builder.start();
        return child.waitFor();
    }

    private static boolean shouldAppendNativeAccessArg(List<String> jvmArgs) {
        if (Runtime.version().feature() < 22) {
            return false;
        }
        for (String arg : jvmArgs) {
            if (arg == null) {
                continue;
            }
            if (arg.startsWith("--enable-native-access")) {
                return false;
            }
        }
        return true;
    }

    private static Path resolveJavaExecutable() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        String executable = isWindows() ? "java.exe" : "java";
        return javaHome.resolve("bin").resolve(executable);
    }

    private static Path findLauncherPath() {
        try {
            ProtectionDomain domain = Main.class.getProtectionDomain();
            if (domain == null) {
                return null;
            }
            CodeSource source = domain.getCodeSource();
            if (source == null) {
                return null;
            }
            URI uri = source.getLocation().toURI();
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String toRelativeArtifactPath(DependencyArtifact artifact) {
        String groupPath = artifact.groupId().replace('.', '/');
        String fileName = buildJarFileName(artifact);
        return groupPath + "/" + artifact.artifactId() + "/" + artifact.version() + "/" + fileName;
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String stripAnsi(String value) {
        return value
                .replaceAll("\\u001B\\[[;\\d]*m", "")
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().contains("win");
    }

    record DependencyArtifact(String groupId, String artifactId, String version, String classifier) {
    }
}
