package de.regelsuche.math.algorithms.iteration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
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

    private static PairReduction source(String summand) {
        var scope = new SymbolScope();
        var column = new PairReduction.Column(scope.declare("left"), scope.declare("right"));
        return new PairReduction(List.of(column), SymbolicExpression.parse(summand, scope).expression());
    }
}
