package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProofDepthChallengeTest {
    @Test void billsTheNativeProofAndPreservesTheOtherConstraints() {
        var primitive = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory()).newTaskSolver();
        var bounds = new MoveSearch.Budget(32, 12, 8192, 256, 8192, 2);
        var task = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm("(x+0)*1").expression(),
            List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(1), bounds);
        var result = primitive.solve(task);
        var gate = ProofDepthChallenge.from(result).orElseThrow();
        assertSame(task.source(), gate.source());
        assertSame(task.goal(), gate.goal());
        assertEquals(task.assumptions(), gate.assumptions());
        assertEquals(new MoveSearch.Budget(bounds.maxPrimitiveSteps(), result.evidence().witness().size()-1,
            bounds.maxTheoryWork(), bounds.maxStates(), result.evidence().totalWork(), bounds.maxComplexityDebt()), gate.budget());
        var oneStep = new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm("x+0").expression(),
            List.of(), task.goal(), bounds);
        assertTrue(ProofDepthChallenge.from(primitive.solve(oneStep)).isEmpty());
        assertTrue(ProofDepthChallenge.from(primitive.solve(gate)).isEmpty());
    }
}
