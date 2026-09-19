package de.regelsuche.inventory;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class TypedHistoryMovePolicyTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final PatternExpr A = PatternExpr.var("A");
    private static final AstRewriteTransport TRANSPORT = new AstRewriteTransport(List.of(
        new PatternRewriteRule("zero", PatternExpr.op(ADD, A, PatternExpr.num(0)), A)), 100, 100);
    private static final MoveProvider.Descriptor DESCRIPTOR = new MoveProvider.Descriptor("zero", "zero-family",
        SearchMove.SourceKind.PRIMITIVE, SearchMove.ProofStrength.REPLAYABLE, List.of(),
        SearchMove.ValueEvidence.UNKNOWN, "zero-v1");

    @Test void collectsTypedTrainEventsAndUsesFrozenHistoryWithoutReparsing(@TempDir Path directory) throws Exception {
        Expr goal = new FunctionExpr("f", List.of(new BinaryExpr(VariableExpr.scoped(new SymbolId(new UUID(0, 51), 4)),
            ADD, new BinaryExpr(NumberExpr.exact("1/3"), ADD, new VariableExpr("c")))));
        Expr source = new BinaryExpr(goal, ADD, new NumberExpr(0));
        var result = run(source, goal, MovePriorityPolicy.INVENTORY_ORDER, 10_000);
        assertTrue(result.reached());
        assertEquals(source, result.events().getFirst().source().expression());
        assertEquals(goal, result.events().getFirst().target().expression());

        var memory = new RuleHistoryMemory();
        memory.observe(result, MoveContext.Phase.TRAIN, Map.of("zero", 5L));
        assertTrue(memory.measuredWork() > 2);
        var file = directory.resolve("typed-history.json");
        memory.freeze().persistTo(file);
        var frozen = RuleHistoryMemory.Snapshot.load(file);
        assertEquals(memory.freeze(), frozen);
        var typed = HistoryMovePolicy.typed(frozen, HistoryMovePolicy.Weights.DEFAULT);
        var empty = HistoryMovePolicy.typed(new RuleHistoryMemory().freeze(), HistoryMovePolicy.Weights.DEFAULT);
        var event = result.encodedResult().events().getFirst();
        var context = MoveContext.frozen(CODEC.encodeExpression(goal));
        assertTrue(typed.score(event.move(), event.source(), context) > empty.score(event.move(), event.source(), context));
        assertTrue(run(source, goal, typed, 10_000).reached());
        assertEquals(frozen, memory.freeze());
        assertThrows(IllegalArgumentException.class, () -> memory.observe(result, MoveContext.Phase.FROZEN_EVALUATION, Map.of()));
        assertEquals(frozen, memory.freeze());
    }

    @Test void typedContextPreservesRepeatedGroupingScopedVariablesAndRationalLeaves() {
        Expr x = VariableExpr.scoped(new SymbolId(new UUID(0, 52), 1));
        Expr y = VariableExpr.scoped(new SymbolId(new UUID(0, 52), 2));
        Expr group = new BinaryExpr(x, ADD, new BinaryExpr(y, ADD, NumberExpr.exact("1/3")));
        Expr expression = new BinaryExpr(group, MUL, group);
        var state = new MoveState(CODEC.encodeExpression(expression), 0, 0, "", List.of(), Set.of(), 0);
        var context = StructuralMoveContext.fromTyped(state);
        assertEquals("MUL", context.rootOperator());
        assertEquals(2, context.variables());
        assertEquals(2, context.degree());
        assertEquals(11, context.visitedNodes());
        assertEquals(5, context.repeatedSubtrees());
        assertNotEquals(context.key(), context.typedKey());
    }

    @Test void typedPolicyFeatureWorkSharesTheSearchBudgetAndLegacyPoliciesStayRejected() {
        Expr goal = NumberExpr.exact("1/3");
        Expr source = new BinaryExpr(goal, ADD, new NumberExpr(0));
        var frozen = new RuleHistoryMemory().freeze();
        var typed = HistoryMovePolicy.typed(frozen, HistoryMovePolicy.Weights.DEFAULT);
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, run(source, goal, typed, 2).outcome());
        assertThrows(IllegalArgumentException.class,
            () -> run(source, goal, new HistoryMovePolicy(frozen, HistoryMovePolicy.Weights.DEFAULT), 1000));
        assertTrue(run(source, goal, typed, 1000).metrics().searchWork()
            > run(source, goal, MovePriorityPolicy.INVENTORY_ORDER, 1000).metrics().searchWork());
    }

    private static TypedMoveSearch.Result run(Expr source, Expr goal, MovePriorityPolicy policy, long work) {
        return new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,
            new TypedMoveSearch.Context(goal, List.of(), MoveContext.Phase.TRAIN),
            List.of(TypedMoveSearch.primitiveProvider(DESCRIPTOR, TRANSPORT)), policy,
            TypedMoveSearch.primitiveReplay(TRANSPORT), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(3, 3, 0, 20, work)));
    }
}
