package de.regelsuche.sdk.optimization.polynomial;

import de.regelsuche.math.algorithms.polynomial.LargePrimeFactorization;
import de.regelsuche.math.algorithms.polynomial.LargePrimeFactorizationVerifier;
import de.regelsuche.polynomial.BigPrimeField;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import de.regelsuche.sdk.optimization.CancellationToken;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LargePrimeFactorizationSdkTest {
    @Test void sdkReturnsConditionalCheckedLargeModulusFactorsAndHonorsCancellation() {
        var p = BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE);
        var ring = new PolynomialRing<>(BigPrimeField.assumingPrime(p), List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        var source = UnivariatePolynomialView.of(ring, List.of(BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ONE))
            .toSparsePolynomial();
        var request = FactorizationRequest.independentComplete(source,
            new FactorizationRequest.StructuralLimits(1, 10, 11, 128), 1, 100_000_000);
        var resources = new LargePrimeFactorizationVerifier.Policy(128, 10_000_000, 10_000_000_000L);
        var policy = new LargePrimeFactorization.Policy(resources, 64, 142);
        var result = PrimeFieldFactorizationSdk.factor(request, policy, CancellationToken.NONE);
        assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL, result.status(), result.reason());
        assertEquals(2, result.certificate().orElseThrow().factors().size());
        assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL,
            PrimeFieldFactorizationSdk.verify(request, resources, result.certificate().orElseThrow(), CancellationToken.NONE).status());
        assertEquals("CANCELLED", PrimeFieldFactorizationSdk.factor(request, policy, () -> true).reason());
    }
}
