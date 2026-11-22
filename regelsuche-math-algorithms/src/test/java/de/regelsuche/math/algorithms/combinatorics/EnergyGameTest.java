package de.regelsuche.math.algorithms.combinatorics;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EnergyGameTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 100_000, 10_000);

    @Test void cyclicGameHasInductiveCreditAndAdversarialMinClosure() {
        var request = example();
        var result = EnergyGame.solve(request, LIMITS, () -> false);
        assertEquals(EnergyGame.Status.WINNING, result.status(), result.reason());
        var certificate = result.certificate().orElseThrow();
        assertEquals(Map.of(0, BigInteger.TWO, 1, BigInteger.ZERO), certificate.credits());
        assertEquals(Map.of(0, 0), certificate.strategy());
        assertTrue(EnergyGameVerifier.verify(request, certificate, LIMITS, () -> false).valid());
    }

    @Test void boundsLosingCyclesAndParallelEdgesHaveExplicitSemantics() {
        var negative = request(List.of(EnergyGame.Owner.MAX), List.of(edge(0, 0, -1)), 0, 3);
        assertEquals(EnergyGame.Status.NO_CERTIFICATE_WITHIN_BOUND, EnergyGame.solve(negative, LIMITS, () -> false).status());
        var tooSmall = new EnergyGame.Request(example().graph(), BigInteger.ONE);
        assertEquals(EnergyGame.Status.NO_CERTIFICATE_WITHIN_BOUND, EnergyGame.solve(tooSmall, LIMITS, () -> false).status());
        var parallel = request(List.of(EnergyGame.Owner.MAX), List.of(edge(0, 0, -1), edge(0, 0, 0)), 0, 0);
        assertEquals(Map.of(0, 1), EnergyGame.solve(parallel, LIMITS, () -> false).certificate().orElseThrow().strategy());
        var min = request(List.of(EnergyGame.Owner.MIN), parallel.graph().edges(), 0, 3);
        assertEquals(EnergyGame.Status.NO_CERTIFICATE_WITHIN_BOUND, EnergyGame.solve(min, LIMITS, () -> false).status());
        BigInteger huge = BigInteger.ONE.shiftLeft(180);
        var large = new EnergyGame.Request(new EnergyGame.Graph(List.of(EnergyGame.Owner.MAX, EnergyGame.Owner.MIN),
            List.of(new EnergyGame.Edge(0, 1, huge.negate()), new EnergyGame.Edge(1, 1, BigInteger.ZERO)), 0), huge);
        assertEquals(huge, EnergyGame.solve(large, LIMITS, () -> false).certificate().orElseThrow().credits().get(0));
    }

    @Test void arbitrarySmallGamesAgreeWithIndependentCreditRegionEnumeration() {
        var random = new Random(104);
        for (int trial = 0; trial < 100; trial++) {
            int n = 1 + random.nextInt(4);
            var owners = new ArrayList<EnergyGame.Owner>();
            var edges = new ArrayList<EnergyGame.Edge>();
            for (int v = 0; v < n; v++) {
                owners.add(random.nextBoolean() ? EnergyGame.Owner.MAX : EnergyGame.Owner.MIN);
                for (int k = 0, count = 1 + random.nextInt(3); k < count; k++)
                    edges.add(edge(v, random.nextInt(n), random.nextInt(5) - 2));
            }
            var request = request(owners, edges, random.nextInt(n), 3);
            int minimum = enumerate(request, 3);
            var result = EnergyGame.solve(request, LIMITS, () -> false);
            assertEquals(minimum >= 0, result.status() == EnergyGame.Status.WINNING, "trial " + trial + ": " + result);
            if (minimum >= 0) {
                var certificate = result.certificate().orElseThrow();
                assertEquals(BigInteger.valueOf(minimum), certificate.credits().get(request.graph().start()));
                assertTrue(EnergyGameVerifier.verify(request, certificate, LIMITS, () -> false).valid());
            }
        }
    }

    @Test void verifierRejectsSourceSwitchBadChoicesOmittedMinEdgesAndBadCredit() {
        var request = example();
        var good = EnergyGame.solve(request, LIMITS, () -> false).certificate().orElseThrow();
        var wrongCredit = new EnergyGame.Certificate(request, Map.of(0, BigInteger.ONE, 1, BigInteger.ZERO), good.strategy());
        assertFalse(EnergyGameVerifier.verify(request, wrongCredit, LIMITS, () -> false).valid());
        var wrongEdge = new EnergyGame.Certificate(request, good.credits(), Map.of(0, 1));
        assertFalse(EnergyGameVerifier.verify(request, wrongEdge, LIMITS, () -> false).valid());
        var other = new EnergyGame.Request(new EnergyGame.Graph(request.graph().owners(),
            List.of(edge(0, 1, -2), edge(0, 0, -1), edge(1, 0, -3), edge(1, 1, 0)), 0), BigInteger.TEN);
        assertFalse(EnergyGameVerifier.verify(other, good, LIMITS, () -> false).valid());
        var rebound = new EnergyGame.Certificate(other, good.credits(), good.strategy());
        assertFalse(EnergyGameVerifier.verify(other, rebound, LIMITS, () -> false).valid());
        var partialRequest = request(List.of(EnergyGame.Owner.MIN, EnergyGame.Owner.MAX), List.of(edge(0, 0, 0), edge(0, 1, 0), edge(1, 1, -1)), 0, 3);
        var partial = new EnergyGame.Certificate(partialRequest, Map.of(0, BigInteger.ZERO), Map.of());
        assertFalse(EnergyGameVerifier.verify(partialRequest, partial, LIMITS, () -> false).valid());
        assertThrows(UnsupportedOperationException.class, () -> good.credits().clear());
    }

    @Test void limitsAndCancellationNeverProduceAWinningClaim() {
        var request = example();
        for (int budget = 1; budget < 20; budget++) {
            var result = EnergyGame.solve(request, new IterationLimits(budget, 100, 5_000), () -> false);
            assertEquals(EnergyGame.Status.UNAVAILABLE, result.status());
            assertTrue(result.certificate().isEmpty());
        }
        assertEquals("CANCELLED", EnergyGame.solve(request, LIMITS, () -> true).reason());
        assertEquals("TERM_LIMIT", EnergyGame.solve(request, new IterationLimits(100_000, 1, 5_000), () -> false).reason());
        var huge = new EnergyGame.Request(request.graph(), BigInteger.ONE.shiftLeft(65_536));
        assertEquals("RUNTIME_BIT_LIMIT", EnergyGame.solve(huge, LIMITS, () -> false).reason());
        var proof = EnergyGame.solve(request, LIMITS, () -> false).certificate().orElseThrow();
        assertEquals(EnergyGameVerifier.Status.UNAVAILABLE, EnergyGameVerifier.verify(request, proof, new IterationLimits(1, 100, 5_000), () -> false).status());
    }

    @Test void matchingGraphSourcePollsDuringComparisonBeforeCheckingTheRegion() {
        int size = 64;
        var owners = java.util.Collections.nCopies(size, EnergyGame.Owner.MIN);
        var edges = new ArrayList<EnergyGame.Edge>();
        for (int v = 0; v < size; v++) {
            edges.add(edge(v, v, 0));
        }
        var source = request(owners, edges, 0, 0);
        var sameSource = request(owners, edges, 0, 0);
        var differentBound = request(owners, edges, 0, 1);
        var control = new EnergyGame.Certificate(differentBound, Map.of(), Map.of());
        var deferredRegionCheck = new EnergyGame.Certificate(sameSource, Map.of(), Map.of());
        var polls = new AtomicInteger();
        assertEquals("SOURCE_MISMATCH", EnergyGameVerifier.verify(source, control, LIMITS, () -> {
            polls.incrementAndGet();
            return false;
        }).reason());
        int cancelAfter = polls.get() + 8;
        polls.set(0);
        var cancelled = EnergyGameVerifier.verify(source, deferredRegionCheck, LIMITS,
            () -> polls.incrementAndGet() > cancelAfter);
        assertEquals(EnergyGameVerifier.Status.UNAVAILABLE, cancelled.status(), cancelled.reason());
        assertEquals("CANCELLED", cancelled.reason());
        assertEquals("MISSING_START", EnergyGameVerifier.verify(source, deferredRegionCheck, LIMITS, () -> false).reason());
    }

    @Test void creditAndStrategySnapshotsRetainUnmodifiableMapContract() {
        var source = example();
        var credits = new HashMap<>(Map.of(0, BigInteger.TWO, 1, BigInteger.ZERO));
        var strategy = new HashMap<>(Map.of(0, 0));
        var certificate = new EnergyGame.Certificate(source, credits, strategy);
        credits.clear();
        strategy.clear();
        assertTrue(EnergyGameVerifier.verify(source, certificate, LIMITS, () -> false).valid());

        assertThrows(UnsupportedOperationException.class, () -> certificate.credits().replace(99, BigInteger.ZERO));
        assertThrows(UnsupportedOperationException.class, () -> certificate.strategy().computeIfAbsent(0, key -> 1));
        assertThrows(NullPointerException.class, () -> certificate.credits().containsKey(null));
        strategy.put(null, 1);
        assertThrows(NullPointerException.class, () -> new EnergyGame.Certificate(source, credits, strategy));
    }

    private static EnergyGame.Request example() {
        return request(List.of(EnergyGame.Owner.MAX, EnergyGame.Owner.MIN),
            List.of(edge(0, 1, -2), edge(0, 0, -1), edge(1, 0, 3), edge(1, 1, 0)), 0, 10);
    }

    private static EnergyGame.Edge edge(int from, int to, int weight) {
        return new EnergyGame.Edge(from, to, BigInteger.valueOf(weight));
    }

    private static EnergyGame.Request request(List<EnergyGame.Owner> owners, List<EnergyGame.Edge> edges, int start, int bound) {
        return new EnergyGame.Request(new EnergyGame.Graph(owners, edges, start), BigInteger.valueOf(bound));
    }

    /** Enumerates every finite credit/omitted-vertex region, independently of lifting. */
    private static int enumerate(EnergyGame.Request request, int bound) {
        int n = request.graph().owners().size(), radix = bound + 2, possibilities = 1;
        for (int v = 0; v < n; v++) possibilities *= radix;
        int best = Integer.MAX_VALUE;
        for (int mask = 0; mask < possibilities; mask++) {
            int[] credits = new int[n];
            for (int v = 0, value = mask; v < n; v++, value /= radix) credits[v] = value % radix;
            if (credits[request.graph().start()] > bound) continue;
            boolean valid = true;
            for (int v = 0; v < n && valid; v++) {
                if (credits[v] > bound) continue;
                boolean any = false, all = true;
                for (var edge : request.graph().edges()) if (edge.from() == v) {
                    boolean admissible = credits[edge.to()] <= bound && credits[v] + edge.weight().intValueExact() >= credits[edge.to()];
                    any |= admissible; all &= admissible;
                }
                valid = request.graph().owners().get(v) == EnergyGame.Owner.MAX ? any : all;
            }
            if (valid) best = Math.min(best, credits[request.graph().start()]);
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }
}
