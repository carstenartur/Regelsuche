package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BinaryQuarticFactorizationEngineTest {
    private final PolynomialRing<BigInteger> ring = new PolynomialRing<>(
        BigIntegerDomain.INSTANCE,
        List.of(
            new PolynomialVariable("A"),
            new PolynomialVariable("B")));
    private final BinaryQuarticFactorizationEngine engine =
        new BinaryQuarticFactorizationEngine();

    @Test
    void emitsVerifiedDecompositionsWithoutClaimingIrreducibility() {
        SparsePolynomial<BigInteger> source = quartic(
            BigInteger.ONE,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.valueOf(4));

        FactorizationReport<BigInteger> report = engine.factor(
            FactorizationRequest.verifiedDecomposition(
                source,
                8,
                4_096));

        assertEquals(
            FactorizationStatus.PARTIAL_FACTORIZATION,
            report.status());
        assertTrue(report.successful());
        assertFalse(report.candidates().isEmpty());
        assertTrue(report.candidates().stream().allMatch(candidate ->
            candidate.completeness()
                == FactorizationCompleteness.DECOMPOSITION_ONLY
                && candidate.unresolvedRemainder().isOne()
                && FactorizationVerifier.verify(source, candidate)
                    .verified()));
    }

    @Test
    void aTemplateMissIsNotReportedAsIrreducibility() {
        SparsePolynomial<BigInteger> source = quartic(
            BigInteger.ONE,
            BigInteger.ZERO,
            BigInteger.ONE,
            BigInteger.ZERO,
            BigInteger.valueOf(2));

        FactorizationReport<BigInteger> report = engine.factor(
            FactorizationRequest.verifiedDecomposition(
                source,
                8,
                4_096));

        assertEquals(
            FactorizationStatus.NO_FACTORIZATION_FOUND,
            report.status());
        assertFalse(report.successful());
        assertTrue(report.candidates().isEmpty());
    }

    @Test
    void strongerCompletenessAndExhaustedBudgetsFailClosed() {
        SparsePolynomial<BigInteger> source = quartic(
            BigInteger.ONE,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.valueOf(4));
        FactorizationReport<BigInteger> completeness = engine.factor(
            new FactorizationRequest<>(
                source,
                FactorizationCompleteness
                    .INDEPENDENTLY_CERTIFIED_COMPLETE,
                8,
                4_096));
        FactorizationReport<BigInteger> zeroCandidates = engine.factor(
            FactorizationRequest.verifiedDecomposition(
                source,
                0,
                4_096));
        FactorizationReport<BigInteger> work =
            new BinaryQuarticFactorizationEngine(32, 1).factor(
                FactorizationRequest.verifiedDecomposition(
                    source,
                    8,
                    1));

        assertEquals(
            FactorizationStatus.UNSUPPORTED_REQUEST,
            completeness.status());
        assertEquals(
            FactorizationStatus.BUDGET_INCONCLUSIVE,
            zeroCandidates.status());
        assertEquals(
            FactorizationStatus.BUDGET_INCONCLUSIVE,
            work.status());
    }

    @Test
    void candidateFactorsAreCanonicalizedAndDuplicateFactorsMerge() {
        SparsePolynomial<BigInteger> factor = new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(1, 0), BigInteger.ONE,
                Monomial.of(0, 1), BigInteger.ONE));
        FactorizationCandidate<BigInteger> candidate =
            new FactorizationCandidate<>(
                BigInteger.ONE,
                List.of(
                    new PolynomialFactor<>(factor, 2),
                    new PolynomialFactor<>(factor, 1)),
                SparsePolynomial.one(ring),
                FactorizationCompleteness.DECOMPOSITION_ONLY,
                "sha256:" + "a".repeat(64));

        assertEquals(1, candidate.factors().size());
        assertEquals(3, candidate.factors().getFirst().multiplicity());
    }

    private SparsePolynomial<BigInteger> quartic(
        BigInteger c40,
        BigInteger c31,
        BigInteger c22,
        BigInteger c13,
        BigInteger c04
    ) {
        return new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(4, 0), c40,
                Monomial.of(3, 1), c31,
                Monomial.of(2, 2), c22,
                Monomial.of(1, 3), c13,
                Monomial.of(0, 4), c04));
    }
}
