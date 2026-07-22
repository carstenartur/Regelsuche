package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RationalFunctionNormalFormEquivalenceServiceTest {
    private final RationalFunctionNormalFormEquivalenceService service =
        new RationalFunctionNormalFormEquivalenceService();

    @Test
    void confirmsAllFrozenRationalIdentityFamiliesUnderDeclaredPoles() {
        assertConfirmed("(7*z)/z", "7", List.of("z != 0"));
        assertConfirmed(
            "((z+5)*(z-7))/(z+5)",
            "z-7",
            List.of("z != -5"));
        assertConfirmed(
            "(x^2-1)/(x-1)",
            "x+1",
            List.of("x != 1"));
        assertConfirmed(
            "1/(x*(x+1))",
            "1/x-1/(x+1)",
            List.of("x != 0", "x != -1"));
        assertConfirmed(
            "(1/(x+1))/(1/(x-1))",
            "(x-1)/(x+1)",
            List.of("x != -1", "x != 1"));
        assertConfirmed(
            "(x^2-a^2)/(x-a)",
            "x+a",
            List.of("x != a"));
    }

    @Test
    void normalizesEquivalentNonzeroConditionsUpToPolynomialScale() {
        var positive = service.evaluate(
            "((x+3)*(x-2))/(x+3)",
            "x-2",
            List.of("x != -3"));
        var signReversed = service.evaluate(
            "((x+3)*(x-2))/(x+3)",
            "x-2",
            List.of("-3 != x"));

        assertEquals(
            RationalFunctionNormalFormEquivalenceService.Status.CONFIRMED,
            positive.status());
        assertEquals(positive.requiredNonZeroFactors(),
            signReversed.requiredNonZeroFactors());
        assertEquals(positive.providedNonZeroFactors(),
            signReversed.providedNonZeroFactors());
    }

    @Test
    void missingCancelledFactorConditionFailsClosed() {
        var evaluation = service.evaluate(
            "(x*y)/(x*z)",
            "y/z",
            List.of("z != 0"));

        assertEquals(
            RationalFunctionNormalFormEquivalenceService.Status.MISSING_ASSUMPTION,
            evaluation.status());
        assertFalse(evaluation.equivalent());
        assertEquals(1, evaluation.missingNonZeroFactors().size());
        assertTrue(evaluation.missingNonZeroFactors().getFirst().contains("x"));
    }

    @Test
    void differingCrossProductsProduceARefutation() {
        var evaluation = service.evaluate(
            "(x+1)/x",
            "1",
            List.of("x != 0"));

        assertEquals(
            RationalFunctionNormalFormEquivalenceService.Status.REFUTED,
            evaluation.status());
        assertFalse(evaluation.equivalent());
        assertFalse(evaluation.leftCrossNormalForm()
            .equals(evaluation.rightCrossNormalForm()));
    }

    @Test
    void unsupportedFunctionsAndZeroDivisorsFailClosed() {
        assertEquals(
            RationalFunctionNormalFormEquivalenceService.Status.UNSUPPORTED,
            service.evaluate("sin(x)/x", "1", List.of("x != 0")).status());
        assertEquals(
            RationalFunctionNormalFormEquivalenceService.Status.UNSUPPORTED,
            service.evaluate("x/0", "x", List.of()).status());
    }

    @Test
    void unsupportedAssumptionSyntaxIsNotIgnored() {
        var evaluation = service.evaluate(
            "x/y",
            "x/y",
            List.of("y is nonzero"));

        assertEquals(
            RationalFunctionNormalFormEquivalenceService.Status.UNSUPPORTED,
            evaluation.status());
        assertEquals(List.of("y is nonzero"),
            evaluation.unsupportedAssumptions());
    }

    private void assertConfirmed(
        String left,
        String right,
        List<String> assumptions
    ) {
        var evaluation = service.evaluate(left, right, assumptions);
        assertEquals(
            RationalFunctionNormalFormEquivalenceService.Status.CONFIRMED,
            evaluation.status(),
            evaluation.detail() + " required="
                + evaluation.requiredNonZeroFactors()
                + " provided=" + evaluation.providedNonZeroFactors()
                + " missing=" + evaluation.missingNonZeroFactors());
        assertTrue(evaluation.equivalent());
        assertTrue(evaluation.missingNonZeroFactors().isEmpty());
    }
}
