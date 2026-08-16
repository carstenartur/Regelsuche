#!/usr/bin/env python3
"""Consolidate #677 persistence into the existing workspace value type."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "regelsuche-discovery/src/main/java/de/regelsuche/discovery/representation"
WORKSPACE = PACKAGE / "RepresentationDiscoveryRunWorkspace.java"
TEST = ROOT / "regelsuche-discovery/src/test/java/de/regelsuche/discovery/representation/JsonFileRepresentationDiscoveryRunRepositoryTest.java"


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"expected one {label} marker, found {count}")
    return source.replace(old, new)


source = WORKSPACE.read_text(encoding="utf-8")
source = replace_once(
    source,
    "import com.fasterxml.jackson.core.JsonProcessingException;\n"
    "import com.fasterxml.jackson.databind.MapperFeature;\n"
    "import com.fasterxml.jackson.databind.SerializationFeature;\n"
    "import com.fasterxml.jackson.databind.json.JsonMapper;\n",
    "import com.fasterxml.jackson.core.JsonProcessingException;\n"
    "import com.fasterxml.jackson.core.StreamReadFeature;\n"
    "import com.fasterxml.jackson.databind.DeserializationFeature;\n"
    "import com.fasterxml.jackson.databind.MapperFeature;\n"
    "import com.fasterxml.jackson.databind.SerializationFeature;\n"
    "import com.fasterxml.jackson.databind.json.JsonMapper;\n",
    "Jackson imports",
)
source = replace_once(
    source,
    "import java.util.Comparator;\n"
    "import java.util.EnumSet;\n"
    "import java.util.HashSet;\n"
    "import java.util.List;\n"
    "import java.util.Objects;\n"
    "import java.util.Set;\n",
    "import java.io.IOException;\n"
    "import java.nio.ByteBuffer;\n"
    "import java.nio.charset.CharacterCodingException;\n"
    "import java.nio.charset.CodingErrorAction;\n"
    "import java.nio.charset.StandardCharsets;\n"
    "import java.nio.file.AtomicMoveNotSupportedException;\n"
    "import java.nio.file.FileAlreadyExistsException;\n"
    "import java.nio.file.Files;\n"
    "import java.nio.file.LinkOption;\n"
    "import java.nio.file.Path;\n"
    "import java.nio.file.StandardCopyOption;\n"
    "import java.nio.file.StandardOpenOption;\n"
    "import java.util.Arrays;\n"
    "import java.util.Comparator;\n"
    "import java.util.EnumSet;\n"
    "import java.util.HashSet;\n"
    "import java.util.List;\n"
    "import java.util.Objects;\n"
    "import java.util.Optional;\n"
    "import java.util.Set;\n"
    "import java.util.regex.Matcher;\n"
    "import java.util.regex.Pattern;\n",
    "JDK imports",
)
source = replace_once(
    source,
    "    private static final JsonMapper JSON = JsonMapper.builder()\n"
    "        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)\n"
    "        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)\n"
    "        .build();\n",
    "    public static final int DEFAULT_MAX_RETAINED_WORKSPACE_BYTES =\n"
    "        2_000_000;\n"
    "    public static final int DEFAULT_MAX_RETAINED_RUNS = 10_000;\n\n"
    "    private static final Pattern RETAINED_FILE_NAME = Pattern.compile(\n"
    "        \"([0-9a-f]{64})\\\\.json\"\n"
    "    );\n"
    "    private static final JsonMapper JSON = JsonMapper.builder()\n"
    "        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)\n"
    "        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)\n"
    "        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)\n"
    "        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)\n"
    "        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)\n"
    "        .build();\n",
    "canonical JSON mapper",
)

persistence = r'''
    /** Decodes only the exact canonical JSON representation. */
    public static RepresentationDiscoveryRunWorkspace fromCanonicalJson(
        String source
    ) {
        Objects.requireNonNull(source, "source");
        try {
            RepresentationDiscoveryRunWorkspace workspace = JSON.readValue(
                source,
                RepresentationDiscoveryRunWorkspace.class
            );
            if (!workspace.toCanonicalJson().equals(source)) {
                throw new IllegalArgumentException(
                    "run workspace JSON is not canonical");
            }
            return workspace;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid representation-discovery run workspace JSON",
                exception
            );
        }
    }

    /** Decodes canonical JSON while rejecting malformed UTF-8. */
    public static RepresentationDiscoveryRunWorkspace fromCanonicalBytes(
        byte[] source
    ) {
        Objects.requireNonNull(source, "source");
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source))
                .toString();
            return fromCanonicalJson(text);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                "run workspace is not valid UTF-8",
                exception
            );
        }
    }

    /** Retains canonical bytes under the immutable Run ID. */
    public static RepresentationDiscoveryRunWorkspace retain(
        Path directory,
        RepresentationDiscoveryRunWorkspace workspace
    ) {
        return retain(
            directory,
            DEFAULT_MAX_RETAINED_WORKSPACE_BYTES,
            DEFAULT_MAX_RETAINED_RUNS,
            workspace
        );
    }

    /** Retains a workspace under explicit finite repository limits. */
    public static synchronized RepresentationDiscoveryRunWorkspace retain(
        Path directory,
        int maxWorkspaceBytes,
        int maxRuns,
        RepresentationDiscoveryRunWorkspace workspace
    ) {
        validateRetainedLimits(maxWorkspaceBytes, maxRuns);
        Objects.requireNonNull(workspace, "workspace");
        byte[] canonical = workspace.toCanonicalJson().getBytes(
            StandardCharsets.UTF_8);
        requireWithinRetainedByteLimit(canonical.length, maxWorkspaceBytes);
        Path root = ensureRetainedDirectory(directory);
        Path target = retainedPath(root, workspace.runId());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return requireIdenticalRetained(
                target,
                canonical,
                workspace.runId(),
                maxWorkspaceBytes
            );
        }
        if (validatedRetainedEntries(root, maxRuns).size() >= maxRuns) {
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
            moveRetainedWithoutReplacement(temporary, target);
            temporary = null;
            return workspace;
        } catch (FileAlreadyExistsException exception) {
            return requireIdenticalRetained(
                target,
                canonical,
                workspace.runId(),
                maxWorkspaceBytes
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to retain representation-discovery run "
                    + workspace.runId(),
                exception
            );
        } finally {
            deleteTemporaryRetainedFile(temporary);
        }
    }

    /** Finds one retained workspace using the default finite limits. */
    public static Optional<RepresentationDiscoveryRunWorkspace> findRetained(
        Path directory,
        String runId
    ) {
        return findRetained(
            directory,
            DEFAULT_MAX_RETAINED_WORKSPACE_BYTES,
            DEFAULT_MAX_RETAINED_RUNS,
            runId
        );
    }

    /** Finds one retained workspace using explicit finite limits. */
    public static synchronized Optional<RepresentationDiscoveryRunWorkspace>
            findRetained(
        Path directory,
        int maxWorkspaceBytes,
        int maxRuns,
        String runId
    ) {
        validateRetainedLimits(maxWorkspaceBytes, maxRuns);
        String normalized = requireSha256(runId, "runId");
        Path root = ensureRetainedDirectory(directory);
        Path target = retainedPath(root, normalized);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(loadRetained(
            target,
            normalized,
            maxWorkspaceBytes
        ));
    }

    /** Lists retained workspaces in deterministic Run-ID order. */
    public static List<RepresentationDiscoveryRunWorkspace> listRetained(
        Path directory
    ) {
        return listRetained(
            directory,
            DEFAULT_MAX_RETAINED_WORKSPACE_BYTES,
            DEFAULT_MAX_RETAINED_RUNS
        );
    }

    /** Lists retained workspaces under explicit finite limits. */
    public static synchronized List<RepresentationDiscoveryRunWorkspace>
            listRetained(
        Path directory,
        int maxWorkspaceBytes,
        int maxRuns
    ) {
        validateRetainedLimits(maxWorkspaceBytes, maxRuns);
        Path root = ensureRetainedDirectory(directory);
        return validatedRetainedEntries(root, maxRuns).stream()
            .map(path -> loadRetained(
                path,
                retainedRunId(path),
                maxWorkspaceBytes
            ))
            .toList();
    }

    private static RepresentationDiscoveryRunWorkspace requireIdenticalRetained(
        Path target,
        byte[] expected,
        String runId,
        int maxWorkspaceBytes
    ) {
        RepresentationDiscoveryRunWorkspace retained = loadRetained(
            target,
            runId,
            maxWorkspaceBytes
        );
        byte[] actual = readRetainedBytes(target, maxWorkspaceBytes);
        if (!Arrays.equals(actual, expected)) {
            throw new IllegalStateException(
                "immutable run identity already contains different bytes: "
                    + runId);
        }
        return retained;
    }

    private static RepresentationDiscoveryRunWorkspace loadRetained(
        Path path,
        String expectedRunId,
        int maxWorkspaceBytes
    ) {
        requireRegularRetainedFile(path);
        RepresentationDiscoveryRunWorkspace workspace = fromCanonicalBytes(
            readRetainedBytes(path, maxWorkspaceBytes)
        );
        if (!expectedRunId.equals(workspace.runId())) {
            throw new IllegalStateException(
                "run-workspace filename and Run ID differ: " + path);
        }
        return workspace;
    }

    private static byte[] readRetainedBytes(
        Path path,
        int maxWorkspaceBytes
    ) {
        requireRegularRetainedFile(path);
        try {
            long size = Files.size(path);
            requireWithinRetainedByteLimit(size, maxWorkspaceBytes);
            byte[] bytes = Files.readAllBytes(path);
            requireWithinRetainedByteLimit(bytes.length, maxWorkspaceBytes);
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to read retained run workspace: " + path,
                exception
            );
        }
    }

    private static List<Path> validatedRetainedEntries(
        Path root,
        int maxRuns
    ) {
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
                requireRegularRetainedFile(entry);
                if (!RETAINED_FILE_NAME.matcher(
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

    private static Path ensureRetainedDirectory(Path directory) {
        Path absolute = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath()
            .normalize();
        rejectSymbolicRetainedComponents(absolute);
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
        rejectSymbolicRetainedComponents(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                "run repository path is not a real directory: " + absolute);
        }
        return absolute;
    }

    private static void rejectSymbolicRetainedComponents(Path absolute) {
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

    private static void requireRegularRetainedFile(Path path) {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                "run repository entry is not a real regular file: " + path);
        }
    }

    private static Path retainedPath(Path root, String runId) {
        String normalized = requireSha256(runId, "runId");
        String digest = normalized.substring("sha256:".length());
        Path path = root.resolve(digest + ".json").normalize();
        if (!root.equals(path.getParent())) {
            throw new IllegalArgumentException(
                "run workspace path escapes repository directory");
        }
        return path;
    }

    private static String retainedRunId(Path path) {
        Matcher matcher = RETAINED_FILE_NAME.matcher(
            path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException(
                "invalid run repository filename: " + path);
        }
        return "sha256:" + matcher.group(1);
    }

    private static void validateRetainedLimits(
        int maxWorkspaceBytes,
        int maxRuns
    ) {
        if (maxWorkspaceBytes < 1) {
            throw new IllegalArgumentException(
                "maxWorkspaceBytes must be positive");
        }
        if (maxRuns < 1) {
            throw new IllegalArgumentException("maxRuns must be positive");
        }
    }

    private static void requireWithinRetainedByteLimit(
        long length,
        int maxWorkspaceBytes
    ) {
        if (length > maxWorkspaceBytes) {
            throw new IllegalStateException(
                "run workspace exceeds byte limit " + maxWorkspaceBytes
                    + ": " + length);
        }
    }

    private static void moveRetainedWithoutReplacement(
        Path source,
        Path target
    ) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteTemporaryRetainedFile(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException exception) {
            // A leftover non-canonical entry is rejected by list/find.
        }
    }

'''
source = replace_once(
    source,
    "    public static List<RepresentationDiscoveryArtifactReference>\n"
    "            notProducedArtifacts() {\n",
    persistence
    + "    public static List<RepresentationDiscoveryArtifactReference>\n"
    "            notProducedArtifacts() {\n",
    "persistence insertion",
)
WORKSPACE.write_text(source, encoding="utf-8")

TEST.write_text(r'''package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole;
import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactStatus;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome.TerminalState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileRepresentationDiscoveryRunRepositoryTest {
    private static final String REPOSITORY_COMMIT =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void codecRoundTripsOnlyCanonicalStrictJson() {
        RepresentationDiscoveryRunWorkspace workspace = workspace(42);
        String canonical = workspace.toCanonicalJson();

        assertEquals(
            workspace,
            RepresentationDiscoveryRunWorkspace.fromCanonicalJson(canonical)
        );
        assertEquals(
            workspace,
            RepresentationDiscoveryRunWorkspace.fromCanonicalBytes(
                canonical.getBytes(StandardCharsets.UTF_8))
        );
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.fromCanonicalJson(
                " " + canonical));
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.fromCanonicalJson(
                canonical + "{}"));
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.fromCanonicalJson(
                "{\"unknown\":true," + canonical.substring(1)));
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.fromCanonicalJson(
                "{\"schema\":\""
                    + RepresentationDiscoveryRunWorkspace.SCHEMA
                    + "\"," + canonical.substring(1)));
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.fromCanonicalBytes(
                new byte[] {(byte) 0xc3, (byte) 0x28}));
    }

    @Test
    void retainFindAndListAreCanonicalImmutableAndDeterministic(
        @TempDir Path directory
    ) throws IOException {
        RepresentationDiscoveryRunWorkspace first = workspace(7);
        RepresentationDiscoveryRunWorkspace second = workspace(11);

        RepresentationDiscoveryRunWorkspace.retain(directory, second);
        RepresentationDiscoveryRunWorkspace.retain(directory, first);
        assertEquals(
            first,
            RepresentationDiscoveryRunWorkspace.retain(directory, first)
        );
        assertEquals(
            first,
            RepresentationDiscoveryRunWorkspace.findRetained(
                directory,
                first.runId()
            ).orElseThrow()
        );
        assertTrue(
            RepresentationDiscoveryRunWorkspace.findRetained(
                directory,
                sha("missing")
            ).isEmpty()
        );

        List<RepresentationDiscoveryRunWorkspace> expected =
            List.of(first, second).stream()
                .sorted(Comparator.comparing(
                    RepresentationDiscoveryRunWorkspace::runId))
                .toList();
        assertEquals(
            expected,
            RepresentationDiscoveryRunWorkspace.listRetained(directory)
        );
        try (var entries = Files.list(directory)) {
            assertEquals(2L, entries.count());
        }
        for (RepresentationDiscoveryRunWorkspace workspace : expected) {
            Path retained = runFile(directory, workspace.runId());
            assertTrue(Files.isRegularFile(retained));
            assertEquals(
                workspace.toCanonicalJson(),
                Files.readString(retained, StandardCharsets.UTF_8)
            );
        }
    }

    @Test
    void nonCanonicalOrMisnamedRetainedFilesFailClosed(
        @TempDir Path directory
    ) throws IOException {
        RepresentationDiscoveryRunWorkspace workspace = workspace(42);
        RepresentationDiscoveryRunWorkspace.retain(directory, workspace);
        Path retained = runFile(directory, workspace.runId());

        Files.writeString(
            retained,
            workspace.toCanonicalJson() + "\n",
            StandardCharsets.UTF_8
        );
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.findRetained(
                directory,
                workspace.runId()
            ));

        Files.writeString(
            retained,
            workspace.toCanonicalJson(),
            StandardCharsets.UTF_8
        );
        Files.copy(retained, runFile(directory, sha("other-run")));
        assertThrows(IllegalStateException.class, () ->
            RepresentationDiscoveryRunWorkspace.listRetained(directory));
    }

    @Test
    void unknownEntriesByteLimitsAndRunLimitsFailClosed(
        @TempDir Path directory
    ) throws IOException {
        RepresentationDiscoveryRunWorkspace first = workspace(1);
        RepresentationDiscoveryRunWorkspace second = workspace(2);
        int bytes = first.toCanonicalJson().getBytes(
            StandardCharsets.UTF_8).length;

        assertThrows(IllegalStateException.class, () ->
            RepresentationDiscoveryRunWorkspace.retain(
                directory.resolve("small"),
                bytes - 1,
                10,
                first
            ));

        Path one = directory.resolve("one");
        RepresentationDiscoveryRunWorkspace.retain(
            one,
            bytes * 2,
            1,
            first
        );
        assertThrows(IllegalStateException.class, () ->
            RepresentationDiscoveryRunWorkspace.retain(
                one,
                bytes * 2,
                1,
                second
            ));

        Path unexpected = directory.resolve("unexpected");
        assertTrue(
            RepresentationDiscoveryRunWorkspace.listRetained(
                unexpected
            ).isEmpty()
        );
        Files.writeString(
            unexpected.resolve("README.txt"),
            "not a run",
            StandardCharsets.UTF_8
        );
        assertThrows(IllegalStateException.class, () ->
            RepresentationDiscoveryRunWorkspace.listRetained(unexpected));
    }

    @Test
    void symbolicRepositoryAndRunFilesFailClosed(
        @TempDir Path directory
    ) throws IOException {
        Path actual = directory.resolve("actual");
        Files.createDirectories(actual);
        Path link = directory.resolve("runs-link");
        if (!createSymbolicLink(link, actual)) {
            return;
        }
        assertThrows(IllegalStateException.class, () ->
            RepresentationDiscoveryRunWorkspace.listRetained(link));

        Path runs = directory.resolve("runs");
        RepresentationDiscoveryRunWorkspace workspace = workspace(9);
        RepresentationDiscoveryRunWorkspace.retain(runs, workspace);
        Path retained = runFile(runs, workspace.runId());
        Path outside = directory.resolve("outside.json");
        Files.writeString(
            outside,
            workspace.toCanonicalJson(),
            StandardCharsets.UTF_8
        );
        Files.delete(retained);
        if (!createSymbolicLink(retained, outside)) {
            return;
        }
        assertThrows(IllegalStateException.class, () ->
            RepresentationDiscoveryRunWorkspace.findRetained(
                runs,
                workspace.runId()
            ));
    }

    @Test
    void invalidRepositoryConfigurationAndRunIdsAreRejected(
        @TempDir Path directory
    ) {
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.listRetained(
                directory,
                0,
                1
            ));
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.listRetained(
                directory,
                1,
                0
            ));
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunWorkspace.findRetained(
                directory,
                "run-1"
            ));
        assertFalse(
            RepresentationDiscoveryRunWorkspace.findRetained(
                directory,
                sha("absent")
            ).isPresent()
        );
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException exception) {
            return false;
        }
    }

    private static Path runFile(Path directory, String runId) {
        return directory.resolve(
            runId.substring("sha256:".length()) + ".json");
    }

    private static RepresentationDiscoveryRunWorkspace workspace(long seed) {
        return RepresentationDiscoveryRunWorkspace.create(
            RepresentationDiscoveryRunInput.expression(
                "sin(x)^2 + (cos(x)^2 + 0)",
                List.of()
            ),
            RepresentationDiscoveryRunPlan.create(
                RepresentationDiscoveryInformationBoundary.Track
                    .R2_CATALOG_BLIND_POST_HOC_BRIDGE,
                sha("boundary"),
                sha("inventory"),
                sha("selection"),
                sha("catalog"),
                "target-free-breadth-first/v1",
                "pareto-archive/v1",
                "representation-discovery/v1",
                sha("budget"),
                seed,
                List.of("internal:java25", "sympy:1.14.0")
            ),
            RepresentationDiscoveryRunOutcome.create(
                TerminalState.COMPLETED,
                "CANDIDATES_RETAINED",
                100,
                80,
                sha("work-ledger"),
                sha("runtime-diagnostics")
            ),
            completeArtifacts(),
            RepresentationDiscoveryRevisionEvidence.create(
                REPOSITORY_COMMIT,
                "Regelsuche-workbench/0.3-SNAPSHOT"
            )
        );
    }

    private static List<RepresentationDiscoveryArtifactReference>
            completeArtifacts() {
        List<RepresentationDiscoveryArtifactReference> references =
            new ArrayList<>(
                RepresentationDiscoveryRunWorkspace.notProducedArtifacts());
        replace(references,
            RepresentationDiscoveryArtifactReference.available(
                ArtifactRole.SEARCH_GRAPH,
                "regelsuche.search-graph/v1",
                sha("search-graph")
            ));
        replace(references,
            RepresentationDiscoveryArtifactReference.available(
                ArtifactRole.REPRESENTATION_CANDIDATES,
                "regelsuche.representation-candidates/v1",
                sha("candidates")
            ));
        replace(references,
            RepresentationDiscoveryArtifactReference.unavailable(
                ArtifactRole.RULE_RADAR,
                ArtifactStatus.UNSUPPORTED,
                "NOT_AVAILABLE_FOR_RETAINED_RUN"
            ));
        return references;
    }

    private static void replace(
        List<RepresentationDiscoveryArtifactReference> references,
        RepresentationDiscoveryArtifactReference replacement
    ) {
        references.removeIf(reference ->
            reference.role() == replacement.role());
        references.add(replacement);
    }

    private static String sha(String value) {
        return KnownStructureCatalog.sha256(value);
    }
}
''', encoding="utf-8")

for name in (
    "JsonFileRepresentationDiscoveryRunRepository.java",
    "RepresentationDiscoveryRunRepository.java",
    "RepresentationDiscoveryRunWorkspaceCodec.java",
):
    (PACKAGE / name).unlink()

print("consolidated immutable run persistence")
