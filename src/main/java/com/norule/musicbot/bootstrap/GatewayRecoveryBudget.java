package com.norule.musicbot.bootstrap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** A durable, manually reset budget, including across Pterodactyl container restarts. */
final class GatewayRecoveryBudget {
    private final Path stateFile;
    private final int maximumRestarts;

    GatewayRecoveryBudget(Path stateFile, int maximumRestarts) {
        this.stateFile = stateFile;
        this.maximumRestarts = maximumRestarts;
    }

    boolean reserve() throws IOException {
        if (maximumRestarts <= 0) {
            return false;
        }
        Files.createDirectories(stateFile.toAbsolutePath().getParent());
        boolean created;
        try {
            Files.createFile(stateFile);
            created = true;
        } catch (FileAlreadyExistsException existing) {
            created = false;
        }
        try (FileChannel channel = FileChannel.open(stateFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) {
                throw new IOException("Gateway recovery budget is locked by another process");
            }
            if (channel.size() > 16) {
                throw new IOException("Invalid gateway recovery budget; manual inspection required");
            }
            ByteBuffer content = ByteBuffer.allocate(16);
            while (channel.read(content) > 0) {
                // The file has a fixed, small upper bound; read until EOF.
            }
            String stored = new String(content.array(), 0, content.position(), StandardCharsets.US_ASCII).trim();
            int used = created && stored.isEmpty() ? 0 : parseCount(stored);
            if (used >= maximumRestarts) {
                return false;
            }
            byte[] next = (used + 1 + "\n").getBytes(StandardCharsets.US_ASCII);
            channel.position(0);
            ByteBuffer update = ByteBuffer.wrap(next);
            while (update.hasRemaining()) {
                channel.write(update);
            }
            channel.truncate(next.length);
            channel.force(true);
            return true;
        } catch (OverlappingFileLockException locked) {
            throw new IOException("Gateway recovery budget is locked by another process", locked);
        }
    }

    private static int parseCount(String stored) throws IOException {
        if (!stored.matches("[0-9]{1,9}")) {
            throw new IOException("Invalid gateway recovery budget; manual inspection required");
        }
        return Integer.parseInt(stored);
    }
}
