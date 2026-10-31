package com.norule.musicbot.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

/** Starts a synthetic bot, never the real Discord application or a configured token. */
class PackagedBootstrapTest {
    @TempDir
    Path directory;

    @Test
    void cachedJarRunsInOneJvmAndKeepsResourcesAvailableAfterMainReturns() throws Exception {
        Path launcher = launcher();
        Process process = start(launcher, "normal");
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), output());
            assertEquals(Long.toString(process.pid()), Files.readString(directory.resolve("pid.txt")));
            assertEquals("late resource", Files.readString(directory.resolve("worker.txt")));
            assertEquals("late resource", Files.readString(directory.resolve("hook.txt")));
            assertTrue(output().contains("downloaded=0 reused=1"), output());
            // A second start must reuse the same bytes and acquire the released OS lock.
            Process second = start(launcher, "normal");
            try {
                assertTrue(second.waitFor(20, TimeUnit.SECONDS));
                assertEquals(0, second.exitValue(), output());
                assertTrue(output().contains("downloaded=0 reused=1"), output());
            } finally {
                second.destroyForcibly();
            }
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void preservesExit75AndRunsShutdownHookInSameJvm() throws Exception {
        Process process = start(launcher(), "75");
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(75, process.exitValue(), output());
            assertEquals(Long.toString(process.pid()), Files.readString(directory.resolve("pid.txt")));
            assertEquals("late resource", Files.readString(directory.resolve("hook.txt")));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void legacyRelaunchedPropertyCannotBypassMissingManifest() throws Exception {
        Path launcher = launcher();
        // java -jar sees a missing manifest when the resource is removed from a copied launcher.
        Path withoutManifest = directory.resolve("without-manifest.jar");
        try (var source = new java.util.jar.JarFile(launcher.toFile());
             var jar = new JarOutputStream(Files.newOutputStream(withoutManifest))) {
            for (var entry : source.stream().toList()) {
                if (!entry.getName().equals("bootstrap/runtime-dependencies.txt")) {
                    jar.putNextEntry(new JarEntry(entry.getName()));
                    try (var input = source.getInputStream(entry)) {
                        input.transferTo(jar);
                    }
                    jar.closeEntry();
                }
            }
        }
        Process process = start(withoutManifest, "normal");
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(1, process.exitValue(), output());
            assertFalse(Files.exists(directory.resolve("pid.txt")));
            assertTrue(output().contains("manifest is missing"), output());
        } finally {
            process.destroyForcibly();
        }
    }

    private Process start(Path launcher, String mode) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Dnorule.bootstrap.relaunched=true", "-jar", launcher.toString(), mode);
        builder.directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("output.log").toFile());
        builder.environment().remove("BOT_CONFIG_PATH");
        builder.environment().remove("DISCORD_TOKEN");
        return builder.start();
    }

    private String output() throws Exception {
        return Files.readString(directory.resolve("output.log"));
    }

    private Path launcher() throws Exception {
        Path libs = Files.createDirectories(directory.resolve("runtime-libs"));
        Path dependency = libs.resolve("fixture-1.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(dependency))) {
            put(jar, "late.txt", "late resource".getBytes(StandardCharsets.UTF_8));
        }
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dependency)));
        Path sources = Files.createDirectories(directory.resolve("sources"));
        Path source = sources.resolve("BotMain.java");
        Files.writeString(source, """
                package com.norule.musicbot.bootstrap;
                public final class BotMain {
                    static void record(String file) {
                        try (var resource = BotMain.class.getClassLoader().getResourceAsStream("late.txt")) {
                            java.nio.file.Files.write(java.nio.file.Path.of(file), resource.readAllBytes());
                        } catch (Exception failure) { throw new RuntimeException(failure); }
                    }
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("pid.txt"),
                                Long.toString(ProcessHandle.current().pid()));
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> record("hook.txt")));
                        if (args[0].equals("75")) { System.exit(75); }
                        new Thread(() -> {
                            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            record("worker.txt");
                        }).start();
                    }
                }
                """, StandardCharsets.UTF_8);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-encoding", "UTF-8", "-d", sources.toString(), source.toString()));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, Main.class.getName());
        Path launcher = directory.resolve("launcher.jar");
        Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path bootstrap = classes.resolve("com/norule/musicbot/bootstrap");
        try (var jar = new JarOutputStream(Files.newOutputStream(launcher), manifest);
             var entries = Files.list(bootstrap)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.equals("Main.class") || List.of("RuntimeDependencyBootstrap", "RuntimeDependencyDownloader",
                        "RuntimeRepository").stream().anyMatch(name::startsWith)) {
                    put(jar, "com/norule/musicbot/bootstrap/" + name, Files.readAllBytes(entry));
                }
            }
            put(jar, "com/norule/musicbot/bootstrap/BotMain.class",
                    Files.readAllBytes(sources.resolve("com/norule/musicbot/bootstrap/BotMain.class")));
            put(jar, "bootstrap/runtime-dependencies.txt",
                    ("org.example|fixture|1||jar|central|" + checksum).getBytes(StandardCharsets.UTF_8));
        }
        return launcher;
    }

    private static void put(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(bytes);
        jar.closeEntry();
    }
}
