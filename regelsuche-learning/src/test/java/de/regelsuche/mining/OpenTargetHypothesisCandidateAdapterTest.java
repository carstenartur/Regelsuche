package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.mining.OpenTargetConjectureEvaluator.CounterexampleEvidence;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldoutResult;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenTargetHypothesisCandidateAdapterTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-14T12:00:00Z");

    private final OpenTargetHypothesisCandidateAdapter adapter =
        new OpenTargetHypothesisCandidateAdapter();

    @Test
    void adaptsCompleteEvaluationWithoutPromotionOrInflatedProof() {
        HypothesisCandidate candidate = adapter.adapt(
            conjecture(false), acceptedEvaluation("open-target-factor-common", false), CREATED_AT);

        assertEquals("open-target-factor-common", candidate.id());
        assertEquals("A * B + A * C", candidate.leftPattern());
        assertEquals("A * (B + C)", candidate.rightPattern());
        assertEquals(List.of("path-a", "path-b", "path-c", "path-d"),
            candidate.supportingPaths());
        assertEquals(2, candidate.supportingExpressions().size());
        assertEquals(0.0, candidate.noveltyScore());
        assertEquals(CandidateProofStatus.VALIDATED_BY_EXAMPLES, candidate.proofStatus());
        assertEquals(Boolean.FALSE, candidate.counterexampleStatus());
        assertEquals(CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            candidate.counterexampleSearchStatus());
        assertEquals(List.of("boundary", "numeric"), candidate.counterexampleAttemptedSources());
        assertEquals(CREATED_AT, candidate.createdAt());
        assertFalse(candidate.proofStatus().atLeast(CandidateProofStatus.FORMALLY_PROVABLE));
    }

    @Test
    void resultIsDeterministicAcrossEvidencePathAndSourceOrder() {
        HypothesisCandidate ordered = adapter.adapt(
            conjecture(false), acceptedEvaluation("open-target-factor-common", false), CREATED_AT);
        HypothesisCandidate reversed = adapter.adapt(
            conjecture(true), acceptedEvaluation("open-target-factor-common", true), CREATED_AT);

        assertEquals(ordered, reversed);
    }

    @Test
    void rejectsIncompleteHoldoutAccounting() {
        EvaluationReport incomplete = new EvaluationReport(
            OpenTargetConjectureEvaluator.SCHEMA,
            "open-target-factor-common",
            EvaluationStatus.ACCEPTED_FOR_PROOF,
            "COMPILED",
            "dynamic-factor",
            "sha256:factor",
            2,
            1,
            1,
            2,
            2,
            0,
            List.of(positive("p-1")),
            List.of(negative("n-1"), negative("n-2")),
            counterexample(false),
            List.of(),
            "NOT_EVALUATED",
            "NOT_EVALUATED");

        assertThrows(IllegalArgumentException.class,
            () -> adapter.adapt(conjecture(false), incomplete, CREATED_AT));
    }

    @Test
    void rejectsMismatchedCandidateIdentity() {
        assertThrows(IllegalArgumentException.class, () -> adapter.adapt(
            conjecture(false), acceptedEvaluation("different-id", false), CREATED_AT));
    }

    @Test
    void rejectsVacuousCounterexampleEvidence() {
        EvaluationReport noSources = new EvaluationReport(
            OpenTargetConjectureEvaluator.SCHEMA,
            "open-target-factor-common",
            EvaluationStatus.ACCEPTED_FOR_PROOF,
            "COMPILED",
            "dynamic-factor",
            "sha256:factor",
            2,
            2,
            0,
            2,
            2,
            0,
            List.of(positive("p-1"), positive("p-2")),
            List.of(negative("n-1"), negative("n-2")),
            new CounterexampleEvidence(
                "NO_COUNTEREXAMPLE_FOUND", List.of(), List.of(), List.of(), "", "", "not run"),
            List.of(),
            "NOT_EVALUATED",
            "NOT_EVALUATED");

        assertThrows(IllegalArgumentException.class,
            () -> adapter.adapt(conjecture(false), noSources, CREATED_AT));
    }

    private static OpenTargetConjecture conjecture(boolean reversed) {
        ConvergenceEvidence first = evidence(
            "obs-1",
            "x * 2 + x * 3",
            "x * (2 + 3)",
            "alpha-1",
            List.of(path("path-b", "x * 2 + x * 3", "x * (2 + 3)"),
                path("path-a", "x * 2 + x * 3", "x * (2 + 3)")));
        ConvergenceEvidence second = evidence(
            "obs-2",
            "(u + v) * 4 + (u + v) * 5",
            "(u + v) * (4 + 5)",
            "alpha-2",
            List.of(path("path-d", "(u + v) * 4 + (u + v) * 5", "(u + v) * (4 + 5)"),
                path("path-c", "(u + v) * 4 + (u + v) * 5", "(u + v) * (4 + 5)")));
        List<ConvergenceEvidence> evidence = reversed
            ? List.of(second, first)
            : List.of(first, second);
        List<String> observationIds = reversed
            ? List.of("obs-2", "obs-1")
            : List.of("obs-1", "obs-2");
        return new OpenTargetConjecture(
            "open-target-factor-common",
            "A * B + A * C",
            "A * (B + C)",
            2,
            2,
            List.of("factor-common"),
            observationIds,
            evidence,
            List.of(),
            Map.of("A", List.of("x", "u + v")),
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS");
    }

    private static ConvergenceEvidence evidence(
        String observationId,
        String input,
        String output,
        String alphaFingerprint,
        List<PathEvidence> paths
    ) {
        return new ConvergenceEvidence(
            observationId,
            "factor-common",
            GoalStatus.UNTARGETED,
            input,
            output,
            "canonical-" + observationId,
            10,
            alphaFingerprint,
            "value-" + observationId,
            "factor-direct||factor-padded",
            paths);
    }

    private static PathEvidence path(String id, String input, String output) {
        return new PathEvidence(
            id,
            List.of(input, output),
            List.of("factor-" + id),
            List.of(),
            1,
            10);
    }

    private static EvaluationReport acceptedEvaluation(String id, boolean reversedSources) {
        List<String> sources = reversedSources
            ? List.of("numeric", "boundary")
            : List.of("boundary", "numeric");
        return new EvaluationReport(
            OpenTargetConjectureEvaluator.SCHEMA,
            id,
            EvaluationStatus.ACCEPTED_FOR_PROOF,
            "COMPILED",
            "dynamic-factor",
            "sha256:factor",
            2,
            2,
            0,
            2,
            2,
            0,
            List.of(positive("p-1"), positive("p-2")),
            List.of(negative("n-1"), negative("n-2")),
            new CounterexampleEvidence(
                "NO_COUNTEREXAMPLE_FOUND", sources, List.of(), List.of(), "", "",
                "no counterexample found"),
            List.of(),
            "NOT_EVALUATED",
            "NOT_EVALUATED");
    }

    private static CounterexampleEvidence counterexample(boolean reversedSources) {
        return new CounterexampleEvidence(
            "NO_COUNTEREXAMPLE_FOUND",
            reversedSources ? List.of("numeric", "boundary") : List.of("boundary", "numeric"),
            List.of(),
            List.of(),
            "",
            "",
            "no counterexample found");
    }

    private static PositiveHoldoutResult positive(String id) {
        return new PositiveHoldoutResult(id, 1, true, List.of("candidate-" + id));
    }

    private static NegativeHoldoutResult negative(String id) {
        return new NegativeHoldoutResult(id, 0, true, List.of());
    }
}
