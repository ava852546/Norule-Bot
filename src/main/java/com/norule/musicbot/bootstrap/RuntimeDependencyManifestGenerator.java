package com.norule.musicbot.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class RuntimeDependencyManifestGenerator {
    private static final String SHA_256 = "SHA-256";
    private static final Pattern DEPENDENCY_LINE = Pattern.compile(
            "^\\s*([^:]+):([^:]+):([^:]+):(?:([^:]+):)?([^:]+):"
                    + "(compile|runtime|provided|system|test):(.+?)(?:\\s+--\\s+module.*)?$");
    private static final Map<String, String> CENTRAL_LIBDAVE_CHECKSUMS = Map.of(
            "moe.kyokobot.libdave:adapter-jda:0.1.2",
            "f38dbec72772e95cdf5043bb521525c934a132db9fb7fbbba049a23d6475ae66",
            "moe.kyokobot.libdave:api:0.1.2",
            "7e50019da94dbb8e03a0089c45afc47af99b740c1a768a1a8fe1e25bb2ffe75e",
            "moe.kyokobot.libdave:impl-jni:0.1.2",
            "3218f7474f5b770ce03c53f6d17d44ebdecd3073dbac693e2b2142b46d561af1");

    private RuntimeDependencyManifestGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected arguments: dependency-list runtime-lib-directory "
                    + "manifest-output checksum-output");
        }
        generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
    }

    static void generate(Path dependencyList, Path runtimeLibDirectory, Path manifestOutput, Path checksumOutput)
            throws IOException {
        List<ResolvedArtifact> resolvedArtifacts = parseResolvedDependencyLines(
                Files.readAllLines(dependencyList, StandardCharsets.UTF_8));
        if (resolvedArtifacts.isEmpty()) {
            throw new IOException("Maven runtime dependency list is empty: " + dependencyList);
        }

        List<RuntimeDependencyBootstrap.DependencyArtifact> manifest = new ArrayList<>();
        Set<String> targetNames = new HashSet<>();
        Set<String> guardedLibdaveArtifacts = new HashSet<>();
        for (ResolvedArtifact resolved : resolvedArtifacts) {
            validateJar(resolved.file());
            String checksum = sha256(resolved.file());
            RuntimeRepository repository = resolveRepository(resolved.file());
            RuntimeDependencyBootstrap.DependencyArtifact artifact = new RuntimeDependencyBootstrap.DependencyArtifact(
                    resolved.groupId(), resolved.artifactId(), resolved.version(), resolved.classifier(),
                    resolved.extension(), repository, checksum);
            String fileName = RuntimeDependencyBootstrap.buildJarFileName(artifact);
            if (!targetNames.add(fileName)) {
                throw new IOException("Runtime dependency target file name is not unique: " + fileName);
            }
            validateRuntimeCopy(runtimeLibDirectory.resolve(fileName), checksum);
            validateCentralLibdaveArtifact(artifact, guardedLibdaveArtifacts);
            manifest.add(artifact);
        }
        if (!guardedLibdaveArtifacts.equals(CENTRAL_LIBDAVE_CHECKSUMS.keySet())) {
            Set<String> missing = new HashSet<>(CENTRAL_LIBDAVE_CHECKSUMS.keySet());
            missing.removeAll(guardedLibdaveArtifacts);
            throw new IOException("Required Maven Central libdave artifacts are missing from runtime dependencies: "
                    + missing);
        }

        manifest.sort(Comparator.comparing(RuntimeDependencyManifestGenerator::sortKey));
        writeManifest(manifestOutput, manifest);
        writeLegacyChecksums(checksumOutput, manifest);
    }

    static List<ResolvedArtifact> parseResolvedDependencyLines(List<String> lines) {
        Map<String, ResolvedArtifact> artifacts = new LinkedHashMap<>();
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = stripAnsi(rawLine);
            Matcher matcher = DEPENDENCY_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String extension = matcher.group(3).trim().toLowerCase(Locale.ROOT);
            if (!"jar".equals(extension)) {
                continue;
            }
            String classifier = matcher.group(4) == null ? "" : matcher.group(4).trim();
            ResolvedArtifact artifact = new ResolvedArtifact(
                    matcher.group(1).trim(), matcher.group(2).trim(), matcher.group(5).trim(), classifier,
                    extension, Path.of(matcher.group(7).trim()).toAbsolutePath().normalize());
            artifacts.putIfAbsent(artifact.coordinate(), artifact);
        }
        return new ArrayList<>(artifacts.values());
    }

    static RuntimeRepository resolveRepository(Path artifactFile) throws IOException {
        Path marker = artifactFile.getParent().resolve("_remote.repositories");
        if (!Files.isRegularFile(marker)) {
            throw unverifiableRepository(artifactFile, "Maven resolver marker is missing");
        }

        String prefix = artifactFile.getFileName() + ">";
        Set<RuntimeRepository> repositories = EnumSet.noneOf(RuntimeRepository.class);
        Set<String> unknownRepositoryIds = new HashSet<>();
        for (String line : Files.readAllLines(marker, StandardCharsets.ISO_8859_1)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(prefix) || !trimmed.endsWith("=")) {
                continue;
            }
            String repositoryId = trimmed.substring(prefix.length(), trimmed.length() - 1);
            try {
                repositories.add(RuntimeRepository.fromResolverId(repositoryId));
            } catch (IllegalArgumentException ignored) {
                unknownRepositoryIds.add(repositoryId);
            }
        }
        for (RuntimeRepository preferred : RuntimeRepository.values()) {
            if (repositories.contains(preferred)) {
                return preferred;
            }
        }
        String detail = unknownRepositoryIds.isEmpty()
                ? "No supported source repository is recorded"
                : "Unsupported repository IDs are recorded: " + unknownRepositoryIds;
        throw unverifiableRepository(artifactFile, detail);
    }

    private static IOException unverifiableRepository(Path artifactFile, String detail) {
        return new IOException(detail + " for " + artifactFile + ". Repository resolution may be overridden by "
                + "Maven settings mirror. The build will not guess a runtime repository.");
    }

    private static void validateJar(Path jar) throws IOException {
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Resolved runtime dependency does not exist: " + jar);
        }
        try (ZipFile ignored = new ZipFile(jar.toFile())) {
            // Opening the central directory is sufficient to reject truncated or non-ZIP files.
        } catch (ZipException e) {
            throw new IOException("Resolved runtime dependency is not a valid JAR/ZIP: " + jar, e);
        }
    }

    private static void validateRuntimeCopy(Path copiedJar, String expectedChecksum) throws IOException {
        validateJar(copiedJar);
        String copiedChecksum = sha256(copiedJar);
        if (!expectedChecksum.equals(copiedChecksum)) {
            throw new IOException("Copied runtime dependency differs from Maven's resolved artifact: " + copiedJar);
        }
    }

    static void validateCentralLibdaveArtifact(RuntimeDependencyBootstrap.DependencyArtifact artifact,
            Set<String> guardedArtifacts) throws IOException {
        String coordinate = artifact.groupId() + ":" + artifact.artifactId() + ":" + artifact.version();
        String expectedChecksum = CENTRAL_LIBDAVE_CHECKSUMS.get(coordinate);
        if (expectedChecksum == null) {
            return;
        }
        guardedArtifacts.add(coordinate);
        if (artifact.repository() != RuntimeRepository.MAVEN_CENTRAL
                || !expectedChecksum.equals(artifact.sha256())) {
            throw new IOException("Resolved artifact content does not match the Maven Central release: " + coordinate
                    + ". Delete the local Maven cache or check repository/mirror configuration. "
                    + "Repository resolution may be overridden by Maven settings mirror.");
        }
    }

    private static void writeManifest(Path output, List<RuntimeDependencyBootstrap.DependencyArtifact> artifacts)
            throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# groupId|artifactId|version|classifier|extension|repository|sha256");
        for (RuntimeDependencyBootstrap.DependencyArtifact artifact : artifacts) {
            lines.add(String.join("|", artifact.groupId(), artifact.artifactId(), artifact.version(),
                    artifact.classifier(), artifact.extension(), artifact.repository().id(), artifact.sha256()));
        }
        writeLines(output, lines);
    }

    private static void writeLegacyChecksums(Path output,
            List<RuntimeDependencyBootstrap.DependencyArtifact> artifacts) throws IOException {
        List<String> lines = artifacts.stream()
                .map(artifact -> artifact.sha256() + " *" + RuntimeDependencyBootstrap.buildJarFileName(artifact))
                .toList();
        writeLines(output, lines);
    }

    private static void writeLines(Path output, List<String> lines) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
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
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sortKey(RuntimeDependencyBootstrap.DependencyArtifact artifact) {
        return String.join("|", artifact.groupId(), artifact.artifactId(), artifact.version(), artifact.classifier(),
                artifact.extension());
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\\u001B\\[[;\\d]*m", "")
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
    }

    record ResolvedArtifact(String groupId, String artifactId, String version, String classifier, String extension,
            Path file) {
        private String coordinate() {
            return String.join(":", groupId, artifactId, extension, classifier, version);
        }
    }
}
