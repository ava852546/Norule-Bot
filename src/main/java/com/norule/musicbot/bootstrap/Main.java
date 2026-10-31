package com.norule.musicbot.bootstrap;

public class Main {
    public static void main(String[] args) {
        try {
            RuntimeDependencyBootstrap.launch(args);
        } catch (Throwable failure) {
            // Unwrap application failures before reaching this JDK-only boundary.
            System.err.println("[NoRule] Startup failed: " + failure.getClass().getName());
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }
}

