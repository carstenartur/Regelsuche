package de.regelsuche.math.algorithms.iteration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class LinearIterationKernelTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 128, 5_000);
    private static final BooleanSupplier RUNNING = () -> false;
    private static final SymbolId INDEX = new SymbolId(new UUID(0, 1), 1);
    private static final SymbolId STATE = new SymbolId(new UUID(0, 1), 2);

    @Test void synthesizesVerifiesAndEvaluatesInBothIntegerRings() {
        for (var ring : List.of(IntegerArithmetic.EXACT, IntegerArithmetic.JAVA_INT)) {
            var source = source(ring, 2);
            var result = new LinearIterationOptimizer().search(source, LIMITS, 8, RUNNING);
            var candidate = result.candidate().orElseThrow();
            var verified = new LinearIterationVerifier().verify(source, candidate, LIMITS, 8, RUNNING);
            assertTrue(verified.verified(), verified.reason());
            var evaluation = new LinearIterationEvaluator().evaluate(source, candidate, Map.of(), LIMITS, 8, RUNNING);
            long expected = ring == IntegerArithmetic.EXACT ? 2_147_483_651L : -2_147_483_645L;
            assertEquals(List.of(BigInteger.valueOf(expected)), evaluation.values().orElseThrow());
        }
    }

    @Test void rejectsACandidateBoundToAnotherSource() {
        var first = source(IntegerArithmetic.EXACT, 2);
        var second = source(IntegerArithmetic.EXACT, 3);
        var candidate = new LinearIterationOptimizer().search(first, LIMITS, 8, RUNNING).candidate().orElseThrow();
        var verified = new LinearIterationVerifier().verify(second, candidate, LIMITS, 8, RUNNING);
        assertFalse(verified.verified());
        assertEquals("SOURCE_MISMATCH", verified.reason());
    }

    @Test void retainsCancellationAndTheSeparateAugmentedDimensionLimit() {
        var source = source(IntegerArithmetic.EXACT, 2);
        var optimizer = new LinearIterationOptimizer();
        assertEquals("CANCELLED", optimizer.search(source, LIMITS, 8, () -> true).reason());
        assertEquals("STATE_LIMIT", optimizer.search(source, LIMITS, 1, RUNNING).reason());
        assertTrue(optimizer.search(source, LIMITS, 2, RUNNING).candidate().isPresent());
    }

    private static LinearIteration source(IntegerArithmetic ring, int count) {
        var increment = new BinaryExpr(VariableExpr.scoped(STATE), BinaryOperator.ADD, new NumberExpr(2));
        return new LinearIteration(INDEX, new NumberExpr(0), BigInteger.ONE, new NumberExpr(count),
            List.of(new LinearIteration.State(STATE, new NumberExpr(Integer.MAX_VALUE))), Set.of(),
            List.of(new LinearIteration.Update(STATE, increment)), LinearIteration.UpdateOrder.SEQUENTIAL, ring);
    }
}
