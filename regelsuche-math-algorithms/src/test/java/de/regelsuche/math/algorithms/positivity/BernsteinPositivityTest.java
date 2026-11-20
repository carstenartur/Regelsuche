package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BernsteinPositivityTest {
    static final IterationLimits LIMITS = new IterationLimits(2_000_000, 10_000, 30_000);
    static ExactRational q(String value) { return ExactRational.parse(value); }
    static SparsePolynomial.Exponent e(int x, int y) { return new SparsePolynomial.Exponent(x, y); }
    static BernsteinPositivity.Interval interval(String lo, String hi) {
        return new BernsteinPositivity.Interval(q(lo), q(hi));
    }
    static BernsteinPositivity.Request square(String constant) {
        return new BernsteinPositivity.Request(new SparsePolynomial(List.of(2),
            Map.of(e(2,0), q("1"), e(1,0), q("-1"), e(0,0), q(constant))), List.of(interval("0","1")));
    }

    @Test void negativeMiddleCoefficientIsInconclusiveUntilMidpointSplit() {
        var request = square("3/8");
        var root = BernsteinPositivity.prove(request, 0, LIMITS, () -> false);
        assertEquals(BernsteinPositivity.Status.INCONCLUSIVE, root.status());
        assertTrue(root.certificate().isEmpty());
        var proof = BernsteinPositivity.prove(request, 1, LIMITS, () -> false).certificate().orElseThrow();
        assertEquals(BernsteinPositivity.Claim.STRICTLY_POSITIVE, proof.claim());
        assertEquals(List.of(new BernsteinPositivity.Split(0),
            new BernsteinPositivity.Leaf(List.of(interval("0","1/2")), List.of(q("3/8"), q("1/8"), q("1/8"))),
            new BernsteinPositivity.Leaf(List.of(interval("1/2","1")), List.of(q("1/8"), q("1/8"), q("3/8")))),
            proof.steps());
        assertTrue(BernsteinPositivity.verify(request, proof, LIMITS, () -> false).valid());
        assertEquals(BernsteinPositivity.Claim.NONNEGATIVE,
            BernsteinPositivity.prove(square("1/4"), 1, LIMITS, () -> false).certificate().orElseThrow().claim());
    }

    @Test void bivariateDegreeElevationAndShiftedRationalBox() {
        // 3 + (2x+1)(3-y)/6 is separately affine: degree elevation evaluates it at each grid point.
        var poly = new SparsePolynomial(List.of(2,2), Map.of(e(0,0),q("7/2"), e(1,0),q("1"),
            e(0,1),q("-1/6"), e(1,1),q("-1/3")));
        var source = new BernsteinPositivity.Request(poly, List.of(interval("-1/2","1"),interval("-1","2")));
        var cert = BernsteinPositivity.prove(source, 0, LIMITS, () -> false).certificate().orElseThrow();
        var leaf = (BernsteinPositivity.Leaf) cert.steps().getFirst();
        assertEquals(List.of(q("3"),q("3"),q("3"), q("4"),q("29/8"),q("13/4"),
            q("5"),q("17/4"),q("7/2")), leaf.coefficients());
        assertTrue(BernsteinPositivity.verify(source, cert, LIMITS, () -> false).valid());
    }

    @Test void identityAndCoverAreIndependentlyChecked() {
        var source = square("3/8");
        var cert = BernsteinPositivity.prove(source, 1, LIMITS, () -> false).certificate().orElseThrow();
        var first = (BernsteinPositivity.Leaf) cert.steps().get(1);
        var second = (BernsteinPositivity.Leaf) cert.steps().get(2);
        var split = cert.steps().getFirst();
        var corrupt = new BernsteinPositivity.Leaf(first.box(), List.of(q("3/8"),q("1/7"),q("1/8")));
        for (var steps : List.<List<BernsteinPositivity.Step>>of(List.of(split,first),
                List.of(split,first,first), List.of(split,second,first), List.of(split,first,second,second),
                List.of(split,corrupt,second), List.of(new BernsteinPositivity.Split(-1),first,second))) {
            var forged = new BernsteinPositivity.Certificate(source, cert.claim(), steps);
            assertFalse(BernsteinPositivity.verify(source, forged, LIMITS, () -> false).valid());
        }
        assertFalse(BernsteinPositivity.verify(square("1/4"), cert, LIMITS, () -> false).valid());
        var changedBox = new BernsteinPositivity.Request(source.polynomial(), List.of(interval("1","2")));
        assertFalse(BernsteinPositivity.verify(changedBox, cert, LIMITS, () -> false).valid());
        var square = square("1/4");
        var weak = BernsteinPositivity.prove(square, 1, LIMITS, () -> false).certificate().orElseThrow();
        var strict = new BernsteinPositivity.Certificate(square, cert.claim(), weak.steps());
        assertFalse(BernsteinPositivity.verify(square, strict, LIMITS, () -> false).valid());
    }

    @Test void resourceStopsNeverReturnAPartialCertificate() {
        var source = square("3/8");
        var certificate = BernsteinPositivity.prove(source, 1, LIMITS, () -> false).certificate().orElseThrow();
        for (var limits : List.of(LIMITS.withMaximumWork(10), new IterationLimits(10000,2,30000))) {
            var result = BernsteinPositivity.prove(source, 1, limits, () -> false);
            assertEquals(BernsteinPositivity.Status.UNAVAILABLE, result.status());
            assertTrue(result.certificate().isEmpty());
            assertFalse(BernsteinPositivity.verify(source, certificate, limits, () -> false).valid());
        }
        var calls = new AtomicInteger();
        var stopped = BernsteinPositivity.prove(source, 1, LIMITS, () -> calls.incrementAndGet() > 10);
        assertEquals("CANCELLED", stopped.reason());
        assertTrue(stopped.certificate().isEmpty());
        assertFalse(BernsteinPositivity.verify(source, certificate, LIMITS, () -> true).valid());
        var huge = new SparsePolynomial(List.of(Integer.MAX_VALUE,Integer.MAX_VALUE), Map.of());
        var rectangle = new BernsteinPositivity.Request(huge, List.of(interval("0","1"),interval("0","1")));
        assertEquals("TERM_LIMIT", BernsteinPositivity.prove(rectangle, 1, LIMITS, () -> false).reason());
    }
    @Test void rootBoundsExposeSignedEvidenceAndBindExtrema() {
        var source = square("3/8");
        var bound = BernsteinPositivity.bounds(source, LIMITS, () -> false).bounds().orElseThrow();
        assertEquals(List.of(q("3/8"), q("-1/8"), q("3/8")), bound.coefficients());
        assertEquals(q("-1/8"), bound.lower());
        assertEquals(q("3/8"), bound.upper());
        assertTrue(BernsteinPositivity.verifyBounds(source,bound,LIMITS,() -> false).valid());
        assertFalse(BernsteinPositivity.verifyBounds(square("1/4"),bound,LIMITS,() -> false).valid());
        var forged = new BernsteinPositivity.Bounds(source,bound.coefficients(), q("0"),bound.upper());
        assertFalse(BernsteinPositivity.verifyBounds(source,forged,LIMITS,() -> false).valid());
        assertTrue(BernsteinPositivity.bounds(source,LIMITS,() -> true).bounds().isEmpty());
    }

    @Test void independentQuadraticBlossomOracleOnSixtyFourRectangles() {
        var random = new java.util.Random(79);
        for (int run = 0; run < 64; run++) {
            var x = new BernsteinPositivity.Interval(ExactRational.integer(-1-random.nextInt(4)),q("1/3"));
            var y = new BernsteinPositivity.Interval(q("-2/3"),ExactRational.integer(1+random.nextInt(4)));
            var terms = new java.util.HashMap<SparsePolynomial.Exponent,ExactRational>();
            for (int i=0; i<=2; i++) for (int j=0; j<=2; j++) terms.put(e(i,j),ExactRational.integer(random.nextInt(9)-4));
            var request = new BernsteinPositivity.Request(new SparsePolynomial(List.of(2,2),terms),List.of(x,y));
            var bound = BernsteinPositivity.bounds(request,LIMITS,() -> false).bounds().orElseThrow();
            var oracle = new java.util.ArrayList<ExactRational>();
            for (int k=0; k<=2; k++) for (int l=0; l<=2; l++) {
                var sum = ExactRational.ZERO;
                for (var term : terms.entrySet()) {
                    sum = sum.add(term.getValue().multiply(blossom(x,k,term.getKey().x()))
                        .multiply(blossom(y,l,term.getKey().y())));
                }
                oracle.add(sum);
            }
            assertEquals(oracle,bound.coefficients());
            assertTrue(BernsteinPositivity.verifyBounds(request,bound,LIMITS,() -> false).valid());
        }
    }

    // The quadratic polar forms of 1, x and x² are 1, (a+b)/2 and ab, respectively.
    private static ExactRational blossom(BernsteinPositivity.Interval axis, int index, int exponent) {
        var a = index == 2 ? axis.upper() : axis.lower();
        var b = index == 0 ? axis.lower() : axis.upper();
        return switch (exponent) {
            case 0 -> ExactRational.ONE;
            case 1 -> a.add(b).divide(ExactRational.integer(2));
            default -> a.multiply(b);
        };
    }

    @Test void subdividesBothAxesAndChecksEveryLeafOnTheClosedRectangle() {
        var p = new SparsePolynomial(List.of(2,2),Map.of(e(2,0),q("1"),e(1,0),q("-1"),
            e(0,2),q("1"),e(0,1),q("-1"),e(0,0),q("1/2")));
        var source = new BernsteinPositivity.Request(p,List.of(interval("0","1"),interval("0","1")));
        assertEquals(BernsteinPositivity.Status.INCONCLUSIVE,BernsteinPositivity.prove(source,1,LIMITS,() -> false).status());
        var cert = BernsteinPositivity.prove(source,2,LIMITS,() -> false).certificate().orElseThrow();
        assertEquals(4,cert.steps().stream().filter(BernsteinPositivity.Leaf.class::isInstance).count());
        assertEquals(BernsteinPositivity.Claim.NONNEGATIVE,cert.claim());
        assertTrue(BernsteinPositivity.verify(source,cert,LIMITS,() -> false).valid());
    }
    @Test void signedRuntimeBitLimitAndZeroDegreeInputs() {
        var tooLarge = ExactRational.integer(java.math.BigInteger.ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS).negate());
        var oversized = new BernsteinPositivity.Request(new SparsePolynomial(List.of(0),Map.of(e(0,0),tooLarge)),
            List.of(interval("0","1")));
        assertEquals("RUNTIME_BIT_LIMIT",BernsteinPositivity.bounds(oversized,LIMITS,() -> false).reason());
        var zero = new BernsteinPositivity.Request(new SparsePolynomial(List.of(0),Map.of()),List.of(interval("0","1")));
        var proof = BernsteinPositivity.prove(zero,0,LIMITS,() -> false).certificate().orElseThrow();
        assertEquals(BernsteinPositivity.Claim.NONNEGATIVE,proof.claim());
        assertTrue(BernsteinPositivity.verify(zero,proof,LIMITS,() -> false).valid());
        assertThrows(IllegalArgumentException.class,() -> new SparsePolynomial(List.of(1),Map.of(e(0,1),q("1"))));
        assertThrows(IllegalArgumentException.class,() -> interval("1","1"));
    }
}
