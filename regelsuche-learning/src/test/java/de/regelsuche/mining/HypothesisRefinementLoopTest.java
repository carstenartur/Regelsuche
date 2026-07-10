package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HypothesisRefinementLoop}. */
class HypothesisRefinementLoopTest {

    private static final CounterexampleSearchService NO_COUNTEREXAMPLE =
        (hypothesis, budget) -> CounterexampleSearchService.CounterexampleSearchResult.noCounterexample();

    private static final CounterexampleSearchService INCONCLUSIVE_SERVICE =
        (hypothesis, budget) -> CounterexampleSearchService.CounterexampleSearchResult.inconclusive("test");

    private static final CounterexampleSearchService ALWAYS_COUNTEREXAMPLE =
        (hypothesis, budget) -> CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
            new CounterexampleSearchService.Counterexample(List.of("x=1"), "2", "3"),
            List.of(),
            List.of("numeric-random")
        );

    /** A service that finds a counterexample for division but not after adding constraint. */
    private static final CounterexampleSearchService DIVISION_BY_ZERO_SERVICE =
        (hypothesis, budget) -> {
            if (hypothesis.assumptions().contains("b != 0")) {
                return CounterexampleSearchService.CounterexampleSearchResult.noCounterexample();
            }
            return CounterexampleSearchService.CounterexampleSearchResult.counterexampleFound(
                new CounterexampleSearchService.Counterexample(List.of("b=0"), "undefined", "1"),
                List.of(),
                List.of("numeric-random")
            );
        };

    private static HypothesisCandidate hypothesis(String id, String left, String right) {
        return new HypothesisCandidate(
            id, left, right, null, null, null, 0.5,
            CandidateProofStatus.OBSERVED, null, null, null, null
        );
    }

    @Test
    void noCounterexampleResultsInValidatedWithinBudget() {
        HypothesisRefinementLoop loop = new HypothesisRefinementLoop(NO_COUNTEREXAMPLE);
        HypothesisRefinementLoop.RefinementOutcome outcome =
            loop.refine(hypothesis("hyp-1", "x + 0", "x"));

        assertTrue(outcome.isAccepted());
        assertFalse(outcome.isRejected());
        assertFalse(outcome.isInconclusive());
        assertEquals(HypothesisRevisionStatus.VALIDATED_WITHIN_BUDGET, outcome.terminalRevision().status());
        assertEquals(1, outcome.revisionHistory().size());
    }

    @Test
    void inconclusiveResultsInInconclusiveOutcome() {
        HypothesisRefinementLoop loop = new HypothesisRefinementLoop(INCONCLUSIVE_SERVICE);
        HypothesisRefinementLoop.RefinementOutcome outcome =
            loop.refine(hypothesis("hyp-2", "x", "x"));

        assertFalse(outcome.isAccepted());
        assertFalse(outcome.isRejected());
        assertTrue(outcome.isInconclusive());
        assertEquals(HypothesisRevisionStatus.INCONCLUSIVE, outcome.terminalRevision().status());
    }

    @Test
    void counterexampleWithNoStrategyResultsInRejected() {
        // Use no strategies so the loop cannot refine
        HypothesisRefinementLoop loop = new HypothesisRefinementLoop(
            ALWAYS_COUNTEREXAMPLE, List.of(), 3
        );
        HypothesisRefinementLoop.RefinementOutcome outcome =
            loop.refine(hypothesis("hyp-3", "x", "x + 1"));

        assertTrue(outcome.isRejected());
        assertEquals(HypothesisRevisionStatus.REJECTED, outcome.terminalRevision().status());
    }

    @Test
    void counterexampleWithRefinementProducesLinkedRevisionChain() {
        HypothesisRefinementLoop loop = new HypothesisRefinementLoop(DIVISION_BY_ZERO_SERVICE);
        HypothesisRefinementLoop.RefinementOutcome outcome =
            loop.refine(hypothesis("hyp-4", "a / b", "a * (1/b)"));

        // Should have been refined (b != 0 added) and then accepted
        assertTrue(outcome.isAccepted());
        assertTrue(outcome.revisionHistory().size() >= 2,
            "revision history should have at least initial + refined revision");

        // The accepted revision should have the non-zero denominator constraint
        assertTrue(outcome.terminalRevision().assumptions().contains("b != 0"),
            "terminal revision must contain the non-zero denominator constraint");

        // The accepted proposal should be available
        assertTrue(outcome.acceptedProposal().isPresent());
        assertTrue(outcome.acceptedProposal().get().newAssumptions().contains("b != 0"));
    }

    @Test
    void revisionHistoryLinksParentIds() {
        HypothesisRefinementLoop loop = new HypothesisRefinementLoop(DIVISION_BY_ZERO_SERVICE);
        HypothesisRefinementLoop.RefinementOutcome outcome =
            loop.refine(hypothesis("hyp-5", "a / b", "a * (1/b)"));

        List<HypothesisRevision> history = outcome.revisionHistory();
        assertTrue(history.size() >= 2);

        // First revision has no parent
        assertNull(history.get(0).parentId());

        // Second revision's parentId matches first revision's id
        if (history.size() >= 2) {
            assertEquals(history.get(0).id(), history.get(1).parentId());
        }
    }

    @Test
    void budgetExhaustionResultsInRejection() {
        // Always returns a counterexample; no strategy helps
        HypothesisRefinementLoop loop = new HypothesisRefinementLoop(
            ALWAYS_COUNTEREXAMPLE, List.of(), 2
        );
        HypothesisRefinementLoop.RefinementOutcome outcome =
            loop.refine(hypothesis("hyp-6", "x", "x + 1"));

        assertTrue(outcome.isRejected());
    }

    @Test
    void cycleDetectionPreventsInfiniteLoop() {
        // Strategy always returns the same refinement (same patterns, same assumptions)
        // → should trigger cycle detection
        RefinementStrategy sameRefinementStrategy = new RefinementStrategy() {
            @Override
            public String name() {
                return "test-no-change";
            }

            @Override
            public Optional<RefinementProposal> refine(
                HypothesisRevision revision,
                CounterexampleSearchService.CounterexampleSearchResult counterexampleResult
            ) {
                // Return a proposal that adds an assumption — but next iteration it's already there
                if (revision.assumptions().contains("x > 0")) {
                    // Same assumptions, same patterns = cycle
                    return Optional.of(new RefinementProposal(
                        revision.leftPattern(), revision.rightPattern(), revision.assumptions()
                    ));
                }
                return Optional.of(new RefinementProposal(
                    revision.leftPattern(), revision.rightPattern(), List.of("x > 0")
                ));
            }
        };

        // The first refinement adds x > 0. The second would produce the same fingerprint.
        // ALWAYS_COUNTEREXAMPLE will keep finding counterexamples.
        HypothesisRefinementLoop loop = new HypothesisRefinementLoop(
            ALWAYS_COUNTEREXAMPLE, List.of(sameRefinementStrategy), 10
        );
        HypothesisRefinementLoop.RefinementOutcome outcome =
            loop.refine(hypothesis("hyp-cycle", "x", "x + 1"));

        // Should eventually be rejected (cycle detected or budget exhausted)
        assertTrue(outcome.isRejected(),
            "cycle or budget should result in rejection, not an infinite loop");
    }

    @Test
    void cycleDetectionRejectsImmediateRepeatOfInitialFingerprint() {
        RefinementStrategy sameFingerprintStrategy = new RefinementStrategy() {
            @Override
            public String name() {
                return "same-fingerprint";
            }

            @Override
            public Optional<RefinementProposal> refine(
                HypothesisRevision revision,
                CounterexampleSearchService.CounterexampleSearchResult counterexampleResult
            ) {
                return Optional.of(new RefinementProposal(
                    revision.leftPattern(), revision.rightPattern(), revision.assumptions()
                ));
            }
        };

        HypothesisRefinementLoop loop = new HypothesisRefinementLoop(
            ALWAYS_COUNTEREXAMPLE, List.of(sameFingerprintStrategy), 10
        );
        HypothesisRefinementLoop.RefinementOutcome outcome =
            loop.refine(hypothesis("hyp-immediate-cycle", "x", "x + 1"));

        assertTrue(outcome.isRejected());
        assertEquals(2, outcome.revisionHistory().size(),
            "the first repeated fingerprint should be rejected immediately");
    }

    @Test
    void revisionIndexIncreasesMonotonically() {
        HypothesisRefinementLoop loop = new HypothesisRefinementLoop(DIVISION_BY_ZERO_SERVICE);
        HypothesisRefinementLoop.RefinementOutcome outcome =
            loop.refine(hypothesis("hyp-mono", "a / b", "a * (1/b)"));

        List<HypothesisRevision> history = outcome.revisionHistory();
        for (int i = 0; i < history.size(); i++) {
            assertEquals(i, history.get(i).revisionIndex(),
                "revisionIndex must equal its position in history");
        }
    }

    @Test
    void noCounterexampleRevisionStatusTransition() {
        HypothesisRefinementLoop loop = new HypothesisRefinementLoop(NO_COUNTEREXAMPLE);
        HypothesisRefinementLoop.RefinementOutcome outcome =
            loop.refine(hypothesis("hyp-status", "x + 0", "x"));

        HypothesisRevision r = outcome.revisionHistory().getFirst();
        // Should have gone PROPOSED → CHALLENGED → VALIDATED_WITHIN_BUDGET
        // (history shows final state, which is VALIDATED_WITHIN_BUDGET)
        assertEquals(HypothesisRevisionStatus.VALIDATED_WITHIN_BUDGET, r.status());
    }

    @Test
    void allRevisionStatusesAreTerminalOrIntermediate() {
        HypothesisRefinementLoop loop = new HypothesisRefinementLoop(DIVISION_BY_ZERO_SERVICE);
        HypothesisRefinementLoop.RefinementOutcome outcome =
            loop.refine(hypothesis("hyp-terminal", "a / b", "a * (1/b)"));

        List<HypothesisRevision> history = outcome.revisionHistory();
        // Only the last revision should be terminal
        for (int i = 0; i < history.size() - 1; i++) {
            assertFalse(history.get(i).status().isTerminal(),
                "intermediate revision at index " + i + " must not have a terminal status");
        }
        assertTrue(history.getLast().status().isTerminal(),
            "last revision must have a terminal status");
    }
}
