package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.Status.*;
import static de.regelsuche.scalar.ExactRational.*;
import static org.junit.jupiter.api.Assertions.*;

class ComposedBilinearSearchTest {
    private static final IterationLimits LIMITS = new IterationLimits(20_000_000, 500_000, 20_000);
    private static final ComposedBilinearSearch.Policy RESIDUAL = ComposedBilinearSearch.Policy.residualOnly();

    @Test void centeredProductSearchReducesActualStageCostsAndExecutesHeldOutRationalBatches() {
        var fixture = centered(8);
        var found = ComposedBilinearSearch.search(fixture.request(), fixture.program(), 3, RESIDUAL, 12,
            LIMITS, () -> false);
        assertEquals(VERIFIED, found.status(), found.detail());
        var discovery = found.requireValue();
        var prepared = discovery.prepared();
        assertEquals(3 * 248, prepared.directCost().total());
        assertEquals(3 * 54, prepared.cost().total());
        assertEquals(24, prepared.cost().variableMultiplications());
        assertEquals(12, discovery.accounting().attemptedCandidates());
        assertTrue(discovery.accounting().suppliedSeed());
        assertTrue(discovery.accounting().acquisitionWork() > 0);
        assertEquals(found.work(), discovery.accounting().acquisitionWork()
            + discovery.accounting().compositionWork());
        assertEquals(VERIFIED, ComposedBilinearVerifier.verify(fixture.request(), 3, prepared.plan(),
            LIMITS, () -> false).status());
        var random = new Random(730192);
        for (int trial = 0; trial < 4; trial++) {
            var left = randomBatch(random, 3, 8);
            var right = randomBatch(random, 3, 8);
            assertEquals(tensorBatch(fixture.request().tensor(), left, right),
                prepared.apply(left, right, LIMITS, () -> false).requireValue());
        }
    }

    @Test void denseNoBenefitKeepsSparseDirectStages() {
        var f = rows(new long[]{2, 3}, new long[]{5, 7});
        var fixture = fixture(f, f, rows(new long[]{1, 0}, new long[]{0, 1}));
        var found = ComposedBilinearSearch.search(fixture.request(), fixture.program(), 1, RESIDUAL, 12,
            LIMITS, () -> false);
        assertEquals(VERIFIED, found.status(), found.detail());
        var prepared = found.requireValue().prepared();
        assertEquals(14, prepared.directCost().total());
        assertEquals(prepared.directCost(), prepared.cost());
        var left = List.of(List.of(ONE, integer(2)));
        var right = List.of(List.of(integer(-3), integer(4)));
        assertEquals(List.of(List.of(integer(48), integer(247))),
            prepared.apply(left, right, LIMITS, () -> false).requireValue());
    }

    @Test void rectangularStagesAreComposedWithoutAssumingSquareMatrices() {
        var fixture = fixture(rows(new long[]{2, 4, 6}, new long[]{3, 6, 9}),
            rows(new long[]{1, 0}, new long[]{0, 1}),
            rows(new long[]{1, 0}, new long[]{0, 1}, new long[]{1, -1}));
        var discovery = ComposedBilinearSearch.search(fixture.request(), fixture.program(), 2,
            ComposedBilinearSearch.Policy.defaults(), 17, LIMITS, () -> false).requireValue();
        var prepared = discovery.prepared();
        assertEquals(2, discovery.attempts().stream().filter(a -> a.detail().equals("RECTANGULAR_STAGE")).count());
        var left = List.of(List.of(ONE, integer(2), integer(3)), List.of(integer(-1), ZERO, ONE));
        var right = List.of(List.of(integer(2), integer(-1)), List.of(integer(3), integer(4)));
        assertEquals(List.of(List.of(integer(56), integer(-42), integer(98)),
            List.of(integer(12), integer(24), integer(-12))),
            prepared.apply(left, right, LIMITS, () -> false).requireValue());
        assertTrue(prepared.cost().total() < prepared.directCost().total());
    }

