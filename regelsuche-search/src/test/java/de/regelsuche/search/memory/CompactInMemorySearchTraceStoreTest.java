package de.regelsuche.search.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompactInMemorySearchTraceStoreTest {

    @Test
    void hashConsingInternsExpressionsRulesAndAssumptionsOnlyOnce() {
        CompactInMemorySearchTraceStore store = new CompactInMemorySearchTraceStore();

        long expr1 = store.internExpression("h:x+y", "x + y");
        long expr2 = store.internExpression("h:x+y", "x + y");
        long rule1 = store.internRule("distribute_multiplication");
        long rule2 = store.internRule("distribute_multiplication");
        long assumptions1 = store.internAssumptions(AssumptionSignature.ofExpressions(List.of("x != 0")));
        long assumptions2 = store.internAssumptions(AssumptionSignature.ofExpressions(List.of("x != 0")));

        assertEquals(expr1, expr2);
        assertEquals(rule1, rule2);
        assertEquals(assumptions1, assumptions2);
        assertEquals(1, store.expressionCount());
        assertEquals(1, store.ruleCount());
        assertEquals(1, store.assumptionsCount());
    }

    @Test
    void compactPathEncodingRoundTripsAndReplayIsReconstructable() {
        CompactInMemorySearchTraceStore store = new CompactInMemorySearchTraceStore(0);
        long e1 = store.internExpression("h1", "a*(b+c)");
        long e2 = store.internExpression("h2", "a*b+a*c");
        long e3 = store.internExpression("h3", "a*c+a*b");
        long rule1 = store.internRule("distribute_multiplication");
        long rule2 = store.internRule("commutativity");
        long assumptions = store.internAssumptions(AssumptionSignature.ofExpressions(List.of("a != 0")));

        long edge1 = store.addEdge(e1, e2, Math.toIntExact(rule1), assumptions);
        long edge2 = store.addEdge(e2, e3, Math.toIntExact(rule2), assumptions);
        long pathId = store.addPath(new long[] {edge1, edge2});

        assertArrayEquals(new long[] {edge1, edge2}, store.expandPath(pathId));
        assertEquals(2, store.replay(pathId).size());
        assertEquals("a*(b+c)", store.replay(pathId).getFirst().fromCanonicalForm());
        assertEquals("distribute_multiplication", store.replay(pathId).getFirst().ruleId());
        assertTrue(
            store.pathStorageBytes(pathId) < 2 * Long.BYTES,
            "delta+varint encoding should be denser than raw long[] payload"
        );
    }

    @Test
    void supportsLargeDiscoveryRunWithMillionEdgesAndCompactPaths() {
        CompactInMemorySearchTraceStore store = new CompactInMemorySearchTraceStore(0);
        long e1 = store.internExpression("h:left", "x*(x+1)");
        long e2 = store.internExpression("h:right", "x*x+x");
        long rule = store.internRule("distribute_multiplication");
        long assumptions = store.internAssumptions(AssumptionSignature.ofExpressions(List.of("x != 0")));

        final int totalEdges = 1_000_000;
        long[] tailPath = new long[2_048];
        for (int i = 1; i <= totalEdges; i++) {
            long edgeId = store.addEdge((i & 1) == 0 ? e1 : e2, (i & 1) == 0 ? e2 : e1, Math.toIntExact(rule), assumptions);
            if (i > totalEdges - tailPath.length) {
                tailPath[i - (totalEdges - tailPath.length) - 1] = edgeId;
            }
        }
        long pathId = store.addPath(tailPath);

        assertEquals(totalEdges, store.edgeCount());
        assertEquals(1, store.compactPathCount());
        assertArrayEquals(tailPath, store.expandPath(pathId));
        assertEquals(tailPath.length, store.replay(pathId).size());
    }
}
