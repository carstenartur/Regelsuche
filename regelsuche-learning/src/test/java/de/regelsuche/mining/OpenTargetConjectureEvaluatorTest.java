package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationPlan;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldout;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldout;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.validation.CounterexampleSearchService.CounterexampleBudget;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenTargetConjectureEvaluatorTest {
    private final OpenTargetConjectureEvaluator evaluator =
        new OpenTargetConjectureEvaluator();

    @Test
    void compilesAndAcceptsDistributiveFactoringForTheProofQueue() {
        OpenTargetConjecture conjecture = conjecture(
            "open-target-factor-common",
            "A * B + A * C",
            "A * (B + C)");
        EvaluationPlan plan = new EvaluationPlan(
            "validation-v1",
            List.of(
                new PositiveHoldout("p-1", "m * 4 + m * 5", "m * (4 + 5)"),
                new PositiveHoldout(
                    "p-2",
                    "(u + v) * 2 + (u + v) * 7",
                    "(u + v) * (2 + 7)")),
            List.of(
                new NegativeHoldout("n-1", "m * 4 + n * 5"),
                new NegativeHoldout("n-2", "m * 4 - m * 5")),
            CounterexampleBudget.defaultBudget());

        var report = evaluator.evaluate(conjecture, plan);

        assertEquals(OpenTargetConjectureEvaluator.SCHEMA, report.schema());
        assertEquals(EvaluationStatus.ACCEPTED_FOR_PROOF, report.status());
        assertTrue(report.holdoutsComplete());
        assertTrue(report.allHoldoutsPassed());
        assertTrue(report.acceptedForProof());
        assertEquals(2, report.executedPositiveHoldouts());
        assertEquals(2, report.executedNegativeHoldouts());
        assertEquals("NO_COUNTEREXAMPLE_FOUND", report.counterexample().status());
        assertTrue(report.counterexample().attemptedSources().size() >= 2);
        assertTrue(report.blockers().isEmpty());
        assertFalse(report.dynamicRuleId().isBlank());
        assertFalse(report.provenanceHash().isBlank());
        assertEquals("NOT_EVALUATED", report.proofStatus());
        assertEquals("NOT_EVALUATED", report.noveltyStatus());
    }

    @Test
    void rejectsAnOvergeneralizedIdempotenceLawWithVisibleNumericCounterexample() {
        OpenTargetConjecture conjecture = conjecture(
            "open-target-false-idempotence",
            "A * A",
            "A");
        EvaluationPlan plan = new EvaluationPlan(
            "validation-v1",
            List.of(new PositiveHoldout("p-idempotent-one", "1 * 1", "1")),
            List.of(new NegativeHoldout("n-other-shape", "x + x")),
            CounterexampleBudget.defaultBudget());

        var report = evaluator.evaluate(conjecture, plan);

        assertEquals(EvaluationStatus.REJECTED, report.status());
        assertTrue(report.holdoutsComplete());
        assertTrue(report.allHoldoutsPassed(),
            "the counterexample search, not a missing holdout, should reject this candidate");
        assertEquals("COUNTEREXAMPLE_FOUND", report.counterexample().status());
        assertFalse(report.counterexample().assignments().isEmpty());
        assertTrue(report.blockers().contains("counterexample found"));
        assertFalse(report.acceptedForProof());
    }

    @Test
    void evaluationIsDeterministicAcrossHoldoutOrder() {
        OpenTargetConjecture conjecture = conjecture(
            "open-target-factor-deterministic",
            "A * B + A * C",
            "A * (B + C)");
        List<PositiveHoldout> positives = List.of(
            new PositiveHoldout("p-a", "q * 2 + q * 3", "q * (2 + 3)"),
            new PositiveHoldout("p-b", "r * 4 + r * 5", "r * (4 + 5)"));
        List<NegativeHoldout> negatives = List.of(
            new NegativeHoldout("n-a", "q * 2 + r * 3"),
            new NegativeHoldout("n-b", "q * 2 - q * 3"));

        var ordered = evaluator.evaluate(conjecture, new EvaluationPlan(
            "validation-v1", positives, negatives, CounterexampleBudget.defaultBudget()));
        var reversed = evaluator.evaluate(conjecture, new EvaluationPlan(
            "validation-v1",
            positives.reversed(),
            negatives.reversed(),
            CounterexampleBudget.defaultBudget()));

        assertEquals(ordered, reversed);
    }

    @Test
    void reportsSkippedHoldoutsInsteadOfVacuousSuccessAfterCompilationFailure() {
        OpenTargetConjecture conjecture = conjecture(
            "open-target-unparseable",
            "A +",
            "A");
        EvaluationPlan plan = new EvaluationPlan(
            "validation-v1",
            List.of(
                new PositiveHoldout("p-1", "x + 0", "x"),
                new PositiveHoldout("p-2", "y + 0", "y")),
            List.of(new NegativeHoldout("n-1", "x + 1")),
            CounterexampleBudget.defaultBudget());

        var report = evaluator.evaluate(conjecture, plan);

        assertEquals(EvaluationStatus.COMPILATION_REJECTED, report.status());
        assertEquals(2, report.configuredPositiveHoldouts());
        assertEquals(0, report.executedPositiveHoldouts());
        assertEquals(2, report.skippedPositiveHoldouts());
        assertEquals(1, report.configuredNegativeHoldouts());
        assertEquals(0, report.executedNegativeHoldouts());
        assertEquals(1, report.skippedNegativeHoldouts());
        assertFalse(report.holdoutsComplete());
        assertFalse(report.allHoldoutsPassed());
        assertEquals("NOT_RUN", report.counterexample().status());
        assertTrue(report.blockers().getFirst().startsWith("compilation rejected:"));
    }

    private static OpenTargetConjecture conjecture(
        String id,
        String leftPattern,
        String rightPattern
    ) {
        return new OpenTargetConjecture(
            id,
            leftPattern,
            rightPattern,
            2,
            2,
            List.of(),
            List.of("obs-1", "obs-2"),
            List.of(),
            List.of(),
            Map.of(),
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS");
    }
}
