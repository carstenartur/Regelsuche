package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static de.regelsuche.math.algorithms.modular.SharedPowerPlan.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedPowerSearchTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 20_000, 20_000);
    private static final Weights WEIGHTS = new Weights(1, 1, 8);

    @Test void derivesOneSharedPowerForRootAndReciprocalObservations() {
        var request = signedPair(1019);
        var result = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false);
        var certificate = result.certificate().orElseThrow();
        assertEquals(1, result.exactCost().orElseThrow().inversions());
        var cost = result.selectedCost().orElseThrow();
        assertEquals(0, cost.inversions());
        assertEquals(1, cost.powerCalls());
        assertEquals(1, cost.multiplications());
        assertTrue(cost.weighted() < result.exactCost().orElseThrow().weighted());
        assertTrue(result.searchWork() > 0);
        assertTrue(result.verificationWork() > 0);
        assertEquals(result.work(), result.searchWork() + result.verificationWork());
        assertTrue(certificate.schedule().steps().stream()
                .anyMatch(step -> step.operation() == Operation.POWER && step.exponent().equals(bi(254))));
        assertTrue(SharedPowerVerifier.verify(request, certificate, WEIGHTS, LIMITS, () -> false).valid());
    }

    @Test void exhaustivePrimeFieldsAgreeWithIndependentModPowObservations() {
        for (int p : new int[]{3, 7, 11, 19, 23, 31, 43, 47, 59, 67, 71, 79, 83, 103, 107, 127}) {
            var request = signedPair(p);
            var certificate = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false)
                    .certificate().orElseThrow();
            for (int x = 1; x < p; x++) assertObservations(request, certificate, p, x);
        }
    }

    @Test void sharesExactConsecutivePowersWithoutChangingTheContract() {
        var request = new Request(bi(1_000_002), List.of(exact(65_537), exact(65_538), exact(65_539)));
        var result = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false);
        var cost = result.selectedCost().orElseThrow();
        assertEquals(1, cost.powerCalls());
        assertEquals(2, cost.multiplications());
        assertEquals(0, cost.inversions());
        assertObservations(request, result.certificate().orElseThrow(), 1_000_003, 17);
    }

    @Test void transfersToCubeObservationsAndMixedExactOutputs() {
        var request = new Request(bi(30), List.of(new Output(bi(22), bi(3)),
                new Output(bi(-7), bi(3)), exact(1)));
        var certificate = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false)
                .certificate().orElseThrow();
        for (int x = 1; x < 31; x++) assertObservations(request, certificate, 31, x);
    }

    @Test void neverReusesARelaxedRepresentativeAsAnExactRequestedPower() {
        var request = new Request(bi(30), List.of(new Output(bi(22), bi(3)), exact(22),
                new Output(bi(0), bi(30)), exact(22)));
        var certificate = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false)
                .certificate().orElseThrow();
        for (int x = 1; x < 31; x++) assertObservations(request, certificate, 31, x);
        assertEquals(certificate.schedule().outputs().get(1), certificate.schedule().outputs().get(3));
    }

    @Test void retainsTheCheaperExactCandidateWhenRelaxedRepresentativesLose() {
        var result = SharedPowerSearch.search(signedPair(31), new Weights(1, 1, 1), 8, LIMITS, () -> false);
        assertEquals(4, result.selectedCost().orElseThrow().weighted());
        assertEquals(result.exactCost(), result.selectedCost());
    }

    @Test void worksForCompositeUnitGroupsWithoutClaimingFieldSigns() {
        var request = new Request(bi(4), List.of(new Output(bi(2), bi(2))));
        var certificate = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false)
                .certificate().orElseThrow();
        for (int x : new int[]{1, 2, 4, 7, 8, 11, 13, 14}) assertObservations(request, certificate, 15, x);
        var values = SharedPowerEvaluation.evaluate(request, certificate, bi(15), bi(2), LIMITS, () -> false)
                .values().orElseThrow();
        assertEquals(bi(1), values.getFirst());
        assertNotEquals(bi(4), values.getFirst());
        assertNotEquals(bi(11), values.getFirst()); // equal squares need not mean + or - over a ring
    }

    @Test void rejectsChangedSourceAndStrongerObservation() {
        var request = signedPair(1019);
        var certificate = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false)
                .certificate().orElseThrow();
        var exact = new Request(request.orderMultiple(), List.of(exact(255), exact(-255)));
        assertEquals("SOURCE_MISMATCH", SharedPowerVerifier.verify(exact, certificate, WEIGHTS, LIMITS, () -> false).reason());
        var rebound = new Certificate(exact, certificate.schedule());
        assertEquals("OBSERVATION_MISMATCH", SharedPowerVerifier.verify(exact, rebound, WEIGHTS, LIMITS, () -> false).reason());
    }

    @Test void rejectsMalformedReferencesAndOutputArity() {
        var request = new Request(bi(100), List.of(exact(3)));
        var forward = new Certificate(request, new Schedule(List.of(Step.multiply(1, 2)), List.of(2)));
        assertEquals("INVALID_REFERENCE", SharedPowerVerifier.verify(request, forward, WEIGHTS, LIMITS, () -> false).reason());
        var output = new Certificate(request, new Schedule(List.of(), List.of(-1)));
        assertFalse(SharedPowerVerifier.verify(request, output, WEIGHTS, LIMITS, () -> false).valid());
        var arity = new Certificate(request, new Schedule(List.of(), List.of(1, 1)));
        assertEquals("OUTPUT_COUNT_MISMATCH", SharedPowerVerifier.verify(request, arity, WEIGHTS, LIMITS, () -> false).reason());
    }

    @Test void checksTheConcreteGroupAssumptionIncludingZero() {
        var request = new Request(bi(6), List.of(exact(-1)));
        var certificate = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false)
                .certificate().orElseThrow();
        for (int x : new int[]{0, 2, 4}) {
            var result = SharedPowerEvaluation.evaluate(request, certificate, bi(8), bi(x), LIMITS, () -> false);
            assertTrue(result.values().isEmpty());
            assertEquals("GROUP_ASSUMPTION_FAILED", result.reason());
        }
        assertObservations(request, certificate, 7, 3);
        assertThrows(IllegalArgumentException.class,
                () -> SharedPowerEvaluation.evaluate(request, certificate, bi(1), bi(1), LIMITS, () -> false));
        assertThrows(IllegalArgumentException.class,
                () -> SharedPowerEvaluation.evaluate(request, certificate, BigInteger.ONE.shiftLeft(4096),
                        bi(1), LIMITS, () -> false));
    }

    @Test void usesTheVerifiedExponentsWithAnExplicitQuadraticExtensionConsumer() {
        int p = 43;
        var modulus = bi(p);
        var request = signedPair(p);
        var certificate = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false)
                .certificate().orElseThrow();
        var proof = SharedPowerVerifier.verify(request, certificate, WEIGHTS, LIMITS, () -> false);
        // These paired exponents are an ADDITIONAL consumer obligation beyond equal squares.
        assertEquals(List.of(bi((p + 1) / 4), bi((p - 3) / 4)), proof.outputExponents());
        long positive = 0, negative = 0;
        for (int x = 0; x < p; x++) for (int y = 0; y < p; y++) {
            int a = Math.floorMod(x * x - y * y, p);
            int b = 2 * x * y % p;
            if (b == 0) continue;
            for (int sign : new int[]{1, -1}) {
                int g = Math.floorMod(sign * (x * x + y * y), p);
                int delta = (a + g) * ((p + 1) / 2) % p;
                assertNotEquals(0, delta);
                var values = SharedPowerEvaluation.evaluate(request, certificate, modulus, bi(delta), LIMITS, () -> false)
                        .values().orElseThrow();
                int c = values.get(0).intValueExact();
                int d = b * ((p + 1) / 2) % p * values.get(1).intValueExact() % p;
                int real, imaginary;
                if (c * c % p == delta) { real = c; imaginary = d; positive++; }
                else { real = d; imaginary = Math.floorMod(-c, p); negative++; }
                assertEquals(a, Math.floorMod(real * real - imaginary * imaginary, p));
                assertEquals(b, 2 * real * imaginary % p);
            }
        }
        assertTrue(positive > 0 && negative > 0);
    }

    @Test void rawValuesDoNotPromisePairedSignsOrCanonicalRoots() {
        var request = signedPair(1019);
        var certificate = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false)
                .certificate().orElseThrow();
        var values = SharedPowerEvaluation.evaluate(request, certificate, bi(1019), bi(2), LIMITS, () -> false)
                .values().orElseThrow();
        assertEquals(bi(1018), values.get(0).multiply(values.get(1)).mod(bi(1019)));
        assertObservations(request, certificate, 1019, 2);
    }

    @Test void resourceAndCancellationStopsNeverReturnAPartialCertificate() {
        var request = signedPair(1019);
        var tiny = new IterationLimits(1, 20_000, 20_000);
        var limited = SharedPowerSearch.search(request, WEIGHTS, 8, tiny, () -> false);
        assertTrue(limited.certificate().isEmpty());
        assertEquals("WORK_LIMIT", limited.reason());
        var cancelled = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> true);
        assertTrue(cancelled.certificate().isEmpty());
        assertEquals("CANCELLED", cancelled.reason());
        var calls = new AtomicInteger();
        assertTrue(SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS,
                () -> calls.incrementAndGet() > 25).certificate().isEmpty());
        var certificate = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false)
                .certificate().orElseThrow();
        assertFalse(SharedPowerVerifier.verify(request, certificate, WEIGHTS, tiny, () -> false).valid());
        assertTrue(SharedPowerEvaluation.evaluate(request, certificate, bi(1019), bi(2), tiny,
                () -> false).values().isEmpty());
        var cells = SharedPowerSearch.search(request, WEIGHTS, 8,
                new IterationLimits(2_000_000, 1, 20_000), () -> false);
        assertEquals("TERM_LIMIT", cells.reason());
    }

    @Test void validatesImmutableBoundedRequests() {
        assertThrows(IllegalArgumentException.class, () -> new Request(bi(0), List.of(exact(1))));
        assertThrows(IllegalArgumentException.class,
                () -> new Request(bi(10), List.of(new Output(bi(1), bi(3)))));
        assertThrows(IllegalArgumentException.class, () -> new Output(bi(1), bi(0)));
        assertThrows(IllegalArgumentException.class, () -> Step.power(bi(-1)));
        assertThrows(IllegalArgumentException.class, () -> new Output(BigInteger.ONE.shiftLeft(4096), bi(1)));
        var request = new Request(bi(1), List.of(exact(-7), exact(0)));
        var certificate = SharedPowerSearch.search(request, WEIGHTS, 8, LIMITS, () -> false)
                .certificate().orElseThrow();
        assertObservations(request, certificate, 17, 1);
    }

    private static void assertObservations(Request request, Certificate certificate, int modulus, int base) {
        var m = bi(modulus);
        var x = bi(base);
        var values = SharedPowerEvaluation.evaluate(request, certificate, m, x, LIMITS, () -> false)
                .values().orElseThrow();
        for (int i = 0; i < request.outputs().size(); i++) {
            var output = request.outputs().get(i);
            var expected = x.modPow(output.exponent(), m).modPow(output.observationPower(), m);
            assertEquals(expected, values.get(i).modPow(output.observationPower(), m),
                    "modulus=" + modulus + ", base=" + base + ", output=" + i);
        }
    }

    private static Request signedPair(int prime) {
        var exponent = bi((prime + 1) / 4);
        return new Request(bi(prime - 1), List.of(new Output(exponent, bi(2)),
                new Output(exponent.negate(), bi(2))));
    }

    private static Output exact(long exponent) { return new Output(bi(exponent), BigInteger.ONE); }
    private static BigInteger bi(long value) { return BigInteger.valueOf(value); }
}
