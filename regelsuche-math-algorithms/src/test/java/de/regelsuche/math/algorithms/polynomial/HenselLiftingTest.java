package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HenselLiftingTest {
    private static final int CANDIDATE_BUDGET = 32;

    private final PolynomialRing<BigInteger> integerRing =
        new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    private final FactorizationRequest.StructuralLimits limits =
        new FactorizationRequest.StructuralLimits(
            1,
            32,
            64,
            4_096);
    private final FiniteFieldFactorizationPolicy finiteFieldPolicy =
        FiniteFieldFactorizationPolicy.deterministicBerlekamp(
            101,
            1_000_000);

    @Test
    void liftsANonmonicTwoFactorSourceFromF5To125() {
        SparsePolynomial<BigInteger> source = integer(5, 2, 2);
        FactorizationRequest<BigInteger> request =
            request(source, 1_000_000);
        SuitablePrimeSelectionResult selection = select(
            request,
            2,
            3,
            5,
            7);

        HenselLiftingResult result = HenselLifting.lift(
            request,
            selection,
            policy(3));

        assertTrue(result.completed(), result.toString());
        assertEquals(BigInteger.valueOf(125), result.targetModulus());
        assertEquals(2, result.steps().size());
        assertEquals(2, result.factors().size());
        assertEquals(
            source.leadingCoefficient(),
            result.factors().getFirst().leadingCoefficient());
        assertEquals(
            BigInteger.ONE,
            result.factors().getLast().leadingCoefficient());
        assertCongruent(
            source,
            result.factors(),
            result.targetModulus());
        assertTrue(result.work().totalWorkUnits()
            > selection.work().totalWorkUnits());
    }

    @Test
    void liftsThreePairwiseCoprimeFactorsWithMixedDegrees() {
        SparsePolynomial<BigInteger> source = integer(-2, 0, 1, 0, 1);
        FactorizationRequest<BigInteger> request =
            request(source, 2_000_000);
        SuitablePrimeSelectionResult selection = select(request, 5);

        HenselLiftingResult result = HenselLifting.lift(
            request,
            selection,
            policy(4));

        assertTrue(result.completed(), result.toString());
        assertEquals(3, result.factors().size());
        assertEquals(3, result.steps().size());
        assertEquals(BigInteger.valueOf(625), result.targetModulus());
        assertCongruent(
            source,
            result.factors(),
            result.targetModulus());
        assertTrue(result.steps().stream().allMatch(step ->
            step.correctionPolynomialHashes().size() == 3
                && step.workUnits() > 0));
    }

    @Test
    void liftsOneIrreducibleModularFactorWithoutASpecialCaseClaim() {
        SparsePolynomial<BigInteger> source = integer(1, 0, 1);
        FactorizationRequest<BigInteger> request =
            request(source, 1_000_000);
        SuitablePrimeSelectionResult selection = select(request, 3);

        HenselLiftingResult result = HenselLifting.lift(
            request,
            selection,
            policy(3));

        assertTrue(result.completed(), result.toString());
        assertEquals(1, result.factors().size());
        assertEquals(2, result.steps().size());
        assertCongruent(
            source,
            result.factors(),
            BigInteger.valueOf(27));
    }

    @Test
    void targetExponentOneRetainsTheVerifiedModularStartingPoint() {
        SparsePolynomial<BigInteger> source = integer(5, 2, 2);
        FactorizationRequest<BigInteger> request =
            request(source, 1_000_000);
        SuitablePrimeSelectionResult selection = select(request, 5);

        HenselLiftingResult result = HenselLifting.lift(
            request,
            selection,
            policy(1));

        assertTrue(result.completed(), result.toString());
        assertEquals(BigInteger.valueOf(5), result.targetModulus());
        assertTrue(result.steps().isEmpty());
        assertCongruent(source, result.factors(), BigInteger.valueOf(5));
    }

    @Test
    void explicitModulusAndCoefficientPoliciesFailInconclusively() {
        SparsePolynomial<BigInteger> source = integer(5, 2, 2);
        FactorizationRequest<BigInteger> request =
            request(source, 1_000_000);
        SuitablePrimeSelectionResult selection = select(request, 5);

        HenselLiftingResult modulus = HenselLifting.lift(
            request,
            selection,
            HenselLiftingPolicy.linearMultifactor(5, 3, 4_096));
        HenselLiftingResult coefficients = HenselLifting.lift(
            request,
            selection,
            HenselLiftingPolicy.linearMultifactor(2, 64, 1));

        assertEquals(
            HenselLiftingResult.Status.BUDGET_INCONCLUSIVE,
            modulus.status());
        assertEquals(
            "HENSEL_MODULUS_BIT_LENGTH_POLICY_EXCEEDED",
            modulus.detailCode());
        assertEquals(
            HenselLiftingResult.Status.BUDGET_INCONCLUSIVE,
            coefficients.status());
        assertEquals(
            "HENSEL_INTERMEDIATE_COEFFICIENT_BIT_LENGTH_EXCEEDED",
            coefficients.detailCode());
    }

    @Test
    void selectionWorkIsResumedAndCannotBeReset() {
        SparsePolynomial<BigInteger> source = integer(5, 2, 2);
        FactorizationRequest<BigInteger> calibrationRequest =
            request(source, 1_000_000);
        SuitablePrimeSelectionResult calibrationSelection = select(
            calibrationRequest,
            5);
        long selectionWork =
            calibrationSelection.work().totalWorkUnits();

        FactorizationRequest<BigInteger> bounded = request(
            source,
            selectionWork + 1);
        SuitablePrimeSelectionResult boundedSelection = select(bounded, 5);
        HenselLiftingResult exhausted = HenselLifting.lift(
            bounded,
            boundedSelection,
            policy(2));
        HenselLiftingResult resetAttempt = HenselLifting.lift(
            calibrationRequest,
            calibrationSelection,
            policy(2),
            new PolynomialWorkBudget(
                calibrationRequest.maxWorkUnits()));

        assertEquals(
            HenselLiftingResult.Status.BUDGET_INCONCLUSIVE,
            exhausted.status());
        assertEquals(
            "HENSEL_LIFTING_WORK_BUDGET_EXCEEDED",
            exhausted.detailCode());
        assertEquals(
            HenselLiftingResult.Status.TECHNICAL_FAILURE,
            resetAttempt.status());
        assertEquals(
            "HENSEL_WORK_BUDGET_AUTHORITY_MISMATCH",
            resetAttempt.detailCode());
    }

    @Test
    void aSelectionForAnotherIntegerSourceFailsClosed() {
        FactorizationRequest<BigInteger> selectedRequest = request(
            integer(5, 2, 2),
            1_000_000);
        SuitablePrimeSelectionResult selection = select(
            selectedRequest,
            5);
        FactorizationRequest<BigInteger> otherRequest = request(
            integer(10, 2, 2),
            1_000_000);

        HenselLiftingResult result = HenselLifting.lift(
            otherRequest,
            selection,
            policy(2));

        assertEquals(
            HenselLiftingResult.Status.TECHNICAL_FAILURE,
            result.status());
        assertEquals(
            "HENSEL_SELECTION_SOURCE_MISMATCH",
            result.detailCode());
    }

    @Test
    void policyBoundsAndResultTypesAreIssuerOwned() {
        assertThrows(IllegalArgumentException.class, () ->
            policy(0));
        assertThrows(IllegalArgumentException.class, () ->
            HenselLiftingPolicy.linearMultifactor(2, 0, 64));
        assertThrows(IllegalArgumentException.class, () ->
            HenselLiftingPolicy.linearMultifactor(2, 64, 0));
        assertEquals(0, HenselLiftingResult.class
            .getConstructors().length);
        assertEquals(0, HenselLiftStep.class
            .getConstructors().length);
    }

    private SuitablePrimeSelectionResult select(
        FactorizationRequest<BigInteger> request,
        Integer... primes
    ) {
        SuitablePrimeSelectionPolicy policy =
            new SuitablePrimeSelectionPolicy(
                SuitablePrimeSelectionPolicy.Algorithm
                    .DETERMINISTIC_ASCENDING_PRIMES_V1,
                List.of(primes),
                finiteFieldPolicy);
        SuitablePrimeSelectionResult result =
            SuitablePrimeSelection.selectAndFactor(request, policy);
        assertTrue(result.completed(), result.toString());
        return result;
    }

    private static HenselLiftingPolicy policy(int targetExponent) {
        return HenselLiftingPolicy.linearMultifactor(
            targetExponent,
            4_096,
            16_384);
    }

    private FactorizationRequest<BigInteger> request(
        SparsePolynomial<BigInteger> source,
        long maximumWork
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            limits,
            CANDIDATE_BUDGET,
            maximumWork);
    }

    private SparsePolynomial<BigInteger> integer(long... coefficients) {
        return UnivariatePolynomialView.of(
            integerRing,
            Arrays.stream(coefficients)
                .mapToObj(BigInteger::valueOf)
                .toList())
            .toSparsePolynomial();
    }

    private static void assertCongruent(
        SparsePolynomial<BigInteger> source,
        List<SparsePolynomial<BigInteger>> factors,
        BigInteger modulus
    ) {
        SparsePolynomial<BigInteger> product =
            SparsePolynomial.one(source.ring());
        for (SparsePolynomial<BigInteger> factor : factors) {
            product = product.multiply(factor);
        }
        UnivariatePolynomialView<BigInteger> left =
            UnivariatePolynomialView.from(source);
        UnivariatePolynomialView<BigInteger> right =
            UnivariatePolynomialView.from(product);
        int count = Math.max(
            left.coefficientCount(),
            right.coefficientCount());
        for (int exponent = 0; exponent < count; exponent++) {
            assertEquals(
                BigInteger.ZERO,
                left.coefficient(exponent)
                    .subtract(right.coefficient(exponent))
                    .remainder(modulus));
        }
    }
}
