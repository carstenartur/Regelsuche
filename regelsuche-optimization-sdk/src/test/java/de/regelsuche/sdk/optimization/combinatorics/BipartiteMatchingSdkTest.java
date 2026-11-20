package de.regelsuche.sdk.optimization.combinatorics;

import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching.Edge;
import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching.Request;
import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching.Status;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BipartiteMatchingSdkTest {
    private static final OptimizationBudget BUDGET = new OptimizationBudget(10_000, 100, 1, 10_000);
    private static final Request SOURCE = new Request(2, 1, List.of(new Edge(0, 0), new Edge(1, 0)));

    @Test
    void searchesAndReplaysAHallCertificate() {
        var result = BipartiteMatchingSdk.search(SOURCE, BUDGET, CancellationToken.NONE);
        var certificate = result.certificate().orElseThrow();
        assertEquals(1, certificate.matchingSize());
        assertEquals(1, certificate.deficiency());
        assertTrue(BipartiteMatchingSdk.verify(SOURCE, certificate, BUDGET, CancellationToken.NONE).valid());
    }

    @Test
    void forwardsCancellationAndExhaustionToBothOperations() {
        var certificate = BipartiteMatchingSdk.search(SOURCE, BUDGET, CancellationToken.NONE)
            .certificate().orElseThrow();
        var cancelled = BipartiteMatchingSdk.search(SOURCE, BUDGET, () -> true);
        assertEquals(Status.UNAVAILABLE, cancelled.status());
        assertTrue(cancelled.certificate().isEmpty());
        assertFalse(BipartiteMatchingSdk.verify(SOURCE, certificate, BUDGET, () -> true).valid());

        for (var budget : List.of(new OptimizationBudget(1, 100, 1, 10_000),
                new OptimizationBudget(10_000, 1, 1, 10_000))) {
            var result = BipartiteMatchingSdk.search(SOURCE, budget, CancellationToken.NONE);
            assertEquals(Status.UNAVAILABLE, result.status());
            assertTrue(result.certificate().isEmpty());
            assertFalse(BipartiteMatchingSdk.verify(SOURCE, certificate, budget, CancellationToken.NONE).valid());
        }
    }
}
