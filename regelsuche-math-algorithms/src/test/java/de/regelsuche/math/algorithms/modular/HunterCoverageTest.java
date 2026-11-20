package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

import static java.math.BigInteger.valueOf;
import static org.junit.jupiter.api.Assertions.*;

class HunterCoverageTest {
    static final IterationLimits LIMITS = new IterationLimits(1_000_000, 256, 10_000);
    static final CongruenceCoverage.Request EXAMPLE = request(0, 1, 30, List.of(
        rule(2, 0, 0), rule(3, 0, 0), rule(5, 0, 0)));

    @Test
    void sevenSurvivorsWithoutCountingTheTripleIntersection() {
        var proof = build(EXAMPLE);
        assertEquals(List.of(valueOf(15), valueOf(10), valueOf(6)), proof.singletonCounts());
        assertEquals(List.of(edge(0, 1, 5), edge(0, 2, 3)), proof.tree());
        assertEquals(valueOf(7), proof.lowerBound());
        assertTrue(valid(EXAMPLE, proof));
        assertEquals(valueOf(0), bonferroni(EXAMPLE, 1));
        assertEquals(valueOf(8), bonferroni(EXAMPLE, 3));
        var otherTree = new HunterCoverage.Certificate(EXAMPLE, proof.singletonCounts(),
            List.of(edge(0, 1, 5), edge(1, 2, 2)), valueOf(6));
        assertTrue(valid(EXAMPLE, otherTree));
    }

    @Test
    void countsAndMaximumWeightAgreeWithSmallIndependentEnumeration() {
        var random = new Random(20261120);
        for (int iteration = 0; iteration < 220; iteration++) {
            int first = random.nextInt(31) - 15;
            int step = 1 + random.nextInt(6);
            int count = random.nextInt(22);
            int vertices = random.nextInt(6);
            var rules = new ArrayList<CongruenceCoverage.Rule>();
            for (int i = 0; i < vertices; i++) {
                rules.add(i == 1 && iteration % 3 == 0 ? rules.getFirst()
                    : rule(1 + random.nextInt(9), random.nextInt(19) - 9, random.nextInt(41) - 20));
            }
            var source = request(first, step, count, rules);
            var proof = build(source);
            long[] masks = new long[vertices];
            long union = 0;
            int singletonSum = 0;
            for (int i = 0; i < vertices; i++) {
                var rule = rules.get(i);
                for (int k = 0; k < count; k++) {
                    int n = first + step * k;
                    if (n >= rule.activeFrom().intValueExact()
                            && Math.floorMod(n - rule.residue().intValueExact(), rule.modulus().intValueExact()) == 0)
                        masks[i] |= 1L << k;
                }
                union |= masks[i];
                singletonSum += Long.bitCount(masks[i]);
                assertEquals(valueOf(Long.bitCount(masks[i])), proof.singletonCounts().get(i));
            }
            for (var edge : proof.tree()) {
                assertEquals(valueOf(Long.bitCount(masks[edge.left()] & masks[edge.right()])), edge.intersectionCount());
            }
            int treeWeight = proof.tree().stream().mapToInt(edge -> edge.intersectionCount().intValueExact()).sum();
            int bestWeight = bestGrowthOrder(masks, vertices == 0 ? 0 : 1);
            assertEquals(bestWeight, treeWeight);
            assertEquals(valueOf(Math.max(0, count - singletonSum + bestWeight)), proof.lowerBound());
            assertTrue(valid(source, proof));
            assertTrue(proof.lowerBound().intValueExact() <= count - Long.bitCount(union));
            assertTrue(proof.lowerBound().compareTo(bonferroni(source, 1)) >= 0);
        }
    }

    // Every rooted tree has a growth order. For each order independently try
    // the best attachment to the preceding vertices, without invoking an MST algorithm.
    private static int bestGrowthOrder(long[] masks, int seen) {
        int best = 0;
        for (int next = 0; next < masks.length; next++) {
            if ((seen & (1 << next)) != 0) continue;
            int weight = 0;
            for (int parent = 0; parent < masks.length; parent++) {
                if ((seen & (1 << parent)) != 0)
                    weight = Math.max(weight, Long.bitCount(masks[parent] & masks[next]));
            }
            best = Math.max(best, weight + bestGrowthOrder(masks, seen | (1 << next)));
        }
        return best;
    }

    @Test
    void extremesOfTheFiniteWindowAndAllBlockedDuplicates() {
        var huge = BigInteger.TEN.pow(100);
        var progression = new CongruenceCoverage.Progression(huge.negate(), BigInteger.ONE, huge);
        assertEquals(huge, build(new CongruenceCoverage.Request(progression, List.of())).lowerBound());
        var active = new CongruenceCoverage.Rule(BigInteger.ONE, BigInteger.ZERO, huge.negate());
        for (var rules : List.of(List.of(active), List.of(active, active, active))) {
            var source = new CongruenceCoverage.Request(progression, rules);
            assertEquals(BigInteger.ZERO, build(source).lowerBound());
            assertTrue(valid(source, build(source)));
        }
        assertEquals(BigInteger.ZERO, build(request(-3, 4, 0, EXAMPLE.rules())).lowerBound());
        var negativeRawBound = request(0, 1, 6, List.of(rule(2, 0, 0), rule(2, 1, 0), rule(3, 0, 0), rule(3, 1, 0)));
        assertEquals(BigInteger.ZERO, build(negativeRawBound).lowerBound());
        assertTrue(valid(negativeRawBound, build(negativeRawBound)));
    }

