package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram.Status;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BilinearFormDiscoveryTest {
    private static final IterationLimits LIMITS = new IterationLimits(3_000_000, 2_000, 15_000);

    @Test void findsSharedProductsFromAnonymousTensorCoefficients() {
        var convolution = new Tensor(2, 2, List.of(row(1,0,0,0), row(0,1,1,0), row(0,0,0,1)));
        var found = discover(convolution, 3);
        assertEquals(3, found.witness().cost().variableMultiplications());
        assertEquals(3, found.lowerBound().lowerBound());
        assertEquals(row(8,22,15), evaluate(found, row(2,3), row(4,5)));

        var rotation = new Tensor(2, 2, List.of(row(1,0,0,-1), row(0,1,1,0)));
        var rotated = discover(rotation, 3);
        assertEquals(2, rotated.lowerBound().lowerBound());
        assertEquals(3, rotated.witness().cost().variableMultiplications());
        assertEquals(row(-7,22), evaluate(rotated, row(2,3), row(4,5)));
    }

    @Test void derivesNonCoordinateFormsFromAsymmetricRationalOutputs() {
        // Two unrelated rank-one coefficient slices, with non-unit slopes on both sides.
        var first = List.of(q("1/2"), q("2/3"), q("-1/6"), q("3/4"), q("1"), q("-1/4"));
        var second = row(2,-6,4,-5,15,-10);
        var tensor = new Tensor(2, 3, List.of(first, second));
        var found = discover(tensor, 2);
        assertEquals(2, found.lowerBound().lowerBound());
        assertEquals(2, found.witness().cost().variableMultiplications());
        assertTrue(found.witness().program().u().stream().flatMap(List::stream)
            .anyMatch(value -> value.denominator().intValue() != 1));
        checkAgainstSource(tensor, found);
    }

    @Test void rankFactorizationReachesExactBoundForRectangularMatricesWithMissingInitialPivots() {
        var tensor = new Tensor(3, 4, List.of(row(0,0,0,0, 0,2,5,-3, 3,-1,4,7)));
        var found = discover(tensor, 2);
        assertEquals(2, found.lowerBound().lowerBound());
        assertEquals(2, found.witness().program().u().size());
        checkAgainstSource(tensor, found);
    }

    @Test void classifiesZeroAndKeepsLimitsDistinctFromProofs() {
        var zero = new Tensor(1, 2, List.of(row(0,0), row(0,0)));
        var found = discover(zero, 0);
        assertEquals(0, found.witness().cost().variableMultiplications());
        assertEquals(row(0,0), evaluate(found, row(1), row(3,2)));

        var tensor = new Tensor(2, 2, List.of(row(0,1,1,0)));
        var tooNarrow = BilinearFormDiscovery.discover(tensor, 1, LIMITS, () -> false);
        assertEquals(Status.INCONCLUSIVE, tooNarrow.status());
        assertEquals("PRODUCT_LIMIT_BELOW_BOUND", tooNarrow.reason());
        var complete = BilinearFormDiscovery.discover(tensor, 2, LIMITS, () -> false);
        var shortBudget = BilinearFormDiscovery.discover(tensor, 2, LIMITS.withMaximumWork(complete.work() - 1), () -> false);
        assertEquals("WORK_LIMIT", shortBudget.reason());
        assertTrue(shortBudget.value().isEmpty());
        assertEquals("TERM_LIMIT", BilinearFormDiscovery.discover(tensor, 2,
            new IterationLimits(10000, 2, 1000), () -> false).reason());
        var poll = new AtomicInteger();
        assertEquals("CANCELLED", BilinearFormDiscovery.discover(tensor, 2, LIMITS,
            () -> poll.incrementAndGet() > 30).reason());
    }

    private static BilinearFormDiscovery.Discovery discover(Tensor tensor, int maximumProducts) {
        var result = BilinearFormDiscovery.discover(tensor, maximumProducts, LIMITS, () -> false);
        assertEquals(Status.VERIFIED, result.status(), result.reason());
        return result.value().orElseThrow();
    }

    private static List<ExactRational> evaluate(BilinearFormDiscovery.Discovery found,
            List<ExactRational> x, List<ExactRational> y) {
        return BilinearProgramVerifier.evaluate(found.request(), found.witness().program(), x, y,
            LIMITS, () -> false).value().orElseThrow();
    }

    private static void checkAgainstSource(Tensor tensor, BilinearFormDiscovery.Discovery found) {
        var samples = new Random(0x65a710L);
        for (int sample = 0; sample < 32; sample++) {
            var x = samples.ints(tensor.leftSize(), -7, 8).mapToObj(ExactRational::integer).toList();
            var y = samples.ints(tensor.rightSize(), -7, 8).mapToObj(ExactRational::integer).toList();
            var expected = new ArrayList<ExactRational>();
            for (var output : tensor.outputs()) {
                var sum = ExactRational.ZERO;
                for (int i = 0; i < x.size(); i++) {
                    for (int j = 0; j < y.size(); j++) {
                        sum = sum.add(x.get(i).multiply(y.get(j)).multiply(output.get(i * y.size() + j)));
                    }
                }
                expected.add(sum);
            }
            assertEquals(expected, evaluate(found, x, y));
        }
    }

    private static ExactRational q(String value) { return ExactRational.parse(value); }

    private static List<ExactRational> row(long... entries) {
        return Arrays.stream(entries).mapToObj(ExactRational::integer).toList();
    }
}
