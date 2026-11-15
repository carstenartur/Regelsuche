package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.ast.iteration.OrderedReduction.All;
import de.regelsuche.ast.iteration.OrderedReduction.Bound;
import de.regelsuche.ast.iteration.OrderedReduction.KeyInterval;
import de.regelsuche.symbol.SymbolicExpression;
import de.regelsuche.symbol.SymbolScope;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class OrderedBlockReductionTest {
    private static final IterationLimits SEARCH = new IterationLimits(100_000, 256, 10_000);
    private static final IterationLimits EXECUTE = new IterationLimits(100_000_000, 256, 20_000);
    private static final BooleanSupplier NEVER = () -> false;

    @Test
    void derivesPositionSensitiveStateAndRepairsOnePath() {
        var input = source("(p1-p0)*(x0-x1)^2", 2);
        var plan = derive(input);
        var rows = new ArrayList<>(List.of(row(4, 0), row(-2, 1), row(9, 2), row(3, 3), row(1, 4)));
        var tree = plan.newAccumulator(rows, EXECUTE, NEVER);
        assertEquals(pairOracle(rows), tree.value());
        assertEquals(6, plan.plan().statistics().size());
        for (int i : List.of(4, 0, 2, 1, 3, 2)) {
            var replacement = row(11 - i, i);
            rows.set(i, replacement);
            var update = tree.replace(i, replacement, EXECUTE, NEVER);
            assertEquals(pairOracle(rows), update.value());
            assertEquals(update.value(), tree.value());
            assertEquals(3, update.blockCombinations());
        }
    }

    @Test
    void derivesNonadditiveSubtuplesForOrderedTriples() {
        var plan = derive(source("(x0*x2-x1^2)^2", 3));
        var rows = new ArrayList<>(List.of(row(2, 0), row(3, 1), row(5, 2), row(-1, 3), row(7, 4)));
        var tree = plan.newAccumulator(rows, EXECUTE, NEVER);
        assertEquals(tripleOracle(rows), tree.value());
        assertTrue(plan.plan().statistics().stream().anyMatch(statistic -> statistic.size() == 2));
        rows.set(1, row(-3, 1));
        assertEquals(tripleOracle(rows), tree.replace(1, rows.get(1), EXECUTE, NEVER).value());
    }

    @Test
    void checksTheCandidateAgainstTheOriginalExpressionAndEveryCut() {
        var input = source("(p1-p0)*(x0-x1)^2", 2);
        var plan = derive(input).plan();
        var missing = new OrderedBlockReductionPlan(plan.reduction(), plan.statistics().subList(1, 6));
        assertEquals("MISSING_BLOCK_STATISTIC",
            OrderedReductionSearch.verifyPointUpdates(input, missing, SEARCH, NEVER).reason());
        assertEquals("ORDERED_IDENTITY_MISMATCH",
            OrderedReductionSearch.verifyPointUpdates(source("x0^2", 2), plan, SEARCH, NEVER).reason());
    }

    @Test
    void preservesThePreviousStateWhenAnUpdateFails() {
        var plan = derive(source("(p1-p0)*(x0-x1)^2", 2));
        var rows = new ArrayList<>(List.of(row(2, 0), row(-4, 1), row(1, 2), row(7, 3), row(0, 4)));
        var tree = plan.newAccumulator(rows, EXECUTE, NEVER);
        var previous = tree.value();
        var replacement = row(9, 3);
        var completed = tree.replace(3, replacement, EXECUTE, NEVER);
        tree.replace(3, rows.get(3), EXECUTE, NEVER);

        assertThrows(IllegalArgumentException.class, () -> tree.replace(3, replacement,
            EXECUTE.withMaximumWork(completed.work() - 1), NEVER));
        assertThrows(IllegalArgumentException.class, () -> tree.replace(3, replacement, EXECUTE, () -> true));
        assertThrows(IllegalArgumentException.class, () -> tree.replace(3, List.of(BigInteger.ONE), EXECUTE, NEVER));
        assertEquals(previous, tree.value());
        rows.set(4, row(5, 4));
        assertEquals(pairOracle(rows), tree.replace(4, rows.get(4), EXECUTE, NEVER).value());
    }

    @Test
    void rejectsValueRestrictionsAndHandlesEmptyOrSingletonInputs() {
        var unrestricted = source("1", 2);
        var interval = new KeyInterval(0, 0, Optional.empty(), Optional.of(new Bound(BigInteger.ZERO, false)), false);
        var restricted = new OrderedReduction(unrestricted.slots(), List.of(interval), unrestricted.summand());
        assertEquals("POINT_UPDATES_REQUIRE_UNRESTRICTED_ROWS",
            OrderedReductionSearch.searchPointUpdates(restricted, SEARCH, 4, NEVER).reason());
        var plan = derive(unrestricted);
        var empty = plan.newAccumulator(List.of(), EXECUTE, NEVER);
        assertEquals(BigInteger.ZERO, empty.value());
        assertThrows(IndexOutOfBoundsException.class, () -> empty.replace(0, row(3, 0), EXECUTE, NEVER));
        var singleton = plan.newAccumulator(List.of(row(-4, 0)), EXECUTE, NEVER);
        var update = singleton.replace(0, row(1, 0), EXECUTE, NEVER);
        assertEquals(BigInteger.ZERO, update.value());
        assertEquals(0, update.blockCombinations());
    }

    private static OrderedBlockReductionPlan.Verified derive(OrderedReduction input) {
        var result = OrderedReductionSearch.searchPointUpdates(input, SEARCH, 4, NEVER);
        return result.verified().orElseThrow(() -> new AssertionError(result.reason()));
    }

    static OrderedReduction source(String formula, int arity) {
        var scope = new SymbolScope();
        var slots = IntStream.range(0, arity)
            .mapToObj(i -> List.of(scope.declare("x" + i), scope.declare("p" + i))).toList();
        var restrictions = IntStream.range(1, arity).<OrderedReduction.Restriction>mapToObj(i -> All.ROWS).toList();
        return new OrderedReduction(slots, restrictions, SymbolicExpression.parse(formula, scope).expression());
    }

    static List<BigInteger> row(long x, long p) {
        return List.of(BigInteger.valueOf(x), BigInteger.valueOf(p));
    }

    private static BigInteger pairOracle(List<List<BigInteger>> rows) {
        BigInteger total = BigInteger.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                total = total.add(rows.get(j).get(1).subtract(rows.get(i).get(1))
                    .multiply(rows.get(i).get(0).subtract(rows.get(j).get(0)).pow(2)));
            }
        }
        return total;
    }

    private static BigInteger tripleOracle(List<List<BigInteger>> rows) {
        BigInteger total = BigInteger.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                for (int k = j + 1; k < rows.size(); k++) {
                    total = total.add(rows.get(i).getFirst().multiply(rows.get(k).getFirst())
                        .subtract(rows.get(j).getFirst().pow(2)).pow(2));
                }
            }
        }
        return total;
    }
}
