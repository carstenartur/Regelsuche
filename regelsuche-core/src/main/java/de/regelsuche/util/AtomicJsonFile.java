package de.regelsuche.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Small helper that centralises the "write-tmp-then-atomic-move" idiom used
 * by persistent JSON stores in the codebase
 * ({@code JsonFileProofCache}, {@code JsonFileProofJobRepository},
 * {@code JsonFileDidacticEventStore}, {@code JsonFileSearchGraphRepository}).
 *
 * <p>Centralising this in one place removes ~10 LoC of duplicated
 * boilerplate per repository and guarantees the same fallback behaviour
 * (best-effort atomic move; plain replace when the filesystem refuses
 * {@link StandardCopyOption#ATOMIC_MOVE}).
 */
public final class AtomicJsonFile {

    private AtomicJsonFile() {
        // utility
    }

    /**
     * Writes {@code contents} to {@code target}, ensuring parent directories
     * exist. Each writer owns a unique temporary file in the destination
     * directory. Replacement is atomic when the filesystem supports it;
     * otherwise this falls back to a plain replacement move.
     *
     * @param target   destination path
     * @param contents UTF-8 payload to write
     * @throws IOException if the write or the move fails irrecoverably
     */
    public static void writeUtf8(Path target, String contents) throws IOException {
        Path destination = target.toAbsolutePath();
        Files.createDirectories(destination.getParent());
        // A regular CREATE_NEW file retains the filesystem's usual permissions
        // and umask. createTempFile defaults to owner-only access on POSIX,
        // which makes container-produced reports unreadable to host consumers.
        Path tmp = Files.createFile(destination.getParent().resolve(".json-write-" + UUID.randomUUID() + ".tmp"));
        try {
            Files.writeString(tmp, contents, StandardCharsets.UTF_8);
            replace(tmp, destination);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException atomicNotSupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
