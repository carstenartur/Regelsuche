package de.regelsuche.math.algorithms.iteration;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.math.BigInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderedReductionPrefixTest {
    private static final IterationLimits LIMITS = new IterationLimits(100_000, 128, 5_000);

    @Test
    void acceptsAndIndependentlyChecksUnaryRowPolynomials() {
        var scope = new SymbolScope();
        var slots = List.of(List.of(scope.declare("x")));
        var source = assertDoesNotThrow(() -> new OrderedReduction(slots, List.of(),
            SymbolicExpression.parse("x*x+3*x", scope).expression()));
        var result = OrderedReductionSearch.search(source, LIMITS, 4, () -> false);
        var found = result.verified().orElseThrow();
        var replay = OrderedReductionSearch.verify(source, found.plan(), false, LIMITS, () -> false);
        assertTrue(replay.verified().isPresent());
        assertEquals(List.of(BigInteger.valueOf(26)),
            found.evaluate(List.of(row(2), row(-2), row(3)), LIMITS, () -> false).sums());
        assertEquals(List.of(BigInteger.TEN, BigInteger.valueOf(8), BigInteger.valueOf(26)),
            found.evaluatePrefixes(List.of(row(2), row(-2), row(3)), LIMITS, () -> false).sums());
    }

    @Test
    void prefixBoundaryIncludesShortAndEmptyInputsAndAgreesWithFullTupleSum() {
        var source = source("x0*x1*x2", 3);
        var found = OrderedReductionSearch.search(source, LIMITS, 4, () -> false).verified().orElseThrow();
        var rows = List.of(row(2), row(3), row(4), row(5));
        var prefixes = found.evaluatePrefixes(rows, LIMITS, () -> false);
        assertEquals(List.of(BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(24), BigInteger.valueOf(154)),
            prefixes.sums());
        assertEquals(found.evaluate(rows, LIMITS, () -> false).sums().getFirst(), prefixes.sums().getLast());
        assertEquals(List.of(), found.evaluatePrefixes(List.of(), LIMITS, () -> false).sums());
        assertEquals(List.of(BigInteger.ZERO), found.evaluate(List.of(), LIMITS, () -> false).sums());
        assertEquals(rows.size(), prefixes.rowReads());
        var zero = OrderedReductionSearch.search(source("0", 1), LIMITS, 4, () -> false).verified().orElseThrow();
        assertEquals(List.of(BigInteger.ZERO, BigInteger.ZERO),
            zero.evaluatePrefixes(rows.subList(0, 2), LIMITS, () -> false).sums());
    }

    @Test
    void rejectsChangedUnaryPlanAndPreservesResourceFailures() {
        var source = source("x0", 1);
        var found = OrderedReductionSearch.search(source, LIMITS, 4, () -> false).verified().orElseThrow();
        var plan = found.plan();
        var term = plan.terms().getFirst();
        var forged = new OrderedReductionPlan(List.of(new OrderedReductionPlan.Term(BigInteger.TWO, term.factors())),
            plan.lookups(), Optional.empty());
        assertTrue(OrderedReductionSearch.verify(source, forged, false, LIMITS, () -> false).verified().isEmpty());
        assertTrue(OrderedReductionSearch.verify(source("x0*x0", 1), plan, false, LIMITS, () -> false).verified().isEmpty());
        assertThrows(IllegalArgumentException.class,
            () -> found.evaluatePrefixes(List.of(row(2)), LIMITS.withMaximumWork(1), () -> false));
        assertThrows(IllegalArgumentException.class,
            () -> found.evaluatePrefixes(List.of(row(2)), LIMITS, () -> true));
        assertEquals("POINT_UPDATES_REQUIRE_MULTIPLE_SLOTS",
            OrderedReductionSearch.searchPointUpdates(source, LIMITS, 4, () -> false).reason());
        assertTrue(OrderedReductionSearch.verifyPointUpdates(source,
            new OrderedBlockReductionPlan(plan, List.of()), LIMITS, () -> false).verified().isEmpty());
    }

    @Test
    void preservesOrderedKeyRestrictionsForEveryPrefix() {
        var base = source("1", 2);
        var less = new OrderedReduction.KeyInterval(0, 0, Optional.empty(),
            Optional.of(new OrderedReduction.Bound(BigInteger.ZERO, false)), false);
        var input = new OrderedReduction(base.slots(), List.of(less), base.summand());
        var found = OrderedReductionSearch.search(input, LIMITS, 4, () -> false).verified().orElseThrow();
        assertEquals(List.of(BigInteger.ZERO, BigInteger.ZERO, BigInteger.TWO),
            found.evaluatePrefixes(List.of(row(2), row(1), row(3)), LIMITS, () -> false).sums());
        var window = OrderedReductionSearch.searchWindows(input, LIMITS, 4, () -> false).verified().orElseThrow();
        assertThrows(IllegalArgumentException.class,
            () -> window.evaluatePrefixes(List.of(row(2), row(3)), LIMITS, () -> false));
    }

    private static OrderedReduction source(String expression, int arity) {
        var scope = new SymbolScope();
        var slots = new ArrayList<List<de.regelsuche.symbol.SymbolId>>();
        var restrictions = new ArrayList<OrderedReduction.Restriction>();
        for (int i = 0; i < arity; i++) {
            slots.add(List.of(scope.declare("x" + i)));
            if (i > 0) restrictions.add(OrderedReduction.All.ROWS);
        }
        return new OrderedReduction(slots, restrictions, SymbolicExpression.parse(expression, scope).expression());
    }

    private static List<BigInteger> row(long value) { return List.of(BigInteger.valueOf(value)); }
}
