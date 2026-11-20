package de.regelsuche.discovery.domain;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Bounded, immutable storage shared by source-bound assessment and model receipts. */
final class DomainReceiptStore {
    public static final int MAX_RECEIPT_BYTES = 1_048_576;
    public static final int MAX_RECEIPTS_PER_EXPORT = 16;
    private final Path sourceDirectory;
    private final Path directory;

    interface Check { void verify(DomainExportWorkspace source, String hash, byte[] bytes); }
    private final Check check;

    DomainReceiptStore(Path sourceDirectory, String suffix, Check check) {
        this.check = Objects.requireNonNull(check);
        Path source = Objects.requireNonNull(sourceDirectory, "sourceDirectory").toAbsolutePath().normalize();
        if (source.getFileName() == null) throw new IllegalArgumentException("a named source repository is required");
        this.sourceDirectory = source;
        directory = source.resolveSibling(source.getFileName() + suffix);
    }

    /** Called only with checker-issued canonical receipts. */
    void retain(DomainExportWorkspace workspace, String hash, String canonicalJson) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(canonicalJson, "canonicalJson");
        byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECEIPT_BYTES) throw new IllegalArgumentException("downstream receipt exceeds 1 MiB");
        var value = DomainExportWorkspace.read(bytes);
        if (!DomainDownstreamValidation.sourceBinding(workspace).equals(value.path("source"))) {
            throw new IllegalArgumentException("downstream receipt belongs to another source");
        }
        Path target = target(workspace.runId(), hash);
        synchronized (DomainReceiptStore.class) {
            try {
                var retained = new DomainExportWorkspaceRepository(sourceDirectory).find(workspace.runId()).orElseThrow(() ->
                    new IllegalStateException("downstream source export is not retained"));
                if (!workspace.toCanonicalJson().equals(retained.toCanonicalJson())) {
                    throw new IllegalArgumentException("downstream source workspace differs from retained bytes");
                }
                createDirectories(target.getParent());
                Path locks = directory.resolve(".locks");
                createDirectories(locks);
                Path lockPath = locks.resolve(workspace.runId().substring(7) + ".lock");
                rejectLinks(lockPath);
                // The JVM monitor prevents overlapping FileLocks in this process; the file lock covers other writers.
                try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
                    if (!lock.isValid() || channel.size() != 0) throw new IllegalStateException("invalid downstream writer lock");
                    retainBytes(target, bytes);
                }
            } catch (IOException | IllegalArgumentException | DomainDiscoveryExportVerifier.ExportVerificationException exception) {
                throw new IllegalStateException("unable to retain downstream receipt", exception);
            }
        }
    }

    /** Successful historical proofs are reproduced before returning their exact original bytes. */
    public Optional<byte[]> find(String runId, String receiptHash) {
        // Reject caller-controlled path material before entering the retained-artifact error boundary.
        Path target = target(runId, receiptHash);
        try {
            checkDirectories(target.getParent());
            var workspace = new DomainExportWorkspaceRepository(sourceDirectory).find(runId);
            if (workspace.isEmpty() || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            byte[] bytes = readBytes(target);
            check.verify(workspace.orElseThrow(), receiptHash, bytes);
            return Optional.of(bytes);
        } catch (IOException | IllegalArgumentException | DomainDiscoveryExportVerifier.ExportVerificationException exception) {
            throw new IllegalStateException("invalid retained downstream receipt", exception);
        }
    }

    private Path target(String runId, String receiptHash) {
        DomainCanonical.requireSha256(runId, "runId");
        DomainCanonical.requireSha256(receiptHash, "receiptHash");
        Path parent = directory.resolve(runId.substring(7));
        return parent.resolve(receiptHash.substring(7) + ".json");
    }

    private static void retainBytes(Path target, byte[] bytes) throws IOException {
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

    private static void requireCapacity(Path parent) throws IOException {
        try (var stream = Files.list(parent)) {
            var entries = stream.filter(path -> !path.getFileName().toString().startsWith(".pending-"))
                .limit(MAX_RECEIPTS_PER_EXPORT + 1L).toList();
            for (Path path : entries) {
                if (!path.getFileName().toString().matches("[0-9a-f]{64}\\.json")
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                    throw new IllegalStateException("invalid downstream repository entry");
                }
            }
            if (entries.size() >= MAX_RECEIPTS_PER_EXPORT) {
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
