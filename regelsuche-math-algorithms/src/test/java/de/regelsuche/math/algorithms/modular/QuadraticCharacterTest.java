package de.regelsuche.math.algorithms.modular;

import static java.math.BigInteger.ONE;
import static java.math.BigInteger.TWO;
import static java.math.BigInteger.ZERO;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QuadraticCharacterTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 8192, 30_000);
    private static final BigInteger P61 = ONE.shiftLeft(61).subtract(ONE);
    private static final BigInteger P63 = new BigInteger("8649287619757590947");
    private static final BigInteger P64 = new BigInteger("15090709121317414903");
    private static final BigInteger P127 = ONE.shiftLeft(127).subtract(ONE);
    private static final BigInteger P381 = new BigInteger("3637458053260103038420824643578933097989333517016604917630035767968033406933320699805786902417769065013767351594239");
    private static final BigInteger P521 = ONE.shiftLeft(521).subtract(ONE);
    private static final List<BigInteger> PRIMES = List.of(P61, P63, P64, P127, P381, P521);

    @BeforeAll static void proveOraclePrimesWithoutTheJacobiImplementation() {
        for (int exponent : new int[]{61, 127, 521}) {
            assertTrue(trialPrime(exponent));
            var p = ONE.shiftLeft(exponent).subtract(ONE);
            var state = BigInteger.valueOf(4);
            for (int i = 0; i < exponent - 2; i++) state = state.multiply(state).subtract(TWO).mod(p);
            assertEquals(ZERO, state, "Lucas-Lehmer certificate");
        }
        var q32 = new BigInteger("4294967291");
        assertTrue(trialPrime(q32.longValueExact()));
        certifyLargeFactor(P63, q32);
        certifyLargeFactor(P64, q32);
        var q193 = new BigInteger("9361202042020643096476812240940527370637720659683189482671");
        certifyLargeFactor(q193, P127);
        certifyLargeFactor(P381, q193);
    }

    private static void certifyLargeFactor(BigInteger p, BigInteger provedPrimeFactor) {
        // Every prime divisor of p must have an element of order divisible by q>sqrt(p).
        assertTrue(provedPrimeFactor.multiply(provedPrimeFactor).compareTo(p) > 0);
        assertEquals(ZERO, p.subtract(ONE).mod(provedPrimeFactor));
        assertEquals(ONE, TWO.modPow(p.subtract(ONE), p));
        assertEquals(ONE, TWO.modPow(p.subtract(ONE).divide(provedPrimeFactor), p).subtract(ONE).gcd(p));
    }

    private static boolean trialPrime(long value) {
        if (value < 2 || (value & 1) == 0) return value == 2;
        for (long divisor = 3; divisor <= value / divisor; divisor += 2)
            if (value % divisor == 0) return false;
        return true;
    }

    private static int euler(BigInteger a, BigInteger p) {
        var value = a.modPow(p.subtract(ONE).shiftRight(1), p);
        if (value.signum() == 0) return 0;
        if (value.equals(ONE)) return 1;
        assertEquals(p.subtract(ONE), value);
        return -1;
    }

    private record Factor(BigInteger prime, int exponent) { }

    private static BigInteger product(List<Factor> factors) {
        var result = ONE;
        for (var factor : factors) result = result.multiply(factor.prime().pow(factor.exponent()));
        return result;
    }

    private static int factored(BigInteger a, List<Factor> factors) {
        int result = 1;
        for (var factor : factors) {
            int symbol = euler(a, factor.prime());
            if (symbol == 0) return 0;
            if ((factor.exponent() & 1) != 0) result *= symbol;
        }
        return result;
    }

    private static void check(BigInteger a, BigInteger n, int expected) {
        assertEquals(expected, QuadraticCharacter.jacobi(a, n), () -> "a=" + a + " n=" + n);
        var work = new CertificateWork(LIMITS, () -> false);
        assertEquals(expected, QuadraticCharacter.jacobi(a, n, work));
        assertTrue(work.used() > 0, "Every valid invocation pays for initial normalization");
    }

    @Test void smallSignedPrimeInputsAgreeWithEulerIncludingZero() {
        for (int p : new int[]{3, 5, 7, 11, 19, 31, 43}) {
            assertTrue(trialPrime(p));
            var modulus = BigInteger.valueOf(p);
            for (int a = -2 * p; a <= 2 * p; a++) {
                var numerator = BigInteger.valueOf(a);
                check(numerator, modulus, euler(numerator, modulus));
            }
        }
    }

    @Test void factoredOddDenominatorsIncludeNonunitsAndDenominatorOne() {
        for (int n = 1; n < 200; n += 2) {
            var factors = new ArrayList<Factor>();
            int remaining = n;
            for (int divisor = 3; divisor <= remaining / divisor; divisor += 2) {
                int exponent = 0;
                while (remaining % divisor == 0) { remaining /= divisor; exponent++; }
                if (exponent != 0) factors.add(new Factor(BigInteger.valueOf(divisor), exponent));
            }
            if (remaining > 1) factors.add(new Factor(BigInteger.valueOf(remaining), 1));
            assertEquals(BigInteger.valueOf(n), product(factors));
            for (var factor : factors) assertTrue(trialPrime(factor.prime().longValueExact()));
            for (int a = -n; a <= n; a++) {
                var numerator = BigInteger.valueOf(a);
                check(numerator, BigInteger.valueOf(n), factored(numerator, factors));
            }
        }
    }

    @Test void independentLargePrimeAndFactoredCompositeOracles() {
        var random = new Random(0x6319382026L);
        for (var p : PRIMES) {
            for (int i = 0; i < 64; i++) {
                var a = new BigInteger(p.bitLength() + 9, random);
                if ((i & 1) != 0) a = a.negate();
                check(a, p, euler(a, p));
                if (i < 16) {
                    var factors = List.of(new Factor(p, 1 + i % 3), new Factor(BigInteger.valueOf(3), 2));
                    check(a, product(factors), factored(a, factors));
                    check(p, product(factors), 0);
                }
            }
        }
    }

    @Test void wordBoundariesAndMaximumSupportedMagnitudeUseKnownFactors() {
        var factors = List.of(List.of(new Factor(BigInteger.valueOf(3), 39)),
                List.of(new Factor(BigInteger.valueOf(5), 27)),
                List.of(new Factor(BigInteger.valueOf(3), 40)),
                List.of(new Factor(BigInteger.valueOf(3), 41)));
        for (int i = 0; i < factors.size(); i++) {
            var n = product(factors.get(i));
            assertEquals(62 + i, n.bitLength());
            for (int shift = 60; shift <= 66; shift++) for (long delta : new long[]{-1, 0, 1}) {
                var a = ONE.shiftLeft(shift).add(BigInteger.valueOf(delta));
                check(a, n, factored(a, factors.get(i)));
                check(a.negate(), n, factored(a.negate(), factors.get(i)));
            }
            for (var a : List.of(ZERO, ONE, n, n.subtract(ONE), n.add(ONE))) check(a, n, factored(a, factors.get(i)));
        }
        var largest = List.of(new Factor(BigInteger.valueOf(3), 2584));
        var n = product(largest);
        assertEquals(SharedPowerPlan.MAX_BITS, n.bitLength());
        for (var a : List.of(ONE.shiftLeft(4095), ONE.shiftLeft(2048), n.subtract(ONE), ZERO)) {
            check(a, n, factored(a, largest));
            check(a.negate(), n, factored(a.negate(), largest));
        }
    }

    @Test void bothOperandsMustFitBeforeTheNativeTail() {
        for (var p : List.of(P64, P127, P381, P521)) for (int delta : new int[]{1, 3, 17, 57, 127}) {
            var a = ONE.shiftLeft(62).subtract(BigInteger.valueOf(delta));
            assertTrue(a.bitLength() <= 63);
            assertTrue(p.subtract(a).bitLength() > 63);
            check(a, p, euler(a, p));
            check(a.shiftLeft(2048).add(ONE), P63, euler(a.shiftLeft(2048).add(ONE), P63));
        }
        for (int delta : new int[]{1, 3, 57}) {
            var a = ONE.shiftLeft(32).add(BigInteger.valueOf(delta));
            assertEquals(31, P64.bitLength() - a.bitLength());
            assertTrue(P64.subtract(a).bitLength() > 63);
            check(a, P64, euler(a, P64));
        }
    }

    @Test void wordGapBoundariesAndLongZeroRunsPreserveSignedSymbols() {
        for (var p : List.of(P64, P127, P381)) {
            for (int gap : new int[]{31, 32, 33}) for (int delta : new int[]{1, 3, 57}) {
                var a = ONE.shiftLeft(p.bitLength() - gap - 1).add(BigInteger.valueOf(delta));
                assertEquals(gap, p.bitLength() - a.bitLength());
                check(a, p, euler(a, p));
                check(a.negate(), p, euler(a.negate(), p));
            }
            for (var a : List.of(ONE, p.subtract(ONE), ONE.shiftLeft(p.bitLength() / 2),
                    BigInteger.valueOf(3).shiftLeft(p.bitLength() - 3))) check(a, p, euler(a, p));
        }
        var factors = List.of(new Factor(P127, 3));
        var n = product(factors);
        for (int gap : new int[]{31, 32, 33}) {
            var a = ONE.shiftLeft(n.bitLength() - gap - 1).add(ONE);
            check(a, n, factored(a, factors));
            check(a.negate(), n, factored(a.negate(), factors));
        }
    }

    @Test void initialNormalizationPaysAndPollsEvenWhenNoLoopRuns() {
        for (var pair : List.of(new BigInteger[]{ZERO, P127}, new BigInteger[]{ZERO, ONE},
                new BigInteger[]{P127, P127}, new BigInteger[]{ONE.shiftLeft(4095).negate(), ONE})) {
            var a = pair[0]; var n = pair[1];
            long normalization = 1 + (1L + a.abs().bitLength() / 64) * (1L + n.bitLength() / 64);
            var exact = new CertificateWork(LIMITS.withMaximumWork(normalization), () -> false);
            assertEquals(n.equals(ONE) ? 1 : 0, QuadraticCharacter.jacobi(a, n, exact));
            assertEquals(normalization, exact.used());
            var oneLess = new CertificateWork(LIMITS.withMaximumWork(normalization - 1), () -> false);
            assertEquals("WORK_LIMIT", assertThrows(CertificateWork.Stop.class,
                    () -> QuadraticCharacter.jacobi(a, n, oneLess)).getMessage());
            var cancelled = new CertificateWork(LIMITS, () -> true);
            assertEquals("CANCELLED", assertThrows(CertificateWork.Stop.class,
                    () -> QuadraticCharacter.jacobi(a, n, cancelled)).getMessage());
            assertEquals(0, cancelled.used());
            var exhausted = new CertificateWork(LIMITS.withMaximumWork(1), () -> false);
            exhausted.chargeCompleted(1);
            assertEquals("WORK_LIMIT", assertThrows(CertificateWork.Stop.class,
                    () -> QuadraticCharacter.jacobi(a, n, exhausted)).getMessage());
        }
        // Magnitude at an exact 64-bit boundary differs from negative BigInteger.bitLength().
        var negativeBoundary = ONE.shiftLeft(63).negate();
        var exact = new CertificateWork(LIMITS.withMaximumWork(3), () -> false);
        assertEquals(1, QuadraticCharacter.jacobi(negativeBoundary, ONE, exact));
        assertEquals(3, exact.used());
    }

    @Test void nativeAndMultiwordPathsRequireTheirEntireReportedWork() {
        var nativeOnly = new CertificateWork(LIMITS, () -> false);
        assertEquals(1, QuadraticCharacter.jacobi(BigInteger.valueOf(5), BigInteger.valueOf(11), nativeOnly));
        assertEquals(6, nativeOnly.used(), "Normalization and two one-word Euclidean rounds cost two each");
        var imbalanced = new CertificateWork(LIMITS, () -> false);
        assertEquals(1, QuadraticCharacter.jacobi(ONE, P127, imbalanced));
        assertEquals(9, imbalanced.used(), "Normalization, two-word shift/comparison and remainder cost three each");
        for (var pair : List.of(new BigInteger[]{BigInteger.valueOf(5), BigInteger.valueOf(11)},
                new BigInteger[]{ONE, P127}, new BigInteger[]{ONE.shiftLeft(32).add(ONE), P64},
                new BigInteger[]{P381.shiftRight(1).subtract(TWO), P381})) {
            var work = new CertificateWork(LIMITS, () -> false);
            int expected = euler(pair[0], pair[1]);
            assertEquals(expected, QuadraticCharacter.jacobi(pair[0], pair[1], work));
            var exact = new CertificateWork(LIMITS.withMaximumWork(work.used()), () -> false);
            assertEquals(expected, QuadraticCharacter.jacobi(pair[0], pair[1], exact));
            var shortWork = new CertificateWork(LIMITS.withMaximumWork(work.used() - 1), () -> false);
            assertEquals("WORK_LIMIT", assertThrows(CertificateWork.Stop.class,
                    () -> QuadraticCharacter.jacobi(pair[0], pair[1], shortWork)).getMessage());
        }
    }

    @Test void cancellationAtEveryChargedPhaseStopsBeforeItsWork() {
        for (var pair : List.of(new BigInteger[]{BigInteger.valueOf(5), BigInteger.valueOf(11)},
                new BigInteger[]{ONE, P127}, new BigInteger[]{ONE.shiftLeft(32).add(ONE), P64})) {
            var completed = new ArrayList<Long>();
            var holder = new CertificateWork[1];
            holder[0] = new CertificateWork(LIMITS, () -> { completed.add(holder[0].used()); return false; });
            assertEquals(euler(pair[0], pair[1]), QuadraticCharacter.jacobi(pair[0], pair[1], holder[0]));
            for (int i = 0; i < completed.size(); i++) {
                int stopAt = i + 1;
                var polls = new AtomicInteger();
                var cancelled = new CertificateWork(LIMITS, () -> polls.incrementAndGet() == stopAt);
                assertEquals("CANCELLED", assertThrows(CertificateWork.Stop.class,
                        () -> QuadraticCharacter.jacobi(pair[0], pair[1], cancelled)).getMessage());
                assertEquals(completed.get(i).longValue(), cancelled.used());
                assertEquals(stopAt, polls.get());
            }
        }
    }

    @Test void originalValidationOrderingPrecedesAnyLedgerPoll() {
        var polls = new AtomicInteger();
        var work = new CertificateWork(LIMITS, () -> { polls.incrementAndGet(); return true; });
        for (var n : List.of(ZERO, TWO, BigInteger.valueOf(-3)))
            assertEquals("ODD_DENOMINATOR_REQUIRED", assertThrows(IllegalArgumentException.class,
                    () -> QuadraticCharacter.jacobi(ONE, n, work)).getMessage());
        for (var a : new BigInteger[]{null, ONE.shiftLeft(4096), new DerivedInteger()})
            assertEquals("POWER_INTEGER_LIMIT", assertThrows(IllegalArgumentException.class,
                    () -> QuadraticCharacter.jacobi(a, ZERO, work)).getMessage());
        assertEquals("POWER_INTEGER_LIMIT", assertThrows(IllegalArgumentException.class,
                () -> QuadraticCharacter.jacobi(ONE, null, work)).getMessage());
        assertEquals(0, polls.get());
        assertEquals(1, QuadraticCharacter.jacobi(ZERO, ONE));
        assertEquals(1, QuadraticCharacter.jacobi(ZERO, ONE, null));
    }

    private static final class DerivedInteger extends BigInteger {
        private static final long serialVersionUID = 1L;
        DerivedInteger() { super("1"); }
    }
}
