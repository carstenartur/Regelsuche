package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SuitablePrimeSelectionTest {
    private static final long TEST_MATRIX_CELLS = 1_000_000L;

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
            TEST_MATRIX_CELLS);

    @Test
    void rejectsDegreeLossAndRepeatedReductionBeforeSelectingF5() {
        SparsePolynomial<BigInteger> source = integer(5, 2, 2);
        SuitablePrimeSelectionPolicy policy = policy(2, 3, 5, 7);

        SuitablePrimeSelectionResult result =
            SuitablePrimeSelection.selectAndFactor(
                request(source, limits, 1_000_000),
                policy);

        assertTrue(result.completed(), result.toString());
        assertEquals(5, result.selectedPrime());
        assertEquals(3, result.attempts().size());
        assertEquals(
            List.of(
                "LEADING_COEFFICIENT_VANISHES_MOD_PRIME",
                "MODULAR_REDUCTION_NOT_SQUARE_FREE",
                "SUITABLE_PRIME_SELECTED"),
            result.attempts().stream()
                .map(SuitablePrimeSelectionResult.PrimeAttempt::detailCode)
                .toList());
        assertEquals(
            List.of(
                SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                    .REJECTED,
                SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                    .REJECTED,
                SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                    .SELECTED),
            result.attempts().stream()
                .map(SuitablePrimeSelectionResult.PrimeAttempt::disposition)
                .toList());

        PolynomialRing<BigInteger> ring = result.modularSource().ring();
        SparsePolynomial<BigInteger> x = polynomial(ring, 0, 1);
        SparsePolynomial<BigInteger> xPlusOne = polynomial(ring, 1, 1);
        assertEquals(
            Set.of(x, xPlusOne),
            result.modularFactorization().factors().stream()
                .map(factor -> factor.polynomial())
                .collect(Collectors.toSet()));
        assertEquals(
            BigInteger.valueOf(2),
            result.modularFactorization().unit());
        assertTrue(result.attempts().stream()
            .allMatch(attempt -> attempt.workUnits() > 0));
        assertTrue(result.certificateHash().matches(
            "sha256:[0-9a-f]{64}"));
    }

    @Test
    void modularResidueGrowthUsesAnExplicitDerivedInternalLimit() {
        SparsePolynomial<BigInteger> source = integer(-1, 0, 1);
        FactorizationRequest.StructuralLimits oneBitSourceLimits =
            new FactorizationRequest.StructuralLimits(
                1,
                2,
                3,
                1);

        SuitablePrimeSelectionResult result =
            SuitablePrimeSelection.selectAndFactor(
                request(source, oneBitSourceLimits, 1_000_000),
                policy(3));

        assertTrue(result.completed(), result.toString());
        assertEquals(3, result.selectedPrime());
        assertEquals(2, result.modularSource().maxCoefficientBitLength());
        assertEquals(1, oneBitSourceLimits.maxCoefficientBitLength());
    }

    @Test
    void boundedNonSelectionRetainsEveryMathematicalRejection() {
        SparsePolynomial<BigInteger> source = integer(5, 2, 2);

        SuitablePrimeSelectionResult result =
            SuitablePrimeSelection.selectAndFactor(
                request(source, limits, 1_000_000),
                policy(2, 3));

        assertEquals(
            SuitablePrimeSelectionResult.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals(
            "NO_SUITABLE_PRIME_WITHIN_POLICY",
            result.detailCode());
        assertEquals(List.of(2, 3), result.attempts().stream()
            .map(SuitablePrimeSelectionResult.PrimeAttempt::prime)
            .toList());
        assertThrows(
            IllegalStateException.class,
            result::selectedPrime);
    }

    @Test
    void nestedFiniteFieldResourceFailureIsTerminalNotAPrimeRejection() {
        SparsePolynomial<BigInteger> source = integer(-1, 0, 1);
        FiniteFieldFactorizationPolicy rejectingFinitePolicy =
            FiniteFieldFactorizationPolicy.deterministicBerlekamp(
                101,
                1);
        SuitablePrimeSelectionPolicy policy =
            new SuitablePrimeSelectionPolicy(
                SuitablePrimeSelectionPolicy.Algorithm
                    .DETERMINISTIC_ASCENDING_PRIMES_V1,
                List.of(3),
                rejectingFinitePolicy);

        SuitablePrimeSelectionResult result =
            SuitablePrimeSelection.selectAndFactor(
                request(source, limits, 1_000_000),
                policy);

        assertEquals(
            SuitablePrimeSelectionResult.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals(
            "MODULAR_FACTORIZATION_INCONCLUSIVE",
            result.detailCode());
        assertEquals(1, result.attempts().size());
        assertEquals(
            SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                .TERMINAL_INCONCLUSIVE,
            result.attempts().getFirst().disposition());
        assertEquals(
            "BERLEKAMP_MATRIX_CELL_POLICY_EXCEEDED",
            result.attempts().getFirst().detailCode());
    }

    @Test
    void sourceCorrespondenceBudgetFailureRetainsThePrimeAttempt() {
        SparsePolynomial<BigInteger> source = integer(-1, 0, 1);
        SuitablePrimeSelectionPolicy policy = policy(3);
        SuitablePrimeSelectionResult calibration =
            SuitablePrimeSelection.selectAndFactor(
                request(source, limits, 1_000_000),
                policy);
        assertTrue(calibration.completed(), calibration.toString());
        long completeWork = calibration.work().totalWorkUnits();

        SuitablePrimeSelectionResult result =
            SuitablePrimeSelection.selectAndFactor(
                request(source, limits, completeWork - 1),
                policy);

        assertEquals(
            SuitablePrimeSelectionResult.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals(
            "SOURCE_CORRESPONDENCE_INCONCLUSIVE",
            result.detailCode());
        assertEquals(1, result.attempts().size());
        SuitablePrimeSelectionResult.PrimeAttempt attempt =
            result.attempts().getFirst();
        assertEquals(
            SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                .TERMINAL_INCONCLUSIVE,
            attempt.disposition());
        assertEquals(
            "SOURCE_CORRESPONDENCE_WORK_BUDGET_EXCEEDED",
            attempt.detailCode());
        assertTrue(attempt.workUnits() > 0);
        assertTrue(attempt.modularFactorizationCertificateHash().matches(
            "sha256:[0-9a-f]{64}"));
    }

    @Test
    void nonprimitiveNegativeLeadingAndWrongDomainsFailClosed() {
        SuitablePrimeSelectionResult nonprimitive =
            SuitablePrimeSelection.selectAndFactor(
                request(integer(2, 2), limits, 10_000),
                policy(3, 5));
        SuitablePrimeSelectionResult negativeLeading =
            SuitablePrimeSelection.selectAndFactor(
                request(integer(1, -1), limits, 10_000),
                policy(3, 5));

        PolynomialRing<BigInteger> primeRing = new PolynomialRing<>(
            PrimeField.of(5),
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        SuitablePrimeSelectionResult wrongDomain =
            SuitablePrimeSelection.selectAndFactor(
                request(polynomial(primeRing, 1, 1), limits, 10_000),
                policy(3, 5));

        assertEquals(
            SuitablePrimeSelectionResult.Status.UNSUPPORTED_SHAPE,
            nonprimitive.status());
        assertEquals(
            "REQUIRES_CANONICAL_PRIMITIVE_INTEGER_INPUT",
            nonprimitive.detailCode());
        assertEquals(
            SuitablePrimeSelectionResult.Status.UNSUPPORTED_SHAPE,
            negativeLeading.status());
        assertEquals(
            SuitablePrimeSelectionResult.Status.UNSUPPORTED_DOMAIN,
            wrongDomain.status());
        assertTrue(wrongDomain.attempts().isEmpty());
    }

    @Test
    void structuralAndSharedWorkAuthoritiesCannotBeBypassed() {
        SparsePolynomial<BigInteger> source = integer(-1, 0, 1);
        SuitablePrimeSelectionPolicy policy = policy(3);
        SuitablePrimeSelectionResult structural =
            SuitablePrimeSelection.selectAndFactor(
                request(
                    source,
                    new FactorizationRequest.StructuralLimits(
                        1,
                        1,
                        3,
                        1),
                    10_000),
                policy);
        SuitablePrimeSelectionResult calibration =
            SuitablePrimeSelection.selectAndFactor(
                request(source, limits, 1_000_000),
                policy);
        long oneRun = calibration.work().totalWorkUnits();
        FactorizationRequest<BigInteger> bounded =
            request(source, limits, oneRun + 1);
        SuitablePrimeSelectionResult mismatched =
            SuitablePrimeSelection.selectAndFactor(
                bounded,
                policy,
                new PolynomialWorkBudget(oneRun + 2));
        PolynomialWorkBudget shared =
            new PolynomialWorkBudget(oneRun + 1);
        SuitablePrimeSelectionResult first =
            SuitablePrimeSelection.selectAndFactor(
                bounded,
                policy,
                shared);
        SuitablePrimeSelectionResult second =
            SuitablePrimeSelection.selectAndFactor(
                bounded,
                policy,
                shared);

        assertEquals(
            SuitablePrimeSelectionResult.Status.BUDGET_INCONCLUSIVE,
            structural.status());
        assertEquals("MAX_TOTAL_DEGREE_EXCEEDED", structural.detailCode());
        assertEquals(0, structural.work().totalWorkUnits());
        assertEquals(
            SuitablePrimeSelectionResult.Status.TECHNICAL_FAILURE,
            mismatched.status());
        assertEquals(
            "SUITABLE_PRIME_WORK_BUDGET_AUTHORITY_MISMATCH",
            mismatched.detailCode());
        assertTrue(first.completed(), first.toString());
        assertEquals(
            SuitablePrimeSelectionResult.Status.BUDGET_INCONCLUSIVE,
            second.status());
        assertEquals(
            "SUITABLE_PRIME_SELECTION_WORK_BUDGET_EXCEEDED",
            second.detailCode());
        assertTrue(second.work().totalWorkUnits() > oneRun);
        assertTrue(second.work().totalWorkUnits() <= oneRun + 1);
    }

    @Test
    void policiesAndCertificatesAreCanonicalAndIssuerOwned() {
        SuitablePrimeSelectionPolicy generated =
            SuitablePrimeSelectionPolicy.deterministicAscending(
                11,
                4,
                finiteFieldPolicy);
        assertEquals(List.of(2, 3, 5, 7), generated.candidatePrimes());
        assertThrows(IllegalArgumentException.class, () ->
            policy(5, 3));
        assertThrows(IllegalArgumentException.class, () ->
            policy(3, 4));

        SparsePolynomial<BigInteger> source = integer(-1, 0, 1);
        FactorizationRequest<BigInteger> request =
            request(source, limits, 1_000_000);
        SuitablePrimeSelectionResult first =
            SuitablePrimeSelection.selectAndFactor(
                request,
                policy(3));
        SuitablePrimeSelectionResult second =
            SuitablePrimeSelection.selectAndFactor(
                request,
                policy(3));

        assertEquals(first, second);
        assertEquals(
            0,
            SuitablePrimeSelectionResult.class
                .getConstructors().length);
        assertEquals(
            0,
            SuitablePrimeSelectionResult.PrimeAttempt.class
                .getConstructors().length);
        assertTrue(first.attempts().getFirst().certificateHash().matches(
            "sha256:[0-9a-f]{64}"));
    }

    private SuitablePrimeSelectionPolicy policy(Integer... primes) {
        return new SuitablePrimeSelectionPolicy(
            SuitablePrimeSelectionPolicy.Algorithm
                .DETERMINISTIC_ASCENDING_PRIMES_V1,
            List.of(primes),
            finiteFieldPolicy);
    }

    private static FactorizationRequest<BigInteger> request(
        SparsePolynomial<BigInteger> source,
        FactorizationRequest.StructuralLimits structuralLimits,
        long maximumWork
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            structuralLimits,
            1,
            maximumWork);
    }

    private SparsePolynomial<BigInteger> integer(long... coefficients) {
        return polynomial(integerRing, coefficients);
    }

    private static SparsePolynomial<BigInteger> polynomial(
        PolynomialRing<BigInteger> ring,
        long... coefficients
    ) {
        return UnivariatePolynomialView.of(
            ring,
            Arrays.stream(coefficients)
                .mapToObj(BigInteger::valueOf)
                .toList())
            .toSparsePolynomial();
    }
}
