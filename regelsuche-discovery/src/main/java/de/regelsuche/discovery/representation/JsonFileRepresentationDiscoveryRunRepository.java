package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded, symlink-safe JSON repository for immutable discovery run workspaces.
 *
 * <p>Each workspace is stored under the hexadecimal part of its content-
 * addressed Run ID. Files are canonical UTF-8 JSON, are committed through a
 * same-directory atomic move where supported, and can never replace different
 * bytes under an existing identity. Unknown directory entries and symbolic
 * path components fail closed so a historical run is never silently loaded
 * from an unrelated location.</p>
 */
public final class JsonFileRepresentationDiscoveryRunRepository
        implements RepresentationDiscoveryRunRepository {
    public static final int DEFAULT_MAX_WORKSPACE_BYTES = 2_000_000;
    public static final int DEFAULT_MAX_RUNS = 10_000;

    private static final Pattern FILE_NAME = Pattern.compile(
        "([0-9a-f]{64})\\.json"
    );

    private final Path directory;
    private final int maxWorkspaceBytes;
    private final int maxRuns;
    private final RepresentationDiscoveryRunWorkspaceCodec codec;

    public JsonFileRepresentationDiscoveryRunRepository(Path directory) {
        this(
            directory,
            DEFAULT_MAX_WORKSPACE_BYTES,
            DEFAULT_MAX_RUNS
        );
    }

    public JsonFileRepresentationDiscoveryRunRepository(
        Path directory,
        int maxWorkspaceBytes,
        int maxRuns
    ) {
        this.directory = Objects.requireNonNull(directory, "directory");
        if (maxWorkspaceBytes < 1) {
            throw new IllegalArgumentException(
                "maxWorkspaceBytes must be positive");
        }
        if (maxRuns < 1) {
            throw new IllegalArgumentException("maxRuns must be positive");
        }
        this.maxWorkspaceBytes = maxWorkspaceBytes;
        this.maxRuns = maxRuns;
        this.codec = new RepresentationDiscoveryRunWorkspaceCodec();
    }

    @Override
    public synchronized RepresentationDiscoveryRunWorkspace save(
        RepresentationDiscoveryRunWorkspace workspace
    ) {
        Objects.requireNonNull(workspace, "workspace");
        byte[] canonical = codec.encodeBytes(workspace);
        requireWithinByteLimit(canonical.length);
        Path root = ensureDirectory();
        Path target = pathFor(root, workspace.runId());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return requireIdentical(target, canonical, workspace.runId());
        }
        if (validatedEntries(root).size() >= maxRuns) {
            throw new IllegalStateException(
                "representation-discovery run repository limit exceeded: "
                    + maxRuns);
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, ".run-workspace-", ".tmp");
            if (Files.isSymbolicLink(temporary)) {
                throw new IllegalStateException(
                    "temporary run-workspace file is symbolic: " + temporary);
            }
            Files.write(
                temporary,
                canonical,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            moveWithoutReplacement(temporary, target);
            temporary = null;
            return workspace;
        } catch (FileAlreadyExistsException exception) {
            return requireIdentical(target, canonical, workspace.runId());
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to retain representation-discovery run "
                    + workspace.runId(),
                exception
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    // A leftover non-canonical entry is rejected by list/find.
                }
            }
        }
    }

    @Override
    public synchronized Optional<RepresentationDiscoveryRunWorkspace> find(
        String runId
    ) {
        String normalized = requireSha256(runId, "runId");
        Path root = ensureDirectory();
        Path target = pathFor(root, normalized);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(load(target, normalized));
    }

    @Override
    public synchronized List<RepresentationDiscoveryRunWorkspace> list() {
        Path root = ensureDirectory();
        return validatedEntries(root).stream()
            .map(path -> load(path, runIdFrom(path)))
            .toList();
    }

    private RepresentationDiscoveryRunWorkspace requireIdentical(
        Path target,
        byte[] expected,
        String runId
    ) {
        RepresentationDiscoveryRunWorkspace retained = load(target, runId);
        byte[] actual = readBounded(target);
        if (!Arrays.equals(actual, expected)) {
            throw new IllegalStateException(
                "immutable run identity already contains different bytes: "
                    + runId);
        }
        return retained;
    }

    private RepresentationDiscoveryRunWorkspace load(
        Path path,
        String expectedRunId
    ) {
        requireRegularFile(path);
        RepresentationDiscoveryRunWorkspace workspace = codec.decode(
            readBounded(path)
        );
        if (!expectedRunId.equals(workspace.runId())) {
            throw new IllegalStateException(
                "run-workspace filename and Run ID differ: " + path);
        }
        return workspace;
    }

    private byte[] readBounded(Path path) {
        requireRegularFile(path);
        try {
            long size = Files.size(path);
            if (size > maxWorkspaceBytes) {
                throw new IllegalStateException(
                    "run workspace exceeds byte limit " + maxWorkspaceBytes
                        + ": " + path);
            }
            byte[] bytes = Files.readAllBytes(path);
            requireWithinByteLimit(bytes.length);
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to read retained run workspace: " + path,
                exception
            );
        }
    }

    private List<Path> validatedEntries(Path root) {
        try (var stream = Files.list(root)) {
            List<Path> entries = stream
                .sorted(Comparator.comparing(path ->
                    path.getFileName().toString()))
                .toList();
            if (entries.size() > maxRuns) {
                throw new IllegalStateException(
                    "representation-discovery run repository limit exceeded: "
                        + entries.size() + " > " + maxRuns);
            }
            for (Path entry : entries) {
                requireRegularFile(entry);
                if (!FILE_NAME.matcher(
                        entry.getFileName().toString()).matches()) {
                    throw new IllegalStateException(
                        "unexpected run repository entry: " + entry);
                }
            }
            return entries;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to list representation-discovery runs",
                exception
            );
        }
    }

    private Path ensureDirectory() {
        Path absolute = directory.toAbsolutePath().normalize();
        rejectSymbolicComponents(absolute);
        try {
            if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(absolute);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to create run repository directory: " + absolute,
                exception
            );
        }
        rejectSymbolicComponents(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                "run repository path is not a real directory: " + absolute);
        }
        return absolute;
    }

    private static void rejectSymbolicComponents(Path absolute) {
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null
                ? component
                : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new IllegalStateException(
                    "run repository contains symbolic path component: "
                        + current);
            }
        }
    }

    private static void requireRegularFile(Path path) {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                "run repository entry is not a real regular file: " + path);
        }
    }

    private void requireWithinByteLimit(long length) {
        if (length > maxWorkspaceBytes) {
            throw new IllegalStateException(
                "run workspace exceeds byte limit " + maxWorkspaceBytes
                    + ": " + length);
        }
    }

    private static Path pathFor(Path root, String runId) {
        String normalized = requireSha256(runId, "runId");
        String digest = normalized.substring("sha256:".length());
        Path path = root.resolve(digest + ".json").normalize();
        if (!root.equals(path.getParent())) {
            throw new IllegalArgumentException(
                "run workspace path escapes repository directory");
        }
        return path;
    }

    private static String runIdFrom(Path path) {
        Matcher matcher = FILE_NAME.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException(
                "invalid run repository filename: " + path);
        }
        return "sha256:" + matcher.group(1);
    }

    private static void moveWithoutReplacement(
        Path source,
        Path target
    ) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }
}
