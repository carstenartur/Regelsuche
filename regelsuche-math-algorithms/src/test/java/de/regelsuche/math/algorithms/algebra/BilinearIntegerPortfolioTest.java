package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearIntegerPortfolio.Completion;
import de.regelsuche.math.algorithms.algebra.BilinearIntegerPortfolio.Objective;
import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Profile;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Status;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BilinearIntegerPortfolioTest {
    private static final Tensor COMPLEX = new Tensor(2, 2, List.of(row(1,0,0,-1), row(0,1,1,0)));
    private static final IterationLimits LIMITS = new IterationLimits(50_000_000, 50_000, 30_000);

    @Test void replayAndFixedFallbackShareWorkAndTheNewTensorIsChecked() {
        var p = profile(2048);
        var hint = Optional.of(acquire(COMPLEX, p));
        var replay = BilinearIntegerPortfolio.selectWithReuse(COMPLEX, p, Objective.LIMB_WORK, hint, 100_000, LIMITS, () -> false);
        assertEquals("REUSED_PROGRAM", replay.selectedProvider().orElseThrow());
        assertEquals(3, replay.value().orElseThrow().schedule().cost().variableProducts());

        var changed = new Tensor(2, 2, List.of(row(1,0,0,-1), row(0,1,-1,0)));
        var fallback = BilinearIntegerPortfolio.selectWithReuse(changed, p, Objective.LIMB_WORK, hint, 100_000, LIMITS, () -> false);
        assertEquals("DIRECT_COORDINATES", fallback.selectedProvider().orElseThrow());
        assertEquals("COEFFICIENT_MISMATCH", fallback.attempts().getFirst().reason());
        assertTrue(fallback.attempts().stream().allMatch(a -> a.work() > 0));
        assertEquals(fallback.work(), fallback.preparationWork() + fallback.attempts().stream().mapToLong(a -> a.work()).sum());
        assertEquals(ints(-7,-2), BilinearIntegerSchedule.evaluate(fallback.value().orElseThrow().schedule(),
            ints(2,3), ints(4,5), LIMITS, () -> false).value().orElseThrow());
        var shorter = BilinearIntegerPortfolio.selectWithReuse(changed, p, Objective.LIMB_WORK, hint, 100_000,
            LIMITS.withMaximumWork(fallback.work() - 1), () -> false);
        assertTrue(shorter.value().isEmpty());
        assertTrue(shorter.work() < fallback.work());
    }

    @Test void allocationConstrainedObjectiveKeepsTheSizedFixedMethodForTheFieldTensor() {
        var p = profile(381);
        var acquired = BilinearIntegerPortfolio.select(COMPLEX, 4, 5000, 1024, p, LIMITS, () -> false);
        var hint = Optional.of(BilinearIntegerPortfolio.Reuse.from(acquired));
        var decision = BilinearIntegerPortfolio.selectWithReuse(COMPLEX, p,
            BilinearIntegerPortfolio.Objective.NO_ALLOCATION_INCREASE, hint, 200_000, LIMITS, () -> false);
        assertEquals("DIRECT_COORDINATES", decision.selectedProvider().orElseThrow());
        assertEquals(acquired.work(), decision.priorWork());
        var skipped = decision.attempts().getFirst();
        assertEquals("ALLOCATION_INCREASE", skipped.reason());
        assertEquals(hint.orElseThrow().candidate().schedule().cost().limbWork(), skipped.estimate().cost().orElseThrow());
        assertEquals(0, decision.attempts().getFirst().work());
        assertEquals(4, decision.value().orElseThrow().schedule().cost().variableProducts());
    }

    @Test void disabledReuseRequiresOnlyTheFixedWorkEvenWithSuppliedAcquisition() {
        var profile = profile(381);
        var hint = acquire(COMPLEX, profile);
        var cold = BilinearIntegerPortfolio.selectWithReuse(COMPLEX, profile, Objective.LIMB_WORK, Optional.empty(),
            0, LIMITS, () -> false);
        var exact = BilinearIntegerPortfolio.selectWithReuse(COMPLEX, profile, Objective.LIMB_WORK, Optional.of(hint),
            0, LIMITS.withMaximumWork(cold.work()), () -> false);
        assertEquals("DIRECT_COORDINATES", exact.selectedProvider().orElse("unavailable"));
        assertEquals(hint.acquisitionWork(), exact.priorWork());
        assertEquals(cold.work(), exact.work());
    }

    @Test void matchingWidthEvidenceIsRequiredAndTiesKeepTheFixedPlan() {
        var tensor = new Tensor(2, 2, List.of(row(1,0,0,0), row(0,1,1,0), row(0,0,0,1)));
        for (int width : List.of(256, 381, 2048, 8192)) {
            var hint = acquire(tensor, profile(width));
            var pick = BilinearIntegerPortfolio.selectWithReuse(tensor, profile(width), Objective.LIMB_WORK,
                Optional.of(hint), 200_000, LIMITS, () -> false);
            assertEquals(hint.candidate().schedule().cost(), pick.value().orElseThrow().schedule().cost());
            assertEquals(width == 256 ? "DIRECT_COORDINATES" : "REUSED_PROGRAM", pick.selectedProvider().orElseThrow());
        }
        var wide = acquire(tensor, profile(8192));
        var cold = BilinearIntegerPortfolio.selectWithReuse(tensor, profile(256), Objective.LIMB_WORK,
            Optional.of(wide), 100_000, LIMITS, () -> false);
        assertEquals("DIRECT_COORDINATES", cold.selectedProvider().orElseThrow());
        assertEquals(0, cold.attempts().getFirst().work());
    }

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

    private static BilinearIntegerPortfolio.Reuse acquire(Tensor tensor, Profile profile) {
        return BilinearIntegerPortfolio.Reuse.from(BilinearIntegerPortfolio.select(tensor, 4, 5000, 1024, profile,
            LIMITS, () -> false));
    }

    private static Profile profile(int bits) { return new Profile(List.of(bits,bits), List.of(bits,bits)); }

    private static List<ExactRational> row(long... v) { return Arrays.stream(v).mapToObj(ExactRational::integer).toList(); }
    private static List<BigInteger> ints(long... v) { return Arrays.stream(v).mapToObj(BigInteger::valueOf).toList(); }
}
