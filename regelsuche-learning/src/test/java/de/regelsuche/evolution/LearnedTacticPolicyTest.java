package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.transform.TransformationProvenance;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LearnedTacticPolicyTest {
    @Test void learnsFromPairedFailuresAndReopensAProvedTacticAfterAPrimitiveStep() {
        var genome = TraceStrategyTransferExample.inventory();
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        var update = CumulativeTaskKnowledge.empty(genome).learnLocalBatch(PairedTacticTraining.lessons(),
            PairedTacticTraining.gates(),1_000_000);
        assertTrue(update.promoted());
        assertTrue(update.training().stream().allMatch(CumulativeTaskKnowledge.Execution::solved));
        var model = CheckedLearnedSchemaModel.load(TaskKnowledgeSnapshot.decode(update.next().toCanonicalJson()).model(),
            genome.contentHash());
        var fit = LearnedTacticPolicy.fit(genome,model,PairedTacticTraining.calibration());
        assertTrue(fit.trials().stream().anyMatch(t -> t.learnedSteps() > 0 && t.candidateSolved() && !t.baselineSolved()));
        assertTrue(fit.trials().stream().anyMatch(t -> t.candidateWork() > t.baselineWork() && !t.candidateSolved()));
        assertTrue(fit.work() > fit.trials().stream().mapToLong(LearnedTacticPolicy.Trial::candidateWork).sum());
        var solver = inventory.newTaskSolverWithTacticPolicy(model,fit.policy(),false,false);
        var source = task("((((r*((s-u)-v)+r*v)+r*u)+0)*1)",3,3);
        var full = solver.solve(source);
        assertTrue(full.solved());
        var steps = full.evidence().witness();
        assertEquals(List.of(SearchMove.SourceKind.PRIMITIVE,SearchMove.SourceKind.LEARNED,SearchMove.SourceKind.PRIMITIVE),
            steps.stream().map(s -> s.move().sourceKind()).toList());
        assertNotEquals(fit.policy().select(steps.get(0).source().expression(),"nodes",new CheckedSchemaSupport.Work()),
            fit.policy().select(steps.get(1).source().expression(),"nodes",new CheckedSchemaSupport.Work()));
        var evidence = (TransformationProvenance.ExactTheoryStep) steps.get(1).move().provenance();
        var dropped = inventory.newTaskSolverWithTacticPolicy(model,fit.policy(),false,Set.of(evidence.evidence().binding().theoryStepId()));
        assertEquals(solver.preparationWork(),dropped.preparationWork());
        assertFalse(dropped.solve(source).solved());
        assertFalse(inventory.newTaskSolver().solve(source).solved());

        var unreachable = task("x+(y*24)",1,1);
        var exhausted = solver.solve(unreachable).evidence();
        assertFalse(exhausted.search().completeBoundedRelation());
        assertFalse(exhausted.search().outcome() == MoveSearch.Outcome.BOUNDED_EXHAUSTED);
        assertTrue(exhausted.accountingComplete());
    }

    private static CheckedSchemaTaskSolver.Task task(String expression, int nodes, int depth) {
        return new CheckedSchemaTaskSolver.Task(new ExpressionParser().parseExactTerm(expression).expression(),List.of(),
            CheckedSchemaTaskSolver.Goal.atMostNodes(nodes),new MoveSearch.Budget(32,depth,200_000,512,8192));
    }
}
