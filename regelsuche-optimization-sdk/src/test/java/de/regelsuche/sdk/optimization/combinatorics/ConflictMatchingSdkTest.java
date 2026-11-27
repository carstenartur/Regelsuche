package de.regelsuche.sdk.optimization.combinatorics;

import static de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status.UNAVAILABLE;
import static de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status.VERIFIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching;
import de.regelsuche.math.algorithms.combinatorics.ConflictMatching;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConflictMatchingSdkTest {
    @Test
    void delegatesTheSameProofWorkAndLimitsToTheGeneralCore() {
        var request = new ConflictMatching.Request(new BipartiteMatching.Request(2, 2,
            List.of(new BipartiteMatching.Edge(0, 0), new BipartiteMatching.Edge(1, 1))),
            List.of(new ConflictMatching.Conflict(0, 1)));
        var budget = new OptimizationBudget(2_000_000, 20_000, 1, 5_000);
        var core = ConflictMatching.search(request, new IterationLimits(budget.maximumWork(),
            budget.maximumStates(), budget.timeoutMillis()), () -> false);
        var result = ConflictMatchingSdk.search(request, budget, CancellationToken.NONE);
        assertEquals(VERIFIED, result.status());
        assertEquals(core, result);
        assertEquals(1, result.bound().orElseThrow().guaranteedUnmatchedLeft());
        var proof = result.certificate().orElseThrow();
        assertTrue(ConflictMatchingSdk.verify(request, proof, budget, CancellationToken.NONE).valid());

        var workLimited = new OptimizationBudget(1, budget.maximumStates(), 1, budget.timeoutMillis());
        assertEquals(UNAVAILABLE, ConflictMatchingSdk.search(request, workLimited, CancellationToken.NONE).status());
        var cellLimited = new OptimizationBudget(budget.maximumWork(), 1, 1, budget.timeoutMillis());
        assertEquals(UNAVAILABLE, ConflictMatchingSdk.search(request, cellLimited, CancellationToken.NONE).status());
        var cancelled = ConflictMatchingSdk.search(request, budget, () -> true);
        assertEquals(UNAVAILABLE, cancelled.status());
        assertTrue(cancelled.certificate().isEmpty());
        assertTrue(cancelled.bound().isEmpty());
        assertEquals(UNAVAILABLE, ConflictMatchingSdk.verify(request, proof, budget, () -> true).status());
        assertEquals(UNAVAILABLE, ConflictMatchingSdk.verify(request, proof, workLimited, CancellationToken.NONE).status());
        assertEquals(UNAVAILABLE, ConflictMatchingSdk.verify(request, proof, cellLimited, CancellationToken.NONE).status());
    }
}
