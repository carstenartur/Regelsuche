package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test void collidingZeroTransitionsConsumeWorkAndCanBeCancelledDuringValidation() {
        var request = collisionRequest("0", "z");
        var proof = PolynomialPotential.search(request, LIMITS, () -> false).certificate().orElseThrow();
        var tiny = PolynomialPotential.verify(request, proof, LIMITS.withMaximumWork(200), () -> false);
        assertEquals("WORK_LIMIT", tiny.reason());
        var polls = new AtomicInteger();
        var stopped = PolynomialPotential.verify(request, proof, LIMITS, () -> polls.incrementAndGet() == 300);
        assertEquals("CANCELLED", stopped.reason());
        assertTrue(stopped.work() >= 50, "The zero transitions traversed before cancellation must count.");
        assertFalse(stopped.terminationUnderContract());
    }

    @Test void cancelsInSourceComparisonBeforeAChangedLastTransition() {
        var request = collisionRequest("0", "z");
        var proof = PolynomialPotential.search(request, LIMITS, () -> false).certificate().orElseThrow();
        var early = collisionRequest("1", "z");
        var late = collisionRequest("0", "y");
        var seen = new AtomicInteger();
        var mismatch = PolynomialPotential.verify(early, proof, LIMITS, () -> { seen.incrementAndGet(); return false; });
        assertEquals("SOURCE_MISMATCH", mismatch.reason());
        // Equal input shapes and values give the same validation cost. The late mismatch follows
        // all 256 colliding names. Cancel after the early mismatch's checkpoint plus 40 more.
        int cancelAt = seen.get() + 40;
        seen.set(0);
        var stopped = PolynomialPotential.verify(late, proof, LIMITS, () -> seen.incrementAndGet() == cancelAt);
        assertEquals("CANCELLED", stopped.reason());
        assertEquals("SOURCE_MISMATCH", PolynomialPotential.verify(late, proof, LIMITS, () -> false).reason());
        assertTrue(PolynomialPotential.verify(collisionRequest("0","z"), proof, LIMITS, () -> false).valid());
    }

    @Test void snapshotAndReplayBindEveryRequestFieldAndUnorderedPolynomialTerms() {
        var domain = template(List.of(C), new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, x, List.of(C)));
        var transition = new LinkedHashMap<String, Polynomial>();
        transition.put("x", x.subtract(one));
        transition.put("unused", x.add(y));
        var basis = List.of(X);
        var grid = List.of(ExactRational.ONE);
        var delta = ExactRational.ONE;
        var req = new PolynomialPotential.Request(transition, domain, basis, grid, delta, true);
        var proof = PolynomialPotential.search(req, LIMITS, () -> false).certificate().orElseThrow();
        transition.put("unused", y.add(x));
        var equivalent = new PolynomialPotential.Request(transition, domain, basis, grid, delta, true);
        assertTrue(PolynomialPotential.verify(equivalent, proof, LIMITS, () -> false).valid());
        var differentPremise = new SosSynthesis.Premise(SosSynthesis.Relation.ZERO, x, List.of(C));
        for (var changed : List.of(
                new PolynomialPotential.Request(transition, domain, basis, grid, delta, false),
                new PolynomialPotential.Request(transition, domain, basis, grid, ExactRational.integer(2), true),
                new PolynomialPotential.Request(transition, domain, List.of(Y), grid, delta, true),
                new PolynomialPotential.Request(transition, domain, basis, GRID, delta, true),
                new PolynomialPotential.Request(transition, template(List.of(C), differentPremise), basis, grid, delta, true),
                new PolynomialPotential.Request(transition, new SosSynthesis.Template(domain.basis(),
                    domain.premises(), grid), basis, grid, delta, true))) {
            assertEquals("SOURCE_MISMATCH", PolynomialPotential.verify(changed, proof, LIMITS, () -> false).reason());
        }
        transition.put("unused", y);
        assertEquals(x.add(y), req.transition().get("unused"));
        assertThrows(UnsupportedOperationException.class, () -> req.transition().remove("absent"));
        transition.put("unused", null);
        assertThrows(NullPointerException.class, () -> new PolynomialPotential.Request(transition, domain, basis, grid, delta, true));
        transition.clear();
        transition.put(null, x);
        assertThrows(NullPointerException.class, () -> new PolynomialPotential.Request(transition, domain, basis, grid, delta, true));
    }

    @Test void twoIdentityKeysWithTheSameVariableNameRemainAnInvalidTransition() {
        var names = new java.util.IdentityHashMap<String, Polynomial>();
        names.put(new String("x"), x);
        names.put(new String("x"), y);
        assertThrows(IllegalArgumentException.class, () -> new PolynomialPotential.Request(names,
            template(List.of(C)), List.of(X), GRID, ExactRational.ONE, false));
    }

    private static PolynomialPotential.Request collisionRequest(String first, String last) {
        var transition = new LinkedHashMap<String, Polynomial>();
        transition.put(first, Polynomial.zero());
        for (int i = 0; i < 256; i++) {
            var name = new StringBuilder();
            for (int j = 0; j < 8; j++) name.append(((i >>> j) & 1) == 0 ? "Aa" : "BB");
            transition.put(name.toString(), Polynomial.zero());
        }
        transition.put("x", x.subtract(one));
        transition.put(last, Polynomial.zero());
        var domain = template(List.of(C), new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, x, List.of(C)));
        return new PolynomialPotential.Request(transition, domain, List.of(X), List.of(ExactRational.ONE),
            ExactRational.ONE, false);
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
