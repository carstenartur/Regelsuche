package de.regelsuche.discovery.representation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;

/** The native artifact uses the existing run-bound dossier location and immutable file convention. */
public final class TargetFreeSearchArtifactStore {
    private TargetFreeSearchArtifactStore() { }

    public static synchronized Optional<String> read(Path runs, RepresentationDiscoveryRunWorkspace run) throws IOException {
        Path target = target(runs, run);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > TargetFreeSearchExecution.MAX_BYTES) {
            throw new IllegalArgumentException("invalid retained native execution file");
        }
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(TargetFreeSearchExecution.fromCanonicalBytes(run, input.readNBytes(TargetFreeSearchExecution.MAX_BYTES + 1)).toCanonicalJson());
        }
    }

    public static synchronized String retain(Path runs, RepresentationDiscoveryRunWorkspace run, byte[] bytes) throws IOException {
        String canonical = TargetFreeSearchExecution.fromCanonicalBytes(run, bytes).toCanonicalJson();
        Path target = target(runs, run), root = target.getParent();
        Files.createDirectories(root); rejectLinks(root);
        var existing = read(runs, run);
        if (existing.isPresent()) {
            if (!existing.get().equals(canonical)) throw new IllegalStateException("immutable native execution conflict");
            return canonical;
        }
        try (var entries = Files.list(root)) {
            if (entries.limit(RepresentationDiscoveryRunWorkspace.DEFAULT_MAX_RETAINED_RUNS).count() >= RepresentationDiscoveryRunWorkspace.DEFAULT_MAX_RETAINED_RUNS) {
                throw new IllegalStateException("retained dossier repository limit exceeded");
            }
        }
        Path temporary = Files.createTempFile(root, ".native-execution-", ".tmp");
        try {
            Files.writeString(temporary, canonical, StandardCharsets.UTF_8);
            try { Files.createLink(target, temporary); }
            catch (FileAlreadyExistsException exception) {
                if (!read(runs, run).orElseThrow().equals(canonical)) throw new IllegalStateException("immutable native execution conflict", exception);
            }
        } finally { Files.deleteIfExists(temporary); }
        return canonical;
    }

    private static Path target(Path runs, RepresentationDiscoveryRunWorkspace run) {
        run.requireArtifact(RepresentationDiscoveryArtifactReference.ArtifactRole.CANDIDATE_DOSSIERS, TargetFreeSearchExecution.SCHEMA);
        Path absolute = runs.toAbsolutePath().normalize();
        Path root = absolute.resolveSibling(absolute.getFileName() + "-dossiers");
        rejectLinks(root);
        return root.resolve(run.runId().substring(7) + ".json");
    }
    private static void rejectLinks(Path absolute) {
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("symbolic native execution repository path");
        }
    }
}
