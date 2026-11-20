package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static de.regelsuche.math.algorithms.modular.MontgomerySchedule.*;
import static org.junit.jupiter.api.Assertions.*;

class MontgomeryScheduleTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 4096, 10_000);
    private static final Parameters MLKEM = new Parameters(16, 3329, 62209);
    private static final Parameters MLDSA = new Parameters(32, 8380417, 58728449);

    @Test void delaysAllSupportedMldsaLengthsAndReplaysTheExactRequest() {
        for (int length : List.of(4, 5, 7)) {
            var inputs = inputs(length, Word.INT, new Interval(0, 8380416), new Interval(-34162429, 34162429));
            var result = MontgomeryReductionPlanner.dotProduct(MLDSA, inputs, products(length),
                    Word.INT, Observation.CANONICAL_RESIDUE, LIMITS, () -> false);
            var certificate = result.certificate().orElseThrow(() -> new AssertionError(result.reason()));
            assertEquals(length, certificate.sourceReductions());
            assertEquals(1, certificate.targetReductions());
            assertTrue(verify(certificate.request(), certificate, LIMITS, () -> false).valid());
            if (length == 7) assertEquals(new Interval(-2004067566133248L, 2004067566133248L),
                    certificate.targetRanges().get(certificate.targetRanges().size() - 2));
            compareConcrete(certificate.request(), 1000, 20261120 + length);
        }
    }

    @Test void checksBothQuadraticSchedulesWithUnsignedDecodedInputs() {
        for (int length : List.of(2, 3, 4)) {
            var inputs = new ArrayList<Input>();
            var terms = new ArrayList<MontgomeryReductionPlanner.QuadraticProduct>();
            for (int i = 0; i < length; i++) {
                int n = inputs.size();
                inputs.add(new Input("a0_" + i, Word.SHORT, new Interval(0, 4095)));
                inputs.add(new Input("a1_" + i, Word.SHORT, new Interval(0, 4095)));
                inputs.add(new Input("b0_" + i, Word.SHORT, new Interval(0, 3329)));
                inputs.add(new Input("b1_" + i, Word.SHORT, new Interval(0, 3329)));
                terms.add(new MontgomeryReductionPlanner.QuadraticProduct(n, n + 1, n + 2, n + 3));
            }
            inputs.add(new Input("zeta", Word.SHORT, new Interval(-1664, 1664)));
            for (var strategy : MontgomeryReductionPlanner.QuadraticStrategy.values()) {
                var result = MontgomeryReductionPlanner.quadraticDotProduct(MLKEM, inputs, terms,
                        inputs.size() - 1, strategy, Observation.CANONICAL_RESIDUE, LIMITS, () -> false);
                var certificate = result.certificate().orElseThrow(() -> new AssertionError(result.reason()));
                assertEquals(5 * length, certificate.sourceReductions());
                assertEquals(strategy == MontgomeryReductionPlanner.QuadraticStrategy.THREE_REDUCTIONS ? 3 : 4,
                        certificate.targetReductions());
                assertTrue(verify(certificate.request(), certificate, LIMITS, () -> false).valid());
                compareConcrete(certificate.request(), 1000, 116266 + length);
            }
        }
    }

    @Test void retainsMontgomeryScaleAndRejectsAnOrdinaryProductReplacement() {
        var inputs = inputs(1, Word.SHORT, new Interval(0, 3329), new Interval(0, 3329));
        var source = new Schedule(List.of(Step.apply(Operation.MULTIPLY, Word.INT, 0, 1),
                Step.apply(Operation.MONTGOMERY, Word.SHORT, 2)), List.of(3));
        var target = new Schedule(List.of(Step.apply(Operation.MULTIPLY, Word.INT, 0, 1)), List.of(2));
        var result = certify(new Request(MLKEM, inputs, source, target, Observation.CONGRUENCE), LIMITS, () -> false);
        assertTrue(result.certificate().isEmpty());
        assertEquals("RESIDUE_IDENTITY_MISMATCH", result.reason());
    }

    @Test void rejectsAccumulatorOverflowAndAnUnsafeNarrowing() {
        var result = MontgomeryReductionPlanner.dotProduct(MLDSA,
                inputs(7, Word.INT, new Interval(Integer.MIN_VALUE, Integer.MAX_VALUE),
                        new Interval(Integer.MIN_VALUE, Integer.MAX_VALUE)), products(7),
                Word.INT, Observation.CANONICAL_RESIDUE, LIMITS, () -> false);
        assertTrue(result.certificate().isEmpty());
        assertTrue(result.reason().contains("OVERFLOW"), result.reason());
        var program = new Schedule(List.of(Step.apply(Operation.CAST, Word.SHORT, 0)), List.of(1));
        var request = new Request(MLKEM, List.of(new Input("x", Word.INT, new Interval(0, 32768))),
                program, program, Observation.CONGRUENCE);
        assertEquals("SIGNED_OVERFLOW", certify(request, LIMITS, () -> false).reason());
    }

    @Test void doesNotAuthorizeDifferentSourcesObservationsOrInventedRanges() {
        var certificate = MontgomeryReductionPlanner.dotProduct(MLKEM,
                inputs(2, Word.SHORT, new Interval(0, 100), new Interval(0, 100)), products(2),
                Word.INT, Observation.CONGRUENCE, LIMITS, () -> false).certificate().orElseThrow();
        var request = certificate.request();
        var changed = new Request(request.parameters(), request.inputs(), request.source(), request.target(),
                Observation.CANONICAL_RESIDUE);
        assertEquals("SOURCE_MISMATCH", verify(changed, certificate, LIMITS, () -> false).reason());
        var ranges = new ArrayList<>(certificate.targetRanges());
        ranges.set(ranges.size() - 1, new Interval(0, 0));
        var forged = new Certificate(request, certificate.sourceRanges(), ranges,
                certificate.sourceReductions(), certificate.targetReductions());
        assertEquals("RANGE_EVIDENCE_MISMATCH", verify(request, forged, LIMITS, () -> false).reason());
    }

    @Test void boundsWorkAndCancellationAndRejectsInvalidParametersOrReferences() {
        var inputs = inputs(2, Word.SHORT, new Interval(0, 100), new Interval(0, 100));
        assertEquals("CANCELLED", MontgomeryReductionPlanner.dotProduct(MLKEM, inputs, products(2),
                Word.INT, Observation.CONGRUENCE, LIMITS, () -> true).reason());
        assertEquals("WORK_LIMIT", MontgomeryReductionPlanner.dotProduct(MLKEM, inputs, products(2),
                Word.INT, Observation.CONGRUENCE, new IterationLimits(1, 100, 1000), () -> false).reason());
        assertThrows(IllegalArgumentException.class, () -> new Parameters(16, 3328, 62209));
        assertThrows(IllegalArgumentException.class, () -> new Parameters(16, 3329, 62208));
        var bad = new Schedule(List.of(Step.apply(Operation.ADD, Word.INT, 0, 99)), List.of(4));
        assertEquals("INVALID_REFERENCE", certify(new Request(MLKEM, inputs, bad, bad, Observation.CONGRUENCE),
                LIMITS, () -> false).reason());
    }

    @Test void returnsTypedFailureAboveThePlannerInputLimit() {
        var range = new Interval(0, 1);
        var inputs = inputs(64, Word.SHORT, range, range);
        var dotProducts = products(1);
        var quadraticProducts = List.of(new MontgomeryReductionPlanner.QuadraticProduct(0, 1, 2, 3));
        var strategy = MontgomeryReductionPlanner.QuadraticStrategy.THREE_REDUCTIONS;
        assertTrue(MontgomeryReductionPlanner.dotProduct(MLKEM, inputs, dotProducts, Word.INT,
                Observation.CONGRUENCE, LIMITS, () -> false).certificate().isPresent());
        assertTrue(MontgomeryReductionPlanner.quadraticDotProduct(MLKEM, inputs, quadraticProducts, 4, strategy,
                Observation.CONGRUENCE, LIMITS, () -> false).certificate().isPresent());

        inputs.add(new Input("extra", Word.SHORT, range));
        var dot = MontgomeryReductionPlanner.dotProduct(MLKEM, inputs, dotProducts, Word.INT,
                Observation.CONGRUENCE, LIMITS, () -> false);
        var quadratic = MontgomeryReductionPlanner.quadraticDotProduct(MLKEM, inputs, quadraticProducts, 4, strategy,
                Observation.CONGRUENCE, LIMITS, () -> false);
        for (var result : List.of(dot, quadratic)) {
            assertEquals("INPUT_SIZE_LIMIT", result.reason());
            assertTrue(result.certificate().isEmpty());
        }
    }

    @Test void retainsCheckerWorkWhenCancelledAtTheFinalPlannerPoll() {
        var range = new Interval(0, 1);
        var inputs = inputs(1, Word.SHORT, range, range);
        var products = products(1);
        var polls = new AtomicInteger();
        var completed = MontgomeryReductionPlanner.dotProduct(MLKEM, inputs, products, Word.INT,
                Observation.CONGRUENCE, LIMITS, () -> {
                    polls.incrementAndGet();
                    return false;
                });
        assertTrue(completed.certificate().isPresent());

        int finalPoll = polls.getAndSet(0);
        var cancelled = MontgomeryReductionPlanner.dotProduct(MLKEM, inputs, products, Word.INT,
                Observation.CONGRUENCE, LIMITS, () -> polls.incrementAndGet() == finalPoll);
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.certificate().isEmpty());
        assertEquals(completed.work(), cancelled.work());
    }

    @Test void supportsOddCompositeModuliAndDoesNotRequirePrimality() {
        for (long q : List.of(9L, 15L, 21L)) {
            var radix = BigInteger.ONE.shiftLeft(16);
            var parameters = new Parameters(16, q, BigInteger.valueOf(q).modInverse(radix).longValueExact());
            var result = MontgomeryReductionPlanner.dotProduct(parameters,
                    inputs(3, Word.SHORT, new Interval(-20, 20), new Interval(-20, 20)), products(3),
                    Word.INT, Observation.CANONICAL_RESIDUE, LIMITS, () -> false);
            compareConcrete(result.certificate().orElseThrow().request(), 200, q);
        }
    }

    @Test void exhaustivelyChecksTheSignedLowWordAgainstTheCertifiedRange() {
        var program = new Schedule(List.of(Step.apply(Operation.MONTGOMERY, Word.SHORT, 0)), List.of(1));
        var request = new Request(MLKEM, List.of(new Input("x", Word.INT, new Interval(-32768, 32767))),
                program, program, Observation.CONGRUENCE);
        var proof = certify(request, LIMITS, () -> false).certificate().orElseThrow();
        var range = proof.targetRanges().getLast();
        for (int x = Short.MIN_VALUE; x <= Short.MAX_VALUE; x++) {
            short u = (short) (x * 62209);
            int reduced = (x - u * 3329) >> 16;
            assertEquals(0, Math.floorMod((long) reduced * 65536 - x, 3329));
            assertTrue(reduced >= range.minimum() && reduced <= range.maximum());
        }
    }

    @Test void boundsPolynomialExpansionAndNeverTreatsRawResiduesAsEqual() {
        var source = new Schedule(List.of(Step.apply(Operation.MULTIPLY, Word.INT, 0, 1),
                Step.apply(Operation.MONTGOMERY, Word.SHORT, 2), Step.constant(Word.INT, 3329),
                Step.apply(Operation.ADD, Word.INT, 3, 4)), List.of(5));
        var target = new Schedule(List.of(Step.apply(Operation.MULTIPLY, Word.INT, 0, 1),
                Step.apply(Operation.MONTGOMERY, Word.SHORT, 2)), List.of(3));
        var request = new Request(MLKEM, inputs(1, Word.SHORT, new Interval(0, 10), new Interval(0, 10)),
                source, target, Observation.CANONICAL_RESIDUE);
        assertTrue(certify(request, LIMITS, () -> false).certificate().isPresent());
        assertNotEquals(executeJava(MLKEM, source, List.of(1L, 2L)), executeJava(MLKEM, target, List.of(1L, 2L)));
        assertEquals("TERM_LIMIT", certify(request, new IterationLimits(10000, 2, 1000), () -> false).reason());
        var steps = new ArrayList<Step>();
        int ref = 0;
        for (int i = 0; i < 7; i++) {
            steps.add(Step.apply(Operation.MULTIPLY, Word.INT, ref, ref));
            ref++;
        }
        var growing = new Schedule(steps, List.of(ref));
        var large = new Request(MLKEM, List.of(new Input("zeroBound", Word.INT, new Interval(0, 0))),
                growing, growing, Observation.CONGRUENCE);
        assertEquals("DEGREE_LIMIT", certify(large, LIMITS, () -> false).reason());
    }

    private static List<Input> inputs(int length, Word word, Interval left, Interval right) {
        var inputs = new ArrayList<Input>();
        for (int i = 0; i < length; i++) {
            inputs.add(new Input("a" + i, word, left));
            inputs.add(new Input("b" + i, word, right));
        }
        return inputs;
    }

    private static List<MontgomeryReductionPlanner.Product> products(int length) {
        var products = new ArrayList<MontgomeryReductionPlanner.Product>();
        for (int i = 0; i < length; i++) products.add(new MontgomeryReductionPlanner.Product(2 * i, 2 * i + 1));
        return products;
    }

    private static void compareConcrete(Request request, int count, long seed) {
        var random = new Random(seed);
        for (int trial = 0; trial < count; trial++) {
            var values = new ArrayList<Long>();
            for (var input : request.inputs()) {
                var range = input.range();
                values.add(trial == 0 ? range.minimum() : trial == 1 ? range.maximum()
                        : random.nextLong(range.minimum(), range.maximum() + 1));
            }
            var source = executeJava(request.parameters(), request.source(), values);
            var target = executeJava(request.parameters(), request.target(), values);
            for (int i = 0; i < source.size(); i++) {
                assertEquals(Math.floorMod(source.get(i), request.parameters().modulus()),
                        Math.floorMod(target.get(i), request.parameters().modulus()));
            }
        }
    }

    // Independent concrete execution uses Java's actual low-word wrap and narrowing semantics.
    private static List<Long> executeJava(Parameters p, Schedule schedule, List<Long> inputs) {
        var values = new ArrayList<>(inputs);
        for (var step : schedule.steps()) {
            long a = step.operands().isEmpty() ? 0 : values.get(step.operands().getFirst());
            long b = step.operands().size() < 2 ? 0 : values.get(step.operands().get(1));
            long value = switch (step.operation()) {
                case CONSTANT -> step.literal();
                case ADD -> Math.addExact(a, b);
                case SUBTRACT -> Math.subtractExact(a, b);
                case MULTIPLY -> Math.multiplyExact(a, b);
                case CAST -> a;
                case MONTGOMERY -> {
                    if (p.radixBits() == 16) {
                        short u = (short) ((int) a * (int) p.inverse());
                        yield (short) (((int) a - u * (int) p.modulus()) >> 16);
                    }
                    int u = (int) (a * p.inverse());
                    yield (int) ((a - (long) u * p.modulus()) >>> 32);
                }
            };
            values.add(switch (step.word()) {
                case SHORT -> (long) (short) value;
                case INT -> (long) (int) value;
                case LONG -> value;
            });
            assertEquals(value, values.getLast());
        }
        return schedule.outputs().stream().map(values::get).toList();
    }
}
