package de.regelsuche.math.algorithms.iteration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class PairReductionSearchTest {
    private static final IterationLimits LIMITS = new IterationLimits(500_000, 128, 30_000);
    private static final BooleanSupplier NEVER = () -> false;

    @Test
    void admitsBothTypedMovesThroughTheSharedWorkLedger() {
        var source = source("(left-right)^2");
        var result = PairReductionSearch.search(source, LIMITS, 3, NEVER);
        assertEquals("VERIFIED", result.reason());
        assertSame(source, result.verified().orElseThrow().source());
        assertEquals(List.of("normalize-pair-summand", "reduce-pair-sum"),
            result.witness().stream().map(PairReductionSearch.CheckedMove::ruleId).toList());
        assertEquals(Map.of("pair-reduction", 2L), result.metrics().familyMatches());
        assertEquals(2, result.metrics().consumedSuccessors());
        assertEquals(result.witness().stream().mapToLong(PairReductionSearch.CheckedMove::synthesisWork).sum(),
            result.metrics().primitiveWork());
        assertEquals(result.witness().stream().mapToLong(PairReductionSearch.CheckedMove::verificationWork).sum(),
            result.metrics().verificationWork());
        assertTrue(result.metrics().totalWork() <= LIMITS.maximumWork());
    }

    @Test
    void rejectsAnIncompleteProofPathWithinWorkAndStateLimits() {
        var source = source("(left-right)^2");
        var sufficient = PairReductionSearch.search(source, LIMITS, 3, NEVER);
        long used = sufficient.metrics().totalWork();
        var workLimited = PairReductionSearch.search(source, LIMITS.withMaximumWork(used - 1), 3, NEVER);
        assertEquals("WORK_LIMIT", workLimited.reason());
        assertTrue(workLimited.verified().isEmpty());
        assertTrue(workLimited.witness().isEmpty());

        long proofWork = sufficient.witness().getLast().verificationWork();
        var proofLimited = PairReductionSearch.search(source, LIMITS.withMaximumWork(used - proofWork / 2), 3, NEVER);
        assertEquals("WORK_LIMIT", proofLimited.reason());
        assertTrue(proofLimited.verified().isEmpty());
        assertEquals(2, proofLimited.metrics().generatedSuccessors());
        assertTrue(proofLimited.metrics().verificationWork() < sufficient.metrics().verificationWork());

        var stateLimited = PairReductionSearch.search(source, LIMITS, 1, NEVER);
        assertEquals("STATE_LIMIT", stateLimited.reason());
        assertFalse(stateLimited.verified().isPresent());
        assertEquals(1, stateLimited.metrics().consumedSuccessors());
    }

    @Test
    void failsClosedOnUnsupportedAndCancelledSearches() {
        var asymmetric = PairReductionSearch.search(source("left-right"), LIMITS, 3, NEVER);
        assertEquals("ASYMMETRIC_SUMMAND", asymmetric.reason());
        assertTrue(asymmetric.verified().isEmpty());
        var cancelled = PairReductionSearch.search(source("left*right"), LIMITS, 3, () -> true);
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.verified().isEmpty());
    }

    @Test
    void orderedWindowAddsTheNewestPairAndRemovesTheOldestPair() {
        var input = source("left*(right-left)");
        var result = PairReductionSearch.searchWindows(input, LIMITS, 3, NEVER);
        var checked = result.verified().orElseThrow();
        var rows = List.of(1L, 2L, 4L, -3L);
        var evaluation = checked.evaluate(rows.size(), 3, i -> List.of(BigInteger.valueOf(rows.get(i))), LIMITS, NEVER);

        assertEquals(List.of(BigInteger.valueOf(8), BigInteger.valueOf(-34)), evaluation.sums());
        assertEquals(5, evaluation.rowReads());
        assertEquals(List.of("normalize-pair-summand", "reduce-pair-windows"),
            result.witness().stream().map(PairReductionSearch.CheckedMove::ruleId).toList());
        assertTrue(PairReductionSearch.verifyWindows(source("right*(left-right)"), checked.plan(), LIMITS, NEVER)
            .verified().isEmpty());
        assertTrue(PairReductionSearch.searchWindows(input, LIMITS.withMaximumWork(result.metrics().totalWork() - 1),
            3, NEVER).verified().isEmpty());
        assertTrue(checked.evaluate(2, 3, i -> { throw new AssertionError("no complete window"); }, LIMITS, NEVER)
            .sums().isEmpty());
    }

    @Test
    void visitsRowsLinearlyAcrossOverlappingWindows() {
        var checked = PairReductionSearch.searchWindows(source("(left-right)^2"), LIMITS, 3, NEVER)
            .verified().orElseThrow();
        var runtimeLimits = LIMITS.withMaximumWork(30_000_000);
        var evaluation = checked.evaluate(100_000, 10_000, i -> List.of(BigInteger.valueOf(i & 1)), runtimeLimits, NEVER);

        assertEquals(190_000, evaluation.rowReads());
        assertEquals(90_001, evaluation.sums().size());
        assertTrue(evaluation.sums().stream().allMatch(BigInteger.valueOf(25_000_000)::equals));
    }

    @Test
    void appliesTheSameWindowPlanToAnAsymmetricTwoColumnPolynomial() {
        var scope = new SymbolScope();
        var x = new PairReduction.Column(scope.declare("xl"), scope.declare("xr"));
        var y = new PairReduction.Column(scope.declare("yl"), scope.declare("yr"));
        var source = new PairReduction(List.of(x, y),
            SymbolicExpression.parse("xl*yr+2*yl*xr-xr^2", scope).expression());
        var rows = List.of(List.of(2L, 7L), List.of(3L, 11L), List.of(5L, 13L), List.of(17L, -2L));
        var plan = PairReductionSearch.searchWindows(source, LIMITS, 3, NEVER).verified().orElseThrow();
        var result = plan.evaluate(rows.size(), 2, i -> rows.get(i).stream().map(BigInteger::valueOf).toList(), LIMITS, NEVER);

        assertEquals(List.of(55L, 124L, 143L).stream().map(BigInteger::valueOf).toList(), result.sums());
    }

    @Test
    void oversizedNegativeCoefficientIsDeclinedWithinTheSearch() {
        var negative = BigInteger.ONE.shiftLeft(FiniteIteration.MAX_COEFFICIENT_BITS).negate();
        var input = new PairReduction(source("left").columns(), new NumberExpr(ExactRational.integer(negative)));
        var result = PairReductionSearch.searchWindows(input, LIMITS, 3, NEVER);
        assertEquals("COEFFICIENT_LIMIT", result.reason());
        assertTrue(result.verified().isEmpty());
    }

    private static PairReduction source(String summand) {
        var scope = new SymbolScope();
        var column = new PairReduction.Column(scope.declare("left"), scope.declare("right"));
        return new PairReduction(List.of(column), SymbolicExpression.parse(summand, scope).expression());
    }
}
