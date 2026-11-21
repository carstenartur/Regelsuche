package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.discovery.representation.TargetFreeRepresentationDiscoveryRun;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.radar.AstRuleRadarService;
import de.regelsuche.search.SearchHeuristic;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetainedCandidateEvidenceTest {
    @TempDir Path runs;
    @Test void contradictoryReviewPremisesRetainBothIndependentTypedWitnesses() {
        var guard = de.regelsuche.assumption.Assumption.nonZero("x");
        var unknown = CandidateGuardReview.evaluate(guard, java.util.List.of(), java.util.List.of());
        var positive = CandidateGuardReview.evaluate(guard, java.util.List.of(), java.util.List.of("x != 0"));
        var negative = CandidateGuardReview.evaluate(guard, java.util.List.of(), java.util.List.of("x = 0"));
        var conflict = CandidateGuardReview.evaluate(guard, java.util.List.of("x != 0"), java.util.List.of("x = 0"));
        assertEquals("UNKNOWN", unknown.required("result").textValue());
        assertEquals("TRUE", positive.required("result").textValue());
        assertEquals("FALSE", negative.required("result").textValue());
        assertEquals("UNKNOWN", conflict.required("result").textValue());
        assertTrue(conflict.required("conflicting").booleanValue());
        assertEquals(2, conflict.required("evidence").size());
    }

    @Test void frozenBridgeProducesAnExactLocalRadarAndAnExplicitlyScopedObligation() throws Exception {
        var bundle = TargetFreeRepresentationDiscoveryRun.run("b".repeat(40));
        RetainedCandidateDossierRepository.retain(runs, bundle.workspace(),
            bundle.scenario().toCanonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var search = bundle.scenario().content().search();
        var state = search.state(bundle.scenario().content().discoveredBridge().stateHash());
        var edge = search.transitions().stream().filter(e -> e.toStateHash().equals(state.stateHash())
            && e.fromStateHash().equals(state.parentStateHash()) && e.applicationKey().equals(state.incomingApplicationKey())).findFirst().orElseThrow();
        try (var radar = new AstRuleRadarService(new InMemoryRuleInventoryRepository(), new InMemoryExpressionGraphStore(), null)) {
            var receipt = RetainedCandidateEvidence.review(runs, bundle.workspace(), state.stateHash(), edge.sequence(), radar);
            assertEquals("REPRODUCED_EXACT_OCCURRENCE", receipt.required("radarStatus").textValue());
            var comparison = receipt.required("candidateComparison");
            assertEquals(bundle.workspace().plan().knownStructureCatalogHash(), comparison.required("catalogHash").textValue());
            assertTrue(comparison.at("/candidate/recognition/matches").size() > 0);
            assertEquals(state.expression(), comparison.at("/candidate/expression").asText());
            assertEquals(state.incomingRuleId(), receipt.at("/radar/ruleId").asText());
            assertEquals(state.expression(), receipt.at("/selectedStep/target").asText());
            assertTrue(receipt.required("occurrence").isArray());
            assertEquals("OUTSIDE_BOUNDED_POLYNOMIAL_FRAGMENT", receipt.required("verificationStatus").textValue());
            assertTrue(receipt.required("obligation").has("contentHash"));
        }
    }

    @Test void radarAndIndependentPolynomialCheckAreBoundToOneActualOccurrence() throws Exception {
        var result = TargetFreeRepresentationDiscoveryRun.runTargetFree("(x + 0) * 1",
            new SearchHeuristic(2,12,1,1,8,8), "a".repeat(40));
        result.retain(runs);
        var edge = result.artifact().content().transitions().stream()
            .filter(e -> !result.artifact().content().generations().get(e.generationSequence()).occurrencePath().isEmpty()).findFirst().orElseThrow();
        try (var radar = new AstRuleRadarService(new InMemoryRuleInventoryRepository(),new InMemoryExpressionGraphStore(),null)) {
            var receipt = RetainedCandidateEvidence.review(runs,result.workspace(),edge.toStateId(),edge.sequence(),radar);
            assertEquals(result.workspace().runId(),receipt.path("runId").asText());
            assertEquals(edge.toStateId(),receipt.path("candidateId").asText());
            assertTrue(receipt.at("/candidateComparison/source/oneStepSuccessors").size() > 0);
            assertTrue(receipt.at("/candidateComparison/source/semanticValueDigest").isTextual());
            assertTrue(receipt.at("/radar/candidateId").asText().startsWith("move:"));
            assertEquals("CONFIRMED",receipt.at("/verification/resultStatus").asText(),receipt::toPrettyString);
            assertEquals(receipt.path("sourceBindingHash").asText(), receipt.at("/obligation/provenance/revisionHash").asText());
            assertThrows(IllegalArgumentException.class,()->RetainedCandidateEvidence.review(runs,result.workspace(),
                result.artifact().content().states().getFirst().stateId(),edge.sequence(),radar));
        }
    }
}
