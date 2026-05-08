package com.norule.musicbot.bootstrap;

public class Main {
    public static void main(String[] args) {
        try {
            RuntimeDependencyBootstrap.ensureDependenciesAndRelaunchIfNeeded(args);
            new RuntimeBootstrap().run();
        } catch (IllegalStateException ex) {
            System.out.println("[NoRule] " + ex.getMessage());
            System.exit(1);
        }
    }
}

