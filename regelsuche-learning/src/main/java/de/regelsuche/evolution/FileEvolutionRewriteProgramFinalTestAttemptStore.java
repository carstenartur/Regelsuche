package de.regelsuche.evolution;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;

/** Private POSIX ledger. Reuse the authoritative directory across adapters, callers and restarts. */
public final class FileEvolutionRewriteProgramFinalTestAttemptStore {
    private final Path root;

    public FileEvolutionRewriteProgramFinalTestAttemptStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    /** Consume the attempt without granting reveal or result-writing authority. */
    public Reservation reserve(EvolutionRewriteProgramFinalTestPlan plan) throws IOException {
        return reserve(plan, false);
    }

    /** Verify the durable private predecessor before issuing an execution-capable reservation. */
    public Reservation reserve(EvolutionRewriteProgramFinalTestPlan plan,
        FileEvolutionRewriteProgramValidationAttemptStore validationStore) throws IOException {
        var selected = Objects.requireNonNull(plan, "plan").validationHandoff().selection();
        var persisted = Objects.requireNonNull(validationStore, "validationStore").readSelection(selected.plan());
        if (!persisted.equals(selected)) {
            throw new IOException("FINAL TEST handoff differs from the actual durable VALIDATION selection");
        }
        return reserve(plan, true);
    }

    private Reservation reserve(EvolutionRewriteProgramFinalTestPlan plan, boolean validationVerified) throws IOException {
        var record = EvolutionRewriteProgramFinalTestReservation.create(Objects.requireNonNull(plan, "plan"));
        requireNoSymlinks(root);
        Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        requirePrivateDirectory();
        writeCreateNew(reservationPath(plan), record.toCanonicalJson());
        return new Reservation(this, record, validationVerified);
    }

    public void writeEvaluation(Reservation receipt, EvolutionRewriteProgramFinalTestEvaluation evaluation) throws IOException {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(evaluation, "evaluation");
        if (receipt.owner != this || !receipt.validationVerified || !receipt.record.plan().equals(evaluation.plan())) {
            throw new IOException("FINAL TEST result requires the winning reservation and unchanged full selected identity");
        }
        requireReservation(evaluation.plan());
        writeCreateNew(evaluationPath(evaluation.plan()), evaluation.toCanonicalJson());
    }

    public EvolutionRewriteProgramFinalTestEvaluation readEvaluation(EvolutionRewriteProgramFinalTestPlan expectedPlan)
        throws IOException {
        requireReservation(expectedPlan);
        return EvolutionRewriteProgramFinalTestEvaluation.fromCanonicalJson(
            readPrivate(evaluationPath(expectedPlan)), expectedPlan);
    }

    public Path reservationPath(EvolutionRewriteProgramFinalTestPlan plan) {
        return root.resolve(token(plan) + ".reservation.json");
    }

    public Path evaluationPath(EvolutionRewriteProgramFinalTestPlan plan) {
        return root.resolve(token(plan) + ".evaluation.json");
    }

    private static String token(EvolutionRewriteProgramFinalTestPlan plan) {
        return Objects.requireNonNull(plan, "plan").runIdentity().substring("sha256:".length());
    }

    private void requireReservation(EvolutionRewriteProgramFinalTestPlan expectedPlan) throws IOException {
        requirePrivateDirectory();
        var reserved = EvolutionRewriteProgramFinalTestReservation.fromCanonicalJson(readPrivate(reservationPath(expectedPlan)));
        if (!reserved.plan().equals(expectedPlan)) {
            throw new IOException("durable FINAL TEST reservation differs from externally expected full selected identity");
        }
    }

    private void requirePrivateDirectory() throws IOException {
        requireNoSymlinks(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.getFileStore(root).supportsFileAttributeView("posix")) {
            throw new IOException("private FINAL TEST custody requires a POSIX directory");
        }
        var permissions = Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS);
        if (permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                || permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException("FINAL TEST directory must not be writable by other users");
        }
    }

    private void writeCreateNew(Path path, String text) throws IOException {
        requirePrivateDirectory();
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
        try (var channel = FileChannel.open(path, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            while (bytes.hasRemaining()) { channel.write(bytes); }
            channel.force(true);
        }
        // Persist every potentially new directory link, including the ledger root's parent entry.
        // No reveal capability or successful result is returned if any required force fails.
        for (Path directory = root; directory != null; directory = directory.getParent()) {
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (UnsupportedOperationException | IllegalArgumentException exception) {
                throw new IOException("FINAL TEST custody requires no-follow directory force", exception);
            }
        }
    }

    static String readPrivate(Path path) throws IOException {
        requireNoSymlinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getFileStore(path).supportsFileAttributeView("posix")
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                    .equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IOException("held-out evidence requires a regular owner-only POSIX file");
        }
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return new String(Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void requireNoSymlinks(Path path) throws IOException {
        Path current = path.toAbsolutePath().getRoot();
        for (Path component : path.toAbsolutePath()) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) { throw new IOException("held-out custody cannot use symbolic links"); }
        }
    }

    /** Private receipt; reveal requires verified durable VALIDATION in addition to exclusive forced creation. */
    public static final class Reservation {
        private final FileEvolutionRewriteProgramFinalTestAttemptStore owner;
        private final EvolutionRewriteProgramFinalTestReservation record;
        private final boolean validationVerified;
        private Reservation(FileEvolutionRewriteProgramFinalTestAttemptStore owner,
            EvolutionRewriteProgramFinalTestReservation record, boolean validationVerified) {
            this.owner = owner;
            this.record = record;
            this.validationVerified = validationVerified;
        }
        void requireRevealAuthority() {
            if (!validationVerified) {
                throw new IllegalArgumentException("FINAL TEST reveal requires a verified durable VALIDATION predecessor");
            }
        }
        public EvolutionRewriteProgramFinalTestReservation record() { return record; }
        public EvolutionRewriteProgramFinalTestPlan plan() { return record.plan(); }
    }
}
