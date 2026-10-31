package com.norule.musicbot.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeClassLoaderTest {
    @TempDir
    Path directory;

    @Test
    void loadsOnlyManifestJarsInOrderAndPreservesPlatformModules() throws Exception {
        Path launcher = jar("launcher.jar", "launcher");
        jar("second-1.jar", "second");
        jar("first-1.jar", "first");
        jar("unlisted.jar", "unlisted");
        var artifacts = List.of(artifact("second"), artifact("first"));
        try (URLClassLoader loader = RuntimeDependencyBootstrap.createRuntimeLoader(launcher, directory, artifacts)) {
            assertSame(ClassLoader.getPlatformClassLoader(), loader.getParent());
            assertEquals(List.of(launcher.toUri().toURL(), directory.resolve("second-1.jar").toUri().toURL(),
                    directory.resolve("first-1.jar").toUri().toURL()), List.of(loader.getURLs()));
            try (var input = loader.getResourceAsStream("marker.txt")) {
                assertNotNull(input);
                assertEquals("launcher", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
            assertEquals(3, loader.resources("marker.txt").count());
            assertSame(java.sql.Driver.class, loader.loadClass("java.sql.Driver"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(getClass().getName()));
        }
    }

    @Test
    void contextLoaderAndLateResourceAccessSurviveMainReturn() throws Throwable {
        Path testClasses = Path.of(LoaderEntry.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = RuntimeDependencyBootstrap.createRuntimeLoader(testClasses, directory, List.of())) {
            try {
                RuntimeDependencyBootstrap.invokeMain(loader, LoaderEntry.class.getName(), new String[]{"argument"});
                assertSame(loader, Thread.currentThread().getContextClassLoader());
                Class<?> entry = loader.loadClass(LoaderEntry.class.getName());
                assertNotSame(LoaderEntry.class, entry);
                assertEquals("argument", entry.getField("argument").get(null));
                assertSame(loader, entry.getField("workerLoader").get(null));
                assertNotNull(loader.getResource(LoaderEntry.class.getName().replace('.', '/') + ".class"));
                assertThrows(ClassNotFoundException.class, () ->
                        RuntimeDependencyBootstrap.invokeMain(loader, "missing.Application", new String[0]));
                IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                        RuntimeDependencyBootstrap.invokeMain(loader, LoaderEntry.class.getName(), new String[]{"fail"}));
                assertEquals("original cause", failure.getMessage());
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    @Test
    void leaseRejectsConcurrentOwnerAndCanBeReacquired() throws Exception {
        try (var lease = RuntimeDependencyBootstrap.CacheLease.acquire(directory)) {
            assertThrows(IOException.class, () -> RuntimeDependencyBootstrap.CacheLease.acquire(directory));
        }
        try (var lease = RuntimeDependencyBootstrap.CacheLease.acquire(directory)) {
            assertNotNull(lease);
        }
    }

    @Test
    void rejectsTraversalAndUrlSyntaxInManifestCoordinates() {
        for (String artifactId : List.of("../escape", "..\\escape", "/escape", "demo?query", "demo#fragment", "C:drive")) {
            assertThrows(IllegalArgumentException.class, () -> RuntimeDependencyBootstrap.parseManifestLines(
                    List.of("org.example|" + artifactId + "|1||jar|central|" + "a".repeat(64))));
        }
    }

    @Test
    void packagedSettingsCannotDisableChecksumVerification() {
        String property = RuntimeDependencyBootstrap.VERIFY_CHECKSUMS_PROPERTY;
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, "false");
            assertThrows(IllegalStateException.class, RuntimeDependencyBootstrap.BootstrapSettings::fromSystemProperties);
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private RuntimeDependencyBootstrap.DependencyArtifact artifact(String name) {
        return new RuntimeDependencyBootstrap.DependencyArtifact("org.example", name, "1", "", "jar",
                RuntimeRepository.MAVEN_CENTRAL, "a".repeat(64));
    }

    private Path jar(String name, String marker) throws IOException {
        Path path = directory.resolve(name);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("marker.txt"));
            jar.write(marker.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return path;
    }

    public static class LoaderEntry {
        public static String argument;
        public static ClassLoader workerLoader;

        public static void main(String[] args) throws InterruptedException {
            if (args[0].equals("fail")) {
                throw new IllegalStateException("original cause");
            }
            argument = args[0];
            Thread worker = new Thread(() -> workerLoader = Thread.currentThread().getContextClassLoader());
            worker.start();
            worker.join();
        }
    }
}
