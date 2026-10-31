package com.norule.musicbot.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Executes real dependency checks behind the same platform-parent boundary as java -jar. */
class RuntimeCompatibilityTest {
    @Test
    void resolvesLibrariesSpiDatabaseAndWindowsNativesInIsolatedLoader() throws Throwable {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        URL[] urls = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).map(path -> {
                    try {
                        return path.toUri().toURL();
                    } catch (java.net.MalformedURLException failure) {
                        throw new IllegalArgumentException(failure);
                    }
                }).toArray(URL[]::new);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader("NoRuleCompatibilityTest", urls,
                ClassLoader.getPlatformClassLoader())) {
            try {
                RuntimeDependencyBootstrap.invokeMain(loader, RuntimeCompatibilityProbe.class.getName(), new String[0]);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }
}
