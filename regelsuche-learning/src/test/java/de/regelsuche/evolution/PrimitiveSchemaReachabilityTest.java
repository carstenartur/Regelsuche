package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrimitiveSchemaReachabilityTest {
    private static CheckedSchemaTaskSolver.Task task(String input, int nodes, long work) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(input).expression(),
            List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(nodes), new MoveSearch.Budget(12, 12, 100_000, 64, work));
    }

    private static CheckedLearnedSchemaModel cancellation(EvolutionGenome inventory) {
        var base = TypedLearnedMoveInventory.primitives(inventory).newTaskSolver();
        var model = CheckedLearnedSchemaModel.extendFromSolutions(CheckedLearnedSchemaModel.empty(inventory.contentHash()),
            List.of(base.solve(task("((x-y)+y)+0", 1, 100_000)), base.solve(task("((a*b-c)+c)+0", 3, 100_000))));
        assertEquals(1, model.schemas().size());
        return model;
    }

    @Test void preservesIncompleteDomainOnTheSourceAndAfterAPrimitiveRewrite() {
        var original = TraceStrategyTransferExample.inventory();
        var inventory = original.withRewrites(original.rewrites().stream().map(gene -> gene.geneId().equals("multiply-one")
            ? gene.withPatterns("?A*1", "?A/(1+0)") : gene).toList());
        var model = cancellation(inventory);
        var primitives = TypedLearnedMoveInventory.primitives(inventory);
        var routed = primitives.newTaskSolverWithSelection(model, true);
        var control = primitives.newTaskSolverWithSelection(model);
        for (var source : List.of("x/y", "x^y", "x^33", "x / 0", "k*1", "x".repeat(129) + "+3",
                "x+" + "9".repeat(90))) {
            var input = task(source, 1, 100_000);
            var scheduling = source.equals("k*1") ? MoveSearch.Scheduling.STAGED : MoveSearch.Scheduling.STAGED_INCREMENTAL;
            assertEquals(scheduling, routed.problem(input).scheduling(), source);
            for (var solver : List.of(control, routed)) {
                var result = solver.solve(input);
                assertFalse(result.solved(), source);
                assertEquals(MoveSearch.Outcome.INCONCLUSIVE, result.evidence().search().outcome(), source);
                assertFalse(result.evidence().search().completeBoundedRelation(), source);
            }
        }
    }

    @Test void boundedInspectionCannotCertifyAnUnvisitedBranchAndEvenAZeroStepQueryPaysRouting() {
        var inventory = TraceStrategyTransferExample.inventory();
        var model = cancellation(inventory);
        var route = PrimitiveSchemaReachability.compile(inventory, model);
        var input = task("x*y", 3, 100_000);
        assertTrue(route.inspect(input.source(), 100).primitiveOnly());
        for (long allowance = 1; allowance <= 3; allowance++) {
            var decision = route.inspect(input.source(), allowance);
            assertFalse(decision.primitiveOnly());
            assertTrue(decision.work() <= allowance);
        }
        var primitives = TypedLearnedMoveInventory.primitives(inventory);
        var routed = primitives.newTaskSolverWithSelection(model, true);
        var base = primitives.newTaskSolver();
        var baseline = base.solve(input);
        var actual = routed.solve(input);
        assertTrue(actual.solved());
        assertTrue(actual.evidence().witness().isEmpty());
        assertEquals(baseline.evidence().totalWork() + route.inspect(input.source(), 100).work() - 3, actual.evidence().totalWork());
        assertTrue(routed.preparationWork() > primitives.newTaskSolverWithSelection(model).preparationWork());
        assertFalse(routed.solve(task("x*y", 3, actual.evidence().totalWork()-1)).solved());
    }
}
