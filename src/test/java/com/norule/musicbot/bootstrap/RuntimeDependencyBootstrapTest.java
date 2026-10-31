package com.norule.musicbot.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RuntimeDependencyBootstrapTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesRuntimeDependencyManifest() {
        String checksum = "a".repeat(64);
        List<RuntimeDependencyBootstrap.DependencyArtifact> artifacts = RuntimeDependencyBootstrap.parseManifestLines(
                List.of(
                        "# groupId|artifactId|version|classifier|extension|repository|sha256",
                        "net.dv8tion|JDA|6.5.0||jar|central|" + checksum));

        assertEquals(1, artifacts.size());
        assertEquals("JDA-6.5.0.jar", RuntimeDependencyBootstrap.buildJarFileName(artifacts.get(0)));
        assertEquals(RuntimeRepository.MAVEN_CENTRAL, artifacts.get(0).repository());
        assertEquals(checksum, artifacts.get(0).sha256());
    }

    @Test
    void supportsClassifierCoordinates() {
        List<RuntimeDependencyBootstrap.DependencyArtifact> artifacts = RuntimeDependencyBootstrap.parseManifestLines(
                List.of("org.example|demo|1.0.0|linux-x86_64|jar|lavalink-releases|" + "b".repeat(64)));

        assertEquals(1, artifacts.size());
        assertEquals("demo-1.0.0-linux-x86_64.jar", RuntimeDependencyBootstrap.buildJarFileName(artifacts.get(0)));
    }

    @Test
    void deduplicatesByTargetJarFileName() {
        String checksum = "c".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> RuntimeDependencyBootstrap.parseManifestLines(List.of(
                "org.slf4j|slf4j-api|2.0.17||jar|central|" + checksum,
                "org.slf4j|slf4j-api|2.0.17||jar|central|" + checksum)));
    }

    @Test
    void rejectsManifestWithoutRepository() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RuntimeDependencyBootstrap.parseManifestLines(
                        List.of("org.example|demo|1.0.0||jar|" + "a".repeat(64))));

        assertTrue(failure.getMessage().contains("expected repository"));
    }

    @Test
    void rejectsUnknownManifestRepository() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> RuntimeDependencyBootstrap.parseManifestLines(
                        List.of("org.example|demo|1.0.0||jar|unknown|" + "a".repeat(64))));

        assertTrue(failure.getMessage().contains("Unknown runtime dependency repository ID"));
    }

    @Test
    void readsRuntimeDependencyDownloadSettingsFromConfig() throws IOException {
        Path config = tempDir.resolve("config.yml");
        Files.writeString(config, """
                token: "ignored-secret"
                runtime-dependencies:
                  progress-enabled: false
                  progress-interval-ms: 3500
                  connect-timeout-ms: 12000
                  read-timeout-ms: 70000
                  stall-timeout-ms: 18000
                  max-retries: 4
                web:
                  enabled: false
                """, StandardCharsets.UTF_8);

        RuntimeDependencyBootstrap.BootstrapSettings settings =
                RuntimeDependencyBootstrap.BootstrapSettings.fromConfig(config);

        assertFalse(settings.progressEnabled());
        assertEquals(3_500, settings.progressIntervalMs());
        assertEquals(12_000, settings.connectTimeoutMs());
        assertEquals(70_000, settings.readTimeoutMs());
        assertEquals(18_000, settings.stallTimeoutMs());
        assertEquals(4, settings.maxRetries());
    }

    @Test
    void preservesOldArtifactVersionAndDownloadsCurrentVersion() throws IOException {
        Path runtimeLibs = createRuntimeLibs();
        Files.writeString(runtimeLibs.resolve("demo-1.0.0.jar"), "old", StandardCharsets.UTF_8);
        RuntimeDependencyBootstrap.DependencyArtifact current = artifact("demo", "2.0.0");
        AtomicInteger downloads = new AtomicInteger();

        RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                runtimeLibs,
                List.of(current),
                settings(true, false, false),
                (artifact, destination) -> {
                    downloads.incrementAndGet();
                    Files.writeString(destination, "current", StandardCharsets.UTF_8);
                });

        assertEquals(1, downloads.get());
        assertTrue(Files.exists(runtimeLibs.resolve("demo-1.0.0.jar")));
        assertEquals("current", Files.readString(runtimeLibs.resolve("demo-2.0.0.jar"), StandardCharsets.UTF_8));
    }

    @Test
    void preservesUnknownJarsAndNonJarFiles() throws IOException {
        Path runtimeLibs = createRuntimeLibs();
        Files.writeString(runtimeLibs.resolve("demo-2.0.0.jar"), "current", StandardCharsets.UTF_8);
        Files.writeString(runtimeLibs.resolve("user-addon.jar"), "unknown", StandardCharsets.UTF_8);
        Files.writeString(runtimeLibs.resolve("user-notes.txt"), "keep", StandardCharsets.UTF_8);

        RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                runtimeLibs,
                List.of(artifact("demo", "2.0.0")),
                settings(true, false, false),
                RuntimeDependencyBootstrapTest::failUnexpectedDownload);

        assertTrue(Files.exists(runtimeLibs.resolve("user-addon.jar")));
        assertTrue(Files.isRegularFile(runtimeLibs.resolve("user-notes.txt")));
    }

    @Test
    void preservesConflictingSlf4jProvidersOutsideLoadList() throws IOException {
        Path runtimeLibs = createRuntimeLibs();
        Files.writeString(runtimeLibs.resolve("demo-2.0.0.jar"), "current", StandardCharsets.UTF_8);
        Files.writeString(runtimeLibs.resolve("slf4j-simple-2.0.17.jar"), "conflict", StandardCharsets.UTF_8);
        Files.writeString(runtimeLibs.resolve("log4j-slf4j2-impl-2.23.1.jar"), "conflict", StandardCharsets.UTF_8);
        Files.writeString(runtimeLibs.resolve("slf4j-reload4j-2.0.17.jar"), "conflict", StandardCharsets.UTF_8);
        Files.writeString(runtimeLibs.resolve("user-addon.jar"), "kept when cleanup is disabled", StandardCharsets.UTF_8);

        RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                runtimeLibs,
                List.of(artifact("demo", "2.0.0")),
                settings(false, false, false),
                RuntimeDependencyBootstrapTest::failUnexpectedDownload);

        assertTrue(Files.exists(runtimeLibs.resolve("slf4j-simple-2.0.17.jar")));
        assertTrue(Files.exists(runtimeLibs.resolve("log4j-slf4j2-impl-2.23.1.jar")));
        assertTrue(Files.exists(runtimeLibs.resolve("slf4j-reload4j-2.0.17.jar")));
        assertTrue(Files.isRegularFile(runtimeLibs.resolve("user-addon.jar")));
    }

    @Test
    void preservesOlderLogbackPairForRollback() throws IOException {
        Path runtimeLibs = createRuntimeLibs();
        Files.writeString(runtimeLibs.resolve("logback-classic-1.5.38.jar"), "classic", StandardCharsets.UTF_8);
        Files.writeString(runtimeLibs.resolve("logback-core-1.5.38.jar"), "core", StandardCharsets.UTF_8);
        Files.writeString(runtimeLibs.resolve("logback-classic-1.4.14.jar"), "old", StandardCharsets.UTF_8);
        Files.writeString(runtimeLibs.resolve("logback-core-1.4.14.jar"), "old", StandardCharsets.UTF_8);

        RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                runtimeLibs,
                List.of(
                        artifact("logback-classic", "1.5.38"),
                        artifact("logback-core", "1.5.38")),
                settings(false, false, false),
                RuntimeDependencyBootstrapTest::failUnexpectedDownload);

        assertTrue(Files.isRegularFile(runtimeLibs.resolve("logback-classic-1.5.38.jar")));
        assertTrue(Files.isRegularFile(runtimeLibs.resolve("logback-core-1.5.38.jar")));
        assertTrue(Files.exists(runtimeLibs.resolve("logback-classic-1.4.14.jar")));
        assertTrue(Files.exists(runtimeLibs.resolve("logback-core-1.4.14.jar")));
    }

    @Test
    void rejectsMismatchedLogbackVersionsBeforeChangingDirectory() throws IOException {
        Path runtimeLibs = createRuntimeLibs();
        Path userJar = runtimeLibs.resolve("user-addon.jar");
        Files.writeString(userJar, "keep", StandardCharsets.UTF_8);

        IOException failure = assertThrows(IOException.class, () ->
                RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                        runtimeLibs,
                        List.of(
                                artifact("logback-classic", "1.5.38"),
                                artifact("logback-core", "1.4.14")),
                        settings(true, false, false),
                        RuntimeDependencyBootstrapTest::failUnexpectedDownload));

        assertTrue(failure.getMessage().contains("matching version"));
        assertTrue(Files.isRegularFile(userJar));
    }

    @Test
    void deletesPartFileWhenDownloadFails() throws IOException {
        Path runtimeLibs = createRuntimeLibs();
        RuntimeDependencyBootstrap.DependencyArtifact artifact = artifact("demo", "2.0.0");
        Path target = runtimeLibs.resolve("demo-2.0.0.jar");
        Path part = runtimeLibs.resolve("demo-2.0.0.jar.part");

        assertThrows(IOException.class, () -> RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                runtimeLibs,
                List.of(artifact),
                settings(true, false, false),
                (ignored, destination) -> {
                    Files.writeString(destination, "partial", StandardCharsets.UTF_8);
                    throw new IOException("simulated download failure");
                }));

        assertFalse(Files.exists(target));
        assertFalse(Files.exists(part));
    }

    @Test
    void redownloadsCorruptedJarAndAtomicallyReplacesIt() throws IOException {
        Path runtimeLibs = createRuntimeLibs();
        Path target = runtimeLibs.resolve("demo-2.0.0.jar");
        byte[] expectedBytes = "valid jar bytes".getBytes(StandardCharsets.UTF_8);
        RuntimeDependencyBootstrap.DependencyArtifact artifact = artifact(
                "demo", "2.0.0", sha256(expectedBytes));
        Files.writeString(target, "corrupted", StandardCharsets.UTF_8);
        AtomicInteger downloads = new AtomicInteger();

        RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                runtimeLibs,
                List.of(artifact),
                settings(true, true, false),
                (ignored, destination) -> {
                    downloads.incrementAndGet();
                    Files.write(destination, expectedBytes);
                });

        assertEquals(1, downloads.get());
        assertEquals("valid jar bytes", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(runtimeLibs.resolve("demo-2.0.0.jar.part")));
    }

    @Test
    void doesNotDownloadJarWithMatchingChecksum() throws IOException {
        Path runtimeLibs = createRuntimeLibs();
        byte[] expectedBytes = "valid jar bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(runtimeLibs.resolve("demo-2.0.0.jar"), expectedBytes);
        Files.writeString(runtimeLibs.resolve("demo-2.0.0.jar.part"), "stale partial", StandardCharsets.UTF_8);

        RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                runtimeLibs,
                List.of(artifact("demo", "2.0.0", sha256(expectedBytes))),
                settings(true, true, false),
                RuntimeDependencyBootstrapTest::failUnexpectedDownload);

        assertTrue(Files.isRegularFile(runtimeLibs.resolve("demo-2.0.0.jar")));
        assertFalse(Files.exists(runtimeLibs.resolve("demo-2.0.0.jar.part")));
    }

    @Test
    void forceRedownloadReplacesOtherwiseValidJar() throws IOException {
        Path runtimeLibs = createRuntimeLibs();
        Path target = runtimeLibs.resolve("demo-2.0.0.jar");
        Files.writeString(target, "old bytes", StandardCharsets.UTF_8);
        AtomicInteger downloads = new AtomicInteger();

        RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                runtimeLibs,
                List.of(artifact("demo", "2.0.0")),
                settings(true, false, true),
                (ignored, destination) -> {
                    downloads.incrementAndGet();
                    Files.writeString(destination, "new bytes", StandardCharsets.UTF_8);
                });

        assertEquals(1, downloads.get());
        assertEquals("new bytes", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void checksumFailureDeletesPartAndKeepsExistingJar() throws IOException {
        Path runtimeLibs = createRuntimeLibs();
        Path target = runtimeLibs.resolve("demo-2.0.0.jar");
        Files.writeString(target, "existing corrupted bytes", StandardCharsets.UTF_8);
        byte[] expectedBytes = "expected bytes".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> RuntimeDependencyBootstrap.synchronizeRuntimeDependencies(
                runtimeLibs,
                List.of(artifact("demo", "2.0.0", sha256(expectedBytes))),
                settings(true, true, false),
                (ignored, destination) -> Files.writeString(
                        destination, "bad downloaded bytes", StandardCharsets.UTF_8)));

        assertEquals("existing corrupted bytes", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(runtimeLibs.resolve("demo-2.0.0.jar.part")));
    }

    private Path createRuntimeLibs() throws IOException {
        return Files.createDirectories(tempDir.resolve(RuntimeDependencyBootstrap.RUNTIME_LIB_DIRECTORY));
    }

    private static RuntimeDependencyBootstrap.DependencyArtifact artifact(String artifactId, String version) {
        return artifact(artifactId, version, "a".repeat(64));
    }

    private static RuntimeDependencyBootstrap.DependencyArtifact artifact(
            String artifactId, String version, String checksum) {
        return new RuntimeDependencyBootstrap.DependencyArtifact("org.example", artifactId, version, "", "jar",
                RuntimeRepository.MAVEN_CENTRAL, checksum);
    }

    private static RuntimeDependencyBootstrap.BootstrapSettings settings(
            boolean cleanupObsolete, boolean verifyChecksums, boolean forceRedownload) {
        return new RuntimeDependencyBootstrap.BootstrapSettings(
                cleanupObsolete, verifyChecksums, forceRedownload);
    }

    private static void failUnexpectedDownload(
            RuntimeDependencyBootstrap.DependencyArtifact artifact, Path destination) {
        fail("Unexpected download of " + artifact + " to " + destination);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
