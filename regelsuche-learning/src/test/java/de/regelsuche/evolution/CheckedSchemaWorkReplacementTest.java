package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Development integration diagnostics, not a frozen or independently held-out performance claim. */
class CheckedSchemaWorkReplacementTest {
    private static final TypedSourceOnlySearch.Objective OBJECTIVE = state ->
        TypedPolynomialSurfaceCost.evaluate(state.expression());
    private static final TypedMoveSearch.TypedPolicy SCHEMA_FIRST = new TypedMoveSearch.TypedPolicy() {
        @Override public double score(SearchMove move, MoveState state, MoveContext context) { return 0; }
        @Override public Stage stage(MoveProvider.Descriptor descriptor, MoveState state, MoveContext context) {
            return descriptor.sourceKind() == SearchMove.SourceKind.LEARNED ? Stage.VALUABLE_LEARNED
                : MovePriorityPolicy.INVENTORY_ORDER.stage(descriptor, state, context);
        }
    };

    @Test void actualLearnedAndRestoredSchemasUseTheSameQualityControlledFrontier() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var inventory = new TypedLearnedMoveInventory(formation);
        var formed = CheckedLearnedSchemaModel.learn(formation);
        var model = CheckedLearnedSchemaModel.load(formed.toCanonicalJson(), formation.inventory().contentHash());
        long learningWork = Math.addExact(inventory.formationWork(), Math.addExact(formed.formationWork(), model.loadWork()));
        long baseWork = 0, learnedWork = 0, continuedWork = 0;
        for (String source : List.of("(x+1+y)*(x+1-y)+y*y", "(x^2+y)*(x^2-y)+y*y", "(3*x+y)*(3*x-y)+y*y")) {
            var primitive = inventory.newSearchSession(false, 0, 0);
            var learned = inventory.newSchemaSearchSession(model, 4, Map.of());
            var base = new TypedSourceOnlySearch().searchUntil(problem(source, primitive, TypedMoveSearch.Policy.INVENTORY_ORDER),
                OBJECTIVE, 3, SearchContinuationContract.DECLARED_STATE_LOCAL);
            var controlled = new TypedSourceOnlySearch().searchUntil(problem(source, learned, SCHEMA_FIRST),
                OBJECTIVE, 3, SearchContinuationContract.DECLARED_STATE_LOCAL);
            var continued = new TypedSourceOnlySearch().search(problem(source, learned, SCHEMA_FIRST), OBJECTIVE);
            assertTrue(controlled.withinBudget());
            assertTrue(controlled.outputScore() <= 3);
            assertTrue(controlled.witness().stream().anyMatch(step -> step.move().sourceKind() == SearchMove.SourceKind.LEARNED));
            assertTrue(controlled.witness().stream().allMatch(step -> step.verification().accepted()));
            assertFalse(controlled.search().reached(), "source-only quality must not be reported as a target hit");
            assertEquals("QUALITY_REACHED", controlled.search().outcome().name());
            baseWork = Math.addExact(baseWork, base.totalWork());
            learnedWork = Math.addExact(learnedWork, controlled.totalWork());
            continuedWork = Math.addExact(continuedWork, continued.totalWork());
            System.out.println("WORK_REPLACEMENT_CASE source=" + source + " baseQuality=" + base.outputScore()
                + " learnedQuality=" + controlled.outputScore() + " continuedQuality=" + continued.outputScore()
                + " baseWork=" + base.totalWork() + " learnedWork=" + controlled.totalWork()
                + " continuedWork=" + continued.totalWork());
        }
        System.out.println("WORK_REPLACEMENT_TOTAL trainingAndRestore=" + learningWork + " baseQueries=" + baseWork
            + " learnedQueries=" + learnedWork + " continuedQueries=" + continuedWork
            + " learnedLifecycle=" + Math.addExact(learningWork, learnedWork));
        // Full lifecycle wins are deliberately not assumed from shorter query witnesses.
        assertTrue(learningWork > 0);
    }

    private static TypedMoveSearch.Problem problem(String source, TypedLearnedMoveInventory.SearchSession session,
            MovePriorityPolicy policy) {
        return new TypedMoveSearch.Problem(new ExpressionParser().parseExactTerm(source).expression(),
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            session.providers(), policy, session.verifier(), state -> 0, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(12, 12, 100000, 64, 100000, 32),
            (state, context) -> {
                var cost = OBJECTIVE.evaluate(state);
                return new StateValue.Assessment(Math.toIntExact(cost.value()), -cost.value(), cost.work(), 0, Map.of());
            });
    }
}
