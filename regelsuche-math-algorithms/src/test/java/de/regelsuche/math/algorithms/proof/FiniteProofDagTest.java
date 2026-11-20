package de.regelsuche.math.algorithms.proof;

import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage;
import de.regelsuche.math.algorithms.modular.HunterCoverage;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

import static de.regelsuche.math.algorithms.proof.FiniteProofDag.*;
import static java.math.BigInteger.*;
import static org.junit.jupiter.api.Assertions.*;

class FiniteProofDagTest {
    static final IterationLimits LIMITS = new IterationLimits(1_000_000, 128, 10_000);
    static final CoverageSource SOURCE = new CoverageSource(new CongruenceCoverage.Request(
        new CongruenceCoverage.Progression(ZERO, ONE, valueOf(30)),
        List.of(new CongruenceCoverage.Rule(TWO, ZERO, ZERO), new CongruenceCoverage.Rule(valueOf(3), ZERO, ZERO),
            new CongruenceCoverage.Rule(valueOf(5), ZERO, ZERO))));
    static final HallSource GRAPH = new HallSource(new BipartiteMatching.Request(2, 1,
        List.of(new BipartiteMatching.Edge(0, 0), new BipartiteMatching.Edge(1, 0))));

    @Test
    void onlyReplayedCountsOfTheExpectedSourceCanBeJoined() {
        var exact = exact(SOURCE);
        var hunter = hunter(SOURCE);
        var proof = proof(hunter, exact, new Maximum(0, 1));
        assertEquals(new LowerBound(SOURCE, valueOf(8)), check(SOURCE, proof));
        var shared = proof(hunter, exact, new Maximum(0, 1), new Maximum(2, 0), new Exists(3));
        assertEquals(new Existence(SOURCE), check(SOURCE, shared));
        var changed = new CoverageSource(new CongruenceCoverage.Request(SOURCE.request().progression(), List.of()));
        assertInvalid(changed, proof);
        assertInvalid(SOURCE, proof(hunter, exact(changed), new Maximum(0, 1)));
        assertInvalid(GRAPH, proof(hunter));
        var cert = hunter.certificate();
        var fake = new HunterCoverage.Certificate(cert.source(), cert.singletonCounts(), cert.tree(), valueOf(9));
        assertInvalid(SOURCE, proof(new HunterLeaf(fake), new Exists(0)));
        var e = exact.certificate();
        assertInvalid(SOURCE, proof(new ExactLeaf(new CongruenceCoverage.Certificate(e.source(), e.terms(),
            ZERO, e.leastIndex())), new Exists(0)));
    }

    @Test
    void matchingDeficitHasOnlyTheGraphInterpretation() {
        var hall = new HallLeaf(BipartiteMatching.search(GRAPH.request(), LIMITS, () -> false)
            .certificate().orElseThrow());
        assertEquals(new LowerBound(GRAPH, ONE), check(GRAPH, proof(hall)));
        assertEquals(new Existence(GRAPH), check(GRAPH, proof(hall, new Exists(0))));
        var changed = new HallSource(new BipartiteMatching.Request(2, 1, List.of()));
        assertInvalid(changed, proof(hall));
        assertInvalid(SOURCE, proof(hall, hunter(SOURCE), new Maximum(0, 1)));
        assertInvalid(GRAPH, proof(new HallLeaf(new BipartiteMatching.Certificate(
            GRAPH.request(), List.of(), List.of(0, 1))), new Exists(0)));
    }

    @Test
    void rejectsBackwardCyclesMissingReferencesAndNonpositiveOrMistypedPremises() {
        var leaf = hunter(SOURCE);
        for (var bad : List.of(proof(), proof(new Exists(0)), proof(new Exists(1), new Exists(0)),
                proof(leaf, new Exists(-1)), proof(leaf, new Exists(7)), proof(leaf, new Exists(0), new Exists(1)),
                proof(leaf, new Exists(0), new Maximum(0, 1)), proof(leaf, new Maximum(0, 2)))) {
            assertInvalid(SOURCE, bad);
        }
        var blocked = new CoverageSource(new CongruenceCoverage.Request(SOURCE.request().progression(),
            List.of(new CongruenceCoverage.Rule(ONE, ZERO, ZERO))));
        assertInvalid(blocked, proof(hunter(blocked), new Exists(0)));
    }

    @Test
    void sharesOneWorkBudgetAcrossLeavesAndRejectsInterruptedReplay() {
        var leaf = hunter(SOURCE);
        var one = verify(SOURCE, proof(leaf), LIMITS, () -> false);
        assertTrue(one.valid());
        var proof = proof(leaf, leaf, new Maximum(0, 1));
        var limited = verify(SOURCE, proof, LIMITS.withMaximumWork(one.work() + 2), () -> false);
        assertFalse(limited.valid());
        assertEquals("WORK_LIMIT", limited.reason());
        assertTrue(limited.conclusion().isEmpty());
        assertEquals("TERM_LIMIT", verify(SOURCE, proof, new IterationLimits(1_000_000, 2, 10_000),
            () -> false).reason());
        assertEquals("CANCELLED", verify(SOURCE, proof, LIMITS, () -> true).reason());
    }

    private static ExactLeaf exact(CoverageSource source) {
        return new ExactLeaf(CongruenceCoverage.search(source.request(), LIMITS, () -> false)
            .certificate().orElseThrow());
    }

    private static HunterLeaf hunter(CoverageSource source) {
        return new HunterLeaf(HunterCoverage.bounds(source.request(), LIMITS, () -> false)
            .certificate().orElseThrow());
    }

    private static Proof proof(Node... nodes) { return new Proof(List.of(nodes)); }
    private static Claim check(Source source, Proof proof) {
        return verify(source, proof, LIMITS, () -> false).conclusion().orElseThrow();
    }
    private static void assertInvalid(Source source, Proof proof) {
        var result = verify(source, proof, LIMITS, () -> false);
        assertFalse(result.valid());
        assertTrue(result.conclusion().isEmpty());
    }
}