    @Test void zeroRankHasAnExecutableZeroBranchWithoutEmptyOperatorMatrices() {
        var tensor = new BilinearProgram.Tensor(2, 3,
            List.of(List.of(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO), List.of(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO)));
        var found = ComposedBilinearSearch.discover(tensor, 0, 2, RESIDUAL, 1, LIMITS, () -> false);
        assertEquals(VERIFIED, found.status(), found.detail());
        var prepared = found.requireValue().prepared();
        assertTrue(prepared.plan().stages().isEmpty());
        assertEquals(0, prepared.cost().total());
        assertEquals(1, found.requireValue().accounting().attemptedCandidates());
        assertEquals(List.of(List.of(ZERO, ZERO), List.of(ZERO, ZERO)), prepared.apply(
            List.of(List.of(integer(7), integer(-2)), List.of(ONE, ONE)),
            List.of(List.of(ONE, integer(2), integer(3)), List.of(integer(-1), ZERO, ONE)),
            LIMITS, () -> false).requireValue());
        var nonzero = new BilinearProgram.Tensor(2, 3,
            List.of(List.of(ONE, ZERO, ZERO, ZERO, ZERO, ZERO), List.of(ZERO, ZERO, ZERO, ZERO, ZERO, ZERO)));
        var wrong = new BilinearProgram.Request(nonzero, List.of(), List.of(), 0);
        assertEquals(INVALID, ComposedBilinearSearch.search(wrong, prepared.plan().program(), 2,
            RESIDUAL, 0, LIMITS, () -> false).status());
    }

    @Test void acquisitionDiscoversAnExactSeedAndPaysItsWorkInsideTheSameCall() {
        var tensor = new BilinearProgram.Tensor(2, 2, List.of(List.of(ONE, integer(2), integer(3), integer(6))));
        var found = ComposedBilinearSearch.discover(tensor, 1, 1, RESIDUAL, 13, LIMITS, () -> false);
        assertEquals(VERIFIED, found.status(), found.detail());
        var discovery = found.requireValue();
        assertFalse(discovery.accounting().suppliedSeed());
        assertEquals(1, discovery.prepared().plan().program().u().size());
        assertEquals(13, discovery.accounting().attemptedCandidates());
        assertTrue(discovery.accounting().acquisitionWork() > 0);
        assertEquals(found.work(), discovery.accounting().acquisitionWork() + discovery.accounting().compositionWork());
        assertEquals(List.of(List.of(integer(98))), discovery.prepared().apply(
            List.of(List.of(ONE, integer(2))), List.of(List.of(integer(4), integer(5))), LIMITS, () -> false)
            .requireValue());
        assertEquals("CANDIDATE_LIMIT", ComposedBilinearSearch.discover(tensor, 1, 1, RESIDUAL, 12,
            LIMITS, () -> false).detail());
    }

    @Test void independentVerifierRejectsAChangedSourceAndAWrongLinearStage() {
        var fixture = centered(3);
        var prepared = ComposedBilinearSearch.search(fixture.request(), fixture.program(), 1, RESIDUAL, 12,
            LIMITS, () -> false).requireValue().prepared();
        var plan = prepared.plan();
        var stages = new ArrayList<>(plan.stages());
        var original = ((ComposedBilinearPlan.Linear) stages.getFirst()).plan();
        stages.set(0, new ComposedBilinearPlan.Linear(new OperatorResidualPlans.Plan(original.source(),
            new ExactMatrixExpression.Identity(3), Optional.empty())));
        var tampered = new ComposedBilinearPlan(plan.program(), 1, stages);
        assertEquals(INVALID, ComposedBilinearVerifier.prepare(fixture.request(), 1, tampered, LIMITS,
            () -> false).status());
        var coefficients = new ArrayList<>(fixture.request().tensor().outputs());
        var first = new ArrayList<>(coefficients.getFirst());
        first.set(0, first.getFirst().add(ONE));
        coefficients.set(0, first);
        var changed = new BilinearProgram.Request(new BilinearProgram.Tensor(3, 3, coefficients),
            fixture.program().u(), fixture.program().v(), 3);
        assertEquals(INVALID, ComposedBilinearVerifier.verify(changed, 1, plan, LIMITS, () -> false).status());
        assertEquals(INVALID, ComposedBilinearVerifier.verify(fixture.request(), 2, plan, LIMITS,
            () -> false).status());
    }

