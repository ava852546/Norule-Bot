package com.norule.musicbot.config.loader;

import com.norule.musicbot.config.BotConfig;
import com.norule.musicbot.config.lang.LanguageManager;

import java.nio.file.Path;

public final class ConfigLoader {
    private final ConfigInitializer initializer;
    private final BotConfigParser parser;

    public ConfigLoader() {
        this.initializer = new ConfigInitializer(new LanguageManager());
        this.parser = new BotConfigParser();
    }

    public BotConfig load(Path configPath) {
        initializer.initialize(configPath);
        return parser.parse(configPath);
    }

    public BotConfig reload(Path configPath) {
        return load(configPath);
    }

    @Deprecated
    public BotConfig initializeAndLoad(Path configPath) {
        return load(configPath);
    }
}
