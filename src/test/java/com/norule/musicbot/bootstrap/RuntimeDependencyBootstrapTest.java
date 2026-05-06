package com.norule.musicbot.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeDependencyBootstrapTest {

    @Test
    void parsesRuntimeDependencyListOutput() {
        List<RuntimeDependencyBootstrap.DependencyArtifact> artifacts = RuntimeDependencyBootstrap.parseDependencyLines(List.of(
                "The following files have been resolved:",
                "   net.dv8tion:JDA:jar:6.3.1:compile\u001B[36m -- module net.dv8tion.jda\u001B[0m",
                "   org.slf4j:slf4j-simple:jar:2.0.17:compile",
                ""
        ));

        assertEquals(2, artifacts.size());
        assertEquals("JDA-6.3.1.jar", RuntimeDependencyBootstrap.buildJarFileName(artifacts.get(0)));
        assertEquals("slf4j-simple-2.0.17.jar", RuntimeDependencyBootstrap.buildJarFileName(artifacts.get(1)));
    }

    @Test
    void supportsClassifierCoordinates() {
        List<RuntimeDependencyBootstrap.DependencyArtifact> artifacts = RuntimeDependencyBootstrap.parseDependencyLines(List.of(
                "   org.example:demo:jar:linux-x86_64:1.0.0:runtime"
        ));

        assertEquals(1, artifacts.size());
        assertEquals("demo-1.0.0-linux-x86_64.jar", RuntimeDependencyBootstrap.buildJarFileName(artifacts.get(0)));
    }

    @Test
    void deduplicatesByTargetJarFileName() {
        List<RuntimeDependencyBootstrap.DependencyArtifact> artifacts = RuntimeDependencyBootstrap.parseDependencyLines(List.of(
                "   org.slf4j:slf4j-api:jar:2.0.17:compile",
                "   org.slf4j:slf4j-api:jar:2.0.17:runtime"
        ));

        assertEquals(1, artifacts.size());
        assertEquals("slf4j-api-2.0.17.jar", RuntimeDependencyBootstrap.buildJarFileName(artifacts.get(0)));
    }
}
