package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SosSynthesisTest {
    static final IterationLimits LIMITS = new IterationLimits(5_000_000, 10000, 10000);
    static final Monomial C = Monomial.constant(), X = Monomial.variable("x"), Y = Monomial.variable("y");
    static final Polynomial x = Polynomial.variable("x"), y = Polynomial.variable("y");
    static final Polynomial one = Polynomial.constant(Rational.ONE);
    static final List<ExactRational> GRID = List.of(ExactRational.ZERO, ExactRational.ONE, ExactRational.integer(2));
    static SosSynthesis.Template template(List<Monomial> basis, SosSynthesis.Premise... premises) {
        return new SosSynthesis.Template(basis, List.of(premises), GRID);
    }

    @Test void distinguishesAll144BinaryQuadraticsUsingIndependentDeterminantOracle() {
        for (int a = 0; a <= 3; a++) for (int b = -4; b <= 4; b++) for (int c = 0; c <= 3; c++) {
            var source = x.pow(2).multiply(Rational.of(a)).add(x.multiply(y).multiply(Rational.of(b)))
                .add(y.pow(2).multiply(Rational.of(c)));
            var request = new SosSynthesis.Request(source, template(List.of(X, Y)));
            var result = SosSynthesis.search(request, LIMITS, () -> false);
            assertEquals(4*a*c >= b*b, result.certificate().isPresent());
            if (result.certificate().isEmpty()) {
                assertEquals(SosSynthesis.Status.INCONCLUSIVE, result.status());
            } else {
                var proof = result.certificate().orElseThrow();
                var q = proof.grams().getFirst().matrix();
                assertEquals(ExactRational.integer(a), q.getFirst().getFirst());
                assertEquals(ExactRational.integer(b), q.getFirst().getLast().add(q.getLast().getFirst()));
                assertEquals(ExactRational.integer(c), q.getLast().getLast());
                assertTrue(SosVerifier.verify(request, proof, LIMITS, () -> false).valid());
            }
        }
    }

    @Test void searchesFreeGramEntriesAndSupportsThreeVariables() {
        var p = x.pow(4).add(x.pow(2).multiply(Rational.of(3))).add(one);
        var r = new SosSynthesis.Request(p, template(List.of(C, X, X.multiply(X))));
        assertTrue(SosSynthesis.search(r, LIMITS, () -> false).certificate().isPresent());
        var zeros = new SosSynthesis.Template(r.template().basis(), List.of(), List.of(ExactRational.ZERO));
        assertTrue(SosSynthesis.search(new SosSynthesis.Request(p, zeros), LIMITS, () -> false).certificate().isEmpty());
        var z = Polynomial.variable("z");
        var square = x.subtract(y).add(z.multiply(Rational.of(2))).pow(2);
        var three = new SosSynthesis.Request(square, template(List.of(X, Y, Monomial.variable("z"))));
        assertTrue(SosSynthesis.search(three, LIMITS, () -> false).certificate().isPresent());
    }

    @Test void signedEqualityMultiplierAndPolynomialInequalityMultiplierAreBothSynthesized() {
        var eq = new SosSynthesis.Premise(SosSynthesis.Relation.ZERO, y.subtract(x), List.of(X));
        var ge = new SosSynthesis.Premise(SosSynthesis.Relation.NONNEGATIVE, x, List.of(Y));
        var p = x.multiply(y.pow(2)).subtract(x.multiply(y.subtract(x)));
        var r = new SosSynthesis.Request(p, template(List.of(C), eq, ge));
        var proof = SosSynthesis.search(r, LIMITS, () -> false).certificate().orElseThrow();
        assertEquals(List.of(List.of(ExactRational.NEGATIVE_ONE)), proof.multipliers());
        assertTrue(SosVerifier.verify(r, proof, LIMITS, () -> false).valid());
        var omitted = new SosSynthesis.Request(p, template(List.of(C), ge));
        assertFalse(SosVerifier.verify(omitted, proof, LIMITS, () -> false).valid());
        assertTrue(SosSynthesis.search(omitted, LIMITS, () -> false).certificate().isEmpty());
        var changed = new SosSynthesis.Request(p.add(one), r.template());
        assertFalse(SosVerifier.verify(changed, proof, LIMITS, () -> false).valid());
        var fake = new SosSynthesis.Certificate(changed, proof.grams(), proof.multipliers());
        assertFalse(SosVerifier.verify(changed, fake, LIMITS, () -> false).valid());
        var forged = new SosSynthesis.Certificate(r, proof.grams(), List.of(List.of(ExactRational.ONE)));
        assertFalse(SosVerifier.verify(r, forged, LIMITS, () -> false).valid());
    }

    @Test void evenAnExactIdentityRequiresPsd() {
        var r = new SosSynthesis.Request(x.pow(2).subtract(y.pow(2)), template(List.of(X, Y)));
        var q = new SosSynthesis.Gram(List.of(List.of(ExactRational.ONE, ExactRational.ZERO),
                List.of(ExactRational.ZERO, ExactRational.NEGATIVE_ONE)));
        var bad = new SosSynthesis.Certificate(r, List.of(q), List.of());
        assertEquals(SosSynthesis.Status.INVALID_CERTIFICATE, SosVerifier.verify(r, bad, LIMITS, () -> false).status());
    }

    @Test void resourcesAndExponentOverflowLeaveQuestionOpen() {
        var r = new SosSynthesis.Request(x.pow(2), template(List.of(C, X)));
        var result = SosSynthesis.search(r, LIMITS, () -> false);
        var proof = result.certificate().orElseThrow();
        var v = SosVerifier.verify(r, proof, LIMITS, () -> false);
        assertTrue(SosVerifier.verify(r, proof, LIMITS.withMaximumWork(v.work()), () -> false).valid());
        assertEquals("WORK_LIMIT", SosVerifier.verify(r, proof, LIMITS.withMaximumWork(v.work()-1), () -> false).reason());
        assertEquals("WORK_LIMIT", SosSynthesis.search(r, LIMITS.withMaximumWork(result.work()-1), () -> false).reason());
        assertEquals("CANCELLED", SosVerifier.verify(r, proof, LIMITS, () -> true).reason());
        assertEquals("TERM_LIMIT", SosSynthesis.search(r, new IterationLimits(10000, 1, 10000), () -> false).reason());
        var huge = new Rational(BigInteger.ONE.shiftLeft(65536), BigInteger.ONE);
        var large = new SosSynthesis.Request(Polynomial.constant(huge), r.template());
        assertEquals("RUNTIME_BIT_LIMIT", SosSynthesis.search(large, LIMITS, () -> false).reason());
        var exponent = new Monomial(Map.of("x", Integer.MAX_VALUE));
        var exp = new SosSynthesis.Request(one, template(List.of(exponent)));
        assertEquals("EXPONENT_LIMIT", SosSynthesis.search(exp, LIMITS, () -> false).reason());
    }

    @Test void malformedShapesAndIncompleteBasesCannotSupplyProofs() {
        var request = new SosSynthesis.Request(x.pow(2), template(List.of(X, Y)));
        var shortRow = new SosSynthesis.Gram(List.of(List.of(ExactRational.ONE), List.of(ExactRational.ZERO)));
        var proof = new SosSynthesis.Certificate(request, List.of(shortRow), List.of());
        assertEquals(SosSynthesis.Status.INVALID_CERTIFICATE, SosVerifier.verify(request, proof, LIMITS, () -> false).status());
        var missing = new SosSynthesis.Request(x.pow(2), template(List.of(Y)));
        assertTrue(SosSynthesis.search(missing, LIMITS, () -> false).certificate().isEmpty());
    }

    @Test void stopsAtEveryWorkBoundaryAndHonorsMidComputationCancellation() {
        var request = new SosSynthesis.Request(x.pow(2), template(List.of(X)));
        var found = SosSynthesis.search(request, LIMITS, () -> false);
        for (long limit = 1; limit < found.work(); limit++) {
            var result = SosSynthesis.search(request, LIMITS.withMaximumWork(limit), () -> false);
            assertEquals("WORK_LIMIT", result.reason());
            assertTrue(result.certificate().isEmpty());
        }
        var polls = new AtomicInteger();
        assertEquals("CANCELLED", SosSynthesis.search(request, LIMITS, () -> polls.incrementAndGet() == 10).reason());
        var timeout = new IterationLimits(50000, 1000, 1);
        assertEquals("TIME_LIMIT", SosSynthesis.search(request, timeout, () -> {
            LockSupport.parkNanos(5_000_000L);
            return false;
        }).reason());
    }

    @Test void retainedExponentMapsCountTowardCoefficientTableLimit() {
        var a = new HashMap<String, Integer>();
        var b = new HashMap<String, Integer>();
        for (int i = 0; i < 50; i++) {
            a.put("a"+i, 1);
            b.put("b"+i, 1);
        }
        var request = new SosSynthesis.Request(Polynomial.zero(), template(List.of(new Monomial(a), new Monomial(b))));
        var small = new IterationLimits(100_000, 150, 10_000);
        assertEquals("TERM_LIMIT", SosSynthesis.search(request, small, () -> false).reason());
    }
}
