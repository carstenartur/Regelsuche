package de.regelsuche.sdk.optimization.polynomial;

import de.regelsuche.math.algorithms.polynomial.FiniteFieldFactorizationPolicy;
import de.regelsuche.math.algorithms.polynomial.PrimeFieldMultiplicity.Status;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.sdk.optimization.CancellationToken;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiplicitySdkTest {
    @Test void nonzeroConstantHasNoFactors() {
        var ring = new PolynomialRing<>(PrimeField.of(3), List.of(new PolynomialVariable("t")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        var source = SparsePolynomial.constant(ring, BigInteger.TWO);
        var req = FactorizationRequest.independentComplete(source, new FactorizationRequest.StructuralLimits(1,0,1,2),
            1, 1000);
        var policy = FiniteFieldFactorizationPolicy.deterministicBerlekamp(3, 1);
        var result = PrimeFieldFactorizationSdk.factor(req, policy, CancellationToken.NONE);
        assertEquals(Status.COMPLETE, result.status());
        var proof = result.certificate().orElseThrow();
        assertEquals(List.of(), proof.factors());
        assertEquals(BigInteger.TWO, proof.unit());
        assertEquals(Status.COMPLETE, PrimeFieldFactorizationSdk.verify(req, policy, proof, CancellationToken.NONE).status());
    }
}
