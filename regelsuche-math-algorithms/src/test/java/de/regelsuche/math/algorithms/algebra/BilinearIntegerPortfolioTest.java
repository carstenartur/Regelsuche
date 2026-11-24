package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearIntegerPortfolio.Completion;
import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Profile;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Status;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BilinearIntegerPortfolioTest {
    private static final Tensor COMPLEX = new Tensor(2, 2, List.of(row(1,0,0,-1), row(0,1,1,0)));
    private static final IterationLimits LIMITS = new IterationLimits(50_000_000, 50_000, 30_000);

    @Test void exploresBeyondMinimumProductCountAndSelectsForSuppliedWidths() {
        var narrow = select(COMPLEX, 4, 5000, 1024, 8);
        var wide = select(COMPLEX, 4, 5000, 1024, 381);
        assertTrue(narrow.complete());
        assertTrue(wide.complete());
        // Three independent output/input cuts bound the first width at two, allowed ranks are 2,3,4:
        // C(16,2) + C(16,3) + C(16,4).
        assertEquals(2500, wide.examinedSubsets());
        assertTrue(wide.verifiedCandidates() > 2);
        assertSame(narrow.direct().orElseThrow(), narrow.selected());
        var direct = wide.direct().orElseThrow().schedule().cost();
        var chosen = wide.selected().schedule().cost();
        assertEquals(4, direct.variableProducts());
        assertEquals(3, chosen.variableProducts());
        assertTrue(chosen.limbWork() < direct.limbWork());
        var guarded = wide.preferredWithoutAllocationIncrease().orElseThrow().schedule().cost();
        assertTrue(guarded.allocatedObjects() <= direct.allocatedObjects());
        assertTrue(guarded.allocatedWords() <= direct.allocatedWords());
        assertTrue(guarded.compareTo(direct) <= 0);
    }

    @Test void sparseBaselineOmitsUnusedCoordinatesAndSharesLiveProducts() {
        var sparse = new Tensor(3, 2, List.of(row(0,2,0,0,0,0),row(0,-1,0,0,0,3)));
        var profile = new Profile(List.of(8,8,8), List.of(8,8));
        var selection = BilinearIntegerPortfolio.select(sparse, 2, 0, 16, profile,
            LIMITS, () -> false).value().orElseThrow();
        assertEquals(Completion.SUBSET_LIMIT, selection.completion());
        assertEquals(0, selection.examinedSubsets());
        assertEquals(1, selection.verifiedCandidates());
        assertEquals(2, selection.selected().witness().program().u().size());
        var output = BilinearIntegerSchedule.evaluate(selection.selected().schedule(), ints(2,-3,4), ints(-7,5),
            LIMITS, () -> false);
        assertEquals(ints(20,50), output.value().orElseThrow());
        assertEquals(Completion.CANDIDATE_LIMIT, select(COMPLEX, 4, 5000, 1, 9).completion());

        var tight = select(COMPLEX, 3, 5000, 256, 32);
        assertTrue(tight.direct().isEmpty());
        assertTrue(tight.preferredWithoutAllocationIncrease().isEmpty());
        assertTrue(tight.complete());
    }

    @Test void primitiveIntegerDirectionsPreserveNonunitSlopesUnderStrictProductCap() {
        var tensor = new Tensor(2, 2, List.of(row(4,6,6,9)));
        var result = select(tensor, 1, 500, 64, 5);
        assertEquals(1, result.selected().schedule().cost().variableProducts());
        assertEquals(List.of(row(2,3)), result.selected().witness().program().u());
        assertEquals(List.of(row(2,3)), result.selected().witness().program().v());
        assertEquals(ints(-28), BilinearIntegerSchedule.evaluate(result.selected().schedule(),
            ints(1,4), ints(-4,2), LIMITS, () -> false).value().orElseThrow());
    }

    @Test void rejectsFractionalSourcesAndMismatchedWidthProfiles() {
        var tensor = new Tensor(1, 1, List.of(List.of(ExactRational.parse("1/2"))));
        var profile = new Profile(List.of(10), List.of(10));
        var rejected = BilinearIntegerPortfolio.select(tensor, 1, 20, 10, profile, LIMITS, () -> false);
        assertEquals(Status.REJECTED, rejected.status());
        assertEquals("NON_INTEGRAL_TENSOR", rejected.reason());
        assertEquals("PROFILE_SHAPE", BilinearIntegerPortfolio.select(COMPLEX, 4, 5000, 10, profile,
            LIMITS, () -> false).reason());
    }

    @Test void aRuntimeBoundaryAfterSeedingDiscardsTheWholePortfolio() {
        var p = profile(17);
        var base = BilinearIntegerPortfolio.select(COMPLEX, 4, 0, 3, p, LIMITS, () -> false);
        var insufficient = BilinearIntegerPortfolio.select(COMPLEX, 4, 5000, 3, p,
            LIMITS.withMaximumWork(base.work()), () -> false);
        assertEquals("WORK_LIMIT", insufficient.reason());
        assertTrue(insufficient.value().isEmpty());
        assertEquals("CANCELLED", BilinearIntegerPortfolio.select(COMPLEX, 4, 5000, 3,
            p, LIMITS, () -> true).reason());
        assertEquals("TERM_LIMIT", BilinearIntegerPortfolio.select(COMPLEX, 4, 5000, 3,
            p, new IterationLimits(1000, 1, 1000), () -> false).reason());
        assertEquals("SUBSET_LIMIT", BilinearIntegerPortfolio.select(COMPLEX, 3, 0, 3,
            p, LIMITS, () -> false).reason());
    }

    private static BilinearIntegerPortfolio.Selection select(Tensor tensor, int products, int subsets,
            int candidates, int bits) {
        var result = BilinearIntegerPortfolio.select(tensor, products, subsets, candidates, profile(bits),
            LIMITS, () -> false);
        assertEquals(Status.VERIFIED, result.status(), result.reason());
        return result.value().orElseThrow();
    }

    private static Profile profile(int bits) { return new Profile(List.of(bits,bits), List.of(bits,bits)); }

    private static List<ExactRational> row(long... v) { return Arrays.stream(v).mapToObj(ExactRational::integer).toList(); }
    private static List<BigInteger> ints(long... v) { return Arrays.stream(v).mapToObj(BigInteger::valueOf).toList(); }
}
