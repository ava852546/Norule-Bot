package com.norule.musicbot.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDependencyManifestGeneratorTest {
    private static final List<LibdaveChecksum> LIBDAVE_CHECKSUMS = List.of(
            new LibdaveChecksum("adapter-jda",
                    "f38dbec72772e95cdf5043bb521525c934a132db9fb7fbbba049a23d6475ae66",
                    "96453b0fac28e42e1d85b95e46156525854f838476472afed31e10b49626ae20"),
            new LibdaveChecksum("api",
                    "7e50019da94dbb8e03a0089c45afc47af99b740c1a768a1a8fe1e25bb2ffe75e",
                    "656b2ccf72d433f84242a5ed55436d6724247daf29e4cd306c612cde0fdbea91"),
            new LibdaveChecksum("impl-jni",
                    "3218f7474f5b770ce03c53f6d17d44ebdecd3073dbac693e2b2142b46d561af1",
                    "09262cd11096e874023a130f2de41abb915508d6c483e687c0f18859b5642c2e"));

    @TempDir
    Path tempDir;

    @Test
    void parsesAbsoluteMavenDependencyListWithAndWithoutClassifier() {
        List<RuntimeDependencyManifestGenerator.ResolvedArtifact> artifacts =
                RuntimeDependencyManifestGenerator.parseResolvedDependencyLines(List.of(
                        "   org.example:demo:jar:1.0.0:runtime:C:\\m2\\demo-1.0.0.jar -- module demo",
                        "   org.example:native:jar:linux-x86-64:2.0.0:compile:C:\\m2\\native.jar"));

        assertEquals(2, artifacts.size());
        assertEquals("", artifacts.get(0).classifier());
        assertEquals("linux-x86-64", artifacts.get(1).classifier());
    }

    @Test
    void prefersCentralWhenResolverMarkerContainsBothRepositories() throws IOException {
        Path artifact = createResolverMarker("demo-1.0.0.jar",
                "demo-1.0.0.jar>lavalink-releases=",
                "demo-1.0.0.jar>central=");

        assertEquals(RuntimeRepository.MAVEN_CENTRAL,
                RuntimeDependencyManifestGenerator.resolveRepository(artifact));
    }

    @Test
    void usesLavalinkWhenItIsTheRecordedSource() throws IOException {
        Path artifact = createResolverMarker("demo-1.0.0.jar", "demo-1.0.0.jar>lavalink-releases=");

        assertEquals(RuntimeRepository.LAVALINK_RELEASES,
                RuntimeDependencyManifestGenerator.resolveRepository(artifact));
    }

    @ParameterizedTest
    @CsvSource({
            "MAVEN_CENTRAL, central-8fac3ebd6edbaca3c794783fa38088af0aa128e7",
            "LAVALINK_RELEASES, lavalink-releases-30024d3cb07e8f2cc1a12c5ebf2de1a59c4127c4",
            "TOPIWTF_RELEASES, topiwtf-releases-2c759bccfc78c076a7e542c5047c6af37660969c"
    })
    void resolvesHashedAndMixedMarkers(RuntimeRepository repository, String resolverId) throws IOException {
        Path artifact = createResolverMarker("demo-1.0.0.jar", "demo-1.0.0.jar>" + resolverId + "=");
        assertEquals(repository, RuntimeDependencyManifestGenerator.resolveRepository(artifact));

        artifact = createResolverMarker("demo-1.0.0.jar",
                "demo-1.0.0.jar>" + repository.id() + "=",
                "demo-1.0.0.jar>" + resolverId + "=");
        assertEquals(repository, RuntimeDependencyManifestGenerator.resolveRepository(artifact));
    }

    @Test
    void preservesPreferenceAcrossLegacyHashedAndUnknownMarkers() throws IOException {
        Path artifact = createResolverMarker("demo-1.0.0.jar",
                "demo-1.0.0.jar>enterprise-mirror=",
                "demo-1.0.0.jar>lavalink-releases=",
                "demo-1.0.0.jar>central-8fac3ebd6edbaca3c794783fa38088af0aa128e7=");
        assertEquals(RuntimeRepository.MAVEN_CENTRAL,
                RuntimeDependencyManifestGenerator.resolveRepository(artifact));

        artifact = createResolverMarker("demo-1.0.0.jar",
                "demo-1.0.0.jar>central-malicious=",
                "demo-1.0.0.jar>topiwtf-releases-2c759bccfc78c076a7e542c5047c6af37660969c=",
                "demo-1.0.0.jar>lavalink-releases=");
        assertEquals(RuntimeRepository.LAVALINK_RELEASES,
                RuntimeDependencyManifestGenerator.resolveRepository(artifact));
    }

    @ParameterizedTest
    @ValueSource(strings = {"central-malicious", "central-0000000000000000000000000000000000000000", ""})
    void rejectsUntrustedSourceEvenWhenAnotherArtifactHasCentralMarker(String repositoryId) throws IOException {
        Path artifact = createResolverMarker("demo-1.0.0.jar",
                "demo-1.0.0.pom>central=",
                "other-1.0.0.jar>central-8fac3ebd6edbaca3c794783fa38088af0aa128e7=",
                "demo-1.0.0.jar>" + repositoryId + "=");
        assertThrows(IOException.class, () -> RuntimeDependencyManifestGenerator.resolveRepository(artifact));
    }

    @Test
    void rejectsMissingMarker() {
        assertThrows(IOException.class,
                () -> RuntimeDependencyManifestGenerator.resolveRepository(tempDir.resolve("demo.jar")));
    }

    @Test
    void failsWhenNoSupportedRepositoryIsRecorded() throws IOException {
        Path artifact = createResolverMarker("demo-1.0.0.jar", "demo-1.0.0.jar>enterprise-mirror=");

        IOException failure = assertThrows(IOException.class,
                () -> RuntimeDependencyManifestGenerator.resolveRepository(artifact));

        assertTrue(failure.getMessage().contains("Repository resolution may be overridden by Maven settings mirror"));
    }

    @Test
    void acceptsOnlyCentralChecksumsForPinnedLibdaveArtifacts() throws IOException {
        Set<String> guarded = new HashSet<>();
        for (LibdaveChecksum checksum : LIBDAVE_CHECKSUMS) {
            RuntimeDependencyManifestGenerator.validateCentralLibdaveArtifact(
                    artifact(checksum.artifactId(), RuntimeRepository.MAVEN_CENTRAL, checksum.central()), guarded);

            IOException wrongRepository = assertThrows(IOException.class,
                    () -> RuntimeDependencyManifestGenerator.validateCentralLibdaveArtifact(
                            artifact(checksum.artifactId(), RuntimeRepository.LAVALINK_RELEASES, checksum.central()),
                            new HashSet<>()));
            IOException wrongContent = assertThrows(IOException.class,
                    () -> RuntimeDependencyManifestGenerator.validateCentralLibdaveArtifact(
                            artifact(checksum.artifactId(), RuntimeRepository.MAVEN_CENTRAL, checksum.lavalink()),
                            new HashSet<>()));

            assertTrue(wrongRepository.getMessage().contains("does not match the Maven Central release"));
            assertTrue(wrongContent.getMessage().contains("does not match the Maven Central release"));
        }
        assertEquals(3, guarded.size());
    }

    @Test
    void buildsPinnedCentralUrlsForLibdaveArtifacts() {
        for (LibdaveChecksum checksum : LIBDAVE_CHECKSUMS) {
            RuntimeDependencyBootstrap.DependencyArtifact artifact = artifact(
                    checksum.artifactId(), RuntimeRepository.MAVEN_CENTRAL, checksum.central());
            String expected = "https://repo.maven.apache.org/maven2/moe/kyokobot/libdave/"
                    + checksum.artifactId() + "/0.1.2/" + checksum.artifactId() + "-0.1.2.jar";

            assertEquals(expected, artifact.repository().baseUrl() + "/"
                    + RuntimeDependencyBootstrap.toRelativeArtifactPath(artifact));
        }
    }

    private Path createResolverMarker(String fileName, String... markerLines) throws IOException {
        Path artifact = tempDir.resolve(fileName);
        Files.writeString(artifact, "placeholder", StandardCharsets.UTF_8);
        Files.write(artifact.getParent().resolve("_remote.repositories"), List.of(markerLines),
                StandardCharsets.ISO_8859_1);
        return artifact;
    }

    private static RuntimeDependencyBootstrap.DependencyArtifact artifact(
            String artifactId, RuntimeRepository repository, String checksum) {
        return new RuntimeDependencyBootstrap.DependencyArtifact(
                "moe.kyokobot.libdave", artifactId, "0.1.2", "", "jar", repository, checksum);
    }

    private record LibdaveChecksum(String artifactId, String central, String lavalink) {
    }
}
