package de.regelsuche.evolution;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import org.junit.jupiter.api.Test;

class StructuralExpansionLearningTest {
    @Test void learnsAndRestoresAnExpansionWithAValidatedBenefit() {
        var genome = TraceStrategyTransferExample.inventory();
        var initial = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_EXPANSION_V1);
        var goal = StructuralTaskObjective.EXPANDED_POLYNOMIAL;
        var lessons = List.of(task("(x+y)^2", goal), task("(p+q)^2", goal));
        var validation = List.of(task("((u+1)+v)^2", goal));
        var update = initial.learnBatch(lessons, validation, 50_000_000);
        assertTrue(update.promoted(), () -> "candidate did not pass validation: " + update.reason());
        assertTrue(update.work().total() > 0, "all lessons and validation are paid");
        System.out.println("EXPANSION_LEARNING " + update.reason() + " work=" + update.work() + " attempts=" + update.attempts());
        var restored = CumulativeTaskKnowledge.restore(genome, update.next().toCanonicalJson());
        assertEquals(TaskConstructionProfile.STRUCTURAL_EXPANSION_V1, restored.constructionProfile());
        assertEquals(update.next().id(), restored.id());
        var query = task("((a+2)+b)^2", goal);
        var before = initial.solve(query, CumulativeTaskKnowledge.Strategy.BASELINE);
        var after = restored.solve(query, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        assertFalse(before.solved(), "the same bounded baseline does not finish this development query");
        assertTrue(after.solved());
        assertTrue(after.solution().orElseThrow().learnedApplications() > 0);
        assertTrue(after.solution().orElseThrow().evidence().withinBudget());
        new ExactPolynomialAnalysis().requireEquivalent("((a+2)+b)^2", ExpressionFormatter.format(
            after.solution().orElseThrow().evidence().incumbent().expression()));
        System.out.println("EXPANSION_LEARNING_QUERY before=" + before.solved() + ":" + before.totalWork() + " after="
            + after.solved() + ":" + after.totalWork() + " uses=" + after.solution().map(s -> s.learnedApplications()).orElse(0L));
    }
    private static CheckedSchemaTaskSolver.Task task(String source, StructuralTaskObjective goal) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(), List.of(),
            goal.goal(), new MoveSearch.Budget(32, 8, 10_000_000, 512, 10_000_000));
    }
}
