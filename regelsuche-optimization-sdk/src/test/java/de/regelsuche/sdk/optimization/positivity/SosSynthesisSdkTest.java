package de.regelsuche.sdk.optimization.positivity;

import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.positivity.PolynomialPotential;
import de.regelsuche.math.algorithms.positivity.SosSynthesis;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SosSynthesisSdkTest {
    @Test void callerCanSearchAndReplayBothKindsOfCertificate() {
        var budget = new OptimizationBudget(100_000, 1000, 1, 10000);
        var x = Polynomial.variable("x");
        var c = Monomial.constant();
        var domain = new SosSynthesis.Template(List.of(c), List.of(
            new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, x, List.of(c))), List.of(ExactRational.ZERO));
        var request = new SosSynthesis.Request(x, domain);
        var proof = SosSynthesisSdk.search(request, budget, CancellationToken.NONE).certificate().orElseThrow();
        assertTrue(SosSynthesisSdk.verify(request, proof, budget, CancellationToken.NONE).valid());
        var ranking = new PolynomialPotential.Request(Map.of("x", x.subtract(Polynomial.constant(Rational.ONE))), domain,
            List.of(Monomial.variable("x")), List.of(ExactRational.ONE), ExactRational.ONE, true);
        var potential = SosSynthesisSdk.search(ranking, budget, CancellationToken.NONE).certificate().orElseThrow();
        assertTrue(SosSynthesisSdk.verify(ranking, potential, budget, CancellationToken.NONE).terminationUnderContract());
        assertEquals("CANCELLED", SosSynthesisSdk.search(ranking, budget, () -> true).reason());
    }
}
