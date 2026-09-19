package de.regelsuche.evolution;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.inventory.HistoryMovePolicy;
import de.regelsuche.inventory.RuleHistoryMemory;
import de.regelsuche.search.moves.*;
import de.regelsuche.symbol.SymbolId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedLearnedMoveInventoryTest {
    @Test void actualLearnedTracesDispatchOverTypedStatesAndFeedBackIntoHistory() {
        var formation = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        String frozen = formation.toCanonicalJson();
        var inventory = formation.typedMoves();
        assertFalse(inventory.learnedPrograms().isEmpty());
        assertTrue(inventory.formationWork() > 0);

        Expr base = new FunctionExpr("f", List.of(VariableExpr.scoped(new SymbolId(new UUID(0, 88), 4)),
            NumberExpr.exact("1/3"), add(new VariableExpr("a"), add(new VariableExpr("b"), new VariableExpr("c")))));
        Expr v = VariableExpr.scoped(new SymbolId(new UUID(0, 88), 5));
        Expr source = add(mul(add(base, v), new BinaryExpr(base, SUB, v)), mul(v, v));
        Expr goal = new BinaryExpr(base, POW, new NumberExpr(2));

        var learned = run(inventory, inventory.providers(), source, goal, MovePriorityPolicy.INVENTORY_ORDER);
        assertTrue(learned.reached());
        assertEquals(1, learned.witness().size());
        var move = learned.witness().getFirst().move();
        assertEquals(SearchMove.SourceKind.LEARNED, move.sourceKind());
        assertEquals(3, move.transformation().primitiveStepCount());
        assertEquals(goal, learned.witness().getFirst().target().expression());
        assertFalse(run(inventory, inventory.primitiveProviders(), source, goal, MovePriorityPolicy.INVENTORY_ORDER).reached());

        var history = new RuleHistoryMemory();
        history.observe(learned, MoveContext.Phase.TRAIN, Map.of());
        assertTrue(run(inventory, inventory.providers(), source, goal,
            HistoryMovePolicy.typed(history.freeze(), HistoryMovePolicy.Weights.DEFAULT)).reached());
        assertEquals(frozen, formation.toCanonicalJson());
    }

    private static TypedMoveSearch.Result run(TypedLearnedMoveInventory inventory, List<MoveProvider> providers,
            Expr source, Expr goal, MovePriorityPolicy policy) {
        return new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,
            new TypedMoveSearch.Context(goal, List.of(), MoveContext.Phase.TRAIN), providers, policy,
            inventory.verifier(), state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(6, 1, 0, 100, 30_000)));
    }

    private static Expr add(Expr left, Expr right) { return new BinaryExpr(left, ADD, right); }
    private static Expr mul(Expr left, Expr right) { return new BinaryExpr(left, MUL, right); }
}
