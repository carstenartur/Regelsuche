package de.regelsuche.proof;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@link ProofArtifactRepository} that stores every artifact as a flat file
 * under a configurable {@code proofs/} root directory.
 *
 * <p>Artifact ids are used as file names verbatim, so callers are responsible
 * for choosing safe ids (typically {@code <jobId><.suffix>}). The repository
 * refuses ids containing path separators to avoid directory traversal.</p>
 */
public final class JsonFileProofArtifactRepository implements ProofArtifactRepository {

    private final Path rootDirectory;

    public JsonFileProofArtifactRepository(Path rootDirectory) throws IOException {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory");
        Files.createDirectories(rootDirectory);
    }

    /** @return the root directory where artifacts are stored. */
    public Path rootDirectory() {
        return rootDirectory;
    }

    @Override
    public Path store(String artifactId, String body) throws IOException {
        validateId(artifactId);
        Path target = rootDirectory.resolve(artifactId);
        Files.writeString(target, body == null ? "" : body, StandardCharsets.UTF_8);
        return target.toAbsolutePath();
    }

    @Override
    public Optional<String> read(String artifactId) throws IOException {
        validateId(artifactId);
        Path target = rootDirectory.resolve(artifactId);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
    }

    @Override
    public Optional<Path> pathOf(String artifactId) {
        try {
            validateId(artifactId);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        Path target = rootDirectory.resolve(artifactId);
        return Files.isRegularFile(target) ? Optional.of(target.toAbsolutePath()) : Optional.empty();
    }

    @Override
    public List<String> listArtifactIds() {
        if (!Files.isDirectory(rootDirectory)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (Stream<Path> stream = Files.list(rootDirectory)) {
            stream.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .sorted()
                .forEach(ids::add);
        } catch (IOException ex) {
            return List.of();
        }
        return ids;
    }

    @Override
    public void delete(String artifactId) throws IOException {
        validateId(artifactId);
        Files.deleteIfExists(rootDirectory.resolve(artifactId));
    }

    // ── Per-job bundle: <root>/<jobId>/<name> ──────────────────────────────

    @Override
    public Path storeJobArtifact(String jobId, String name, String body) throws IOException {
        validateId(jobId);
        validateId(name);
        Path jobDir = rootDirectory.resolve(jobId);
        Files.createDirectories(jobDir);
        Path target = jobDir.resolve(name);
        Files.writeString(target, body == null ? "" : body, StandardCharsets.UTF_8);
        return target.toAbsolutePath();
    }

    @Override
    public Optional<String> readJobArtifact(String jobId, String name) throws IOException {
        validateId(jobId);
        validateId(name);
        Path target = rootDirectory.resolve(jobId).resolve(name);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
    }

    @Override
    public Optional<Path> jobArtifactPath(String jobId, String name) {
        try {
            validateId(jobId);
            validateId(name);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        Path target = rootDirectory.resolve(jobId).resolve(name);
        return Files.isRegularFile(target) ? Optional.of(target.toAbsolutePath()) : Optional.empty();
    }

    @Override
    public List<String> listJobArtifacts(String jobId) {
        try {
            validateId(jobId);
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
        Path jobDir = rootDirectory.resolve(jobId);
        if (!Files.isDirectory(jobDir)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(jobDir)) {
            stream.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .sorted()
                .forEach(names::add);
        } catch (IOException ex) {
            return List.of();
        }
        return names;
    }

    private static void validateId(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
        if (artifactId.contains("/") || artifactId.contains("\\")
            || artifactId.contains("..")) {
            throw new IllegalArgumentException("artifactId must not contain path separators");
        }
    }
}
