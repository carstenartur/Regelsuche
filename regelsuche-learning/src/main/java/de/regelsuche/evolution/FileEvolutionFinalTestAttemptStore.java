package de.regelsuche.evolution;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** File-backed CREATE_NEW store; reservations survive restarts and write failures. */
public final class FileEvolutionFinalTestAttemptStore
        implements EvolutionFinalTestAttemptStore {
    private static final String HASH_PREFIX = "sha256:";
    private final Path root;

    public FileEvolutionFinalTestAttemptStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath();
    }

    @Override
    public void reserve(EvolutionFinalTestReservation reservation)
            throws IOException {
        Objects.requireNonNull(reservation, "reservation");
        Files.createDirectories(root);
        try {
            writeCreateNew(
                reservationPath(reservation.runIdentity()),
                reservation.toCanonicalJson());
        } catch (FileAlreadyExistsException exception) {
            throw new EvolutionFinalTestAlreadyReservedException(
                "FINAL TEST attempt is already reserved for "
                    + reservation.runIdentity());
        }
    }

    @Override
    public void writeEvaluation(EvolutionFinalTestEvaluation evaluation)
            throws IOException {
        Objects.requireNonNull(evaluation, "evaluation");
        Path reservationPath = reservationPath(evaluation.runIdentity());
        if (!Files.isRegularFile(reservationPath)) {
            throw new IOException(
                "FINAL TEST result has no durable reservation");
        }
        EvolutionFinalTestReservation persisted =
            EvolutionFinalTestReservation.fromCanonicalJson(
                Files.readString(reservationPath, StandardCharsets.UTF_8));
        if (!evaluation.matchesReservation(persisted)) {
            throw new IOException(
                "FINAL TEST result does not match persisted reservation");
        }
        try {
            writeCreateNew(
                evaluationPath(evaluation.runIdentity()),
                evaluation.toCanonicalJson());
        } catch (FileAlreadyExistsException exception) {
            throw new EvolutionFinalTestAlreadyReservedException(
                "FINAL TEST result already exists for "
                    + evaluation.runIdentity());
        }
    }

    public Path reservationPath(String runIdentity) {
        return root.resolve(token(runIdentity) + ".reservation.json");
    }

    public Path evaluationPath(String runIdentity) {
        return root.resolve(token(runIdentity) + ".evaluation.json");
    }

    private static void writeCreateNew(Path path, String text)
            throws IOException {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        forceDirectoryBestEffort(path.getParent());
    }

    private static void forceDirectoryBestEffort(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(
                directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // CREATE_NEW plus file force remains fail-closed on platforms that
            // do not support opening directories as channels.
        }
    }

    private static String token(String hash) {
        EvolutionGenome.requireSha256(hash, "runIdentity");
        return hash.substring(HASH_PREFIX.length());
    }
}
