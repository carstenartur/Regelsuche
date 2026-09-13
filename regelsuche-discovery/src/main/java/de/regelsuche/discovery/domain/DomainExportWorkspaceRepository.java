package de.regelsuche.discovery.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Finite immutable storage of original export files and their supported view. */
public final class DomainExportWorkspaceRepository {
    public static final String UPLOAD_SCHEMA = "regelsuche.domain-export-upload/v1";
    public static final int MAX_UPLOAD_BYTES = 1_048_576;
    public static final int MAX_EXPORTS = 256;
    private static final Set<String> FILES = Set.of("domain.json", "evidence.json", "lifecycle-handoff.json", "export-manifest.json");
    private final Path directory;

    public DomainExportWorkspaceRepository(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        rejectLinks(this.directory);
    }

    /** File names are fixed; upload contents never select a filesystem path. */
    public DomainExportWorkspace importSnapshot(byte[] request) throws IOException {
        if (request.length > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("domain upload exceeds 1 MiB");
        JsonNode input = DomainExportWorkspace.read(request);
        if (input.size() != 2 || !UPLOAD_SCHEMA.equals(input.path("schema").asText()) || !input.path("files").isObject()) {
            throw new IllegalArgumentException("unsupported domain export upload");
        }
        Set<String> names = new HashSet<>(); input.path("files").fieldNames().forEachRemaining(names::add);
        if (!FILES.equals(names)) throw new IllegalArgumentException("upload must contain the four original export files");
        Map<String, byte[]> bytes = new TreeMap<>();
        long total = 0;
        for (String name : FILES) {
            JsonNode encoded = input.path("files").path(name);
            if (!encoded.isTextual()) throw new IllegalArgumentException("export files must be base64 byte strings");
            byte[] decoded = Base64.getDecoder().decode(encoded.asText());
            total += decoded.length;
            if (total > DomainExportWorkspace.MAX_EXPORT_BYTES) throw new IllegalArgumentException("export files exceed 512 KiB");
            bytes.put(name, decoded);
        }
        Path staging = Files.createTempDirectory("regelsuche-domain-export-");
        try {
            for (var item : bytes.entrySet()) Files.write(staging.resolve(item.getKey()), item.getValue(), StandardOpenOption.CREATE_NEW);
            return retain(verifier().requireVerified(staging));
        } finally { deleteTree(staging); }
    }

    public DomainExportWorkspace retain(DomainDiscoveryExportVerifier.VerifiedDomainExport snapshot) throws IOException {
        DomainExportWorkspace workspace = DomainExportWorkspace.fromVerified(snapshot);
        synchronized (DomainExportWorkspaceRepository.class) {
            rejectLinks(directory); Files.createDirectories(directory); rejectLinks(directory);
            var existing = find(workspace.runId());
            if (existing.isPresent()) return requireSame(existing.get(), workspace);
            if (entries().size() >= MAX_EXPORTS) throw new IllegalStateException("domain export repository is full");
            Path staging = Files.createTempDirectory(directory, ".pending-");
            try {
                Path export = Files.createDirectory(staging.resolve("export"));
                for (var role : DomainDiscoveryExport.ArtifactRole.values()) {
                    Files.write(export.resolve(role.fileName()), workspace.originalArtifactBytes(role), StandardOpenOption.CREATE_NEW);
                }
                Files.write(export.resolve(DomainDiscoveryExport.MANIFEST_FILE_NAME), workspace.originalManifestBytes(), StandardOpenOption.CREATE_NEW);
                Files.writeString(staging.resolve("workspace.json"), workspace.toCanonicalJson(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                try { Files.move(staging, target(workspace.runId()), StandardCopyOption.ATOMIC_MOVE); }
                catch (FileAlreadyExistsException | DirectoryNotEmptyException exception) {
                    return requireSame(find(workspace.runId()).orElseThrow(), workspace);
                }
                return workspace;
            } finally { if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) deleteTree(staging); }
        }
    }

    public Optional<DomainExportWorkspace> find(String runId) throws IOException {
        Path target = target(runId);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        rejectLinks(target);
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("invalid retained export directory");
        try (var contents = Files.list(target)) {
            if (!contents.limit(3).map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(Set.of("export", "workspace.json"))) {
                throw new IllegalArgumentException("retained domain workspace membership changed");
            }
        }
        var workspace = DomainExportWorkspace.fromVerified(verifier().requireVerified(target.resolve("export")));
        if (!runId.equals(workspace.runId())) throw new IllegalArgumentException("retained domain export belongs to another source identity");
        Path view = target.resolve("workspace.json");
        rejectLinks(view);
        if (!Files.isRegularFile(view, LinkOption.NOFOLLOW_LINKS) || Files.size(view) > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("invalid retained domain workspace file");
        }
        try (var input = Files.newInputStream(view, LinkOption.NOFOLLOW_LINKS)) {
            byte[] retained = input.readNBytes(MAX_UPLOAD_BYTES + 1);
            if (!Arrays.equals(retained, workspace.toCanonicalJson().getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("retained domain workspace differs from its original export");
            }
        }
        return Optional.of(workspace);
    }

    public Page list(int offset, int limit) throws IOException {
        if (offset < 0 || limit < 1 || limit > 25) throw new IllegalArgumentException("offset must be nonnegative and limit between 1 and 25");
        List<Path> entries = entries();
        List<DomainExportWorkspace> workspaces = new ArrayList<>();
        for (Path entry : entries.stream().skip(offset).limit(limit).toList()) {
            workspaces.add(find("sha256:" + entry.getFileName()).orElseThrow());
        }
        return new Page(offset, limit, entries.size(), workspaces);
    }

    public record Page(int offset, int limit, int total, List<DomainExportWorkspace> exports) {
        public Page { exports = List.copyOf(exports); }
    }

    private List<Path> entries() throws IOException {
        rejectLinks(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (var stream = Files.list(directory)) {
            List<Path> paths = stream.filter(path -> !path.getFileName().toString().startsWith(".pending-"))
                .limit(MAX_EXPORTS + 1L).toList();
            if (paths.size() > MAX_EXPORTS) throw new IllegalStateException("domain export repository limit exceeded");
            List<Path> retained = new ArrayList<>();
            for (Path path : paths) {
                if (!path.getFileName().toString().matches("[0-9a-f]{64}") || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("invalid domain export repository entry");
                }
                retained.add(path);
            }
            return retained.stream().sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }

    private Path target(String runId) {
        DomainCanonical.requireSha256(runId, "runId"); rejectLinks(directory);
        return directory.resolve(runId.substring(7));
    }

    private static DomainExportWorkspace requireSame(DomainExportWorkspace retained, DomainExportWorkspace supplied) {
        if (!retained.toCanonicalJson().equals(supplied.toCanonicalJson())) {
            throw new SourceIdentityConflictException("the export identity already binds different original bytes");
        }
        return retained;
    }

    public static final class SourceIdentityConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        public SourceIdentityConflictException(String message) { super(message); }
    }

    private static DomainDiscoveryExportVerifier verifier() {
        return new DomainDiscoveryExportVerifier(DomainExportWorkspace.MAX_EXPORT_BYTES, DomainExportWorkspace.MAX_EXPORT_BYTES);
    }

    private static void rejectLinks(Path path) {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("symbolic domain export repository path");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
