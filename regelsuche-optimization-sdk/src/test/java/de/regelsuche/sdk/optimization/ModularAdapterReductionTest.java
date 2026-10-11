package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.modular.ModularComputationDomain;
import de.regelsuche.math.algorithms.modular.ModularJointPlans;
import de.regelsuche.search.moves.MoveSearch;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModularAdapterReductionTest {
    @Test void directAdapterOptimizesAndExecutesRepeatedReduction() {
        Expr x = new VariableExpr("x"), modulus = new VariableExpr("m");
        Expr reduced = new FunctionExpr("mod", List.of(x, modulus));
        var adapter = new ModularJointPlans(new ModularComputationDomain(Set.of(), Set.of("m"), Set.of()));
        var source = adapter.plan(Map.of("result", new FunctionExpr("mod", List.of(reduced, modulus))), Set.of("x", "m"));
        var result = adapter.optimizer(64).optimize(source, new MoveSearch.Budget(5, 5, 0, 128, 1_000_000));

        assertTrue(result.search().improved());
        assertTrue(result.withinBudget());
        assertTrue(adapter.verify(source, result.plan()).accepted());
        assertEquals(List.of(reduced), result.plan().resolveOutputs().expressions());
        assertTrue(result.prepared().cost().operationWork() < source.prepare(adapter).cost().operationWork());
        assertEquals(1, result.prepared().nodes().stream()
                .filter(node -> node.operation() != null && node.operation().id().equals("mod")).count());
        for (int value = -30; value <= 30; value++) for (int divisor = 1; divisor <= 31; divisor++) {
            var input = BigInteger.valueOf(value);
            var positiveModulus = BigInteger.valueOf(divisor);
            assertEquals(input.mod(positiveModulus),
                    result.prepared().execute(Map.of("x", input, "m", positiveModulus)).get("result"));
        }
        for (var invalid : List.of(BigInteger.ZERO, BigInteger.ONE.negate())) {
            assertThrows(IllegalArgumentException.class,
                    () -> result.prepared().execute(Map.of("x", BigInteger.TEN, "m", invalid)));
        }
    }
}
