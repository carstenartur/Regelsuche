package de.regelsuche.evolution;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.transform.TransformationProvenance;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalTacticSearchTest {
    @Test void appliesALearnedRuleBelowTheRootAndAblatesAfterAdmission() {
        var genome = TraceStrategyTransferExample.inventory();
        var formation = new TraceRewriteStrategyLearner().learn(genome, TraceStrategyTransferExample.trainingInputs(),
            TraceStrategyTransferExample.limits());
        var model = CheckedLearnedSchemaModel.learn(formation);
        var search = LocalTacticSearch.prepare(genome, model);
        var query = task("7*((x+y)*(x-y)+y*y)");
        var found = search.solve(query, Set.of());
        assertTrue(search.preparationWork() > 0);
        assertTrue(found.admitted());
        assertTrue(found.solved());
        assertEquals(1, found.evidence().witness().size());
        var step = found.evidence().witness().getFirst().move();
        assertEquals(SearchMove.SourceKind.LEARNED, step.sourceKind());
        assertTrue(found.evidence().replayWork() > 0);
        assertEquals(query.budget().totalWork(), found.selectionWork() + found.evidence().workBudget());

        var id = ((TransformationProvenance.ExactTheoryStep) step.provenance()).evidence().binding().theoryStepId();
        var all = model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id).collect(Collectors.toSet());
        for (var disabled : List.of(Set.of(id), all)) {
            var drop = search.solve(query, disabled);
            assertEquals(found.selectionWork(), drop.selectionWork());
            assertTrue(drop.admitted());
            assertFalse(drop.solved());
        }
        assertFalse(search.solve(task("7*((x+y)*(x-y)+z*z)"), Set.of()).solved());
        var oneStep = new CheckedSchemaTaskSolver.Task(query.source(), List.of(), query.goal(),
            new MoveSearch.Budget(8, 1, 100_000, 32, 8192));
        var afterPilot = search.solve(oneStep, Set.of());
        assertTrue(afterPilot.admitted());
        assertTrue(afterPilot.solved());
        assertTrue(afterPilot.selectionWork() > found.selectionWork());
        assertEquals(SearchMove.SourceKind.LEARNED, afterPilot.evidence().witness().getFirst().move().sourceKind());
        assertEquals(oneStep.budget().totalWork(), afterPilot.selectionWork() + afterPilot.evidence().workBudget());

        var restricted = LocalTacticSearch.prepare(genome, model.requiring(List.of("t>0")));
        assertFalse(restricted.solve(query, Set.of()).admitted());

        var withAssumption = new CheckedSchemaTaskSolver.Task(query.source(), List.of("t > 0"), query.goal(), query.budget());
        assertTrue(restricted.solve(withAssumption, Set.of()).solved());

        var tiny = new CheckedSchemaTaskSolver.Task(query.source(), List.of(), query.goal(),
            new MoveSearch.Budget(0, 1, 100_000, 32, 1));
        var exhausted = search.solve(tiny, Set.of());
        assertFalse(exhausted.withinBudget());
        assertFalse(exhausted.solved());
        assertTrue(exhausted.totalWork() > exhausted.originalBudget());

        try (var scope = RetainedOperation.open(new RetainedOperation.Sink() {
            @Override public void executionWork(long units) {}
            @Override public void validationWork(long units) {}
            @Override public void checkpoint() {}
            @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
        })) {
            var observed = search.solve(query, Set.of());
            assertFalse(observed.admitted());
            assertFalse(observed.solved());
        }
    }

    private static CheckedSchemaTaskSolver.Task task(String text) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(text).expression(), List.of(),
            CheckedSchemaTaskSolver.Goal.atMostNodes(5), new MoveSearch.Budget(0, 1, 100_000, 32, 8192));
    }
}