    @Test void workCandidatesCellsAndCancellationAreGlobalAndNeverReturnAnUncheckedWinner() {
        var fixture = centered(3);
        var found = ComposedBilinearSearch.search(fixture.request(), fixture.program(), 1, RESIDUAL, 12,
            LIMITS, () -> false);
        assertEquals(VERIFIED, found.status(), found.detail());
        var tightWork = ComposedBilinearSearch.search(fixture.request(), fixture.program(), 1, RESIDUAL, 12,
            LIMITS.withMaximumWork(found.work() - 1), () -> false);
        assertEquals(STOPPED, tightWork.status());
        assertEquals("WORK_LIMIT", tightWork.detail());
        assertTrue(tightWork.work() > found.requireValue().accounting().acquisitionWork());
        var candidates = ComposedBilinearSearch.search(fixture.request(), fixture.program(), 1, RESIDUAL, 4,
            LIMITS, () -> false);
        assertEquals(STOPPED, candidates.status());
        assertEquals("CANDIDATE_LIMIT", candidates.detail());
        assertTrue(candidates.work() > found.requireValue().accounting().acquisitionWork());
        assertEquals("CANDIDATE_LIMIT", ComposedBilinearSearch.search(fixture.request(), fixture.program(),
            1, RESIDUAL, 11, LIMITS, () -> false).detail());
        assertEquals(STOPPED, ComposedBilinearSearch.search(fixture.request(), fixture.program(), 1, RESIDUAL,
            12, new IterationLimits(1_000_000, 10, 20_000), () -> false).status());
        assertEquals("CANCELLED", ComposedBilinearSearch.search(fixture.request(), fixture.program(), 1,
            RESIDUAL, 12, LIMITS, () -> true).detail());
        var polls = new AtomicInteger();
        ComposedBilinearSearch.search(fixture.request(), fixture.program(), 1, RESIDUAL, 12, LIMITS,
            () -> { polls.incrementAndGet(); return false; });
        int finalPoll = polls.get();
        polls.set(0);
        var cancelled = ComposedBilinearSearch.search(fixture.request(), fixture.program(), 1, RESIDUAL, 12,
            LIMITS, () -> polls.incrementAndGet() >= finalPoll);
        assertEquals("CANCELLED", cancelled.detail());
        assertEquals(found.work(), cancelled.work());
        assertTrue(cancelled.value().isEmpty());
        var prepared = found.requireValue().prepared();
        assertEquals(INVALID, prepared.apply(List.of(), List.of(), LIMITS, () -> false).status());
        assertEquals("CANCELLED", prepared.apply(List.of(List.of(ONE, ONE, ONE)),
            List.of(List.of(ONE, ONE, ONE)), LIMITS, () -> true).detail());
    }

    @Test void retainedParentStateCannotBeHiddenFromAChildsCellLimit() {
        var fixture = centered(3);
        var found = ComposedBilinearSearch.search(fixture.request(), fixture.program(), 1, RESIDUAL, 12,
            LIMITS, () -> false).requireValue();
        int minimumForEverySeparateStage = 1;
        for (var stage : found.prepared().plan().stages()) {
            var source = ((ComposedBilinearPlan.Linear) stage).plan().source();
            int low = 1;
            int high = LIMITS.maximumTerms();
            while (low < high) {
                int middle = low + (high - low) / 2;
                var result = OperatorResidualSearch.discover(source, RESIDUAL.residual(),
                    new IterationLimits(LIMITS.maximumWork(), middle, LIMITS.timeoutMillis()), () -> false);
                if (result.status() == VERIFIED) high = middle;
                else {
                    assertEquals("TERM_LIMIT", result.detail());
                    low = middle + 1;
                }
            }
            minimumForEverySeparateStage = Math.max(minimumForEverySeparateStage, low);
        }
        var tight = new IterationLimits(LIMITS.maximumWork(), minimumForEverySeparateStage, LIMITS.timeoutMillis());
        for (var stage : found.prepared().plan().stages()) {
            var source = ((ComposedBilinearPlan.Linear) stage).plan().source();
            assertEquals(VERIFIED, OperatorResidualSearch.discover(source, RESIDUAL.residual(), tight, () -> false).status());
        }
        var combined = ComposedBilinearSearch.search(fixture.request(), fixture.program(), 1, RESIDUAL, 12, tight, () -> false);
        assertEquals(STOPPED, combined.status());
        assertEquals("TERM_LIMIT", combined.detail());
        assertTrue(combined.value().isEmpty());
    }

