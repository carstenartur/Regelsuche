package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.search.SearchHeuristic;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetFreeRetentionAtomicityReviewTest {
    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final SearchHeuristic BUDGET = new SearchHeuristic(2, 8, 1, 1, 8, 8);
    @TempDir Path temporary;

    @Test void workspaceConflictDoesNotLeaveANewDossierOrChangeTheExistingWorkspace() throws Exception {
        Path runs = Files.createDirectory(temporary.resolve("runs"));
        var expected = run("x + 0");
        byte[] foreign = run("y + 0").workspace().toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        Path workspace = workspacePath(runs, expected.workspace());
        Files.write(workspace, foreign);
        assertThrows(RepresentationDiscoveryRunWorkspace.ImmutableRunConflictException.class,
            () -> TargetFreeRepresentationDiscoveryRun.writeTargetFree(runs, "x + 0", BUDGET, REVISION));
        assertArrayEquals(foreign, Files.readAllBytes(workspace));
        assertFalse(Files.exists(dossierPath(runs, expected.workspace())), "failed workspace retention leaked a new dossier");
    }

    @Test void invalidWorkspaceDirectoryDoesNotLeaveANewDossier() throws Exception {
        Path runs = temporary.resolve("runs");
        byte[] original = "existing regular file, not a repository".getBytes(StandardCharsets.UTF_8);
        Files.write(runs, original);
        var expected = run("x + 0");
        assertThrows(IllegalStateException.class,
            () -> TargetFreeRepresentationDiscoveryRun.writeTargetFree(runs, "x + 0", BUDGET, REVISION));
        assertArrayEquals(original, Files.readAllBytes(runs));
        assertFalse(Files.exists(dossierPath(runs, expected.workspace())), "failed directory admission leaked a dossier");
    }

    @Test void repeatedFullWorkspaceFailuresDoNotConsumeDossierSlots() throws Exception {
        Path runs = Files.createDirectory(temporary.resolve("runs"));
        // Capacity uses regular filename entries, before decoding their payloads.
        int capacity = RepresentationDiscoveryRunWorkspace.DEFAULT_MAX_RETAINED_RUNS;
        for (int i = 0; i < capacity; i++) Files.createFile(runs.resolve(String.format("%064x.json", i)));
        for (String source : List.of("x + 0", "y + 0", "z + 0")) {
            var failure = assertThrows(IllegalStateException.class,
                () -> TargetFreeRepresentationDiscoveryRun.writeTargetFree(runs, source, BUDGET, REVISION));
            assertTrue(failure.getMessage().contains("repository limit exceeded: " + capacity));
        }
        try (var dossiers = Files.list(temporary.resolve("runs-dossiers"))) {
            assertEquals(0, dossiers.count(), "three failed writes must not permanently consume three dossier slots");
        }
        try (var workspaces = Files.list(runs)) { assertEquals(capacity, workspaces.count()); }
    }

    @Test void anAlreadyRetainedDossierSurvivesWorkspaceConflictByteForByte() throws Exception {
        Path runs = Files.createDirectory(temporary.resolve("runs"));
        var expected = run("x + 0");
        byte[] original = expected.artifact().toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        TargetFreeSearchArtifactStore.retain(runs, expected.workspace(), original);
        byte[] foreign = run("y + 0").workspace().toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        Files.write(workspacePath(runs, expected.workspace()), foreign);
        assertThrows(RepresentationDiscoveryRunWorkspace.ImmutableRunConflictException.class,
            () -> TargetFreeRepresentationDiscoveryRun.writeTargetFree(runs, "x + 0", BUDGET, REVISION));
        assertArrayEquals(original, Files.readAllBytes(dossierPath(runs, expected.workspace())));
        assertArrayEquals(foreign, Files.readAllBytes(workspacePath(runs, expected.workspace())));
    }

    @Test void conflictingDossierBytesAreNeverDeletedOrReplacedDuringFailure() throws Exception {
        Path runs = Files.createDirectory(temporary.resolve("runs"));
        var expected = run("x + 0");
        Path dossier = dossierPath(runs, expected.workspace());
        Files.createDirectories(dossier.getParent());
        byte[] original = "existing conflicting dossier".getBytes(StandardCharsets.UTF_8);
        Files.write(dossier, original);
        assertThrows(IllegalArgumentException.class,
            () -> TargetFreeRepresentationDiscoveryRun.writeTargetFree(runs, "x + 0", BUDGET, REVISION));
        assertArrayEquals(original, Files.readAllBytes(dossier));
        assertFalse(Files.exists(workspacePath(runs, expected.workspace())));
    }

    @Test void concurrentIdenticalRetentionsProduceOneCompleteUnchangedPair() throws Exception {
        Path runs = temporary.resolve("runs");
        var expected = run("x + 0");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<TargetFreeSearchExecution.RunResult> write = () -> {
                start.await();
                return TargetFreeRepresentationDiscoveryRun.writeTargetFree(runs, "x + 0", BUDGET, REVISION);
            };
            var first = executor.submit(write); var second = executor.submit(write); start.countDown();
            assertEquals(expected.workspace(), first.get(10, TimeUnit.SECONDS).workspace());
            assertEquals(expected.workspace(), second.get(10, TimeUnit.SECONDS).workspace());
        }
        assertEquals(expected.workspace(), RepresentationDiscoveryRunWorkspace.findRetained(runs, expected.workspace().runId()).orElseThrow());
        assertEquals(expected.artifact().toCanonicalJson(), TargetFreeSearchArtifactStore.read(runs, expected.workspace()).orElseThrow());
        try (var entries = Files.list(runs)) { assertEquals(1, entries.count()); }
        try (var entries = Files.list(temporary.resolve("runs-dossiers"))) { assertEquals(1, entries.count()); }
    }

    private static TargetFreeSearchExecution.RunResult run(String source) {
        return TargetFreeRepresentationDiscoveryRun.runTargetFree(source, BUDGET, REVISION);
    }
    private static Path workspacePath(Path runs, RepresentationDiscoveryRunWorkspace workspace) {
        return runs.resolve(workspace.runId().substring(7) + ".json");
    }
    private static Path dossierPath(Path runs, RepresentationDiscoveryRunWorkspace workspace) {
        return runs.resolveSibling(runs.getFileName() + "-dossiers").resolve(workspace.runId().substring(7) + ".json");
    }
}
