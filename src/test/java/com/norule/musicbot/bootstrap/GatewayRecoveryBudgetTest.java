package com.norule.musicbot.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayRecoveryBudgetTest {
    @TempDir
    Path directory;

    @Test
    void restartBudgetPersistsAcrossProcessInstancesAndNeverResetsItself() throws IOException {
        Path file = directory.resolve("monitoring/restarts.count");
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertTrue(new GatewayRecoveryBudget(file, 3).reserve());
            assertEquals(attempt + "\n", Files.readString(file));
        }
        assertFalse(new GatewayRecoveryBudget(file, 3).reserve());
        assertFalse(new GatewayRecoveryBudget(file, 2).reserve());
        assertEquals("3\n", Files.readString(file));
    }

    @Test
    void corruptOrEmptyExistingBudgetFailsClosed() throws IOException {
        Path file = directory.resolve("restarts.count");
        for (String corrupted : new String[]{"", "-1", "invalid", "99999999999999999"}) {
            Files.writeString(file, corrupted);
            assertThrows(IOException.class, () -> new GatewayRecoveryBudget(file, 3).reserve());
            assertEquals(corrupted, Files.readString(file));
        }
    }

    @Test
    void disabledBudgetDoesNotCreateStateAndStorageFailureDoesNotGrantRestart() throws IOException {
        Path file = directory.resolve("restarts.count");
        assertFalse(new GatewayRecoveryBudget(file, 0).reserve());
        assertFalse(Files.exists(file));
        Files.writeString(file, "not a directory");
        assertThrows(IOException.class, () -> new GatewayRecoveryBudget(file.resolve("count"), 3).reserve());
    }

    @Test
    void competingProcessCannotConsumeLockedBudget() throws IOException {
        Path file = directory.resolve("restarts.count");
        Files.writeString(file, "1\n");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertTrue(lock.isValid());
            assertThrows(IOException.class, () -> new GatewayRecoveryBudget(file, 3).reserve());
        }
        assertEquals("1\n", Files.readString(file));
    }
}