    @Test void unseenRationalProgramsAgreeWithTensorEvaluationAcrossDifferentShapes() {
        var random = new Random(817233);
        for (int trial = 0; trial < 12; trial++) {
            int rank = 2 + random.nextInt(3);
            int leftSize = 2 + random.nextInt(3);
            int rightSize = 2 + random.nextInt(3);
            int outputs = 1 + random.nextInt(4);
            var fixture = fixture(randomBatch(random, rank, leftSize), randomBatch(random, rank, rightSize),
                randomBatch(random, outputs, rank));
            var prepared = ComposedBilinearSearch.search(fixture.request(), fixture.program(), 2, RESIDUAL, 12,
                LIMITS, () -> false).requireValue().prepared();
            var left = randomBatch(random, 2, leftSize);
            var right = randomBatch(random, 2, rightSize);
            assertEquals(tensorBatch(fixture.request().tensor(), left, right),
                prepared.apply(left, right, LIMITS, () -> false).requireValue());
            assertTrue(prepared.cost().total() <= prepared.directCost().total());
        }
    }

    private record Fixture(BilinearProgram.Request request, BilinearProgram program) {}

    private static Fixture centered(int size) {
        var matrix = new ArrayList<List<ExactRational>>();
        var identity = new ArrayList<List<ExactRational>>();
        var reciprocal = new ExactRational(BigInteger.ONE, BigInteger.valueOf(size));
        for (int i = 0; i < size; i++) {
            var row = new ArrayList<ExactRational>();
            var unit = new ArrayList<ExactRational>();
            for (int j = 0; j < size; j++) {
                var delta = i == j ? ONE : ZERO;
                row.add(delta.subtract(reciprocal));
                unit.add(delta);
            }
            matrix.add(row);
            identity.add(unit);
        }
        return fixture(matrix, matrix, identity);
    }

    private static Fixture fixture(List<List<ExactRational>> u, List<List<ExactRational>> v,
                                   List<List<ExactRational>> w) {
        int left = u.getFirst().size();
        int right = v.getFirst().size();
        var coefficients = new ArrayList<List<ExactRational>>();
        for (var weights : w) {
            var output = new ArrayList<ExactRational>();
            for (int i = 0; i < left; i++) for (int j = 0; j < right; j++) {
                var sum = ZERO;
                for (int p = 0; p < u.size(); p++) sum = sum.add(weights.get(p).multiply(u.get(p).get(i)).multiply(v.get(p).get(j)));
                output.add(sum);
            }
            coefficients.add(output);
        }
        var program = new BilinearProgram(u, v, w);
        var request = new BilinearProgram.Request(new BilinearProgram.Tensor(left, right, coefficients), u, v, u.size());
        return new Fixture(request, program);
    }

    private static List<List<ExactRational>> rows(long[]... entries) {
        var rows = new ArrayList<List<ExactRational>>();
        for (var row : entries) {
            var values = new ArrayList<ExactRational>();
            for (long entry : row) values.add(integer(entry));
            rows.add(values);
        }
        return rows;
    }

    private static List<List<ExactRational>> randomBatch(Random random, int width, int dimension) {
        var rows = new ArrayList<List<ExactRational>>();
        for (int b = 0; b < width; b++) {
            var row = new ArrayList<ExactRational>();
            for (int j = 0; j < dimension; j++) row.add(new ExactRational(BigInteger.valueOf(random.nextInt(13) - 6),
                BigInteger.valueOf(1 + random.nextInt(5))));
            rows.add(row);
        }
        return rows;
    }

    private static List<List<ExactRational>> tensorBatch(BilinearProgram.Tensor tensor,
            List<List<ExactRational>> left, List<List<ExactRational>> right) {
        var result = new ArrayList<List<ExactRational>>();
        for (int b = 0; b < left.size(); b++) {
            var output = new ArrayList<ExactRational>();
            for (var coefficients : tensor.outputs()) {
                var sum = ZERO;
                for (int i = 0; i < tensor.leftSize(); i++) for (int j = 0; j < tensor.rightSize(); j++)
                    sum = sum.add(coefficients.get(i * tensor.rightSize() + j).multiply(left.get(b).get(i)).multiply(right.get(b).get(j)));
                output.add(sum);
            }
            result.add(output);
        }
        return result;
    }
}
