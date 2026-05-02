package com.norule.musicbot.config.loader;

import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.config.lang.LanguageManager;

import java.nio.file.Path;

public final class ConfigLoader {
    private final ConfigInitializer initializer;

    public ConfigLoader() {
        this.initializer = new ConfigInitializer(new LanguageManager());
    }

    public BotConfig initializeAndLoad(Path configPath) {
        initializer.initialize(configPath);
        return BotConfig.load(configPath);
    }

    public BotConfig reload(Path configPath) {
        return initializeAndLoad(configPath);
    }
}
