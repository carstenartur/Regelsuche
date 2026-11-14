package de.regelsuche.quality.coverage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Joins the isolated SymPy JaCoCo input without replacing existing measurements. */
final class IsolatedCoverageInput {
    private static final Path CANONICAL =
        Path.of("regelsuche-math-sympy/build/reports/jacoco/test/jacocoTestReport.xml");

    private IsolatedCoverageInput() {}

    static String install(Path root, Path input) throws IOException {
        Path source = root.resolve(input).normalize();
        if (!source.startsWith(root) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || !source.toRealPath().startsWith(root)) {
            throw new IOException("isolated SymPy report must be a regular file inside the repository: " + source);
        }
        Path target = root.resolve(CANONICAL);
        Path parent = target.getParent();
        // Resolve existing ancestors before creating any directories through them.
        Path ancestor = parent;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (!ancestor.toRealPath().startsWith(root)) throw new IOException("canonical report escapes repository root");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("canonical SymPy report already exists: " + target);
        }
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, ".isolated-coverage-", ".xml");
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            if (Files.mismatch(source, staged) != -1) throw new IOException("isolated SymPy report changed during copy");
            String checksum = sha256(staged);
            // A hard link publishes fully verified bytes atomically and fails if anyone won the race.
            Files.createLink(target, staged);
            return checksum;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
