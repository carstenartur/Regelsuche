package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static de.regelsuche.math.algorithms.positivity.SosSynthesisTest.*;
import static org.junit.jupiter.api.Assertions.*;

class PolynomialPotentialTest {
    @Test void findsQuadraticPotentialAndChecksBothIdentitiesAgainstOriginalTransition() {
        var domain = template(List.of(C, X), new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, x.subtract(one), List.of(C)));
        var request = new PolynomialPotential.Request(Map.of("x", x.subtract(one)), domain,
            List.of(X.multiply(X)), GRID, ExactRational.ONE, false);
        var result = PolynomialPotential.search(request, LIMITS, () -> false);
        var proof = result.certificate().orElseThrow();
        assertEquals(x.pow(2), proof.potential());
        assertEquals(x.subtract(one).multiply(Rational.of(2)),
            proof.decrease().source().target());
        var verified = PolynomialPotential.verify(request, proof, LIMITS, () -> false);
        assertTrue(verified.valid());
        assertFalse(verified.terminationUnderContract());
        var changed = new PolynomialPotential.Request(Map.of("x", x), domain, request.basis(), GRID, ExactRational.ONE, true);
        assertFalse(PolynomialPotential.verify(changed, proof, LIMITS, () -> false).valid());
        var fake = new PolynomialPotential.Certificate(changed, proof.potential(), proof.nonnegative(), proof.decrease());
        assertFalse(PolynomialPotential.verify(changed, fake, LIMITS, () -> false).valid());
        assertTrue(PolynomialPotential.search(changed, LIMITS, () -> false).certificate().isEmpty());
        assertEquals("CANCELLED", PolynomialPotential.verify(request, proof, LIMITS, () -> true).reason());
    }

    @Test void enumeratesCoefficientCombinationsAndSubstitutesSimultaneously() {
        var domain = template(List.of(C), new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, x, List.of(C)),
            new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, y, List.of(C)));
        var request = new PolynomialPotential.Request(Map.of("x", y, "y", x.subtract(one)), domain,
            List.of(X, Y), List.of(ExactRational.ZERO, ExactRational.ONE), ExactRational.ONE, true);
        var proof = PolynomialPotential.search(request, LIMITS, () -> false).certificate().orElseThrow();
        assertEquals(x.add(y), proof.potential());
        assertTrue(PolynomialPotential.verify(request, proof, LIMITS, () -> false).terminationUnderContract());
        assertThrows(IllegalArgumentException.class, () -> new PolynomialPotential.Request(request.transition(), domain,
            List.of(Monomial.constant()), GRID, ExactRational.ZERO, true));
    }

    @Test void bindsNonlinearTransitionFrameVariableAndStrictMargin() {
        var domain = template(List.of(C),
            new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, x.multiply(y), List.of(C)),
            new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, y.pow(3).subtract(one), List.of(C)));
        var request = new PolynomialPotential.Request(Map.of("x", x.subtract(y.pow(2))), domain,
            List.of(X.multiply(Y)), List.of(ExactRational.ONE), ExactRational.ONE, true);
        var proof = PolynomialPotential.search(request, LIMITS, () -> false).certificate().orElseThrow();
        assertEquals(x.multiply(y), proof.potential());
        assertEquals(y.pow(3).subtract(one), proof.decrease().source().target());
        var stronger = new PolynomialPotential.Request(request.transition(), domain, request.basis(), request.coefficients(),
            ExactRational.integer(2), true);
        var rebranded = new PolynomialPotential.Certificate(stronger, proof.potential(), proof.nonnegative(), proof.decrease());
        assertFalse(PolynomialPotential.verify(stronger, rebranded, LIMITS, () -> false).valid());
    }

    @Test void budgetsBothRetainedGramProofsTogether() {
        var domain = template(List.of(C, X, Y, Monomial.variable("z")),
            new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, x, List.of(C)));
        var request = new PolynomialPotential.Request(Map.of("x", x.subtract(one)), domain, List.of(X),
            List.of(ExactRational.ONE), ExactRational.ONE, true);
        var proof = PolynomialPotential.search(request, LIMITS, () -> false).certificate().orElseThrow();
        // Each Gram proof has 17 scalar entries. A complete potential witness retains both.
        var small = new IterationLimits(500_000, 33, 10_000);
        var replay = PolynomialPotential.verify(request, proof, small, () -> false);
        assertEquals("TERM_LIMIT", replay.reason());
        assertFalse(replay.valid());
    }

    @Test void zeroCandidateCanCertifyAnEmptyActiveDomain() {
        var domain = template(List.of(C),
            new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, one.multiply(Rational.NEGATIVE_ONE), List.of(C)));
        var request = new PolynomialPotential.Request(Map.of(), domain, List.of(C), List.of(ExactRational.ZERO),
            ExactRational.ONE, false);
        var proof = PolynomialPotential.search(request, LIMITS, () -> false).certificate().orElseThrow();
        assertEquals(Polynomial.zero(), proof.potential());
        assertTrue(PolynomialPotential.verify(request, proof, LIMITS, () -> false).valid());
    }
}
