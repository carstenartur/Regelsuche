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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;

/** Private POSIX VALIDATION ledger, never reset by changing a candidate or budget. */
public final class FileEvolutionRewriteProgramValidationAttemptStore {
    private final Path root;

    public FileEvolutionRewriteProgramValidationAttemptStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Reservation reserve(EvolutionRewriteProgramValidationPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        requireNoSymlinks(root);
        if (!root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            throw new IOException("private VALIDATION custody requires a POSIX filesystem");
        }
        Files.createDirectories(root, PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));
        writeCreateNew(reservationPath(plan), plan.toCanonicalJson());
        return new Reservation(plan);
    }

    public void writeSelection(EvolutionRewriteProgramValidationSelection selection) throws IOException {
        Objects.requireNonNull(selection, "selection");
        requireReservation(selection.plan());
        writeCreateNew(selectionPath(selection.plan()), selection.toCanonicalJson());
    }

    /** Reads both private retained records against the externally expected complete plan. */
    public EvolutionRewriteProgramValidationSelection readSelection(EvolutionRewriteProgramValidationPlan expectedPlan)
        throws IOException {
        requireReservation(expectedPlan);
        return EvolutionRewriteProgramValidationSelection.fromCanonicalJson(
            readPrivate(selectionPath(expectedPlan)), expectedPlan);
    }

    private void requireReservation(EvolutionRewriteProgramValidationPlan expectedPlan) throws IOException {
        Objects.requireNonNull(expectedPlan, "expectedPlan");
        requirePrivateDirectory();
        var persisted = EvolutionRewriteProgramValidationPlan.fromCanonicalJson(
            readPrivate(reservationPath(expectedPlan)));
        if (!persisted.equals(expectedPlan)) {
            throw new IOException("durable VALIDATION reservation differs from externally expected complete plan");
        }
    }

    public Path reservationPath(EvolutionRewriteProgramValidationPlan plan) {
        return root.resolve(token(plan) + ".validation-reservation.json");
    }

    public Path selectionPath(EvolutionRewriteProgramValidationPlan plan) {
        return root.resolve(token(plan) + ".validation-selection.json");
    }

    private static String token(EvolutionRewriteProgramValidationPlan plan) {
        return plan.runIdentity().substring("sha256:".length());
    }

    private void requirePrivateDirectory() throws IOException {
        requireNoSymlinks(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.getFileStore(root).supportsFileAttributeView("posix")) {
            throw new IOException("private VALIDATION custody requires a POSIX directory");
        }
        var permissions = Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS);
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException("VALIDATION directory must not be writable by other users");
        }
    }

    private void writeCreateNew(Path path, String text) throws IOException {
        requirePrivateDirectory();
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
        try (FileChannel channel = FileChannel.open(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            while (bytes.hasRemaining()) { channel.write(bytes); }
            channel.force(true);
        }
        forceDirectoryChain();
    }

    private void forceDirectoryChain() throws IOException {
        // Include new directory links, not only the reservation entry in the leaf ledger directory.
        // A failed force leaves the attempt consumed, but issues no reveal receipt or successful write.
        for (Path directory = root; directory != null; directory = directory.getParent()) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            } catch (UnsupportedOperationException | IllegalArgumentException exception) {
                throw new IOException("VALIDATION ledger requires no-follow directory force support", exception);
            }
        }
    }

    private static String readPrivate(Path path) throws IOException {
        requireNoSymlinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getFileStore(path).supportsFileAttributeView("posix")
                || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                    .equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IOException("VALIDATION reservation requires a regular owner-only POSIX file");
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return new String(Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void requireNoSymlinks(Path path) throws IOException {
        Path current = path.toAbsolutePath().getRoot();
        for (Path component : path.toAbsolutePath()) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("VALIDATION custody cannot use symbolic links");
            }
        }
    }

    /** A receipt is issued only after private CREATE_NEW, file force and directory force succeed. */
    public static final class Reservation {
        private final EvolutionRewriteProgramValidationPlan plan;
        private Reservation(EvolutionRewriteProgramValidationPlan plan) { this.plan = plan; }
        public EvolutionRewriteProgramValidationPlan plan() { return plan; }
    }
}
