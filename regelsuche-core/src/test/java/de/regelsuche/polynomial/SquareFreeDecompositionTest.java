package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SquareFreeDecompositionTest {
    private final PolynomialRing<ExactRational> ring =
        new PolynomialRing<>(
            ExactRationalField.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    private final FactorizationRequest.StructuralLimits limits =
        new FactorizationRequest.StructuralLimits(
            1,
            32,
            64,
            4_096);

    @Test
    void separatesRepeatedFactorsAndVerifiesMultiplicities() {
        SparsePolynomial<ExactRational> xMinusOne =
            polynomial(-1, 1);
        SparsePolynomial<ExactRational> xPlusTwo =
            polynomial(2, 1);
        SparsePolynomial<ExactRational> xSquaredPlusOne =
            polynomial(1, 0, 1);
        ExactRational unit = new ExactRational(
            BigInteger.valueOf(6),
            BigInteger.valueOf(5));
        SparsePolynomial<ExactRational> source =
            SparsePolynomial.constant(ring, unit)
                .multiply(xMinusOne.pow(3))
                .multiply(xPlusTwo.pow(2))
                .multiply(xSquaredPlusOne);

        SquareFreeDecomposition.Result<ExactRational> result =
            SquareFreeDecomposition.decompose(
                source,
                limits,
                100_000);

        assertTrue(result.completed(), result.toString());
        assertEquals(unit, result.unit());
        assertEquals(
            List.of(1, 2, 3),
            result.factors().stream()
                .map(PolynomialFactor::multiplicity)
                .sorted()
                .toList());
        assertEquals(
            List.of(
                xSquaredPlusOne,
                xPlusTwo,
                xMinusOne),
            result.factors().stream()
                .sorted(java.util.Comparator.comparingInt(
                    PolynomialFactor::multiplicity))
                .map(PolynomialFactor::polynomial)
                .toList());
        assertTrue(result.work().totalWorkUnits() > 0);
        assertTrue(result.certificateHash().matches(
            "sha256:[0-9a-f]{64}"));
    }

    @Test
    void certificatesAreDeterministicAndDoNotClaimIrreducibility() {
        SparsePolynomial<ExactRational> source =
            polynomial(-1, 1).pow(2)
                .multiply(polynomial(1, 0, 1));

        SquareFreeDecomposition.Result<ExactRational> first =
            SquareFreeDecomposition.decompose(
                source,
                limits,
                100_000);
        SquareFreeDecomposition.Result<ExactRational> second =
            SquareFreeDecomposition.decompose(
                source,
                limits,
                100_000);

        assertEquals(first, second);
        assertEquals(
            SquareFreeDecomposition.Status.COMPLETED,
            first.status());
        assertEquals(
            0,
            SquareFreeDecomposition.Result.class
                .getConstructors()
                .length);
    }

    @Test
    void structuralAndWorkLimitsFailBeforeAFalseMathematicalConclusion() {
        SparsePolynomial<ExactRational> source =
            polynomial(-1, 1).pow(5);
        SquareFreeDecomposition.Result<ExactRational> degreeLimited =
            SquareFreeDecomposition.decompose(
                source,
                new FactorizationRequest.StructuralLimits(
                    1,
                    4,
                    64,
                    4_096),
                100_000);
        SquareFreeDecomposition.Result<ExactRational> workLimited =
            SquareFreeDecomposition.decompose(
                source,
                limits,
                1);

        assertFalse(degreeLimited.completed());
        assertEquals(
            SquareFreeDecomposition.Status.BUDGET_INCONCLUSIVE,
            degreeLimited.status());
        assertEquals(
            "MAX_TOTAL_DEGREE_EXCEEDED",
            degreeLimited.detailCode());
        assertFalse(workLimited.completed());
        assertEquals(
            SquareFreeDecomposition.Status.BUDGET_INCONCLUSIVE,
            workLimited.status());
        assertThrows(
            IllegalStateException.class,
            workLimited::unit);
    }

    @Test
    void integerDomainIsNotSilentlyPromotedToARationalField() {
        PolynomialRing<BigInteger> integerRing =
            new PolynomialRing<>(
                BigIntegerDomain.INSTANCE,
                List.of(new PolynomialVariable("x")),
                PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        SparsePolynomial<BigInteger> source =
            new SparsePolynomial<>(
                integerRing,
                Map.of(
                    Monomial.of(2), BigInteger.ONE,
                    Monomial.of(0), BigInteger.ONE));

        SquareFreeDecomposition.Result<BigInteger> result =
            SquareFreeDecomposition.decompose(
                source,
                limits,
                1_000);

        assertEquals(
            SquareFreeDecomposition.Status.UNSUPPORTED_DOMAIN,
            result.status());
    }

    private SparsePolynomial<ExactRational> polynomial(
        long... coefficients
    ) {
        return UnivariatePolynomialView.of(
            ring,
            java.util.Arrays.stream(coefficients)
                .mapToObj(ExactRational::integer)
                .toList())
            .toSparsePolynomial();
    }
}
