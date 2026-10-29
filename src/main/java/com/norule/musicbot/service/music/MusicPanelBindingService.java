package com.norule.musicbot.service.music;

import com.norule.musicbot.domain.music.MusicPanelBinding;
import com.norule.musicbot.storage.sqlite.MusicPanelSqliteRepository;
import java.util.List;

/** Durable MUSIC bindings are independent of automatic channel provisioning state. */
public final class MusicPanelBindingService {
    private final MusicPanelSqliteRepository repository;

    public MusicPanelBindingService(MusicPanelSqliteRepository repository) {
        this.repository = repository;
    }

    public List<MusicPanelBinding> load() {
        return repository.findAll();
    }

    public void save(long guildId, long channelId, long messageId) {
        repository.save(new MusicPanelBinding(guildId, channelId, messageId));
    }

    public void forgetGuild(long guildId) {
        repository.delete(guildId);
    }
}
