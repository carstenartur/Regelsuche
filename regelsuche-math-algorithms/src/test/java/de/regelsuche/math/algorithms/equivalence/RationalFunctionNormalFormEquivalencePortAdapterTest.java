package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.AssumptionAwareEquivalenceService;
import java.util.List;
import org.junit.jupiter.api.Test;

class RationalFunctionNormalFormEquivalencePortAdapterTest {
    private final AssumptionAwareEquivalenceService service =
        new RationalFunctionNormalFormEquivalencePortAdapter();

    @Test
    void preservesConfirmedNormalFormsAndAssumptionEvidence() {
        var evaluation = service.evaluate(
            "(x^2-1)/(x-1)",
            "x+1",
            List.of("x != 1"));

        assertEquals(
            AssumptionAwareEquivalenceService.Status.CONFIRMED,
            evaluation.status());
        assertTrue(evaluation.equivalent());
        assertFalse(evaluation.leftNormalForm().isBlank());
        assertEquals(
            evaluation.leftNormalForm(),
            evaluation.rightNormalForm());
        assertTrue(evaluation.missingAssumptions().isEmpty());
    }

    @Test
    void preservesMissingAssumptionAndRefutationStatuses() {
        var missing = service.evaluate(
            "(x*y)/(x*z)",
            "y/z",
            List.of("z != 0"));
        var refuted = service.evaluate(
            "(x+1)/x",
            "1",
            List.of("x != 0"));

        assertEquals(
            AssumptionAwareEquivalenceService.Status.MISSING_ASSUMPTION,
            missing.status());
        assertFalse(missing.equivalent());
        assertTrue(missing.missingAssumptions().stream()
            .anyMatch(value -> value.contains("x")));
        assertEquals(
            AssumptionAwareEquivalenceService.Status.REFUTED,
            refuted.status());
        assertFalse(refuted.equivalent());
    }
}
