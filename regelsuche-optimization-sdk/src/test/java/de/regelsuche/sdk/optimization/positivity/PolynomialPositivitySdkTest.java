package de.regelsuche.sdk.optimization.positivity;

import de.regelsuche.math.algorithms.positivity.BernsteinPositivity;
import de.regelsuche.math.algorithms.positivity.GramPositivity;
import de.regelsuche.math.algorithms.positivity.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolynomialPositivitySdkTest {
    @Test void typedBoundsAndProofsUseTheCallersBudgetAndCancellation() {
        var budget = new OptimizationBudget(10_000, 100, 1, 10_000);
        var exponent = new SparsePolynomial.Exponent(0,0);
        var polynomial = new SparsePolynomial(List.of(1),Map.of(exponent,ExactRational.ONE));
        var request = new BernsteinPositivity.Request(polynomial,
            List.of(new BernsteinPositivity.Interval(ExactRational.ZERO,ExactRational.ONE)));
        var proof = PolynomialPositivitySdk.prove(request,0,budget,CancellationToken.NONE).certificate().orElseThrow();
        assertEquals(BernsteinPositivity.Claim.STRICTLY_POSITIVE,proof.claim());
        assertTrue(PolynomialPositivitySdk.verify(request,proof,budget,CancellationToken.NONE).valid());
        var bounds = PolynomialPositivitySdk.bounds(request,budget,CancellationToken.NONE).bounds().orElseThrow();
        assertTrue(PolynomialPositivitySdk.verifyBounds(request,bounds,budget,CancellationToken.NONE).valid());
        var gram = new GramPositivity.Certificate(polynomial,List.of(exponent),List.of(List.of(ExactRational.ONE)));
        assertTrue(PolynomialPositivitySdk.verifyPolynomial(polynomial,gram,budget,CancellationToken.NONE).valid());
        assertEquals(GramPositivity.Classification.POSITIVE_DEFINITE,
            PolynomialPositivitySdk.checkMatrix(gram.matrix(),budget,CancellationToken.NONE).classification());
        var tiny = new OptimizationBudget(1,1,1,10_000);
        assertTrue(PolynomialPositivitySdk.prove(request,0,tiny,CancellationToken.NONE).certificate().isEmpty());
        assertTrue(PolynomialPositivitySdk.bounds(request,tiny,CancellationToken.NONE).bounds().isEmpty());
        assertFalse(PolynomialPositivitySdk.verify(request,proof,budget,() -> true).valid());
        assertFalse(PolynomialPositivitySdk.verifyBounds(request,bounds,budget,() -> true).valid());
        assertFalse(PolynomialPositivitySdk.verifyPolynomial(polynomial,gram,budget,() -> true).valid());
        assertEquals(GramPositivity.Classification.UNAVAILABLE,
            PolynomialPositivitySdk.checkMatrix(gram.matrix(),budget,() -> true).classification());
    }
}
