package com.norule.musicbot.discord.bot.gateway.panel;

import com.norule.musicbot.domain.music.MusicPanelBinding;
import com.norule.musicbot.domain.music.MusicCommandChannelProvisioning;
import com.norule.musicbot.service.music.MusicCommandChannelProvisioningService;
import com.norule.musicbot.storage.sqlite.MusicCommandChannelProvisioningSqliteRepository;
import com.norule.musicbot.storage.sqlite.MusicPanelSqliteRepository;
import com.norule.musicbot.storage.sqlite.SqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MusicPanelPersistenceTest {
    @TempDir
    Path directory;

    @Test
    void restartRestoresBindingReplacementOverwritesAndGuildLeaveRemovesIt() {
        Path path = directory.resolve("bot.db");
        MusicPanelStateStore first = new MusicPanelStateStore();
        first.initializePersistence(new com.norule.musicbot.service.music.MusicPanelBindingService(
                new MusicPanelSqliteRepository(new SqliteDatabase(path))));
        first.activatePanelRef(1, new MusicPanelStateStore.PanelRef(10, 100), 500);
        MusicPanelStateStore restarted = new MusicPanelStateStore();
        var repository = new MusicPanelSqliteRepository(new SqliteDatabase(path));
        restarted.initializePersistence(new com.norule.musicbot.service.music.MusicPanelBindingService(repository));
        assertTrue(restarted.isActivePanel(1, 10, 100));
        assertEquals(0, restarted.getLastRefreshAt(1));
        restarted.activatePanelRef(1, new MusicPanelStateStore.PanelRef(10, 200), 600);
        assertEquals(java.util.List.of(new MusicPanelBinding(1, 10, 200)), repository.findAll());
        restarted.forgetGuild(1);
        assertNull(restarted.getPanelRef(1));
        assertTrue(repository.findAll().isEmpty());
    }

    @Test
    void manualBindingDoesNotChangeFailedChannelProvisioningClaim() {
        SqliteDatabase database = new SqliteDatabase(directory.resolve("bot.db"));
        var claims = new MusicCommandChannelProvisioningSqliteRepository(database);
        var provisioning = new MusicCommandChannelProvisioningService(claims);
        var attempt = provisioning.tryStart(1);
        provisioning.fail(attempt, new IllegalStateException("Missing MANAGE_CHANNEL"));
        MusicCommandChannelProvisioning before = claims.find(1);
        var panels = new MusicPanelSqliteRepository(database);
        panels.save(new MusicPanelBinding(1, 10, 100));
        panels.save(new MusicPanelBinding(1, 10, 200));
        assertEquals(before, claims.find(1));
        assertNull(provisioning.tryStart(1));
        assertEquals(200, panels.findAll().getFirst().messageId());
    }
}
