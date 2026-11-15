package de.regelsuche.math.algorithms.iteration;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static de.regelsuche.math.algorithms.iteration.OrderedBlockReductionTest.row;
import static de.regelsuche.math.algorithms.iteration.OrderedBlockReductionTest.source;
import static org.junit.jupiter.api.Assertions.*;

class OrderedPairStateTransferTest {
    private static final IterationLimits SEARCH = new IterationLimits(100_000, 256, 10_000);
    private static final IterationLimits EXECUTE = new IterationLimits(100_000_000, 256, 30_000);
    private static final BooleanSupplier NEVER = () -> false;
    private static final String TRAINING = "(p1-p0)*(x0-x1)^2";
    private static final String PREFIX = "(p1-p0)*(x0^2-2*x0*x1+x1^2)*(x2+2*p2-3)";
    private static final String SUFFIX = "(1-x0*p0)*(p2-p1)*(x1-x2)^2";

    @Test
    void reusesOneWholePairStateFromTheTrainingPlan() {
        var training = checked(OrderedReductionSearch.searchPointUpdates(source(TRAINING, 2), SEARCH, 4, NEVER));
        var lesson = OrderedReductionKnowledge.learnPairState(training, SEARCH, NEVER).knowledge().orElseThrow();
        var task = source(PREFIX, 3);
        var plain = checked(OrderedReductionSearch.searchPointUpdates(task, SEARCH, 4, NEVER));
        var trained = checked(OrderedReductionSearch.searchPointUpdates(task, lesson, SEARCH, 4, NEVER));
        var projection = trained.plan().pairState().orElseThrow();
        assertTrue(projection.pairFirst());
        assertTrue(trained.plan().statistics().size() + 1 < plain.plan().statistics().size());
        var rows = new ArrayList<>(List.of(row(2, 0), row(-3, 1), row(11, 2), row(6, 3), row(5, 4), row(-3, 5)));
        var tree = trained.newAccumulator(rows, EXECUTE, NEVER);
        assertEquals(prefixOracle(rows), tree.value());
        for (int index : List.of(0, 5, 2)) {
            rows.set(index, row(13 - index, index));
            assertEquals(prefixOracle(rows), tree.replace(index, rows.get(index), EXECUTE, NEVER).value());
        }
    }

    @Test
    void rejectsAForgedProjectionDespiteHavingTheCorrectOriginalFactorization() {
        var training = checked(OrderedReductionSearch.searchPointUpdates(source(TRAINING, 2), SEARCH, 4, NEVER));
        var lesson = OrderedReductionKnowledge.learnPairState(training, SEARCH, NEVER).knowledge().orElseThrow();
        var target = source(SUFFIX, 3);
        var plan = checked(OrderedReductionSearch.searchPointUpdates(target, lesson, SEARCH, 4, NEVER)).plan();
        var projection = plan.pairState().orElseThrow();
        assertFalse(projection.pairFirst());
        var multipliers = new ArrayList<>(projection.rowTerms());
        var wrong = multipliers.getFirst();
        multipliers.set(0, new OrderedPairStateProjection.RowTerm(wrong.factor(), wrong.coefficient().negate()));
        var forged = new OrderedBlockReductionPlan(plan.reduction(), plan.statistics(),
            Optional.of(new OrderedPairStateProjection(false, projection.pairTerms(), multipliers)));
        var check = OrderedReductionSearch.verifyPointUpdates(target, forged, SEARCH, NEVER);
        assertTrue(check.verified().isEmpty());
        assertEquals("PAIR_STATE_IDENTITY_MISMATCH", check.reason());
    }

    @Test
    void keepsTheOriginalStateIfGroupingCannotShrinkIt() {
        var pair = checked(OrderedReductionSearch.searchPointUpdates(source("(1+x0)*x1", 2), SEARCH, 4, NEVER));
        var lesson = OrderedReductionKnowledge.learnPairState(pair, SEARCH, NEVER).knowledge().orElseThrow();
        var target = source("(1+x0)*x1*x2", 3);
        var cold = checked(OrderedReductionSearch.searchProjectedPointUpdates(target, SEARCH, 4, NEVER));
        var warm = checked(OrderedReductionSearch.searchPointUpdates(target, lesson, SEARCH, 4, NEVER));
        assertTrue(cold.plan().pairState().isEmpty());
        assertEquals(cold.plan(), warm.plan());
    }

    @Test
    void triesTheOtherPlacementWhenTheFirstWouldRetainTheSameStateSize() {
        var target = source("x0*(1+x1)*(1+x2+p2)", 3);
        var plan = checked(OrderedReductionSearch.searchProjectedPointUpdates(target, SEARCH, 4, NEVER)).plan();
        assertFalse(plan.pairState().orElseThrow().pairFirst());
    }

    @Test
    void honorsTheAcquisitionTermBudget() {
        var pair = checked(OrderedReductionSearch.searchPointUpdates(source(TRAINING, 2), SEARCH, 4, NEVER));
        var tooTight = new IterationLimits(100_000, 2, 10_000);
        var result = OrderedReductionKnowledge.learnPairState(pair, tooTight, NEVER);
        assertTrue(result.knowledge().isEmpty());
        assertEquals("TERM_LIMIT", result.reason());
    }

