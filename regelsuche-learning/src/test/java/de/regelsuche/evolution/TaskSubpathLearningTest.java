package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.MoveVerifier;
import de.regelsuche.search.moves.TypedMoveSearch;
import java.util.ArrayList;
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
        var otherLessons = List.of(task("((z+0)*1)+(c+0)", 3), lessons.get(1));
        var other = initial.learnLocalBatch(otherLessons, validation, CumulativeTaskKnowledge.DEFAULT_LEARNING_WORK);
        var firstSupport = local.attempts().stream().filter(a -> a.status().equals("PROVED")).findFirst().orElseThrow();
        var otherSupport = other.attempts().stream().filter(a -> a.status().equals("PROVED")).findFirst().orElseThrow();
        assertEquals(firstSupport.detail(), otherSupport.detail()); // the same mathematical identity
        assertNotEquals(firstSupport.observationIds().getFirst(), otherSupport.observationIds().getFirst());
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

    @Test void refusesCyclesSingleStepsAndFailedChecksBeforeGeneralization() {
        var initial = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var solved = initial.solve(task("((x+0)*1)+(a+0)", 3), CumulativeTaskKnowledge.Strategy.BASELINE);
        var witness = solved.solution().orElseThrow().evidence().witness();
        assertEquals(3, witness.size());
        var first = witness.getFirst();
        var reversed = new TypedMoveSearch.WitnessStep(first.target(), first.source(), first.move(), first.verification());
        var bounds = CheckedLearnedSchemaModel.Bounds.defaults();
        var work = new CheckedSchemaSupport.Work();
        var ids = new ArrayList<String>();
        TaskSubpathMiner.Sink collect = (id, source, target) -> ids.add(id);
        assertTrue(TaskSubpathMiner.mine(List.of(first), bounds, 10, work, collect));
        // Deliberately present a repeated state to the extraction layer only.
        assertTrue(TaskSubpathMiner.mine(List.of(first, reversed, first), bounds, 10, work, collect));
        assertTrue(ids.isEmpty());

        var refused = new TypedMoveSearch.WitnessStep(first.source(), first.target(), first.move(),
            new MoveVerifier.Verification(false, 1, List.of(), "test refusal"));
        assertTrue(TaskSubpathMiner.mine(List.of(refused, witness.get(1), witness.get(2)), bounds, 10, work, collect));
        assertFalse(ids.contains("L[0,2,]"));
        ids.clear();
        assertFalse(TaskSubpathMiner.mine(witness, bounds, 0, work, collect));
        assertTrue(ids.isEmpty());
        assertTrue(work.units > 0);
    }

    @Test void keepsCompletedChildWorkBeforeARootRewrite() {
        var initial = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        var execution = initial.solve(task("(p-u)+((u+0)*1)", 1), CumulativeTaskKnowledge.Strategy.BASELINE);
        assertTrue(execution.solved());
        var witness = execution.solution().orElseThrow().evidence().witness();
        assertEquals(3, witness.size());
        var observations = new ArrayList<String>();
        TaskSubpathMiner.mine(witness, CheckedLearnedSchemaModel.Bounds.defaults(), 32, new CheckedSchemaSupport.Work(),
            (id, source, target) -> observations.add(id + ":" + de.regelsuche.parse.ExpressionFormatter.format(source)
                + " => " + de.regelsuche.parse.ExpressionFormatter.format(target)));
        assertTrue(observations.contains("R[0,1,]:(u + 0) * 1 => u"), observations::toString);
    }
}
