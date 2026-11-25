package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigPrimeField;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LargePrimeFactorizationTest {
    static final LargePrimeFactorizationVerifier.Policy RESOURCES =
        new LargePrimeFactorizationVerifier.Policy(512, 100_000_000, 15_000_000_000L);
    static final LargePrimeFactorization.Policy POLICY = new LargePrimeFactorization.Policy(RESOURCES, 256, 142);
    static final List<BigInteger> PRIMES = List.of(
        BigInteger.ONE.shiftLeft(64).subtract(BigInteger.valueOf(59)),
        BigInteger.ONE.shiftLeft(128).subtract(BigInteger.valueOf(159)),
        BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE.shiftLeft(32)).subtract(BigInteger.valueOf(977)),
        new BigInteger("1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16));

    static PolynomialRing<BigInteger> ring(BigInteger p) {
        return new PolynomialRing<>(BigPrimeField.assumingPrime(p), List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    }
    static SparsePolynomial<BigInteger> poly(PolynomialRing<BigInteger> ring, long... coefficients) {
        return UnivariatePolynomialView.of(ring, Arrays.stream(coefficients).mapToObj(BigInteger::valueOf).toList())
            .toSparsePolynomial();
    }
    static FactorizationRequest<BigInteger> request(SparsePolynomial<BigInteger> source, long work) {
        return FactorizationRequest.independentComplete(source,
            new FactorizationRequest.StructuralLimits(1, 100, 101, 512), 1, work);
    }
    static FactorizationRequest<BigInteger> request(SparsePolynomial<BigInteger> source) {
        return request(source, 500_000_000L);
    }

    @Test void completeMixedRepeatedNonmonicFactorsAt64_128_256_381Bits() {
        int[] nonsquares = {2, 5, 3, 2}; // Independently checked by Euler's criterion and SymPy reference.
        int[] sizes = {64, 128, 256, 381};
        for (int i = 0; i < PRIMES.size(); i++) {
            BigInteger p = PRIMES.get(i);
            assertEquals(sizes[i], p.bitLength());
            var ring = ring(p);
            var linear = poly(ring, -2, 1);
            var quadratic = poly(ring, -nonsquares[i], 0, 1);
            var source = linear.pow(3).multiply(quadratic.pow(2)).scale(BigInteger.valueOf(7));
            var result = LargePrimeFactorization.factor(request(source), POLICY, () -> false);
            assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL, result.status(), result.reason());
            var certificate = result.certificate().orElseThrow();
            assertEquals(BigInteger.valueOf(7), certificate.unit());
            assertEquals(Set.of(new PolynomialFactor<>(linear, 3), new PolynomialFactor<>(quadratic, 2)),
                Set.copyOf(certificate.factors()));
            assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL,
                LargePrimeFactorizationVerifier.verify(request(source), RESOURCES, certificate, () -> false).status());
        }
    }

    @Test void randomizedEqualDegreeSplittingAndSeededReplay() {
        var ring = ring(PRIMES.getLast());
        var expected = Set.of(new PolynomialFactor<>(poly(ring, -1, 1), 1),
            new PolynomialFactor<>(poly(ring, -2, 1), 1), new PolynomialFactor<>(poly(ring, -3, 1), 1));
        // (x-1)(x-2)(x-3) = x^3 - 6x^2 + 11x - 6.
        var req = request(poly(ring, -6, 11, -6, 1));
        var first = LargePrimeFactorization.factor(req, POLICY, () -> false);
        var second = LargePrimeFactorization.factor(req, POLICY, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL, first.status(), first.reason());
        assertEquals(expected, Set.copyOf(first.certificate().orElseThrow().factors()));
        assertEquals(first.certificate(), second.certificate());
        assertEquals(first.work(), second.work());
        assertEquals(first.splitAttempts(), second.splitAttempts());
        assertTrue(first.splitAttempts() > 0);
        var noAttempts = new LargePrimeFactorization.Policy(RESOURCES, 0, 142);
        var exhausted = LargePrimeFactorization.factor(req, noAttempts, () -> false);
        assertEquals(LargePrimeFactorization.Status.INCONCLUSIVE, exhausted.status());
        assertEquals("SPLIT_ATTEMPT_LIMIT", exhausted.reason());
        assertTrue(exhausted.certificate().isEmpty());
    }

    @Test void oddEqualDegreeQuadraticsUseFrobeniusWithoutExpandedExponent() {
        var ring = ring(PRIMES.getLast());
        // x^2-2 and (x-1)^2-2 are distinct irreducible quadratics.
        var a = poly(ring, -2, 0, 1);
        var b = poly(ring, -1, -2, 1);
        var result = LargePrimeFactorization.factor(request(poly(ring, 2, 4, -3, -2, 1)), POLICY, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL, result.status(), result.reason());
        assertEquals(Set.of(new PolynomialFactor<>(a, 1), new PolynomialFactor<>(b, 1)),
            Set.copyOf(result.certificate().orElseThrow().factors()));
    }

    @Test void inseparableAndCharacteristicTwoTraceSplittingRemainSupported() {
        var ring = new PolynomialRing<>(PrimeField.of(2), List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        var repeated = LargePrimeFactorization.factor(request(poly(ring, 1, 1, 0, 0, 0, 0, 1, 1)), POLICY, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE, repeated.status(), repeated.reason());
        assertEquals(Set.of(new PolynomialFactor<>(poly(ring, 1, 1), 3),
            new PolynomialFactor<>(poly(ring, 1, 1, 1), 2)), Set.copyOf(repeated.certificate().orElseThrow().factors()));
        // (x^3+x+1)(x^3+x^2+1) = x^6+x^5+x^4+x^3+x^2+x+1.
        var trace = LargePrimeFactorization.factor(request(poly(ring, 1, 1, 1, 1, 1, 1, 1)), POLICY, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE, trace.status(), trace.reason());
        assertEquals(Set.of(new PolynomialFactor<>(poly(ring, 1, 1, 0, 1), 1),
            new PolynomialFactor<>(poly(ring, 1, 0, 1, 1), 1)), Set.copyOf(trace.certificate().orElseThrow().factors()));
        var pure = LargePrimeFactorization.factor(request(poly(ring, 1, 0, 0, 0, 1)), POLICY, () -> false);
        assertEquals(List.of(new PolynomialFactor<>(poly(ring, 1, 1), 4)), pure.certificate().orElseThrow().factors());
    }

    @Test void oddCharacteristicFrobeniusRootsAndIndependentlyProvedDomain() {
        var ring = new PolynomialRing<>(BigPrimeField.of(3), List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        var source = poly(ring, 2, 0, 0, 0, 0, 0, 0, 0, 0, 2);
        var result = LargePrimeFactorization.factor(request(source), POLICY, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE, result.status(), result.reason());
        assertEquals(BigInteger.TWO, result.certificate().orElseThrow().unit());
        assertEquals(List.of(new PolynomialFactor<>(poly(ring, 1, 1), 9)),
            result.certificate().orElseThrow().factors());
    }

    @Test void exhaustiveSmallFieldsMatchIndependentTrialDivision() {
        int checkedCases = 0;
        for (int p : new int[]{2, 3}) {
            var ring = new PolynomialRing<>(PrimeField.of(p), List.of(new PolynomialVariable("x")),
                PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
            int count = p == 2 ? 64 : 81;
            for (int code = 1; code < count; code++) {
                assertCompleteFactorizationByTrialDivision(polynomialFromCode(ring, code, p), p);
                checkedCases++;
            }
        }
        assertEquals(143, checkedCases);
    }

    private static SparsePolynomial<BigInteger> polynomialFromCode(PolynomialRing<BigInteger> ring, int code, int p) {
        var coefficients = new ArrayList<BigInteger>();
        for (int rest = code; rest > 0; rest /= p) coefficients.add(BigInteger.valueOf(rest % p));
        return UnivariatePolynomialView.of(ring, coefficients).toSparsePolynomial();
    }

    private static void assertCompleteFactorizationByTrialDivision(SparsePolynomial<BigInteger> source, int p) {
        var result = LargePrimeFactorization.factor(request(source), POLICY, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE, result.status(), result.reason());
        var certificate = result.certificate().orElseThrow();
        var product = SparsePolynomial.constant(source.ring(), certificate.unit());
        for (var item : certificate.factors()) {
            product = product.multiply(item.polynomial().pow(item.multiplicity()));
            assertIrreducibleByTrialDivision(item.polynomial(), p);
        }
        assertEquals(source, product);
    }

    private static void assertIrreducibleByTrialDivision(SparsePolynomial<BigInteger> factor, int p) {
        int[] coefficients = UnivariatePolynomialView.from(factor).coefficients().stream()
            .mapToInt(BigInteger::intValueExact).toArray();
        for (int degree = 1, lower = p; degree <= (coefficients.length - 1) / 2; degree++, lower *= p) {
            for (int divisorCode = lower; divisorCode < 2 * lower; divisorCode++) {
                assertFalse(monicDivides(coefficients, divisorCode, degree, p), "reducible emitted factor");
            }
        }
    }

    private static boolean monicDivides(int[] coefficients, int divisorCode, int degree, int p) {
        int[] divisor = new int[degree + 1];
        int remaining = divisorCode;
        for (int i = 0; i < divisor.length; i++, remaining /= p) divisor[i] = remaining % p;
        int[] residue = coefficients.clone();
        for (int top = residue.length - 1; top >= degree; top--) {
            int leading = residue[top];
            for (int i = 0; i <= degree; i++)
                residue[top - degree + i] = Math.floorMod(residue[top - degree + i] - leading * divisor[i], p);
        }
        return Arrays.stream(residue).allMatch(value -> value == 0);
    }

    @Test void constantsAndIrreduciblesDoNotNeedRandomSplitting() {
        var ring = ring(PRIMES.getLast());
        var noAttempts = new LargePrimeFactorization.Policy(RESOURCES, 0, 142);
        var constant = LargePrimeFactorization.factor(request(poly(ring, 9)), noAttempts, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL, constant.status(), constant.reason());
        assertEquals(BigInteger.valueOf(9), constant.certificate().orElseThrow().unit());
        assertTrue(constant.certificate().orElseThrow().factors().isEmpty());
        var irreducible = LargePrimeFactorization.factor(request(poly(ring, -2, 0, 1)), noAttempts, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE_CONDITIONAL, irreducible.status(), irreducible.reason());
        assertEquals(0, irreducible.splitAttempts());
    }

    @Test void independentSympyReferenceQuarticsExerciseDistinctAndEqualDegrees() {
        var ring = new PolynomialRing<>(PrimeField.of(5), List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        var result = LargePrimeFactorization.factor(request(poly(ring, 1, 0, 1, 0, 1, 0, 0, 0, 1)), POLICY, () -> false);
        assertEquals(LargePrimeFactorization.Status.COMPLETE, result.status(), result.reason());
        assertEquals(Set.of(new PolynomialFactor<>(poly(ring, 1, 0, -2, 1, 1), 1),
            new PolynomialFactor<>(poly(ring, 1, 0, -2, -1, 1), 1)), Set.copyOf(result.certificate().orElseThrow().factors()));
    }
}
