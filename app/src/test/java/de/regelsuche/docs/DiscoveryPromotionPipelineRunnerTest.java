package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryPromotionPipelineRunnerTest {
    @Test
    void pipelineWritesClosedLoopPromotionArtifacts(@TempDir Path tempDir) throws Exception {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();

        DiscoveryPromotionPipelineRunner.PipelineReport report = runner.writeReport(tempDir);

        assertTrue(Files.exists(tempDir.resolve("promotion-records.json")));
        assertTrue(Files.exists(tempDir.resolve("promotion-registry.json")));
        assertTrue(Files.exists(tempDir.resolve("promotion-history.md")));
        assertTrue(Files.exists(tempDir.resolve("campaign-metrics.json")));
        assertTrue(Files.exists(tempDir.resolveSibling("discovery-backlog").resolve("blocked-candidates.md")));
        assertTrue(Files.exists(tempDir.resolveSibling("discovery-backlog").resolve("operator-opportunities.md")));
        assertTrue(Files.exists(tempDir.resolveSibling("discovery-backlog").resolve("macro-opportunities.md")));
        assertTrue(Files.exists(tempDir.resolveSibling("discovery-campaign-4").resolve("discovery-campaign-4.json")));
        assertTrue(Files.exists(tempDir.resolveSibling("discovery-campaign-4").resolve("macro-reuse-report.md")));

        assertTrue(report.promotionRecords().stream()
            .anyMatch(record -> record.stage() == PromotionStage.REUSED));
        assertTrue(report.campaignFour().improvedCandidates() > 0);

        Set<String> candidateIds = report.registry().records().stream()
            .map(PromotionRecord::candidateId)
            .collect(Collectors.toSet());
        assertEquals(candidateIds.size(), report.registry().records().size());
    }

    @Test
    void promotionRegistryIsDeterministicAndRerunsDoNotCreateDuplicates(@TempDir Path tempDir) throws Exception {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");

        runner.writeReport(first);
        runner.writeReport(second);
        runner.writeReport(first);

        String firstRegistry = Files.readString(first.resolve("promotion-registry.json"), StandardCharsets.UTF_8);
        String secondRegistry = Files.readString(second.resolve("promotion-registry.json"), StandardCharsets.UTF_8);
        String firstHistory = Files.readString(first.resolve("promotion-history.md"), StandardCharsets.UTF_8);

        assertEquals(firstRegistry, secondRegistry);
        assertTrue(firstHistory.contains("complete-square-family"));
        assertTrue(firstRegistry.indexOf("\"candidateId\" : \"complete-square-family\"")
            == firstRegistry.lastIndexOf("\"candidateId\" : \"complete-square-family\""));
    }
}
