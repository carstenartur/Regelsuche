package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.VariableExpr;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypedStateValuationTest {
    @Test void sourceOnlyIncumbentRetainsValuedRootCapabilities() {
        var source = new VariableExpr("a");
        var codec = new de.regelsuche.search.program.CompiledAstReplayCodec();
        var encoded = codec.encodeExpression(source);
        var capability = new StateValue.Capability("available", encoded, "root", encoded, "witness");
        var problem = new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.sourceOnly(List.of("a integer"), MoveContext.Phase.FROZEN_EVALUATION),
            List.of(), MovePriorityPolicy.INVENTORY_ORDER,
            (state, move, context) -> new MoveVerifier.Verification(false, 1, List.of(), "unused"),
            state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(4, 4, 100, 20, 100),
            (state, context) -> new StateValue.Assessment(1, 0, 1, 0, Map.of("available", capability)));
        var result = new TypedSourceOnlySearch().search(problem, state -> new TypedSourceOnlySearch.Score(1, 1));
        assertTrue(result.incumbent().capabilities().contains("available"));
        assertEquals(List.of("a integer"), result.incumbent().assumptions());
    }

    @Test void valuationWorkConsumesTheSameSearchBudgetBeforeExpansion() {
        var problem = new TypedMoveSearch.Problem(new VariableExpr("a"),
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            List.of(), MovePriorityPolicy.INVENTORY_ORDER,
            (state, move, context) -> new MoveVerifier.Verification(false, 1, List.of(), "unused"),
            state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(4, 4, 100, 20, 3),
            (state, context) -> new StateValue.Assessment(1, 0, 5, 0, Map.of()));
        var result = new TypedMoveSearch().search(problem);
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, result.outcome());
        assertEquals(5, result.metrics().totalWork());
        assertEquals(0, result.metrics().expandedStates());
        assertFalse(result.completeBoundedRelation());
    }
}
