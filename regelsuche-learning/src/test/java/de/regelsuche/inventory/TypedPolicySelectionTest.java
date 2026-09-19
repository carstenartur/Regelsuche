package de.regelsuche.inventory;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypedPolicySelectionTest {
    private static final HistoryMovePolicy.Weights ZERO = new HistoryMovePolicy.Weights(0, 0, 0, 0, 0, 0, 0, 0);
    private static final HistoryMovePolicy.Weights GOAL = new HistoryMovePolicy.Weights(0, 0, 0, 20, 0, 0, 0, 0);

    @Test void selectsWeightsFromActualMatchedTrainWorkAndFreezesEveryTrial() {
        var tasks = List.of(task("train-a", new VariableExpr("a"), MoveContext.Phase.TRAIN),
            task("train-b", NumberExpr.exact("2/3"), MoveContext.Phase.TRAIN));
        var profiles = List.of(new TypedPolicySelection.Profile("zero", ZERO), new TypedPolicySelection.Profile("goal", GOAL));
        var history = new RuleHistoryMemory().freeze();
        var model = new TypedPolicySelection().train(history, tasks, profiles);
        assertEquals("goal", model.selected().id());
        assertEquals(2, model.trials().size());
        assertEquals(2, model.trials().getFirst().solved());
        assertEquals(2, model.trials().getLast().solved());
        assertTrue(model.trials().getLast().totalWork() < model.trials().getFirst().totalWork());
        assertEquals(model.trials().stream().mapToLong(TypedPolicySelection.Trial::totalWork).sum(), model.trainingWork());
        String before = model.toCanonicalJson();
        var evaluation = task("development-transfer", new FunctionExpr("f", List.of(new VariableExpr("x"))),
            MoveContext.Phase.FROZEN_EVALUATION).problem();
        assertTrue(model.evaluate(evaluation).reached());
        assertEquals(before, model.toCanonicalJson());
        assertEquals(history, model.history());
    }

    @Test void declaredOrderBreaksTiesAndRepeatedTrainingIsDeterministic() {
        var tasks = List.of(task("train-a", new VariableExpr("a"), MoveContext.Phase.TRAIN),
            task("train-b", new VariableExpr("b"), MoveContext.Phase.TRAIN));
        var profiles = List.of(new TypedPolicySelection.Profile("first", GOAL), new TypedPolicySelection.Profile("second", GOAL));
        var selector = new TypedPolicySelection();
        var model = selector.train(new RuleHistoryMemory().freeze(), tasks, profiles);
        assertEquals("first", model.selected().id());
        assertEquals(model.toCanonicalJson(), selector.train(new RuleHistoryMemory().freeze(), tasks, profiles).toCanonicalJson());
    }

    @Test void refusesEvaluationLeakageDuplicateInputsAndOversizedTraining() {
        var selector = new TypedPolicySelection();
        var history = new RuleHistoryMemory().freeze();
        var profiles = List.of(new TypedPolicySelection.Profile("goal", GOAL));
        var train = task("train-a", new VariableExpr("a"), MoveContext.Phase.TRAIN);
        var other = task("train-b", new VariableExpr("b"), MoveContext.Phase.TRAIN);
        assertThrows(IllegalArgumentException.class, () -> selector.train(history, List.of(train,
            task("test", new VariableExpr("b"), MoveContext.Phase.FROZEN_EVALUATION)), profiles));
        assertThrows(IllegalArgumentException.class, () -> selector.train(history, List.of(train, train), profiles));
        assertThrows(IllegalArgumentException.class, () -> selector.train(history,
            List.of(train, task("duplicate-source", new VariableExpr("a"), MoveContext.Phase.TRAIN)), profiles));
        assertThrows(IllegalArgumentException.class, () -> selector.train(history, List.of(train, other),
            List.of(profiles.getFirst(), profiles.getFirst())));
        var model = selector.train(history, List.of(train, other), profiles);
        assertThrows(IllegalArgumentException.class, () -> model.evaluate(train.problem()));
        assertThrows(IllegalArgumentException.class, () -> model.evaluate(
            task("leaked", new VariableExpr("a"), MoveContext.Phase.FROZEN_EVALUATION).problem()));
        assertThrows(IllegalArgumentException.class, () -> selector.train(history,
            java.util.stream.IntStream.range(0, 33).mapToObj(i -> task("many-" + i,
                new VariableExpr("x" + i), MoveContext.Phase.TRAIN)).toList(), profiles));
    }

    private static TypedPolicySelection.TrainingTask task(String id, Expr goal, MoveContext.Phase phase) {
        var a = PatternExpr.var("A");
        var transport = new AstRewriteTransport(List.of(
            new PatternRewriteRule("a-wrap", PatternExpr.op(ADD, a, PatternExpr.num(0)),
                PatternExpr.op(MUL, a, PatternExpr.num(1))),
            new PatternRewriteRule("z-zero", PatternExpr.op(ADD, a, PatternExpr.num(0)), a)), 100, 100);
        var descriptor = new MoveProvider.Descriptor("primitives", "*", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixture-v1");
        var source = new BinaryExpr(goal, ADD, new NumberExpr(0));
        return new TypedPolicySelection.TrainingTask(id, new TypedMoveSearch.Problem(source,
            new TypedMoveSearch.Context(goal, List.of(), phase),
            List.of(TypedMoveSearch.primitiveProvider(descriptor, transport)), MovePriorityPolicy.INVENTORY_ORDER,
            TypedMoveSearch.primitiveReplay(transport), state -> 0, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(3, 3, 0, 100, 10_000)));
    }
}
