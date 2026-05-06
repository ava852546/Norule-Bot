package com.norule.musicbot.bootstrap;

public class Main {
    public static void main(String[] args) {
        try {
            if (RuntimeDependencyBootstrap.ensureDependenciesAndRelaunchIfNeeded(args)) {
                return;
            }
            new RuntimeBootstrap().run(args);
        } catch (IllegalStateException ex) {
            System.out.println("[NoRule] " + ex.getMessage());
            System.exit(1);
        }
    }
}

