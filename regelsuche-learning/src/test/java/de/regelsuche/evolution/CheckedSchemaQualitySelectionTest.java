package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.inventory.TypedPolicySelection;
import de.regelsuche.inventory.TypedSourcePolicySelection;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Public development integration, not an independent or frozen performance comparison. */
class CheckedSchemaQualitySelectionTest {
    private static final TypedSourceOnlySearch.Objective OBJECTIVE = state ->
        TypedPolynomialSurfaceCost.evaluate(state.expression());
    private static final TypedMoveSearch.TypedPolicy SCHEMA_FIRST = new TypedMoveSearch.TypedPolicy() {
        @Override public double score(SearchMove move, MoveState state, MoveContext context) { return 0; }
        @Override public Stage stage(MoveProvider.Descriptor descriptor, MoveState state, MoveContext context) {
            return descriptor.sourceKind() == SearchMove.SourceKind.LEARNED ? Stage.VALUABLE_LEARNED
                : MovePriorityPolicy.INVENTORY_ORDER.stage(descriptor, state, context);
        }
    };

    @Test void selectorPricesActualLearnedRestoredRulesWithTheSameOnlineControl() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var inventory = new TypedLearnedMoveInventory(formation);
        var formed = CheckedLearnedSchemaModel.learn(formation);
        var model = CheckedLearnedSchemaModel.load(formed.toCanonicalJson(), formation.inventory().contentHash());
        var primitive = inventory.newSearchSession(false, 0, 0);
        var learned = inventory.newSchemaSearchSession(model, 4, Map.of());
        var profiles = List.of(new TypedSourcePolicySelection.Profile("BASE", primitive.providers(), TypedMoveSearch.Policy.INVENTORY_ORDER),
            new TypedSourcePolicySelection.Profile("LEARNED", learned.providers(), SCHEMA_FIRST));
        var tasks = List.of("(x^2+y)*(x^2-y)+y*y", "(3*x+y)*(3*x-y)+y*y").stream()
            .map(source -> new TypedPolicySelection.TrainingTask(source, problem(source, learned, MoveContext.Phase.TRAIN))).toList();
        var selected = TypedSourcePolicySelection.trainUntil(tasks, profiles, OBJECTIVE, 3,
            SearchContinuationContract.DECLARED_STATE_LOCAL);
        assertEquals("LEARNED", selected.selected().id());
        assertTrue(selected.trials().stream().flatMap(t -> t.observations().stream())
            .allMatch(o -> o.outcome() == MoveSearch.Outcome.QUALITY_REACHED && o.withinBudget()));
        assertEquals(selected.trials().getFirst().outputCost(), selected.trials().getLast().outputCost());
        assertTrue(selected.trials().getLast().totalWork() < selected.trials().getFirst().totalWork());
        var evaluation = selected.evaluate(problem("(7*z+y)*(7*z-y)+y*y", learned, MoveContext.Phase.FROZEN_EVALUATION), OBJECTIVE);
        assertTrue(evaluation.withinBudget());
        assertEquals(MoveSearch.Outcome.QUALITY_REACHED, evaluation.search().outcome());
        assertTrue(evaluation.outputScore() <= 3);
        assertTrue(evaluation.witness().stream().anyMatch(s -> s.move().sourceKind() == SearchMove.SourceKind.LEARNED));
        assertTrue(evaluation.witness().stream().allMatch(s -> s.verification().accepted()));
        assertTrue(evaluation.replayWork() > 0);
        long formationAndRestore = Math.addExact(inventory.formationWork(), Math.addExact(formed.formationWork(), model.loadWork()));
        long lifecycle = Math.addExact(formationAndRestore, Math.addExact(selected.trainingWork(), evaluation.totalWork()));
        assertTrue(lifecycle > evaluation.totalWork(), "formation, restore and all selection trials remain payable");
        System.out.println("CHECKED_SCHEMA_QUALITY_SELECTION selected=" + selected.selected().id()
            + " baseTrial=" + selected.trials().getFirst().totalWork() + " learnedTrial=" + selected.trials().getLast().totalWork()
            + " formationAndRestore=" + formationAndRestore + " selectionWork=" + selected.trainingWork()
            + " evaluationWork=" + evaluation.totalWork() + " paidLifecycle=" + lifecycle);
    }

    private static TypedMoveSearch.Problem problem(String source, TypedLearnedMoveInventory.SearchSession session,
            MoveContext.Phase phase) {
        return new TypedMoveSearch.Problem(new ExpressionParser().parseExactTerm(source).expression(),
            TypedMoveSearch.Context.sourceOnly(List.of(), phase), session.providers(), SCHEMA_FIRST,
            session.verifier(), state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(12, 12, 100000, 64, 100000, 32), (state, context) -> {
                var cost = OBJECTIVE.evaluate(state);
                return new StateValue.Assessment(Math.toIntExact(cost.value()), -cost.value(), cost.work(), 0, Map.of());
            });
    }
}
