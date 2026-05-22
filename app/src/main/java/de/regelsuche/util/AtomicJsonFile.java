package de.regelsuche.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Small helper that centralises the "write-tmp-then-atomic-move" idiom used
 * by every persistent JSON store in the codebase
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
     * exist and using an atomic temp-file swap so concurrent readers never
     * see a partially-written file.
     *
     * @param target   destination path
     * @param contents UTF-8 payload to write
     * @throws IOException if the write or the move fails irrecoverably
     */
    public static void writeUtf8(Path target, String contents) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String tmpName = target.getFileName().toString() + ".tmp";
        Path tmp = (parent != null) ? parent.resolve(tmpName) : Path.of(tmpName);
        Files.writeString(tmp, contents, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException atomicNotSupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
