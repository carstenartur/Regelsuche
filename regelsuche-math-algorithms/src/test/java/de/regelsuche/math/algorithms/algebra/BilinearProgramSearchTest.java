package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram.Cost;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Request;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Status;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BilinearProgramSearchTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 1000, 10_000);
    private static final List<List<ExactRational>> FORMS = List.of(row(1,0), row(0,1), row(1,1));
    private static final Tensor CONVOLUTION = new Tensor(2, 2,
        List.of(row(1,0,0,0), row(0,1,1,0), row(0,0,0,1)));

    @Test void discoversConvolutionAndMatchesDirectEvaluationOnAll81Inputs() {
        var request = convolution(3);
        var witness = BilinearProgramSearch.search(request, LIMITS, () -> false).value().orElseThrow();
        var cost = witness.cost();
        assertEquals(new Cost(3, 4, 2, 5), cost);
        assertEquals(cost, verify(request, witness.program()).value().orElseThrow());
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
            for (int c = -1; c <= 1; c++) for (int d = -1; d <= 1; d++) {
                var got = BilinearProgramVerifier.evaluate(request, witness.program(),
                    row(a,b), row(c,d), LIMITS, () -> false);
                assertEquals(row(a*c, a*d+b*c, b*d), got.value().orElseThrow());
            }
        }
    }

    @Test void findsJointRationalOutputsOnUnequalInputDimensions() {
        // Common product (x/2 - y)(b + 2c); second output also uses xa.
        var half = ExactRational.parse("1/2");
        var quarter = ExactRational.parse("1/4");
        var forms = List.of(row(1,0), List.of(half, ExactRational.NEGATIVE_ONE));
        var first = List.of(ExactRational.ZERO, quarter, half, ExactRational.ZERO,
            half.negate(), ExactRational.NEGATIVE_ONE);
        var tensor = new Tensor(2, 3, List.of(first, row(1,1,2,0,-2,-4)));
        var request = new Request(tensor, forms, List.of(row(1,0,0), row(0,1,2)), 2);
        var witness = BilinearProgramSearch.search(request, LIMITS, () -> false).value().orElseThrow();
        assertEquals(2, witness.cost().variableMultiplications());
        var evaluated = BilinearProgramVerifier.evaluate(request, witness.program(),
            row(4,-1), row(5,3,-2), LIMITS, () -> false);
        assertEquals(List.of(ExactRational.parse("-3/2"), ExactRational.integer(14)),
            evaluated.value().orElseThrow());
    }

    @Test void denseTensorReferenceAgreesOn64IndependentRationalAssignments() {
        var tensor = new Tensor(2, 3, List.of(row(1,3,-1,2,6,-2), row(-1,-3,1,1,3,-1)));
        var leftForms = List.of(row(0,1), row(1,2), row(-1,1));
        var rightForms = List.of(row(1,0,0), row(1,3,-1));
        var request = new Request(tensor, leftForms, rightForms, 2);
        var found = BilinearProgramSearch.search(request, LIMITS, () -> false).value().orElseThrow();
        var random = new Random(0xB11E2026L);
        for (int sample = 0; sample < 64; sample++) {
            var x = randomVector(random, 2);
            var y = randomVector(random, 3);
            var expected = new ArrayList<ExactRational>();
            for (var output : tensor.outputs()) {
                var sum = ExactRational.ZERO;
                for (int i = 0; i < x.size(); i++) {
                    for (int j = 0; j < y.size(); j++) {
                        var term = x.get(i).multiply(y.get(j)).multiply(output.get(i * y.size() + j));
                        sum = sum.add(term);
                    }
                }
                expected.add(sum);
            }
            assertEquals(expected, BilinearProgramVerifier.evaluate(request, found.program(), x, y,
                LIMITS, () -> false).value().orElseThrow());
        }
    }

    private static List<ExactRational> randomVector(Random random, int size) {
        var values = new ArrayList<ExactRational>();
        for (int i = 0; i < size; i++) {
            values.add(new ExactRational(BigInteger.valueOf(random.nextInt(25) - 12),
                BigInteger.valueOf(1 + random.nextInt(9))));
        }
        return values;
    }

    @Test void boundedMissIsInconclusiveAndZeroTensorUsesEmptyProductSet() {
        var miss = BilinearProgramSearch.search(convolution(2), LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, miss.status());
        assertEquals("DICTIONARY_EXHAUSTED", miss.reason());
        assertTrue(miss.value().isEmpty());
        var zero = new Request(new Tensor(1, 2, List.of(row(0,0))), List.of(), List.of(), 0);
        var found = BilinearProgramSearch.search(zero, LIMITS, () -> false).value().orElseThrow();
        assertEquals(new Cost(0,0,0,0), found.cost());
        assertEquals(row(0), BilinearProgramVerifier.evaluate(zero, found.program(), row(2),
            row(1,3), LIMITS, () -> false).value().orElseThrow());
    }

    @Test void checkerBindsSourceCoefficientsDictionaryRankAndEveryMatrix() {
        var p = new BilinearProgram(List.of(row(1,0), row(0,1), row(1,1)), FORMS,
            List.of(row(1,0,0), row(-1,-1,1), row(0,1,0)));
        var request = convolution(3);
        assertEquals(Status.VERIFIED, verify(request, p).status());
        var changedSource = new Request(new Tensor(2,2,
            List.of(row(1,0,0,0), row(0,1,2,0), row(0,0,0,1))), FORMS, FORMS, 3);
        assertEquals(Status.REJECTED, verify(changedSource, p).status());
        var narrow = new Request(CONVOLUTION, FORMS.subList(0, 2), FORMS, 3);
        assertEquals(Status.REJECTED, verify(narrow, p).status());
        assertEquals(Status.REJECTED, verify(convolution(2), p).status());
        var wrong = List.of(row(1,0), row(1,0), row(1,1));
        assertEquals(Status.REJECTED, verify(request, new BilinearProgram(wrong, FORMS, p.w())).status());
        assertEquals(Status.REJECTED, verify(request, new BilinearProgram(FORMS, wrong, p.w())).status());
        var altered = new BilinearProgram(FORMS, FORMS, List.of(row(1,0,0), row(-1,-1,1), row(0,1,1)));
        assertEquals(Status.REJECTED, verify(request, altered).status());
        assertTrue(BilinearProgramVerifier.evaluate(request, altered, row(0,0), row(0,0),
            LIMITS, () -> false).value().isEmpty());
    }

    @Test void shapeChecksAndDefensiveCopiesProtectSourceAndCertificates() {
        assertThrows(IllegalArgumentException.class, () -> new Tensor(2,2, List.of(row(0,0,0))));
        assertThrows(IllegalArgumentException.class, () -> new Request(CONVOLUTION, List.of(row(1)), FORMS, 3));
        var r = new ArrayList<>(row(1));
        var program = new BilinearProgram(List.of(r), List.of(r), List.of(r));
        var req = new Request(new Tensor(1,1,List.of(r)), List.of(r), List.of(r), 1);
        r.clear();
        assertEquals(Status.VERIFIED, verify(req, program).status());
        assertEquals(Status.REJECTED, verify(req,
            new BilinearProgram(program.u(), List.of(), program.w())).status());
        assertEquals(Status.REJECTED, verify(req,
            new BilinearProgram(program.u(), program.v(), List.of(row(1,1)))).status());
        assertThrows(IllegalArgumentException.class, () -> BilinearProgramVerifier.evaluate(req,
            program, row(1,2), row(3), LIMITS, () -> false));
    }

    @Test void limitsNeverYieldAProvedResultOrPartialValue() {
        var request = convolution(3);
        var p = BilinearProgramSearch.search(request, LIMITS, () -> false).value().orElseThrow().program();
        var tiny = new IterationLimits(100_000, 1, 1000);
        assertEquals("TERM_LIMIT", BilinearProgramSearch.search(request, tiny, () -> false).reason());
        assertEquals("WORK_LIMIT", BilinearProgramVerifier.verify(request, p,
            LIMITS.withMaximumWork(2), () -> false).reason());
        assertEquals("CANCELLED", BilinearProgramSearch.search(request, LIMITS, () -> true).reason());
        assertEquals(Status.INCONCLUSIVE, BilinearProgramVerifier.verify(request, p, LIMITS, () -> true).status());
        var huge = ExactRational.integer(BigInteger.ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS));
        var bad = new Request(CONVOLUTION, FORMS, List.of(List.of(huge, huge)), 3);
        assertEquals("RUNTIME_BIT_LIMIT", BilinearProgramSearch.search(bad, LIMITS, () -> false).reason());
        var stopped = BilinearProgramVerifier.evaluate(request, p, List.of(huge, huge), row(1,1), LIMITS, () -> false);
        assertEquals("RUNTIME_BIT_LIMIT", stopped.reason());
        assertTrue(stopped.value().isEmpty());
    }

    @Test void finiteSearchRecoversDistinctJointOutputsWithSignedRationalWeights() {
        var forms = List.of(row(0,0), row(1,0), row(1,0), row(0,1), row(1,-1));
        for (int t = -2; t <= 2; t++) {
            // p = x1*y0, q = (x0-x1)*(y0-y1); outputs p+t*q and q-p/2.
            var minusHalf = ExactRational.parse("-1/2");
            var tensor = new Tensor(2,2, List.of(row(t,-t,1-t,t),
                List.of(ExactRational.ONE, ExactRational.NEGATIVE_ONE,
                    ExactRational.NEGATIVE_ONE.add(minusHalf), ExactRational.ONE)));
            var request = new Request(tensor, forms, forms, 2);
            var witness = BilinearProgramSearch.search(request, LIMITS, () -> false).value().orElseThrow();
            assertEquals(2, witness.cost().variableMultiplications());
            // x=(4,-3), y=(3,5): p=-9, q=-14.
            assertEquals(List.of(ExactRational.integer(-9L-14L*t), ExactRational.parse("-19/2")),
                BilinearProgramVerifier.evaluate(request, witness.program(), row(4,-3), row(3,5),
                    LIMITS, () -> false).value().orElseThrow());
        }
    }

    @Test void midSearchTerminationAndSharedEvaluationBudgetDiscardPartialResults() {
        var request = convolution(3);
        var polls = new AtomicInteger();
        var cancelled = BilinearProgramSearch.search(request, LIMITS, () -> polls.incrementAndGet() > 200);
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.work() > 0);
        assertTrue(cancelled.value().isEmpty());
        var p = BilinearProgramSearch.search(request, LIMITS, () -> false).value().orElseThrow().program();
        long checkingWork = verify(request, p).work();
        var stopped = BilinearProgramVerifier.evaluate(request, p, row(1,0), row(1,0),
            LIMITS.withMaximumWork(checkingWork), () -> false);
        assertEquals("WORK_LIMIT", stopped.reason());
        assertTrue(stopped.value().isEmpty());
        // Input and dictionary need 33 cells. The first augmented matrix already needs 16 more.
        var termStop = BilinearProgramSearch.search(request, new IterationLimits(100_000, 40, 1000),
            () -> false);
        assertEquals("TERM_LIMIT", termStop.reason());
    }

    @Test void enforcesTimeoutAndBoundsIntermediateMultiplicationBeforeReturningValues() {
        var one = List.of(row(1));
        var request = new Request(new Tensor(1,1,one), one, one, 1);
        var p = new BilinearProgram(one, one, one);
        var timeout = BilinearProgramVerifier.verify(request, p, new IterationLimits(1_000_000,100,1), () -> {
            // Spend the test deadline deterministically in the cancellation callback.
            long start = System.nanoTime();
            while (System.nanoTime() - start < 2_000_000) Thread.onSpinWait();
            return false;
        });
        assertEquals("TIME_LIMIT", timeout.reason());
        var factor = List.of(ExactRational.integer(BigInteger.ONE.shiftLeft(33_000)));
        assertEquals("RUNTIME_BIT_LIMIT", BilinearProgramVerifier.evaluate(request, p, factor, factor,
            LIMITS, () -> false).reason());
    }

    @Test void evaluatesOneValidatedInputSnapshotEvenWhenCallerMutatesDuringVerification() {
        var one = List.of(row(1));
        var request = new Request(new Tensor(1, 1, one), one, one, 1);
        var program = new BilinearProgram(one, one, one);
        for (boolean grow : new boolean[] {false, true}) {
            var input = new java.util.ArrayList<>(row(2));
            var polls = new AtomicInteger();
            var result = BilinearProgramVerifier.evaluate(request, program, input, row(3), LIMITS, () -> {
                if (polls.incrementAndGet() == 10) {
                    if (grow) input.add(ExactRational.integer(100)); else input.clear();
                }
                return false;
            });
            assertEquals(row(6), result.value().orElseThrow());
        }
        var input = new java.util.ArrayList<>(row(2));
        var polls = new AtomicInteger();
        var error = assertThrows(IllegalArgumentException.class,
            () -> BilinearProgramVerifier.evaluate(request, program, input, row(3), LIMITS, () -> {
                if (polls.incrementAndGet() == 2) input.clear();
                return false;
            }));
        assertEquals("INPUT_VECTOR_SHAPE", error.getMessage());
    }

    private static Request convolution(int rank) {
        return new Request(CONVOLUTION, FORMS, FORMS, rank);
    }

    private static BilinearProgram.Result<Cost> verify(Request request, BilinearProgram program) {
        return BilinearProgramVerifier.verify(request, program, LIMITS, () -> false);
    }

    private static List<ExactRational> row(long... entries) {
        return Arrays.stream(entries).mapToObj(ExactRational::integer).toList();
    }
}
