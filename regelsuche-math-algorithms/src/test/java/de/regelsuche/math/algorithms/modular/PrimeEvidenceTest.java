package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static de.regelsuche.math.algorithms.modular.PrimeEvidence.*;
import static java.math.BigInteger.ONE;
import static java.math.BigInteger.valueOf;
import static org.junit.jupiter.api.Assertions.*;

class PrimeEvidenceTest {
    private static final IterationLimits LIMITS = new IterationLimits(3_000_000, 1_000, 30_000);
    private static final BigInteger FIELD = new BigInteger(
            "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);

    @Test void smallLeavesRequireDeterministicPrimalityWithinIntRange() {
        for (int prime : List.of(2, 3, 31, 97, 2_147_483_647)) {
            assertValid(PrimeEvidenceCatalog.forModulus(valueOf(prime)).orElseThrow());
        }
        for (int other : List.of(-3, 0, 1, 4, 9, 49, 341, 561, 2_147_395_600)) {
            assertInvalid(new SmallPrime(valueOf(other)));
        }
        assertInvalid(new SmallPrime(FIELD));
        assertTrue(PrimeEvidenceCatalog.forModulus(FIELD.add(ONE)).isEmpty());
        assertFalse(PrimeEvidenceVerifier.verify(valueOf(11), new SmallPrime(valueOf(7)), LIMITS, () -> false).valid());
        assertFalse(PrimeEvidenceVerifier.verify(valueOf(11), null, LIMITS, () -> false).valid());
    }

    @Test void lucasRecursesAndAllowsReusedPrimesInDifferentNodes() {
        var eleven = new Lucas(valueOf(11), valueOf(2), List.of(factor(2, 1), factor(5, 1)));
        var factors = new ArrayList<>(List.of(factor(2, 1), new Factor(eleven, 1)));
        var proof = new Lucas(valueOf(23), valueOf(5), factors);
        factors.clear();
        assertValid(proof);
        assertInvalid(new Lucas(valueOf(23), valueOf(5), List.of(factor(2, 1))));
        assertInvalid(new Lucas(valueOf(23), valueOf(4), proof.factors())); // order 11, not 22
        assertInvalid(new Lucas(valueOf(23), valueOf(23), proof.factors()));
        assertInvalid(new Lucas(valueOf(23), valueOf(5), List.of(factor(22, 1))));
        assertInvalid(new Lucas(valueOf(17), valueOf(3), List.of(factor(2, 2), factor(2, 2))));
        // 2^560 = 1 modulo 561, but Fermat alone accepts this composite.
        assertInvalid(new Lucas(valueOf(561), valueOf(2), List.of(factor(2, 4), factor(5, 1), factor(7, 1))));
    }

    @Test void malformedChildAndExponentCannotScaleReplay() {
        assertInvalid(new Lucas(valueOf(23), valueOf(5), List.of(factor(23, 1))));
        assertInvalid(new Lucas(valueOf(23), valueOf(5), List.of(factor(29, 1))));
        assertInvalid(new Lucas(valueOf(23), valueOf(5), List.of(factor(2, Integer.MAX_VALUE))));
        assertInvalid(new Lucas(valueOf(23), valueOf(5), List.of(factor(2, 0))));
        assertInvalid(new Lucas(valueOf(23), valueOf(5), List.of(factor(2, -1))));
        assertThrows(IllegalArgumentException.class, () -> new SmallPrime(ONE.shiftLeft(100_000)));
    }

    @Test void decreasingButExcessivelyDeepEvidenceStopsBeforeStackExhaustion() {
        Certificate proof = new SmallPrime(valueOf(3));
        for (int i = 0; i < 600; i++) {
            var parent = proof.modulus().shiftLeft(1).add(ONE);
            proof = new Lucas(parent, valueOf(2), List.of(new Factor(proof, 1), factor(2, 1)));
        }
        var result = PrimeEvidenceVerifier.verify(proof.modulus(), proof, LIMITS, () -> false);
        assertFalse(result.valid());
        assertEquals("PRIME_DEPTH_LIMIT", result.reason());
    }

    @Test void ellipticCertificateUsesOrderBoundAndCheckedGroupLaw() {
        // Over F17, (3,6) on y² = x³+2x+3 has order 11 > 2 floor(sqrt(17))+1.
        assertValid(curve(17, 2, 3, 3, 6, 11));
        assertInvalid(curve(17, 2, 3, 3, 6, 13)); // valid curve/point, wrong scalar
        assertInvalid(curve(17, 0, 1, 0, 1, 3)); // true order 3, insufficient bound
        assertInvalid(curve(17, 0, 0, 1, 1, 11)); // singular curve
        assertInvalid(curve(17, 2, 3, 20, 6, 11)); // noncanonical affine coordinates
        assertInvalid(curve(17, 2, 3, 3, 6, 17)); // nondecreasing recursion
        // Both pass discriminant, equation, prime order candidate and size bound.
        // The first scalar run needs a nonunit slope; the second meets different signs modulo 5 and 7.
        var nonunit = curve(35, 0, 1, 4, 10, 13);
        assertEquals("NONUNIT_ELLIPTIC_DENOMINATOR",
                PrimeEvidenceVerifier.verify(valueOf(35), nonunit, LIMITS, () -> false).reason());
        var mixed = curve(35, 0, 12, 3, 2, 19);
        assertEquals("MIXED_ELLIPTIC_SUM",
                PrimeEvidenceVerifier.verify(valueOf(35), mixed, LIMITS, () -> false).reason());
    }

    @Test void largeCatalogWitnessIsDataAndReplaysAgainstObservedModulus() {
        var curve = assertInstanceOf(EllipticOrder.class, PrimeEvidenceCatalog.forModulus(FIELD).orElseThrow());
        assertEquals(FIELD, curve.modulus());
        assertValid(curve);
        var order = assertInstanceOf(Lucas.class, curve.orderPrime());
        assertInvalid(new EllipticOrder(FIELD, curve.a().add(ONE), curve.b(), curve.x(), curve.y(), order));
        assertInvalid(new EllipticOrder(FIELD, curve.a(), curve.b(), curve.x(), curve.y().add(ONE), order));
        assertInvalid(new EllipticOrder(FIELD, curve.a(), curve.b(), curve.x(), curve.y(),
                new Lucas(order.modulus(), ONE, order.factors())));
        assertInvalid(new EllipticOrder(FIELD, curve.a(), curve.b(), curve.x(), curve.y(),
                new Lucas(order.modulus(), order.witness(), order.factors().subList(1, order.factors().size()))));
        assertFalse(PrimeEvidenceVerifier.verify(FIELD.subtract(valueOf(2)), curve, LIMITS, () -> false).valid());
    }

    @Test void limitsCoverLeafTrialsNestedFactorsAndScalarReplay() {
        var proof = PrimeEvidenceCatalog.forModulus(FIELD).orElseThrow();
        var ok = PrimeEvidenceVerifier.verify(FIELD, proof, LIMITS, () -> false);
        assertTrue(ok.valid(), ok::reason);
        assertTrue(ok.work() > 1);
        var exact = LIMITS.withMaximumWork(ok.work());
        assertTrue(PrimeEvidenceVerifier.verify(FIELD, proof, exact, () -> false).valid());
        var shortRun = PrimeEvidenceVerifier.verify(FIELD, proof, exact.withMaximumWork(ok.work() - 1), () -> false);
        assertFalse(shortRun.valid());
        assertEquals("WORK_LIMIT", shortRun.reason());
        assertTrue(shortRun.work() < ok.work());
        var small = new SmallPrime(valueOf(Integer.MAX_VALUE));
        assertEquals("WORK_LIMIT", PrimeEvidenceVerifier.verify(
                small.modulus(), small, LIMITS.withMaximumWork(10), () -> false).reason());
        assertEquals("TERM_LIMIT", PrimeEvidenceVerifier.verify(
                FIELD, proof, new IterationLimits(3_000_000, 2, 30_000), () -> false).reason());
        var checks = new AtomicInteger();
        var cancelled = PrimeEvidenceVerifier.verify(FIELD, proof, LIMITS, () -> checks.incrementAndGet() > 20);
        assertFalse(cancelled.valid());
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.work() > 0);
    }

    private static Factor factor(int q, int multiplicity) {
        return new Factor(new SmallPrime(valueOf(q)), multiplicity);
    }

    private static EllipticOrder curve(int n, int a, int b, int x, int y, int order) {
        return new EllipticOrder(valueOf(n), valueOf(a), valueOf(b), valueOf(x), valueOf(y),
                new SmallPrime(valueOf(order)));
    }

    private static void assertValid(Certificate proof) {
        var result = PrimeEvidenceVerifier.verify(proof.modulus(), proof, LIMITS, () -> false);
        assertTrue(result.valid(), result::reason);
    }

    private static void assertInvalid(Certificate proof) {
        assertFalse(PrimeEvidenceVerifier.verify(proof.modulus(), proof, LIMITS, () -> false).valid());
    }
}
