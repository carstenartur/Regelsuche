package de.regelsuche.proof;

import static de.regelsuche.testsupport.ConditionAwaiter.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProofJobCancellationAuditTest {
    @Test
    void keepsCancellationPendingUntilWorkerReturnsAndDiscardsItsLateResult(@TempDir Path dir) throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = new ProofWorker() {
            public String workerId() { return "held-worker"; }
            public Result prove(RuleCandidate candidate, List<Assumption> assumptions) {
                entered.countDown();
                for (;;) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException stop) {
                        interrupted.countDown();
                    }
                }
                Thread.currentThread().interrupt();
                return new Result(candidate, CandidateProofStatus.FORMALLY_PROVABLE, "late", workerId(), 0);
            }
        };
        var path = dir.resolve("jobs.json");
        var jobs = new JsonFileProofJobRepository(path);
        var cache = new InMemoryProofCache();
        var artifacts = new JsonFileProofArtifactRepository(dir.resolve("proofs"));
        var candidate = new RuleCandidate("x", "x", 0, 0, 0, false, false, false,
            List.of(), RuleStatus.NEW, CandidateProofStatus.OBSERVED, "");
        try (var service = new ProofJobScheduler(worker, jobs, cache, null, artifacts, Duration.ofSeconds(5))) {
            service.start();
            try {
                var id = service.submit(candidate, List.of(), 0);
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var requested = service.cancel(id).orElseThrow();
                assertEquals("CANCELLING", requested.status().name());
                assertEquals(1, requested.attemptsStarted());
                assertEquals(5_000L, requested.attemptTimeoutMillis());
                assertEquals("USER_CANCELLED", requested.stopReason());
                assertNotNull(requested.cancellationRequestedAt());
                assertTrue(interrupted.await(3, TimeUnit.SECONDS));
                assertEquals(requested, service.get(id).orElseThrow());
                assertEquals(requested, service.cancel(id).orElseThrow());
                release.countDown();
                await(Duration.ofSeconds(3), () -> service.get(id).orElseThrow().status() == ProofJobStatus.CANCELLED,
                    "worker exit was not retained");
                var stopped = service.get(id).orElseThrow();
                assertEquals(stopped, service.cancel(id).orElseThrow());
                assertEquals(stopped, new JsonFileProofJobRepository(path).findById(id).orElseThrow());
                assertEquals(requested.cancellationRequestedAt(), stopped.cancellationRequestedAt());
                assertEquals(1, stopped.attemptsStarted());
                assertTrue(!stopped.updatedAt().isBefore(requested.cancellationRequestedAt()));
                assertNull(stopped.resultStatus());
                assertTrue(cache.get(ProofCacheKey.of("x", "x", List.of(), worker.cacheIdentity())).isEmpty());
                assertTrue(artifacts.listJobArtifacts(id).isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void restoresPendingStopOnlyOnceAndPreservesTheUnknownBudgetOfOldSnapshots(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("jobs.json");
        var repo = new JsonFileProofJobRepository(file);
        var pending = new ProofJob("pending", "x", "x", List.of(), 0, "worker")
            .withBudget(300).startAttempt(300).stopping("USER_CANCELLED");
        repo.save(pending);
        var restored = new JsonFileProofJobRepository(file).findById("pending").orElseThrow();
        assertEquals(ProofJobStatus.CANCELLED, restored.status());
        assertEquals("USER_CANCELLED_RECOVERED_AFTER_RESTART", restored.stopReason());
        assertEquals(1, restored.attemptsStarted());
        assertEquals(pending.cancellationRequestedAt(), restored.cancellationRequestedAt());
        assertEquals(restored, new JsonFileProofJobRepository(file).findById("pending").orElseThrow());

        Files.writeString(file, """
            {"jobs":[{"id":"old","leftPattern":"x","rightPattern":"x","status":"CANCELLED"}]}
            """);
        var old = new JsonFileProofJobRepository(file).findById("old").orElseThrow();
        assertNull(old.attemptsStarted());
        assertNull(old.attemptTimeoutMillis());
        assertNull(old.cancellationRequestedAt());
    }

    @Test
    void retriesATimedOutAttemptOnlyAfterItExits() throws Exception {
        var calls = new AtomicInteger();
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ProofWorker worker = new ProofWorker() {
            public String workerId() { return "bounded-timeout"; }
            public Result prove(RuleCandidate candidate, List<Assumption> assumptions) {
                if (calls.incrementAndGet() == 1) {
                    for (;;) {
                        try {
                            release.await();
                            break;
                        } catch (InterruptedException timeout) {
                            interrupted.countDown();
                        }
                    }
                }
                return new Result(candidate, CandidateProofStatus.FORMALLY_PROVABLE, "", workerId(), 0);
            }
        };
        var candidate = new RuleCandidate("x", "x", 0, 0, 0, false, false, false,
            List.of(), RuleStatus.NEW, CandidateProofStatus.OBSERVED, "");
        try (var service = new ProofJobScheduler(worker, new InMemoryProofJobRepository(),
                new InMemoryProofCache(), null, Duration.ofMillis(100))) {
            service.start();
            String id = service.submit(candidate, List.of(), 0);
            try {
                assertTrue(interrupted.await(3, TimeUnit.SECONDS));
                var pending = service.get(id).orElseThrow();
                assertEquals(ProofJobStatus.RUNNING, pending.status());
                assertEquals(1, pending.attemptsStarted());
                assertEquals(0, pending.retryCount());
            } finally {
                release.countDown();
            }
            await(Duration.ofSeconds(4), () -> service.get(id).orElseThrow().status() == ProofJobStatus.DONE,
                "timed out attempt was not retried");
            assertEquals(2, service.get(id).orElseThrow().attemptsStarted());
            assertEquals(1, service.get(id).orElseThrow().retryCount());
        }
    }

    @Test
    void queuedCancellationRecordsZeroActualAttempts() {
        var jobs = new InMemoryProofJobRepository();
        var candidate = new RuleCandidate("x", "x", 0, 0, 0, false, false, false,
            List.of(), RuleStatus.NEW, CandidateProofStatus.OBSERVED, "");
        try (var service = new ProofJobScheduler(new LeanProofWorker(), jobs, new InMemoryProofCache(),
                null, Duration.ofSeconds(3))) {
            var id = service.submit(candidate, List.of(), 0);
            var stopped = service.cancel(id).orElseThrow();
            assertEquals(ProofJobStatus.CANCELLED, stopped.status());
            assertEquals(0, stopped.attemptsStarted());
            assertEquals(3000L, stopped.attemptTimeoutMillis());
            assertEquals("USER_CANCELLED", stopped.stopReason());
            assertNotNull(stopped.cancellationRequestedAt());
        }
    }
}
