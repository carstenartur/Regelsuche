package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuitablePrimeSelectionEvidenceTest {
    private final PolynomialRing<BigInteger> integerRing =
        new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    private final FactorizationRequest.StructuralLimits limits =
        new FactorizationRequest.StructuralLimits(
            1,
            4,
            8,
            64);
    private final FiniteFieldFactorizationPolicy finiteFieldPolicy =
        FiniteFieldFactorizationPolicy.deterministicBerlekamp(
            101,
            1_000_000);

    @Test
    void selectedAttemptBindsTheIssuedSourceAndNestedCertificate() {
        FactorizationRequest<BigInteger> request = request();
        SuitablePrimeSelectionPolicy policy = policy(3);

        SuitablePrimeSelectionResult result =
            SuitablePrimeSelection.selectAndFactor(request, policy);

        assertTrue(result.completed(), result.toString());
        SuitablePrimeSelectionResult.PrimeAttempt selected =
            result.attempts().getLast();
        String modularSourceHash = AlgorithmEvidence.sha256(
            result.modularSource().canonicalMaterial());
        assertEquals(
            SuitablePrimeSelectionResult.PrimeAttempt.Disposition.SELECTED,
            selected.disposition());
        assertEquals(
            modularSourceHash,
            selected.modularSourceHash());
        assertEquals(
            modularSourceHash,
            result.modularFactorization().sourcePolynomialHash());
        assertEquals(
            result.modularFactorization().certificateHash(),
            selected.modularFactorizationCertificateHash());
    }

    @Test
    void issuerRejectsAttemptsOutsideTheBoundCandidatePrefix() {
        FactorizationRequest<BigInteger> request = request();
        SuitablePrimeSelectionPolicy policy = policy(3, 5);
        SuitablePrimeSelectionResult.PrimeAttempt wrongFirstAttempt =
            SuitablePrimeSelectionResult.issueAttempt(
                5,
                SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                    .REJECTED,
                "LEADING_COEFFICIENT_VANISHES_MOD_PRIME",
                modularPolynomial(5, 1, 1),
                null,
                0);

        assertThrows(IllegalArgumentException.class, () ->
            SuitablePrimeSelectionResult.failure(
                SuitablePrimeSelectionResult.Status.BUDGET_INCONCLUSIVE,
                "NO_SUITABLE_PRIME_WITHIN_POLICY",
                List.of(wrongFirstAttempt),
                PolynomialWorkLedger.empty(),
                request,
                policy));
    }

    @Test
    void issuerRejectsAttemptsBeyondTheRequestCandidateBudget() {
        FactorizationRequest<BigInteger> request = request();
        SuitablePrimeSelectionPolicy policy = policy(3, 5);
        SuitablePrimeSelectionResult.PrimeAttempt first =
            SuitablePrimeSelectionResult.issueAttempt(
                3,
                SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                    .REJECTED,
                "MODULAR_REDUCTION_NOT_SQUARE_FREE",
                modularPolynomial(3, 1, 1),
                null,
                0);
        SuitablePrimeSelectionResult.PrimeAttempt second =
            SuitablePrimeSelectionResult.issueAttempt(
                5,
                SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                    .REJECTED,
                "MODULAR_REDUCTION_NOT_SQUARE_FREE",
                modularPolynomial(5, 1, 1),
                null,
                0);

        assertThrows(IllegalArgumentException.class, () ->
            SuitablePrimeSelectionResult.failure(
                SuitablePrimeSelectionResult.Status.BUDGET_INCONCLUSIVE,
                "NO_SUITABLE_PRIME_WITHIN_POLICY",
                List.of(first, second),
                PolynomialWorkLedger.empty(),
                request,
                policy));
    }

    @Test
    void issuerRejectsASelectedAttemptForAnotherModularSource() {
        FactorizationRequest<BigInteger> request = request();
        SuitablePrimeSelectionPolicy policy = policy(3);
        SuitablePrimeSelectionResult valid =
            SuitablePrimeSelection.selectAndFactor(request, policy);
        assertTrue(valid.completed(), valid.toString());

        SuitablePrimeSelectionResult.PrimeAttempt forgedSelectedAttempt =
            SuitablePrimeSelectionResult.issueAttempt(
                3,
                SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                    .SELECTED,
                "SUITABLE_PRIME_SELECTED",
                modularPolynomial(3, 1, 1),
                valid.modularFactorization().certificateHash(),
                valid.attempts().getLast().workUnits());

        assertThrows(IllegalArgumentException.class, () ->
            SuitablePrimeSelectionResult.completed(
                List.of(forgedSelectedAttempt),
                3,
                valid.modularSource(),
                valid.modularFactorization(),
                valid.work(),
                request,
                policy));
    }

    @Test
    void issuerRejectsANestedCertificateForAnotherModularSource() {
        FactorizationRequest<BigInteger> request = request();
        SuitablePrimeSelectionPolicy policy = policy(3);
        SuitablePrimeSelectionResult valid =
            SuitablePrimeSelection.selectAndFactor(request, policy);
        assertTrue(valid.completed(), valid.toString());

        SparsePolynomial<BigInteger> otherSource =
            modularPolynomial(3, 1, 0, 1);
        FiniteFieldFactorizationResult otherFactorization =
            factor(otherSource);
        assertTrue(
            otherFactorization.completed(),
            otherFactorization.toString());

        SuitablePrimeSelectionResult.PrimeAttempt forgedSelectedAttempt =
            SuitablePrimeSelectionResult.issueAttempt(
                3,
                SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                    .SELECTED,
                "SUITABLE_PRIME_SELECTED",
                valid.modularSource(),
                otherFactorization.certificateHash(),
                valid.attempts().getLast().workUnits());

        assertThrows(IllegalArgumentException.class, () ->
            SuitablePrimeSelectionResult.completed(
                List.of(forgedSelectedAttempt),
                3,
                valid.modularSource(),
                otherFactorization,
                valid.work(),
                request,
                policy));
    }

    @Test
    void issuerRejectsAModularSourceThatDoesNotReduceTheIntegerSource() {
        FactorizationRequest<BigInteger> request = request();
        SuitablePrimeSelectionPolicy policy = policy(3);
        SparsePolynomial<BigInteger> forgedSource =
            modularPolynomial(3, 1, 0, 1);
        FiniteFieldFactorizationResult forgedFactorization =
            factor(forgedSource);
        assertTrue(
            forgedFactorization.completed(),
            forgedFactorization.toString());

        SuitablePrimeSelectionResult.PrimeAttempt forgedSelectedAttempt =
            SuitablePrimeSelectionResult.issueAttempt(
                3,
                SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                    .SELECTED,
                "SUITABLE_PRIME_SELECTED",
                forgedSource,
                forgedFactorization.certificateHash(),
                forgedFactorization.work().totalWorkUnits());

        assertThrows(IllegalArgumentException.class, () ->
            SuitablePrimeSelectionResult.completed(
                List.of(forgedSelectedAttempt),
                3,
                forgedSource,
                forgedFactorization,
                forgedFactorization.work(),
                request,
                policy));
    }

    private FiniteFieldFactorizationResult factor(
        SparsePolynomial<BigInteger> source
    ) {
        return FiniteFieldFactorization.factorSquareFree(
            FactorizationRequest.verifiedDecomposition(
                source,
                limits,
                1,
                1_000_000),
            finiteFieldPolicy);
    }

    private FactorizationRequest<BigInteger> request() {
        SparsePolynomial<BigInteger> source =
            UnivariatePolynomialView.of(
                integerRing,
                List.of(
                    BigInteger.valueOf(-1),
                    BigInteger.ZERO,
                    BigInteger.ONE))
                .toSparsePolynomial();
        return FactorizationRequest.verifiedDecomposition(
            source,
            limits,
            1,
            1_000_000);
    }

    private SuitablePrimeSelectionPolicy policy(Integer... primes) {
        return new SuitablePrimeSelectionPolicy(
            SuitablePrimeSelectionPolicy.Algorithm
                .DETERMINISTIC_ASCENDING_PRIMES_V1,
            List.of(primes),
            finiteFieldPolicy);
    }

    private static SparsePolynomial<BigInteger> modularPolynomial(
        int prime,
        long... coefficients
    ) {
        PolynomialRing<BigInteger> ring = new PolynomialRing<>(
            PrimeField.of(prime),
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        return UnivariatePolynomialView.of(
            ring,
            java.util.Arrays.stream(coefficients)
                .mapToObj(BigInteger::valueOf)
                .toList())
            .toSparsePolynomial();
    }
}
