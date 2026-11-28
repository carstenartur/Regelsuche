package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.NativeNodeCountObjective;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SharedNodeCountTest {
    private final CheckedSchemaTaskSolver solver =
        TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory()).newTaskSolver();

    @Test void primitiveProofAndExhaustedRelationSurviveSharing() {
        var reducible = task("((a+b)+0)*1", CheckedSchemaTaskSolver.Goal.atMostNodes(3));
        var previous = solver.solve(reducible);
        var shared = solver.solveWithSharedNodeCounts(reducible);
        assertTrue(previous.solved());
        assertTrue(shared.solved());
        assertEquals(previous.evidence().incumbent().expression(), shared.evidence().incumbent().expression());
        assertEquals(previous.evidence().witness(), shared.evidence().witness());
        var subtree = solver.solveWithSubtreeNodeCounts(reducible);
        assertTrue(subtree.solved());
        assertEquals(previous.evidence().witness(), subtree.evidence().witness());
        assertEquals(previous.evidence().incumbent().expression(), subtree.evidence().incumbent().expression());
        assertTrue(subtree.evidence().replayWork() > 0);
        assertTrue(shared.evidence().replayWork() > 0);
        assertEquals(0, shared.learnedApplications());

        var stuck = task("x+y", CheckedSchemaTaskSolver.Goal.atMostNodes(1));
        var original = solver.solve(stuck);
        var exhausted = solver.solveWithSharedNodeCounts(stuck);
        assertFalse(original.solved());
        assertFalse(exhausted.solved());
        // The ordinary typed primitive provider explicitly returns incomplete batches.
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, original.evidence().search().outcome());
        assertFalse(original.evidence().search().completeBoundedRelation());
        assertEquals(original.evidence().search().outcome(), exhausted.evidence().search().outcome());
        assertEquals(original.evidence().search().completeBoundedRelation(),
            exhausted.evidence().search().completeBoundedRelation());

        // An empty provider inventory really does enumerate its entire (empty) relation.
        var emptySession = new TypedLearnedMoveInventory.SearchSession(List.of(), List.of(),
            (state, move, context) -> { throw new AssertionError("empty inventory produced a move"); });
        var emptySolver = new CheckedSchemaTaskSolver(emptySession, null);
        for (var answer : List.of(emptySolver.solve(stuck), emptySolver.solveWithSharedNodeCounts(stuck),
                emptySolver.solveWithSubtreeNodeCounts(stuck))) {
            assertFalse(answer.solved());
            assertTrue(answer.evidence().search().completeBoundedRelation());
            assertEquals(MoveSearch.Outcome.BOUNDED_EXHAUSTED, answer.evidence().search().outcome());
        }
    }

    @Test void paysForNativeVisitsAndRepeatedLookupsByTreeIdentity() {
        var source = new ExpressionParser().parseExactTerm("x*(y+2)").expression();
        var root = new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0);
        var cache = new CostAwareTacticRouter.QueryNodeCount();
        var nativeCount = NativeNodeCountObjective.INSTANCE.evaluate(root);
        var first = cache.evaluate(root);
        assertEquals(5, nativeCount.value());
        assertEquals(nativeCount.value(), first.value());
        assertEquals(nativeCount.work() + 4, first.work());
        var later = new TypedMoveSearch.State(source, 1, 1, "", List.of(), Set.of(), 0);
        assertEquals(new TypedSourceOnlySearch.Score(first.value(), 1), cache.evaluate(later));
        var equivalent = new ExpressionParser().parseExactTerm("x*(y+2)").expression();
        var otherRoot = new TypedMoveSearch.State(equivalent, 0, 0, "", List.of(), Set.of(), 0);
        assertEquals(nativeCount.work() + 2, cache.evaluate(otherRoot).work());
    }

    @Test void preservesCustomObjectiveAndNativeResourceObservation() {
        var impossible = new CheckedSchemaTaskSolver.Goal(state -> new TypedSourceOnlySearch.Score(17, 2), 1);
        var custom = task("x+0", impossible);
        var result = solver.solveWithSharedNodeCounts(custom);
        var customSubtree = solver.solveWithSubtreeNodeCounts(custom);
        assertEquals(result.evidence().totalWork(), customSubtree.evidence().totalWork());
        assertEquals(17, customSubtree.evidence().outputScore());
        assertFalse(result.solved());
        assertEquals(17, result.evidence().outputScore());
        assertEquals(solver.solve(custom).evidence().totalWork(), result.evidence().totalWork());

        var nativeTask = task("(x+0)*1", CheckedSchemaTaskSolver.Goal.atMostNodes(1));
        var resources = new RetainedOperation.Sink() {
            @Override public void executionWork(long units) {}
            @Override public void validationWork(long units) {}
            @Override public void checkpoint() {}
            @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
        };
        try (var scope = RetainedOperation.open(resources)) {
            var normal = solver.solve(nativeTask).evidence();
            var observed = solver.solveWithSharedNodeCounts(nativeTask).evidence();
            var subtree = solver.solveWithSubtreeNodeCounts(nativeTask).evidence();
            assertEquals(normal.totalWork(), subtree.totalWork());
            assertEquals(normal.outputScore(), subtree.outputScore());
            assertEquals(normal.totalWork(), observed.totalWork());
            assertEquals(normal.outputScore(), observed.outputScore());
        }
    }

    private static CheckedSchemaTaskSolver.Task task(String expression, CheckedSchemaTaskSolver.Goal goal) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(expression).expression(),
            List.of(), goal, new MoveSearch.Budget(32, 32, 200_000, 512, 8192));
    }
}
