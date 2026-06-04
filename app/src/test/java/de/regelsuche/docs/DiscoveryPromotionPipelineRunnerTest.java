package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryPromotionPipelineRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

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

        Map<String, Object> dashboardJson = JSON.readValue(
            Files.readString(tempDir.resolve("promotion-dashboard.json"), StandardCharsets.UTF_8),
            new TypeReference<LinkedHashMap<String, Object>>() { }
        );
        long observed = report.promotionRecords().size();
        long candidate = report.promotionRecords().stream()
            .filter(record -> record.stage().atLeast(PromotionStage.CANDIDATE))
            .count();
        long validated = report.promotionRecords().stream()
            .filter(record -> record.stage().atLeast(PromotionStage.VALIDATED))
            .count();
        long promoted = report.promotionRecords().stream()
            .filter(record -> record.stage().atLeast(PromotionStage.PROMOTED))
            .count();
        long reused = report.promotionRecords().stream()
            .filter(record -> record.stage().atLeast(PromotionStage.REUSED))
            .count();
        assertEquals(observed, ((Number) dashboardJson.get("observed")).longValue());
        assertEquals(candidate, ((Number) dashboardJson.get("candidate")).longValue());
        assertEquals(validated, ((Number) dashboardJson.get("validated")).longValue());
        assertEquals(promoted, ((Number) dashboardJson.get("promoted")).longValue());
        assertEquals(reused, ((Number) dashboardJson.get("reused")).longValue());
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

    @Test
    void galleryExcludesSyntheticNonEligibleRecords() {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();
        PromotionRecord eligible = new PromotionRecord(
            "eligible",
            "campaign",
            "2026-01-01",
            "substitution",
            PromotionStage.REUSED,
            "x + x",
            "2 * x",
            "AGREE",
            "ok",
            "DEGRADED",
            "operator",
            "pack",
            List.of("substitution.placeholder.A=x", "substitution.occurrences.A=2", "substitution.substituted=A + A"),
            "rationale",
            List.of("substitution_introduction"),
            true,
            List.of(),
            true,
            false,
            false,
            true,
            "macro.id",
            List.of("macro.id"),
            true,
            "discovery-campaign-4"
        );
        PromotionRecord blocked = new PromotionRecord(
            "blocked",
            "campaign",
            "2026-01-01",
            "substitution",
            PromotionStage.VALIDATED,
            "x + y",
            "x + y",
            "AGREE",
            "ok",
            "DEGRADED",
            "operator",
            "pack",
            List.of(),
            "rationale",
            List.of("fallback_rule"),
            false,
            List.of("fallback=true", "curated-path=true"),
            true,
            true,
            true,
            true,
            "",
            List.of(),
            false,
            ""
        );

        String gallery = runner.renderGallery(List.of(eligible, blocked));

        assertTrue(gallery.contains("| eligible |"));
        assertFalse(gallery.contains("| blocked |"));
    }

    @Test
    void detailReportPrioritizesEvidenceMappingsOverParenthesisHeuristics() {
        DiscoveryPromotionPipelineRunner runner = new DiscoveryPromotionPipelineRunner();
        PromotionRecord record = new PromotionRecord(
            "evidence-priority",
            "campaign",
            "2026-01-01",
            "substitution",
            PromotionStage.PROMOTED,
            "(x + 1) * z + (x + 1)",
            "A * z + A",
            "AGREE",
            "oracle",
            "DEGRADED",
            "substitution_introduction",
            "sympy-polynomial-basic",
            List.of(
                "substitution.placeholder.A=y + 2",
                "substitution.occurrences.A=2",
                "substitution.substituted=A * z + A",
                "substitution.expanded.A=true"
            ),
            "rationale",
            List.of("substitution_introduction", "complete_square_bridge"),
            true,
            List.of(),
            true,
            false,
            false,
            true,
            "",
            List.of(),
            false,
            ""
        );

        String details = runner.renderDetailReport(record);

        assertTrue(details.contains("Abstracted subexpression: A -> y + 2 (occurrences=2)"));
        assertTrue(details.contains("Placeholder mappings: A -> y + 2 (occurrences=2)"));
        assertTrue(details.contains("Substituted expression: A * z + A"));
        assertFalse(details.contains("Abstracted subexpression: (x + 1)"));
    }

}
