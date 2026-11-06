package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.SearchMove;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CumulativeAlternativeSearchTest {
    private static CumulativeTaskKnowledge knowledge;

    @BeforeAll static void acquireAndValidateKnowledge() {
        var initial = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var update = initial.learnBatch(List.of(task(nested("a", "b") + "+3", 200_000),
            task("5*(" + nested("c", "d") + ")", 200_000)),
            List.of(task(nested("z", "w") + "+7", 200_000)));
        assertTrue(update.promoted());
        knowledge = update.next();
    }

    @Test void explorationUsesRealLearnedPathsWithoutChangingTheGeneration() {
        var task = task(nested("r", "t") + "+11", 200_000);
        var before = knowledge.toCanonicalJson();
        for (var strategy : List.of(CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST,
                CumulativeTaskKnowledge.Strategy.AUTOMATIC)) {
            var run = knowledge.explore(task, strategy, 40_000, 8);
            assertTrue(run.withinBudget());
            assertEquals(knowledge.id(), run.generationId());
            assertEquals(task, run.task());
            assertEquals(CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST, run.selectedStrategy());
            var evidence = run.evidence().orElseThrow();
            assertFalse(evidence.candidates().isEmpty());
            assertTrue(evidence.candidates().stream().flatMap(c -> c.witness().stream())
                .anyMatch(s -> s.move().sourceKind() == SearchMove.SourceKind.LEARNED));
            assertEquals(run.selectionWork() + evidence.totalWork(), run.totalWork());
            for (var candidate : evidence.candidates()) {
                assertFalse(candidate.witness().isEmpty());
                assertEquals(task.source(), candidate.witness().getFirst().source().expression());
                new ExactPolynomialAnalysis().requireEquivalent(ExpressionFormatter.format(task.source()),
                    ExpressionFormatter.format(candidate.state().expression()));
            }
        }
        assertEquals(before, knowledge.toCanonicalJson(), "exploration cannot promote or train knowledge");
    }

    @Test void routingIsPaidBeforeReservingReplayWork() {
        var task = task(nested("r", "t") + "+11", 200_000);
        long routing = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC).selectionWork();
        assertTrue(routing > 0);
        var unavailable = knowledge.explore(task(task.source(), routing + 2),
            CumulativeTaskKnowledge.Strategy.AUTOMATIC, 2, 8);
        assertFalse(unavailable.withinBudget());
        assertTrue(unavailable.evidence().isEmpty());
        assertEquals(routing, unavailable.totalWork());

        var tiny = knowledge.explore(task(task.source(), 3), CumulativeTaskKnowledge.Strategy.AUTOMATIC, 1, 8);
        assertFalse(tiny.withinBudget());
        assertTrue(tiny.evidence().isEmpty());
        assertEquals(routing, tiny.totalWork(), "routing overrun is not discarded");
    }

    @Test void restoredKnowledgeHasTheSameAlternativesAndQueryCosts() {
        var restored = CumulativeTaskKnowledge.restore(TraceStrategyTransferExample.inventory(), knowledge.toCanonicalJson());
        var task = task(nested("u", "v") + "+13", 200_000);
        var before = knowledge.explore(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC, 40_000, 8);
        var after = restored.explore(task, CumulativeTaskKnowledge.Strategy.AUTOMATIC, 40_000, 8);
        assertTrue(after.withinBudget());
        assertTrue(restored.sessionSetupWork() > 0);
        assertEquals(before.generationId(), after.generationId());
        assertEquals(before.totalWork(), after.totalWork());
        assertEquals(before.evidence().orElseThrow().candidates().stream().map(c -> c.witness()).toList(),
            after.evidence().orElseThrow().candidates().stream().map(c -> c.witness()).toList());
    }

    private static String nested(String source, String auxiliary) {
        return CumulativeTaskKnowledgeContract.nested(source, 1, auxiliary);
    }
    private static CheckedSchemaTaskSolver.Task task(String source, long work) {
        return task(new ExpressionParser().parseExactTerm(source).expression(), work);
    }
    private static CheckedSchemaTaskSolver.Task task(de.regelsuche.ast.Expr source, long work) {
        return new CheckedSchemaTaskSolver.Task(source, List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(5),
            new MoveSearch.Budget(24, 24, 200_000, 256, work));
    }
}
