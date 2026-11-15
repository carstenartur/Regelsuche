package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderedReductionSdkTest {
    @Test
    void checksPreparesAndUpdatesAnOrderedSumThroughTheSdk() {
        var scope = new SymbolScope();
        var slots = List.of(List.of(scope.declare("left")), List.of(scope.declare("right")));
        var source = new OrderedReduction(slots, List.of(OrderedReduction.All.ROWS),
            SymbolicExpression.parse("(left-right)^2", scope).expression());
        var budget = OptimizationBudget.DEFAULT;
        var token = CancellationToken.NONE;
        var found = OrderedReductionSdk.searchPointUpdates(source, budget, token).verified().orElseThrow();
        var knowledge = OrderedReductionSdk.learnPointUpdateKnowledge(found, budget, token).knowledge().orElseThrow();
        var reused = OrderedReductionSdk.searchPointUpdates(source, knowledge, budget, token).verified().orElseThrow();
        assertEquals(found.plan(), reused.plan());
        var checked = OrderedReductionSdk.verifyPointUpdates(source, found.plan(), budget, token).verified().orElseThrow();
        var tree = OrderedReductionSdk.preparePointUpdates(checked, List.of(row(1), row(4), row(6)), budget, token);
        assertEquals(BigInteger.valueOf(38), tree.value());
        var changed = OrderedReductionSdk.replaceRow(tree, 1, row(2), budget, token);
        assertEquals(BigInteger.valueOf(42), changed.value());
        assertEquals(2, changed.blockCombinations());
        assertTrue(OrderedReductionSdk.searchPointUpdates(source, budget, () -> true).verified().isEmpty());
    }

    private static List<BigInteger> row(long value) {
        return List.of(BigInteger.valueOf(value));
    }

    @Test
    void learnsAndAppliesACheckedPairStateThroughTheSdk() {
        var scope = new SymbolScope();
        var slots = List.of(List.of(scope.declare("left")), List.of(scope.declare("middle")),
            List.of(scope.declare("right")));
        var pair = new OrderedReduction(slots.subList(0, 2), List.of(OrderedReduction.All.ROWS),
            SymbolicExpression.parse("left-middle", scope).expression());
        var triple = new OrderedReduction(slots, List.of(OrderedReduction.All.ROWS, OrderedReduction.All.ROWS),
            SymbolicExpression.parse("(left-middle)*right^2", scope).expression());
        var budget = OptimizationBudget.DEFAULT;
        var noCancellation = CancellationToken.NONE;
        var trained = OrderedReductionSdk.searchPointUpdates(pair, budget, noCancellation).verified().orElseThrow();
        var lesson = OrderedReductionSdk.learnPairState(trained, budget, noCancellation).knowledge().orElseThrow();
        var found = OrderedReductionSdk.searchPointUpdates(triple, lesson, budget, noCancellation).verified().orElseThrow();
        var direct = OrderedReductionSdk.searchProjectedPointUpdates(triple, budget, noCancellation).verified().orElseThrow();
        assertTrue(found.plan().pairState().isPresent());
        assertEquals(found.plan().pairState(), direct.plan().pairState());
        var tree = OrderedReductionSdk.preparePointUpdates(found, List.of(row(1), row(4), row(6), row(9)), budget,
            noCancellation);
        assertEquals(BigInteger.valueOf(-918), tree.value());
        assertEquals(BigInteger.valueOf(-846), OrderedReductionSdk.replaceRow(tree, 1, row(2), budget,
            noCancellation).value());
    }
}