    @Test
    void measuresAllWorkAgainstBothUngroupedAndDirectlyGroupedControls() {
        var queries = List.of(
            new Query(PREFIX, true), new Query(SUFFIX, true),
            new Query("(p1-p0)*(x0-x1)^2*(4*x2^2-p2+2)", true),
            new Query("(p2-p1)*(x1^2-2*x1*x2+x2^2)*(2*p0^2+x0-1)", true),
            new Query("-2*(p1-p0)*(x0-x1)^2*(x2+p2+3)", true),
            new Query("(p2-p1)*(x1-x2)^2*(x0^2+2*x0*p0+1)", true),
            new Query("(p2-p0)*(x0-x2)^2*x1", false),
            new Query("(x1-x0)*(p2-p0)-(p1-p0)*(x2-x0)", false)
        );
        var training = OrderedReductionSearch.searchPointUpdates(source(TRAINING, 2), SEARCH, 4, NEVER);
        var acquisition = OrderedReductionKnowledge.learnPairState(checked(training), SEARCH, NEVER);
        var lesson = acquisition.knowledge().orElseThrow();
        var rows = IntStream.range(0, 257).mapToObj(i -> row(i % 17 - 8, i - 50)).toList();
        long[] planning = {training.metrics().totalWork(), training.metrics().totalWork(),
            training.metrics().totalWork() + acquisition.work()};
        long[] building = new long[3];
        long[] updating = new long[3];
        int fits = 0;
        for (var query : queries) {
            var input = source(query.formula(), 3);
            var results = List.of(OrderedReductionSearch.searchPointUpdates(input, SEARCH, 4, NEVER),
                OrderedReductionSearch.searchProjectedPointUpdates(input, SEARCH, 4, NEVER),
                OrderedReductionSearch.searchPointUpdates(input, lesson, SEARCH, 4, NEVER));
            var sessions = new ArrayList<OrderedBlockAccumulator>();
            for (int arm = 0; arm < results.size(); arm++) {
                var verified = checked(results.get(arm));
                planning[arm] += results.get(arm).metrics().totalWork();
                var session = verified.newAccumulator(rows, EXECUTE, NEVER);
                building[arm] += session.initializationWork();
                sessions.add(session);
                assertEquals(sessions.getFirst().value(), session.value());
            }
            var projected = checked(results.get(2)).plan().pairState().isPresent();
            assertEquals(query.expectedProjection(), projected, query.formula());
            assertEquals(checked(results.get(1)).plan().pairState(), checked(results.get(2)).plan().pairState());
            if (projected) {
                fits++;
                assertTrue(results.get(2).metrics().totalWork() < results.get(1).metrics().totalWork());
            } else {
                assertTrue(results.get(2).metrics().totalWork() > results.get(1).metrics().totalWork());
            }
            for (int index : List.of(0, 256, 3, 128, 12, 192)) {
                var replacement = row(17 - index % 31, index - 50);
                var reference = sessions.getFirst().replace(index, replacement, EXECUTE, NEVER);
                updating[0] += reference.work();
                for (int arm = 1; arm < sessions.size(); arm++) {
                    var measured = sessions.get(arm).replace(index, replacement, EXECUTE, NEVER);
                    assertEquals(reference.value(), measured.value());
                    updating[arm] += measured.work();
                }
            }
        }
        assertEquals(6, fits);
        assertTrue(planning[2] < planning[1]);
        assertTrue(building[2] < building[0]);
        assertTrue(updating[2] < updating[0]);
        assertEquals(building[1], building[2]);
        assertEquals(updating[1], updating[2]);
        System.out.printf("Pair-state transfer: seed=%d acquisition=%d planning=%s build=%s updates=%s projections=%d/%d%n",
            training.metrics().totalWork(), acquisition.work(), java.util.Arrays.toString(planning),
            java.util.Arrays.toString(building), java.util.Arrays.toString(updating), fits, queries.size());
    }

    private record Query(String formula, boolean expectedProjection) {}

    private static OrderedBlockReductionPlan.Verified checked(OrderedReductionSearch.PointUpdateResult result) {
        return result.verified().orElseThrow(() -> new AssertionError(result.reason()));
    }

    private static BigInteger prefixOracle(List<List<BigInteger>> rows) {
        BigInteger sum = BigInteger.ZERO;
        for (int k = 2; k < rows.size(); k++) {
            var last = rows.get(k);
            var weight = last.getFirst().add(last.getLast().shiftLeft(1)).subtract(BigInteger.valueOf(3));
            for (int j = 1; j < k; j++) {
                for (int i = 0; i < j; i++) {
                    var distance = rows.get(j).getLast().subtract(rows.get(i).getLast());
                    var difference = rows.get(i).getFirst().subtract(rows.get(j).getFirst());
                    sum = sum.add(distance.multiply(difference.pow(2)).multiply(weight));
                }
            }
        }
        return sum;
    }
}
