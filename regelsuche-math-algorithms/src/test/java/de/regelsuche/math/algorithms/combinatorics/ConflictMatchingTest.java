package de.regelsuche.math.algorithms.combinatorics;

import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching.Edge;
import de.regelsuche.math.algorithms.combinatorics.ConflictMatching.Certificate;
import de.regelsuche.math.algorithms.combinatorics.ConflictMatching.Conflict;
import de.regelsuche.math.algorithms.combinatorics.ConflictMatching.Request;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConflictMatchingTest {
    private static final IterationLimits LIMITS = new IterationLimits(10_000_000, 100_000, 10_000);

    @Test
    void conflictsProveDeficiencyThatOrdinaryHallCannotSee() {
        var source = disjoint(2, List.of(new Conflict(0, 1)));
        assertEquals(2, BipartiteMatching.search(source.graph(), LIMITS, () -> false)
            .certificate().orElseThrow().matchingSize());
        var result = complete(source);
        assertEquals(ONE, result.bound().orElseThrow().fractionalUpperBound());
        assertEquals(1, result.bound().orElseThrow().matchingSizeUpperBound());
        assertEquals(1, result.bound().orElseThrow().guaranteedUnmatchedLeft());
    }

    @Test
    void compatibleAlternativePreventsAFalseDeficiency() {
        var graph = new BipartiteMatching.Request(2, 3,
            List.of(new Edge(0, 0), new Edge(0, 1), new Edge(1, 2)));
        var source = new Request(graph, List.of(new Conflict(0, 2)));
        assertEquals(0, complete(source).bound().orElseThrow().guaranteedUnmatchedLeft());
    }

    @Test
    void keepsFractionalCoverAndRoundsOnlyTheIntegerConclusion() {
        var source = disjoint(3, allConflicts(3));
        var bound = complete(source).bound().orElseThrow();
        assertEquals(new ExactRational(BigInteger.valueOf(3), BigInteger.TWO), bound.fractionalUpperBound());
        assertEquals(1, bound.matchingSizeUpperBound());
        assertEquals(2, bound.guaranteedUnmatchedLeft());
    }

    @Test
    void aLooseRelaxationIsNotReportedAsTheExactCompatibleMatchingSize() {
        var source = disjoint(4, allConflicts(4));
        var bound = complete(source).bound().orElseThrow();
        assertEquals(2, bound.matchingSizeUpperBound());
        assertEquals(1, enumerate(source));
    }

    @Test
    void emptyGraphsRetainTheirIsolatedLeftVertices() {
        for (int left : List.of(0, 4)) {
            var source = new Request(new BipartiteMatching.Request(left, 2, List.of()), List.of());
            var bound = complete(source).bound().orElseThrow();
            assertEquals(ZERO, bound.fractionalUpperBound());
            assertEquals(0, bound.matchingSizeUpperBound());
            assertEquals(left, bound.guaranteedUnmatchedLeft());
        }
    }

    @Test
    void canonicalConflictsReferToTheCanonicalGraphEdges() {
        var graph = new BipartiteMatching.Request(2, 2,
            List.of(new Edge(1, 1), new Edge(0, 0), new Edge(1, 1)));
        var rows = new ArrayList<>(List.of(new Conflict(1, 0), new Conflict(0, 1)));
        var source = new Request(graph, rows);
        rows.clear();
        assertEquals(disjoint(2, List.of(new Conflict(0, 1))), source);
        assertEquals(1, complete(source).bound().orElseThrow().guaranteedUnmatchedLeft());
        assertThrows(IllegalArgumentException.class, () -> new Request(graph, List.of(new Conflict(0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new Request(graph, List.of(new Conflict(0, 2))));
        assertThrows(IllegalArgumentException.class, () -> new Request(graph, List.of(new Conflict(-1, 1))));
    }

    @Test
    void independentlyChecksWeightsAndRejectsTransplantedEvidence() {
        var source = disjoint(2, List.of(new Conflict(0, 1)));
        var proof = new Certificate(source, List.of(ZERO, ZERO), List.of(ZERO, ZERO), List.of(ONE));
        assertTrue(ConflictMatchingVerifier.verify(source, proof, LIMITS, () -> false).valid());
        assertInvalid(source, new Certificate(source, List.of(ZERO, ZERO), List.of(ZERO, ZERO), List.of(ZERO)));
        assertInvalid(source, new Certificate(source, List.of(ONE, ONE), List.of(ZERO, ZERO),
            List.of(ExactRational.NEGATIVE_ONE)));
        assertInvalid(source, new Certificate(source, List.of(ONE), List.of(ZERO, ZERO), List.of(ONE)));
        assertInvalid(disjoint(2, List.of()), proof);
        var changed = new Request(new BipartiteMatching.Request(2, 2,
            List.of(new Edge(0, 1), new Edge(1, 0))), source.conflicts());
        assertInvalid(changed, proof);
        assertFalse(ConflictMatchingVerifier.verify(source, null, LIMITS, () -> false).valid());
    }

    @Test
    void exhaustiveSmallGraphsNeverUnderestimateACompatibleMatching() {
        int cases = 0;
        for (int graphMask = 0; graphMask < 16; graphMask++) {
            var edges = new ArrayList<Edge>();
            for (int edge = 0; edge < 4; edge++) {
                if ((graphMask & (1 << edge)) != 0) edges.add(new Edge(edge / 2, edge % 2));
            }
            var pairs = allConflicts(edges.size());
            for (int conflictMask = 0; conflictMask < (1 << pairs.size()); conflictMask++) {
                var conflicts = new ArrayList<Conflict>();
                for (int pair = 0; pair < pairs.size(); pair++) {
                    if ((conflictMask & (1 << pair)) != 0) conflicts.add(pairs.get(pair));
                }
                var source = new Request(new BipartiteMatching.Request(2, 2, edges), conflicts);
                int actual = enumerate(source);
                int bound = complete(source).bound().orElseThrow().matchingSizeUpperBound();
                assertTrue(actual <= bound, source::toString);
                if (conflicts.isEmpty()) assertEquals(actual, bound);
                cases++;
            }
        }
        assertEquals(113, cases);
    }

    @Test
    void nestedWorkAndVerificationStayWithinOneGlobalAllowance() {
        var source = disjoint(3, allConflicts(3));
        var result = complete(source);
        var exact = new IterationLimits(result.work(), LIMITS.maximumTerms(), LIMITS.timeoutMillis());
        assertEquals(Status.VERIFIED, ConflictMatching.search(source, exact, () -> false).status());
        var shortBudget = new IterationLimits(result.work() - 1, LIMITS.maximumTerms(), LIMITS.timeoutMillis());
        var stopped = ConflictMatching.search(source, shortBudget, () -> false);
        assertEquals(Status.UNAVAILABLE, stopped.status());
        assertTrue(stopped.certificate().isEmpty());
        assertTrue(stopped.bound().isEmpty());
        assertTrue(stopped.work() <= shortBudget.maximumWork());
        var proof = result.certificate().orElseThrow();
        var replay = ConflictMatchingVerifier.verify(source, proof, LIMITS, () -> false);
        assertTrue(replay.valid());
        var replayShort = new IterationLimits(replay.work() - 1, LIMITS.maximumTerms(), LIMITS.timeoutMillis());
        assertEquals(Status.UNAVAILABLE,
            ConflictMatchingVerifier.verify(source, proof, replayShort, () -> false).status());
    }

    @Test
    void cancellationAndCellLimitsNeverPublishAPartialBound() {
        var source = disjoint(2, List.of(new Conflict(0, 1)));
        assertEquals(Status.UNAVAILABLE, ConflictMatching.search(source, LIMITS, () -> true).status());
        var tiny = new IterationLimits(100_000, 1, 10_000);
        assertEquals(Status.UNAVAILABLE, ConflictMatching.search(source, tiny, () -> false).status());
        var polls = new AtomicInteger();
        assertEquals(Status.VERIFIED, ConflictMatching.search(source, LIMITS,
            () -> { polls.incrementAndGet(); return false; }).status());
        int finalPoll = polls.get();
        var counter = new AtomicInteger();
        var stopped = ConflictMatching.search(source, LIMITS, () -> counter.incrementAndGet() >= finalPoll);
        assertEquals(Status.UNAVAILABLE, stopped.status());
        assertTrue(stopped.certificate().isEmpty());
        assertTrue(stopped.bound().isEmpty());
        var huge = new Request(new BipartiteMatching.Request(Integer.MAX_VALUE, 0, List.of()), List.of());
        assertEquals(Status.UNAVAILABLE, ConflictMatching.search(huge, LIMITS, () -> false).status());
    }

    private static ConflictMatching.Result complete(Request source) {
        var result = ConflictMatching.search(source, LIMITS, () -> false);
        assertEquals(Status.VERIFIED, result.status(), result::reason);
        var replay = ConflictMatchingVerifier.verify(source, result.certificate().orElseThrow(), LIMITS, () -> false);
        assertTrue(replay.valid(), replay::reason);
        assertEquals(result.bound(), replay.bound());
        return result;
    }

    private static void assertInvalid(Request source, Certificate proof) {
        assertEquals(Status.INVALID, ConflictMatchingVerifier.verify(source, proof, LIMITS, () -> false).status());
    }

    private static Request disjoint(int count, List<Conflict> conflicts) {
        var edges = new ArrayList<Edge>();
        for (int index = 0; index < count; index++) edges.add(new Edge(index, index));
        return new Request(new BipartiteMatching.Request(count, count, edges), conflicts);
    }

    private static List<Conflict> allConflicts(int count) {
        var pairs = new ArrayList<Conflict>();
        for (int first = 0; first < count; first++) {
            for (int second = first + 1; second < count; second++) pairs.add(new Conflict(first, second));
        }
        return pairs;
    }

    /** Independent integer oracle: enumerate subsets and test actual endpoint/conflict usage. */
    private static int enumerate(Request source) {
        int best = 0;
        for (int subset = 0; subset < (1 << source.graph().edges().size()); subset++) {
            var left = new boolean[source.graph().leftCount()];
            var right = new boolean[source.graph().rightCount()];
            boolean valid = true;
            for (int edge = 0; edge < source.graph().edges().size(); edge++) {
                if ((subset & (1 << edge)) == 0) continue;
                var selected = source.graph().edges().get(edge);
                if (left[selected.left()] || right[selected.right()]) valid = false;
                left[selected.left()] = true;
                right[selected.right()] = true;
            }
            for (var conflict : source.conflicts()) {
                if ((subset & (1 << conflict.first())) != 0 && (subset & (1 << conflict.second())) != 0) valid = false;
            }
            if (valid) best = Math.max(best, Integer.bitCount(subset));
        }
        return best;
    }
}
