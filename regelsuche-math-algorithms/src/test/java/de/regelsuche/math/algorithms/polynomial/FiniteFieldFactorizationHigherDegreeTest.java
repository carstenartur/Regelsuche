package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class FiniteFieldFactorizationHigherDegreeTest {
    private final PolynomialRing<BigInteger> ring =
        new PolynomialRing<>(
            PrimeField.of(2),
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    private final FactorizationRequest.StructuralLimits limits =
        new FactorizationRequest.StructuralLimits(
            1,
            16,
            32,
            64);
    private final FiniteFieldFactorizationPolicy policy =
        FiniteFieldFactorizationPolicy.deterministicBerlekamp(
            16,
            100_000);

    @Test
    void verifiesAnIrreduciblePolynomialOfCompositeDegree() {
        SparsePolynomial<BigInteger> quartic =
            polynomial(1, 1, 0, 0, 1);

        FiniteFieldFactorizationResult result =
            FiniteFieldFactorization.factorSquareFree(
                request(quartic),
                policy);

        assertTrue(result.completed(), result.toString());
        assertEquals(1, result.berlekampNullity());
        assertEquals(
            List.of(quartic),
            result.factors().stream()
                .map(factor -> factor.polynomial())
                .toList());
    }

    @Test
    void splitsTwoDistinctIrreducibleCubics() {
        SparsePolynomial<BigInteger> first =
            polynomial(1, 1, 0, 1);
        SparsePolynomial<BigInteger> second =
            polynomial(1, 0, 1, 1);
        SparsePolynomial<BigInteger> source =
            first.multiply(second);

        FiniteFieldFactorizationResult result =
            FiniteFieldFactorization.factorSquareFree(
                request(source),
                policy);

        assertTrue(result.completed(), result.toString());
        assertEquals(2, result.berlekampNullity());
        assertEquals(
            Set.of(first, second),
            result.factors().stream()
                .map(factor -> factor.polynomial())
                .collect(Collectors.toSet()));
    }

    private FactorizationRequest<BigInteger> request(
        SparsePolynomial<BigInteger> source
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            limits,
            1,
            1_000_000);
    }

    private SparsePolynomial<BigInteger> polynomial(
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
