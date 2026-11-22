package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.representation.RepresentationDiscoveryRunWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeSearchOperationsTest {
    @TempDir Path root;
    private static final String REVISION = "a".repeat(40);

    @Test void unavailableSourceRevisionPreventsAdmissionBeforeCreatingAnOperation() throws Exception {
        try (var service = new NativeSearchOperations(root.resolve("runs"), () -> "")) {
            assertThrows(IllegalStateException.class, () -> service.start("x + 0", "small"));
            assertTrue(service.ids().isEmpty());
            assertFalse(Files.exists(root.resolve("runs-operations")));
        }
    }

    @Test void nativeCompletionPublishesTheActualImmutableWorkspaceAndTerminalCounters() throws Exception {
        Path runs = root.resolve("runs");
        String id;
        try (var service = new NativeSearchOperations(runs, () -> REVISION)) {
            var started = service.start("x + 0", "small");
            id = started.operationId();
            var terminal = service.await(id, 20, TimeUnit.SECONDS);
            assertEquals("COMPLETED", terminal.status(), terminal::toString);
            assertTrue(terminal.progress().visited() > 0);
            assertTrue(terminal.progress().generated() >= 1);
            var run = RepresentationDiscoveryRunWorkspace.findRetained(runs,terminal.workspaceRunId()).orElseThrow();
            assertEquals("x + 0",run.input().displayText());
            var artifact = de.regelsuche.discovery.representation.TargetFreeSearchExecution.fromCanonicalBytes(run,
                de.regelsuche.discovery.representation.TargetFreeSearchArtifactStore.read(runs, run).orElseThrow()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(artifact.content().work().generatedTransformations(), terminal.progress().generated());
            assertEquals(artifact.content().states().size(), terminal.progress().visited());
            assertEquals(terminal,service.cancel(id));
        }
        try (var reopened = new NativeSearchOperations(runs, () -> REVISION)) {
            assertEquals("COMPLETED",reopened.find(id).orElseThrow().status());
        }
    }

    @Test void restartRecoversLostWorkerAndRejectsTamperedTerminalRecord() throws Exception {
        var runs = root.resolve("runs");
        var store = new NativeSearchOperationStore(runs);
        var id = java.util.UUID.randomUUID().toString();
        var paused = new NativeSearchOperation(id, "x + 0", "small", new de.regelsuche.search.SearchHeuristic(2,12,1,1,8,8),
            REVISION, "PAUSED", "", null, NativeSearchOperation.Progress.initial());
        var lease = store.tryLease();
        try {
            store.save(paused);
            try (var observer = new NativeSearchOperations(runs, () -> REVISION)) {
                assertEquals("PAUSED", observer.find(id).orElseThrow().status());
                assertThrows(IllegalStateException.class, () -> observer.cancel(id));
            }
        } finally { NativeSearchOperationStore.release(lease); }
        try (var recovered = new NativeSearchOperations(runs, () -> REVISION)) {
            var receipt = recovered.find(id).orElseThrow();
            assertEquals("INTERRUPTED", receipt.status());
            assertEquals("WORKER_LEASE_ENDED", receipt.terminalReason());
            var file = root.resolve("runs-operations").resolve(id + ".json");
            Files.writeString(file, Files.readString(file).replace("WORKER_LEASE_ENDED", "COMPLETED"));
            assertThrows(java.io.IOException.class, () -> recovered.find(id));
        }
    }

    @Test void hostShutdownAndRunnerFailureRetainTheirOwnTerminalReason() throws Exception {
        Path runs = root.resolve("runs");
        var paused = new NativeSearchOperations(runs, () -> REVISION);
        var started = paused.start("x", "small", true);
        paused.close();
        var stopped = paused.await(started.operationId(), 10, TimeUnit.SECONDS);
        assertEquals("INTERRUPTED", stopped.status());
        assertEquals("HOST_STOP", stopped.terminalReason());
        assertNull(stopped.workspaceRunId());
        // await() is also the synchronization boundary for the old worker's file lease.
        var releasedLease = new NativeSearchOperationStore(runs).tryLease();
        assertNotNull(releasedLease, "terminal await must not return while the old worker owns the file lease");
        NativeSearchOperationStore.release(releasedLease);
        try (var failed = new NativeSearchOperations(runs, () -> REVISION, (source,budget,revision,events) -> {
            throw new java.io.IOException("worker unavailable");
        })) {
            var attempt = failed.start("x", "small");
            var terminal = failed.await(attempt.operationId(), 10, TimeUnit.SECONDS);
            assertEquals("FAILED", terminal.status());
            assertEquals("IOException", terminal.terminalReason());
            assertNull(terminal.workspaceRunId());
        }
    }

    @Test void cancellationRemainsAuditableAndNeverCreatesACompletedCandidateRun() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var service = new NativeSearchOperations(root.resolve("runs"), () -> REVISION, (source,budget,revision,observer) -> {
            entered.countDown();
            release.await(10,TimeUnit.SECONDS);
            return de.regelsuche.discovery.representation.TargetFreeRepresentationDiscoveryRun.runTargetFree(source,budget,revision,observer);
        })) {
            var started = service.start("(x + 0) * 1", "small");
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> service.start("x","small"));
            assertEquals("CANCELLING",service.cancel(started.operationId()).status());
            release.countDown();
            var end = service.await(started.operationId(),20,TimeUnit.SECONDS);
            assertEquals("CANCELLED",end.status());
            assertNull(end.workspaceRunId());
            assertEquals("OPERATOR_STOP",end.terminalReason());
            assertEquals(end,service.find(started.operationId()).orElseThrow());
        } finally { release.countDown(); }
    }
}
