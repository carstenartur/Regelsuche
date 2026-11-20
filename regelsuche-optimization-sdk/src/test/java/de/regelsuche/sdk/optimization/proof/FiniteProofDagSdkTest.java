package de.regelsuche.sdk.optimization.proof;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage;
import de.regelsuche.math.algorithms.proof.FiniteProofDag.*;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.modular.HunterCoverageSdk;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import static org.junit.jupiter.api.Assertions.*;

class FiniteProofDagSdkTest {
    private static final OptimizationBudget BUDGET = new OptimizationBudget(500_000, 100, 1, 10_000);
    private static final IterationLimits LIMITS = new IterationLimits(500_000, 100, 10_000);

    @Test
    void jsonRoundTripPreservesAllCoverageProofKindsAndArbitraryPrecision() throws Exception {
        var big = BigInteger.ONE.shiftLeft(83).add(ONE);
        var window = new CongruenceCoverage.Progression(big.negate(), ONE, big);
        var source = new CoverageSource(new CongruenceCoverage.Request(window, List.of()));
        var hunter = HunterCoverageSdk.bounds(source.request(), BUDGET, CancellationToken.NONE).certificate().orElseThrow();
        assertTrue(HunterCoverageSdk.verify(source.request(), hunter, BUDGET, CancellationToken.NONE).valid());
        var exact = CongruenceCoverage.search(source.request(), LIMITS, () -> false).certificate().orElseThrow();
        var proof = new Proof(List.of(new HunterLeaf(hunter), new ExactLeaf(exact), new Maximum(0, 1), new Exists(2)));
        var result = roundTrip(source, proof);
        assertEquals(new Existence(source), result);
        var covered = new CoverageSource(new CongruenceCoverage.Request(window,
            List.of(new CongruenceCoverage.Rule(ONE, ZERO, big.negate()))));
        var coveredLeaf = CongruenceCoverage.search(covered.request(), LIMITS, () -> false).certificate().orElseThrow();
        assertEquals(new LowerBound(covered, ZERO), roundTrip(covered, new Proof(List.of(new ExactLeaf(coveredLeaf)))));
        assertFalse(FiniteProofDagSdk.verify(covered, proof, BUDGET, CancellationToken.NONE).valid());
    }

    @Test
    void roundTripsHallEvidenceWithExplicitGraphSource() throws Exception {
        var graph = new HallSource(new BipartiteMatching.Request(3, 2,
            List.of(new BipartiteMatching.Edge(0, 1), new BipartiteMatching.Edge(2, 1))));
        var leaf = BipartiteMatching.search(graph.request(), LIMITS, () -> false).certificate().orElseThrow();
        assertEquals(new LowerBound(graph, BigInteger.TWO), roundTrip(graph, new Proof(List.of(new HallLeaf(leaf)))));
    }

    @Test
    void rejectsUnknownNodeKindsMissingPremisesAndMalformedOrOversizedPackets() {
        var badNodes = List.of("{\"kind\":\"any-assertion\",\"premise\":0}", "{\"kind\":\"exists\"}",
            "{\"kind\":\"exists\",\"premise\":null}", "{\"kind\":\"exists\",\"premise\":1.9}",
            "{\"kind\":\"exists\",\"premise\":0,\"premise\":2}");
        for (var node : badNodes) {
            var json = "{\"version\":1,\"proof\":{\"nodes\":[" + node + "]}}";
            assertThrows(JsonProcessingException.class, () -> FiniteProofDagJson.read(json));
        }
        assertThrows(IllegalArgumentException.class, () -> FiniteProofDagJson.read(
            "{\"version\":2,\"proof\":{\"nodes\":[]}}"));
        assertThrows(IllegalArgumentException.class, () -> FiniteProofDagJson.read(" ".repeat(2_000_001)));
    }

    @Test
    void stopsBeforeEncodingLaterLeavesWhenSharedEvidenceExceedsThePacketLimit() throws Exception {
        var conversions = new AtomicInteger();
        var observedFirst = new BigInteger("0") {
            @Override public String toString() {
                conversions.incrementAndGet();
                return super.toString();
            }
        };
        var tail = new CongruenceCoverage.Request(new CongruenceCoverage.Progression(observedFirst, ONE, ONE), List.of());
        var last = new HunterLeaf(HunterCoverageSdk.bounds(tail, BUDGET, CancellationToken.NONE).certificate().orElseThrow());
        FiniteProofDagJson.write(new Proof(List.of(last)));
        assertTrue(conversions.get() > 0);
        conversions.set(0);

        var plain = new CongruenceCoverage.Request(new CongruenceCoverage.Progression(ZERO, ONE, ONE), List.of());
        var repeated = new HunterLeaf(HunterCoverageSdk.bounds(plain, BUDGET, CancellationToken.NONE)
            .certificate().orElseThrow());
        var nodes = new ArrayList<Node>(Collections.nCopies(20_000, repeated));
        nodes.add(last);
        var failure = assertThrows(IllegalArgumentException.class, () -> FiniteProofDagJson.write(new Proof(nodes)));
        assertEquals("PROOF_TRANSPORT_SIZE_LIMIT", failure.getMessage());
        assertEquals(0, conversions.get(), "The emitter must stop before visiting the tail");
    }

    @Test
    void sdkPropagatesStopConditionsToSearchAndBothVerifiers() {
        var source = new CoverageSource(new CongruenceCoverage.Request(new CongruenceCoverage.Progression(ZERO, ONE, ONE),
            List.of(new CongruenceCoverage.Rule(ONE, ZERO, ZERO))));
        var certificate = HunterCoverageSdk.bounds(source.request(), BUDGET, CancellationToken.NONE).certificate().orElseThrow();
        var proof = new Proof(List.of(new HunterLeaf(certificate)));
        assertEquals("CANCELLED", FiniteProofDagSdk.verify(source, proof, BUDGET, () -> true).reason());
        assertTrue(HunterCoverageSdk.bounds(source.request(), BUDGET, () -> true).certificate().isEmpty());
        assertFalse(HunterCoverageSdk.verify(source.request(), certificate, BUDGET, () -> true).valid());
        var tiny = new OptimizationBudget(1, 100, 1, 10_000);
        assertTrue(HunterCoverageSdk.bounds(source.request(), tiny, CancellationToken.NONE).certificate().isEmpty());
        assertFalse(FiniteProofDagSdk.verify(source, proof, tiny, CancellationToken.NONE).valid());
    }

    private static Claim roundTrip(Source source, Proof proof) throws Exception {
        var json = FiniteProofDagJson.write(proof);
        var decoded = FiniteProofDagJson.read(json);
        assertEquals(proof, decoded);
        return FiniteProofDagSdk.verify(source, decoded, BUDGET, CancellationToken.NONE).conclusion().orElseThrow();
    }
}
