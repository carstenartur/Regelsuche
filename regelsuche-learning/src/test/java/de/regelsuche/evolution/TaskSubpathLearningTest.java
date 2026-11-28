package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskSubpathLearningTest {
    private static final MoveSearch.Budget BUDGET = new MoveSearch.Budget(16, 16, 200_000, 256, 200_000);

    private static CheckedSchemaTaskSolver.Task task(String source, int nodes) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(),
            List.of(), CheckedSchemaTaskSolver.Goal.atMostNodes(nodes), BUDGET);
    }

    @Test void differentFullSolutionsCanTeachTheSameCheckedSubpath() {
        var inventory = TraceStrategyTransferExample.inventory();
        var initial = CumulativeTaskKnowledge.empty(inventory);
        var lessons = List.of(task("((x+0)*1)+(a+0)", 3), task("((y+0)*1)-(b+0)", 3));
        var query = task("((3*c+0)*1)", 3);
        var validation = List.of(new CheckedSchemaTaskSolver.Task(query.source(), query.assumptions(), query.goal(),
            new MoveSearch.Budget(16, 1, 200_000, 256, 200_000)));
        var endpoints = initial.learnBatch(lessons, validation);
        var local = initial.learnLocalBatch(lessons, validation, CumulativeTaskKnowledge.DEFAULT_LEARNING_WORK);
        assertEquals(0, endpoints.next().schemaCount());
        assertTrue(local.promoted(), () -> local.reason() + ": " + local.attempts());
        assertTrue(local.work().formation() > endpoints.work().formation());
        var restored = CumulativeTaskKnowledge.restore(inventory, local.next().toCanonicalJson());
        var held = task("7+(((d*e+0)*1))", 5);
        var learned = restored.solve(held, CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST);
        assertTrue(learned.solved());
        var solution = learned.solution().orElseThrow();
        assertTrue(solution.learnedApplications() > 0);
        assertTrue(solution.evidence().replayWork() > 0);
        assertFalse(restored.solve(task("((d*e+2)*3)", 3),
            CumulativeTaskKnowledge.Strategy.KNOWLEDGE_FIRST).solved());
        assertEquals(0, initial.schemaCount());
        var repeated = List.of(lessons.getFirst(), task("((u+0)*1)+(v+0)", 3));
        assertThrows(IllegalArgumentException.class,
            () -> initial.learnLocalBatch(repeated, validation, CumulativeTaskKnowledge.DEFAULT_LEARNING_WORK));
    }
}
