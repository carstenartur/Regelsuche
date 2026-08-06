package de.regelsuche.proof;

import static de.regelsuche.testsupport.ConditionAwaiter.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProofJobSchedulerLifecycleTest {

    private static RuleCandidate candidate() {
        return new RuleCandidate(
            "A+0", "A", 2, 1.0, 1, true, true, false,
            List.of(), RuleStatus.NEW, CandidateProofStatus.VALIDATED_BY_EXAMPLES, "h"
        );
    }

    @Test
    void schedulerClosesWorkerExecutor() throws InterruptedException {
        ProofJobScheduler scheduler = new ProofJobScheduler(
            new LeanProofWorker(),
            new InMemoryProofJobRepository(),
            new InMemoryProofCache(),
            null,
            Duration.ofSeconds(2)
        );
        scheduler.start();
        assertFalse(scheduler.isWorkerExecutorTerminated(),
            "executor should be running while scheduler is alive");
        scheduler.close();
        assertTrue(scheduler.isWorkerExecutorTerminated(),
            "close() must terminate the worker executor");
    }

    @Test
    void schedulerDoesNotLeakExecutorsAcrossJobs() throws InterruptedException {
        InMemoryProofJobRepository repo = new InMemoryProofJobRepository();
        ProofJobScheduler scheduler = new ProofJobScheduler(
            new LeanProofWorker(),
            repo,
            new InMemoryProofCache(),
            null,
            Duration.ofSeconds(5)
        );
        scheduler.start();
        try {
            List<String> jobIds = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                jobIds.add(scheduler.submit(candidate(), List.of(), i));
            }
            await(
                Duration.ofSeconds(10),
                () -> jobIds.stream()
                    .map(repo::findById)
                    .allMatch(job -> job.isPresent() && job.get().status().isTerminal()),
                "not all submitted proof jobs reached a terminal state"
            );

            for (String jobId : jobIds) {
                Optional<ProofJob> job = repo.findById(jobId);
                assertTrue(job.isPresent() && job.get().status().isTerminal(),
                    "all submitted jobs should reach a terminal state");
            }
            assertFalse(scheduler.isWorkerExecutorTerminated(),
                "executor should still be alive after job batch");
        } finally {
            scheduler.close();
        }
        assertTrue(scheduler.isWorkerExecutorTerminated(),
            "executor must be terminated after scheduler.close()");
    }

    @Test
    void proofJobsSurviveRestart(@TempDir Path tempDir)
        throws IOException, InterruptedException {
        Path jobFile = tempDir.resolve("jobs.json");

        JsonFileProofJobRepository repo1 = new JsonFileProofJobRepository(jobFile);
        ProofJobScheduler scheduler1 = new ProofJobScheduler(
            new LeanProofWorker(),
            repo1,
            new InMemoryProofCache(),
            null,
            Duration.ofSeconds(5)
        );
        scheduler1.start();
        String jobId;
        try {
            jobId = scheduler1.submit(
                candidate(), List.of(Assumption.nonZero("b")), 0);
            await(
                Duration.ofSeconds(5),
                () -> repo1.findById(jobId)
                    .map(job -> job.status().isTerminal())
                    .orElse(false),
                "proof job did not finish before restart"
            );
        } finally {
            scheduler1.close();
        }

        JsonFileProofJobRepository repo2 = new JsonFileProofJobRepository(jobFile);
        Optional<ProofJob> reloaded = repo2.findById(jobId);
        assertTrue(reloaded.isPresent(), "job must survive restart");
        assertEquals(ProofJobStatus.DONE, reloaded.get().status());
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, reloaded.get().resultStatus());
        assertEquals(1, reloaded.get().assumptions().size(),
            "assumptions must survive restart");
        assertEquals(Assumption.Kind.NON_ZERO, reloaded.get().assumptions().get(0).kind());
    }

    @Test
    void queuedJobsAreResumedAfterRestart(@TempDir Path tempDir)
        throws IOException, InterruptedException {
        Path jobFile = tempDir.resolve("jobs.json");

        JsonFileProofJobRepository repo1 = new JsonFileProofJobRepository(jobFile);
        ProofJob queued = new ProofJob(
            "test-job-1",
            "A*1",
            "A",
            List.of(),
            5,
            "lean4"
        );
        repo1.save(queued);

        ProofJob running = new ProofJob("test-job-2", "B+0", "B",
            List.of(), 1, "lean4").withStatus(ProofJobStatus.RUNNING);
        repo1.save(running);

        JsonFileProofJobRepository repo2 = new JsonFileProofJobRepository(jobFile);
        assertEquals(ProofJobStatus.QUEUED,
            repo2.findById("test-job-1").orElseThrow().status());
        assertEquals(ProofJobStatus.QUEUED,
            repo2.findById("test-job-2").orElseThrow().status(),
            "RUNNING jobs at JVM-death time must be requeued on restart");

        ProofJobScheduler scheduler = new ProofJobScheduler(
            new LeanProofWorker(),
            repo2,
            new InMemoryProofCache(),
            null,
            Duration.ofSeconds(5)
        );
        scheduler.start();
        try {
            await(
                Duration.ofSeconds(5),
                () -> repo2.findById("test-job-1")
                    .map(job -> job.status().isTerminal())
                    .orElse(false)
                    && repo2.findById("test-job-2")
                        .map(job -> job.status().isTerminal())
                        .orElse(false),
                "requeued proof jobs did not finish after restart"
            );
            assertEquals(ProofJobStatus.DONE,
                repo2.findById("test-job-1").orElseThrow().status());
            assertEquals(ProofJobStatus.DONE,
                repo2.findById("test-job-2").orElseThrow().status());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void cancelledJobsRemainCancelled(@TempDir Path tempDir) throws IOException {
        Path jobFile = tempDir.resolve("jobs.json");
        JsonFileProofJobRepository repo1 = new JsonFileProofJobRepository(jobFile);
        ProofJob cancelled = new ProofJob("c1", "x", "x", List.of(), 0, "lean4")
            .withStatus(ProofJobStatus.CANCELLED);
        repo1.save(cancelled);

        JsonFileProofJobRepository repo2 = new JsonFileProofJobRepository(jobFile);
        Optional<ProofJob> reloaded = repo2.findById("c1");
        assertTrue(reloaded.isPresent());
        assertEquals(ProofJobStatus.CANCELLED, reloaded.get().status(),
            "CANCELLED is terminal and must survive restart unchanged");
    }
}
