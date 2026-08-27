package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeUnivariateFactorizationEngineTest {
    private static final FactorizationRequest.StructuralLimits LIMITS =
        new FactorizationRequest.StructuralLimits(
            1,
            32,
            128,
            4_096);
    private static final int CANDIDATES = 250_000;
    private static final long WORK = 20_000_000;

    private final PolynomialRing<BigInteger> integerRing =
        new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    private final PolynomialRing<ExactRational> rationalRing =
        new PolynomialRing<>(
            ExactRationalField.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);

    @Test
    void completelyFactorsGeneralIntegerPolynomial() {
        SparsePolynomial<BigInteger> xMinusOne = integer(-1, 1);
        SparsePolynomial<BigInteger> xPlusTwo = integer(2, 1);
        SparsePolynomial<BigInteger> xSquaredPlusOne =
            integer(1, 0, 1);
        SparsePolynomial<BigInteger> source = xMinusOne
            .multiply(xPlusTwo)
            .multiply(xSquaredPlusOne)
            .scale(BigInteger.valueOf(6));

        NativeUnivariateFactorizationEngine<BigInteger> engine =
            NativeUnivariateFactorizationEngine.boundedIntegers();
        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(engine, request(source));

        assertTrue(report.successful(), report.toString());
        assertEquals(
            FactorizationVerifier.ClaimStrength.BACKEND_CLAIMED_COMPLETE,
            report.claimStrength());
        assertEquals(1, report.candidates().size());
        assertEquals(
            BigInteger.valueOf(6),
            report.candidates().getFirst().unit());
        assertEquals(
            List.of(xMinusOne, xPlusTwo, xSquaredPlusOne).stream()
                .map(SparsePolynomial::canonicalMaterial)
                .sorted()
                .toList(),
            report.candidates().getFirst().factors().stream()
                .map(PolynomialFactor::polynomial)
                .map(SparsePolynomial::canonicalMaterial)
                .sorted()
                .toList());
    }

    @Test
    void preservesRationalUnitAndRepeatedMultiplicities() {
        SparsePolynomial<ExactRational> xMinusHalf =
            rational(q(-1, 2), ExactRational.ONE);
        SparsePolynomial<ExactRational> xPlusThird =
            rational(q(1, 3), ExactRational.ONE);
        SparsePolynomial<ExactRational> source = xMinusHalf
            .pow(3)
            .multiply(xPlusThird.pow(2))
            .scale(q(-7, 11));

        NativeUnivariateFactorizationEngine<ExactRational> engine =
            NativeUnivariateFactorizationEngine.boundedRationals();
        FactorizationVerifier.Report<ExactRational> report =
            FactorizationVerifier.execute(engine, request(source));

        assertTrue(report.successful(), report.toString());
        assertEquals(
            FactorizationVerifier.ClaimStrength.BACKEND_CLAIMED_COMPLETE,
            report.claimStrength());
        assertEquals(1, report.candidates().size());
        List<Integer> multiplicities =
            report.candidates().getFirst().factors().stream()
                .map(PolynomialFactor::multiplicity)
                .sorted()
                .toList();
        assertEquals(List.of(2, 3), multiplicities);
    }

    @Test
    void exhaustivelyRetainsIrreducibilityOnlyAsBackendClaim() {
        SparsePolynomial<BigInteger> source = integer(1, 0, 1);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                NativeUnivariateFactorizationEngine.boundedIntegers(),
                request(source));

        assertEquals(
            FactorizationVerifier.Status.NO_FACTORIZATION_FOUND,
            report.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength
                .BACKEND_CLAIMED_IRREDUCIBLE,
            report.claimStrength());
        assertTrue(report.candidates().isEmpty());
    }

    @Test
    void candidateExhaustionIsNotIrreducibility() {
        SparsePolynomial<BigInteger> source = integer(-2, 1, 1);
        FactorizationRequest<BigInteger> bounded =
            FactorizationRequest.verifiedDecomposition(
                source,
                LIMITS,
                1,
                WORK);

        FactorizationEngine.EngineResult<BigInteger> result =
            NativeUnivariateFactorizationEngine.boundedIntegers()
                .propose(bounded);

        assertEquals(
            FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE,
            result.outcome());
        assertEquals(
            FactorizationEngine.BackendClaim.NONE,
            result.backendClaim());
        assertTrue(result.proposals().isEmpty());
    }

    @Test
    void multivariateInputRemainsUnsupported() {
        PolynomialRing<BigInteger> multivariate =
            new PolynomialRing<>(
                BigIntegerDomain.INSTANCE,
                List.of(
                    new PolynomialVariable("x"),
                    new PolynomialVariable("y")),
                PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        SparsePolynomial<BigInteger> source =
            new SparsePolynomial<>(
                multivariate,
                java.util.Map.of(
                    de.regelsuche.polynomial.Monomial.of(1, 0),
                    BigInteger.ONE,
                    de.regelsuche.polynomial.Monomial.of(0, 1),
                    BigInteger.ONE));
        FactorizationRequest<BigInteger> request =
            FactorizationRequest.verifiedDecomposition(
                source,
                new FactorizationRequest.StructuralLimits(
                    2,
                    8,
                    16,
                    128),
                10,
                10_000);

        FactorizationEngine.EngineResult<BigInteger> result =
            NativeUnivariateFactorizationEngine.boundedIntegers()
                .propose(request);

        assertEquals(
            FactorizationEngine.Outcome.UNSUPPORTED_REQUEST,
            result.outcome());
        assertFalse(result.backendClaim()
            == FactorizationEngine.BackendClaim.IRREDUCIBLE);
    }

    private SparsePolynomial<BigInteger> integer(
        long... coefficients
    ) {
        return UnivariatePolynomialView.of(
            integerRing,
            Arrays.stream(coefficients)
                .mapToObj(BigInteger::valueOf)
                .toList())
            .toSparsePolynomial();
    }

    private SparsePolynomial<ExactRational> rational(
        ExactRational... coefficients
    ) {
        return UnivariatePolynomialView.of(
            rationalRing,
            List.of(coefficients))
            .toSparsePolynomial();
    }

    private static ExactRational q(
        long numerator,
        long denominator
    ) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }

    private static <C> FactorizationRequest<C> request(
        SparsePolynomial<C> source
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            LIMITS,
            CANDIDATES,
            WORK);
    }
}
