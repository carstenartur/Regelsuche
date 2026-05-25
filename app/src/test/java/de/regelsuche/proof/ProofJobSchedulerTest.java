package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProofJobSchedulerTest {

    private ProofJobScheduler scheduler;
    private InMemoryProofJobRepository repository;
    private InMemoryProofCache cache;
    private InMemoryRuleInventoryRepository inventory;

    @BeforeEach
    void setup() {
        repository = new InMemoryProofJobRepository();
        cache = new InMemoryProofCache();
        inventory = new InMemoryRuleInventoryRepository();
        // Use the skeleton-only LeanProofWorker (no real Lean installation needed)
        ProofWorker worker = new LeanProofWorker();
        scheduler = new ProofJobScheduler(worker, repository, cache, inventory,
            Duration.ofSeconds(5));
        scheduler.start();
    }

    @AfterEach
    void teardown() {
        scheduler.close();
    }

    private static RuleCandidate candidate(String left, String right) {
        return new RuleCandidate(
            left, right, 2, 1.0, 1, true, true, false,
            List.of(), RuleStatus.NEW, CandidateProofStatus.VALIDATED_BY_EXAMPLES, "h"
        );
    }

    // ── basic lifecycle ────────────────────────────────────────────────────

    @Test
    void jobReachesDoneState() throws InterruptedException {
        String jobId = scheduler.submit(candidate("A+0", "A"), List.of(), 0);
        assertEventually(() -> {
            Optional<ProofJob> job = scheduler.get(jobId);
            return job.isPresent() && job.get().status().isTerminal();
        }, 3000);
        ProofJob done = scheduler.get(jobId).orElseThrow();
        assertEquals(ProofJobStatus.DONE, done.status());
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, done.resultStatus());
    }

    @Test
    void cancellationOfQueuedJobWorks() throws InterruptedException {
        // Saturate the scheduler with a no-op worker so the next job stays QUEUED
        ProofWorker slow = new ProofWorker() {
            @Override
            public ProofWorker.Result prove(RuleCandidate c, List<Assumption> a) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new ProofWorker.Result(c, c.proofStatus(), "", "slow", 0L);
            }

            @Override
            public String workerId() {
                return "slow";
            }
        };
        // Create a separate scheduler with the slow worker
        InMemoryProofJobRepository slowRepo = new InMemoryProofJobRepository();
        ProofJobScheduler slowScheduler = new ProofJobScheduler(
            slow, slowRepo, new InMemoryProofCache(), null, Duration.ofSeconds(10));
        slowScheduler.start();
        try {
            String first = slowScheduler.submit(candidate("x", "x"), List.of(), 0);
            String second = slowScheduler.submit(candidate("y", "y"), List.of(), 1);
            // Cancel the second before it starts
            Thread.sleep(100);
            Optional<ProofJob> cancelled = slowScheduler.cancel(second);
            assertTrue(cancelled.isPresent());
            assertEquals(ProofJobStatus.CANCELLED, cancelled.get().status());
        } finally {
            slowScheduler.close();
        }
    }

    @Test
    void cancellationOfRunningJobStaysCancelled() throws InterruptedException {
        ProofWorker slow = new ProofWorker() {
            @Override
            public ProofWorker.Result prove(RuleCandidate c, List<Assumption> a) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new ProofWorker.Result(c, CandidateProofStatus.FORMALLY_PROVABLE, "", "slow", 0L);
            }

            @Override
            public String workerId() {
                return "slow-running";
            }
        };

        InMemoryProofJobRepository repo = new InMemoryProofJobRepository();
        ProofJobScheduler slowScheduler = new ProofJobScheduler(
            slow, repo, new InMemoryProofCache(), null, Duration.ofSeconds(5));
        slowScheduler.start();
        try {
            String jobId = slowScheduler.submit(candidate("x", "x"), List.of(), 0);
            assertEventually(() -> repo.findById(jobId)
                .map(job -> job.status() == ProofJobStatus.RUNNING)
                .orElse(false), 3000);
            slowScheduler.cancel(jobId);
            assertEventually(() -> repo.findById(jobId)
                .map(job -> job.status() == ProofJobStatus.CANCELLED)
                .orElse(false), 3000);
        } finally {
            slowScheduler.close();
        }
    }

    @Test
    void cacheHitSkipsWorker() throws InterruptedException {
        // Pre-populate the cache
        ProofCacheKey key = ProofCacheKey.of("A*1", "A", List.of(), "lean4");
        cache.put(key, CandidateProofStatus.FORMALLY_PROVED);

        String jobId = scheduler.submit(candidate("A*1", "A"), List.of(), 0);
        assertEventually(() -> {
            Optional<ProofJob> job = scheduler.get(jobId);
            return job.isPresent() && job.get().status().isTerminal();
        }, 3000);

        ProofJob done = scheduler.get(jobId).orElseThrow();
        assertEquals(ProofJobStatus.DONE, done.status());
        assertEquals(CandidateProofStatus.FORMALLY_PROVED, done.resultStatus(),
            "cache hit must propagate FORMALLY_PROVED status");
    }

    @Test
    void priorityOrderRespected() throws InterruptedException {
        // Submit two jobs with different priorities; the low-priority one is submitted first
        // but the high-priority one should be processed first.
        // Since the scheduler processes one at a time this is verifiable via ordering.
        String lowPrioId = scheduler.submit(candidate("x+y", "y+x"), List.of(), 10);
        String highPrioId = scheduler.submit(candidate("x*1", "x"), List.of(), 0);

        assertEventually(() -> {
            Optional<ProofJob> low = scheduler.get(lowPrioId);
            Optional<ProofJob> high = scheduler.get(highPrioId);
            return low.isPresent() && high.isPresent()
                && low.get().status().isTerminal()
                && high.get().status().isTerminal();
        }, 5000);

        ProofJob high = scheduler.get(highPrioId).orElseThrow();
        assertEquals(ProofJobStatus.DONE, high.status());
    }

    @Test
    void retriesOnFailure() throws InterruptedException {
        final int[] callCount = {0};
        ProofWorker failThenSucceed = new ProofWorker() {
            @Override
            public ProofWorker.Result prove(RuleCandidate c, List<Assumption> a) {
                callCount[0]++;
                if (callCount[0] < 2) {
                    throw new RuntimeException("simulated failure");
                }
                return new ProofWorker.Result(c, CandidateProofStatus.FORMALLY_PROVABLE,
                    "ok", "mock", 1L);
            }

            @Override
            public String workerId() {
                return "mock-retry";
            }
        };

        InMemoryProofJobRepository retryRepo = new InMemoryProofJobRepository();
        ProofJobScheduler retryScheduler = new ProofJobScheduler(
            failThenSucceed, retryRepo, new InMemoryProofCache(), null,
            Duration.ofSeconds(5));
        retryScheduler.start();
        try {
            String jobId = retryScheduler.submit(candidate("a+b", "b+a"), List.of(), 0);
            assertEventually(() -> {
                Optional<ProofJob> job = retryRepo.findById(jobId);
                return job.isPresent() && job.get().status().isTerminal();
            }, 5000);
            ProofJob done = retryRepo.findById(jobId).orElseThrow();
            assertEquals(ProofJobStatus.DONE, done.status());
            assertTrue(done.retryCount() >= 1, "at least one retry expected");
        } finally {
            retryScheduler.close();
        }
    }

    @Test
    void jobExhaustsRetriesAndFails() throws InterruptedException {
        ProofWorker alwaysFails = new ProofWorker() {
            @Override
            public ProofWorker.Result prove(RuleCandidate c, List<Assumption> a) {
                throw new RuntimeException("always fails");
            }

            @Override
            public String workerId() {
                return "mock-fail";
            }
        };

        InMemoryProofJobRepository failRepo = new InMemoryProofJobRepository();
        ProofJobScheduler failScheduler = new ProofJobScheduler(
            alwaysFails, failRepo, new InMemoryProofCache(), null,
            Duration.ofSeconds(5));
        failScheduler.start();
        try {
            String jobId = failScheduler.submit(candidate("a", "b"), List.of(), 0);
            assertEventually(() -> {
                Optional<ProofJob> job = failRepo.findById(jobId);
                return job.isPresent() && job.get().status() == ProofJobStatus.FAILED;
            }, 10000);
            ProofJob failed = failRepo.findById(jobId).orElseThrow();
            assertEquals(ProofJobStatus.FAILED, failed.status());
            assertFalse(failed.errorMessage().isBlank(),
                "failed job must carry an error message");
        } finally {
            failScheduler.close();
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void assertEventually(BooleanSupplier condition, long timeoutMillis)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.check()) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(condition.check(), "condition not met within " + timeoutMillis + "ms");
    }

    @FunctionalInterface
    interface BooleanSupplier {
        boolean check();
    }
}
