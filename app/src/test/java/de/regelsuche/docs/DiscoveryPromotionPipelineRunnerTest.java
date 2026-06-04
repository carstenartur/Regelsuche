package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        assertTrue(Files.exists(tempDir.resolve("discovery-backlog").resolve("blocked-candidates.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-backlog").resolve("operator-opportunities.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-backlog").resolve("macro-opportunities.md")));
        assertTrue(Files.exists(tempDir.resolve("promotion-dashboard.json")));
        assertTrue(Files.exists(tempDir.resolve("promotion-dashboard.md")));
        assertTrue(Files.exists(tempDir.resolve("gallery-2.0.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-details").resolve("README.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-4").resolve("discovery-campaign-4.json")));
        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-4").resolve("macro-reuse-report.md")));

        assertTrue(report.promotionRecords().stream()
            .anyMatch(record -> record.stage() == PromotionStage.REUSED));
        assertTrue(report.campaignFour().improvedCandidates() > 0);

        Set<String> candidateIds = report.registry().records().stream()
            .map(PromotionRecord::candidateId)
            .collect(Collectors.toSet());
        assertEquals(candidateIds.size(), report.registry().records().size());

        String dashboard = Files.readString(tempDir.resolve("promotion-dashboard.md"), StandardCharsets.UTF_8);
        assertTrue(dashboard.contains("Top promoted candidates"));
        assertTrue(dashboard.contains("Unresolved blockers"));

        String detailsIndex = Files.readString(tempDir.resolve("discovery-details").resolve("README.md"), StandardCharsets.UTF_8);
        assertTrue(detailsIndex.contains(".md)"));

        String gallery = Files.readString(tempDir.resolve("gallery-2.0.md"), StandardCharsets.UTF_8);
        List<String> galleryCandidates = gallery.lines()
            .filter(line -> line.startsWith("| ") && !line.contains("Candidate | Stage"))
            .filter(line -> !line.contains("---"))
            .map(line -> line.split("\\|")[1].trim())
            .toList();
        for (String candidateId : galleryCandidates) {
            assertTrue(report.promotionRecords().stream()
                .filter(entry -> entry.candidateId().equals(candidateId))
                .anyMatch(PromotionRecord::galleryEligible), candidateId);
        }
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
