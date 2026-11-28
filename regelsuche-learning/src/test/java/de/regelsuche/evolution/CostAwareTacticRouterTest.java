package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.transform.TransformationProvenance;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CostAwareTacticRouterTest {
    @Test void paysForBothCalibrationArmsAndKeepsNegativeAndUnseenShapesOnThePrimitiveLane() {
        var genome = TraceStrategyTransferExample.inventory();
        var update = CumulativeTaskKnowledge.empty(genome).learnLocalBatch(PairedTacticTraining.lessons(),
            PairedTacticTraining.gates(), 1_000_000);
        var model = CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(update.next().toCanonicalJson()).model(),
            genome.contentHash());
        var first = cancellation(21);
        var second = cancellation(22);
        var ordinary = task("x*(y+5)", 1, 2, 8192);
        var fit = CostAwareTacticRouter.fit(genome,model,List.of(first,second,ordinary));
        assertEquals(3,fit.trials().size());
        assertTrue(fit.work() > fit.trials().stream().mapToLong(t -> t.primitiveWork() + t.learnedWork()).sum());
        assertTrue(fit.trials().getFirst().learnedSteps() > 0);
        assertNotEquals(fit.trials().getFirst().learnedSolved(),fit.trials().getFirst().primitiveSolved());
        assertEquals(0, CostAwareTacticRouter.fit(genome,model,List.of(first,first)).admission().acceptedShapes());
        var router = CostAwareTacticRouter.prepare(genome,model,fit.admission(),Set.of());
        var held = cancellation(23);
        var found = router.solve(held);
        assertTrue(found.admitted());
        assertTrue(found.solved());
        assertTrue(found.evidence().replayWork() > 0);
        assertEquals(held.budget().totalWork(),found.evidence().workBudget() + found.selectionWork());

        var sparse = CostAwareTacticRouter.fitSparse(genome,model,List.of(first,second,ordinary));
        assertEquals(fit.trials(),sparse.trials());
        assertTrue(sparse.admission().sparse());
        assertFalse(sparse.admission().selectedSchemas().isEmpty());
        assertTrue(sparse.admission().selectedSchemas().size() < model.schemas().size());
        var allowed = Set.copyOf(sparse.admission().selectedSchemas());
        assertEquals(allowed,sparse.sparseTrials().stream().filter(CostAwareTacticRouter.SparseTrial::compressedOrCheaper)
            .flatMap(row -> row.pilotSchemas().stream()).collect(Collectors.toSet()));
        assertEquals(CostAwareTacticRouter.Refinement.MEASURED,sparse.sparseTrials().getFirst().status());
        assertEquals(CostAwareTacticRouter.Refinement.OUTSIDE_SUPPORT,sparse.sparseTrials().getLast().status());
        assertTrue(sparse.sparseTrials().stream().allMatch(row -> allowed.containsAll(row.usedSchemas())));
        long pairedWork = sparse.trials().stream().mapToLong(row -> row.primitiveWork() + row.learnedWork()).sum();
        assertTrue(sparse.work() > pairedWork + sparse.sparseTrials().stream().mapToLong(CostAwareTacticRouter.SparseTrial::work).sum());
        var sparseSolver = CostAwareTacticRouter.prepare(genome,model,sparse.admission(),Set.of());
        var sparseFound = sparseSolver.solve(held);
        assertTrue(sparseFound.solved());
        var sparseDrop = CostAwareTacticRouter.prepare(genome,model,sparse.admission(),allowed);
        assertEquals(sparseSolver.preparationWork(),sparseDrop.preparationWork());
        assertFalse(sparseDrop.solve(held).solved());

        var ids = model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id).collect(Collectors.toSet());
        var dropped = CostAwareTacticRouter.prepare(genome,model,fit.admission(),ids);
        var failed = dropped.solve(held);
        assertTrue(failed.admitted());
        assertEquals(found.selectionWork(),failed.selectionWork());
        assertEquals(router.preparationWork(),dropped.preparationWork());
        assertFalse(failed.solved());
        assertTrue(failed.evidence().witness().stream().noneMatch(s -> s.move().sourceKind() == SearchMove.SourceKind.LEARNED));

        var used = found.evidence().witness().stream().filter(s -> s.move().sourceKind() == SearchMove.SourceKind.LEARNED)
            .map(s -> ((TransformationProvenance.ExactTheoryStep) s.move().provenance()).evidence().binding().theoryStepId())
            .findFirst().orElseThrow();
        var single = CostAwareTacticRouter.prepare(genome,model,fit.admission(),Set.of(used)).solve(held);
        assertTrue(single.admitted());
        assertTrue(single.evidence().witness().stream().filter(s -> s.move().sourceKind() == SearchMove.SourceKind.LEARNED)
            .map(s -> ((TransformationProvenance.ExactTheoryStep) s.move().provenance()).evidence().binding().theoryStepId())
            .noneMatch(used::equals));
        assertThrows(IllegalArgumentException.class, () -> CostAwareTacticRouter.prepare(genome,model,fit.admission(),Set.of("unknown")));
        var exhausted = router.solve(ordinary);
        assertFalse(exhausted.admitted());
        assertFalse(exhausted.evidence().search().completeBoundedRelation());
        assertTrue(exhausted.selectionWork() > 0);
        assertFalse(router.solve(task("x", 1, 2, 1)).withinBudget());
    }

    private static CheckedSchemaTaskSolver.Task cancellation(int n) {
        String x = "(a+" + n + ")";
        return task(x + "*((b-c)-d)+" + x + "*d+" + x + "*c", 5, 2, 8192);
    }

    private static CheckedSchemaTaskSolver.Task task(String expr, int nodes, int depth, long units) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(expr).expression(), List.of(),
            CheckedSchemaTaskSolver.Goal.atMostNodes(nodes), new MoveSearch.Budget(32,depth,200_000,512,units));
    }
}
