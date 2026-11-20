package de.regelsuche.discovery.domain;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Optional;

/** Bounded, immutable receipt bytes indexed by source and receipt identities. Callers validate their semantic bindings. */
public final class SourceBoundReceiptFiles {
    public static final int MAX_RECEIPT_BYTES = 1_048_576;
    private final Path directory;
    private final int capacity;

    public SourceBoundReceiptFiles(Path directory, int capacity) {
        this.directory = directory.toAbsolutePath().normalize();
        if (capacity < 1 || capacity > 1024) throw new IllegalArgumentException("invalid receipt capacity");
        this.capacity = capacity;
    }

    public void retain(String source, String hash, byte[] bytes) throws IOException {
        if (bytes.length > MAX_RECEIPT_BYTES) throw new IllegalArgumentException("downstream receipt exceeds 1 MiB");
        Path target = target(source, hash);
        synchronized (SourceBoundReceiptFiles.class) {
            createDirectories(target.getParent());
            Path locks = directory.resolve(".locks");
            createDirectories(locks);
            Path lockPath = locks.resolve(source.substring(7) + ".lock");
            rejectLinks(lockPath);
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
                if (!lock.isValid() || channel.size() != 0) throw new IllegalStateException("invalid downstream writer lock");
                retainBytes(target, bytes);
            }
        }
    }

    public Optional<byte[]> find(String source, String hash) throws IOException {
        Path path = target(source, hash);
        checkDirectories(path.getParent());
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(readBytes(path));
    }

    private Path target(String runId, String receiptHash) {
        DomainCanonical.requireSha256(runId, "runId");
        DomainCanonical.requireSha256(receiptHash, "receiptHash");
        Path parent = directory.resolve(runId.substring(7));
        return parent.resolve(receiptHash.substring(7) + ".json");
    }

    private void retainBytes(Path target, byte[] bytes) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireSame(readBytes(target), bytes);
            return;
        }
        requireCapacity(target.getParent());
        Path pending = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
        try {
            Files.write(pending, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.createLink(target, pending); }
            catch (FileAlreadyExistsException exception) { requireSame(readBytes(target), bytes); }
        } finally { Files.deleteIfExists(pending); }
    }

    private static void requireSame(byte[] retained, byte[] supplied) {
        if (!Arrays.equals(retained, supplied)) throw new IllegalStateException("immutable downstream receipt conflict");
    }

    private static void createDirectories(Path path) throws IOException {
        checkDirectories(path);
        Files.createDirectories(path);
        checkDirectories(path);
    }

    private static void checkDirectories(Path path) {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)
                    || (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalArgumentException("invalid downstream repository directory");
            }
        }
    }

    private static byte[] readBytes(Path target) throws IOException {
        rejectLinks(target);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > MAX_RECEIPT_BYTES) {
            throw new IllegalArgumentException("invalid retained downstream file");
        }
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_RECEIPT_BYTES + 1);
            if (bytes.length > MAX_RECEIPT_BYTES) throw new IllegalArgumentException("downstream file exceeds 1 MiB");
            return bytes;
        }
    }

    private void requireCapacity(Path parent) throws IOException {
        try (var stream = Files.list(parent)) {
            var entries = stream.filter(path -> !path.getFileName().toString().startsWith(".pending-"))
                .limit(capacity + 1L).toList();
            for (Path path : entries) {
                if (!path.getFileName().toString().matches("[0-9a-f]{64}\\.json")
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                    throw new IllegalStateException("invalid downstream repository entry");
                }
            }
            if (entries.size() >= capacity) {
                throw new IllegalStateException("downstream receipt limit reached for this export");
            }
        }
    }

    private static void rejectLinks(Path path) {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("symbolic downstream repository path");
        }
    }
}
