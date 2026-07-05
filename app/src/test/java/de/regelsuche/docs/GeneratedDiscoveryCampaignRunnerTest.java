package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedDiscoveryCampaignRunnerTest {

    @Test
    void generatedCasesAreDeterministicAndCoverMultipleFamilies() {
        GeneratedDiscoveryCampaignRunner runner = new GeneratedDiscoveryCampaignRunner();

        GeneratedDiscoveryCampaignRunner.CampaignReport first = runner.run();
        GeneratedDiscoveryCampaignRunner.CampaignReport second = runner.run();

        assertEquals(first.cases(), second.cases());
        assertEquals(6, first.cases().size());
        Set<String> families = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (GeneratedDiscoveryCampaignRunner.GeneratedCase generatedCase : first.cases()) {
            families.add(generatedCase.family());
            assertTrue(ids.add(generatedCase.id()), "duplicate generated id: " + generatedCase.id());
            assertFalse(generatedCase.sourceSeedParameters().isEmpty(), generatedCase.id());
            assertFalse(generatedCase.operatorId().isBlank(), generatedCase.id());
            assertFalse(generatedCase.packId().isBlank(), generatedCase.id());
            assertFalse(generatedCase.rulePath().isEmpty(), generatedCase.id());
        }
        assertTrue(families.contains("perfect-square-expansion"));
        assertTrue(families.contains("rational-common-denominator"));
    }

    @Test
    void generatedCampaignWritesNormalDiscoveryReports(@TempDir Path tempDir) throws Exception {
        GeneratedDiscoveryCampaignRunner.CampaignReport report =
            new GeneratedDiscoveryCampaignRunner().writeReport(tempDir);

        assertEquals(6, report.cases().size());
        assertTrue(Files.exists(tempDir.resolve("generated-discovery-campaign.json")));
        assertTrue(Files.exists(tempDir.resolve("generated-discovery-campaign.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-candidates.json")));
        assertTrue(Files.exists(tempDir.resolve("discovery-candidates.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-candidate-store.json")));
        assertTrue(Files.exists(tempDir.resolve("discovery-candidate-store.md")));
        assertTrue(Files.exists(tempDir.resolve("pattern-hypotheses.json")));
        assertTrue(Files.exists(tempDir.resolve("pattern-hypotheses.md")));
        assertTrue(Files.exists(tempDir.resolve("operator-suggestions.md")));
        assertTrue(Files.exists(tempDir.resolve("public-evidence-gate.json")));
        assertTrue(Files.exists(tempDir.resolve("public-evidence-rejections.md")));

        String campaign = Files.readString(tempDir.resolve("generated-discovery-campaign.md"), StandardCharsets.UTF_8);
        assertTrue(campaign.contains("perfect-square-expansion"));
        assertTrue(campaign.contains("rational-common-denominator"));
        assertTrue(campaign.contains("nonZero"));
    }

    @Test
    void generatedCampaignFeedsCandidateStorePatternMiningAndGate(@TempDir Path tempDir) throws Exception {
        GeneratedDiscoveryCampaignRunner.CampaignReport report =
            new GeneratedDiscoveryCampaignRunner().writeReport(tempDir);

        DiscoveryCandidateStore.CandidateStoreReport store = new DiscoveryCandidateStore().build(report.promotionRecords());
        PatternHypothesisMiner.PatternHypothesisReport hypotheses = new PatternHypothesisMiner().mine(store);
        PublicEvidenceGate.GateReport gateReport = new PublicEvidenceGate().evaluate(report.promotionRecords());

        assertFalse(store.candidates().isEmpty());
        assertFalse(hypotheses.hypotheses().isEmpty(), "generated families should produce at least one generalized hypothesis");
        assertTrue(gateReport.acceptedCount() > 0, "some generated variants should pass the public evidence gate");
        assertTrue(gateReport.rejectedCount() > 0, "alpha-equivalent support cases should remain rejected for public evidence");
        assertTrue(gateReport.rejected().stream()
            .anyMatch(decision -> decision.rejectionReasons().stream().anyMatch(reason -> reason.startsWith("novelty="))));
    }
}
