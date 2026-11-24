package de.regelsuche.sdk.optimization.combinatorics;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.combinatorics.EnergyGame;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnergyGameSdkTest {
    @Test void facadeReturnsCheckedStrategyAndPreservesResourceStatuses() {
        var budget = new OptimizationBudget(100_000, 1_000, 1, 5_000);
        var graph = new EnergyGame.Graph(List.of(EnergyGame.Owner.MAX, EnergyGame.Owner.MIN),
            List.of(new EnergyGame.Edge(0, 1, BigInteger.valueOf(-2)),
                new EnergyGame.Edge(1, 0, BigInteger.valueOf(3))), 0);
        var request = new EnergyGame.Request(graph, BigInteger.TEN);
        var result = EnergyGameSdk.solve(request, budget, CancellationToken.NONE);
        assertEquals(EnergyGame.Status.WINNING, result.status());
        assertTrue(EnergyGameSdk.verify(request, result.certificate().orElseThrow(), budget, CancellationToken.NONE).valid());
        assertEquals("CANCELLED", EnergyGameSdk.solve(request, budget, () -> true).reason());
        assertEquals(EnergyGame.Status.UNAVAILABLE,
            EnergyGameSdk.solve(request, new OptimizationBudget(1, 1_000, 1, 5_000), CancellationToken.NONE).status());
    }
}
