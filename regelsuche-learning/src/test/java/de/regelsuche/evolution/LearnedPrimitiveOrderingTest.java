package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.SearchMove;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LearnedPrimitiveOrderingTest {
    private static CheckedSchemaTaskSolver.Task task(String source, int target) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(source).expression(), List.of(),
            CheckedSchemaTaskSolver.Goal.atMostNodes(target), new MoveSearch.Budget(32,32,200_000,512,8192));
    }

    @Test void trainedProposalsStillReceiveNativeVerificationInAnUnseenContext() {
        var genome = TraceStrategyTransferExample.inventory();
        var fit = LearnedPrimitiveOrdering.fit(genome, List.of(task("((a*(b-c)+a*c)+0)",3),
            task("((p*(q-s)+p*s)+0)",3)));
        assertEquals(2, fit.lessons().size());
        assertTrue(fit.lessons().stream().allMatch(LearnedPrimitiveOrdering.Lesson::solved));
        assertTrue(fit.acquisitionWork() > 0 && fit.formationWork() > 0);
        var ids = TypedLearnedMoveInventory.primitives(genome).primitiveProviders().stream()
            .map(p -> p.descriptor().id()).toList();
        var first = fit.lessons().getFirst().rules();
        assertEquals(first, fit.lessons().getLast().rules());
        int initial = fit.model().orders().get(LearnedPrimitiveOrdering.INITIAL).getFirst();
        assertEquals(first.getFirst(), ids.get(initial));
        assertEquals(first.get(1), ids.get(fit.model().orders().get(initial).getFirst()));
        var trained = LearnedPrimitiveOrdering.load(genome, fit.model());
        assertTrue(trained.loadWork() > 0);
        var result = trained.solve(task("(t+17)*(r*(u-v)+r*v)",7));
        assertEquals(MoveSearch.Outcome.QUALITY_REACHED, result.search().outcome());
        assertTrue(result.withinBudget() && result.outputScore() <= 7);
        assertTrue(result.witness().size() >= 2);
        assertTrue(result.witness().stream().allMatch(step ->
            step.move().sourceKind() == SearchMove.SourceKind.PRIMITIVE && step.verification().accepted()));
        assertEquals(result.witness().stream().mapToLong(s -> s.verification().work()).sum(),result.replayWork());
        assertEquals(0, LearnedPrimitiveOrdering.untrained(genome).loadWork());
    }

    @Test void rejectsAnIncompatibleInventoryOrAMalformedPermutation() {
        var genome = TraceStrategyTransferExample.inventory();
        assertThrows(IllegalArgumentException.class, () -> LearnedPrimitiveOrdering.load(genome,
            new LearnedPrimitiveOrdering.Model("different", Map.of())));
        int size = genome.rewrites().size();
        assertThrows(IllegalArgumentException.class, () -> LearnedPrimitiveOrdering.load(genome,
            new LearnedPrimitiveOrdering.Model(genome.contentHash(), Map.of(-1, java.util.Collections.nCopies(size,0)))));
    }
}
