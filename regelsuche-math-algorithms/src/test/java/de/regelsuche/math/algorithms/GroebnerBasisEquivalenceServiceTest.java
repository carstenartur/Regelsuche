package de.regelsuche.math.algorithms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.GroebnerBasisEquivalenceService;
import de.regelsuche.math.algorithms.equivalence.PolynomialDiscoveryPack;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroebnerBasisEquivalenceServiceTest {
    @Test
    void reducesPolynomialModuloSmallIdeal() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE, false,
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true,
                MathematicalAlgorithmRegistry.JAS_BACKEND, false
            ), Map.of())
        );

        assertTrue(service.reducesToZeroModuloIdeal("x^2 - 1", List.of("x - 1")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS, service.lastResult().status());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.PROOF, service.lastResult().resultType());
        assertEquals(MathematicalAlgorithmRegistry.GROEBNER_BASIS, service.lastResult().payload().get("capability"));
    }

    @Test
    void provesIdealMembershipWithMultipleGenerators() {
        GroebnerBasisEquivalenceService service = enabledService();

        assertTrue(service.reducesToZeroModuloIdeal("x*y + x", List.of("x", "y - 1")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS, service.lastResult().status());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.PROOF, service.lastResult().resultType());
        assertEquals("pureJavaSmallGroebner", service.lastResult().payload().get("backend"));
    }

    @Test
    void groebnerDisabledPreventsIdealReduction() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, false
            ), Map.of())
        );

        assertFalse(service.reducesToZeroModuloIdeal("x^2 - 1", List.of("x - 1")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.DISABLED, service.lastResult().status());
    }

    @Test
    void jasBackendRequestedButUnavailableDoesNotFallbackToNormalForm() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true,
                MathematicalAlgorithmRegistry.JAS_BACKEND, true
            ), Map.of()),
            false
        );

        assertFalse(service.reducesToZeroModuloIdeal("x^2 - 1", List.of("x - 1")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNAVAILABLE, service.lastResult().status());
    }

    @Test
    void payloadReportsRequestedAndActualBackend() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true,
                MathematicalAlgorithmRegistry.JAS_BACKEND, true
            ), Map.of()),
            true
        );

        assertTrue(service.reducesToZeroModuloIdeal("x^2 - 1", List.of("x - 1")));
        assertEquals("jas", service.lastResult().payload().get("requestedBackend"));
        assertEquals("pureJavaSmallGroebner", service.lastResult().payload().get("backend"));
    }

    @Test
    void nonMemberHasSeparateGroebnerRefutationMetadata() {
        GroebnerBasisEquivalenceService service = enabledService();

        assertFalse(service.reducesToZeroModuloIdeal("x", List.of("x^2")));
        assertEquals(MathematicalAlgorithmRegistry.ResultType.REFUTATION, service.lastResult().resultType());
        assertEquals(MathematicalAlgorithmRegistry.GROEBNER_BASIS, service.lastResult().payload().get("capability"));
        assertEquals("x", service.lastResult().payload().get("remainder"));
    }

    @Test
    void budgetExhaustedStopsBasisComputation() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(
                Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS, true),
                Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS,
                    MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(0, 1, 0, 0.0))
            )
        );

        assertFalse(service.reducesToZeroModuloIdeal("x*y", List.of("x", "y")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED, service.lastResult().status());
    }

    @Test
    void budgetAlsoAppliesToFinalReductionAfterBasisConstruction() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(
                Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS, true),
                Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS,
                    MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(1, 10, 0, 0.0))
            )
        );

        assertFalse(service.reducesToZeroModuloIdeal("x + y", List.of("x")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED, service.lastResult().status());
        assertEquals("BUDGET_EXHAUSTED", service.lastResult().payload().get("budgetStatus"));
    }

    @Test
    void unsupportedPolynomialDomainStaysUnknown() {
        GroebnerBasisEquivalenceService service = enabledService();

        assertFalse(service.reducesToZeroModuloIdeal("sin(x)", List.of("x")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN, service.lastResult().status());
    }

    @Test
    void provesSpecifiedIdealMembershipExample() {
        GroebnerBasisEquivalenceService service = enabledService();

        assertTrue(service.reducesToZeroModuloIdeal("x^4 - x", List.of("x^2 - y", "y^2 - x")));
        assertEquals("0", service.lastResult().payload().get("remainder"));
        assertEquals("gradedReverseLex", service.lastResult().payload().get("monomialOrder"));
        assertEquals("OK", service.lastResult().payload().get("budgetStatus"));
    }

    @Test
    void specifiedNonMemberHasNonZeroRemainder() {
        GroebnerBasisEquivalenceService service = enabledService();

        assertFalse(service.reducesToZeroModuloIdeal("x + y", List.of("x*y - 1")));
        assertEquals(MathematicalAlgorithmRegistry.ResultType.REFUTATION, service.lastResult().resultType());
        assertFalse("0".equals(service.lastResult().payload().get("remainder")));
    }

    @Test
    void reducesDerivedConsequenceOfSimpleSystem() {
        GroebnerBasisEquivalenceService service = enabledService();

        assertTrue(service.reducesToZeroModuloIdeal("2*y - 1", List.of("x + y - 1", "x - y")));
        assertEquals(MathematicalAlgorithmRegistry.ResultType.PROOF, service.lastResult().resultType());
    }

    @Test
    void deterministicBasisAndRemainderPayload() {
        GroebnerBasisEquivalenceService first = enabledService();
        GroebnerBasisEquivalenceService second = enabledService();

        assertFalse(first.reducesToZeroModuloIdeal("x + y", List.of("x*y - 1")));
        assertFalse(second.reducesToZeroModuloIdeal("x + y", List.of("x*y - 1")));

        assertEquals(first.lastResult().payload().get("basis"), second.lastResult().payload().get("basis"));
        assertEquals(first.lastResult().payload().get("remainder"), second.lastResult().payload().get("remainder"));
        assertEquals(first.lastResult().payload().get("steps"), second.lastResult().payload().get("steps"));
    }

    @Test
    void standardizedPayloadIncludesReducedBasisLimitsAndUnsupportedReason() {
        GroebnerBasisEquivalenceService service = enabledService();

        assertTrue(service.reducesToZeroModuloIdeal("x^2 - 1", List.of("x - 1")));

        assertTrue(service.lastResult().payload().containsKey("reducedBasis"));
        assertEquals("", service.lastResult().payload().get("unsupportedReason"));
        assertEquals(256, service.lastResult().payload().get("maxTerms"));
        assertEquals(20, service.lastResult().payload().get("maxDegree"));
        assertEquals(8, service.lastResult().payload().get("maxVariables"));
        assertEquals(2_000, service.lastResult().payload().get("maxPairs"));
        assertEquals(0, service.lastResult().payload().get("timeoutMillis"));
    }

    @Test
    void unsupportedExpressionsCarryMachineReadableReason() {
        GroebnerBasisEquivalenceService service = enabledService();

        assertFalse(service.reducesToZeroModuloIdeal("sin(x)", List.of("x")));

        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN, service.lastResult().status());
        assertEquals("unsupported-polynomial-syntax", service.lastResult().payload().get("unsupportedReason"));
    }

    @Test
    void hardLimitsRejectOversizedPolynomialDomains() {
        GroebnerBasisEquivalenceService service = enabledService(
            MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(200, 2_000, 0, 0.0, 256, 20, 8));

        assertFalse(service.reducesToZeroModuloIdeal("a + b + c + d + e + f + g + h + i", List.of("a")));

        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN, service.lastResult().status());
        assertEquals("maxVariables", service.lastResult().payload().get("unsupportedReason"));
    }

    @Test
    void configuredGroebnerLimitsAreEnforcedAndVisibleInPayload() {
        GroebnerBasisEquivalenceService service = enabledService(
            MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(200, 2_000, 0, 0.0, 4, 2, 3));

        assertFalse(service.reducesToZeroModuloIdeal("x^3", List.of("x")));

        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN, service.lastResult().status());
        assertEquals("maxDegree", service.lastResult().payload().get("unsupportedReason"));
        assertEquals(4, service.lastResult().payload().get("maxTerms"));
        assertEquals(2, service.lastResult().payload().get("maxDegree"));
        assertEquals(3, service.lastResult().payload().get("maxVariables"));
    }

    @Test
    void polynomialDiscoveryPackExercisesProofsAndCounterexampleTraps() {
        List<PolynomialDiscoveryPack.EvaluationResult> results = new ArrayList<>();

        for (PolynomialDiscoveryPack.Example example : PolynomialDiscoveryPack.examples()) {
            GroebnerBasisEquivalenceService service = example.budget() == null ? enabledService() : enabledService(example.budget());
            boolean actualMember = service.reducesToZeroModuloIdeal(example.polynomial(), example.generators());
            assertEquals(
                example.expectedMember(),
                actualMember,
                example.id()
            );
            assertEquals(example.expectedStatus(), service.lastResult().status(), example.id());
            results.add(new PolynomialDiscoveryPack.EvaluationResult(
                example,
                actualMember,
                service.lastResult().detail(),
                service.lastResult().status()
            ));
            if (service.lastResult().status() == MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS) {
                assertEquals("pureJavaSmallGroebner", service.lastResult().payload().get("backend"));
                assertTrue(service.lastResult().payload().containsKey("basis"), example.id());
                assertTrue(service.lastResult().payload().containsKey("remainder"), example.id());
            }
        }

        String report = PolynomialDiscoveryPack.renderReportJson(results);
        assertTrue(report.contains("\"expectedMember\""));
        assertTrue(report.contains("\"actualResult\""));
        assertTrue(report.contains("\"cubic-factorization\""));
        assertTrue(report.contains("\"budget-limit\""));
    }

    @Test
    void unsupportedDivisionRadicalsAndTrigStayUnknown() {
        GroebnerBasisEquivalenceService service = enabledService();

        assertFalse(service.reducesToZeroModuloIdeal("x / y", List.of("x")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN, service.lastResult().status());

        assertFalse(service.reducesToZeroModuloIdeal("sqrt(x)", List.of("x")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN, service.lastResult().status());

        assertFalse(service.reducesToZeroModuloIdeal("sin(x)", List.of("x")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN, service.lastResult().status());
    }

    @Test
    void exactRationalCoefficientsAreReducedWithoutDecimalArithmetic() {
        GroebnerBasisEquivalenceService service = enabledService();

        assertEquals(new Rational(BigInteger.ONE, BigInteger.valueOf(2)), Rational.fromDouble(0.5));
        assertTrue(service.reducesToZeroModuloIdeal("0.5*x + 0.5*x - 1", List.of("x - 1")));
        assertTrue(service.reducesToZeroModuloIdeal("0.3*x - 0.1*x - 0.2", List.of("x - 1")));
    }

    private GroebnerBasisEquivalenceService enabledService() {
        return enabledService(null);
    }

    private GroebnerBasisEquivalenceService enabledService(MathematicalAlgorithmRegistry.AlgorithmBudget budget) {
        return new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true
            ), budget == null ? Map.of() : Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS, budget))
        );
    }
}
