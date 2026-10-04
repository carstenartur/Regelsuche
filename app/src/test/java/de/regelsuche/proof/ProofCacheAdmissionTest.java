package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A persisted status is not the requested proof under today's evidence contract. */
class ProofCacheAdmissionTest {
    @TempDir Path temporary;

    @Test void legacyPersistentLeanAndSmtStatusesCannotBypassExecution() throws Exception {
        int index = 0;
        for (ProofWorker worker : List.of(new LeanProofWorker(), new SmtProofWorker(),
                new CompositeProofWorker(List.of(new LeanProofWorker(), new SmtProofWorker())))) {
            Path file = temporary.resolve("legacy-" + index++ + ".json");
            var cache = new JsonFileProofCache(file);
            cache.put(ProofCacheKey.of("x+1", "x", List.of(), worker.workerId()),
                CandidateProofStatus.FORMALLY_PROVED);
            var reopened = new JsonFileProofCache(file);
            try (var scheduler = new ProofJobScheduler(worker, new InMemoryProofJobRepository(),
                    reopened, null, Duration.ofSeconds(3))) {
                String id = scheduler.submit(new RuleCandidate("x+1", "x", 0, 0, 0,
                    false, false, false, List.of(), RuleStatus.NEW,
                    CandidateProofStatus.OBSERVED, ""), List.of(), 0);
                scheduler.start();
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (!scheduler.get(id).orElseThrow().status().isTerminal()
                        && System.nanoTime() < deadline) Thread.sleep(10);
                var result = scheduler.get(id).orElseThrow();
                assertEquals(ProofJobStatus.DONE, result.status());
                assertNotEquals(CandidateProofStatus.FORMALLY_PROVED, result.resultStatus(),
                    "a legacy " + worker.workerId() + " status bypassed the current proof boundary");
            }
        }
    }
}
