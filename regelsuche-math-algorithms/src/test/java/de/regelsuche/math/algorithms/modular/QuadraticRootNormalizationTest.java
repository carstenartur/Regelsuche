package de.regelsuche.math.algorithms.modular;

import static de.regelsuche.math.algorithms.modular.QuadraticRootContract.Observation.EXACT_PAIR;
import static de.regelsuche.math.algorithms.modular.QuadraticRootContract.Observation.UP_TO_JOINT_SIGN;
import static de.regelsuche.math.algorithms.modular.QuadraticRootProgram.Step.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuadraticRootNormalizationTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 8192, 30_000);

    private static QuadraticRootSearch.Certificate certificate(int prime) {
        var p = BigInteger.valueOf(prime);
        var half = p.add(BigInteger.ONE).shiftRight(1);
        var exponent = half.shiftRight(1);
        var original = new QuadraticRootProgram(List.of(constant(half), add(0, 2), multiply(4, 3),
                power(5, exponent), square(6)), 7, 5,
                new QuadraticRootProgram.Arm(List.of(multiply(1, 3), inverse(6), multiply(8, 9)), 6, 10, false),
                new QuadraticRootProgram.Arm(List.of(subtract(0, 2), multiply(8, 3), power(9, exponent),
                        multiply(1, 3), inverse(10), multiply(11, 12)), 10, 13, false));
        var contract = new QuadraticRootContract("root", p, new PrimeEvidence.SmallPrime(p), original, UP_TO_JOINT_SIGN);
        return QuadraticRootSearch.search(contract, new SharedPowerPlan.Weights(1, 1, 1000), 64, LIMITS, () -> false)
                .certificate().orElseThrow();
    }

    private record Pair(int real, int imaginary) {
        Pair negate(int p) {
            return new Pair(Math.floorMod(-real, p), Math.floorMod(-imaginary, p));
        }
        Pair times(Pair other, int p) {
            return new Pair(Math.floorMod(real * other.real - imaginary * other.imaginary, p),
                    (real * other.imaginary + imaginary * other.real) % p);
        }
        Pair normalizeMask(int mask, int target, int p) {
            int bit = 3 * coordinateClass(real) + coordinateClass(imaginary);
            return ((mask >>> bit) & 1) == target ? this : negate(p);
        }
        Pair normalizeFirst(boolean realFirst, int target, int p) {
            int first = realFirst ? real : imaginary;
            int second = realFirst ? imaginary : real;
            int sign = (first == 0 ? second : first) % 2;
            return sign == target ? this : negate(p);
        }
    }

    private static int coordinateClass(int x) {
        return x == 0 ? 0 : 1 + (x % 2);
    }

    private static boolean exactForEveryPairModuloThree(int mask) {
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int target = 0; target < 2; target++) {
            var a = new Pair(x, y);
            if (!a.normalizeMask(mask, target, 3).equals(a.negate(3).normalizeMask(mask, target, 3))) return false;
        }
        return true;
    }

    private static QuadraticRootNormalization.Contract normalizer(BigInteger p, QuadraticRootNormalization.ParitySelector selector) {
        return new QuadraticRootNormalization.Contract("observer", p, selector);
    }

    @Test void allSelectorsAgreeWithDirectNormalizationOfEveryPairAtThree() {
        var root = certificate(3);
        int accepted = 0;
        for (int mask = 0; mask < 512; mask++) {
            var spec = normalizer(root.contract().modulus(), new QuadraticRootNormalization.ParitySelector(mask));
            var cert = new QuadraticRootNormalization.Certificate(root, spec);
            var result = QuadraticRootNormalization.verifyNormalized(root.contract(), spec, cert, LIMITS, () -> false);
            assertEquals(exactForEveryPairModuloThree(mask), result.valid(), "selector " + mask);
            if (result.valid()) accepted++;
        }
        assertEquals(32, accepted);
    }

    @Test void concreteCandidatesGiveExactlyTheSameNormalizedObservationsForBothPrioritiesAndTargets() {
        int differentRaw = 0;
        int tuples = 0;
        for (int p : new int[]{3, 7, 11, 19}) {
            var root = certificate(p);
            var pairs = canonicalPairs(p);
            assertSelector(root, pairs, true);
            assertSelector(root, pairs, false);
            var prepared = QuadraticRootEvaluation.prepare(root.contract(), root, LIMITS, () -> false).plan().orElseThrow();
            var inputs = nonrealNormInputs(p);
            tuples += inputs.size();
            for (var input : inputs) {
                var original = input.sourceRoot(p);
                var actual = input.candidateRoot(prepared);
                if (!original.equals(actual)) differentRaw++;
                assertScaledObservations(original, actual, pairs, p);
            }
        }
        assertEquals(464, tuples);
        assertTrue(differentRaw > 0);
    }

    private static List<Pair> canonicalPairs(int p) {
        var pairs = new ArrayList<Pair>();
        for (int x = 0; x < p; x++) for (int y = 0; y < p; y++) pairs.add(new Pair(x, y));
        return pairs;
    }

    private static void assertSelector(QuadraticRootSearch.Certificate root, List<Pair> pairs, boolean realFirst) {
        var c = root.contract();
        int p = c.modulus().intValueExact();
        var selector = realFirst ? QuadraticRootNormalization.ParitySelector.realFirst()
                : QuadraticRootNormalization.ParitySelector.imaginaryFirst();
        var spec = normalizer(c.modulus(), selector);
        var verified = QuadraticRootNormalization.verifyNormalized(c, spec,
                new QuadraticRootNormalization.Certificate(root, spec), LIMITS, () -> false);
        assertTrue(verified.valid(), verified.reason());
        for (var pair : pairs) for (int target = 0; target <= 1; target++) {
            assertEquals(pair.normalizeFirst(realFirst, target, p), pair.normalizeMask(selector.mask(), target, p));
        }
    }

    private record Input(int a, int b, int g) {
        Pair sourceRoot(int p) {
            // Independent source oracle: select the branch by squaring its root, then form b/(2r).
            var m = BigInteger.valueOf(p);
            var delta = BigInteger.valueOf((long) (a + g) * ((p + 1) / 2)).mod(m);
            var exponent = BigInteger.valueOf((p + 1) / 4);
            var r = delta.modPow(exponent, m);
            if (!r.multiply(r).mod(m).equals(delta)) {
                delta = BigInteger.valueOf((long) (a - g) * ((p + 1) / 2)).mod(m);
                r = delta.modPow(exponent, m);
            }
            var imaginary = BigInteger.valueOf(b).multiply(r.shiftLeft(1).modInverse(m)).mod(m);
            return new Pair(r.intValueExact(), imaginary.intValueExact());
        }

        Pair candidateRoot(QuadraticRootEvaluation.Prepared prepared) {
            var pair = prepared.evaluate(BigInteger.valueOf(a), BigInteger.valueOf(b), BigInteger.valueOf(g),
                    LIMITS, () -> false).pair().orElseThrow();
            return new Pair(pair.real().intValueExact(), pair.imaginary().intValueExact());
        }
    }

    private static List<Input> nonrealNormInputs(int p) {
        var inputs = new ArrayList<Input>();
        for (int a = 0; a < p; a++) for (int b = 1; b < p; b++) for (int g = 0; g < p; g++) {
            if (Math.floorMod(g * g - a * a - b * b, p) == 0) inputs.add(new Input(a, b, g));
        }
        return inputs;
    }

    private static void assertScaledObservations(Pair original, Pair actual, List<Pair> factors, int p) {
        // Includes zero scale, either axis, and every ring multiplier.
        for (var factor : factors) {
            var oldScaled = original.times(factor, p);
            var newScaled = factor.times(actual, p);
            for (int target = 0; target <= 1; target++) {
                assertEquals(oldScaled.normalizeFirst(true, target, p), newScaled.normalizeFirst(true, target, p));
                assertEquals(oldScaled.normalizeFirst(false, target, p), newScaled.normalizeFirst(false, target, p));
            }
        }
    }

    @Test void changedSourcesModulusSelectorAndRawProofFailClosed() {
        var root = certificate(19);
        var c = root.contract();
        var spec = normalizer(c.modulus(), QuadraticRootNormalization.ParitySelector.realFirst());
        var cert = new QuadraticRootNormalization.Certificate(root, spec);
        for (var changed : List.of(
                new QuadraticRootNormalization.Contract("elsewhere", spec.modulus(), spec.selector()),
                normalizer(spec.modulus(), QuadraticRootNormalization.ParitySelector.imaginaryFirst()),
                normalizer(BigInteger.valueOf(7), spec.selector()))) {
            assertFalse(QuadraticRootNormalization.verifyNormalized(c, changed, cert, LIMITS, () -> false).valid());
        }
        var wrongField = normalizer(BigInteger.valueOf(7), spec.selector());
        assertFalse(QuadraticRootNormalization.verifyNormalized(c, wrongField,
                new QuadraticRootNormalization.Certificate(root, wrongField), LIMITS, () -> false).valid());
        var differentSource = new QuadraticRootContract("elsewhere", c.modulus(), c.primeEvidence(), c.original(), c.observation());
        assertFalse(QuadraticRootNormalization.verifyNormalized(differentSource, spec, cert, LIMITS, () -> false).valid());
        var composite = BigInteger.valueOf(15);
        var invalidPrime = new QuadraticRootContract("root", composite, new PrimeEvidence.SmallPrime(composite), c.original(), c.observation());
        var compositeSpec = normalizer(composite, spec.selector());
        assertFalse(QuadraticRootNormalization.verifyNormalized(invalidPrime, compositeSpec,
                new QuadraticRootNormalization.Certificate(new QuadraticRootSearch.Certificate(invalidPrime, root.power(),
                        root.program()), compositeSpec), LIMITS, () -> false).valid());
        var program = root.program();
        var arm = program.equal();
        var steps = new ArrayList<>(arm.steps());
        int wrongReal = QuadraticRootProgram.INPUTS + program.prefix().size() + steps.size();
        steps.add(negate(arm.real()));
        var bad = new QuadraticRootProgram(program.prefix(), program.guardLeft(), program.guardRight(),
                new QuadraticRootProgram.Arm(steps, wrongReal, arm.imaginary(), arm.normalizeRealCharacter()), program.unequal());
        var tampered = new QuadraticRootNormalization.Certificate(new QuadraticRootSearch.Certificate(c, root.power(), bad), spec);
        assertFalse(QuadraticRootNormalization.verifyNormalized(c, spec, tampered, LIMITS, () -> false).valid());
        var exact = new QuadraticRootContract(c.sourceIdentity(), c.modulus(), c.primeEvidence(), c.original(), EXACT_PAIR);
        assertFalse(QuadraticRootVerifier.verify(exact, root, LIMITS, () -> false).valid());
        assertFalse(QuadraticRootVerifier.verify(exact, new QuadraticRootSearch.Certificate(exact, root.power(), program),
                LIMITS, () -> false).valid());
    }

    private static final class UntrustedInteger extends BigInteger {
        private static final long serialVersionUID = 1L;
        UntrustedInteger() { super("19"); }
        @Override public BigInteger abs() { throw new AssertionError("virtual abs"); }
        @Override public int signum() { throw new AssertionError("virtual signum"); }
        @Override public int bitLength() { throw new AssertionError("virtual bitLength"); }
        @Override public boolean equals(Object other) { throw new AssertionError("virtual equals"); }
        @Override public int hashCode() { throw new AssertionError("virtual hashCode"); }
    }

    @Test void rejectsUntrustedModulusAndUnboundedDescriptorShapes() {
        var selector = QuadraticRootNormalization.ParitySelector.realFirst();
        assertThrows(IllegalArgumentException.class, () -> normalizer(new UntrustedInteger(), selector));
        assertThrows(IllegalArgumentException.class, () -> new QuadraticRootNormalization.Contract(" ", BigInteger.TWO, selector));
        assertThrows(IllegalArgumentException.class, () -> new QuadraticRootNormalization.Contract("x".repeat(1025), BigInteger.TWO, selector));
        for (int mask : new int[]{-1, 512, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new QuadraticRootNormalization.ParitySelector(mask));
        }
    }

    @Test void oneSharedBudgetPaysForBothProofsAndFinalCancellationPreventsRelease() {
        var root = certificate(7);
        var c = root.contract();
        var spec = normalizer(c.modulus(), QuadraticRootNormalization.ParitySelector.realFirst());
        var cert = new QuadraticRootNormalization.Certificate(root, spec);
        var calls = new AtomicInteger();
        var good = QuadraticRootNormalization.verifyNormalized(c, spec, cert, LIMITS, () -> { calls.incrementAndGet(); return false; });
        assertTrue(good.valid(), good.reason());
        assertTrue(good.work() > QuadraticRootVerifier.verify(c, root, LIMITS, () -> false).work());
        assertTrue(QuadraticRootNormalization.verifyNormalized(c, spec, cert, LIMITS.withMaximumWork(good.work()), () -> false).valid());
        assertEquals("WORK_LIMIT", QuadraticRootNormalization.verifyNormalized(c, spec, cert,
                LIMITS.withMaximumWork(good.work() - 1), () -> false).reason());
        var lateCalls = new AtomicInteger();
        var late = QuadraticRootNormalization.verifyNormalized(c, spec, cert, LIMITS, () -> lateCalls.incrementAndGet() == calls.get());
        assertFalse(late.valid());
        assertEquals("CANCELLED", late.reason());
        assertEquals("CANCELLED", QuadraticRootNormalization.verifyNormalized(null, null, null, LIMITS, () -> true).reason());
    }
}
