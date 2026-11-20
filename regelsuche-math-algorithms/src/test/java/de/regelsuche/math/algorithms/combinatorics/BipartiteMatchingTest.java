package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching.Certificate;
import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching.Edge;
import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching.Request;
import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching.Status;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BipartiteMatchingTest {
    private static final IterationLimits LIMITS = new IterationLimits(100_000, 1_000, 10_000);
    private static final BooleanSupplier RUN = () -> false;

    @Test
    void rematchesAnEarlierVertexToReachTheMaximum() {
        var source = graph(2, 2, new Edge(0, 0), new Edge(0, 1), new Edge(1, 0));
        var certificate = complete(source);
        assertEquals(List.of(new Edge(0, 1), new Edge(1, 0)), certificate.matching());
        assertEquals(List.of(), certificate.witness());
        assertEquals(0, certificate.deficiency());
        assertTrue(BipartiteMatching.verify(source, certificate, LIMITS, RUN).valid());
    }

    @Test
    void traversesALongAlternatingPathWithinTheWorkBudget() {
        int count = 20_000;
        var edges = new ArrayList<Edge>();
        for (int vertex = 0; vertex < count - 1; vertex++) {
            edges.add(new Edge(vertex, vertex));
            edges.add(new Edge(vertex, vertex + 1));
        }
        edges.add(new Edge(count - 1, 0));
        var source = new Request(count, count, edges);
        var limits = new IterationLimits(1_000_000, 100_000, 10_000);
        var result = BipartiteMatching.search(source, limits, RUN);
        assertEquals(Status.COMPLETE, result.status(), result::reason);
        var certificate = result.certificate().orElseThrow();
        assertEquals(count, certificate.matchingSize());
        assertTrue(certificate.witness().isEmpty());
        assertTrue(BipartiteMatching.verify(source, certificate, limits, RUN).valid());
    }

    @Test
    void detectsALocalBottleneckDespiteEqualSideSizes() {
        var source = bottleneck();
        var certificate = complete(source);
        assertEquals(2, certificate.matchingSize());
        assertEquals(2, certificate.deficiency());
        assertEquals(List.of(0, 1, 2), certificate.witness());
        assertTrue(BipartiteMatching.verify(source, certificate, LIMITS, RUN).valid());
    }

    @Test
    void agreesWithEveryLeftSubsetAcrossAllGraphsUpToThreeByThree() {
        for (int left = 0; left <= 3; left++) {
            for (int right = 0; right <= 3; right++) {
                for (int mask = 0; mask < (1 << (left * right)); mask++) {
                    var edges = new ArrayList<Edge>();
                    for (int u = 0; u < left; u++) {
                        for (int v = 0; v < right; v++) {
                            if ((mask & (1 << (u * right + v))) != 0) edges.add(new Edge(u, v));
                        }
                    }
                    var source = new Request(left, right, edges);
                    var certificate = complete(source);
                    assertEquals(maximumDeficit(left, edges), certificate.deficiency(), source::toString);
                    var replay = BipartiteMatching.verify(source, certificate, LIMITS, RUN);
                    assertTrue(replay.valid(), () -> source + " " + replay);
                }
            }
        }
    }

    @Test
    void preservesIsolatesAndUsesCanonicalEdgeSetForDeterminismAndSourceIdentity() {
        var edges = new ArrayList<>(List.of(new Edge(2, 0), new Edge(0, 1), new Edge(0, 0), new Edge(2, 0)));
        var source = new Request(3, 3, edges);
        edges.clear();
        var ordered = graph(3, 3, new Edge(0, 0), new Edge(0, 1), new Edge(2, 0));
        var certificate = complete(source);
        assertEquals(complete(ordered), certificate);
        assertEquals(List.of(1), certificate.witness());
        assertTrue(BipartiteMatching.verify(ordered, certificate, LIMITS, RUN).valid());
        assertThrows(UnsupportedOperationException.class, () -> source.edges().clear());

        var matching = new ArrayList<>(certificate.matching());
        var witness = new ArrayList<>(certificate.witness());
        var copied = new Certificate(source, matching, witness);
        matching.clear();
        witness.clear();
        assertEquals(certificate, copied);
        assertThrows(UnsupportedOperationException.class, () -> copied.matching().clear());
        assertThrows(UnsupportedOperationException.class, () -> copied.witness().clear());
    }

    @Test
    void rejectsInvalidSourceDimensionsAndEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> graph(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> graph(0, -1));
        assertThrows(IllegalArgumentException.class, () -> graph(1, 1, new Edge(-1, 0)));
        assertThrows(IllegalArgumentException.class, () -> graph(1, 1, new Edge(0, -1)));
        assertThrows(IllegalArgumentException.class, () -> graph(1, 1, new Edge(1, 0)));
        assertThrows(IllegalArgumentException.class, () -> graph(1, 1, new Edge(0, 1)));
    }

    @Test
    void replayRejectsTransplantsEvenWhenTheEvidenceStillProvesTheSameCardinality() {
        var source = bottleneck();
        var certificate = complete(source);
        var changedEdges = graph(4, 4, new Edge(0, 0), new Edge(1, 0), new Edge(2, 0),
            new Edge(3, 1), new Edge(3, 2));
        assertInvalid(changedEdges, certificate);
        assertInvalid(new Request(4, 5, source.edges()), certificate);
        var sameSizeChangedEdges = graph(4, 4, new Edge(0, 0), new Edge(1, 0), new Edge(2, 0),
            new Edge(3, 0), new Edge(3, 1), new Edge(3, 2));
        var replay = BipartiteMatching.verify(sameSizeChangedEdges, certificate, LIMITS, () -> false);
        assertFalse(replay.valid());
        assertEquals("SOURCE_MISMATCH", replay.reason());
    }

    @Test
    void replayRejectsReusedEndpointsNonedgesAndOutOfRangeEdgesEvenWithTheRightSize() {
        var source = bottleneck();
        var witness = List.of(0, 1, 2);
        for (var matching : List.of(
                List.of(new Edge(0, 0), new Edge(1, 0)),
                List.of(new Edge(3, 1), new Edge(3, 2)),
                List.of(new Edge(0, 0), new Edge(1, 1)),
                List.of(new Edge(0, 0), new Edge(4, 1)),
                List.of(new Edge(0, 0), new Edge(3, -1)))) {
            assertInvalid(source, new Certificate(source, matching, witness));
        }
    }

    @Test
    void replayDerivesTheFullNeighborhoodAndRejectsForgedWitnesses() {
        var source = bottleneck();
        var matching = complete(source).matching();
        for (var witness : List.<List<Integer>>of(List.of(), List.of(0, 0, 1), List.of(0, 1, 3), List.of(0, 1, 4))) {
            assertInvalid(source, new Certificate(source, matching, witness));
        }
    }

    @Test
    void replayAcceptsAlternativeValidEvidence() {
        var source = bottleneck();
        var alternative = new Certificate(source, List.of(new Edge(3, 3), new Edge(2, 0)), List.of(2, 0, 1));
        assertTrue(BipartiteMatching.verify(source, alternative, LIMITS, RUN).valid());
    }

    @Test
    void preCancelledOperationsChargeNoWork() {
        var source = bottleneck();
        var result = BipartiteMatching.search(source, LIMITS, () -> true);
        assertEquals(Status.UNAVAILABLE, result.status());
        assertTrue(result.certificate().isEmpty());
        assertEquals(0, result.work());

        var replay = BipartiteMatching.verify(source, complete(source), LIMITS, () -> true);
        assertFalse(replay.valid());
        assertEquals(0, replay.work());
    }

    @Test
    void workExhaustionDuringSearchAndReplayReturnsNoClaim() {
        var source = bottleneck();
        var result = BipartiteMatching.search(source, LIMITS.withMaximumWork(49), RUN);
        assertEquals(Status.UNAVAILABLE, result.status());
        assertEquals("WORK_LIMIT", result.reason());
        assertTrue(result.work() > 0);
        assertTrue(result.certificate().isEmpty());

        var certificate = complete(source);
        long replayWork = BipartiteMatching.verify(source, certificate, LIMITS, RUN).work();
        var replay = BipartiteMatching.verify(source, certificate, LIMITS.withMaximumWork(replayWork - 1), RUN);
        assertFalse(replay.valid());
        assertEquals("WORK_LIMIT", replay.reason());
    }

    @Test
    void dimensionsAreBoundedBeforeAllocationEvenForEdgelessSources() {
        var smallProof = complete(graph(0, 0));
        for (var source : List.of(graph(Integer.MAX_VALUE, 0), graph(0, Integer.MAX_VALUE),
                graph(1_000_001, 0))) {
            var proof = new Certificate(source, smallProof.matching(), smallProof.witness());
            var permissive = new IterationLimits(Long.MAX_VALUE, Integer.MAX_VALUE, 10_000);
            for (var limits : List.of(LIMITS, permissive)) {
                var result = BipartiteMatching.search(source, limits, RUN);
                assertEquals(Status.UNAVAILABLE, result.status());
                assertTrue(result.certificate().isEmpty());
                assertFalse(BipartiteMatching.verify(source, proof, limits, RUN).valid());
            }
        }
    }

    @Test
    void timeoutAlsoAppliesToEdgelessSources() {
        var source = graph(0, 0);
        var certificate = complete(source);
        var limits = new IterationLimits(100_000, 100, 1);
        BooleanSupplier delay = () -> {
            long start = System.nanoTime();
            while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(5)) {
                Thread.onSpinWait();
            }
            return false;
        };
        var search = BipartiteMatching.search(source, limits, delay);
        assertEquals("TIME_LIMIT", search.reason());
        assertTrue(search.certificate().isEmpty());
        var replay = BipartiteMatching.verify(source, certificate, limits, delay);
        assertEquals("TIME_LIMIT", replay.reason());
        assertFalse(replay.valid());
    }

    private static int maximumDeficit(int leftCount, List<Edge> edges) {
        int best = 0;
        for (int subset = 0; subset < (1 << leftCount); subset++) {
            int neighbors = 0;
            for (var edge : edges) {
                if ((subset & (1 << edge.left())) != 0) neighbors |= 1 << edge.right();
            }
            best = Math.max(best, Integer.bitCount(subset) - Integer.bitCount(neighbors));
        }
        return best;
    }

    private static void assertInvalid(Request source, Certificate certificate) {
        assertFalse(BipartiteMatching.verify(source, certificate, LIMITS, RUN).valid());
    }

    private static Certificate complete(Request source) {
        var result = BipartiteMatching.search(source, LIMITS, RUN);
        assertEquals(Status.COMPLETE, result.status(), result::reason);
        return result.certificate().orElseThrow();
    }

    private static Request bottleneck() {
        return graph(4, 4, new Edge(0, 0), new Edge(1, 0), new Edge(2, 0),
            new Edge(3, 1), new Edge(3, 2), new Edge(3, 3));
    }

    private static Request graph(int left, int right, Edge... edges) {
        return new Request(left, right, List.of(edges));
    }
}
