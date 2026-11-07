package de.regelsuche.math.algorithms.iteration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.ast.iteration.IndexRange;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class FiniteIterationSearchTest {
    private static final SymbolId INDEX = new SymbolId(new UUID(0, 1), 1);
    private static final SymbolId STATE = new SymbolId(new UUID(0, 1), 2);
    private static final IterationLimits LIMITS = new IterationLimits(100_000, 128, 30_000);
    private static final BooleanSupplier NEVER = () -> false;

    @Test
    void exposesFiniteIterationSearchFromTheMathModule() {
        var source = sum(100);
        var result = FiniteIterationSearch.search(source, LIMITS, 2, NEVER);
        assertEquals("VERIFIED", result.reason());
        var reduction = result.verified().orElseThrow();
        assertEquals("telescope-finite-iteration", reduction.ruleId());
        assertSame(source, reduction.plan().certificate().source());
        assertEquals(new NumberExpr(5050), reduction.plan().result());
        assertEquals(new FiniteIterationReduction.Cost(BigInteger.valueOf(100), 0), reduction.plan().cost());
        assertEquals(Map.of("finite-iteration", 1L), result.metrics().familyMatches());
        assertEquals(1, result.metrics().consumedSuccessors());
        assertEquals(reduction.synthesisWork(), result.metrics().primitiveWork());
        assertEquals(reduction.verificationWork(), result.metrics().verificationWork());
        assertTrue(result.metrics().totalWork() <= LIMITS.maximumWork());
    }

    @Test
    void requiresAnAdmittedProofWithinTheSharedWorkAllowance() {
        var source = sum(100);
        var full = FiniteIterationSearch.search(source, LIMITS, 2, NEVER);
        long tightBudget = full.metrics().totalWork() - full.metrics().verificationWork() / 2;
        var limited = FiniteIterationSearch.search(source, LIMITS.withMaximumWork(tightBudget), 2, NEVER);
        assertEquals("WORK_LIMIT", limited.reason());
        assertTrue(limited.verified().isEmpty());
        assertEquals(1, limited.metrics().generatedSuccessors());
        assertTrue(limited.metrics().verificationWork() > 0);
        assertTrue(limited.metrics().verificationWork() < full.metrics().verificationWork());

        var cancelled = FiniteIterationSearch.search(source, LIMITS, 2, () -> true);
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.verified().isEmpty());
    }

    @Test
    void handlesDescendingAdditiveUpdatesAndRejectsNonlinearOnes() {
        var range = new IndexRange(BigInteger.valueOf(5), BigInteger.valueOf(-2), BigInteger.valueOf(3));
        var initial = new NumberExpr(17);
        var state = VariableExpr.scoped(STATE);
        var additive = new BinaryExpr(state, BinaryOperator.ADD, VariableExpr.scoped(INDEX));
        var source = new FiniteIteration.Recurrence(range, INDEX, STATE, Set.of(), initial, additive, IntegerArithmetic.EXACT);
        var checked = FiniteIterationSearch.search(source, LIMITS, 2, NEVER).verified().orElseThrow();
        assertEquals(new NumberExpr(26), checked.plan().result());
        assertEquals("RESULT_MISMATCH", FiniteIterationReduction.verify(source, new NumberExpr(27),
            checked.plan().certificate(), LIMITS, NEVER).reason());

        var nonlinear = new BinaryExpr(state, BinaryOperator.MUL, state);
        var unsupported = new FiniteIteration.Recurrence(range, INDEX, STATE, Set.of(), initial, nonlinear, IntegerArithmetic.EXACT);
        var rejected = FiniteIterationSearch.search(unsupported, LIMITS, 2, NEVER);
        assertEquals("NONLINEAR_STATE_UPDATE", rejected.reason());
        assertTrue(rejected.verified().isEmpty());
    }

    private static FiniteIteration.Sum sum(long count) {
        var range = new IndexRange(BigInteger.ONE, BigInteger.ONE, BigInteger.valueOf(count));
        return new FiniteIteration.Sum(range, INDEX, Set.of(), VariableExpr.scoped(INDEX), IntegerArithmetic.EXACT);
    }
}
