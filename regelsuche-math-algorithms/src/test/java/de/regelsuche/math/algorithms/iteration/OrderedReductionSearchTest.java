package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.ast.iteration.OrderedReduction.All;
import de.regelsuche.ast.iteration.OrderedReduction.Bound;
import de.regelsuche.ast.iteration.OrderedReduction.KeyInterval;
import de.regelsuche.ast.iteration.OrderedReduction.Restriction;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderedReductionSearchTest {
    private static final IterationLimits SEARCH = new IterationLimits(100_000, 128, 5_000);
    private static final IterationLimits RUN = new IterationLimits(200_000_000, 128, 20_000);
    private static final BooleanSupplier NEVER = () -> false;

    @Test
    void derivesAndReplaysAnIndexedWeightedChain() {
        var less = new KeyInterval(0, 0, Optional.empty(), bound(0, false), false);
        var equal = new KeyInterval(1, 1, bound(1, true), bound(1, true), false);
        var input = source("u0*(x1-x0)*(x3-x2)+u2", less, equal, All.ROWS);
        var search = OrderedReductionSearch.search(input, SEARCH, 4, NEVER);
        var checked = search.verified().orElseThrow();
        assertEquals(List.of("normalize-ordered-summand", "derive-indexed-reduction"),
            search.witness().stream().map(OrderedReductionSearch.CheckedMove::ruleId).toList());
        assertEquals(search.metrics().verificationWork(),
            search.witness().stream().mapToLong(OrderedReductionSearch.CheckedMove::verificationWork).sum());
        assertEquals(List.of(OrderedReductionPlan.Access.RANGE, OrderedReductionPlan.Access.HASH,
                OrderedReductionPlan.Access.SCALAR),
            checked.plan().lookups().stream().map(OrderedReductionPlan.Lookup::access).toList());

        var random = new Random(167);
        var rows = IntStream.range(0, 31).mapToObj(i -> row(random.nextInt(9) - 4, random.nextInt(5) - 2)).toList();
        long reference = 0;
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                if (value(rows, i, 0) >= value(rows, j, 0)) continue;
                for (int k = j + 1; k < rows.size(); k++) {
                    if (value(rows, j, 1) != value(rows, k, 1) + 1) continue;
                    for (int l = k + 1; l < rows.size(); l++) {
                        reference += value(rows, i, 1) * (value(rows, j, 0) - value(rows, i, 0))
                            * (value(rows, l, 0) - value(rows, k, 0)) + value(rows, k, 1);
                    }
                }
            }
        }
        assertEquals(List.of(BigInteger.valueOf(reference)), checked.evaluate(rows, RUN, NEVER).sums());
        assertTrue(OrderedReductionSearch.verify(input, checked.plan(), false, SEARCH, NEVER).verified().isPresent());
        assertTrue(OrderedReductionSearch.search(input, SEARCH.withMaximumWork(1), 4, NEVER).verified().isEmpty());
        assertTrue(OrderedReductionSearch.search(input, SEARCH, 4, () -> true).verified().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> checked.evaluate(rows, RUN.withMaximumWork(1), NEVER));
    }

    @Test
    void rollsAnAsymmetricWeightedBandWithTiesAndEvictions() {
        var interval = new KeyInterval(0, 0, bound(0, false), bound(4, true), false);
        var input = source("u0*(x0-x1)^2+u1", interval);
        var checked = OrderedReductionSearch.searchWindows(input, SEARCH, 4, NEVER).verified().orElseThrow();
        var rows = List.of(row(4, -3), row(1, 8), row(4, 2), row(0, -7), row(5, 4),
            row(0, 9), row(-1, 1), row(3, -4), row(1, -5));
        for (int width = 1; width <= rows.size() + 1; width++) {
            var expected = new ArrayList<BigInteger>();
            for (int start = 0; start + width <= rows.size(); start++) {
                long total = 0;
                for (int i = start; i < start + width; i++) {
                    for (int j = i + 1; j < start + width; j++) {
                        long gap = value(rows, i, 0) - value(rows, j, 0);
                        if (gap > 0 && gap <= 4) {
                            total += value(rows, i, 1) * gap * gap + value(rows, j, 1);
                        }
                    }
                }
                expected.add(BigInteger.valueOf(total));
            }
            assertEquals(expected, checked.evaluateWindows(rows, width, RUN, NEVER).sums(), "width " + width);
        }
        var plan = checked.plan();
        var wrong = new OrderedReductionPlan(plan.terms(), plan.lookups(), Optional.of(plan.lookups().getFirst()));
        assertEquals("EVICTION_PREDICATE_MISMATCH",
            OrderedReductionSearch.verify(input, wrong, true, SEARCH, NEVER).reason());
        assertEquals("ORDERED_IDENTITY_MISMATCH",
            OrderedReductionSearch.verify(source("u0", interval), plan, true, SEARCH, NEVER).reason());
    }

    @Test
    void handlesAChainWithEightEqualKeysWithoutReusingARow() {
        var equal = new KeyInterval(0, 0, bound(0, true), bound(0, true), false);
        var input = source("1", equal, equal, equal, equal, equal, equal, equal);
        var checked = OrderedReductionSearch.search(input, SEARCH, 4, NEVER).verified().orElseThrow();
        var rows = java.util.Collections.nCopies(10, row(7, 0));
        assertEquals(List.of(BigInteger.valueOf(45)), checked.evaluate(rows, RUN, NEVER).sums());
    }

    @Test
    void usesExactOffsetsForComplementedEqualityAcrossColumns() {
        var point = new KeyInterval(0, 1, bound(1, true), bound(1, true), true);
        var checked = OrderedReductionSearch.searchWindows(source("u0", point), SEARCH, 4, NEVER)
            .verified().orElseThrow();
        var large = BigInteger.ONE.shiftLeft(100);
        var rows = List.of(List.of(large.add(BigInteger.ONE), BigInteger.TWO),
            List.of(large.add(BigInteger.TWO), large),
            List.of(BigInteger.ZERO, large.add(BigInteger.ONE)),
            List.of(BigInteger.ZERO, large));
        assertEquals(List.of(BigInteger.ZERO, BigInteger.ZERO, large.add(BigInteger.ONE)),
            checked.evaluateWindows(rows, 2, RUN, NEVER).sums());
    }

    @Test
    void returnsAReasonWhenTheNormalizedCoefficientExceedsThePlanLimit() {
        var base = source("1", All.ROWS);
        var coefficient = BigInteger.ONE.shiftLeft(FiniteIteration.MAX_COEFFICIENT_BITS).negate();
        var input = new OrderedReduction(base.slots(), base.restrictions(),
            new NumberExpr(de.regelsuche.scalar.ExactRational.integer(coefficient)));
        var result = OrderedReductionSearch.search(input, SEARCH, 4, NEVER);
        assertTrue(result.verified().isEmpty());
        assertEquals("COEFFICIENT_LIMIT", result.reason());
    }

    @Test
    void evaluatesAllHundredThousandRowWindowsWithAClosedForm() {
        int size = 100_000;
        int width = 10_000;
        int tolerance = 32;
        var interval = new KeyInterval(0, 0, bound(0, false), bound(tolerance, true), false);
        var input = source("(x0-x1)^2", interval);
        var checked = OrderedReductionSearch.searchWindows(input, SEARCH, 4, NEVER).verified().orElseThrow();
        var rows = IntStream.range(0, size).mapToObj(i -> row(size - i, 0)).toList();
        var result = checked.evaluateWindows(rows, width, RUN, NEVER);
        long sum = IntStream.rangeClosed(1, tolerance).mapToLong(gap -> (width - gap) * (long) gap * gap).sum();
        assertEquals(size - width + 1, result.sums().size());
        assertTrue(result.sums().stream().allMatch(BigInteger.valueOf(sum)::equals));
        assertEquals(size, result.rowReads());
        assertTrue(result.indexAccesses() > 0 && result.indexAccesses() < 35_000_000);
    }

    @Test
    void checksHundredThousandRowsWithAnIndependentClosedForm() {
        var less = new KeyInterval(0, 0, Optional.empty(), bound(0, false), false);
        var input = source("1", less, less);
        var checked = OrderedReductionSearch.search(input, SEARCH, 4, NEVER).verified().orElseThrow();
        int size = 100_000;
        var rows = IntStream.range(0, size).mapToObj(i -> row(i, 0)).toList();
        var result = checked.evaluate(rows, RUN, NEVER);
        assertEquals(List.of(BigInteger.valueOf((long) size * (size - 1) * (size - 2) / 6)), result.sums());
        assertEquals(size, result.rowReads());
        assertTrue(result.indexAccesses() > 0 && result.indexAccesses() < 7_000_000);
    }

    private static OrderedReduction source(String term, Restriction... conditions) {
        var scope = new SymbolScope();
        var slots = IntStream.rangeClosed(0, conditions.length)
            .mapToObj(index -> List.of(scope.declare("x" + index), scope.declare("u" + index))).toList();
        return new OrderedReduction(slots, List.of(conditions), SymbolicExpression.parse(term, scope).expression());
    }

    private static Optional<Bound> bound(long offset, boolean inclusive) {
        return Optional.of(new Bound(BigInteger.valueOf(offset), inclusive));
    }

    private static List<BigInteger> row(long x, long u) {
        return List.of(BigInteger.valueOf(x), BigInteger.valueOf(u));
    }

    private static long value(List<List<BigInteger>> rows, int row, int column) {
        return rows.get(row).get(column).longValueExact();
    }
}
