package com.norule.musicbot.shorturl.infra;

import com.norule.musicbot.domain.shorturl.ImageShare;
import com.norule.musicbot.shorturl.ImageShareStorage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class FileSystemImageShareStorage implements ImageShareStorage {
    private final Path storageDirectory;
    private final Path temporaryDirectory;
    private final Path archiveDirectory;
    private final List<Path> legacyStorageDirectories;

    public FileSystemImageShareStorage(Path storageDirectory) {
        this(storageDirectory,
                storageDirectory == null ? null : storageDirectory.resolveSibling("tmp/uploads"),
                storageDirectory == null ? null : storageDirectory.resolveSibling("short-url-expired"),
                List.of());
    }

    public FileSystemImageShareStorage(Path storageDirectory, Path temporaryDirectory,
                                       Path archiveDirectory) {
        this(storageDirectory, temporaryDirectory, archiveDirectory, List.of());
    }

    public FileSystemImageShareStorage(Path storageDirectory, Path temporaryDirectory,
                                       Path archiveDirectory, List<Path> legacyStorageDirectories) {
        if (storageDirectory == null) {
            throw new IllegalArgumentException("storageDirectory cannot be null");
        }
        this.storageDirectory = storageDirectory.toAbsolutePath().normalize();
        this.temporaryDirectory = (temporaryDirectory == null
                ? this.storageDirectory.resolveSibling("tmp/uploads") : temporaryDirectory)
                .toAbsolutePath().normalize();
        this.archiveDirectory = (archiveDirectory == null
                ? this.storageDirectory.resolveSibling("short-url-expired") : archiveDirectory)
                .toAbsolutePath().normalize();
        this.legacyStorageDirectories = normalizeLegacyDirectories(legacyStorageDirectories);
    }

    @Override
    public void save(ImageShare imageShare, byte[] content) throws IOException {
        Files.createDirectories(storageDirectory);
        Files.createDirectories(temporaryDirectory);
        Path target = resolve(imageShare);
        Path temporary = Files.createTempFile(temporaryDirectory, ".media-", ".tmp");
        try {
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            } catch (FileAlreadyExistsException existing) {
                verifyExistingBlob(target, imageShare, content.length, existing);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public InputStream open(ImageShare imageShare) throws IOException {
        return Files.newInputStream(resolve(imageShare), StandardOpenOption.READ);
    }

    @Override
    public boolean exists(ImageShare imageShare) {
        return Files.isRegularFile(resolve(imageShare));
    }

    @Override
    public void delete(ImageShare imageShare) throws IOException {
        Files.deleteIfExists(resolve(imageShare));
    }

    @Override
    public String archive(ImageShare imageShare) throws IOException {
        ArchiveResult result = archiveOrReconcile(imageShare);
        if (result.status() == ArchiveStatus.MISSING) {
            throw new NoSuchFileException(resolve(imageShare).toString());
        }
        return result.archiveStorageName();
    }

    @Override
    public ArchiveResult archiveOrReconcile(ImageShare imageShare) throws IOException {
        String canonicalName = validateStorageName(imageShare == null ? null : imageShare.storageName());
        Path configuredArchive = resolveArchive(imageShare);
        if (isRegularFileOrMissing(configuredArchive)) {
            return new ArchiveResult(ArchiveStatus.ALREADY_ARCHIVED,
                    configuredArchive.getFileName().toString());
        }

        Path canonicalArchive = resolveArchive(canonicalName);
        if (!canonicalArchive.equals(configuredArchive) && isRegularFileOrMissing(canonicalArchive)) {
            return new ArchiveResult(ArchiveStatus.ALREADY_ARCHIVED, canonicalName);
        }

        Path source = resolve(imageShare);
        ArchiveStatus status = ArchiveStatus.ARCHIVED;
        if (!isRegularFileOrMissing(source)) {
            source = findLegacySource(canonicalName);
            status = ArchiveStatus.LEGACY_MIGRATED;
        }
        if (source == null) {
            return new ArchiveResult(ArchiveStatus.MISSING, "");
        }

        moveToArchive(source, canonicalArchive);
        return new ArchiveResult(status, canonicalName);
    }

    private void moveToArchive(Path source, Path target) throws IOException {
        Files.createDirectories(archiveDirectory);
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            copyVerifyAndDelete(source, target);
        } catch (FileAlreadyExistsException exception) {
            if (!isRegularFileOrMissing(target)) {
                throw exception;
            }
        } catch (java.nio.file.FileSystemException exception) {
            if (isRegularFileOrMissing(target)) {
                return;
            }
            copyVerifyAndDelete(source, target);
        }
    }

    @Override
    public boolean existsArchived(ImageShare imageShare) {
        return Files.isRegularFile(resolveArchive(imageShare));
    }

    @Override
    public InputStream openArchived(ImageShare imageShare) throws IOException {
        return Files.newInputStream(resolveArchive(imageShare), StandardOpenOption.READ);
    }

    @Override
    public InputStream openLegacy(ImageShare imageShare) throws IOException {
        String storageName = validateStorageName(imageShare == null ? null : imageShare.storageName());
        Path legacy = findLegacySource(storageName);
        return legacy == null ? null : Files.newInputStream(legacy, StandardOpenOption.READ);
    }

    @Override
    public void deleteLegacy(ImageShare imageShare) throws IOException {
        String storageName = validateStorageName(imageShare == null ? null : imageShare.storageName());
        Path legacy = findLegacySource(storageName);
        if (legacy != null) {
            Files.deleteIfExists(legacy);
        }
    }

    @Override
    public void deleteArchived(ImageShare imageShare) throws IOException {
        Files.deleteIfExists(resolveArchive(imageShare));
    }

    @Override
    public int filesystemUsagePercent() {
        try {
            Files.createDirectories(storageDirectory);
            var store = Files.getFileStore(storageDirectory);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            if (total <= 0L) {
                return 0;
            }
            return (int) Math.max(0L, Math.min(100L, ((total - usable) * 100L) / total));
        } catch (IOException ignored) {
            return 100;
        }
    }

    public Path activeDirectory() {
        return storageDirectory;
    }

    public Path temporaryDirectory() {
        return temporaryDirectory;
    }

    public Path archiveDirectory() {
        return archiveDirectory;
    }

    public List<Path> legacyStorageDirectories() {
        return legacyStorageDirectories;
    }

    private Path resolve(ImageShare imageShare) {
        String storageName = validateStorageName(imageShare == null ? null : imageShare.storageName());
        Path resolved = storageDirectory.resolve(storageName).normalize();
        if (!resolved.startsWith(storageDirectory)) {
            throw new IllegalArgumentException("Image-share storage path escapes configured directory");
        }
        return resolved;
    }

    private Path resolveArchive(ImageShare imageShare) {
        String requestedName = imageShare == null || imageShare.archiveStorageName().isBlank()
                ? imageShare == null ? null : imageShare.storageName()
                : imageShare.archiveStorageName();
        return resolveArchive(validateStorageName(requestedName));
    }

    private Path resolveArchive(String storageName) {
        Path resolved = archiveDirectory.resolve(storageName).normalize();
        if (!resolved.startsWith(archiveDirectory)) {
            throw new IllegalArgumentException("Image-share archive path escapes configured directory");
        }
        return resolved;
    }

    private String validateStorageName(String storageName) {
        if (storageName == null
                || !storageName.matches("[A-Za-z0-9_-]{1,96}\\.(?:png|jpg|gif|webp|mp4|webm)")) {
            throw new IllegalArgumentException("Invalid image-share storage name");
        }
        return storageName;
    }

    private List<Path> normalizeLegacyDirectories(List<Path> directories) {
        if (directories == null || directories.isEmpty()) {
            return List.of();
        }
        Set<Path> normalized = new LinkedHashSet<>();
        for (Path directory : directories) {
            if (directory == null) {
                continue;
            }
            Path candidate = directory.toAbsolutePath().normalize();
            if (!candidate.equals(storageDirectory)
                    && !candidate.equals(temporaryDirectory)
                    && !candidate.equals(archiveDirectory)) {
                normalized.add(candidate);
            }
        }
        return List.copyOf(normalized);
    }

    private Path findLegacySource(String storageName) throws IOException {
        for (Path legacyDirectory : legacyStorageDirectories) {
            Path candidate = legacyDirectory.resolve(storageName).normalize();
            if (!candidate.startsWith(legacyDirectory)) {
                throw new IOException("Legacy image-share path escapes configured directory");
            }
            if (isRegularFileOrMissing(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isRegularFileOrMissing(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new IOException("Media storage path is not a regular file: " + path);
            }
            return true;
        } catch (NoSuchFileException exception) {
            return false;
        }
    }

    private void copyVerifyAndDelete(Path source, Path target) throws IOException {
        Path archiveTemp = archiveDirectory.resolve("." + target.getFileName() + "."
                + UUID.randomUUID().toString().replace("-", "") + ".tmp").normalize();
        if (!archiveTemp.startsWith(archiveDirectory)) {
            throw new IOException("Archive temporary path escapes configured directory");
        }
        try {
            Files.copy(source, archiveTemp, StandardCopyOption.COPY_ATTRIBUTES);
            try (FileChannel channel = FileChannel.open(archiveTemp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.size(source) != Files.size(archiveTemp)
                    || !sha256(source).equals(sha256(archiveTemp))) {
                throw new IOException("Archived media verification failed");
            }
            try {
                Files.move(archiveTemp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(archiveTemp, target);
            }
            Files.delete(source);
        } finally {
            Files.deleteIfExists(archiveTemp);
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public Path createTemporaryUpload() throws IOException {
        Files.createDirectories(temporaryDirectory);
        return Files.createTempFile(temporaryDirectory, ".request-", ".tmp");
    }

    @Override
    public void deleteTemporaryUpload(Path path) throws IOException {
        if (path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(temporaryDirectory)) {
            throw new IOException("Upload temporary path escapes configured directory");
        }
        Files.deleteIfExists(normalized);
    }

    private void verifyExistingBlob(Path target, ImageShare imageShare, long expectedSize,
                                    FileAlreadyExistsException original) throws IOException {
        String expectedHash = imageShare == null ? "" : imageShare.contentHash();
        if (!Files.isRegularFile(target) || Files.size(target) != expectedSize
                || expectedHash.isBlank() || !expectedHash.equalsIgnoreCase(sha256(target))) {
            throw original;
        }
    }
}