    @Test
    void replayRejectsForgedEndpointsMultiplicityCountsAndSource() {
        var proof = build(EXAMPLE);
        var singles = proof.singletonCounts();
        for (var tree : List.of(List.of(edge(0, 1, 5)), List.of(edge(0, 1, 5), edge(1, 0, 5)),
                List.of(edge(0, 0, 15), edge(1, 2, 2)), List.of(edge(0, 3, 3), edge(0, 1, 5)),
                List.of(edge(-1, 2, 3), edge(0, 1, 5)), List.of(edge(0, 1, 5), edge(0, 2, 4)))) {
            assertFalse(valid(EXAMPLE, new HunterCoverage.Certificate(EXAMPLE, singles, tree, valueOf(7))));
        }
        assertFalse(valid(EXAMPLE, new HunterCoverage.Certificate(EXAMPLE, List.of(), proof.tree(), valueOf(7))));
        assertFalse(valid(EXAMPLE, new HunterCoverage.Certificate(EXAMPLE,
            List.of(valueOf(15), valueOf(9), valueOf(6)), proof.tree(), valueOf(8))));
        assertFalse(valid(EXAMPLE, new HunterCoverage.Certificate(EXAMPLE, singles, proof.tree(), valueOf(8))));
        assertFalse(valid(request(1, 1, 30, EXAMPLE.rules()), proof));
        assertFalse(valid(request(0, 1, 30, EXAMPLE.rules().reversed()), proof));
    }

    @Test
    void cancellationAndResourceExhaustionProduceNoEvidence() {
        var proof = build(EXAMPLE);
        var oversized = request(0, 1, 1, List.of(new CongruenceCoverage.Rule(
            BigInteger.ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS), BigInteger.ZERO, BigInteger.ZERO)));
        assertEquals("RUNTIME_BIT_LIMIT", HunterCoverage.bounds(oversized, LIMITS, () -> false).reason());
        for (var limits : List.of(LIMITS.withMaximumWork(1), new IterationLimits(1_000_000, 2, 10_000))) {
            assertTrue(HunterCoverage.bounds(EXAMPLE, limits, () -> false).certificate().isEmpty());
            assertFalse(HunterCoverage.verify(EXAMPLE, proof, limits, () -> false).valid());
        }
        assertEquals("CANCELLED", HunterCoverage.bounds(EXAMPLE, LIMITS, () -> true).reason());
        assertFalse(HunterCoverage.verify(EXAMPLE, proof, LIMITS, () -> true).valid());
        var checks = new AtomicInteger();
        var midSearch = HunterCoverage.bounds(EXAMPLE, LIMITS, () -> checks.incrementAndGet() == 75);
        assertEquals("CANCELLED", midSearch.reason());
        assertTrue(midSearch.work() > 0);
        assertTrue(midSearch.certificate().isEmpty());
        var time = new IterationLimits(1_000_000, 256, 1);
        var expired = HunterCoverage.bounds(EXAMPLE, time, () -> {
            long start = System.nanoTime();
            while (System.nanoTime() - start < 2_000_000) LockSupport.parkNanos(2_000_000);
            return false;
        });
        assertEquals("TIME_LIMIT", expired.reason());
        assertTrue(expired.certificate().isEmpty());
    }

    private static BigInteger bonferroni(CongruenceCoverage.Request request, int order) {
        return CongruenceCoverage.bounds(request, order, LIMITS, () -> false).certificate().orElseThrow().lowerBound();
    }

    private static HunterCoverage.Certificate build(CongruenceCoverage.Request request) {
        return HunterCoverage.bounds(request, LIMITS, () -> false).certificate().orElseThrow();
    }

    private static boolean valid(CongruenceCoverage.Request request, HunterCoverage.Certificate certificate) {
        return HunterCoverage.verify(request, certificate, LIMITS, () -> false).valid();
    }

    private static HunterCoverage.Edge edge(int left, int right, int count) {
        return new HunterCoverage.Edge(left, right, valueOf(count));
    }

    private static CongruenceCoverage.Request request(int first, int step, int count,
            List<CongruenceCoverage.Rule> rules) {
        return new CongruenceCoverage.Request(new CongruenceCoverage.Progression(valueOf(first), valueOf(step),
            valueOf(count)), rules);
    }

    private static CongruenceCoverage.Rule rule(int modulus, int residue, int activeFrom) {
        return new CongruenceCoverage.Rule(valueOf(modulus), valueOf(residue), valueOf(activeFrom));
    }
}
