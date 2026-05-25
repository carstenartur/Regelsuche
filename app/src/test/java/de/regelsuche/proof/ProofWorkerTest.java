package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProofWorkerTest {

    private static RuleCandidate candidate() {
        return new RuleCandidate(
            "A + 0",
            "A",
            3,
            1.0,
            2,
            true,
            true,
            false,
            List.of(),
            RuleStatus.MATCHES_KNOWN_RULE,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            "hash_a0"
        );
    }

    // ── LeanProofWorker ────────────────────────────────────────────────────

    @Test
    void leanWorkerGeneratesSkeletonWithoutExecutor() {
        LeanProofWorker worker = new LeanProofWorker();
        ProofWorker.Result result = worker.prove(candidate(), List.of());

        assertEquals("lean4", result.tool());
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, result.status());
        assertTrue(result.artifact().contains("sorry"),
            "Lean skeleton must contain 'sorry'");
        assertNotNull(result.updatedCandidate());
    }

    @Test
    void leanWorkerIncludesAssumptionsInArtifact() {
        LeanProofWorker worker = new LeanProofWorker();
        ProofWorker.Result result = worker.prove(candidate(),
            List.of(Assumption.nonZero("x")));

        assertTrue(result.artifact().contains("x"),
            "artifact should mention the assumption variable");
    }

    @Test
    void leanWorkerDoesNotLowerAlreadyProvedStatus() {
        RuleCandidate proved = new RuleCandidate(
            "A + 0", "A", 3, 1.0, 2, true, true, false,
            List.of(), RuleStatus.MATCHES_KNOWN_RULE, CandidateProofStatus.FORMALLY_PROVED, "h"
        );
        LeanProofWorker worker = new LeanProofWorker();
        ProofWorker.Result result = worker.prove(proved, List.of());

        assertEquals(CandidateProofStatus.FORMALLY_PROVED, result.status(),
            "status must not be lowered below FORMALLY_PROVED");
    }

    @Test
    void leanWorkerIdIsLean4() {
        assertEquals("lean4", new LeanProofWorker().workerId());
    }

    // ── SmtProofWorker ─────────────────────────────────────────────────────

    @Test
    void smtWorkerGeneratesSmtScriptWithoutExecutor() {
        SmtProofWorker worker = new SmtProofWorker();
        ProofWorker.Result result = worker.prove(candidate(), List.of());

        assertEquals("smtlib2", result.tool());
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, result.status());
        assertTrue(result.artifact().contains("check-sat"),
            "SMT script must contain '(check-sat)'");
    }

    @Test
    void smtWorkerIdIsSmtlib2() {
        assertEquals("smtlib2", new SmtProofWorker().workerId());
    }

    // ── CompositeProofWorker ───────────────────────────────────────────────

    @Test
    void compositeReturnsHighestStatus() {
        CompositeProofWorker composite = new CompositeProofWorker(
            List.of(new LeanProofWorker(), new SmtProofWorker()));
        ProofWorker.Result result = composite.prove(candidate(), List.of());

        // Both workers return FORMALLY_PROVABLE without an executor; composite
        // should return the first worker's result (same level).
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE, result.status());
    }

    @Test
    void compositeShortCircuitsOnFormallyProved() {
        // Worker that always claims FORMALLY_PROVED
        ProofWorker fastWorker = new ProofWorker() {
            @Override
            public Result prove(RuleCandidate c, List<Assumption> a) {
                RuleCandidate updated = new RuleCandidate(
                    c.leftPattern(), c.rightPattern(), c.examplesCount(),
                    c.averageScoreImprovement(), c.maximumScoreImprovement(),
                    c.equivalenceVerified(), c.generalizationPlausible(),
                    c.containsFreeParameters(), c.parameterRelations(),
                    c.status(), CandidateProofStatus.FORMALLY_PROVED, c.canonicalHash()
                );
                return new Result(updated, CandidateProofStatus.FORMALLY_PROVED,
                    "proof", "mock", 1L);
            }

            @Override
            public String workerId() {
                return "mock-proved";
            }
        };

        // Worker that should never be reached
        ProofWorker neverReached = new ProofWorker() {
            @Override
            public Result prove(RuleCandidate c, List<Assumption> a) {
                throw new AssertionError("should not be reached after short-circuit");
            }

            @Override
            public String workerId() {
                return "never";
            }
        };

        CompositeProofWorker composite = new CompositeProofWorker(
            List.of(fastWorker, neverReached));
        ProofWorker.Result result = composite.prove(candidate(), List.of());

        assertEquals(CandidateProofStatus.FORMALLY_PROVED, result.status());
    }

    @Test
    void compositeWorkerIdContainsMemberIds() {
        CompositeProofWorker composite = new CompositeProofWorker(
            List.of(new LeanProofWorker(), new SmtProofWorker()));
        assertTrue(composite.workerId().contains("lean4"));
        assertTrue(composite.workerId().contains("smtlib2"));
    }
}
