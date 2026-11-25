package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrimeFieldMultiplicityTest {
    static final FiniteFieldFactorizationPolicy POLICY = FiniteFieldFactorizationPolicy.deterministicBerlekamp(5, 4096);
    static final FactorizationRequest.StructuralLimits LIMITS = new FactorizationRequest.StructuralLimits(1, 20, 50, 64);

    private static PolynomialRing<BigInteger> ring(int p) {
        return new PolynomialRing<>(PrimeField.of(p), List.of(new PolynomialVariable("t")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    }
    private static SparsePolynomial<BigInteger> poly(PolynomialRing<BigInteger> ring, int... coefficients) {
        return UnivariatePolynomialView.of(ring, Arrays.stream(coefficients).mapToObj(BigInteger::valueOf).toList())
            .toSparsePolynomial();
    }
    private static FactorizationRequest<BigInteger> request(SparsePolynomial<BigInteger> source, long budget) {
        return FactorizationRequest.independentComplete(source, LIMITS, 1, budget);
    }
    @Test void mixedAndInseparableLayersAndForgery() {
        var ring = ring(2);
        var source = poly(ring, 1,1,0,0,0,0,1,1);
        var request = request(source, 1_000_000);
        var result = PrimeFieldMultiplicity.factor(request, POLICY, () -> false);
        assertEquals(PrimeFieldMultiplicity.Status.COMPLETE, result.status(), result.reason());
        var proof = result.certificate().orElseThrow();
        assertEquals(Set.of(new PolynomialFactor<>(poly(ring, 1,1), 3), new PolynomialFactor<>(poly(ring, 1,1,1), 2)),
            Set.copyOf(proof.factors()));
        assertEquals(PrimeFieldMultiplicity.Status.COMPLETE, PrimeFieldMultiplicity.verify(request, POLICY, proof, () -> false).status());
        var wrong = new PrimeFieldMultiplicity.Certificate(source, BigInteger.ONE,
            List.of(new PolynomialFactor<>(poly(ring, 1,1), Integer.MAX_VALUE)));
        assertEquals(PrimeFieldMultiplicity.Status.INVALID_EVIDENCE, PrimeFieldMultiplicity.verify(request, POLICY, wrong, () -> false).status());
        assertEquals(PrimeFieldMultiplicity.Status.INCONCLUSIVE, PrimeFieldMultiplicity.factor(request, POLICY, () -> true).status());
        assertEquals(PrimeFieldMultiplicity.Status.INCONCLUSIVE, PrimeFieldMultiplicity.factor(request(source, 1), POLICY, () -> false).status());
        assertEquals(PrimeFieldMultiplicity.Status.INCONCLUSIVE, PrimeFieldMultiplicity.factor(request,
            FiniteFieldFactorizationPolicy.deterministicBerlekamp(2, 1), () -> false).status());
        assertThrows(IllegalArgumentException.class, () -> PrimeField.of(4));
    }
    @Test void independentCheckerRejectsWrongSourceReducibleFactorsRepeatedEntriesAndInexactProducts() {
        var ring = ring(3);
        var factor = poly(ring, 1,1);
        var source = factor.pow(2);
        var req = request(source, 1_000_000);
        var goodFactor = new PolynomialFactor<>(factor, 2);
        var repeated = new PolynomialFactor<>(factor, 1);
        var good = new PrimeFieldMultiplicity.Certificate(source, BigInteger.ONE, List.of(goodFactor));
        var candidates = List.of(
            new PrimeFieldMultiplicity.Certificate(factor, BigInteger.ONE, List.of(goodFactor)),
            new PrimeFieldMultiplicity.Certificate(source, BigInteger.TWO, List.of(goodFactor)),
            new PrimeFieldMultiplicity.Certificate(source, BigInteger.ONE, List.of(new PolynomialFactor<>(source, 1))),
            new PrimeFieldMultiplicity.Certificate(source, BigInteger.ONE, List.of(repeated, repeated)),
            new PrimeFieldMultiplicity.Certificate(source, BigInteger.ONE, List.of(new PolynomialFactor<>(poly(ring, 0,1), 2))));
        for (var candidate : candidates)
            assertEquals(PrimeFieldMultiplicity.Status.INVALID_EVIDENCE,
                PrimeFieldMultiplicity.verify(req, POLICY, candidate, () -> false).status());
        var verified = PrimeFieldMultiplicity.verify(req, POLICY, good, () -> false);
        var bounded = request(source, verified.work().totalWorkUnits() - 1);
        assertEquals(PrimeFieldMultiplicity.Status.INCONCLUSIVE,
            PrimeFieldMultiplicity.verify(bounded, POLICY, good, () -> false).status());
        int[] calls = {0};
        var stopped = PrimeFieldMultiplicity.factor(req, POLICY, () -> ++calls[0] > 12);
        assertEquals("CANCELLED", stopped.reason());
        assertTrue(stopped.certificate().isEmpty());
        var integers = new PolynomialRing<>(BigIntegerDomain.INSTANCE, List.of(new PolynomialVariable("t")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        assertEquals(PrimeFieldMultiplicity.Status.UNSUPPORTED,
            PrimeFieldMultiplicity.factor(request(poly(integers, 1,1), 10_000), POLICY, () -> false).status());
    }

    @Test void exhaustiveBinaryThroughDegreeFiveAndTernaryThroughDegreeThree() {
        for (int p : List.of(2,3)) {
            var ring = ring(p);
            int count = p == 2 ? 64 : 81;
            for (int code = 1; code < count; code++) {
                var source = poly(ring, digits(code, p));
                var result = PrimeFieldMultiplicity.factor(request(source, 1_000_000), POLICY, () -> false);
                assertEquals(PrimeFieldMultiplicity.Status.COMPLETE, result.status(), result.reason());
                var proof = result.certificate().orElseThrow();
                var product = SparsePolynomial.constant(ring, proof.unit());
                for (var item : proof.factors()) {
                    product = product.multiply(item.polynomial().pow(item.multiplicity()));
                    int[] f = UnivariatePolynomialView.from(item.polynomial()).coefficients().stream()
                        .mapToInt(BigInteger::intValueExact).toArray();
                    for (int d = 1; d <= (f.length - 1) / 2; d++) {
                        int monic = (int) Math.pow(p, d);
                        for (int k = monic; k < 2 * monic; k++)
                            assertFalse(divides(digits(k, p), f, p), "reducible reported factor");
                    }
                }
                assertEquals(source, product);
            }
        }
    }
    private static int[] digits(int n, int p) {
        var digits = new ArrayList<Integer>();
        do { digits.add(n % p); n /= p; } while (n != 0);
        return digits.stream().mapToInt(Integer::intValue).toArray();
    }
    private static boolean divides(int[] monic, int[] f, int p) {
        int[] r = f.clone();
        for (int i = f.length - 1; i >= monic.length - 1; i--) {
            int lead = r[i];
            for (int j = 0; j < monic.length; j++)
                r[i - monic.length + 1 + j] = Math.floorMod(r[i - monic.length + 1 + j] - lead * monic[j], p);
        }
        return Arrays.stream(r).allMatch(v -> v == 0);
    }
}
