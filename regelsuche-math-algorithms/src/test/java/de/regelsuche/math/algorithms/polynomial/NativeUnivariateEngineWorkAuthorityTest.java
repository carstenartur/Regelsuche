package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeUnivariateEngineWorkAuthorityTest {
    @Test
    void capsBackendWorkBelowTheOuterVerifierAuthority() {
        SparsePolynomial<BigInteger> source = polynomial(-1L, 0L, 1L);
        FactorizationRequest<BigInteger> request =
            FactorizationRequest.verifiedDecomposition(
                source,
                new FactorizationRequest.StructuralLimits(
                    1,
                    8,
                    16,
                    128
                ),
                32,
                20_000L
            );
        NativeUnivariateFactorizationPolicy defaults =
            NativeUnivariateFactorizationPolicy.boundedDefaults();
        NativeUnivariateFactorizationPolicy limited =
            defaults.withMaxEngineWorkUnits(1L);

        FactorizationEngine.EngineResult<BigInteger> result =
            NativeUnivariateFactorizationEngine.integers(limited)
                .propose(request);

        assertEquals(
            FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE,
            result.outcome()
        );
        assertEquals(1L, result.work().totalWorkUnits());
        assertEquals(1L, limited.maxEngineWorkUnits());
        assertNotEquals(
            defaults.canonicalMaterial(),
            limited.canonicalMaterial()
        );
    }

    @Test
    void rejectsANonPositiveBackendAuthority() {
        assertThrows(
            IllegalArgumentException.class,
            () -> NativeUnivariateFactorizationPolicy.boundedDefaults()
                .withMaxEngineWorkUnits(0L)
        );
    }

    private static SparsePolynomial<BigInteger> polynomial(
        long... coefficients
    ) {
        PolynomialRing<BigInteger> ring = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC
        );
        return UnivariatePolynomialView.of(
            ring,
            java.util.Arrays.stream(coefficients)
                .mapToObj(BigInteger::valueOf)
                .toList()
        ).toSparsePolynomial();
    }
}
