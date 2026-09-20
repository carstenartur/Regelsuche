package de.regelsuche.inventory;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypedSourcePolicySelectionTest {
    private static final AstRewriteTransport ZERO = new AstRewriteTransport(List.of(new PatternRewriteRule("zero",
        PatternExpr.op(ADD, PatternExpr.var("A"), PatternExpr.num(0)), PatternExpr.var("A"))), 16, 32);
    private static final MoveProvider PROVIDER = TypedMoveSearch.primitiveProvider(new MoveProvider.Descriptor("zero", "zero",
        SearchMove.SourceKind.PRIMITIVE, SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "v1"), ZERO);
    private static final TypedSourceOnlySearch.Objective OBJECTIVE = state ->
        new TypedSourceOnlySearch.Score(state.expression() instanceof BinaryExpr ? 1 : 0, 1);

    @Test void selectsReachableQualityWithoutAnyGoalExpression() {
        var model = new TypedPolicySelection().trainSourceOnly(tasks(true), List.of(profile("empty", List.of()),
            profile("reduce", List.of(PROVIDER))), OBJECTIVE);
        assertEquals("reduce", model.selected().id());
        assertEquals(2, model.trials().size());
        assertEquals(0, model.trials().getLast().observations().getFirst().outputScore());
        assertTrue(model.trials().stream().flatMap(t -> t.observations().stream())
            .noneMatch(o -> o.outcome() == MoveSearch.Outcome.TARGET_REACHED));
        assertTrue(model.trainingWork() > 0);
    }

    @Test void unsuccessfulMatchingAndFullContinuationCostsCanRejectKnowledge() {
        var model = new TypedPolicySelection().trainSourceOnly(tasks(false), List.of(profile("costly", List.of(PROVIDER)),
            profile("plain", List.of())), OBJECTIVE);
        assertEquals("plain", model.selected().id());
        assertTrue(model.trials().getFirst().totalWork() > model.trials().getLast().totalWork());
    }

    @Test void budgetViolationsDisqualifyAnExpensiveProfileWithoutHidingWork() {
        var bounded = tasks(true).stream().map(task -> {
            var p = task.problem();
            return new TypedPolicySelection.TrainingTask(task.id(), new TypedMoveSearch.Problem(p.source(), p.context(), p.providers(),
                p.policy(), p.verifier(), p.stateScore(), p.mode(), p.scheduling(), new MoveSearch.Budget(4, 4, 100, 16, 5)));
        }).toList();
        var model = new TypedPolicySelection().trainSourceOnly(bounded,
            List.of(profile("costly", List.of(PROVIDER)), profile("plain", List.of())), OBJECTIVE);
        assertEquals("plain", model.selected().id());
        assertTrue(model.trials().getFirst().violations() > 0);
        assertTrue(model.trials().getFirst().observations().stream().anyMatch(o -> o.totalWork() > 5));
    }

    @Test void fittedPolicyCannotReuseTrainSourcesOrReplaceCompleteReferenceMode() {
        var model = new TypedPolicySelection().trainSourceOnly(tasks(true), List.of(profile("plain", List.of())), OBJECTIVE);
        var p = tasks(true).getFirst().problem();
        var frozen = new TypedMoveSearch.Problem(p.source(), TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            p.providers(), p.policy(), p.verifier(), p.stateScore(), p.mode(), p.scheduling(), p.budget());
        assertThrows(IllegalArgumentException.class, () -> model.evaluate(frozen, OBJECTIVE));
        var complete = new TypedMoveSearch.Problem(new VariableExpr("newSource"), frozen.context(), p.providers(), p.policy(), p.verifier(),
            p.stateScore(), MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE, p.scheduling(), p.budget());
        assertThrows(IllegalArgumentException.class, () -> model.evaluate(complete, OBJECTIVE));
        var trainComplete = new TypedPolicySelection.TrainingTask("complete", new TypedMoveSearch.Problem(p.source(), p.context(),
            p.providers(), p.policy(), p.verifier(), p.stateScore(), MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE, p.scheduling(), p.budget()));
        assertThrows(IllegalArgumentException.class, () -> new TypedPolicySelection().trainSourceOnly(
            List.of(trainComplete, tasks(true).getLast()), List.of(profile("plain", List.of())), OBJECTIVE));
    }

    @Test void paidContinuationCanSelectABridgeWhileZeroDebtDisallowsItsExpansion() {
        var x = PatternExpr.var("X");
        var initial = PatternExpr.op(BinaryOperator.MUL, PatternExpr.op(ADD, x, PatternExpr.num(0)), PatternExpr.num(1));
        var larger = PatternExpr.op(ADD, initial, PatternExpr.num(0));
        var transport = new AstRewriteTransport(List.of(new PatternRewriteRule("bridge", initial, larger),
            new PatternRewriteRule("finish", larger, x)), 16, 16);
        var provider = TypedMoveSearch.primitiveProvider(new MoveProvider.Descriptor("bridge-path", "bridge-path",
            SearchMove.SourceKind.PRIMITIVE, SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "v1"), transport);
        TypedSourceOnlySearch.Objective objective = state -> new TypedSourceOnlySearch.Score(arithmeticCost(state.expression()), 1);
        for (int debt : List.of(0, 1)) {
            var tasks = new java.util.ArrayList<TypedPolicySelection.TrainingTask>();
            for (String name : List.of("a", "b")) tasks.add(new TypedPolicySelection.TrainingTask(name,
                new TypedMoveSearch.Problem(initial.instantiate(java.util.Map.of("X", new VariableExpr(name))),
                    TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.TRAIN), List.of(), MovePriorityPolicy.INVENTORY_ORDER,
                    TypedMoveSearch.primitiveReplay(transport), state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
                    new MoveSearch.Budget(3, 3, 100, 8, 2000, debt), (state, context) ->
                        new StateValue.Assessment(arithmeticCost(state.expression()), -arithmeticCost(state.expression()), 1, 0, java.util.Map.of()))));
            var model = new TypedPolicySelection().trainSourceOnly(tasks,
                List.of(profile("plain", List.of()), profile("bridge", List.of(provider))), objective);
            assertEquals(debt == 0 ? "plain" : "bridge", model.selected().id());
            assertEquals(debt == 0 ? 2 : 0, model.trials().getLast().observations().getFirst().outputScore());
        }
    }

    private static int arithmeticCost(Expr expression) {
        return expression instanceof BinaryExpr binary ? 1 + arithmeticCost(binary.left()) + arithmeticCost(binary.right()) : 0;
    }

    private static TypedSourcePolicySelection.Profile profile(String id, List<MoveProvider> providers) {
        return new TypedSourcePolicySelection.Profile(id, providers, MovePriorityPolicy.INVENTORY_ORDER);
    }
    private static List<TypedPolicySelection.TrainingTask> tasks(boolean reducible) {
        return List.of(task("a", reducible), task("b", reducible));
    }
    private static TypedPolicySelection.TrainingTask task(String id, boolean reducible) {
        Expr source = new VariableExpr(id);
        if (reducible) source = new BinaryExpr(source, ADD, new NumberExpr(0));
        return new TypedPolicySelection.TrainingTask(id, new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.TRAIN), List.of(), MovePriorityPolicy.INVENTORY_ORDER,
            TypedMoveSearch.primitiveReplay(ZERO), state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(4, 4, 100, 16, 1000)));
    }
}
