package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class FiniteFieldFactorizationTest {
    private final FactorizationRequest.StructuralLimits limits =
        new FactorizationRequest.StructuralLimits(
            1,
            32,
            64,
            4_096);
    private final FiniteFieldFactorizationPolicy policy =
        FiniteFieldFactorizationPolicy.deterministicBerlekamp(101);

    @Test
    void factorsAUnitTimesDistinctIrreducibleFactorsOverF5() {
        PolynomialRing<BigInteger> ring = ring(5);
        SparsePolynomial<BigInteger> first = polynomial(ring, 1, 1);
        SparsePolynomial<BigInteger> second = polynomial(ring, 2, 1);
        SparsePolynomial<BigInteger> quadratic =
            polynomial(ring, 2, 0, 1);
        SparsePolynomial<BigInteger> source =
            SparsePolynomial.constant(ring, BigInteger.valueOf(3))
                .multiply(first)
                .multiply(second)
                .multiply(quadratic);

        FiniteFieldFactorizationResult result =
            FiniteFieldFactorization.factorSquareFree(
                request(source, 1_000_000),
                policy);

        assertTrue(result.completed(), result.toString());
        assertEquals(5, result.prime());
        assertEquals(BigInteger.valueOf(3), result.unit());
        assertEquals(3, result.berlekampNullity());
        assertEquals(
            Set.of(first, second, quadratic),
            result.factors().stream()
                .map(factor -> factor.polynomial())
                .collect(Collectors.toSet()));
        assertEquals(
            3,
            result.irreducibilityCertificateHashes().size());
        assertTrue(result.irreducibilityCertificateHashes().stream()
            .allMatch(hash -> hash.matches(
                "sha256:[0-9a-f]{64}")));
        assertTrue(result.kernelCertificateHash().matches(
            "sha256:[0-9a-f]{64}"));
        assertTrue(result.certificateHash().matches(
            "sha256:[0-9a-f]{64}"));
    }

    @Test
    void handlesCharacteristicTwoAndAnIrreducibleCubic() {
        PolynomialRing<BigInteger> binaryRing = ring(2);
        SparsePolynomial<BigInteger> binarySource =
            polynomial(binaryRing, 0, 1)
                .multiply(polynomial(binaryRing, 1, 1))
                .multiply(polynomial(binaryRing, 1, 1, 1));
        FiniteFieldFactorizationResult binary =
            FiniteFieldFactorization.factorSquareFree(
                request(binarySource, 1_000_000),
                policy);

        PolynomialRing<BigInteger> ternaryRing = ring(3);
        SparsePolynomial<BigInteger> cubic =
            polynomial(ternaryRing, 1, 2, 0, 1);
        FiniteFieldFactorizationResult irreducible =
            FiniteFieldFactorization.factorSquareFree(
                request(cubic, 1_000_000),
                policy);

        assertTrue(binary.completed(), binary.toString());
        assertEquals(3, binary.factors().size());
        assertTrue(irreducible.completed(), irreducible.toString());
        assertEquals(1, irreducible.berlekampNullity());
        assertEquals(
            List.of(cubic),
            irreducible.factors().stream()
                .map(factor -> factor.polynomial())
                .toList());
    }

    @Test
    void certificatesAreDeterministicAndIssuerOwned() {
        PolynomialRing<BigInteger> ring = ring(5);
        SparsePolynomial<BigInteger> source = polynomial(ring, 2, 0, 1)
            .multiply(polynomial(ring, 1, 1));
        FactorizationRequest<BigInteger> request =
            request(source, 1_000_000);

        FiniteFieldFactorizationResult first =
            FiniteFieldFactorization.factorSquareFree(request, policy);
        FiniteFieldFactorizationResult second =
            FiniteFieldFactorization.factorSquareFree(request, policy);

        assertTrue(first.completed(), first.toString());
        assertEquals(first, second);
        assertEquals(
            0,
            FiniteFieldFactorizationResult.class
                .getConstructors().length);
    }

    @Test
    void nonSquareFreeAndWrongDomainInputsFailClosed() {
        PolynomialRing<BigInteger> ring = ring(5);
        SparsePolynomial<BigInteger> repeated =
            polynomial(ring, 1, 1).pow(2);
        FiniteFieldFactorizationResult nonSquareFree =
            FiniteFieldFactorization.factorSquareFree(
                request(repeated, 100_000),
                policy);

        PolynomialRing<BigInteger> integers = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        FiniteFieldFactorizationResult wrongDomain =
            FiniteFieldFactorization.factorSquareFree(
                request(polynomial(integers, 1, 0, 1), 100_000),
                policy);

        assertFalse(nonSquareFree.completed());
        assertEquals(
            FiniteFieldFactorizationResult.Status.UNSUPPORTED_SHAPE,
            nonSquareFree.status());
        assertEquals(
            "REQUIRES_SQUARE_FREE_INPUT",
            nonSquareFree.detailCode());
        assertFalse(wrongDomain.completed());
        assertEquals(
            FiniteFieldFactorizationResult.Status.UNSUPPORTED_DOMAIN,
            wrongDomain.status());
        assertThrows(
            IllegalStateException.class,
            wrongDomain::prime);
    }

    @Test
    void structuralEnumerationAndWorkLimitsRemainInconclusive() {
        PolynomialRing<BigInteger> ring = ring(7);
        SparsePolynomial<BigInteger> source =
            polynomial(ring, 1, 0, 1);
        FiniteFieldFactorizationResult structural =
            FiniteFieldFactorization.factorSquareFree(
                FactorizationRequest.verifiedDecomposition(
                    source,
                    new FactorizationRequest.StructuralLimits(
                        1,
                        1,
                        8,
                        128),
                    1,
                    100_000),
                policy);
        FiniteFieldFactorizationResult enumeration =
            FiniteFieldFactorization.factorSquareFree(
                request(source, 100_000),
                FiniteFieldFactorizationPolicy
                    .deterministicBerlekamp(5));
        FiniteFieldFactorizationResult work =
            FiniteFieldFactorization.factorSquareFree(
                request(source, 1),
                policy);

        assertEquals(
            FiniteFieldFactorizationResult.Status.BUDGET_INCONCLUSIVE,
            structural.status());
        assertEquals(
            "MAX_TOTAL_DEGREE_EXCEEDED",
            structural.detailCode());
        assertEquals(0, structural.work().totalWorkUnits());
        assertEquals(
            "PRIME_FIELD_ENUMERATION_POLICY_EXCEEDED",
            enumeration.detailCode());
        assertEquals(0, enumeration.work().totalWorkUnits());
        assertEquals(
            "FINITE_FIELD_FACTORIZATION_WORK_BUDGET_EXCEEDED",
            work.detailCode());
    }

    @Test
    void aSharedRequestBudgetCannotBeResetOrReauthorized() {
        PolynomialRing<BigInteger> ring = ring(5);
        SparsePolynomial<BigInteger> source = polynomial(ring, 1, 1)
            .multiply(polynomial(ring, 2, 1));
        FiniteFieldFactorizationResult calibration =
            FiniteFieldFactorization.factorSquareFree(
                request(source, 1_000_000),
                policy);
        long oneRun = calibration.work().totalWorkUnits();
        FactorizationRequest<BigInteger> bounded =
            request(source, oneRun + 1);
        FiniteFieldFactorizationResult mismatched =
            FiniteFieldFactorization.factorSquareFree(
                bounded,
                policy,
                new PolynomialWorkBudget(oneRun + 2));
        PolynomialWorkBudget shared =
            new PolynomialWorkBudget(oneRun + 1);
        FiniteFieldFactorizationResult first =
            FiniteFieldFactorization.factorSquareFree(
                bounded,
                policy,
                shared);
        FiniteFieldFactorizationResult second =
            FiniteFieldFactorization.factorSquareFree(
                bounded,
                policy,
                shared);

        assertEquals(
            FiniteFieldFactorizationResult.Status.TECHNICAL_FAILURE,
            mismatched.status());
        assertEquals(
            "FINITE_FIELD_WORK_BUDGET_AUTHORITY_MISMATCH",
            mismatched.detailCode());
        assertTrue(first.completed(), first.toString());
        assertEquals(
            FiniteFieldFactorizationResult.Status.BUDGET_INCONCLUSIVE,
            second.status());
        assertTrue(second.work().totalWorkUnits() > oneRun);
        assertTrue(second.work().totalWorkUnits() <= oneRun + 1);
    }

    private FactorizationRequest<BigInteger> request(
        SparsePolynomial<BigInteger> source,
        long maxWork
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            limits,
            1,
            maxWork);
    }

    private static PolynomialRing<BigInteger> ring(int prime) {
        return new PolynomialRing<>(
            PrimeField.of(prime),
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
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
