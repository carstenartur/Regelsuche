package de.regelsuche.discovery.representation;

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
            assertTrue(
                retained.getFileName().toString().matches(
                    "[0-9a-f]{64}\\.json")
            );
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
